package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusResourcePermissionService
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.cloud.MorpheusCloudPoolService
import com.morpheusdata.core.cloud.MorpheusDatastoreService
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.morpheusdata.scvmm.ScvmmApiService
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Unroll

class ClustersSyncSpec extends Specification {

    private ClustersSync clustersSync
    private MorpheusContext morpheusContext
    private ScvmmApiService mockApiService
    private MorpheusComputeServerService asyncComputeServerService
    private MorpheusSynchronousComputeServerService computeServerService
    private MorpheusCloudService asyncCloudService
    private MorpheusCloudPoolService asyncCloudPoolService
    private MorpheusSynchronousCloudPoolService cloudPoolService
    private MorpheusDatastoreService asyncDatastoreService
    private MorpheusSynchronousDatastoreService datastoreService
    private MorpheusSynchronousCloudService cloudService
    private MorpheusResourcePermissionService asyncResourcePermissionService
    private Cloud cloud
    private ComputeServer server
    private Account masterAccount
    private Account subAccount

    def setup() {
        // Setup mock context and services
        morpheusContext = Mock(MorpheusContext)
        mockApiService = Mock(ScvmmApiService)

        // Mock async services
        asyncComputeServerService = Mock(MorpheusComputeServerService)
        asyncCloudService = Mock(MorpheusCloudService)
        asyncCloudPoolService = Mock(MorpheusCloudPoolService)
        asyncDatastoreService = Mock(MorpheusDatastoreService)
        asyncResourcePermissionService = Mock(MorpheusResourcePermissionService)

        // Mock sync services
        computeServerService = Mock(MorpheusSynchronousComputeServerService)
        cloudPoolService = Mock(MorpheusSynchronousCloudPoolService)
        datastoreService = Mock(MorpheusSynchronousDatastoreService)
        cloudService = Mock(MorpheusSynchronousCloudService)

        // Mock network services with explicit types
        def syncNetworkService = Mock(com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService)
        def asyncNetworkService = Mock(com.morpheusdata.core.network.MorpheusNetworkService)

        def morpheusServices = Mock(MorpheusServices) {
            getComputeServer() >> computeServerService
            getCloud() >> cloudService
        }

        def morpheusAsyncServices = Mock(MorpheusAsyncServices) {
            getComputeServer() >> asyncComputeServerService
            getCloud() >> asyncCloudService
            getResourcePermission() >> asyncResourcePermissionService
        }

        // Configure cloud service chain
        cloudService.getPool() >> cloudPoolService
        cloudService.getDatastore() >> datastoreService
        cloudService.getNetwork() >> syncNetworkService
        asyncCloudService.getPool() >> asyncCloudPoolService
        asyncCloudService.getDatastore() >> asyncDatastoreService
        asyncCloudService.getNetwork() >> asyncNetworkService

        // Configure context mocks
        morpheusContext.getAsync() >> morpheusAsyncServices
        morpheusContext.getServices() >> morpheusServices

        // Create test objects
        masterAccount = new Account(id: 1L, name: "master-account", masterAccount: true)
        subAccount = new Account(id: 2L, name: "sub-account", masterAccount: false)

        cloud = new Cloud(
                id: 1L,
                name: "test-scvmm-cloud",
                account: subAccount,
                defaultPoolSyncActive: true
        )
        cloud.owner = subAccount

        server = new ComputeServer(
                id: 1L,
                name: "scvmm-controller",
                externalId: "controller-123"
        )

        // Create ClustersSync instance
        clustersSync = new ClustersSync(morpheusContext, cloud)

        // Use reflection to inject mock apiService
        def field = ClustersSync.class.getDeclaredField('apiService')
        field.setAccessible(true)
        field.set(clustersSync, mockApiService)
    }

    @Unroll
    def "execute should handle API failure gracefully"() {
        given: "An API failure response"
        def clustersResponse = [success: false, clusters: null]
        def scvmmOpts = [hostname: "scvmm-server"]

        when: "execute is called"
        clustersSync.execute()

        then: "API is called but no sync operations occur"
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.listClusters(scvmmOpts) >> clustersResponse
    }

    @Unroll
    def "execute should handle exceptions gracefully"() {
        when: "execute is called and an exception occurs"
        clustersSync.execute()

        then: "exception is handled gracefully"
        1 * computeServerService.find(_ as DataQuery) >> { throw new RuntimeException("Database error") }
        0 * mockApiService.getScvmmZoneAndHypervisorOpts(_, _, _)

        and: "no exception is thrown from the method"
        noExceptionThrown()
    }

