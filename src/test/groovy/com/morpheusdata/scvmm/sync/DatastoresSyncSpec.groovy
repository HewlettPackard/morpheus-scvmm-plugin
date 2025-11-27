package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.MorpheusStorageVolumeService
import com.morpheusdata.core.MorpheusStorageVolumeTypeService
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.cloud.MorpheusDatastoreService
import com.morpheusdata.core.cloud.MorpheusCloudPoolService
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.DatastoreIdentity
import com.morpheusdata.scvmm.ScvmmApiService
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Unroll

class DatastoresSyncSpec extends Specification {

    private DatastoresSync datastoresSync
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
    private MorpheusStorageVolumeService asyncStorageVolumeService
    private MorpheusSynchronousStorageVolumeService storageVolumeService
    private MorpheusStorageVolumeTypeService asyncStorageVolumeTypeService
    private MorpheusSynchronousStorageVolumeTypeService storageVolumeTypeService
    private Cloud cloud
    private ComputeServer node
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
        asyncStorageVolumeService = Mock(MorpheusStorageVolumeService)
        asyncStorageVolumeTypeService = Mock(MorpheusStorageVolumeTypeService)

        // Mock sync services
        computeServerService = Mock(MorpheusSynchronousComputeServerService)
        cloudPoolService = Mock(MorpheusSynchronousCloudPoolService)
        datastoreService = Mock(MorpheusSynchronousDatastoreService)
        cloudService = Mock(MorpheusSynchronousCloudService)
        storageVolumeService = Mock(MorpheusSynchronousStorageVolumeService)
        storageVolumeTypeService = Mock(MorpheusSynchronousStorageVolumeTypeService)

        def morpheusServices = Mock(MorpheusServices) {
            getComputeServer() >> computeServerService
            getCloud() >> cloudService
            getStorageVolume() >> storageVolumeService
        }

        def morpheusAsyncServices = Mock(MorpheusAsyncServices) {
            getComputeServer() >> asyncComputeServerService
            getCloud() >> asyncCloudService
            getStorageVolume() >> asyncStorageVolumeService
        }

        // Configure cloud service chain
        cloudService.getPool() >> cloudPoolService
        cloudService.getDatastore() >> datastoreService
        asyncCloudService.getPool() >> asyncCloudPoolService
        asyncCloudService.getDatastore() >> asyncDatastoreService

