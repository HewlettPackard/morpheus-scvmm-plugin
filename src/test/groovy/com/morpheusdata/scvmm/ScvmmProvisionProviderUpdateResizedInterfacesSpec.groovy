// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.compute.MorpheusComputeServerInterfaceService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkSubnetService
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.NetworkPoolIp
import com.morpheusdata.model.NetworkPoolType
import com.morpheusdata.model.NetworkSubnet
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.request.UpdateModel
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.scvmm.helper.ReconfigurePoolIpHelper
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Subject

class ScvmmProvisionProviderUpdateResizedInterfacesSpec extends Specification {

    static final String VM_ID = 'vm-ext-id'
    static final Map SCVMM_OPTS = [sshHost: 'scvmm.local']

    MorpheusContext context = Mock()
    MorpheusServices services = Mock()
    MorpheusSynchronousNetworkSubnetService subnetService = Mock()
    MorpheusSynchronousCloudService cloudService = Mock()
    MorpheusSynchronousNetworkService networkService = Mock()
    MorpheusAsyncServices asyncServices = Mock()
    MorpheusComputeServerService computeServerService = Mock()
    MorpheusComputeServerInterfaceService interfaceService = Mock()
    ScvmmApiService apiService = Mock()
    ReconfigurePoolIpHelper poolIpHelper = Mock()

    @Subject
    ScvmmProvisionProvider provider

    Network currentNetwork = new Network(id: 10L, externalId: 'net-current', vlanId: null)
    Network targetNetwork = new Network(id: 20L, externalId: 'net-target', vlanId: null)
    ComputeServerInterface nic
    ComputeServer server

    def setup() {
        context.services >> services
        services.networkSubnet >> subnetService
        services.cloud >> cloudService
        cloudService.network >> networkService
        context.async >> asyncServices
        asyncServices.computeServer >> computeServerService
        computeServerService.computeServerInterface >> interfaceService

        provider = new ScvmmProvisionProvider(null, context)
        provider.apiService = apiService
        provider.poolIpHelper = poolIpHelper
        poolIpHelper.findLeases(_, _) >> []
        poolIpHelper.releaseLeases(_, _ as List) >> [success: true]
        poolIpHelper.releaseLeases(_, _ as ComputeServer, _) >> [success: true]

        nic = new ComputeServerInterface(id: 1L, externalId: 'adapter-1', macAddress: '00:15:5D:00:00:01', network: currentNetwork)
        server = new ComputeServer(id: 100L)
        server.interfaces = [nic]
    }

    private static ResizeRequest resizeRequestFor(List<UpdateModel<ComputeServerInterface>> updates) {
        ResizeRequest rr = new ResizeRequest()
        rr.interfacesUpdate = updates
        return rr
    }

    private static UpdateModel<ComputeServerInterface> update(ComputeServerInterface existing, Map props) {
        new UpdateModel<ComputeServerInterface>(existing, props)
    }

    def "canReconfigureNetwork is enabled for SCVMM"() {
        expect:
        provider.canReconfigureNetwork()
    }

    def "returns success and does nothing when there are no interface updates"() {
        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID, resizeRequestFor(updates))

        then:
        rtn.success
        0 * apiService.updateNetworkInterface(*_)
        0 * interfaceService.save(*_)