    @Unroll
    def "updateMatchedResourcePools should update shared volumes"() {
        given: "Existing cloud pool and updated cluster data"
        def existingPool = new CloudPool(id: 1L, name: "Test Cluster", externalId: "cluster-1")
        existingPool.setConfigProperty("sharedVolumes", [])
        existingPool.setConfigProperty("nullSharedVolumeSyncCount", 0)

        def masterItem = [
                id: "cluster-1",
                name: "Test Cluster",
                sharedVolumes: [
                        [name: "volume1", path: "/volumes/volume1"]
                ]
        ]

        def updateItem = new SyncTask.UpdateItem<CloudPool, Map>(
                existingItem: existingPool,
                masterItem: masterItem
        )
        def updateList = [updateItem]

        when: "updateMatchedResourcePools is called"
        clustersSync.updateMatchedResourcePools(updateList)

        then: "shared volumes are updated"
        1 * asyncCloudPoolService.bulkSave({ List<CloudPool> pools ->
            pools.size() == 1 &&
            pools[0].getConfigProperty("sharedVolumes") == masterItem.sharedVolumes
        }) >> Single.just([])
    }

    @Unroll
    def "updateMatchedResourcePools should handle null shared volumes with counter"() {
        given: "Existing cloud pool with null count tracking"
        def existingPool = new CloudPool(id: 1L, name: "Test Cluster", externalId: "cluster-1")
        existingPool.setConfigProperty("sharedVolumes", [[name: "volume1"]])
        existingPool.setConfigProperty("nullSharedVolumeSyncCount", 0)

        def masterItem = [
                id: "cluster-1",
                name: "Test Cluster",
                sharedVolumes: null
        ]

        def updateItem = new SyncTask.UpdateItem<CloudPool, Map>(
                existingItem: existingPool,
                masterItem: masterItem
        )
        def updateList = [updateItem]

        when: "updateMatchedResourcePools is called"
        clustersSync.updateMatchedResourcePools(updateList)

        then: "null count is incremented"
        1 * asyncCloudPoolService.bulkSave({ List<CloudPool> pools ->
            pools.size() == 1 &&
            pools[0].getConfigProperty("nullSharedVolumeSyncCount") == 1
        }) >> Single.just([])
    }

    @Unroll
    def "updateMatchedResourcePools should not save when no changes detected"() {
        given: "Existing cloud pool with same shared volumes"
        def sharedVolumes = [[name: "volume1", path: "/volumes/volume1"]]
        def existingPool = new CloudPool(id: 1L, name: "Test Cluster", externalId: "cluster-1")
        existingPool.setConfigProperty("sharedVolumes", sharedVolumes)

        def masterItem = [
                id: "cluster-1",
                name: "Test Cluster",
                sharedVolumes: sharedVolumes
        ]

        def updateItem = new SyncTask.UpdateItem<CloudPool, Map>(
                existingItem: existingPool,
                masterItem: masterItem
        )
        def updateList = [updateItem]

        when: "updateMatchedResourcePools is called"
        clustersSync.updateMatchedResourcePools(updateList)

        then: "no save operation is performed"
        0 * asyncCloudPoolService.bulkSave(_ as List<CloudPool>)
    }

    @Unroll
    def "updateMatchedResourcePools should handle exceptions gracefully"() {
        given: "Update list that will cause an exception"
        def existingPool = new CloudPool(id: 1L, name: "Test Cluster", externalId: "cluster-1")
        def masterItem = [id: "cluster-1", name: "Test Cluster", sharedVolumes: []]
        def updateItem = new SyncTask.UpdateItem<CloudPool, Map>(
                existingItem: existingPool,
                masterItem: masterItem
        )
        def updateList = [updateItem]

        when: "updateMatchedResourcePools is called and an exception occurs"
        clustersSync.updateMatchedResourcePools(updateList)

        then: "exception is handled gracefully"
        1 * asyncCloudPoolService.bulkSave(_ as List<CloudPool>) >> { throw new RuntimeException("Update error") }

        and: "no exception is thrown from the method"
        noExceptionThrown()
    }

    @Unroll
    def "execute should sync clusters successfully"() {
        given: "A successful cluster list response"
        def clustersResponse = [
                success: true,
                clusters: [
                        [
                                id: "cluster-1",
                                name: "Test Cluster 1",
                                sharedVolumes: [
                                        [name: "volume1", path: "/volumes/volume1"],
                                        [name: "volume2", path: "/volumes/volume2"]
                                ]
                        ],
                        [
                                id: "cluster-2",
                                name: "Test Cluster 2",
                                sharedVolumes: []
                        ]
                ]
        ]

        def scvmmOpts = [hostname: "scvmm-server", username: "user", password: "pass"]
        def existingClusterIdentities = []

        when: "execute is called"
        clustersSync.execute()

        then: "services are called appropriately"
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.listClusters(scvmmOpts) >> clustersResponse
        1 * asyncCloudPoolService.listIdentityProjections(_ as DataQuery) >> Observable.fromIterable(existingClusterIdentities)
    }

    @Unroll
    def "execute should handle cluster scope filtering"() {
        given: "A cluster list response with scope filtering"
        cloud.setConfigProperty('cluster', 'cluster-1')

        def clustersResponse = [
                success: true,
                clusters: [
                        [
                                id: "cluster-1",
                                name: "Test Cluster 1",
                                sharedVolumes: []
                        ],
                        [
                                id: "cluster-2",
                                name: "Test Cluster 2",
                                sharedVolumes: []
                        ]
                ]
        ]

        def scvmmOpts = [hostname: "scvmm-server"]
        def existingClusterIdentities = []

        when: "execute is called"
        clustersSync.execute()

        then: "only the scoped cluster should be processed"
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.listClusters(scvmmOpts) >> clustersResponse
        1 * asyncCloudPoolService.listIdentityProjections(_ as DataQuery) >> Observable.fromIterable(existingClusterIdentities)
    }

