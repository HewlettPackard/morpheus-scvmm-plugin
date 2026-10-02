// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.compute.MorpheusComputeServerInterfaceService
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
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

/**
 * Covers adding and removing NICs on reconfigure (interfacesAdd / interfacesDelete) and the
 * orchestration order remove -> update -> add in reconfigureResizedInterfaces.
 */
class ScvmmProvisionProviderAddRemoveResizedInterfacesSpec extends Specification {

    static final String VM_ID = 'vm-ext-id'
    static final Map SCVMM_OPTS = [sshHost: 'scvmm.local']

    MorpheusContext context = Mock()
    MorpheusServices services = Mock()
    MorpheusSynchronousNetworkSubnetService subnetService = Mock()
    MorpheusSynchronousCloudService cloudService = Mock()
    MorpheusSynchronousNetworkService networkService = Mock()
    MorpheusSynchronousComputeServerService syncComputeServerService = Mock()
    MorpheusAsyncServices asyncServices = Mock()
    MorpheusComputeServerService computeServerService = Mock()
    MorpheusComputeServerInterfaceService interfaceService = Mock()
    ScvmmApiService apiService = Mock()

    @Subject
    ScvmmProvisionProvider provider

    Network currentNetwork = new Network(id: 10L, externalId: 'net-current', vlanId: null)
    Network targetNetwork = new Network(id: 20L, externalId: 'net-target', vlanId: null)
    Network vlanNetwork = new Network(id: 30L, externalId: 'net-vlan.200', vlanId: 200)
    NetworkSubnet subnet = new NetworkSubnet(id: 300L, externalId: 'subnet-ext', vlanId: 55, networkId: 20L)
    ComputeServerInterface primaryNic
    ComputeServerInterface secondNic
    ComputeServer server

    def setup() {
        context.services >> services
        services.networkSubnet >> subnetService
        services.cloud >> cloudService
        services.computeServer >> syncComputeServerService
        cloudService.network >> networkService
        context.async >> asyncServices
        asyncServices.computeServer >> computeServerService
        computeServerService.computeServerInterface >> interfaceService

        provider = new ScvmmProvisionProvider(null, context)
        provider.apiService = apiService

        primaryNic = new ComputeServerInterface(id: 1L, name: 'eth0', externalId: 'adapter-1', macAddress: '00:15:5D:00:00:01',
                network: currentNetwork, primaryInterface: true, displayOrder: 1)
        secondNic = new ComputeServerInterface(id: 2L, name: 'eth1', externalId: 'adapter-2', macAddress: '00:15:5D:00:00:02',
                network: currentNetwork, primaryInterface: false, displayOrder: 2)
        server = new ComputeServer(id: 100L)
        server.interfaces = [primaryNic, secondNic]
        // getMorpheusServer reloads the server; return the same in-memory server by default
        syncComputeServerService.find(_ as DataQuery) >> { server }
    }

    private static ResizeRequest addRequest(List<Map> adds) {
        ResizeRequest rr = new ResizeRequest()
        rr.interfacesAdd = adds
        return rr
    }

    private static ResizeRequest deleteRequest(List<ComputeServerInterface> deletes) {
        ResizeRequest rr = new ResizeRequest()
        rr.interfacesDelete = deletes
        return rr
    }

    // ---------------------------------------------------------------- addResizedInterfaces

    def "add: returns success and does nothing when there are no interfaces to add"() {
        when:
        ServiceResponse rtn = provider.addResizedInterfaces(server, SCVMM_OPTS, VM_ID, addRequest(adds))

        then:
        rtn.success
        0 * apiService.addNetworkInterface(*_)
        0 * interfaceService.create(*_)

        where:
        adds << [null, []]
    }

    def "add: fails when the new NIC row does not select a network"() {
        when:
        ServiceResponse rtn = provider.addResizedInterfaces(server, SCVMM_OPTS, VM_ID, addRequest([row]))

        then:
        !rtn.success
        rtn.error.startsWith('No network selected for new NIC')
        0 * apiService.addNetworkInterface(*_)
        0 * interfaceService.create(*_)

        where:
        row << [[id: -1], [id: -1, network: [:]], [id: -1, network: [id: null, subnet: null]], [id: -1, network: 'bogus']]
    }

