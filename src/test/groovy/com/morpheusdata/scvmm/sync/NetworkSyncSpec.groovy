package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.network.MorpheusNetworkService
import com.morpheusdata.core.network.MorpheusNetworkSubnetService
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.core.util.NetworkUtility
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.NetworkIdentityProjection
import com.morpheusdata.model.projection.NetworkSubnetIdentityProjection
import com.morpheusdata.scvmm.ScvmmApiService
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Unroll

class NetworkSyncSpec extends Specification {

    private NetworkSync networkSync
    private MorpheusContext morpheusContext
    private ScvmmApiService mockApiService
    private MorpheusSynchronousComputeServerService computeServerService
    private MorpheusNetworkService networkService
    private MorpheusNetworkSubnetService networkSubnetService
    private MorpheusCloudService cloudAsyncService
    private Cloud cloud
    private ComputeServer server
    private Account testAccount

    def setup() {
        // Setup mock context and services
        morpheusContext = Mock(MorpheusContext)
        mockApiService = Mock(ScvmmApiService)

        // Mock services
        computeServerService = Mock(MorpheusSynchronousComputeServerService)
        networkService = Mock(MorpheusNetworkService)
        networkSubnetService = Mock(MorpheusNetworkSubnetService)
        cloudAsyncService = Mock(MorpheusCloudService)

        def morpheusServices = Mock(MorpheusServices) {
            getComputeServer() >> computeServerService
        }

        def morpheusAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> cloudAsyncService
            getNetworkSubnet() >> networkSubnetService
        }

        // Configure context mocks
        morpheusContext.getAsync() >> morpheusAsyncServices
        morpheusContext.getServices() >> morpheusServices

        // Configure cloud service mock
        cloudAsyncService.getNetwork() >> networkService

        // Create test objects
        testAccount = new Account(id: 1L, name: "test-account")
        cloud = new Cloud(
                id: 1L,
                name: "test-scvmm-cloud",
                account: testAccount,
                owner: testAccount,
                defaultNetworkSyncActive: true
        )

        server = new ComputeServer(
                id: 2L,
                name: "scvmm-server",
                externalId: "server-123",
                cloud: cloud
        )

        // Create real NetworkSync instance
        networkSync = new NetworkSync(morpheusContext, cloud)