    @Unroll
    def "execute should call chooseOwnerPoolDefaults for non-master accounts"() {
        given: "Non-master account and successful cluster sync"
        cloud.owner = subAccount

        def clustersResponse = [
                success: true,
                clusters: [
                        [id: "cluster-1", name: "Test Cluster 1", sharedVolumes: []]
                ]
        ]
        def scvmmOpts = [hostname: "scvmm-server"]

        when: "execute is called"
        clustersSync.execute()

        then: "chooseOwnerPoolDefaults is called"
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.listClusters(scvmmOpts) >> clustersResponse
        1 * asyncCloudPoolService.listIdentityProjections(_ as DataQuery) >> Observable.fromIterable([])

        // This should be called for non-master accounts
        2 * cloudPoolService.find(_ as DataQuery) >> null
    }

    @Unroll
    def "execute should not call chooseOwnerPoolDefaults for master accounts"() {
        given: "Master account and successful cluster sync"
        cloud.owner = masterAccount

        def clustersResponse = [
                success: true,
                clusters: [
                        [id: "cluster-1", name: "Test Cluster 1", sharedVolumes: []]
                ]
        ]
        def scvmmOpts = [hostname: "scvmm-server"]

        when: "execute is called"
        clustersSync.execute()

        then: "chooseOwnerPoolDefaults is not called"
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.listClusters(scvmmOpts) >> clustersResponse
        1 * asyncCloudPoolService.listIdentityProjections(_ as DataQuery) >> Observable.fromIterable([])

        // This should not be called for master accounts
        0 * cloudPoolService.find(_ as DataQuery)
    }

    @Unroll
    def "addMissingResourcePools should create new cloud pools with correct properties"() {
        given: "A list of clusters to add"
        def addList = [
                [
                        id: "cluster-1",
                        name: "Test Cluster 1",
                        sharedVolumes: [
                                [name: "volume1", path: "/volumes/volume1"]
                        ]
                ],
                [
                        id: "cluster-2",
                        name: "Test Cluster 2",
                        sharedVolumes: []
                ]
        ]

        when: "addMissingResourcePools is called"
        clustersSync.addMissingResourcePools(addList)

        then: "cloud pools are created with correct properties"
        1 * asyncCloudPoolService.bulkCreate({ List<CloudPool> pools ->
            pools.size() == 2 &&
            pools[0].name == "Test Cluster 1" &&
            pools[0].externalId == "cluster-1" &&
            pools[0].internalId == "Test Cluster 1" &&
            pools[0].refType == "ComputeZone" &&
            pools[0].refId == cloud.id &&
            pools[0].cloud == cloud &&
            pools[0].owner == cloud.owner &&
            pools[0].category == "scvmm.cluster.${cloud.id}" &&
            pools[0].code == "scvmm.cluster.${cloud.id}.cluster-1" &&
            pools[0].readOnly == false &&
            pools[0].type == "Cluster" &&
            pools[0].active == cloud.defaultPoolSyncActive &&
            pools[0].getConfigProperty("sharedVolumes") == addList[0].sharedVolumes &&
            pools[1].name == "Test Cluster 2" &&
            pools[1].externalId == "cluster-2"
        }) >> Single.just([])

        1 * asyncCloudPoolService.bulkSave(_ as List<CloudPool>) >> Single.just([])

        1 * asyncResourcePermissionService.bulkCreate({ List<ResourcePermission> perms ->
            perms.size() == 2 &&
            perms[0].morpheusResourceType == "ComputeZonePool" &&
            perms[0].account == cloud.account &&
            perms[1].morpheusResourceType == "ComputeZonePool" &&
            perms[1].account == cloud.account
        }) >> Single.just([])
    }

    @Unroll
    def "addMissingResourcePools should handle empty list"() {
        given: "An empty list of clusters to add"
        def addList = []

        when: "addMissingResourcePools is called"
        clustersSync.addMissingResourcePools(addList)

        then: "no bulk operations are performed"
        0 * asyncCloudPoolService.bulkCreate(_ as List<CloudPool>)
        0 * asyncCloudPoolService.bulkSave(_ as List<CloudPool>)
        0 * asyncResourcePermissionService.bulkCreate(_ as List<ResourcePermission>)
    }

    @Unroll
    def "addMissingResourcePools should handle exceptions gracefully"() {
        given: "A list of clusters and an exception during processing"
        def addList = [
                [id: "cluster-1", name: "Test Cluster 1", sharedVolumes: []]
        ]

        when: "addMissingResourcePools is called and an exception occurs"
        clustersSync.addMissingResourcePools(addList)

        then: "exception is handled gracefully"
        1 * asyncCloudPoolService.bulkCreate(_ as List<CloudPool>) >> { throw new RuntimeException("Save error") }
        0 * asyncCloudPoolService.bulkSave(_ as List<CloudPool>)
        0 * asyncResourcePermissionService.bulkCreate(_ as List<ResourcePermission>)

        and: "no exception is thrown from the method"
        noExceptionThrown()
    }