        where:
        updates << [null, []]
    }

    def "skips entries without an existing interface model"() {
        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(null, [network: [id: 20L]])]))

        then:
        rtn.success
        0 * apiService.updateNetworkInterface(*_)
    }

    def "skips entries that do not request a network or subnet (e.g. connected toggle only)"() {
        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(nic, props)]))

        then:
        rtn.success
        0 * networkService.get(_)
        0 * apiService.updateNetworkInterface(*_)

        where:
        props << [[:], [connected: false], [network: [:]], [network: [id: null, subnet: null]]]
    }

    def "skips the NIC when it is already on the requested network and subnet"() {
        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(nic, [network: [id: '10']])]))

        then:
        rtn.success
        0 * networkService.get(_)
        0 * apiService.updateNetworkInterface(*_)
        0 * interfaceService.save(*_)
    }

    def "moves a NIC to another (non-VLAN) network and persists the change"() {
        given:
        Map capturedProps

        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(nic, [id: 1, network: [id: '20'], row: 0])]))

        then:
        1 * networkService.get(20L) >> targetNetwork
        0 * subnetService.get(_)
        1 * apiService.updateNetworkInterface(SCVMM_OPTS, VM_ID, _ as Map) >> { o, v, Map props ->
            capturedProps = props
            [success: true]
        }
        1 * interfaceService.save({ List l -> l.size() == 1 && l[0].is(nic) }) >> Single.just(true)

        and:
        rtn.success
        capturedProps.adapterId == 'adapter-1'
        capturedProps.macAddress == '00:15:5D:00:00:01'
        capturedProps.networkExternalId == 'net-target'
        capturedProps.subnetExternalId == null
        capturedProps.vlanEnabled == false
        capturedProps.vlanId == null
        // target has no IP pool, so the adapter is switched to DHCP (SCVMM error 15046 otherwise)
        capturedProps.ipv4AddressType == 'Dynamic'
        nic.network.is(targetNetwork)
        nic.subnet == null
        nic.dhcp == true
    }

    def "moving a NIC onto a network with an SCVMM IP pool leases an address, binds it Static and records the lease"() {
        given:
        NetworkPool pool = new NetworkPool(id: 5L, externalId: 'pool-1', name: 'Pool 1', type: new NetworkPoolType(code: 'scvmm'))
        targetNetwork.pool = pool
        NetworkPoolIp grant = new NetworkPoolIp(ipAddress: '10.0.0.50', externalId: 'ip-50', networkPool: pool)
        Map capturedProps

        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(nic, [network: [id: '20'], ipMode: 'pool'])]))

        then:
        1 * networkService.get(20L) >> targetNetwork
        1 * poolIpHelper.findLeases(server, nic) >> []
        1 * poolIpHelper.resolveScvmmPool(targetNetwork) >> pool
        1 * poolIpHelper.leasePoolIp(SCVMM_OPTS, pool, targetNetwork, server, nic, null) >> [success: true, poolIp: grant]
        1 * apiService.updateNetworkInterface(SCVMM_OPTS, VM_ID, _ as Map) >> { o, v, Map props ->
            capturedProps = props
            [success: true]
        }
        1 * poolIpHelper.persistLease(grant) >> true
        1 * poolIpHelper.releaseLeases(SCVMM_OPTS, []) >> [success: true]
        0 * poolIpHelper.revokeLease(*_)
        1 * interfaceService.save(_) >> Single.just(true)

        and:
        rtn.success
        capturedProps.ipv4AddressType == 'Static'
        capturedProps.ipAddress == '10.0.0.50'
        capturedProps.poolExternalId == 'pool-1'
        nic.network.is(targetNetwork)
        nic.ipAddress == '10.0.0.50'
        nic.dhcp == false
        nic.poolAssigned == true
        nic.networkPool.is(pool)
        nic.ipMode == 'pool'
    }

    def "a static row with a user-entered address asks the pool for that specific address"() {
        given:
        NetworkPool pool = new NetworkPool(id: 5L, externalId: 'pool-1', type: new NetworkPoolType(code: 'scvmm'))
        targetNetwork.pool = pool
        NetworkPoolIp grant = new NetworkPoolIp(ipAddress: '10.0.0.77', externalId: 'ip-77', networkPool: pool)

        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(nic, [network: [id: '20', ipMode: 'static', ipAddress: ' 10.0.0.77 ']])]))

        then:
        1 * networkService.get(20L) >> targetNetwork
        1 * poolIpHelper.findLeases(server, nic) >> []
        1 * poolIpHelper.resolveScvmmPool(targetNetwork) >> pool
        1 * poolIpHelper.leasePoolIp(SCVMM_OPTS, pool, targetNetwork, server, nic, '10.0.0.77') >> [success: true, poolIp: grant]
        1 * apiService.updateNetworkInterface(SCVMM_OPTS, VM_ID, { Map p -> p.ipAddress == '10.0.0.77' && p.ipv4AddressType == 'Static' }) >> [success: true]
        1 * poolIpHelper.persistLease(grant) >> true
        1 * poolIpHelper.releaseLeases(SCVMM_OPTS, []) >> [success: true]
        1 * interfaceService.save(_) >> Single.just(true)
        rtn.success
        nic.ipAddress == '10.0.0.77'
    }

    def "a dhcp row on a pool-backed network does not lease and switches the adapter to Dynamic"() {
        given:
        NetworkPool pool = new NetworkPool(id: 5L, externalId: 'pool-1', type: new NetworkPoolType(code: 'scvmm'))
        targetNetwork.pool = pool
        Map capturedProps

        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(nic, [network: [id: '20'], ipMode: 'dhcp'])]))

        then:
        1 * networkService.get(20L) >> targetNetwork
        1 * poolIpHelper.findLeases(server, nic) >> []
        1 * poolIpHelper.resolveScvmmPool(targetNetwork) >> pool
        0 * poolIpHelper.leasePoolIp(*_)
        1 * apiService.updateNetworkInterface(SCVMM_OPTS, VM_ID, _ as Map) >> { o, v, Map props ->
            capturedProps = props
            [success: true]
        }
        1 * poolIpHelper.releaseLeases(SCVMM_OPTS, []) >> [success: true]
        1 * interfaceService.save(_) >> Single.just(true)
        rtn.success
        capturedProps.ipv4AddressType == 'Dynamic'
        capturedProps.ipAddress == null
        capturedProps.poolExternalId == null
        nic.dhcp == true
        nic.ipMode == 'dhcp'
    }

    def "moving between two pool networks releases the old lease only after the new one is bound"() {
        given:
        NetworkPool oldPool = new NetworkPool(id: 4L, externalId: 'pool-old', type: new NetworkPoolType(code: 'scvmm'))
        NetworkPool newPool = new NetworkPool(id: 5L, externalId: 'pool-new', type: new NetworkPoolType(code: 'scvmm'))
        currentNetwork.pool = oldPool
        targetNetwork.pool = newPool
        NetworkPoolIp oldLease = new NetworkPoolIp(id: 900L, ipAddress: '10.0.0.5', externalId: 'ip-5', networkPool: oldPool)
        NetworkPoolIp grant = new NetworkPoolIp(ipAddress: '10.1.0.5', externalId: 'ip-new', networkPool: newPool)
        List<String> order = []

        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(nic, [network: [id: '20'], ipMode: 'pool'])]))

        then:
        1 * networkService.get(20L) >> targetNetwork
        1 * poolIpHelper.findLeases(server, nic) >> [oldLease]
        1 * poolIpHelper.resolveScvmmPool(targetNetwork) >> newPool
        1 * poolIpHelper.leasePoolIp(SCVMM_OPTS, newPool, targetNetwork, server, nic, null) >> { order << 'lease'; [success: true, poolIp: grant] }
        1 * apiService.updateNetworkInterface(SCVMM_OPTS, VM_ID, _ as Map) >> { order << 'bind'; [success: true] }
        1 * poolIpHelper.persistLease(grant) >> { order << 'persist'; true }
        1 * poolIpHelper.releaseLeases(SCVMM_OPTS, [oldLease]) >> { order << 'release'; [success: true] }
        1 * interfaceService.save(_) >> Single.just(true)
        rtn.success
        order == ['lease', 'bind', 'persist', 'release']
        nic.ipAddress == '10.1.0.5'
        nic.networkPool.is(newPool)
    }

    def "fails and stops when the pool has no free address"() {
        given:
        NetworkPool pool = new NetworkPool(id: 5L, externalId: 'pool-1', name: 'Pool 1', type: new NetworkPoolType(code: 'scvmm'))
        targetNetwork.pool = pool

        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(nic, [network: [id: '20']])]))

        then:
        1 * networkService.get(20L) >> targetNetwork
        1 * poolIpHelper.findLeases(server, nic) >> []
        1 * poolIpHelper.resolveScvmmPool(targetNetwork) >> pool
        1 * poolIpHelper.leasePoolIp(SCVMM_OPTS, pool, targetNetwork, server, nic, null) >> [success: false, error: 'No free IP in pool']
        0 * apiService.updateNetworkInterface(*_)
        0 * poolIpHelper.releaseLeases(*_)
        0 * interfaceService.save(_)
        !rtn.success
        rtn.error == 'No free IP in pool'
        nic.network.is(currentNetwork)
    }

    def "revokes the new grant and keeps the old lease when SCVMM rejects the bind"() {
        given:
        NetworkPool pool = new NetworkPool(id: 5L, externalId: 'pool-1', type: new NetworkPoolType(code: 'scvmm'))
        targetNetwork.pool = pool
        NetworkPoolIp oldLease = new NetworkPoolIp(id: 900L, ipAddress: '10.0.0.5')
        NetworkPoolIp grant = new NetworkPoolIp(ipAddress: '10.1.0.5', externalId: 'ip-new', networkPool: pool)

        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(nic, [network: [id: '20']])]))

        then:
        1 * networkService.get(20L) >> targetNetwork
        1 * poolIpHelper.findLeases(server, nic) >> [oldLease]
        1 * poolIpHelper.resolveScvmmPool(targetNetwork) >> pool
        1 * poolIpHelper.leasePoolIp(*_) >> [success: true, poolIp: grant]
        1 * apiService.updateNetworkInterface(SCVMM_OPTS, VM_ID, _ as Map) >> [success: false, error: 'Error 15046']
        1 * poolIpHelper.revokeLease(SCVMM_OPTS, pool, grant) >> [success: true]
        0 * poolIpHelper.persistLease(_)
        0 * poolIpHelper.releaseLeases(*_)
        0 * interfaceService.save(_)
        !rtn.success
        rtn.error == 'Error 15046'
        nic.network.is(currentNetwork)
    }

    def "moving a static NIC from a pool network to a DHCP network switches it to Dynamic, marks it dhcp and releases its lease"() {
        given:
        NetworkPool oldPool = new NetworkPool(id: 5L, externalId: 'pool-1', type: new NetworkPoolType(code: 'scvmm'))
        currentNetwork.pool = oldPool
        NetworkPoolIp oldLease = new NetworkPoolIp(id: 900L, ipAddress: '10.0.0.5', externalId: 'ip-5', networkPool: oldPool)
        nic.dhcp = false
        nic.ipAddress = '10.0.0.5'
        nic.poolAssigned = true
        nic.networkPool = oldPool
        Map capturedProps

        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(nic, [network: [id: '20']])]))

        then:
        1 * networkService.get(20L) >> targetNetwork
        1 * poolIpHelper.findLeases(server, nic) >> [oldLease]
        1 * poolIpHelper.resolveScvmmPool(targetNetwork) >> null
        0 * poolIpHelper.leasePoolIp(*_)
        1 * apiService.updateNetworkInterface(SCVMM_OPTS, VM_ID, _ as Map) >> { o, v, Map props ->
            capturedProps = props
            [success: true]
        }
        1 * poolIpHelper.releaseLeases(SCVMM_OPTS, [oldLease]) >> [success: true]
        1 * interfaceService.save(_) >> Single.just(true)

        and:
        rtn.success
        capturedProps.ipv4AddressType == 'Dynamic'
        nic.dhcp == true
        nic.poolAssigned == false
        nic.networkPool == null
        nic.ipMode == 'dhcp'
    }

    def "a VLAN network enables the VLAN only when the VLAN id is positive"() {
        given:
        targetNetwork.vlanId = vlanId
        Map capturedProps

        when:
        provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID, resizeRequestFor([update(nic, [network: [id: 20L]])]))

        then:
        1 * networkService.get(20L) >> targetNetwork
        1 * apiService.updateNetworkInterface(SCVMM_OPTS, VM_ID, _ as Map) >> { o, v, Map props -> capturedProps = props; [success: true] }
        1 * interfaceService.save(_ as List) >> Single.just(true)
        capturedProps.vlanEnabled == expectedEnabled
        capturedProps.vlanId == vlanId

        where:
        vlanId | expectedEnabled
        0      | false
        120    | true
    }

    def "a subnet-only selection resolves the parent network, passes the subnet and its VLAN, and persists both"() {
        given:
        NetworkSubnet subnet = new NetworkSubnet(id: 300L, externalId: 'subnet-ext', vlanId: 55, networkId: 20L)
        targetNetwork.vlanId = 99 // subnet VLAN must take precedence
        Map capturedProps

        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(nic, [network: [subnet: '300']])]))

        then:
        1 * subnetService.get(300L) >> subnet
        1 * networkService.get(20L) >> targetNetwork
        1 * apiService.updateNetworkInterface(SCVMM_OPTS, VM_ID, _ as Map) >> { o, v, Map props -> capturedProps = props; [success: true] }
        1 * interfaceService.save(_ as List) >> Single.just(true)

        and:
        rtn.success
        capturedProps.networkExternalId == 'net-target'
        capturedProps.subnetExternalId == 'subnet-ext'
        capturedProps.vlanEnabled == true
        capturedProps.vlanId == 55
        nic.network.is(targetNetwork)
        nic.subnet.is(subnet)
    }

    def "an explicit network plus subnet selection uses the given network id and the subnet"() {
        given:
        NetworkSubnet subnet = new NetworkSubnet(id: 300L, externalId: 'subnet-ext', vlanId: null, networkId: 20L)
        Map capturedProps

        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(nic, [network: [id: 20L, subnet: 300L]])]))

        then:
        1 * subnetService.get(300L) >> subnet
        1 * networkService.get(20L) >> targetNetwork
        1 * apiService.updateNetworkInterface(SCVMM_OPTS, VM_ID, _ as Map) >> { o, v, Map props -> capturedProps = props; [success: true] }
        1 * interfaceService.save(_ as List) >> Single.just(true)
        rtn.success
        capturedProps.subnetExternalId == 'subnet-ext'
        capturedProps.vlanEnabled == false
    }

    def "changing only the subnet on the same network is treated as a change"() {
        given:
        NetworkSubnet subnet = new NetworkSubnet(id: 300L, externalId: 'subnet-ext', networkId: 10L)

        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(nic, [network: [id: 10L, subnet: 300L]])]))

        then:
        1 * subnetService.get(300L) >> subnet
        1 * networkService.get(10L) >> currentNetwork
        1 * apiService.updateNetworkInterface(SCVMM_OPTS, VM_ID, { Map p -> p.subnetExternalId == 'subnet-ext' }) >> [success: true]
        1 * interfaceService.save(_ as List) >> Single.just(true)
        rtn.success
        nic.subnet.is(subnet)
    }

    def "fails without calling SCVMM when the target network cannot be found"() {
        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(nic, [network: [id: 999L]])]))

        then:
        1 * networkService.get(999L) >> null
        0 * apiService.updateNetworkInterface(*_)
        0 * interfaceService.save(*_)
        !rtn.success
        rtn.error == 'Unable to find network 999 for NIC 1 update'
        nic.network.is(currentNetwork)
    }

    def "fails without calling SCVMM when the target subnet cannot be found"() {
        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(nic, [network: [subnet: 999L]])]))

        then:
        1 * subnetService.get(999L) >> null
        0 * networkService.get(_)
        0 * apiService.updateNetworkInterface(*_)
        !rtn.success
        rtn.error == 'Unable to find subnet 999 for NIC 1 update'
    }

    def "reports the SCVMM error, does not persist, and stops processing further NICs on failure"() {
        given:
        ComputeServerInterface nic2 = new ComputeServerInterface(id: 2L, externalId: 'adapter-2', network: currentNetwork)
        server.interfaces = [nic, nic2]

        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID, resizeRequestFor([
                update(nic, [network: [id: 20L]]),
                update(nic2, [network: [id: 20L]])
        ]))

        then:
        1 * networkService.get(20L) >> targetNetwork
        1 * apiService.updateNetworkInterface(SCVMM_OPTS, VM_ID, { Map p -> p.adapterId == 'adapter-1' }) >> [success: false, error: 'Network adapter not found']
        0 * apiService.updateNetworkInterface(SCVMM_OPTS, VM_ID, { Map p -> p.adapterId == 'adapter-2' })
        0 * interfaceService.save(*_)

        and:
        !rtn.success
        rtn.error == 'Network adapter not found'
        nic.network.is(currentNetwork)
        nic2.network.is(currentNetwork)
    }

    def "uses a default error message when SCVMM fails without one"() {
        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(nic, [network: [id: 20L]])]))

        then:
        1 * networkService.get(20L) >> targetNetwork
        1 * apiService.updateNetworkInterface(*_) >> [success: false]
        !rtn.success
        rtn.error == 'Failed to update network for NIC 1'
    }

    def "updates every NIC when several are changed"() {
        given:
        ComputeServerInterface nic2 = new ComputeServerInterface(id: 2L, externalId: 'adapter-2', network: currentNetwork)
        server.interfaces = [nic, nic2]
        Network otherNetwork = new Network(id: 30L, externalId: 'net-other')

        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID, resizeRequestFor([
                update(nic, [network: [id: 20L]]),
                update(nic2, [network: [id: 30L]])
        ]))

        then:
        1 * networkService.get(20L) >> targetNetwork
        1 * networkService.get(30L) >> otherNetwork
        1 * apiService.updateNetworkInterface(SCVMM_OPTS, VM_ID, { Map p -> p.adapterId == 'adapter-1' && p.networkExternalId == 'net-target' }) >> [success: true]
        1 * apiService.updateNetworkInterface(SCVMM_OPTS, VM_ID, { Map p -> p.adapterId == 'adapter-2' && p.networkExternalId == 'net-other' }) >> [success: true]
        2 * interfaceService.save(_ as List) >> Single.just(true)
        rtn.success
        nic.network.is(targetNetwork)
        nic2.network.is(otherNetwork)
    }

    def "still succeeds (with a warning) when the updated NIC is not present on the loaded server"() {
        given:
        ComputeServerInterface detached = new ComputeServerInterface(id: 42L, externalId: 'adapter-42', network: currentNetwork)

        when:
        ServiceResponse rtn = provider.updateResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                resizeRequestFor([update(detached, [network: [id: 20L]])]))

        then:
        1 * networkService.get(20L) >> targetNetwork
        1 * apiService.updateNetworkInterface(*_) >> [success: true]
        0 * interfaceService.save(*_)
        rtn.success
    }
}