        // Use reflection to inject mock API service
        def field = NetworkSync.class.getDeclaredField('apiService')
        field.setAccessible(true)
        field.set(networkSync, mockApiService)
    }

    @Unroll
    def "execute should handle server not found gracefully"() {
        given: "No server found in database"
        def scvmmOpts = [server: null, username: "user", password: "pass"]
        def listResults = [success: false, error: "No server found"]

        when: "execute is called"
        networkSync.execute()

        then: "services are called but sync stops due to API failure"
        1 * computeServerService.find(_ as DataQuery) >> null
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, null) >> scvmmOpts
        1 * mockApiService.listNetworks(scvmmOpts) >> listResults

        and: "no exception is thrown"
        noExceptionThrown()
    }

    @Unroll
    def "execute should handle API failure gracefully"() {
        given: "Server exists but API fails"
        def scvmmOpts = [server: "scvmm-server", username: "user", password: "pass"]
        def listResults = [success: false, networks: null]

        when: "execute is called"
        networkSync.execute()

        then: "services are called but sync stops due to API failure"
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.listNetworks(scvmmOpts) >> listResults

        and: "no exception is thrown"
        noExceptionThrown()
    }

    @Unroll
    def "execute should handle missing networks property gracefully"() {
        given: "API returns success but no networks property"
        def scvmmOpts = [server: "scvmm-server", username: "user", password: "pass"]
        def listResults = [success: true] // No networks property

        when: "execute is called"
        networkSync.execute()

        then: "services are called but sync stops due to missing networks"
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.listNetworks(scvmmOpts) >> listResults

        and: "no exception is thrown"
        noExceptionThrown()
    }

    @Unroll
    def "execute should handle exceptions gracefully"() {
        when: "execute is called and exception occurs"
        networkSync.execute()

        then: "exception is caught and logged"
        1 * computeServerService.find(_ as DataQuery) >> { throw new RuntimeException("Database error") }

        and: "no exception is propagated"
        noExceptionThrown()
    }

    @Unroll
    def "execute should proceed to sync task when conditions are met"() {
        given: "Valid server and successful API response with networks"
        def networkData = [
                [
                        ID: "network-123",
                        Name: "Test Network",
                        VLanID: 100,
                        Subnets: []
                ]
        ]
        def scvmmOpts = [server: "scvmm-server", username: "user", password: "pass"]
        def listResults = [success: true, networks: networkData]

        and: "Mock network identity projections"
        def identityProjection = Mock(NetworkIdentityProjection) {
            getExternalId() >> "network-123"
        }

        when: "execute is called"
        networkSync.execute()

        then: "services are called and sync task starts"
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.listNetworks(scvmmOpts) >> listResults
        1 * networkService.listIdentityProjections(_ as DataQuery) >> Observable.fromIterable([identityProjection])

        and: "no exception is thrown even if sync task fails internally"
        noExceptionThrown()
    }

    @Unroll
    def "addMissingNetworks should create networks and process subnets successfully"() {
        given: "Valid network data and dependencies"
        def networkType = new NetworkType(code: 'scvmmNetwork')
        def subnetType = new NetworkSubnetType(code: 'scvmm')
        def server = new ComputeServer(id: 2L, name: "test-server")

        def addList = [
                [ID: "net-1", Name: "Network1", VLanID: 100, Subnets: [[Subnet: "192.168.1.0/24"]]],
                [ID: "net-2", Name: "Network2", VLanID: 200, Subnets: [[Subnet: "10.0.1.0/24"]]]
        ]

        and: "Mock bulk create result"
        def createdNetwork1 = new Network(id: 1L, name: "Network1", externalId: "net-1")
        def createdNetwork2 = new Network(id: 2L, name: "Network2", externalId: "net-2")
        def bulkCreateResult = [persistedItems: [createdNetwork1, createdNetwork2]]

        when: "addMissingNetworks is called"
        networkSync.addMissingNetworks(addList, networkType, subnetType, server)

        then: "networks are created with correct configuration"
        1 * networkService.bulkCreate({ List<Network> networks ->
            assert networks.size() == 2

            def network1 = networks.find { it.externalId == "net-1" }
            assert network1.name == "Network1"
            assert network1.code == "scvmm.network.${cloud.id}.${server.id}.net-1"
            assert network1.category == "scvmm.network.${cloud.id}.${server.id}"
            assert network1.cloud == cloud
            assert network1.dhcpServer == true
            assert network1.uniqueId == "net-1"
            assert network1.type == networkType
            assert network1.refType == "ComputeZone"
            assert network1.refId == cloud.id
            assert network1.owner == cloud.owner
            assert network1.active == cloud.defaultNetworkSyncActive

            def network2 = networks.find { it.externalId == "net-2" }
            assert network2.name == "Network2"
            assert network2.code == "scvmm.network.${cloud.id}.${server.id}.net-2"
            assert network2.category == "scvmm.network.${cloud.id}.${server.id}"

            true
        }) >> Single.just(bulkCreateResult)

        and: "subnets are processed for created networks"
        1 * networkSubnetService.create(_, createdNetwork1) >> Single.just([success: true])
        1 * networkSubnetService.create(_, createdNetwork2) >> Single.just([success: true])
    }

    @Unroll
    def "addMissingNetworks should handle empty add list gracefully"() {
        given: "Empty add list"
        def networkType = new NetworkType(code: 'scvmmNetwork')
        def subnetType = new NetworkSubnetType(code: 'scvmm')
        def server = new ComputeServer(id: 2L, name: "test-server")
        def addList = []

        when: "addMissingNetworks is called"
        networkSync.addMissingNetworks(addList, networkType, subnetType, server)

        then: "no networks are created"
        0 * networkService.bulkCreate(_)
        0 * networkSubnetService.create(_, _)
    }

    @Unroll
    def "addMissingNetworks should handle null add list gracefully"() {
        given: "Null add list"
        def networkType = new NetworkType(code: 'scvmmNetwork')
        def subnetType = new NetworkSubnetType(code: 'scvmm')
        def server = new ComputeServer(id: 2L, name: "test-server")
        def addList = null

        when: "addMissingNetworks is called"
        networkSync.addMissingNetworks(addList, networkType, subnetType, server)

        then: "no networks are created"
        0 * networkService.bulkCreate(_)
        0 * networkSubnetService.create(_, _)
    }

    @Unroll
    def "addMissingNetworks should handle exceptions in network creation gracefully"() {
        given: "Valid data but bulk create fails"
        def networkType = new NetworkType(code: 'scvmmNetwork')
        def subnetType = new NetworkSubnetType(code: 'scvmm')
        def server = new ComputeServer(id: 2L, name: "test-server")
        def addList = [
                [ID: "net-1", Name: "Network1", VLanID: 100, Subnets: [[Subnet: "192.168.1.0/24"]]]
        ]

        when: "addMissingNetworks is called and exception occurs"
        networkSync.addMissingNetworks(addList, networkType, subnetType, server)

        then: "exception is caught and logged"
        1 * networkService.bulkCreate(_) >> { throw new RuntimeException("Database error") }

        and: "no exception is propagated"
        noExceptionThrown()
    }

    def "processNetworksWithSubnets should create networks and process subnets when networks exist"() {
        given: "A list of networks to add"
        def network1 = new Network(id: 1L, name: "Network1")
        def network2 = new Network(id: 2L, name: "Network2")
        def networkAdds = [network1, network2]

        and: "Cloud items with subnet data"
        def addList = [
                [Name: "Network1", ID: "net-1", VLanID: 100, Subnets: [[Subnet: "192.168.1.0/24"]]],
                [Name: "Network2", ID: "net-2", VLanID: 200, Subnets: [[Subnet: "10.0.1.0/24"]]]
        ]

        and: "Mock network subnet type"
        def subnetType = new NetworkSubnetType(code: 'scvmm')

        and: "Mock bulk create result"
        def bulkCreateResult = [persistedItems: networkAdds]

        when: "processNetworksWithSubnets is called"
        networkSync.processNetworksWithSubnets(networkAdds, addList, subnetType)

        then: "bulk create is called and subnets are processed"
        1 * networkService.bulkCreate(networkAdds) >> Single.just(bulkCreateResult)
        1 * networkSubnetService.create(_, network1) >> Single.just([success: true])
        1 * networkSubnetService.create(_, network2) >> Single.just([success: true])
    }

    def "processNetworksWithSubnets should not process when no networks exist"() {
        given: "Empty network list"
        def networkAdds = []
        def addList = []
        def subnetType = new NetworkSubnetType(code: 'scvmm')

        when: "processNetworksWithSubnets is called"
        networkSync.processNetworksWithSubnets(networkAdds, addList, subnetType)

        then: "no services are called"
        0 * networkService.bulkCreate(_)
        0 * networkSubnetService.create(_, _)
    }

    def "processSubnetsForNetworks should process subnets for matching networks"() {
        given: "Networks and matching cloud items"
        def network1 = new Network(id: 1L, name: "TestNetwork")
        def network2 = new Network(id: 2L, name: "AnotherNetwork")
        def networks = [network1, network2]

        def addList = [
                [Name: "TestNetwork", ID: "net-1", VLanID: 100, Subnets: [[Subnet: "192.168.1.0/24"]]],
                [Name: "UnmatchedNetwork", ID: "net-3", VLanID: 300, Subnets: [[Subnet: "172.16.1.0/24"]]]
        ]

        def subnetType = new NetworkSubnetType(code: 'scvmm')

        when: "processSubnetsForNetworks is called"
        networkSync.processSubnetsForNetworks(networks, addList, subnetType)

        then: "subnet is created only for matching network"
        1 * networkSubnetService.create(_, network1) >> Single.just([success: true])
        0 * networkSubnetService.create(_, network2)
    }

    def "processSubnetsForNetworks should handle networks with no matching cloud items"() {
        given: "Networks with no matching cloud items"
        def network1 = new Network(id: 1L, name: "OrphanNetwork")
        def networks = [network1]
        def addList = [
                [Name: "DifferentNetwork", ID: "net-1", VLanID: 100, Subnets: [[Subnet: "192.168.1.0/24"]]]
        ]
        def subnetType = new NetworkSubnetType(code: 'scvmm')

        when: "processSubnetsForNetworks is called"
        networkSync.processSubnetsForNetworks(networks, addList, subnetType)

        then: "no subnets are created"
        0 * networkSubnetService.create(_, _)
    }

    def "addSubnetToNetwork should create subnet with correct configuration"() {
        given: "A network and cloud item with subnet data"
        def network = new Network(id: 1L, name: "TestNetwork")
        def cloudItem = [
                Name: "TestNetwork",
                ID: "net-1",
                VLanID: 100,
                Subnets: [[Subnet: "192.168.1.0/24"]]
        ]
        def subnetType = new NetworkSubnetType(code: 'scvmm')

        and: "Mock NetworkUtility response"
        GroovyMock(NetworkUtility, global: true)
        def mockNetworkCidr = [
                config: [netmask: "255.255.255.0"],
                ranges: [[startAddress: "192.168.1.10", endAddress: "192.168.1.254"]]
        ]
        NetworkUtility.getNetworkCidrConfig("192.168.1.0/24") >> mockNetworkCidr

        when: "addSubnetToNetwork is called"
        networkSync.addSubnetToNetwork(network, cloudItem, subnetType)

        then: "subnet is created with correct configuration"
        1 * networkSubnetService.create({ List<NetworkSubnet> subnets ->
            def subnet = subnets[0]
            assert subnet.name == "TestNetwork"
            assert subnet.externalId == "net-1"
            assert subnet.vlanId == 100
            assert subnet.cidr == "192.168.1.0/24"
            assert subnet.netmask == "255.255.255.0"
            assert subnet.dhcpStart == "192.168.1.10"
            assert subnet.dhcpEnd == "192.168.1.254"
            assert subnet.dhcpServer == true
            assert subnet.category == "scvmm.subnet.${cloud.id}"
            assert subnet.networkSubnetType == subnetType
            true
        }, network) >> Single.just([success: true])
    }

    def "addSubnetToNetwork should handle missing subnet data gracefully"() {
        given: "A network and cloud item with no subnet data"
        def network = new Network(id: 1L, name: "TestNetwork")
        def cloudItem = [
                Name: "TestNetwork",
                ID: "net-1",
                VLanID: 100,
                Subnets: null
        ]
        def subnetType = new NetworkSubnetType(code: 'scvmm')

        and: "Mock NetworkUtility to handle null input"
        GroovyMock(NetworkUtility, global: true)
        NetworkUtility.getNetworkCidrConfig(null) >> [config: null, ranges: null]

        when: "addSubnetToNetwork is called"
        networkSync.addSubnetToNetwork(network, cloudItem, subnetType)

        then: "subnet is created with null subnet values"
        1 * networkSubnetService.create({ List<NetworkSubnet> subnets ->
            def subnet = subnets[0]
            assert subnet.cidr == null
            assert subnet.netmask == null
            assert subnet.dhcpStart == null
            assert subnet.dhcpEnd == null
            true
        }, network) >> Single.just([success: true])
    }

    def "updateMatchedNetworks should handle exceptions gracefully"() {
        given: "Update list that will cause an exception"
        def updateItem = Mock(SyncTask.UpdateItem) {
            getExistingItem() >> { throw new RuntimeException("Test exception") }
        }
        def updateList = [updateItem]
        def subnetType = new NetworkSubnetType(code: 'scvmm')

        when: "updateMatchedNetworks is called"
        networkSync.updateMatchedNetworks(updateList, subnetType)

        then: "exception is caught and handled"
        noExceptionThrown()
    }

    def "addMissingNetworkSubnet should create multiple subnets correctly"() {
        given: "List of SCVMM subnets to add"
        def addList = [
                [ID: "subnet-1", Name: "Subnet1", Subnet: "192.168.1.0/24"],
                [ID: "subnet-2", Name: "Subnet2", Subnet: "10.0.1.0/24"]
        ]
        def subnetType = new NetworkSubnetType(code: 'scvmm')
        def network = new Network(id: 1L, name: "TestNetwork")

        and: "Mock NetworkUtility"
        GroovyMock(NetworkUtility, global: true)
        NetworkUtility.getNetworkCidrConfig("192.168.1.0/24") >> [
                config: [netmask: "255.255.255.0"],
                ranges: [[startAddress: "192.168.1.10", endAddress: "192.168.1.254"]]
        ]
        NetworkUtility.getNetworkCidrConfig("10.0.1.0/24") >> [
                config: [netmask: "255.255.255.0"],
                ranges: [[startAddress: "10.0.1.10", endAddress: "10.0.1.254"]]
        ]

        when: "addMissingNetworkSubnet is called"
        networkSync.addMissingNetworkSubnet(addList, subnetType, network)

        then: "multiple subnets are created"
        1 * networkSubnetService.create({ List<NetworkSubnet> subnets ->
            assert subnets.size() == 2

            def subnet1 = subnets.find { it.externalId == "subnet-1" }
            assert subnet1.name == "Subnet1"
            assert subnet1.cidr == "192.168.1.0/24"
            assert subnet1.status == NetworkSubnet.Status.AVAILABLE

            def subnet2 = subnets.find { it.externalId == "subnet-2" }
            assert subnet2.name == "Subnet2"
            assert subnet2.cidr == "10.0.1.0/24"
            assert subnet2.status == NetworkSubnet.Status.AVAILABLE

            true
        }, network) >> Single.just([success: true])
    }

    def "addMissingNetworkSubnet should handle empty add list"() {
        given: "Empty add list"
        def addList = []
        def subnetType = new NetworkSubnetType(code: 'scvmm')
        def network = new Network(id: 1L, name: "TestNetwork")

        when: "addMissingNetworkSubnet is called"
        networkSync.addMissingNetworkSubnet(addList, subnetType, network)

        then: "no subnets are created"
        1 * networkSubnetService.create([], network) >> Single.just([success: true])
    }

    def "updateMatchedNetworkSubnet should handle exceptions gracefully"() {
        given: "Update list that causes exception"
        def updateItem = Mock(SyncTask.UpdateItem) {
            getExistingItem() >> { throw new RuntimeException("Test exception") }
        }
        def updateList = [updateItem]

        when: "updateMatchedNetworkSubnet is called"
        networkSync.updateMatchedNetworkSubnet(updateList)

        then: "exception is caught and handled"
        noExceptionThrown()
        0 * networkSubnetService.save(_)
    }
    // Tests for updateSubnetBasicProperties
    def "updateSubnetBasicProperties should update name and config properties"() {
        given: "A subnet with outdated basic properties"
        def subnet = new NetworkSubnet(name: "OldName")
        subnet.setConfigProperty("subnetName", "OldConfigName")

        def matchedSubnet = [Name: "NewName"]

        when: "updateSubnetBasicProperties is called"
        def result = networkSync.updateSubnetBasicProperties(subnet, matchedSubnet)

        then: "properties are updated and true is returned"
        result == true
        subnet.name == "NewName"
        subnet.getConfigProperty("subnetName") == "NewName"
    }

    def "updateSubnetBasicProperties should return false when properties are current"() {
        given: "A subnet with current basic properties"
        def subnet = new NetworkSubnet(name: "CurrentName")
        subnet.setConfigProperty("subnetName", "CurrentName")

        def matchedSubnet = [Name: "CurrentName"]

        when: "updateSubnetBasicProperties is called"
        def result = networkSync.updateSubnetBasicProperties(subnet, matchedSubnet)

        then: "false is returned as no changes were made"
        result == false
    }

    def "updateSubnetNetworkProperties should update network-related properties"() {
        given: "A subnet with outdated network properties"
        def subnet = new NetworkSubnet(
                cidr: "192.168.1.0/24",
                subnetAddress: "192.168.1.0/24",
                netmask: "255.255.255.0"
        )
        subnet.setConfigProperty("subnetCidr", "192.168.1.0/24")

        def matchedSubnet = [Subnet: "10.0.1.0/24"]
        def networkCidr = [config: [netmask: "255.255.255.128"]]

        when: "updateSubnetNetworkProperties is called"
        def result = networkSync.updateSubnetNetworkProperties(subnet, matchedSubnet, networkCidr)

        then: "properties are updated and true is returned"
        result == true
        subnet.cidr == "10.0.1.0/24"
        subnet.subnetAddress == "10.0.1.0/24"
        subnet.netmask == "255.255.255.128"
        subnet.getConfigProperty("subnetCidr") == "10.0.1.0/24"
    }

    def "updateSubnetNetworkProperties should return false when properties are current"() {
        given: "A subnet with current network properties"
        def subnet = new NetworkSubnet(
                cidr: "192.168.1.0/24",
                subnetAddress: "192.168.1.0/24",
                netmask: "255.255.255.0"
        )
        subnet.setConfigProperty("subnetCidr", "192.168.1.0/24")

        def matchedSubnet = [Subnet: "192.168.1.0/24"]
        def networkCidr = [config: [netmask: "255.255.255.0"]]

        when: "updateSubnetNetworkProperties is called"
        def result = networkSync.updateSubnetNetworkProperties(subnet, matchedSubnet, networkCidr)

        then: "false is returned as no changes were made"
        result == false
    }

    def "updateSubnetDhcpProperties should update DHCP range properties"() {
        given: "A subnet with outdated DHCP properties"
        def subnet = new NetworkSubnet(
                dhcpStart: "192.168.1.10",
                dhcpEnd: "192.168.1.254"
        )

        def networkCidr = [
                ranges: [[startAddress: "10.0.1.50", endAddress: "10.0.1.200"]]
        ]

        when: "updateSubnetDhcpProperties is called"
        def result = networkSync.updateSubnetDhcpProperties(subnet, networkCidr)

        then: "DHCP properties are updated and true is returned"
        result == true
        subnet.dhcpStart == "10.0.1.50"
        subnet.dhcpEnd == "10.0.1.200"
    }

    def "updateSubnetDhcpProperties should handle missing ranges gracefully"() {
        given: "A subnet with DHCP properties and networkCidr without ranges"
        def subnet = new NetworkSubnet(
                dhcpStart: "192.168.1.10",
                dhcpEnd: "192.168.1.254"
        )

        def networkCidr = [ranges: null]

        when: "updateSubnetDhcpProperties is called"
        def result = networkSync.updateSubnetDhcpProperties(subnet, networkCidr)

        then: "DHCP properties are set to null and true is returned"
        result == true
        subnet.dhcpStart == null
        subnet.dhcpEnd == null
    }

    def "updateSubnetDhcpProperties should return false when properties are current"() {
        given: "A subnet with current DHCP properties"
        def subnet = new NetworkSubnet(
                dhcpStart: "192.168.1.10",
                dhcpEnd: "192.168.1.254"
        )

        def networkCidr = [
                ranges: [[startAddress: "192.168.1.10", endAddress: "192.168.1.254"]]
        ]

        when: "updateSubnetDhcpProperties is called"
        def result = networkSync.updateSubnetDhcpProperties(subnet, networkCidr)

        then: "false is returned as no changes were made"
        result == false
    }

    @Unroll
    def "createNetworkEntities should create network entities with correct configuration"() {
        given: "Network data and dependencies"
        def networkType = new NetworkType(code: 'scvmmNetwork')
        def server = new ComputeServer(id: 5L, name: "test-server")
        def addList = [
                [ID: "network-123", Name: "Production Network", VLanID: 500],
                [ID: "network-456", Name: "Development Network", VLanID: 501]
        ]

        when: "createNetworkEntities is called"
        def result = networkSync.createNetworkEntities(addList, networkType, server)

        then: "correct number of networks are created"
        result.size() == 2

        and: "first network has correct properties"
        def network1 = result.find { it.externalId == "network-123" }
        network1.name == "Production Network"
        network1.code == "scvmm.network.${cloud.id}.${server.id}.network-123"
        network1.category == "scvmm.network.${cloud.id}.${server.id}"
        network1.cloud == cloud
        network1.dhcpServer == true
        network1.uniqueId == "network-123"
        network1.externalId == "network-123"
        network1.type == networkType
        network1.refType == "ComputeZone"
        network1.refId == cloud.id
        network1.owner == cloud.owner
        network1.active == cloud.defaultNetworkSyncActive

        and: "second network has correct properties"
        def network2 = result.find { it.externalId == "network-456" }
        network2.name == "Development Network"
        network2.code == "scvmm.network.${cloud.id}.${server.id}.network-456"
        network2.uniqueId == "network-456"
    }

    @Unroll
    def "createNetworkEntities should handle empty list and return empty result"() {
        given: "Empty add list"
        def networkType = new NetworkType(code: 'scvmmNetwork')
        def server = new ComputeServer(id: 5L, name: "test-server")
        def addList = []

        when: "createNetworkEntities is called"
        def result = networkSync.createNetworkEntities(addList, networkType, server)

        then: "empty list is returned"
        result.isEmpty()
    }

    @Unroll
    def "createNetworkEntities should handle null list and return empty result"() {
        given: "Null add list"
        def networkType = new NetworkType(code: 'scvmmNetwork')
        def server = new ComputeServer(id: 5L, name: "test-server")
        def addList = null

        when: "createNetworkEntities is called"
        def result = networkSync.createNetworkEntities(addList, networkType, server)

        then: "empty list is returned"
        result.isEmpty()
    }

    def "updateMatchedNetworks should do nothing for null list"() {
        given: "A subnet type"
        def subnetType = new NetworkSubnetType(code: 'scvmm')

        when: "updateMatchedNetworks is called with null list"
        networkSync.updateMatchedNetworks(null, subnetType)

        then: "no service interactions occur"
        0 * networkSubnetService.list(_)
        noExceptionThrown()
    }

    def "updateMatchedNetworks should do nothing for empty list"() {
        given: "A subnet type and empty update list"
        def subnetType = new NetworkSubnetType(code: 'scvmm')
        def updateList = []

        when: "updateMatchedNetworks is called with empty list"
        networkSync.updateMatchedNetworks(updateList, subnetType)

        then: "no service interactions occur"
        0 * networkSubnetService.list(_)
        noExceptionThrown()
    }

    def "updateMatchedNetworks should handle network with no subnets"() {
        given: "A network with no existing subnets"
        def subnetType = new NetworkSubnetType(code: 'scvmm')
        def network = new Network(id: 1L, subnets: [])
        def masterItem = [Subnets: []]
        def updateItem = new SyncTask.UpdateItem<Network, Map>(
            existingItem: network,
            masterItem: masterItem
        )
        def updateList = [updateItem]

        and: "mock returns empty observable for subnet list"
        networkSubnetService.list(_) >> Observable.empty()

        when: "updateMatchedNetworks is called"
        networkSync.updateMatchedNetworks(updateList, subnetType)

        then: "subnet list is queried but no further operations occur"
        1 * networkSubnetService.list(_) >> Observable.empty()
        noExceptionThrown()
    }

    def "updateMatchedNetworks should handle network with existing subnets"() {
        given: "A network with existing subnets"
        def subnetType = new NetworkSubnetType(code: 'scvmm')
        def existingSubnet = new NetworkSubnet(id: 1L, externalId: "subnet-1")
        def network = new Network(id: 1L, subnets: [existingSubnet])
        def masterSubnet = [ID: "subnet-1", Name: "Test Subnet"]
        def masterItem = [Subnets: [masterSubnet]]
        def updateItem = new SyncTask.UpdateItem<Network, Map>(
            existingItem: network,
            masterItem: masterItem
        )
        def updateList = [updateItem]

        and: "mock subnet projection"
        def subnetProjection = new NetworkSubnetIdentityProjection(
            id: 1L,
            externalId: "subnet-1"
        )

        when: "updateMatchedNetworks is called"
        networkSync.updateMatchedNetworks(updateList, subnetType)

        then: "subnet services are called appropriately"
        1 * networkSubnetService.list(_) >> Observable.just(subnetProjection)
        1 * networkSubnetService.listById(_) >> Observable.just(existingSubnet)
        noExceptionThrown()
    }

    def "updateMatchedNetworks should handle exception gracefully"() {
        given: "A network that will cause an exception"
        def subnetType = new NetworkSubnetType(code: 'scvmm')
        def network = new Network(id: 1L, subnets: [])
        def masterItem = [Subnets: []]
        def updateItem = new SyncTask.UpdateItem<Network, Map>(
            existingItem: network,
            masterItem: masterItem
        )
        def updateList = [updateItem]

        when: "updateMatchedNetworks is called and service throws exception"
        networkSync.updateMatchedNetworks(updateList, subnetType)

        then: "exception is caught and logged"
        1 * networkSubnetService.list(_) >> { throw new RuntimeException("Test exception") }
        noExceptionThrown()
    }

    def "updateMatchedNetworks should handle multiple networks"() {
        given: "Multiple networks to update"
        def subnetType = new NetworkSubnetType(code: 'scvmm')

        def network1 = new Network(id: 1L, subnets: [])
        def masterItem1 = [Subnets: []]
        def updateItem1 = new SyncTask.UpdateItem<Network, Map>(
            existingItem: network1,
            masterItem: masterItem1
        )

        def network2 = new Network(id: 2L, subnets: [])
        def masterItem2 = [Subnets: []]
        def updateItem2 = new SyncTask.UpdateItem<Network, Map>(
            existingItem: network2,
            masterItem: masterItem2
        )

        def updateList = [updateItem1, updateItem2]

        when: "updateMatchedNetworks is called with multiple networks"
        networkSync.updateMatchedNetworks(updateList, subnetType)

        then: "each network is processed"
        2 * networkSubnetService.list(_) >> Observable.empty()
        noExceptionThrown()
    }

}