    @Unroll
    def "updateMatchedResourcePools should handle null shared volumes with counter threshold"() {
        given: "Existing cloud pool with null count at threshold"
        def existingPool = new CloudPool(id: 1L, name: "Test Cluster", externalId: "cluster-1")
        existingPool.setConfigProperty("sharedVolumes", [[name: "volume1"]])
        existingPool.setConfigProperty("nullSharedVolumeSyncCount", nullCount)

        def masterItem = [
                id: "cluster-1",
                name: "Test Cluster",
                sharedVolumes: null
        ]

        def updateItem = new SyncTask.UpdateItem<CloudPool, Map>(
                existingItem: existingPool,
                masterItem: masterItem
        )
        def updateList = [updateItem]

        when: "updateMatchedResourcePools is called"
        clustersSync.updateMatchedResourcePools(updateList)

        then: "null count behavior is correct"
        expectedSaveCall * asyncCloudPoolService.bulkSave({ List<CloudPool> pools ->
            pools.size() == 1
        }) >> Single.just([])

        where:
        nullCount | expectedSaveCall
        4         | 1  // Count incremented to 5 but volumes not cleared yet
        5         | 1  // Count at threshold, volumes cleared
        6         | 1  // Count above threshold, volumes cleared
    }

    @Unroll
    def "updateMatchedResourcePools should reset null count when volumes are present"() {
        given: "Existing cloud pool with null count and new volumes"
        def existingPool = new CloudPool(id: 1L, name: "Test Cluster", externalId: "cluster-1")
        existingPool.setConfigProperty("sharedVolumes", [])
        existingPool.setConfigProperty("nullSharedVolumeSyncCount", 3)

        def masterItem = [
                id: "cluster-1",
                name: "Test Cluster",
                sharedVolumes: [
                        [name: "volume1", path: "/volumes/volume1"]
                ]
        ]

        def updateItem = new SyncTask.UpdateItem<CloudPool, Map>(
                existingItem: existingPool,
                masterItem: masterItem
        )
        def updateList = [updateItem]

        when: "updateMatchedResourcePools is called"
        clustersSync.updateMatchedResourcePools(updateList)

        then: "null count is reset and volumes are updated"
        1 * asyncCloudPoolService.bulkSave({ List<CloudPool> pools ->
            pools.size() == 1 &&
            pools[0].getConfigProperty("sharedVolumes") == masterItem.sharedVolumes &&
            pools[0].getConfigProperty("nullSharedVolumeSyncCount") == 0
        }) >> Single.just([])
    }

    @Unroll
    def "removeMissingResourcePools should handle empty list"() {
        given: "An empty list of pools to remove"
        def removeList = []

        when: "removeMissingResourcePools is called"
        clustersSync.removeMissingResourcePools(removeList)

        then: "no operations are performed"
        0 * computeServerService.list(_ as DataQuery)
        0 * asyncCloudPoolService.bulkRemove(_ as List<CloudPoolIdentity>)
    }

    @Unroll
    def "removeMissingResourcePools should handle null list"() {
        given: "A null list of pools to remove"
        def removeList = null

        when: "removeMissingResourcePools is called"
        clustersSync.removeMissingResourcePools(removeList)

        then: "no operations are performed"
        0 * computeServerService.list(_ as DataQuery)
        0 * asyncCloudPoolService.bulkRemove(_ as List<CloudPoolIdentity>)
    }

    @Unroll
    def "chooseOwnerPoolDefaults should set default pool when none exists"() {
        given: "No existing default pool"
        def account = subAccount
        def nonDefaultPool = new CloudPool(
                id: 1L,
                name: "Test Pool",
                owner: account,
                refType: "ComputeZone",
                refId: cloud.id,
                defaultPool: false,
                readOnly: false
        )

        when: "chooseOwnerPoolDefaults is called"
        clustersSync.chooseOwnerPoolDefaults(account)

        then: "a pool is found and set as default"
        2 * cloudPoolService.find(_ as DataQuery) >>> [null, nonDefaultPool]
        1 * cloudPoolService.save({ CloudPool pool ->
            pool.defaultPool == true
        }) >> nonDefaultPool
    }

    @Unroll
    def "chooseOwnerPoolDefaults should clear readonly default pool and set new default"() {
        given: "Existing readonly default pool"
        def account = subAccount
        def readOnlyPool = new CloudPool(
                id: 1L,
                name: "ReadOnly Pool",
                owner: account,
                refType: "ComputeZone",
                refId: cloud.id,
                defaultPool: true,
                readOnly: true
        )
        def newDefaultPool = new CloudPool(
                id: 2L,
                name: "New Default Pool",
                owner: account,
                refType: "ComputeZone",
                refId: cloud.id,
                defaultPool: false,
                readOnly: false
        )

        when: "chooseOwnerPoolDefaults is called"
        clustersSync.chooseOwnerPoolDefaults(account)

        then: "readonly pool is cleared and new pool is set as default"
        2 * cloudPoolService.find(_ as DataQuery) >>> [readOnlyPool, newDefaultPool]
        2 * cloudPoolService.save(_ as CloudPool) >> { CloudPool pool ->
            if (pool.id == 1L) {
                assert pool.defaultPool == false
                return pool
            } else if (pool.id == 2L) {
                assert pool.defaultPool == true
                return pool
            }
            return pool
        }
    }

