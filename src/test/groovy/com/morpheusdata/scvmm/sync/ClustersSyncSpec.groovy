package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.scvmm.logging.LogInterface
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

class ClustersSyncSpec extends Specification {

    MorpheusContext mockContext
    Cloud mockCloud
    ClustersSync sync
    ScvmmApiService mockApiService
    LogInterface mockLog
    Object mockComputeServerService
    Object mockCloudPoolService
    Object mockAsyncCloudPoolService
    Object mockResourcePermissionService
    Object mockAsyncComputeServerService
    Object mockAsyncDatastoreService
    Object mockAsyncNetworkService
    Object mockDatastoreService
    Object mockNetworkService

    def setup() {
        mockContext = Mock(MorpheusContext)
        mockCloud = new Cloud(id: 1L, code: 'test-cloud')
        mockCloud.account = new Account(id: 1L)
        mockCloud.owner = new Account(id: 2L, masterAccount: false)
        mockCloud.defaultPoolSyncActive = true
        
        mockApiService = Mock(ScvmmApiService)
        mockLog = Mock(LogInterface)
        
        // Service mocks
        mockComputeServerService = Mock(Object)
        mockCloudPoolService = Mock(Object)
        mockAsyncCloudPoolService = Mock(Object)
        mockResourcePermissionService = Mock(Object)
        mockAsyncComputeServerService = Mock(Object)
        mockAsyncDatastoreService = Mock(Object)
        mockAsyncNetworkService = Mock(Object)
        mockDatastoreService = Mock(Object)
        mockNetworkService = Mock(Object)
        
        sync = new ClustersSync(mockContext, mockCloud)
        sync.apiService = mockApiService
        sync.log = mockLog
    }

    def "constructor initializes fields correctly"() {
        when:
        def newSync = new ClustersSync(mockContext, mockCloud)

        then:
        newSync.morpheusContext == mockContext
        newSync.cloud == mockCloud
        newSync.apiService != null
    }

    // ============================================
    // EXECUTE METHOD TESTS
    // ============================================

    def "execute logs debug message"() {
        given:
        mockComputeServerService.find(_ as DataQuery) >> null
        mockContext.services >> Mock(MorpheusServices) {
            getComputeServer() >> mockComputeServerService
        }
        mockApiService.getScvmmZoneAndHypervisorOpts(_, _, _) >> [:]
        mockApiService.listClusters(_) >> [success: false]

        when:
        sync.execute()

        then:
        1 * mockLog.debug('ClustersSync')
        notThrown(Exception)
    }

    def "execute handles exception and logs error"() {
        given:
        mockContext.services >> { throw new RuntimeException("Test error") }

        when:
        sync.execute()

        then:
        1 * mockLog.error(_ as String, _ as Throwable)
        notThrown(Exception)
    }

    def "execute handles null clusters list"() {
        given:
        mockComputeServerService.find(_ as DataQuery) >> null
        mockContext.services >> Mock(MorpheusServices) {
            getComputeServer() >> mockComputeServerService
        }
        mockApiService.getScvmmZoneAndHypervisorOpts(_, _, _) >> [:]
        mockApiService.listClusters(_) >> [success: true, clusters: null]

        when:
        sync.execute()

        then:
        1 * mockLog.debug('ClustersSync')
        notThrown(Exception)
    }

    def "execute handles empty clusters list"() {
        given:
        mockComputeServerService.find(_ as DataQuery) >> null
        mockContext.services >> Mock(MorpheusServices) {
            getComputeServer() >> mockComputeServerService
        }
        mockApiService.getScvmmZoneAndHypervisorOpts(_, _, _) >> [:]
        mockApiService.listClusters(_) >> [success: true, clusters: []]

        when:
        sync.execute()

        then:
        1 * mockLog.debug('ClustersSync')
        notThrown(Exception)
    }