        // Configure storage service chain
        storageVolumeService.getStorageVolumeType() >> storageVolumeTypeService

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
                defaultDatastoreSyncActive: true
        )
        cloud.owner = subAccount

        node = new ComputeServer(
                id: 1L,
                name: "scvmm-controller",
                externalId: "controller-123"
        )

        // Create DatastoresSync instance
        datastoresSync = new DatastoresSync(node, cloud, morpheusContext, mockApiService)
    }

    @Unroll
    def "execute should handle API failure gracefully"() {
        given: "An API failure response"
        def datastoresResponse = [success: false, datastores: null]
        def scvmmOpts = [hostname: "scvmm-server"]
        def clusters = []
        def volumeType = new StorageVolumeType(code: 'scvmm-datastore')
        def existingHosts = []

        when: "execute is called"
        datastoresSync.execute()

        then: "API is called but no sync operations occur"
        1 * cloudPoolService.list(_ as DataQuery) >> clusters
        1 * storageVolumeTypeService.find(_ as DataQuery) >> volumeType
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, node) >> scvmmOpts
        1 * computeServerService.list(_ as DataQuery) >> existingHosts
        1 * mockApiService.listDatastores(scvmmOpts) >> datastoresResponse
        0 * asyncDatastoreService.listIdentityProjections(_ as DataQuery)
    }

    @Unroll
    def "execute should handle exceptions gracefully"() {
        when: "execute is called and an exception occurs"
        datastoresSync.execute()

        then: "exception is handled gracefully"
        1 * cloudPoolService.list(_ as DataQuery) >> { throw new RuntimeException("Database error") }
        0 * mockApiService.getScvmmZoneAndHypervisorOpts(_, _, _)

        and: "no exception is thrown from the method"
        noExceptionThrown()
    }

    @Unroll
    def "execute should sync datastores successfully"() {
        given: "A successful datastore list response"
        def datastoresResponse = [
                success: true,
                datastores: [
                        [
                                id: "ds-1",
                                name: "Test Datastore 1",
                                partitionUniqueID: "partition-1",
                                vmHost: "host1",
                                isAvailableForPlacement: true,
                                freeSpace: 1000000L,
                                size: 2000000L,
                                isClusteredSharedVolume: false
                        ],
                        [
                                id: "ds-2",
                                name: "Test Datastore 2",
                                partitionUniqueID: "partition-2",
                                vmHost: "host2",
                                isAvailableForPlacement: true,
                                freeSpace: 500000L,
                                capacity: 1500000L,
                                isClusteredSharedVolume: true
                        ]
                ]
        ]

        def scvmmOpts = [hostname: "scvmm-server", username: "user", password: "pass"]
        def clusters = []
        def volumeType = new StorageVolumeType(code: 'scvmm-datastore')
        def existingHosts = []
        def existingDatastoreIdentities = []

        when: "execute is called"
        datastoresSync.execute()

        then: "services are called appropriately"
        1 * cloudPoolService.list(_ as DataQuery) >> clusters
        1 * storageVolumeTypeService.find(_ as DataQuery) >> volumeType
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, node) >> scvmmOpts
        1 * computeServerService.list(_ as DataQuery) >> existingHosts
        1 * mockApiService.listDatastores(scvmmOpts) >> datastoresResponse
        1 * asyncDatastoreService.listIdentityProjections(_ as DataQuery) >> Observable.fromIterable(existingDatastoreIdentities)
    }

    @Unroll
    def "buildSyncContext should return correct context data"() {
        given: "Mock data for sync context"
        def clusters = [
                new CloudPool(id: 1L, name: "Cluster1", type: "Cluster")
        ]
        def volumeType = new StorageVolumeType(code: 'scvmm-datastore')
        def scvmmOpts = [hostname: "scvmm-server"]
        def existingHosts = [
                new ComputeServer(id: 1L, name: "host1")
        ]

        when: "buildSyncContext is called"
        def result = datastoresSync.buildSyncContext()

        then: "correct data is returned"
        1 * cloudPoolService.list(_ as DataQuery) >> clusters
        1 * storageVolumeTypeService.find(_ as DataQuery) >> volumeType
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, node) >> scvmmOpts
        1 * computeServerService.list(_ as DataQuery) >> existingHosts

        result.clusters == clusters
        result.volumeType == volumeType
        result.scvmmOpts == scvmmOpts
        result.existingHosts == existingHosts
    }

    @Unroll
    def "filterUniqueDatastores should filter duplicates by partitionUniqueID"() {
        given: "Datastores with duplicate partitionUniqueIDs"
        def datastores = [
                [partitionUniqueID: "partition-1", name: "DS1"],
                [partitionUniqueID: "partition-2", name: "DS2"],
                [partitionUniqueID: "partition-1", name: "DS1-duplicate"],
                [partitionUniqueID: "partition-3", name: "DS3"]
        ]

        when: "filterUniqueDatastores is called"
        def result = datastoresSync.filterUniqueDatastores(datastores)

        then: "only unique datastores are returned"
        result.size() == 3
        result[0].name == "DS1"
        result[1].name == "DS2"
        result[2].name == "DS3"
    }

    @Unroll
    def "removeMissingDatastores should remove datastores"() {
        given: "List of datastores to remove"
        def removeList = [
                new DatastoreIdentity(id: 1L, externalId: "ds-1"),
                new DatastoreIdentity(id: 2L, externalId: "ds-2")
        ]

        when: "removeMissingDatastores is called"
        datastoresSync.removeMissingDatastores(removeList)

        then: "bulkRemove is called"
        1 * datastoreService.bulkRemove(removeList)
    }

    @Unroll
    def "removeMissingDatastores should handle exceptions gracefully"() {
        given: "List of datastores to remove that will cause an exception"
        def removeList = [new DatastoreIdentity(id: 1L, externalId: "ds-1")]

        when: "removeMissingDatastores is called and an exception occurs"
        datastoresSync.removeMissingDatastores(removeList)

        then: "exception is handled gracefully"
        1 * datastoreService.bulkRemove(removeList) >> { throw new RuntimeException("Remove error") }

        and: "no exception is thrown from the method"
        noExceptionThrown()
    }

    @Unroll
    def "updateMatchedDatastores should update each datastore"() {
        given: "Update list with datastores"
        def existingDatastore = new Datastore(id: 1L, name: "Old DS", online: false)
        def masterItem = [
                id: "ds-1",
                name: "Updated DS",
                isAvailableForPlacement: true,
                freeSpace: 1000000L,
                size: 2000000L,
                vmHost: "host1"
        ]
        def updateItem = new SyncTask.UpdateItem<Datastore, Map>(
                existingItem: existingDatastore,
                masterItem: masterItem
        )
        def updateList = [updateItem]
        def clusters = []
        def existingHosts = [new ComputeServer(hostname: "host1")]
        def volumeType = new StorageVolumeType(code: 'scvmm-datastore')

        when: "updateMatchedDatastores is called"
        datastoresSync.updateMatchedDatastores(updateList, clusters, existingHosts, volumeType)

        then: "datastore is updated"
        1 * asyncDatastoreService.save(_ as Datastore) >> Single.just(existingDatastore)
    }

    @Unroll
    def "updateMatchedDatastores should handle exceptions gracefully"() {
        given: "Update list that will cause an exception"
        def existingDatastore = new Datastore(id: 1L, name: "Test DS")
        def masterItem = [id: "ds-1", name: "Test DS"]
        def updateItem = new SyncTask.UpdateItem<Datastore, Map>(
                existingItem: existingDatastore,
                masterItem: masterItem
        )
        def updateList = [updateItem]
        def clusters = []
        def existingHosts = []
        def volumeType = new StorageVolumeType()

        when: "updateMatchedDatastores is called and an exception occurs"
        datastoresSync.updateMatchedDatastores(updateList, clusters, existingHosts, volumeType)

        then: "exception is handled gracefully"
        noExceptionThrown()
    }

    @Unroll
    def "findClusterForDatastore should return correct cluster for CSV"() {
        given: "Clustered shared volume datastore and matching cluster"
        def masterItem = [
                name: "TestVolume",
                isClusteredSharedVolume: true
        ]
        def cluster = new CloudPool(id: 1L, name: "TestCluster")
        cluster.setConfigProperty("sharedVolumes", "[{name: 'TestVolume'}]")
        def clusters = [cluster]
        def host = new ComputeServer(resourcePool: new CloudPool(id: 1L))

        when: "findClusterForDatastore is called"
        def result = datastoresSync.findClusterForDatastore(masterItem, clusters, host)

        then: "correct cluster is returned"
        result == cluster
    }

    @Unroll
    def "findClusterForDatastore should return null for non-CSV"() {
        given: "Non-clustered datastore"
        def masterItem = [
                name: "TestVolume",
                isClusteredSharedVolume: false
        ]
        def clusters = []
        def host = null

        when: "findClusterForDatastore is called"
        def result = datastoresSync.findClusterForDatastore(masterItem, clusters, host)

        then: "null is returned"
        result == null
    }

    @Unroll
    def "updateDatastoreProperties should detect and update changes"() {
        given: "Datastore with different properties"
        def existingItem = new Datastore(
                online: false,
                name: "Old Name",
                freeSpace: 0L,
                storageSize: 0L,
                zonePool: null
        )
        def masterItem = [
                isAvailableForPlacement: true,
                freeSpace: 1000000L,
                size: 2000000L
        ]
        def name = "New Name"
        def cluster = new CloudPool(id: 1L)

        when: "updateDatastoreProperties is called"
        def result = datastoresSync.updateDatastoreProperties(existingItem, masterItem, name, cluster)

        then: "changes are detected and applied"
        result
        existingItem.online == true
        existingItem.name == "New Name"
        existingItem.freeSpace == 1000000L
        existingItem.storageSize == 2000000L
        existingItem.zonePool == cluster
    }

    @Unroll
    def "updateDatastoreProperties should return false when no changes"() {
        given: "Datastore with same properties"
        def cluster = new CloudPool(id: 1L)
        def existingItem = new Datastore(
                online: true,
                name: "Same Name",
                freeSpace: 1000000L,
                storageSize: 2000000L,
                zonePool: cluster
        )
        def masterItem = [
                isAvailableForPlacement: true,
                freeSpace: 1000000L,
                size: 2000000L
        ]
        def name = "Same Name"

        when: "updateDatastoreProperties is called"
        def result = datastoresSync.updateDatastoreProperties(existingItem, masterItem, name, cluster)

        then: "no changes are detected"
        !result
    }

    @Unroll
    def "calculateTotalSize should return size when available"() {
        given: "Master item with size"
        def masterItem = [size: "2000000"]

        when: "calculateTotalSize is called"
        def result = datastoresSync.calculateTotalSize(masterItem)

        then: "size is returned"
        result == 2000000L
    }

    @Unroll
    def "calculateTotalSize should return capacity when size unavailable"() {
        given: "Master item with capacity but no size"
        def masterItem = [capacity: "1500000"]

        when: "calculateTotalSize is called"
        def result = datastoresSync.calculateTotalSize(masterItem)

        then: "capacity is returned"
        result == 1500000L
    }

    @Unroll
    def "calculateTotalSize should return 0 when neither available"() {
        given: "Master item with neither size nor capacity"
        def masterItem = [:]

        when: "calculateTotalSize is called"
        def result = datastoresSync.calculateTotalSize(masterItem)

        then: "0 is returned"
        result == 0L
    }

    @Unroll
    def "addMissingDatastores should add each datastore"() {
        given: "List of datastores to add"
        def addList = [
                [
                        id: "ds-1",
                        name: "Test DS 1",
                        partitionUniqueID: "partition-1",
                        vmHost: "host1",
                        isAvailableForPlacement: true,
                        freeSpace: 1000000L,
                        size: 2000000L,
                        isClusteredSharedVolume: false
                ]
        ]
        def clusters = []
        def existingHosts = [new ComputeServer(hostname: "host1")]
        def volumeType = new StorageVolumeType(code: 'scvmm-datastore')

        when: "addMissingDatastores is called"
        datastoresSync.addMissingDatastores(addList, clusters, existingHosts, volumeType)

        then: "datastore is created"
        1 * asyncDatastoreService.create(_ as Datastore) >> Single.just(new Datastore(id: 1L))
        1 * asyncStorageVolumeService.create(_ as List<StorageVolume>, _ as ComputeServer) >> Single.just([])
    }

    @Unroll
    def "addMissingDatastores should handle exceptions gracefully"() {
        given: "List that will cause an exception"
        def addList = [[id: "ds-1", name: "Test DS"]]
        def clusters = []
        def existingHosts = []
        def volumeType = new StorageVolumeType()

        when: "addMissingDatastores is called and an exception occurs"
        datastoresSync.addMissingDatastores(addList, clusters, existingHosts, volumeType)

        then: "exception is handled gracefully"
        noExceptionThrown()
    }

    @Unroll
    def "buildDatastoreConfig should create correct configuration"() {
        given: "Item data and context"
        def item = [
                id: "ds-1",
                name: "Test DS",
                isAvailableForPlacement: true,
                freeSpace: 1000000L,
                size: 2000000L
        ]
        def cluster = new CloudPool(id: 1L, name: "TestCluster")
        def host = new ComputeServer(name: "TestHost")
        def externalId = "partition-1"

        when: "buildDatastoreConfig is called"
        def result = datastoresSync.buildDatastoreConfig(item, cluster, host, externalId)

        then: "correct configuration is created"
        result.cloud == cloud
        result.zonePool == cluster
        result.name == "TestHost : Test DS"
        result.externalId == externalId
        result.online == true
        result.freeSpace == 1000000L
        result.storageSize == 2000000L
        result.category == "scvmm.datastore.${cloud.id}"
    }

    @Unroll
    def "syncVolume should update existing volume"() {
        given: "Existing volume and datastore"
        def item = [
                id: "vol-1",
                name: "Test Volume",
                freeSpace: 1000000L,
                size: 2000000L,
                mountPoints: ["/mount1"]
        ]
        def existingVolume = new StorageVolume(
                id: 1L,
                externalId: "partition-1",
                internalId: "old-id",
                maxStorage: 1000000L,
                usedStorage: 500000L,
                name: "Old Name",
                volumePath: "/old-mount",
                datastore: new Datastore(id: 2L)
        )
        def host = new ComputeServer(volumes: [existingVolume])
        def savedDataStore = new Datastore(id: 1L)
        def volumeType = new StorageVolumeType()
        def externalId = "partition-1"

        when: "syncVolume is called"
        datastoresSync.syncVolume(item, host, savedDataStore, volumeType, externalId)

        then: "existing volume is updated"
        1 * asyncStorageVolumeService.save(existingVolume) >> Single.just(existingVolume)
        existingVolume.internalId == "vol-1"
        existingVolume.maxStorage == 2000000L
        existingVolume.usedStorage == 1000000L
        existingVolume.name == "Test Volume"
        existingVolume.volumePath == "/mount1"
        existingVolume.datastore == savedDataStore
    }

    @Unroll
    def "syncVolume should create new volume when not existing"() {
        given: "No existing volume"
        def item = [
                id: "vol-1",
                name: "Test Volume",
                freeSpace: 1000000L,
                size: 2000000L,
                mountPoints: ["/mount1"]
        ]
        def host = new ComputeServer(volumes: [])
        def savedDataStore = new Datastore(id: 1L)
        def volumeType = new StorageVolumeType(code: 'scvmm-datastore')
        def externalId = "partition-1"

        when: "syncVolume is called"
        datastoresSync.syncVolume(item, host, savedDataStore, volumeType, externalId)

        then: "new volume is created"
        1 * asyncStorageVolumeService.create(_ as List<StorageVolume>, host) >> Single.just([])
    }

    @Unroll
    def "syncVolume should handle exceptions gracefully"() {
        given: "Item that will cause an exception"
        def item = [id: "vol-1", name: "Test Volume"]
        def host = new ComputeServer(volumes: [])
        def savedDataStore = new Datastore(id: 1L)
        def volumeType = new StorageVolumeType()
        def externalId = "partition-1"

        when: "syncVolume is called and an exception occurs"
        datastoresSync.syncVolume(item, host, savedDataStore, volumeType, externalId)

        then: "exception is handled gracefully"
        1 * asyncStorageVolumeService.create(_, _) >> { throw new RuntimeException("Create error") }
        noExceptionThrown()
    }

    @Unroll
    def "calculateVolumeMetrics should calculate correct metrics"() {
        given: "Item with volume data"
        def item = [
                size: 2000000L,
                freeSpace: 1000000L,
                mountPoints: ["/mount1", "/mount2"]
        ]

        when: "calculateVolumeMetrics is called"
        def result = datastoresSync.calculateVolumeMetrics(item)

        then: "correct metrics are calculated"
        result.totalSize == 2000000L
        result.freeSpace == 1000000L
        result.usedSpace == 1000000L
        result.mountPoint == "/mount1"
    }

    @Unroll
    def "calculateVolumeMetrics should handle missing data gracefully"() {
        given: "Item with minimal data"
        def item = [:]

        when: "calculateVolumeMetrics is called"
        def result = datastoresSync.calculateVolumeMetrics(item)

        then: "default values are used"
        result.totalSize == 0L
        result.freeSpace == 0L
        result.usedSpace == 0L
        result.mountPoint == null
    }

    @Unroll
    def "updateExistingVolume should update only changed properties"() {
        given: "Existing volume and updated data"
        def existingVolume = new StorageVolume(
                internalId: "old-id",
                maxStorage: 1000000L,
                usedStorage: 500000L,
                name: "Old Name",
                volumePath: "/old-mount",
                datastore: new Datastore(id: 2L)
        )
        def item = [
                id: "new-id",
                name: "New Name"
        ]
        def savedDataStore = new Datastore(id: 1L)
        def volumeMetrics = [
                totalSize: 2000000L,
                usedSpace: 1000000L,
                mountPoint: "/new-mount"
        ]

        when: "updateExistingVolume is called"
        datastoresSync.updateExistingVolume(existingVolume, item, savedDataStore, volumeMetrics)

        then: "volume is updated and saved"
        1 * asyncStorageVolumeService.save(existingVolume) >> Single.just(existingVolume)
        existingVolume.internalId == "new-id"
        existingVolume.maxStorage == 2000000L
        existingVolume.usedStorage == 1000000L
        existingVolume.name == "New Name"
        existingVolume.volumePath == "/new-mount"
        existingVolume.datastore == savedDataStore
    }

    @Unroll
    def "updateExistingVolume should not save when no changes"() {
        given: "Volume with same data"
        def savedDataStore = new Datastore(id: 1L)
        def existingVolume = new StorageVolume(
                internalId: "same-id",
                maxStorage: 2000000L,
                usedStorage: 1000000L,
                name: "Same Name",
                volumePath: "/same-mount",
                datastore: savedDataStore
        )
        def item = [
                id: "same-id",
                name: "Same Name"
        ]
        def volumeMetrics = [
                totalSize: 2000000L,
                usedSpace: 1000000L,
                mountPoint: "/same-mount"
        ]

        when: "updateExistingVolume is called"
        datastoresSync.updateExistingVolume(existingVolume, item, savedDataStore, volumeMetrics)

        then: "no save operation occurs"
        0 * asyncStorageVolumeService.save(_ as StorageVolume)
    }

    @Unroll
    def "addNewVolume should create volume with correct properties"() {
        given: "Volume context data"
        def volumeContext = [
                item: [id: "vol-1", name: "Test Volume"],
                savedDataStore: new Datastore(id: 1L),
                volumeType: new StorageVolumeType(code: 'scvmm-datastore'),
                externalId: "partition-1",
                host: new ComputeServer(),
                volumeMetrics: [
                        totalSize: 2000000L,
                        usedSpace: 1000000L,
                        mountPoint: "/mount1"
                ]
        ]

        when: "addNewVolume is called"
        datastoresSync.addNewVolume(volumeContext)

        then: "new volume is created"
        1 * asyncStorageVolumeService.create({ List<StorageVolume> volumes ->
            volumes.size() == 1 &&
            volumes[0].type == volumeContext.volumeType &&
            volumes[0].name == "Test Volume" &&
            volumes[0].cloudId == cloud.id &&
            volumes[0].maxStorage == 2000000L &&
            volumes[0].usedStorage == 1000000L &&
            volumes[0].externalId == "partition-1" &&
            volumes[0].internalId == "vol-1" &&
            volumes[0].volumePath == "/mount1" &&
            volumes[0].datastore == volumeContext.savedDataStore
        }, volumeContext.host) >> Single.just([])
    }

    @Unroll
    def "getDataStoreExternalId should return partitionUniqueID when available"() {
        given: "Cloud item with partitionUniqueID"
        def cloudItem = [partitionUniqueID: "partition-123"]

        when: "getDataStoreExternalId is called"
        def result = datastoresSync.getDataStoreExternalId(cloudItem)

        then: "partitionUniqueID is returned"
        result == "partition-123"
    }

    @Unroll
    def "getDataStoreExternalId should return storageVolumeID for CSV"() {
        given: "Clustered shared volume with storageVolumeID"
        def cloudItem = [
                isClusteredSharedVolume: true,
                storageVolumeID: "volume-456"
        ]

        when: "getDataStoreExternalId is called"
        def result = datastoresSync.getDataStoreExternalId(cloudItem)

        then: "storageVolumeID is returned"
        result == "volume-456"
    }

    @Unroll
    def "getDataStoreExternalId should return name|host fallback"() {
        given: "Cloud item with only name and vmHost"
        def cloudItem = [
                name: "TestDS",
                vmHost: "TestHost"
        ]

        when: "getDataStoreExternalId is called"
        def result = datastoresSync.getDataStoreExternalId(cloudItem)

        then: "name|host format is returned"
        result == "TestDS|TestHost"
    }

    @Unroll
    def "getName should return cluster:name for CSV"() {
        given: "CSV datastore with cluster"
        def ds = [name: "TestDS", isClusteredSharedVolume: true]
        def cluster = new CloudPool(name: "TestCluster")
        def host = new ComputeServer(name: "TestHost")

        when: "getName is called"
        def result = datastoresSync.getName(ds, cluster, host)

        then: "cluster:name format is returned"
        result == "TestCluster : TestDS"
    }

    @Unroll
    def "getName should return host:name for non-CSV with host"() {
        given: "Non-CSV datastore with host"
        def ds = [name: "TestDS", isClusteredSharedVolume: false]
        def cluster = null
        def host = new ComputeServer(name: "TestHost")

        when: "getName is called"
        def result = datastoresSync.getName(ds, cluster, host)

        then: "host:name format is returned"
        result == "TestHost : TestDS"
    }

    @Unroll
    def "getName should return plain name when no cluster or host"() {
        given: "Datastore with no cluster or host"
        def ds = [name: "TestDS"]
        def cluster = null
        def host = null

        when: "getName is called"
        def result = datastoresSync.getName(ds, cluster, host)

        then: "plain name is returned"
        result == "TestDS"
    }
}