    @Unroll
    def "chooseOwnerPoolDefaults should do nothing when valid default pool exists"() {
        given: "Existing valid default pool"
        def account = subAccount
        def validDefaultPool = new CloudPool(
                id: 1L,
                name: "Valid Default Pool",
                owner: account,
                refType: "ComputeZone",
                refId: cloud.id,
                defaultPool: true,
                readOnly: false
        )

        when: "chooseOwnerPoolDefaults is called"
        clustersSync.chooseOwnerPoolDefaults(account)

        then: "no changes are made"
        1 * cloudPoolService.find(_ as DataQuery) >> validDefaultPool
        0 * cloudPoolService.save(_ as CloudPool)
    }

    @Unroll
    def "chooseOwnerPoolDefaults should handle no pools available"() {
        given: "No pools available for account"
        def account = subAccount

        when: "chooseOwnerPoolDefaults is called"
        clustersSync.chooseOwnerPoolDefaults(account)

        then: "no errors occur"
        2 * cloudPoolService.find(_ as DataQuery) >> null
        0 * cloudPoolService.save(_ as CloudPool)
    }

    @Unroll
    def "clearComputeServerAssociations should handle no associated servers"() {
        given: "A pool with no associated servers"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Remove Pool")

        when: "clearComputeServerAssociations is called"
        clustersSync.clearComputeServerAssociations(removePool)

        then: "no servers are updated"
        1 * computeServerService.list(_ as DataQuery) >> []
        0 * asyncComputeServerService.bulkSave(_ as List<ComputeServer>)
    }

    @Unroll
    def "clearDatastoreAssociations should handle no associated datastores"() {
        given: "A pool with no associated datastores"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Remove Pool")

        when: "clearDatastoreAssociations is called"
        clustersSync.clearDatastoreAssociations(removePool)

        then: "no datastores are updated"
        1 * datastoreService.list(_ as DataQuery) >> []
        0 * datastoreService.bulkSave(_ as List<Datastore>)
    }

    @Unroll
    def "clearComputeServerAssociations should handle no associated servers"() {
        given: "A pool with no associated servers"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Remove Pool")

        when: "clearComputeServerAssociations is called"
        clustersSync.clearComputeServerAssociations(removePool)

        then: "no servers are updated"
        1 * computeServerService.list(_ as DataQuery) >> []
        0 * asyncComputeServerService.bulkSave(_ as List<ComputeServer>)
    }

    @Unroll
    def "clearDatastoreAssociations should handle no associated datastores"() {
        given: "A pool with no associated datastores"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Remove Pool")

        when: "clearDatastoreAssociations is called"
        clustersSync.clearDatastoreAssociations(removePool)

        then: "no datastores are updated"
        1 * datastoreService.list(_ as DataQuery) >> []
        0 * datastoreService.bulkSave(_ as List<Datastore>)
    }

    @Unroll
    def "clearCloudPoolAssociations should handle no associated cloud pools"() {
        given: "A pool with no associated child pools"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Remove Pool")

        when: "clearCloudPoolAssociations is called"
        clustersSync.clearCloudPoolAssociations(removePool)

        then: "no cloud pools are updated"
        1 * cloudPoolService.list(_ as DataQuery) >> []
        0 * asyncCloudPoolService.bulkSave(_ as List<CloudPool>)
    }

    @Unroll
    def "removeMissingResourcePools should handle exceptions gracefully"() {
        given: "Remove list that will cause an exception"
        def removeList = [new CloudPoolIdentity(id: 1L, name: "Remove Pool")]

        when: "removeMissingResourcePools is called and an exception occurs"
        clustersSync.removeMissingResourcePools(removeList)

        then: "exception is handled gracefully"
        1 * computeServerService.list(_ as DataQuery) >> { throw new RuntimeException("Query error") }
        0 * asyncCloudPoolService.bulkRemove(_ as List<CloudPoolIdentity>)

        and: "no exception is thrown from the method"
        noExceptionThrown()
    }

    @Unroll
    def "updateMatchedResourcePools should handle empty update list"() {
        given: "An empty update list"
        def updateList = []

        when: "updateMatchedResourcePools is called"
        clustersSync.updateMatchedResourcePools(updateList)

        then: "no save operation is performed"
        0 * asyncCloudPoolService.bulkSave(_ as List<CloudPool>)
    }

    @Unroll
    def "execute should handle null clusters in response"() {
        given: "A response with null clusters"
        def clustersResponse = [success: true, clusters: null]
        def scvmmOpts = [hostname: "scvmm-server"]

        when: "execute is called"
        clustersSync.execute()

        then: "API is called but no sync operations occur"
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.listClusters(scvmmOpts) >> clustersResponse
        0 * asyncCloudPoolService.listIdentityProjections(_ as DataQuery)
    }

