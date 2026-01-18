package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.NetworkPoolIdentityProjection
import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.scvmm.logging.LogInterface
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkSubnetService as SyncNetworkSubnetService

class IpPoolsSyncSpec extends Specification {

    MorpheusContext mockContext
    Cloud mockCloud
    IpPoolsSync sync
    ScvmmApiService mockApiService
    LogInterface mockLog

    def setup() {
        mockContext = Mock(MorpheusContext)
        mockCloud = new Cloud(id: 1L, code: 'test-cloud')
        mockCloud.account = new Account(id: 1L)
        mockCloud.owner = new Account(id: 2L)
        mockApiService = Mock(ScvmmApiService)
        mockLog = Mock(LogInterface)
        
        sync = new IpPoolsSync(mockContext, mockCloud)
        sync.apiService = mockApiService
        sync.log = mockLog
    }

    def "constructor initializes fields correctly"() {
        when:
        def newSync = new IpPoolsSync(mockContext, mockCloud)

        then:
        newSync.morpheusContext == mockContext
        newSync.cloud == mockCloud
        newSync.apiService != null
    }

    def "execute successfully processes IP pools"() {
        given:
        def network1 = new Network(id: 1L, externalId: 'net-1', category: "scvmm.network.${mockCloud.id}.1")
        def network2 = new Network(id: 2L, externalId: 'net-2', category: "scvmm.vlan.network.${mockCloud.id}.2")
        def networks = [network1, network2]
        
        def computeServer = new ComputeServer(id: 1L, cloud: mockCloud)
        
        def ipPool1 = [
            ID: 'pool-1',
            Name: 'Pool 1',
            Subnet: '192.168.1.0/24',
            DefaultGateways: ['192.168.1.1'],
            TotalAddresses: 254,
            AvailableAddresses: 200,
            IPAddressRangeStart: '192.168.1.10',
            IPAddressRangeEnd: '192.168.1.253',
            NetworkID: 'net-1',
            SubnetID: 'subnet-1'
        ]
        
        def listResults = [
            success: true,
            ipPools: [ipPool1],
            networkMapping: [[ID: 'net-1', Name: 'Network 1']]
        ]
        
        def existingProjections = Observable.empty()
        
        def mockNetworkPoolRangeService = Mock(com.morpheusdata.core.network.MorpheusNetworkPoolRangeService) {
            bulkCreate(_ as List) >> Single.just(true)
        }
        def mockNetworkPoolService = Mock(com.morpheusdata.core.network.MorpheusNetworkPoolService) {
            listIdentityProjections(_ as DataQuery) >> existingProjections
            bulkCreate(_ as List) >> Single.just(true)
            poolRange >> mockNetworkPoolRangeService
            bulkSave(_ as List) >> Single.just(true)
        }
        def mockNetworkService = Mock(com.morpheusdata.core.network.MorpheusNetworkService) {
            list(_ as DataQuery) >> networks
            getPool() >> mockNetworkPoolService
            save(_ as Network) >> Single.just(network1)
        }
        def mockCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService) {
            getNetwork() >> mockNetworkService
        }
        def mockComputeServerService = Mock(com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService) {
            find(_ as DataQuery) >> computeServer
        }
        def mockResourcePermissionService = Mock(com.morpheusdata.core.MorpheusResourcePermissionService) {
            bulkCreate(_ as List) >> Single.just(true)
        }
        def mockSyncNetworkService = Mock(com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService) {
            list(_ as DataQuery) >> networks
        }
        def mockSyncCloudService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService) {
            getNetwork() >> mockSyncNetworkService
        }
        def mockServices = Mock(com.morpheusdata.core.MorpheusServices) {
            getCloud() >> mockSyncCloudService
            getComputeServer() >> mockComputeServerService
        }
        def mockAsyncServices = Mock(com.morpheusdata.core.MorpheusAsyncServices) {
            getCloud() >> mockCloudService
            getResourcePermission() >> mockResourcePermissionService
        }
        
        mockContext.getServices() >> mockServices
        mockContext.getAsync() >> mockAsyncServices
        mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, mockCloud, computeServer) >> [:]
        mockApiService.listNetworkIPPools(_) >> listResults

        when:
        sync.execute()

        then:
        1 * mockLog.debug('IpPoolsSync')
        notThrown(Exception)
    }

    def "execute handles API failure gracefully"() {
        given:
        def mockComputeServerService = Mock(com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService) {
            find(_ as DataQuery) >> null
        }
        def mockSyncNetworkService = Mock(com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService) {
            list(_ as DataQuery) >> []
        }
        def mockSyncCloudService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService) {
            getNetwork() >> mockSyncNetworkService
        }
        def mockServices = Mock(com.morpheusdata.core.MorpheusServices) {
            getCloud() >> mockSyncCloudService
            getComputeServer() >> mockComputeServerService
        }
        
        mockContext.getServices() >> mockServices
        mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, mockCloud, null) >> [:]
        mockApiService.listNetworkIPPools(_) >> [success: false]

        when:
        sync.execute()

        then:
        1 * mockLog.debug('IpPoolsSync')
        notThrown(Exception)
    }

    def "execute handles exception during sync"() {
        given:
        def mockSyncNetworkService = Mock(com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService) {
            list(_ as DataQuery) >> { throw new RuntimeException("Test error") }
        }
        def mockSyncCloudService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService) {
            getNetwork() >> mockSyncNetworkService
        }
        def mockServices = Mock(com.morpheusdata.core.MorpheusServices) {
            getCloud() >> mockSyncCloudService
        }
        
        mockContext.getServices() >> mockServices

        when:
        sync.execute()

        then:
        1 * mockLog.debug('IpPoolsSync')
        1 * mockLog.error(_ as String, _ as Exception)
        notThrown(Exception)
    }

    def "addMissingIpPools handles empty list"() {
        given:
        def poolType = new NetworkPoolType(code: 'scvmm')
        def networks = []
        def networkMapping = []

        when:
        sync.addMissingIpPools([], networks, poolType, networkMapping)

        then:
        notThrown(Exception)
    }

    def "addMissingIpPools creates pools with all fields"() {
        given:
        def poolType = new NetworkPoolType(code: 'scvmm')
        def network = new Network(id: 1L, externalId: 'net-1')
        def networks = [network]
        def networkMapping = [[ID: 'net-1', Name: 'Network 1']]
        
        def addList = [
            [
                ID: 'pool-1',
                Name: 'Test Pool',
                Subnet: '10.0.0.0/24',
                DefaultGateways: ['10.0.0.1'],
                TotalAddresses: 254,
                AvailableAddresses: 200,
                IPAddressRangeStart: '10.0.0.10',
                IPAddressRangeEnd: '10.0.0.253',
                NetworkID: 'net-1',
                SubnetID: 'subnet-1'
            ]
        ]
        
        def mockNetworkPoolRangeService = Mock(com.morpheusdata.core.network.MorpheusNetworkPoolRangeService) {
            bulkCreate(_ as List) >> Single.just(true)
        }
        def mockNetworkPoolService = Mock(com.morpheusdata.core.network.MorpheusNetworkPoolService) {
            bulkCreate(_ as List) >> Single.just(true)
            poolRange >> mockNetworkPoolRangeService
            bulkSave(_ as List) >> Single.just(true)
        }
        def mockNetworkService = Mock(com.morpheusdata.core.network.MorpheusNetworkService) {
            getPool() >> mockNetworkPoolService
            save(_ as Network) >> Single.just(network)
        }
        def mockCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService) {
            getNetwork() >> mockNetworkService
        }
        def mockResourcePermissionService = Mock(com.morpheusdata.core.MorpheusResourcePermissionService) {
            bulkCreate(_ as List) >> Single.just(true)
        }
        def mockAsyncServices = Mock(com.morpheusdata.core.MorpheusAsyncServices) {
            getCloud() >> mockCloudService
            getResourcePermission() >> mockResourcePermissionService
        }
        
        mockContext.getAsync() >> mockAsyncServices

        when:
        sync.addMissingIpPools(addList, networks, poolType, networkMapping)

        then:
        1 * mockLog.debug("addMissingIpPools: 1")
        notThrown(Exception)
    }

    def "addMissingIpPools creates pools without IP ranges"() {
        given:
        def poolType = new NetworkPoolType(code: 'scvmm')
        def networks = []
        def networkMapping = []
        
        def addList = [
            [
                ID: 'pool-1',
                Name: 'Test Pool',
                Subnet: '10.0.0.0/24',
                DefaultGateways: ['10.0.0.1'],
                TotalAddresses: 254,
                AvailableAddresses: 200
                // No IPAddressRangeStart/End
            ]
        ]
        
        def mockNetworkPoolService = Mock(com.morpheusdata.core.network.MorpheusNetworkPoolService) {
            bulkCreate(_ as List) >> Single.just(true)
        }
        def mockNetworkService = Mock(com.morpheusdata.core.network.MorpheusNetworkService) {
            getPool() >> mockNetworkPoolService
        }
        def mockCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService) {
            getNetwork() >> mockNetworkService
        }
        def mockResourcePermissionService = Mock(com.morpheusdata.core.MorpheusResourcePermissionService) {
            bulkCreate(_ as List) >> Single.just(true)
        }
        def mockAsyncServices = Mock(com.morpheusdata.core.MorpheusAsyncServices) {
            getCloud() >> mockCloudService
            getResourcePermission() >> mockResourcePermissionService
        }
        
        mockContext.getAsync() >> mockAsyncServices

        when:
        sync.addMissingIpPools(addList, networks, poolType, networkMapping)

        then:
        1 * mockLog.debug("addMissingIpPools: 1")
        notThrown(Exception)
    }

    def "addMissingIpPools handles exception gracefully"() {
        given:
        def poolType = new NetworkPoolType(code: 'scvmm')
        def networks = []
        def networkMapping = []
        def addList = [
            [
                ID: 'pool-1',
                Name: 'Test',
                Subnet: 'invalid-subnet',
                TotalAddresses: 100
            ]
        ]

        when:
        sync.addMissingIpPools(addList, networks, poolType, networkMapping)

        then:
        1 * mockLog.debug("addMissingIpPools: 1")
        1 * mockLog.error(_ as String, _ as Exception)
        notThrown(Exception)
    }

    def "updateNetworkForPool updates network with pool info"() {
        given:
        def pool = new NetworkPool(
            id: 1L, 
            externalId: 'pool-1',
            gateway: '10.0.0.1',
            dnsServers: ['8.8.8.8', '8.8.4.4'],
            netmask: '255.255.255.0'
        )
        def network = new Network(
            id: 1L, 
            externalId: 'net-1',
            pool: null,
            gateway: null,
            dnsPrimary: null,
            dnsSecondary: null,
            netmask: null,
            allowStaticOverride: false
        )
        def networks = [network]
        def networkMapping = [[ID: 'net-1', Name: 'Network 1']]
        
        def mockNetworkService = Mock(com.morpheusdata.core.network.MorpheusNetworkService) {
            save(_ as Network) >> Single.just(network)
        }
        def mockCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService) {
            getNetwork() >> mockNetworkService
        }
        def mockAsyncServices = Mock(com.morpheusdata.core.MorpheusAsyncServices) {
            getCloud() >> mockCloudService
        }
        
        mockContext.getAsync() >> mockAsyncServices

        when:
        sync.updateNetworkForPool(networks, pool, 'net-1', null, networkMapping)

        then:
        1 * mockLog.debug(_ as String)
        1 * mockNetworkService.save(_ as Network)
        notThrown(Exception)
    }

    def "updateNetworkForPool handles subnet not found"() {
        given:
        def pool = new NetworkPool(id: 1L, externalId: 'pool-1')
        def network = new Network(
            id: 1L, 
            externalId: 'net-1',
            pool: pool,
            subnets: []
        )
        def networks = [network]
        def networkMapping = [[ID: 'net-1', Name: 'Network 1']]

        when:
        sync.updateNetworkForPool(networks, pool, 'net-1', 'subnet-1', networkMapping)

        then:
        1 * mockLog.debug(_ as String)
        notThrown(Exception)
    }

    def "updateNetworkForPool logs debug message"() {
        given:
        def pool = new NetworkPool(id: 1L, externalId: 'pool-1')
        def networks = []
        def networkMapping = []

        when:
        sync.updateNetworkForPool(networks, pool, 'net-1', null, networkMapping)

        then:
        1 * mockLog.debug(_ as String)
        notThrown(Exception)
    }

    def "updateNetworkForPool handles network not found"() {
        given:
        def pool = new NetworkPool(id: 1L, externalId: 'pool-1')
        def networks = []
        def networkMapping = []

        when:
        sync.updateNetworkForPool(networks, pool, 'net-1', null, networkMapping)

        then:
        1 * mockLog.debug(_ as String)
        notThrown(Exception)
    }

    def "updateNetworkForPool handles subnet not found"() {
        given:
        def pool = new NetworkPool(id: 1L, externalId: 'pool-1')
        def network = new Network(
            id: 1L, 
            externalId: 'net-1',
            pool: pool,
            subnets: []
        )
        def networks = [network]
        def networkMapping = [[ID: 'net-1', Name: 'Network 1']]

        when:
        sync.updateNetworkForPool(networks, pool, 'net-1', 'subnet-1', networkMapping)

        then:
        1 * mockLog.debug(_ as String)
        notThrown(Exception)
    }



    def "updateMatchedIpPools handles empty update list"() {
        when:
        sync.updateMatchedIpPools([], [], [])

        then:
        1 * mockLog.debug("updateMatchedIpPools : 0")
        notThrown(Exception)
    }

    def "updateMatchedIpPools updates pool with new IP range"() {
        given:
        def existingPool = new NetworkPool(
            id: 1L, 
            externalId: 'pool-1',
            name: 'Old Name',
            ipRanges: []
        )
        def masterItem = [
            ID: 'pool-1',
            Name: 'New Name',
            Subnet: '10.0.0.0/24',
            DefaultGateways: ['10.0.0.1'],
            TotalAddresses: 254,
            AvailableAddresses: 200,
            IPAddressRangeStart: '10.0.0.10',
            IPAddressRangeEnd: '10.0.0.253',
            NetworkID: 'net-1',
            SubnetID: 'subnet-1'
        ]
        
        def updateItem = new com.morpheusdata.core.util.SyncTask.UpdateItem(
            existingItem: existingPool,
            masterItem: masterItem
        )
        
        def mockNetworkPoolRangeService = Mock(com.morpheusdata.core.network.MorpheusNetworkPoolRangeService) {
            create(_ as NetworkPoolRange) >> Single.just(true)
        }
        def mockNetworkPoolService = Mock(com.morpheusdata.core.network.MorpheusNetworkPoolService) {
            poolRange >> mockNetworkPoolRangeService
            save(_ as NetworkPool) >> Single.just(existingPool)
        }
        def mockNetworkService = Mock(com.morpheusdata.core.network.MorpheusNetworkService) {
            getPool() >> mockNetworkPoolService
        }
        def mockCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService) {
            getNetwork() >> mockNetworkService
        }
        def mockResourcePermissionService = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService) {
            find(_ as DataQuery) >> null
        }
        def mockAsyncResourcePermissionService = Mock(com.morpheusdata.core.MorpheusResourcePermissionService) {
            create(_ as ResourcePermission) >> Single.just(true)
        }
        def mockServices = Mock(com.morpheusdata.core.MorpheusServices) {
            getResourcePermission() >> mockResourcePermissionService
        }
        def mockAsyncServices = Mock(com.morpheusdata.core.MorpheusAsyncServices) {
            getCloud() >> mockCloudService
            getResourcePermission() >> mockAsyncResourcePermissionService
        }
        
        mockContext.getServices() >> mockServices
        mockContext.getAsync() >> mockAsyncServices

        when:
        sync.updateMatchedIpPools([updateItem], [], [])

        then:
        1 * mockLog.debug("updateMatchedIpPools : 1")
        notThrown(Exception)
    }

    def "updateMatchedIpPools updates existing IP range"() {
        given:
        def existingRange = new NetworkPoolRange(
            id: 1L,
            startAddress: '10.0.0.10',
            endAddress: '10.0.0.100',
            addressCount: 90,
            externalId: 'pool-1'
        )
        def existingPool = new NetworkPool(
            id: 1L, 
            externalId: 'pool-1',
            name: 'Test Pool',
            displayName: 'Test Pool (10.0.0.0/24)',
            ipCount: 254,
            ipFreeCount: 200,
            gateway: '10.0.0.1',
            netmask: '255.255.255.0',
            subnetAddress: '10.0.0.0',
            ipRanges: [existingRange]
        )
        def masterItem = [
            ID: 'pool-1',
            Name: 'Test Pool',
            Subnet: '10.0.0.0/24',
            DefaultGateways: ['10.0.0.1'],
            TotalAddresses: 254,
            AvailableAddresses: 200,
            IPAddressRangeStart: '10.0.0.10',
            IPAddressRangeEnd: '10.0.0.253',
            NetworkID: 'net-1',
            SubnetID: 'subnet-1'
        ]
        
        def updateItem = new com.morpheusdata.core.util.SyncTask.UpdateItem(
            existingItem: existingPool,
            masterItem: masterItem
        )
        
        def mockNetworkPoolRangeService = Mock(com.morpheusdata.core.network.MorpheusNetworkPoolRangeService) {
            save(_ as NetworkPoolRange) >> Single.just(existingRange)
        }
        def mockNetworkPoolService = Mock(com.morpheusdata.core.network.MorpheusNetworkPoolService) {
            poolRange >> mockNetworkPoolRangeService
        }
        def mockNetworkService = Mock(com.morpheusdata.core.network.MorpheusNetworkService) {
            getPool() >> mockNetworkPoolService
        }
        def mockCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService) {
            getNetwork() >> mockNetworkService
        }
        def mockResourcePermissionService = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService) {
            find(_ as DataQuery) >> new ResourcePermission(id: 1L)
        }
        def mockServices = Mock(com.morpheusdata.core.MorpheusServices) {
            getResourcePermission() >> mockResourcePermissionService
        }
        def mockAsyncServices = Mock(com.morpheusdata.core.MorpheusAsyncServices) {
            getCloud() >> mockCloudService
        }
        
        mockContext.getServices() >> mockServices
        mockContext.getAsync() >> mockAsyncServices

        when:
        sync.updateMatchedIpPools([updateItem], [], [])

        then:
        1 * mockLog.debug("updateMatchedIpPools : 1")
        notThrown(Exception)
    }

    def "updateMatchedIpPools updates pool fields when changed"() {
        given:
        def existingPool = new NetworkPool(
            id: 1L, 
            externalId: 'pool-1',
            name: 'Old Name',
            displayName: 'Old Display',
            ipCount: 100,
            ipFreeCount: 50,
            gateway: '10.0.0.254',
            netmask: '255.255.0.0',
            subnetAddress: '10.0.0.0',
            ipRanges: []
        )
        def masterItem = [
            ID: 'pool-1',
            Name: 'New Name',
            Subnet: '10.0.0.0/24',
            DefaultGateways: ['10.0.0.1'],
            TotalAddresses: 254,
            AvailableAddresses: 200
        ]
        
        def updateItem = new com.morpheusdata.core.util.SyncTask.UpdateItem(
            existingItem: existingPool,
            masterItem: masterItem
        )
        
        def mockNetworkPoolService = Mock(com.morpheusdata.core.network.MorpheusNetworkPoolService) {
            save(_ as NetworkPool) >> Single.just(existingPool)
        }
        def mockNetworkService = Mock(com.morpheusdata.core.network.MorpheusNetworkService) {
            getPool() >> mockNetworkPoolService
        }
        def mockCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService) {
            getNetwork() >> mockNetworkService
        }
        def mockResourcePermissionService = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService) {
            find(_ as DataQuery) >> new ResourcePermission(id: 1L)
        }
        def mockServices = Mock(com.morpheusdata.core.MorpheusServices) {
            getResourcePermission() >> mockResourcePermissionService
        }
        def mockAsyncServices = Mock(com.morpheusdata.core.MorpheusAsyncServices) {
            getCloud() >> mockCloudService
        }
        
        mockContext.getServices() >> mockServices
        mockContext.getAsync() >> mockAsyncServices

        when:
        sync.updateMatchedIpPools([updateItem], [], [])

        then:
        1 * mockLog.debug("updateMatchedIpPools : 1")
        1 * mockNetworkPoolService.save(_ as NetworkPool)
        notThrown(Exception)
    }

    def "updateMatchedIpPools handles null existing item"() {
        given:
        def updateItem = new com.morpheusdata.core.util.SyncTask.UpdateItem(
            existingItem: null,
            masterItem: [ID: 'pool-1']
        )

        when:
        sync.updateMatchedIpPools([updateItem], [], [])

        then:
        1 * mockLog.debug("updateMatchedIpPools : 1")
        notThrown(Exception)
    }

    def "updateMatchedIpPools creates missing resource permission"() {
        given:
        def existingPool = new NetworkPool(
            id: 1L, 
            externalId: 'pool-1',
            name: 'Test Pool',
            displayName: 'Test Pool (10.0.0.0/24)',
            ipCount: 254,
            ipFreeCount: 200,
            gateway: '10.0.0.1',
            netmask: '255.255.255.0',
            subnetAddress: '10.0.0.0',
            ipRanges: []
        )
        def masterItem = [
            ID: 'pool-1',
            Name: 'Test Pool',
            Subnet: '10.0.0.0/24',
            DefaultGateways: ['10.0.0.1'],
            TotalAddresses: 254,
            AvailableAddresses: 200
        ]
        
        def updateItem = new com.morpheusdata.core.util.SyncTask.UpdateItem(
            existingItem: existingPool,
            masterItem: masterItem
        )
        
        def mockResourcePermissionService = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService) {
            find(_ as DataQuery) >> null
        }
        def mockAsyncResourcePermissionService = Mock(com.morpheusdata.core.MorpheusResourcePermissionService) {
            create(_ as ResourcePermission) >> Single.just(true)
        }
        def mockServices = Mock(com.morpheusdata.core.MorpheusServices) {
            getResourcePermission() >> mockResourcePermissionService
        }
        def mockAsyncServices = Mock(com.morpheusdata.core.MorpheusAsyncServices) {
            getResourcePermission() >> mockAsyncResourcePermissionService
        }
        
        mockContext.getServices() >> mockServices
        mockContext.getAsync() >> mockAsyncServices

        when:
        sync.updateMatchedIpPools([updateItem], [], [])

        then:
        1 * mockLog.debug("updateMatchedIpPools : 1")
        1 * mockAsyncResourcePermissionService.create(_ as ResourcePermission)
        notThrown(Exception)
    }

    def "updateMatchedIpPools handles exception during update"() {
        given:
        def existingPool = new NetworkPool(id: 1L, externalId: 'pool-1')
        def masterItem = [
            ID: 'pool-1',
            Name: 'Test',
            Subnet: 'invalid-subnet'
        ]

        def updateItem = new com.morpheusdata.core.util.SyncTask.UpdateItem(
            existingItem: existingPool,
            masterItem: masterItem
        )

        when:
        sync.updateMatchedIpPools([updateItem], [], [])

        then:
        1 * mockLog.debug("updateMatchedIpPools : 1")
        1 * mockLog.error(_ as String, _ as Exception)
        notThrown(Exception)
    }

    def "updateNetworkForPool handles subnet with matching pool"() {
        given:
        def pool = new NetworkPool(
            id: 1L, 
            externalId: 'pool-1',
            gateway: '10.0.0.1',
            dnsServers: ['8.8.8.8', '8.8.4.4'],
            netmask: '255.255.255.0'
        )
        def subnet = new NetworkSubnet(
            id: 1L,
            externalId: 'subnet-1-extra',
            pool: pool,
            gateway: '10.0.0.1',
            dnsPrimary: '8.8.8.8',
            dnsSecondary: '8.8.4.4',
            netmask: '255.255.255.0'
        )
        def subnetObj = new NetworkSubnet(id: 1L, externalId: 'subnet-1-extra')
        def network = new Network(
            id: 1L, 
            externalId: 'net-1',
            pool: pool,
            subnets: [subnetObj]
        )
        def networks = [network]
        def networkMapping = [[ID: 'net-1', Name: 'Network 1']]
        
        def mockNetworkSubnetService = Mock(com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkSubnetService)
        def mockServices = Mock(com.morpheusdata.core.MorpheusServices)
        
        mockContext.getServices() >> mockServices
        mockServices.getNetworkSubnet() >> mockNetworkSubnetService
        mockNetworkSubnetService.get(1L) >> subnet

        when:
        sync.updateNetworkForPool(networks, pool, 'net-1', 'subnet-1', networkMapping)

        then:
        1 * mockLog.debug(_ as String)
        notThrown(Exception)
    }

    def "updateNetworkForPool skips subnet save when no changes needed"() {
        given:
        def pool = new NetworkPool(
            id: 1L, 
            externalId: 'pool-1',
            gateway: '10.0.0.1',
            dnsServers: ['8.8.8.8'],
            netmask: '255.255.255.0'
        )
        def subnet = new NetworkSubnet(
            id: 1L,
            externalId: 'subnet-1',
            pool: pool,
            gateway: '10.0.0.1',
            dnsPrimary: '8.8.8.8',
            dnsSecondary: null,
            netmask: '255.255.255.0'
        )
        def subnetObj = new NetworkSubnet(id: 1L, externalId: 'subnet-1')
        def network = new Network(
            id: 1L, 
            externalId: 'net-1',
            pool: pool,
            gateway: '10.0.0.1',
            dnsPrimary: '8.8.8.8',
            dnsSecondary: null,
            netmask: '255.255.255.0',
            allowStaticOverride: true,
            subnets: [subnetObj]
        )
        def networks = [network]
        def networkMapping = [[ID: 'net-1', Name: 'Network 1']]
        
        def mockNetworkSubnetService = Mock(com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkSubnetService)
        def mockServices = Mock(com.morpheusdata.core.MorpheusServices)
        
        mockContext.getServices() >> mockServices
        mockServices.getNetworkSubnet() >> mockNetworkSubnetService
        mockNetworkSubnetService.get(1L) >> subnet

        when:
        sync.updateNetworkForPool(networks, pool, 'net-1', 'subnet-1', networkMapping)

        then:
        1 * mockLog.debug(_ as String)
        notThrown(Exception)
    }

    def "updateNetworkForPool updates network with secondary DNS"() {
        given:
        def pool = new NetworkPool(
            id: 1L, 
            externalId: 'pool-1',
            gateway: '10.0.0.1',
            dnsServers: ['8.8.8.8', '8.8.4.4'],
            netmask: '255.255.255.0'
        )
        def network = new Network(
            id: 1L, 
            externalId: 'net-1',
            pool: pool,
            gateway: '10.0.0.1',
            dnsPrimary: '1.1.1.1',
            dnsSecondary: null,
            netmask: '255.255.255.0',
            allowStaticOverride: true
        )
        def networks = [network]
        def networkMapping = [[ID: 'net-1', Name: 'Network 1']]
        
        def mockNetworkService = Mock(com.morpheusdata.core.network.MorpheusNetworkService) {
            save(_ as Network) >> Single.just(network)
        }
        def mockCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService) {
            getNetwork() >> mockNetworkService
        }
        def mockAsyncServices = Mock(com.morpheusdata.core.MorpheusAsyncServices) {
            getCloud() >> mockCloudService
        }
        
        mockContext.getAsync() >> mockAsyncServices

        when:
        sync.updateNetworkForPool(networks, pool, 'net-1', null, networkMapping)

        then:
        1 * mockLog.debug(_ as String)
        1 * mockNetworkService.save(_ as Network)
        notThrown(Exception)
    }

    def "updateNetworkForPool skips network save when already configured"() {
        given:
        def pool = new NetworkPool(
            id: 1L, 
            externalId: 'pool-1',
            gateway: '10.0.0.1',
            dnsServers: ['8.8.8.8', '8.8.4.4'],
            netmask: '255.255.255.0'
        )
        def network = new Network(
            id: 1L, 
            externalId: 'net-1',
            pool: pool,
            gateway: '10.0.0.1',
            dnsPrimary: '8.8.8.8',
            dnsSecondary: '8.8.4.4',
            netmask: '255.255.255.0',
            allowStaticOverride: true
        )
        def networks = [network]
        def networkMapping = [[ID: 'net-1', Name: 'Network 1']]

        when:
        sync.updateNetworkForPool(networks, pool, 'net-1', null, networkMapping)

        then:
        1 * mockLog.debug(_ as String)
        notThrown(Exception)
    }

    def "updateNetworkForPool handles exception in subnet processing"() {
        given:
        def pool = new NetworkPool(id: 1L, externalId: 'pool-1')
        def subnetObj = new NetworkSubnet(id: 1L, externalId: 'subnet-1')
        def network = new Network(
            id: 1L, 
            externalId: 'net-1',
            pool: pool,
            subnets: [subnetObj]
        )
        def networks = [network]
        def networkMapping = [[ID: 'net-1', Name: 'Network 1']]
        
        def mockNetworkSubnetService = Mock(com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkSubnetService)
        def mockServices = Mock(com.morpheusdata.core.MorpheusServices)
        
        mockContext.getServices() >> mockServices
        mockServices.getNetworkSubnet() >> mockNetworkSubnetService
        mockNetworkSubnetService.get(1L) >> { throw new RuntimeException("Subnet error") }

        when:
        sync.updateNetworkForPool(networks, pool, 'net-1', 'subnet-1', networkMapping)

        then:
        1 * mockLog.debug(_ as String)
        1 * mockLog.error(_ as String, _ as Exception)
        notThrown(Exception)
    }

    def "addMissingIpPools creates multiple pools"() {
        given:
        def poolType = new NetworkPoolType(code: 'scvmm')
        def networks = []
        def networkMapping = []
        
        def addList = [
            [
                ID: 'pool-1',
                Name: 'Pool 1',
                Subnet: '10.0.0.0/24',
                DefaultGateways: ['10.0.0.1'],
                TotalAddresses: 254,
                AvailableAddresses: 200,
                IPAddressRangeStart: '10.0.0.10',
                IPAddressRangeEnd: '10.0.0.253'
            ],
            [
                ID: 'pool-2',
                Name: 'Pool 2',
                Subnet: '192.168.1.0/24',
                DefaultGateways: ['192.168.1.1'],
                TotalAddresses: 254,
                AvailableAddresses: 150,
                IPAddressRangeStart: '192.168.1.10',
                IPAddressRangeEnd: '192.168.1.253'
            ]
        ]
        
        def mockNetworkPoolRangeService = Mock(com.morpheusdata.core.network.MorpheusNetworkPoolRangeService) {
            bulkCreate(_ as List) >> Single.just(true)
        }
        def mockNetworkPoolService = Mock(com.morpheusdata.core.network.MorpheusNetworkPoolService) {
            bulkCreate(_ as List) >> Single.just(true)
            poolRange >> mockNetworkPoolRangeService
            bulkSave(_ as List) >> Single.just(true)
        }
        def mockNetworkService = Mock(com.morpheusdata.core.network.MorpheusNetworkService) {
            getPool() >> mockNetworkPoolService
        }
        def mockCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService) {
            getNetwork() >> mockNetworkService
        }
        def mockResourcePermissionService = Mock(com.morpheusdata.core.MorpheusResourcePermissionService) {
            bulkCreate(_ as List) >> Single.just(true)
        }
        def mockAsyncServices = Mock(com.morpheusdata.core.MorpheusAsyncServices) {
            getCloud() >> mockCloudService
            getResourcePermission() >> mockResourcePermissionService
        }
        
        mockContext.getAsync() >> mockAsyncServices

        when:
        sync.addMissingIpPools(addList, networks, poolType, networkMapping)

        then:
        1 * mockLog.debug("addMissingIpPools: 2")
        notThrown(Exception)
    }

    def "addMissingIpPools handles pools with no gateway"() {
        given:
        def poolType = new NetworkPoolType(code: 'scvmm')
        def networks = []
        def networkMapping = []
        
        def addList = [
            [
                ID: 'pool-1',
                Name: 'Pool Without Gateway',
                Subnet: '10.0.0.0/24',
                DefaultGateways: [],
                TotalAddresses: 254,
                AvailableAddresses: 200,
                IPAddressRangeStart: '10.0.0.10',
                IPAddressRangeEnd: '10.0.0.253'
            ]
        ]
        
        def mockNetworkPoolRangeService = Mock(com.morpheusdata.core.network.MorpheusNetworkPoolRangeService) {
            bulkCreate(_ as List) >> Single.just(true)
        }
        def mockNetworkPoolService = Mock(com.morpheusdata.core.network.MorpheusNetworkPoolService) {
            bulkCreate(_ as List) >> Single.just(true)
            poolRange >> mockNetworkPoolRangeService
            bulkSave(_ as List) >> Single.just(true)
        }
        def mockNetworkService = Mock(com.morpheusdata.core.network.MorpheusNetworkService) {
            getPool() >> mockNetworkPoolService
        }
        def mockCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService) {
            getNetwork() >> mockNetworkService
        }
        def mockResourcePermissionService = Mock(com.morpheusdata.core.MorpheusResourcePermissionService) {
            bulkCreate(_ as List) >> Single.just(true)
        }
        def mockAsyncServices = Mock(com.morpheusdata.core.MorpheusAsyncServices) {
            getCloud() >> mockCloudService
            getResourcePermission() >> mockResourcePermissionService
        }
        
        mockContext.getAsync() >> mockAsyncServices

        when:
        sync.addMissingIpPools(addList, networks, poolType, networkMapping)

        then:
        1 * mockLog.debug("addMissingIpPools: 1")
        notThrown(Exception)
    }

    def "execute with no IP pools returns gracefully"() {
        given:
        def mockComputeServerService = Mock(com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService) {
            find(_ as DataQuery) >> null
        }
        def mockSyncNetworkService = Mock(com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService) {
            list(_ as DataQuery) >> []
        }
        def mockSyncCloudService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService) {
            getNetwork() >> mockSyncNetworkService
        }
        def mockServices = Mock(com.morpheusdata.core.MorpheusServices) {
            getCloud() >> mockSyncCloudService
            getComputeServer() >> mockComputeServerService
        }
        
        mockContext.getServices() >> mockServices
        mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, mockCloud, null) >> [:]
        mockApiService.listNetworkIPPools(_) >> [success: true, ipPools: [], networkMapping: []]

        def mockNetworkPoolService = Mock(com.morpheusdata.core.network.MorpheusNetworkPoolService) {
            listIdentityProjections(_ as DataQuery) >> Observable.empty()
        }
        def mockNetworkService = Mock(com.morpheusdata.core.network.MorpheusNetworkService) {
            getPool() >> mockNetworkPoolService
        }
        def mockCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService) {
            getNetwork() >> mockNetworkService
        }
        def mockAsyncServices = Mock(com.morpheusdata.core.MorpheusAsyncServices) {
            getCloud() >> mockCloudService
        }
        
        mockContext.getAsync() >> mockAsyncServices

        when:
        sync.execute()

        then:
        1 * mockLog.debug('IpPoolsSync')
        notThrown(Exception)
    }

    def "updateNetworkForPool handles empty dnsServers list"() {
        given:
        def pool = new NetworkPool(
            id: 1L, 
            externalId: 'pool-1',
            gateway: '10.0.0.1',
            dnsServers: [],
            netmask: '255.255.255.0'
        )
        def network = new Network(
            id: 1L, 
            externalId: 'net-1',
            pool: pool,
            gateway: '10.0.0.1',
            dnsPrimary: '8.8.8.8',
            dnsSecondary: '8.8.4.4',
            netmask: '255.255.255.0',
            allowStaticOverride: true
        )
        def networks = [network]
        def networkMapping = [[ID: 'net-1', Name: 'Network 1']]

        when:
        sync.updateNetworkForPool(networks, pool, 'net-1', null, networkMapping)

        then:
        1 * mockLog.debug(_ as String)
        notThrown(Exception)
    }

    def "updateNetworkForPool handles null dnsServers"() {
        given:
        def pool = new NetworkPool(
            id: 1L, 
            externalId: 'pool-1',
            gateway: '10.0.0.1',
            dnsServers: null,
            netmask: '255.255.255.0'
        )
        def network = new Network(
            id: 1L, 
            externalId: 'net-1',
            pool: null,
            gateway: null,
            dnsPrimary: null,
            dnsSecondary: null,
            netmask: null,
            allowStaticOverride: false
        )
        def networks = [network]
        def networkMapping = [[ID: 'net-1', Name: 'Network 1']]
        
        def mockNetworkService = Mock(com.morpheusdata.core.network.MorpheusNetworkService) {
            save(_ as Network) >> Single.just(network)
        }
        def mockCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService) {
            getNetwork() >> mockNetworkService
        }
        def mockAsyncServices = Mock(com.morpheusdata.core.MorpheusAsyncServices) {
            getCloud() >> mockCloudService
        }
        
        mockContext.getAsync() >> mockAsyncServices

        when:
        sync.updateNetworkForPool(networks, pool, 'net-1', null, networkMapping)

        then:
        1 * mockLog.debug(_ as String)
        1 * mockNetworkService.save(_ as Network)
        notThrown(Exception)
    }

    def "updateNetworkForPool handles pool without gateway"() {
        given:
        def pool = new NetworkPool(
            id: 1L, 
            externalId: 'pool-1',
            gateway: null,
            dnsServers: ['8.8.8.8'],
            netmask: '255.255.255.0'
        )
        def network = new Network(
            id: 1L, 
            externalId: 'net-1',
            pool: null,
            gateway: null,
            dnsPrimary: null,
            netmask: null,
            allowStaticOverride: false
        )
        def networks = [network]
        def networkMapping = [[ID: 'net-1', Name: 'Network 1']]
        
        def mockNetworkService = Mock(com.morpheusdata.core.network.MorpheusNetworkService) {
            save(_ as Network) >> Single.just(network)
        }
        def mockCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService) {
            getNetwork() >> mockNetworkService
        }
        def mockAsyncServices = Mock(com.morpheusdata.core.MorpheusAsyncServices) {
            getCloud() >> mockCloudService
        }
        
        mockContext.getAsync() >> mockAsyncServices

        when:
        sync.updateNetworkForPool(networks, pool, 'net-1', null, networkMapping)

        then:
        1 * mockLog.debug(_ as String)
        1 * mockNetworkService.save(_ as Network)
        notThrown(Exception)
    }

    def "updateNetworkForPool handles pool without netmask"() {
        given:
        def pool = new NetworkPool(
            id: 1L, 
            externalId: 'pool-1',
            gateway: '10.0.0.1',
            dnsServers: [],
            netmask: null
        )
        def network = new Network(
            id: 1L, 
            externalId: 'net-1',
            pool: null,
            gateway: null,
            allowStaticOverride: false
        )
        def networks = [network]
        def networkMapping = [[ID: 'net-1', Name: 'Network 1']]
        
        def mockNetworkService = Mock(com.morpheusdata.core.network.MorpheusNetworkService) {
            save(_ as Network) >> Single.just(network)
        }
        def mockCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService) {
            getNetwork() >> mockNetworkService
        }
        def mockAsyncServices = Mock(com.morpheusdata.core.MorpheusAsyncServices) {
            getCloud() >> mockCloudService
        }
        
        mockContext.getAsync() >> mockAsyncServices

        when:
        sync.updateNetworkForPool(networks, pool, 'net-1', null, networkMapping)

        then:
        1 * mockLog.debug(_ as String)
        1 * mockNetworkService.save(_ as Network)
        notThrown(Exception)
    }

    def "updateMatchedIpPools updates range address count"() {
        given:
        def existingRange = new NetworkPoolRange(
            id: 1L,
            startAddress: '10.0.0.10',
            endAddress: '10.0.0.253',
            addressCount: 100,
            externalId: 'pool-1'
        )
        def existingPool = new NetworkPool(
            id: 1L, 
            externalId: 'pool-1',
            name: 'Test Pool',
            displayName: 'Test Pool (10.0.0.0/24)',
            ipCount: 254,
            ipFreeCount: 200,
            gateway: '10.0.0.1',
            netmask: '255.255.255.0',
            subnetAddress: '10.0.0.0',
            ipRanges: [existingRange]
        )
        def masterItem = [
            ID: 'pool-1',
            Name: 'Test Pool',
            Subnet: '10.0.0.0/24',
            DefaultGateways: ['10.0.0.1'],
            TotalAddresses: 254,
            AvailableAddresses: 200,
            IPAddressRangeStart: '10.0.0.10',
            IPAddressRangeEnd: '10.0.0.253'
        ]
        
        def updateItem = new com.morpheusdata.core.util.SyncTask.UpdateItem(
            existingItem: existingPool,
            masterItem: masterItem
        )
        
        def mockNetworkPoolRangeService = Mock(com.morpheusdata.core.network.MorpheusNetworkPoolRangeService)
        def mockNetworkPoolService = Mock(com.morpheusdata.core.network.MorpheusNetworkPoolService)
        def mockNetworkService = Mock(com.morpheusdata.core.network.MorpheusNetworkService)
        def mockCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def mockResourcePermissionService = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService)
        def mockServices = Mock(com.morpheusdata.core.MorpheusServices)
        def mockAsyncServices = Mock(com.morpheusdata.core.MorpheusAsyncServices)
        
        mockContext.getServices() >> mockServices
        mockContext.getAsync() >> mockAsyncServices
        mockNetworkPoolService.poolRange >> mockNetworkPoolRangeService
        mockNetworkService.getPool() >> mockNetworkPoolService
        mockCloudService.getNetwork() >> mockNetworkService
        mockAsyncServices.getCloud() >> mockCloudService
        mockServices.getResourcePermission() >> mockResourcePermissionService
        mockResourcePermissionService.find(_ as DataQuery) >> new ResourcePermission(id: 1L)
        mockNetworkPoolRangeService.save(_ as NetworkPoolRange) >> Single.just(existingRange)

        when:
        sync.updateMatchedIpPools([updateItem], [], [])

        then:
        1 * mockLog.debug("updateMatchedIpPools : 1")
        1 * mockNetworkPoolRangeService.save(_ as NetworkPoolRange)
        notThrown(Exception)
    }
}