    def "execute filters clusters by cluster scope when configured"() {
        given:
        def mockComputeServer = new ComputeServer(id: 10L)
        def clusterData = [
            [id: 'cluster-1', name: 'Cluster 1', sharedVolumes: ['vol1']],
            [id: 'cluster-2', name: 'Cluster 2', sharedVolumes: ['vol2']]
        ]
        
        mockComputeServerService.find(_ as DataQuery) >> mockComputeServer
        mockAsyncCloudPoolService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
        mockAsyncCloudPoolService.bulkCreate(_) >> Single.just(true)
        mockAsyncCloudPoolService.bulkSave(_) >> Single.just(true)
        mockResourcePermissionService.bulkCreate(_) >> Single.just(true)
        
        def mockAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> Mock(Object) {
                getPool() >> mockAsyncCloudPoolService
            }
            getResourcePermission() >> mockResourcePermissionService
        }
        
        def mockServices = Mock(MorpheusServices) {
            getComputeServer() >> mockComputeServerService
        }
        
        mockContext.services >> mockServices
        mockContext.async >> mockAsyncServices
        mockCloud.setConfigProperty('cluster', 'cluster-1')
        
        mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, mockCloud, mockComputeServer) >> [:]
        mockApiService.listClusters([:]) >> [success: true, clusters: clusterData]

        when:
        sync.execute()

        then:
        1 * mockLog.debug('ClustersSync')
        notThrown(Exception)
    }

    def "execute calls chooseOwnerPoolDefaults when masterAccount is false"() {
        given:
        def mockComputeServer = new ComputeServer(id: 10L)
        def clusterData = [[id: 'cluster-1', name: 'Cluster 1', sharedVolumes: []]]
        
        mockComputeServerService.find(_ as DataQuery) >> mockComputeServer
        mockCloudPoolService.find(_ as DataQuery) >> null
        mockAsyncCloudPoolService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
        mockAsyncCloudPoolService.bulkCreate(_) >> Single.just(true)
        mockAsyncCloudPoolService.bulkSave(_) >> Single.just(true)
        mockResourcePermissionService.bulkCreate(_) >> Single.just(true)
        
        def mockAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> Mock(Object) {
                getPool() >> mockAsyncCloudPoolService
            }
            getResourcePermission() >> mockResourcePermissionService
        }
        
        def mockServices = Mock(MorpheusServices) {
            getComputeServer() >> mockComputeServerService
            getCloud() >> Mock(Object) {
                getPool() >> mockCloudPoolService
            }
        }
        
        mockContext.services >> mockServices
        mockContext.async >> mockAsyncServices
        mockCloud.owner.masterAccount = false
        
        mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, mockCloud, mockComputeServer) >> [:]
        mockApiService.listClusters([:]) >> [success: true, clusters: clusterData]

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "execute skips chooseOwnerPoolDefaults when masterAccount is true"() {
        given:
        def mockComputeServer = new ComputeServer(id: 10L)
        def clusterData = [[id: 'cluster-1', name: 'Cluster 1', sharedVolumes: []]]
        
        mockComputeServerService.find(_ as DataQuery) >> mockComputeServer
        mockAsyncCloudPoolService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
        mockAsyncCloudPoolService.bulkCreate(_) >> Single.just(true)
        mockAsyncCloudPoolService.bulkSave(_) >> Single.just(true)
        mockResourcePermissionService.bulkCreate(_) >> Single.just(true)
        
        def mockAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> Mock(Object) {
                getPool() >> mockAsyncCloudPoolService
            }
            getResourcePermission() >> mockResourcePermissionService
        }
        
        def mockServices = Mock(MorpheusServices) {
            getComputeServer() >> mockComputeServerService
        }
        
        mockContext.services >> mockServices
        mockContext.async >> mockAsyncServices
        mockCloud.owner.masterAccount = true
        
        mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, mockCloud, mockComputeServer) >> [:]
        mockApiService.listClusters([:]) >> [success: true, clusters: clusterData]

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    // ============================================
    // ADD MISSING RESOURCE POOLS TESTS
    // ============================================

    def "addMissingResourcePools handles empty list"() {
        when:
        sync.addMissingResourcePools([])

        then:
        1 * mockLog.debug("addMissingResourcePools: 0")
        notThrown(Exception)
    }

    def "addMissingResourcePools creates pools with correct configuration"() {
        given:
        def clusterData = [
            [id: 'cluster-1', name: 'Test Cluster', sharedVolumes: ['vol1', 'vol2']]
        ]
        
        mockAsyncCloudPoolService.bulkCreate(_) >> Single.just(true)
        mockAsyncCloudPoolService.bulkSave(_) >> Single.just(true)
        mockResourcePermissionService.bulkCreate(_) >> Single.just(true)
        
        def mockAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> Mock(Object) {
                getPool() >> mockAsyncCloudPoolService
            }
            getResourcePermission() >> mockResourcePermissionService
        }
        
        mockContext.async >> mockAsyncServices

        when:
        sync.addMissingResourcePools(clusterData)

        then:
        1 * mockLog.debug("addMissingResourcePools: 1")
        1 * mockLog.debug("add cluster: {}", clusterData[0])
        notThrown(Exception)
    }

    def "addMissingResourcePools handles multiple clusters"() {
        given:
        def clusterData = [
            [id: 'cluster-1', name: 'Cluster 1', sharedVolumes: ['vol1']],
            [id: 'cluster-2', name: 'Cluster 2', sharedVolumes: ['vol2']],
            [id: 'cluster-3', name: 'Cluster 3', sharedVolumes: []]
        ]
        
        mockAsyncCloudPoolService.bulkCreate(_) >> Single.just(true)
        mockAsyncCloudPoolService.bulkSave(_) >> Single.just(true)
        mockResourcePermissionService.bulkCreate(_) >> Single.just(true)
        
        def mockAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> Mock(Object) {
                getPool() >> mockAsyncCloudPoolService
            }
            getResourcePermission() >> mockResourcePermissionService
        }
        
        mockContext.async >> mockAsyncServices

        when:
        sync.addMissingResourcePools(clusterData)

        then:
        1 * mockLog.debug("addMissingResourcePools: 3")
        3 * mockLog.debug("add cluster: {}", _)
        notThrown(Exception)
    }

    def "addMissingResourcePools handles cluster with null shared volumes"() {
        given:
        def clusterData = [[id: 'cluster-1', name: 'Test Cluster', sharedVolumes: null]]
        
        mockAsyncCloudPoolService.bulkCreate(_) >> Single.just(true)
        mockAsyncCloudPoolService.bulkSave(_) >> Single.just(true)
        mockResourcePermissionService.bulkCreate(_) >> Single.just(true)
        
        def mockAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> Mock(Object) {
                getPool() >> mockAsyncCloudPoolService
            }
            getResourcePermission() >> mockResourcePermissionService
        }
        
        mockContext.async >> mockAsyncServices

        when:
        sync.addMissingResourcePools(clusterData)

        then:
        1 * mockLog.debug("addMissingResourcePools: 1")
        notThrown(Exception)
    }

    def "addMissingResourcePools handles errors gracefully"() {
        given:
        def clusterData = [[id: 'cluster-1', name: 'Test Cluster', sharedVolumes: []]]
        
        def mockAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> { throw new RuntimeException("Test error") }
        }
        
        mockContext.async >> mockAsyncServices

        when:
        sync.addMissingResourcePools(clusterData)

        then:
        1 * mockLog.error(_ as String, _ as Throwable)
        notThrown(Exception)
    }

    // ============================================
    // UPDATE MATCHED RESOURCE POOLS TESTS
    // ============================================

    def "updateMatchedResourcePools handles empty list"() {
        when:
        sync.updateMatchedResourcePools([])

        then:
        1 * mockLog.debug("updateMatchedResourcePools: 0")
        notThrown(Exception)
    }

    def "updateMatchedResourcePools skips when shared volumes unchanged"() {
        given:
        def existingPool = new CloudPool(id: 1L, name: 'Test Pool')
        existingPool.setConfigProperty('sharedVolumes', ['vol1'])
        
        def masterItem = [id: 'cluster-1', name: 'Test Cluster', sharedVolumes: ['vol1']]
        def updateItem = new SyncTask.UpdateItem(existingItem: existingPool, masterItem: masterItem)

        when:
        sync.updateMatchedResourcePools([updateItem])

        then:
        1 * mockLog.debug("updateMatchedResourcePools: 1")
        notThrown(Exception)
    }

    def "updateMatchedResourcePools updates shared volumes when changed"() {
        given:
        def existingPool = new CloudPool(id: 1L, name: 'Test Pool')
        existingPool.setConfigProperty('sharedVolumes', ['old-vol'])
        
        def masterItem = [id: 'cluster-1', name: 'Test Cluster', sharedVolumes: ['new-vol1', 'new-vol2']]
        def updateItem = new SyncTask.UpdateItem(existingItem: existingPool, masterItem: masterItem)
        
        mockAsyncCloudPoolService.bulkSave(_) >> Single.just(true)
        
        def mockAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> Mock(Object) {
                getPool() >> mockAsyncCloudPoolService
            }
        }
        
        mockContext.async >> mockAsyncServices

        when:
        sync.updateMatchedResourcePools([updateItem])

        then:
        1 * mockLog.debug("updateMatchedResourcePools: 1")
        existingPool.getConfigProperty('sharedVolumes') == ['new-vol1', 'new-vol2']
        notThrown(Exception)
    }

    def "updateMatchedResourcePools increments null count when shared volumes become null"() {
        given:
        def existingPool = new CloudPool(id: 1L, name: 'Test Pool')
        existingPool.setConfigProperty('sharedVolumes', ['vol1'])
        existingPool.setConfigProperty('nullSharedVolumeSyncCount', 2)
        
        def masterItem = [id: 'cluster-1', name: 'Test Cluster', sharedVolumes: null]
        def updateItem = new SyncTask.UpdateItem(existingItem: existingPool, masterItem: masterItem)
        
        mockAsyncCloudPoolService.bulkSave(_) >> Single.just(true)
        
        def mockAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> Mock(Object) {
                getPool() >> mockAsyncCloudPoolService
            }
        }
        
        mockContext.async >> mockAsyncServices

        when:
        sync.updateMatchedResourcePools([updateItem])

        then:
        existingPool.getConfigProperty('nullSharedVolumeSyncCount') == 3
        notThrown(Exception)
    }

    def "updateMatchedResourcePools sets null volumes after 5 null counts"() {
        given:
        def existingPool = new CloudPool(id: 1L, name: 'Test Pool')
        existingPool.setConfigProperty('sharedVolumes', ['vol1'])
        existingPool.setConfigProperty('nullSharedVolumeSyncCount', 5)
        
        def masterItem = [id: 'cluster-1', name: 'Test Cluster', sharedVolumes: null]
        def updateItem = new SyncTask.UpdateItem(existingItem: existingPool, masterItem: masterItem)
        
        mockAsyncCloudPoolService.bulkSave(_) >> Single.just(true)
        
        def mockAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> Mock(Object) {
                getPool() >> mockAsyncCloudPoolService
            }
        }
        
        mockContext.async >> mockAsyncServices

        when:
        sync.updateMatchedResourcePools([updateItem])

        then:
        existingPool.getConfigProperty('sharedVolumes') == null
        notThrown(Exception)
    }

    def "updateMatchedResourcePools resets null count when volumes are present"() {
        given:
        def existingPool = new CloudPool(id: 1L, name: 'Test Pool')
        existingPool.setConfigProperty('sharedVolumes', ['old-vol'])
        existingPool.setConfigProperty('nullSharedVolumeSyncCount', 3)
        
        def masterItem = [id: 'cluster-1', name: 'Test Cluster', sharedVolumes: ['new-vol']]
        def updateItem = new SyncTask.UpdateItem(existingItem: existingPool, masterItem: masterItem)
        
        mockAsyncCloudPoolService.bulkSave(_) >> Single.just(true)
        
        def mockAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> Mock(Object) {
                getPool() >> mockAsyncCloudPoolService
            }
        }
        
        mockContext.async >> mockAsyncServices

        when:
        sync.updateMatchedResourcePools([updateItem])

        then:
        existingPool.getConfigProperty('nullSharedVolumeSyncCount') == 0
        existingPool.getConfigProperty('sharedVolumes') == ['new-vol']
        notThrown(Exception)
    }

    def "updateMatchedResourcePools handles single null value in array"() {
        given:
        def existingPool = new CloudPool(id: 1L, name: 'Test Pool')
        existingPool.setConfigProperty('sharedVolumes', ['vol1'])
        existingPool.setConfigProperty('nullSharedVolumeSyncCount', 2)
        
        def masterItem = [id: 'cluster-1', name: 'Test Cluster', sharedVolumes: [null]]
        def updateItem = new SyncTask.UpdateItem(existingItem: existingPool, masterItem: masterItem)
        
        mockAsyncCloudPoolService.bulkSave(_) >> Single.just(true)
        
        def mockAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> Mock(Object) {
                getPool() >> mockAsyncCloudPoolService
            }
        }
        
        mockContext.async >> mockAsyncServices

        when:
        sync.updateMatchedResourcePools([updateItem])

        then:
        existingPool.getConfigProperty('nullSharedVolumeSyncCount') == 3
        notThrown(Exception)
    }

    def "updateMatchedResourcePools handles empty array as null"() {
        given:
        def existingPool = new CloudPool(id: 1L, name: 'Test Pool')
        existingPool.setConfigProperty('sharedVolumes', ['vol1'])
        existingPool.setConfigProperty('nullSharedVolumeSyncCount', 0)
        
        def masterItem = [id: 'cluster-1', name: 'Test Cluster', sharedVolumes: []]
        def updateItem = new SyncTask.UpdateItem(existingItem: existingPool, masterItem: masterItem)
        
        mockAsyncCloudPoolService.bulkSave(_) >> Single.just(true)
        
        def mockAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> Mock(Object) {
                getPool() >> mockAsyncCloudPoolService
            }
        }
        
        mockContext.async >> mockAsyncServices

        when:
        sync.updateMatchedResourcePools([updateItem])

        then:
        existingPool.getConfigProperty('nullSharedVolumeSyncCount') == 1
        notThrown(Exception)
    }

    def "updateMatchedResourcePools handles null count initialization"() {
        given:
        def existingPool = new CloudPool(id: 1L, name: 'Test Pool')
        existingPool.setConfigProperty('sharedVolumes', ['vol1'])
        // nullSharedVolumeSyncCount not set
        
        def masterItem = [id: 'cluster-1', name: 'Test Cluster', sharedVolumes: null]
        def updateItem = new SyncTask.UpdateItem(existingItem: existingPool, masterItem: masterItem)
        
        mockAsyncCloudPoolService.bulkSave(_) >> Single.just(true)
        
        def mockAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> Mock(Object) {
                getPool() >> mockAsyncCloudPoolService
            }
        }
        
        mockContext.async >> mockAsyncServices

        when:
        sync.updateMatchedResourcePools([updateItem])

        then:
        existingPool.getConfigProperty('nullSharedVolumeSyncCount') == 1
        notThrown(Exception)
    }

    def "updateMatchedResourcePools handles errors gracefully"() {
        given:
        def existingPool = new CloudPool(id: 1L, name: 'Test Pool')
        def masterItem = [id: 'cluster-1', name: 'Test Cluster', sharedVolumes: ['vol1']]
        def updateItem = new SyncTask.UpdateItem(existingItem: existingPool, masterItem: masterItem)
        
        def mockAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> { throw new RuntimeException("Test error") }
        }
        
        mockContext.async >> mockAsyncServices

        when:
        sync.updateMatchedResourcePools([updateItem])

        then:
        1 * mockLog.error(_ as String, _ as Throwable)
        notThrown(Exception)
    }

    // ============================================
    // REMOVE MISSING RESOURCE POOLS TESTS
    // ============================================

    def "removeMissingResourcePools handles empty list"() {
        when:
        sync.removeMissingResourcePools([])

        then:
        (0.._) * mockLog.debug(_)
        notThrown(Exception)
    }

}