    @Unroll
    def "addMissingResourcePools should handle clusters with null sharedVolumes"() {
        given: "A list of clusters with null sharedVolumes"
        def addList = [
                [
                        id: "cluster-1",
                        name: "Test Cluster 1",
                        sharedVolumes: null
                ]
        ]

        when: "addMissingResourcePools is called"
        clustersSync.addMissingResourcePools(addList)

        then: "cloud pools are created with null sharedVolumes"
        1 * asyncCloudPoolService.bulkCreate({ List<CloudPool> pools ->
            pools.size() == 1 &&
            pools[0].name == "Test Cluster 1" &&
            pools[0].getConfigProperty("sharedVolumes") == null
        }) >> Single.just([])

        1 * asyncCloudPoolService.bulkSave(_ as List<CloudPool>) >> Single.just([])
        1 * asyncResourcePermissionService.bulkCreate(_ as List<ResourcePermission>) >> Single.just([])
    }

    // ===============================
    // Comprehensive tests for clearResourcePoolAssociations and sub-methods
    // ===============================

    @Unroll
    def "clearResourcePoolAssociations should call all clear methods"() {
        given: "A cloud pool identity to remove"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Test Pool", externalId: "pool-1")

        when: "clearResourcePoolAssociations is called"
        clustersSync.clearResourcePoolAssociations(removePool)

        then: "all four clear methods are invoked"
        1 * computeServerService.list(_ as DataQuery) >> []
        1 * datastoreService.list(_ as DataQuery) >> []
        1 * cloudService.network.list(_ as DataQuery) >> []
        1 * cloudPoolService.list(_ as DataQuery) >> []
    }

    @Unroll
    def "clearComputeServerAssociations should clear resource pool associations and save servers"() {
        given: "Servers associated with a resource pool"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Test Pool")
        def resourcePool = new CloudPool(id: 1L, name: "Test Pool")
        def server1 = new ComputeServer(id: 1L, name: "Server 1", resourcePool: resourcePool)
        def server2 = new ComputeServer(id: 2L, name: "Server 2", resourcePool: resourcePool)
        def serversToUpdate = [server1, server2]

        when: "clearComputeServerAssociations is called"
        clustersSync.clearComputeServerAssociations(removePool)

        then: "servers are found, resource pool is cleared, and servers are saved"
        1 * computeServerService.list(_ as DataQuery) >> serversToUpdate
        1 * asyncComputeServerService.bulkSave({ List<ComputeServer> servers ->
            servers.size() == 2 &&
            servers[0].resourcePool == null &&
            servers[1].resourcePool == null &&
            servers[0].name == "Server 1" &&
            servers[1].name == "Server 2"
        }) >> Single.just([])
    }

    @Unroll
    def "clearComputeServerAssociations should handle empty servers list"() {
        given: "No servers associated with a resource pool"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Test Pool")

        when: "clearComputeServerAssociations is called"
        clustersSync.clearComputeServerAssociations(removePool)

        then: "no save operation is performed"
        1 * computeServerService.list(_ as DataQuery) >> []
        0 * asyncComputeServerService.bulkSave(_ as List<ComputeServer>)
    }

    @Unroll
    def "clearComputeServerAssociations should handle null servers list"() {
        given: "Null servers list from query"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Test Pool")

        when: "clearComputeServerAssociations is called"
        clustersSync.clearComputeServerAssociations(removePool)

        then: "no save operation is performed"
        1 * computeServerService.list(_ as DataQuery) >> null
        0 * asyncComputeServerService.bulkSave(_ as List<ComputeServer>)
    }

    @Unroll
    def "clearDatastoreAssociations should clear zone pool associations and save datastores"() {
        given: "Datastores associated with a zone pool"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Test Pool")
        def zonePool = new CloudPool(id: 1L, name: "Test Pool")
        def datastore1 = new Datastore(id: 1L, name: "Datastore 1", zonePool: zonePool)
        def datastore2 = new Datastore(id: 2L, name: "Datastore 2", zonePool: zonePool)
        def datastoresToUpdate = [datastore1, datastore2]

        when: "clearDatastoreAssociations is called"
        clustersSync.clearDatastoreAssociations(removePool)

        then: "datastores are found, zone pool is cleared, and datastores are saved"
        1 * datastoreService.list(_ as DataQuery) >> datastoresToUpdate
        1 * asyncDatastoreService.bulkSave({ List<Datastore> datastores ->
            datastores.size() == 2 &&
            datastores[0].zonePool == null &&
            datastores[1].zonePool == null &&
            datastores[0].name == "Datastore 1" &&
            datastores[1].name == "Datastore 2"
        }) >> Single.just([])
    }