    def "add: fails when the selected network cannot be found"() {
        when:
        ServiceResponse rtn = provider.addResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                addRequest([[id: -1, network: [id: '999']]]))

        then:
        1 * networkService.get(999L) >> null
        !rtn.success
        rtn.error == 'Unable to find network 999 for new NIC 1'
        0 * apiService.addNetworkInterface(*_)
        0 * interfaceService.create(*_)
    }

    def "add: fails when the selected subnet cannot be found"() {
        when:
        ServiceResponse rtn = provider.addResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                addRequest([[id: -1, name: 'eth2', network: [id: '20', subnet: '999']]]))

        then:
        1 * subnetService.get(999L) >> null
        0 * networkService.get(_)
        !rtn.success
        rtn.error == 'Unable to find subnet 999 for new NIC eth2'
        0 * apiService.addNetworkInterface(*_)
    }

    def "add: creates the adapter in SCVMM and persists a new ComputeServerInterface (plain network)"() {
        given:
        Map capturedProps
        List<ComputeServerInterface> created

        when:
        ServiceResponse rtn = provider.addResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                addRequest([[id: -1, network: [id: '20'], row: 2]]))

        then:
        1 * networkService.get(20L) >> targetNetwork
        0 * subnetService.get(_)
        1 * apiService.addNetworkInterface(SCVMM_OPTS, VM_ID, _ as Map) >> { o, v, Map props ->
            capturedProps = props
            [success: true, adapterId: 'adapter-new', macAddress: '00:15:5D:00:00:09']
        }
        1 * interfaceService.create(_ as List, server) >> { List ifaces, ComputeServer s ->
            created = ifaces
            Single.just(true)
        }
        rtn.success
        capturedProps.networkExternalId == 'net-target'
        capturedProps.subnetExternalId == null
        capturedProps.vlanEnabled == false
        capturedProps.vlanId == null
        !capturedProps.containsKey('adapterId')
        created.size() == 1
        with(created[0]) {
            externalId == 'adapter-new'
            macAddress == '00:15:5D:00:00:09'
            network.is(targetNetwork)
            subnet == null
            vlanId == null
            name == 'eth2'          // server already had 2 NICs
            displayOrder == 3
            primaryInterface == false
            dhcp == true
        }
    }

    def "add: uses the name from the UI row when provided"() {
        given:
        List<ComputeServerInterface> created

        when:
        ServiceResponse rtn = provider.addResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                addRequest([[id: -1, name: 'mgmt0', network: [id: '20']]]))

        then:
        1 * networkService.get(20L) >> targetNetwork
        1 * apiService.addNetworkInterface(*_) >> [success: true, adapterId: 'adapter-new', macAddress: 'aa']
        1 * interfaceService.create(_ as List, server) >> { List ifaces, s -> created = ifaces; Single.just(true) }
        rtn.success
        created[0].name == 'mgmt0'
    }

    def "add: passes the network VLAN when the target network is a VLAN network"() {
        given:
        Map capturedProps
        List<ComputeServerInterface> created

        when:
        ServiceResponse rtn = provider.addResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                addRequest([[id: -1, network: [id: '30']]]))

        then:
        1 * networkService.get(30L) >> vlanNetwork
        1 * apiService.addNetworkInterface(SCVMM_OPTS, VM_ID, _ as Map) >> { o, v, Map props ->
            capturedProps = props
            [success: true, adapterId: 'adapter-new', macAddress: 'aa']
        }
        1 * interfaceService.create(_ as List, server) >> { List ifaces, s -> created = ifaces; Single.just(true) }
        rtn.success
        capturedProps.networkExternalId == 'net-vlan.200'
        capturedProps.vlanEnabled == true
        capturedProps.vlanId == 200
        created[0].vlanId == '200'
    }

    def "add: the subnet VLAN takes precedence over the network VLAN and the subnet is persisted"() {
        given:
        Map capturedProps
        List<ComputeServerInterface> created

        when:
        ServiceResponse rtn = provider.addResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                addRequest([[id: -1, network: [id: '20', subnet: '300']]]))

        then:
        1 * subnetService.get(300L) >> subnet
        1 * networkService.get(20L) >> targetNetwork
        1 * apiService.addNetworkInterface(SCVMM_OPTS, VM_ID, _ as Map) >> { o, v, Map props ->
            capturedProps = props
            [success: true, adapterId: 'adapter-new', macAddress: 'aa']
        }
        1 * interfaceService.create(_ as List, server) >> { List ifaces, s -> created = ifaces; Single.just(true) }
        rtn.success
        capturedProps.subnetExternalId == 'subnet-ext'
        capturedProps.vlanEnabled == true
        capturedProps.vlanId == 55
        created[0].subnet.is(subnet)
        created[0].vlanId == '55'
    }

    def "add: a subnet-only selection resolves the parent network from the subnet"() {
        when:
        ServiceResponse rtn = provider.addResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                addRequest([[id: -1, network: [subnet: '300']]]))

        then:
        1 * subnetService.get(300L) >> subnet
        1 * networkService.get(20L) >> targetNetwork
        1 * apiService.addNetworkInterface(SCVMM_OPTS, VM_ID, { Map p -> p.networkExternalId == 'net-target' && p.subnetExternalId == 'subnet-ext' }) >>
                [success: true, adapterId: 'adapter-new', macAddress: 'aa']
        1 * interfaceService.create(_ as List, server) >> Single.just(true)
        rtn.success
    }

    def "add: does not persist a model and reports the SCVMM error when the adapter creation fails"() {
        when:
        ServiceResponse rtn = provider.addResizedInterfaces(server, SCVMM_OPTS, VM_ID,
                addRequest([[id: -1, network: [id: '20']]]))

        then:
        1 * networkService.get(20L) >> targetNetwork
        1 * apiService.addNetworkInterface(*_) >> apiResult
        0 * interfaceService.create(*_)
        !rtn.success
        rtn.error == expectedError

        where:
        apiResult                                 || expectedError
        [success: false, error: 'VM network not found'] || 'VM network not found'
        [success: false]                          || 'Failed to add new NIC 1'
        [success: null]                           || 'Failed to add new NIC 1'
    }

    def "add: adds several NICs in order, increments display order, and stops at the first failure"() {
        given:
        List<ComputeServerInterface> created = []
        def reloaded = new ComputeServer(id: 100L)
        reloaded.interfaces = [primaryNic, secondNic, new ComputeServerInterface(id: 3L, externalId: 'adapter-new-1')]
        syncComputeServerService.find(_ as DataQuery) >> { reloaded }

        when:
        ServiceResponse rtn = provider.addResizedInterfaces(server, SCVMM_OPTS, VM_ID, addRequest([
                [id: -1, network: [id: '20']],
                [id: -1, network: [id: '30']],
                [id: -1, network: [id: '20']]
        ]))

        then:
        // the third NIC is never resolved because processing stops at the second failure
        1 * networkService.get(20L) >> targetNetwork
        1 * networkService.get(30L) >> vlanNetwork
        1 * apiService.addNetworkInterface(SCVMM_OPTS, VM_ID, { Map p -> p.networkExternalId == 'net-target' }) >>
                [success: true, adapterId: 'adapter-new-1', macAddress: 'aa']
        1 * apiService.addNetworkInterface(SCVMM_OPTS, VM_ID, { Map p -> p.networkExternalId == 'net-vlan.200' }) >>
                [success: false, error: 'out of slots']
        1 * interfaceService.create(_ as List, _ as ComputeServer) >> { List ifaces, s -> created.addAll(ifaces); Single.just(true) }
        !rtn.success
        rtn.error == 'out of slots'
        created.size() == 1
        created[0].name == 'eth2'
        created[0].displayOrder == 3
    }

    // ---------------------------------------------------------------- removeResizedInterfaces

    def "remove: returns success and does nothing when there are no interfaces to remove"() {
        when:
        ServiceResponse rtn = provider.removeResizedInterfaces(server, SCVMM_OPTS, VM_ID, deleteRequest(deletes))

        then:
        rtn.success
        0 * apiService.removeNetworkInterface(*_)
        0 * interfaceService.remove(*_)

        where:
        deletes << [null, [], [null]]
    }

    def "remove: refuses to remove the primary interface"() {
        when:
        ServiceResponse rtn = provider.removeResizedInterfaces(server, SCVMM_OPTS, VM_ID, deleteRequest([primaryNic]))

        then:
        !rtn.success
        rtn.error == 'The primary network interface (eth0) cannot be removed'
        0 * apiService.removeNetworkInterface(*_)
        0 * interfaceService.remove(*_)
    }

    def "remove: fails when the NIC has neither an adapter id nor a MAC address"() {
        given:
        def orphan = new ComputeServerInterface(id: 7L, name: 'eth7', externalId: null, macAddress: null, primaryInterface: false)

        when:
        ServiceResponse rtn = provider.removeResizedInterfaces(server, SCVMM_OPTS, VM_ID, deleteRequest([orphan]))

        then:
        !rtn.success
        rtn.error == 'NIC eth7 has no SCVMM adapter ID or MAC address and cannot be removed'
        0 * apiService.removeNetworkInterface(*_)
        0 * interfaceService.remove(*_)
    }

    def "remove: removes the adapter in SCVMM then removes the Morpheus interface"() {
        given:
        List<ComputeServerInterface> removed

        when:
        ServiceResponse rtn = provider.removeResizedInterfaces(server, SCVMM_OPTS, VM_ID, deleteRequest([secondNic]))

        then:
        1 * apiService.removeNetworkInterface(SCVMM_OPTS, VM_ID, [adapterId: 'adapter-2', macAddress: '00:15:5D:00:00:02']) >> [success: true]

        then:
        1 * interfaceService.remove(_ as List, server) >> { List ifaces, ComputeServer s -> removed = ifaces; Single.just(true) }
        rtn.success
        removed == [secondNic]
    }

    def "remove: falls back to MAC when the interface has no adapter id"() {
        given:
        def macOnly = new ComputeServerInterface(id: 5L, name: 'eth5', externalId: null, macAddress: '00:15:5D:00:00:05', primaryInterface: false)

        when:
        ServiceResponse rtn = provider.removeResizedInterfaces(server, SCVMM_OPTS, VM_ID, deleteRequest([macOnly]))

        then:
        1 * apiService.removeNetworkInterface(SCVMM_OPTS, VM_ID, [adapterId: null, macAddress: '00:15:5D:00:00:05']) >> [success: true]
        1 * interfaceService.remove([macOnly], server) >> Single.just(true)
        rtn.success
    }

    def "remove: does not touch Morpheus and surfaces the SCVMM error when the adapter removal fails"() {
        when:
        ServiceResponse rtn = provider.removeResizedInterfaces(server, SCVMM_OPTS, VM_ID, deleteRequest([secondNic]))

        then:
        1 * apiService.removeNetworkInterface(*_) >> apiResult
        0 * interfaceService.remove(*_)
        !rtn.success
        rtn.error == expectedError

        where:
        apiResult                                     || expectedError
        [success: false, error: 'Network adapter not found'] || 'Network adapter not found'
        [success: false]                              || 'Failed to remove NIC eth1'
    }

    def "remove: stops at the first failure when removing several NICs"() {
        given:
        def thirdNic = new ComputeServerInterface(id: 3L, name: 'eth2', externalId: 'adapter-3', macAddress: '00:15:5D:00:00:03', primaryInterface: false)
        server.interfaces = [primaryNic, secondNic, thirdNic]

        when:
        ServiceResponse rtn = provider.removeResizedInterfaces(server, SCVMM_OPTS, VM_ID, deleteRequest([secondNic, thirdNic]))

        then:
        1 * apiService.removeNetworkInterface(SCVMM_OPTS, VM_ID, { Map p -> p.adapterId == 'adapter-2' }) >> [success: false, error: 'boom']
        0 * apiService.removeNetworkInterface(SCVMM_OPTS, VM_ID, { Map p -> p.adapterId == 'adapter-3' })
        0 * interfaceService.remove(*_)
        !rtn.success
        rtn.error == 'boom'
    }

    // ---------------------------------------------------------------- reconfigureResizedInterfaces

    def "reconfigure: returns success when nothing is requested"() {
        when:
        ServiceResponse rtn = provider.reconfigureResizedInterfaces(server, SCVMM_OPTS, VM_ID, new ResizeRequest())

        then:
        rtn.success
        0 * apiService._
        0 * interfaceService._
    }

    def "reconfigure: applies removals, then updates, then additions"() {
        given:
        ResizeRequest rr = new ResizeRequest()
        rr.interfacesDelete = [secondNic]
        rr.interfacesUpdate = [new UpdateModel<ComputeServerInterface>(primaryNic, [id: 1, network: [id: '20']])]
        rr.interfacesAdd = [[id: -1, network: [id: '30']]]

        when:
        ServiceResponse rtn = provider.reconfigureResizedInterfaces(server, SCVMM_OPTS, VM_ID, rr)

        then:
        1 * apiService.removeNetworkInterface(SCVMM_OPTS, VM_ID, { Map p -> p.adapterId == 'adapter-2' }) >> [success: true]
        1 * interfaceService.remove([secondNic], server) >> Single.just(true)

        then:
        1 * networkService.get(20L) >> targetNetwork
        1 * apiService.updateNetworkInterface(SCVMM_OPTS, VM_ID, { Map p -> p.adapterId == 'adapter-1' && p.networkExternalId == 'net-target' }) >> [success: true]
        1 * interfaceService.save([primaryNic]) >> Single.just(true)

        then:
        1 * networkService.get(30L) >> vlanNetwork
        1 * apiService.addNetworkInterface(SCVMM_OPTS, VM_ID, { Map p -> p.networkExternalId == 'net-vlan.200' && p.vlanId == 200 }) >>
                [success: true, adapterId: 'adapter-new', macAddress: 'aa']
        1 * interfaceService.create(_ as List, server) >> Single.just(true)
        rtn.success
    }

    def "reconfigure: a removal failure short-circuits updates and additions"() {
        given:
        ResizeRequest rr = new ResizeRequest()
        rr.interfacesDelete = [secondNic]
        rr.interfacesUpdate = [new UpdateModel<ComputeServerInterface>(primaryNic, [id: 1, network: [id: '20']])]
        rr.interfacesAdd = [[id: -1, network: [id: '30']]]

        when:
        ServiceResponse rtn = provider.reconfigureResizedInterfaces(server, SCVMM_OPTS, VM_ID, rr)

        then:
        1 * apiService.removeNetworkInterface(*_) >> [success: false, error: 'remove failed']
        0 * apiService.updateNetworkInterface(*_)
        0 * apiService.addNetworkInterface(*_)
        0 * interfaceService._
        !rtn.success
        rtn.error == 'remove failed'
    }

    def "reconfigure: an update failure short-circuits additions"() {
        given:
        ResizeRequest rr = new ResizeRequest()
        rr.interfacesUpdate = [new UpdateModel<ComputeServerInterface>(primaryNic, [id: 1, network: [id: '20']])]
        rr.interfacesAdd = [[id: -1, network: [id: '30']]]

        when:
        ServiceResponse rtn = provider.reconfigureResizedInterfaces(server, SCVMM_OPTS, VM_ID, rr)

        then:
        1 * networkService.get(20L) >> targetNetwork
        1 * apiService.updateNetworkInterface(*_) >> [success: false, error: 'update failed']
        0 * apiService.addNetworkInterface(*_)
        0 * interfaceService.create(*_)
        !rtn.success
        rtn.error == 'update failed'
    }
}
