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
import com.morpheusdata.model.NetworkSubnet
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.request.UpdateModel
import com.morpheusdata.response.ServiceResponse
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
        nic.network.is(targetNetwork)
        nic.subnet == null
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