    @Unroll
    def "clearDatastoreAssociations should handle empty datastores list"() {
        given: "No datastores associated with a zone pool"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Test Pool")

        when: "clearDatastoreAssociations is called"
        clustersSync.clearDatastoreAssociations(removePool)

        then: "no save operation is performed"
        1 * datastoreService.list(_ as DataQuery) >> []
        0 * asyncDatastoreService.bulkSave(_ as List<Datastore>)
    }

    @Unroll
    def "clearDatastoreAssociations should handle null datastores list"() {
        given: "Null datastores list from query"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Test Pool")

        when: "clearDatastoreAssociations is called"
        clustersSync.clearDatastoreAssociations(removePool)

        then: "no save operation is performed"
        1 * datastoreService.list(_ as DataQuery) >> null
        0 * asyncDatastoreService.bulkSave(_ as List<Datastore>)
    }

    @Unroll
    def "clearNetworkAssociations should clear cloud pool associations and save networks"() {
        given: "Networks associated with a cloud pool"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Test Pool")
        def cloudPool = new CloudPool(id: 1L, name: "Test Pool")
        def network1 = new Network(id: 1L, name: "Network 1", cloudPool: cloudPool)
        def network2 = new Network(id: 2L, name: "Network 2", cloudPool: cloudPool)
        def networksToUpdate = [network1, network2]

        when: "clearNetworkAssociations is called"
        clustersSync.clearNetworkAssociations(removePool)

        then: "networks are found, cloud pool is cleared, and networks are saved"
        1 * cloudService.network.list(_ as DataQuery) >> networksToUpdate
        1 * asyncCloudService.network.bulkSave({ List<Network> networks ->
            networks.size() == 2 &&
            networks[0].cloudPool == null &&
            networks[1].cloudPool == null &&
            networks[0].name == "Network 1" &&
            networks[1].name == "Network 2"
        }) >> Single.just([])
    }

    @Unroll
    def "clearNetworkAssociations should handle empty networks list"() {
        given: "No networks associated with a cloud pool"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Test Pool")

        when: "clearNetworkAssociations is called"
        clustersSync.clearNetworkAssociations(removePool)

        then: "no save operation is performed"
        1 * cloudService.network.list(_ as DataQuery) >> []
        0 * asyncCloudService.network.bulkSave(_ as List<Network>)
    }

    @Unroll
    def "clearNetworkAssociations should handle null networks list"() {
        given: "Null networks list from query"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Test Pool")

        when: "clearNetworkAssociations is called"
        clustersSync.clearNetworkAssociations(removePool)

        then: "no save operation is performed"
        1 * cloudService.network.list(_ as DataQuery) >> null
        0 * asyncCloudService.network.bulkSave(_ as List<Network>)
    }

    @Unroll
    def "clearCloudPoolAssociations should clear parent associations and save cloud pools"() {
        given: "Child cloud pools associated with a parent pool"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Parent Pool")
        def parentPool = new CloudPool(id: 1L, name: "Parent Pool")
        def childPool1 = new CloudPool(id: 2L, name: "Child Pool 1", parent: parentPool)
        def childPool2 = new CloudPool(id: 3L, name: "Child Pool 2", parent: parentPool)
        def cloudPoolsToUpdate = [childPool1, childPool2]

        when: "clearCloudPoolAssociations is called"
        clustersSync.clearCloudPoolAssociations(removePool)

        then: "child pools are found, parent is cleared, and pools are saved"
        1 * cloudPoolService.list(_ as DataQuery) >> cloudPoolsToUpdate
        1 * asyncCloudPoolService.bulkSave({ List<CloudPool> pools ->
            pools.size() == 2 &&
            pools[0].parent == null &&
            pools[1].parent == null &&
            pools[0].name == "Child Pool 1" &&
            pools[1].name == "Child Pool 2"
        }) >> Single.just([])
    }

    @Unroll
    def "clearCloudPoolAssociations should handle empty cloud pools list"() {
        given: "No child cloud pools associated with a parent pool"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Parent Pool")

        when: "clearCloudPoolAssociations is called"
        clustersSync.clearCloudPoolAssociations(removePool)

        then: "no save operation is performed"
        1 * cloudPoolService.list(_ as DataQuery) >> []
        0 * asyncCloudPoolService.bulkSave(_ as List<CloudPool>)
    }

    @Unroll
    def "clearCloudPoolAssociations should handle null cloud pools list"() {
        given: "Null cloud pools list from query"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Parent Pool")

        when: "clearCloudPoolAssociations is called"
        clustersSync.clearCloudPoolAssociations(removePool)

        then: "no save operation is performed"
        1 * cloudPoolService.list(_ as DataQuery) >> null
        0 * asyncCloudPoolService.bulkSave(_ as List<CloudPool>)
    }

    @Unroll
    def "clearComputeServerAssociations should preserve server properties when clearing resource pool"() {
        given: "Servers with various properties and associated resource pool"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Test Pool")
        def resourcePool = new CloudPool(id: 1L, name: "Test Pool")
        def server1 = new ComputeServer(id: 1L, name: "Server 1", externalId: "srv-1", status: "running", resourcePool: resourcePool)
        def server2 = new ComputeServer(id: 2L, name: "Server 2", externalId: "srv-2", status: "stopped", resourcePool: resourcePool)
        def serversToUpdate = [server1, server2]

        when: "clearComputeServerAssociations is called"
        clustersSync.clearComputeServerAssociations(removePool)

        then: "servers are found, only resource pool is cleared, other properties preserved"
        1 * computeServerService.list(_ as DataQuery) >> serversToUpdate
        1 * asyncComputeServerService.bulkSave({ List<ComputeServer> servers ->
            servers.size() == 2 &&
            servers[0].resourcePool == null &&
            servers[1].resourcePool == null &&
            servers[0].name == "Server 1" &&
            servers[0].externalId == "srv-1" &&
            servers[0].status == "running" &&
            servers[1].name == "Server 2" &&
            servers[1].externalId == "srv-2" &&
            servers[1].status == "stopped"
        }) >> Single.just([])
    }

    @Unroll
    def "clearDatastoreAssociations should preserve datastore properties when clearing zone pool"() {
        given: "Datastores with various properties and associated zone pool"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Test Pool")
        def zonePool = new CloudPool(id: 1L, name: "Test Pool")
        def datastore1 = new Datastore(id: 1L, name: "Datastore 1", externalId: "ds-1", storageSize: 1000L, zonePool: zonePool)
        def datastore2 = new Datastore(id: 2L, name: "Datastore 2", externalId: "ds-2", storageSize: 2000L, zonePool: zonePool)
        def datastoresToUpdate = [datastore1, datastore2]

        when: "clearDatastoreAssociations is called"
        clustersSync.clearDatastoreAssociations(removePool)

        then: "datastores are found, only zone pool is cleared, other properties preserved"
        1 * datastoreService.list(_ as DataQuery) >> datastoresToUpdate
        1 * asyncDatastoreService.bulkSave({ List<Datastore> datastores ->
            datastores.size() == 2 &&
            datastores[0].zonePool == null &&
            datastores[1].zonePool == null &&
            datastores[0].name == "Datastore 1" &&
            datastores[0].externalId == "ds-1" &&
            datastores[0].storageSize == 1000L &&
            datastores[1].name == "Datastore 2" &&
            datastores[1].externalId == "ds-2" &&
            datastores[1].storageSize == 2000L
        }) >> Single.just([])
    }

    @Unroll
    def "clearNetworkAssociations should preserve network properties when clearing cloud pool"() {
        given: "Networks with various properties and associated cloud pool"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Test Pool")
        def cloudPool = new CloudPool(id: 1L, name: "Test Pool")
        def network1 = new Network(id: 1L, name: "Network 1", externalId: "net-1", cidr: "192.168.1.0/24", cloudPool: cloudPool)
        def network2 = new Network(id: 2L, name: "Network 2", externalId: "net-2", cidr: "10.0.0.0/16", cloudPool: cloudPool)
        def networksToUpdate = [network1, network2]

        when: "clearNetworkAssociations is called"
        clustersSync.clearNetworkAssociations(removePool)

        then: "networks are found, only cloud pool is cleared, other properties preserved"
        1 * cloudService.network.list(_ as DataQuery) >> networksToUpdate
        1 * asyncCloudService.network.bulkSave({ List<Network> networks ->
            networks.size() == 2 &&
            networks[0].cloudPool == null &&
            networks[1].cloudPool == null &&
            networks[0].name == "Network 1" &&
            networks[0].externalId == "net-1" &&
            networks[0].cidr == "192.168.1.0/24" &&
            networks[1].name == "Network 2" &&
            networks[1].externalId == "net-2" &&
            networks[1].cidr == "10.0.0.0/16"
        }) >> Single.just([])
    }

    @Unroll
    def "clearCloudPoolAssociations should preserve cloud pool properties when clearing parent"() {
        given: "Child cloud pools with various properties and associated parent"
        def removePool = new CloudPoolIdentity(id: 1L, name: "Parent Pool")
        def parentPool = new CloudPool(id: 1L, name: "Parent Pool")
        def childPool1 = new CloudPool(id: 2L, name: "Child Pool 1", externalId: "child-1", type: "ResourcePool", parent: parentPool)
        def childPool2 = new CloudPool(id: 3L, name: "Child Pool 2", externalId: "child-2", type: "Folder", parent: parentPool)
        def cloudPoolsToUpdate = [childPool1, childPool2]

        when: "clearCloudPoolAssociations is called"
        clustersSync.clearCloudPoolAssociations(removePool)

        then: "child pools are found, only parent is cleared, other properties preserved"
        1 * cloudPoolService.list(_ as DataQuery) >> cloudPoolsToUpdate
        1 * asyncCloudPoolService.bulkSave({ List<CloudPool> pools ->
            pools.size() == 2 &&
            pools[0].parent == null &&
            pools[1].parent == null &&
            pools[0].name == "Child Pool 1" &&
            pools[0].externalId == "child-1" &&
            pools[0].type == "ResourcePool" &&
            pools[1].name == "Child Pool 2" &&
            pools[1].externalId == "child-2" &&
            pools[1].type == "Folder"
        }) >> Single.just([])
    }

}
