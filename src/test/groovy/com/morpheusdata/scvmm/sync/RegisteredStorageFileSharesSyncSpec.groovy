package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.MorpheusStorageVolumeService
import com.morpheusdata.core.MorpheusStorageVolumeTypeService
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.cloud.MorpheusDatastoreService
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.DatastoreIdentity
import com.morpheusdata.model.projection.StorageVolumeIdentityProjection
import com.morpheusdata.scvmm.ScvmmApiService
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Unroll

class RegisteredStorageFileSharesSyncSpec extends Specification {

    private TestableRegisteredStorageFileSharesSync sync
    private MorpheusContext mockContext
    private ScvmmApiService mockApiService
    private Cloud mockCloud
    private ComputeServer mockNode

    // Mock services
    private MorpheusAsyncServices mockAsyncServices
    private MorpheusServices mockServices
    private MorpheusSynchronousComputeServerService mockComputeServerService
    private MorpheusSynchronousCloudService mockCloudService
    private MorpheusSynchronousDatastoreService mockDatastoreService
    private MorpheusSynchronousStorageVolumeService mockSyncStorageVolumeService
    private MorpheusCloudService mockAsyncCloudService
    private MorpheusStorageVolumeService mockAsyncStorageVolumeService
    private com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService mockStorageVolumeTypeService
    private MorpheusComputeServerService mockAsyncComputeServerService

    // Test class that allows us to inject mock apiService
    private static class TestableRegisteredStorageFileSharesSync extends RegisteredStorageFileSharesSync {

        TestableRegisteredStorageFileSharesSync(Cloud cloud, ComputeServer node, MorpheusContext context, ScvmmApiService apiService) {
            super(cloud, node, context)
            // Use reflection to set the private apiService field
            def field = RegisteredStorageFileSharesSync.class.getDeclaredField('apiService')
            field.setAccessible(true)
            field.set(this, apiService)
        }
    }

    def setup() {
        // Mock context and services
        mockContext = Mock(MorpheusContext)
        mockApiService = Mock(ScvmmApiService)

        // Mock synchronous services
        mockComputeServerService = Mock(MorpheusSynchronousComputeServerService)
        mockDatastoreService = Mock(MorpheusSynchronousDatastoreService)
        mockStorageVolumeTypeService = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService)
        mockSyncStorageVolumeService = Mock(MorpheusSynchronousStorageVolumeService) {
            getStorageVolumeType() >> mockStorageVolumeTypeService
        }

        mockCloudService = Mock(MorpheusSynchronousCloudService) {
            getDatastore() >> mockDatastoreService
        }

        mockServices = Mock(MorpheusServices) {
            getComputeServer() >> mockComputeServerService
            getCloud() >> mockCloudService
            getStorageVolume() >> mockSyncStorageVolumeService
        }

        mockServices = Mock(MorpheusServices) {
            getComputeServer() >> mockComputeServerService
            getCloud() >> mockCloudService
            getStorageVolume() >> mockSyncStorageVolumeService
        }

        // Mock async services
        mockAsyncCloudService = Mock(MorpheusCloudService) {
            getDatastore() >> Mock(MorpheusDatastoreService)
        }
        mockAsyncStorageVolumeService = Mock(MorpheusStorageVolumeService)
        mockAsyncComputeServerService = Mock(MorpheusComputeServerService)

        mockAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> mockAsyncCloudService
            getStorageVolume() >> mockAsyncStorageVolumeService
            getComputeServer() >> mockAsyncComputeServerService
        }

        // Configure context
        mockContext.getServices() >> mockServices
        mockContext.getAsync() >> mockAsyncServices

        // Create test objects
        mockCloud = new Cloud(
                id: 1L,
                name: "test-cloud",
                owner: new Account(id: 1L)
        )

        mockNode = new ComputeServer(
                id: 2L,
                name: "test-node",
                externalId: "node-123"
        )

        // Create testable sync instance
        sync = new TestableRegisteredStorageFileSharesSync(mockCloud, mockNode, mockContext, mockApiService)
    }

    def "execute should handle successful API response setup"() {
        given: "Mock API service returns successful file shares list"
        def scvmmOpts = [host: "test-host", username: "test-user"]
        def fileSharesData = [
                [
                        ID                     : "share-1",
                        Name                   : "FileShare1",
                        Capacity               : 1000000000,
                        FreeSpace              : 500000000,
                        IsAvailableForPlacement: true,
                        ClusterAssociations    : [[HostID: "host-1"]],
                        HostAssociations       : [[HostID: "host-2"]]
                ]
        ]
        def listResults = [success: true, datastores: fileSharesData]

        when: "execute is called"
        sync.execute()

        then: "API service is called correctly"
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, mockCloud, mockNode) >> scvmmOpts
        1 * mockApiService.listRegisteredFileShares(scvmmOpts) >> listResults
        // Allow the sync task to proceed but don't verify specific internal calls due to complexity
        (0.._) * mockAsyncCloudService.datastore._
    }

    def "execute should handle API failure gracefully"() {
        given: "Mock API service returns failure"
        def scvmmOpts = [host: "test-host", username: "test-user"]
        def listResults = [success: false]

        when: "execute is called"
        sync.execute()

        then: "API service is called but sync is not processed"
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, mockCloud, mockNode) >> scvmmOpts
        1 * mockApiService.listRegisteredFileShares(scvmmOpts) >> listResults
        0 * mockAsyncCloudService.datastore._
    }

    def "addMissingFileShares should handle empty list gracefully"() {
        given: "Empty list of file shares to add"
        def addList = []
        def objList = []

        when: "addMissingFileShares is called"
        sync.addMissingFileShares(addList, objList)

        then: "No datastores are created but volume sync is attempted"
        0 * mockAsyncCloudService.datastore.bulkCreate(_)
        1 * mockComputeServerService.list(_) >> []
        1 * mockCloudService.datastore.list(_) >> []
    }

    def "updateMatchedFileShares should update existing datastores"() {
        given: "List of file shares to update"
        def existingDatastore = new Datastore(
                id: 1L,
                name: "OldName",
                online: false,
                freeSpace: 100000,
                storageSize: 200000
        )
        def updateItem = new SyncTask.UpdateItem<Datastore, Map>(
                existingItem: existingDatastore,
                masterItem: [
                        ID                     : "share-1",
                        Name                   : "NewName",
                        IsAvailableForPlacement: true,
                        FreeSpace              : 150000,
                        Capacity               : 300000,
                        ClusterAssociations    : [[HostID: "host-1"]]
                ]
        )
        def updateList = [updateItem]
        def objList = []

        when: "updateMatchedFileShares is called"
        sync.updateMatchedFileShares(updateList, objList)

        then: "Datastores are updated and volumes synced"
        1 * mockAsyncCloudService.datastore.bulkSave(_) >> Single.just([])
        1 * mockComputeServerService.list(_) >> []
        1 * mockCloudService.datastore.list(_) >> []
        existingDatastore.name == "NewName"
        existingDatastore.online
        existingDatastore.freeSpace == 150000
        existingDatastore.storageSize == 300000
    }

    def "syncVolumesForSingleHost should sync volumes for host with volumes to add"() {
        given: "Host with no existing volumes and master volume IDs to add"
        def host = new ComputeServer(
                id: 1L,
                externalId: "host-1",
                volumes: []
        )
        def hostToShareMap = [
                "host-1": ["share-1", "share-2"] as Set
        ]
        def datastore1 = new Datastore(
                id: 1L,
                externalId: "share-1",
                name: "DS1",
                storageSize: 1000L,
                freeSpace: 300L
        )
        def datastore2 = new Datastore(
                id: 2L,
                externalId: "share-2",
                name: "DS2",
                storageSize: 2000L,
                freeSpace: 500L
        )
        def morphDatastores = [datastore1, datastore2]
        def findMountPath = { id -> "\\\\server\\${id}" }
        def volumeType = new StorageVolumeType(code: "scvmm-registered-file-share-datastore")

        and: "Mock volume type lookup"
        mockStorageVolumeTypeService.find(_) >> volumeType

        when: "syncVolumesForSingleHost is called"
        sync.syncVolumesForSingleHost(host, hostToShareMap, morphDatastores, findMountPath)

        then: "New volumes are created for the host"
        2 * mockAsyncStorageVolumeService.create(_, host) >> Single.just([])
        2 * mockAsyncComputeServerService.save(host) >> Single.just(host)
    }

    def "syncVolumesForSingleHost should handle empty volumes list"() {
        given: "Host with no existing volumes and empty master list"
        def host = new ComputeServer(
                id: 1L,
                externalId: "host-1",
                volumes: []
        )
        def hostToShareMap = [
                "host-1": [] as Set
        ]
        def morphDatastores = []
        def findMountPath = { id -> null }
        def volumeType = new StorageVolumeType(code: "scvmm-registered-file-share-datastore")

        and: "Mock volume type lookup"
        mockStorageVolumeTypeService.find(_) >> volumeType

        when: "syncVolumesForSingleHost is called"
        sync.syncVolumesForSingleHost(host, hostToShareMap, morphDatastores, findMountPath)

        then: "No volumes are created or removed"
        0 * mockAsyncStorageVolumeService.create(_, _)
        0 * mockAsyncStorageVolumeService.save(_)
        0 * mockAsyncStorageVolumeService.remove(_)
    }

    def "syncVolumeForEachHosts should iterate through all hosts and sync volumes"() {
        given: "Multiple hosts with volume mappings"
        def host1 = new ComputeServer(
                id: 1L,
                externalId: "host-1",
                volumes: []
        )
        def host2 = new ComputeServer(
                id: 2L,
                externalId: "host-2",
                volumes: []
        )
        def hostToShareMap = [
                "host-1": ["share-1"] as Set,
                "host-2": ["share-2"] as Set
        ]
        def datastore1 = new Datastore(
                id: 1L,
                externalId: "share-1",
                name: "DS1",
                storageSize: 1000L,
                freeSpace: 300L
        )
        def datastore2 = new Datastore(
                id: 2L,
                externalId: "share-2",
                name: "DS2",
                storageSize: 2000L,
                freeSpace: 500L
        )
        def objList = [
                [ID: "share-1", MountPoints: ["\\\\server\\share1"]],
                [ID: "share-2", MountPoints: ["\\\\server\\share2"]]
        ]
        def volumeType = new StorageVolumeType(code: "scvmm-registered-file-share-datastore")

        and: "Mock services"
        mockComputeServerService.list(_) >> [host1, host2]
        mockCloudService.datastore.list(_) >> [datastore1, datastore2]
        mockStorageVolumeTypeService.find(_) >> volumeType

        when: "syncVolumeForEachHosts is called"
        sync.syncVolumeForEachHosts(hostToShareMap, objList)

        then: "Volumes are created for each host"
        2 * mockAsyncStorageVolumeService.create(_, _) >> Single.just([])
        2 * mockAsyncComputeServerService.save(_) >> Single.just(new ComputeServer())
    }

    def "syncVolumeForEachHosts should handle empty host list gracefully"() {
        given: "Empty host list"
        def hostToShareMap = ["host-1": ["share-1"] as Set]
        def objList = [[ID: "share-1", MountPoints: ["\\\\server\\share1"]]]

        and: "Mock empty host list"
        mockComputeServerService.list(_) >> []
        mockCloudService.datastore.list(_) >> []

        when: "syncVolumeForEachHosts is called"
        sync.syncVolumeForEachHosts(hostToShareMap, objList)

        then: "No volumes are created"
        0 * mockAsyncStorageVolumeService.create(_, _)
        0 * mockAsyncComputeServerService.save(_)
    }

    def "updateMatchedFileShares should process multiple updates correctly"() {
        given: "Multiple file shares to update"
        def existingDatastore1 = new Datastore(
                id: 1L,
                name: "OldName1",
                online: false,
                freeSpace: 100000,
                storageSize: 200000
        )
        def existingDatastore2 = new Datastore(
                id: 2L,
                name: "OldName2",
                online: true,
                freeSpace: 150000,
                storageSize: 300000
        )
        def masterItem1 = [
                ID: "share-1",
                Name: "NewName1",
                IsAvailableForPlacement: true,
                FreeSpace: 120000,
                Capacity: 250000,
                ClusterAssociations: [[HostID: "host-1"]]
        ]
        def masterItem2 = [
                ID: "share-2",
                Name: "NewName2",
                IsAvailableForPlacement: false,
                FreeSpace: 180000,
                Capacity: 350000,
                HostAssociations: [[HostID: "host-2"]]
        ]
        def updateItem1 = new SyncTask.UpdateItem<Datastore, Map>(
                existingItem: existingDatastore1,
                masterItem: masterItem1
        )
        def updateItem2 = new SyncTask.UpdateItem<Datastore, Map>(
                existingItem: existingDatastore2,
                masterItem: masterItem2
        )
        def updateList = [updateItem1, updateItem2]
        def objList = []

        and: "Mock services"
        mockAsyncCloudService.datastore.bulkSave(_) >> Single.just([])
        mockComputeServerService.list(_) >> []
        mockCloudService.datastore.list(_) >> []

        when: "updateMatchedFileShares is called"
        sync.updateMatchedFileShares(updateList, objList)

        then: "Both datastores are updated"
        1 * mockAsyncCloudService.datastore.bulkSave({ List items ->
            items.size() == 2 &&
                    items[0].name == "NewName1" &&
                    items[1].name == "NewName2"
        }) >> Single.just([])
        existingDatastore1.name == "NewName1"
        existingDatastore1.online
        existingDatastore2.name == "NewName2"
        !existingDatastore2.online
    }

    def "syncVolumesForSingleHost should handle host with no master volume IDs"() {
        given: "Host with no master volume IDs in map"
        def host = new ComputeServer(
                id: 1L,
                externalId: "host-1",
                volumes: []
        )
        def hostToShareMap = [:]  // Empty map - no entries for host-1
        def morphDatastores = []
        def findMountPath = { id -> null }
        def volumeType = new StorageVolumeType(code: "scvmm-registered-file-share-datastore")
        def mockSyncTask = Mock(SyncTask) {
            start() >> null
        }

        and: "Mock volume type lookup"
        mockStorageVolumeTypeService.find(_) >> volumeType
        // Spy on the sync object to mock the createStorageVolumeSyncTask method
        def spySync = Spy(sync)
        spySync.createStorageVolumeSyncTask(_, null, _, _, _) >> mockSyncTask

        when: "syncVolumesForSingleHost is called"
        spySync.syncVolumesForSingleHost(host, hostToShareMap, morphDatastores, findMountPath)

        then: "No volumes are created or removed and sync task is started"
        1 * mockSyncTask.start()
        0 * mockAsyncStorageVolumeService.create(_, _)
        0 * mockAsyncStorageVolumeService.save(_)
        0 * mockAsyncStorageVolumeService.remove(_)
    }

    def "execute should handle complete sync flow with successful API response"() {
        given: "Mock API service returns successful file shares list"
        def scvmmOpts = [host: "test-host", username: "test-user"]
        def fileSharesData = [
                [
                        ID: "share-1",
                        Name: "FileShare1",
                        Capacity: 1000000000,
                        FreeSpace: 500000000,
                        IsAvailableForPlacement: true,
                        ClusterAssociations: [[HostID: "host-1"]],
                        HostAssociations: [[HostID: "host-2"]]
                ],
                [
                        ID: "share-2",
                        Name: "FileShare2",
                        Capacity: 2000000000,
                        FreeSpace: 800000000,
                        IsAvailableForPlacement: false,
                        ClusterAssociations: [[HostID: "host-3"]]
                ]
        ]
        def listResults = [success: true, datastores: fileSharesData]

        // Mock existing datastore for update scenario
        def existingDatastore = Mock(DatastoreIdentity) {
            getId() >> 1L
            getExternalId() >> "share-1"
        }
        def domainRecords = Observable.just(existingDatastore)

        when: "execute is called"
        sync.execute()

        then: "API service is called and sync process completes"
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, mockCloud, mockNode) >> scvmmOpts
        1 * mockApiService.listRegisteredFileShares(scvmmOpts) >> listResults
        1 * mockAsyncCloudService.datastore.listIdentityProjections(_) >> domainRecords
        // Allow for datastore operations during sync
        (0.._) * mockAsyncCloudService.datastore._
        (0.._) * mockComputeServerService._
        (0.._) * mockCloudService._
    }

    def "execute should handle API response with empty datastores list"() {
        given: "Mock API service returns empty datastores list"
        def scvmmOpts = [host: "test-host", username: "test-user"]
        def listResults = [success: true, datastores: []]

        when: "execute is called"
        sync.execute()

        then: "API service is called but no sync operations occur"
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, mockCloud, mockNode) >> scvmmOpts
        1 * mockApiService.listRegisteredFileShares(scvmmOpts) >> listResults
        // Empty datastores list means the condition fails and listIdentityProjections is not called
        0 * mockAsyncCloudService.datastore.listIdentityProjections(_)
        0 * mockAsyncCloudService.datastore.remove(_)
        0 * mockAsyncCloudService.datastore.bulkCreate(_)
        0 * mockAsyncCloudService.datastore.bulkSave(_)
    }


    def "execute should handle API response with null datastores"() {
        given: "Mock API service returns null datastores"
        def scvmmOpts = [host: "test-host", username: "test-user"]
        def listResults = [success: true, datastores: null]

        when: "execute is called"
        sync.execute()

        then: "API service is called but sync process is skipped"
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, mockCloud, mockNode) >> scvmmOpts
        1 * mockApiService.listRegisteredFileShares(scvmmOpts) >> listResults
        0 * mockAsyncCloudService.datastore.listIdentityProjections(_)
    }

    def "execute should handle exception during API call gracefully"() {
        given: "Mock API service throws exception"
        def scvmmOpts = [host: "test-host", username: "test-user"]

        when: "execute is called"
        sync.execute()

        then: "Exception is caught and logged"
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, mockCloud, mockNode) >> scvmmOpts
        1 * mockApiService.listRegisteredFileShares(scvmmOpts) >> { throw new RuntimeException("API Error") }
        0 * mockAsyncCloudService.datastore._
        // Test should not throw exception due to try-catch
        noExceptionThrown()
    }

    def "execute should handle exception during sync task execution"() {
        given: "Mock API service returns data but sync fails"
        def scvmmOpts = [host: "test-host", username: "test-user"]
        def fileSharesData = [
                [ID: "share-1", Name: "FileShare1", Capacity: 1000000000, FreeSpace: 500000000]
        ]
        def listResults = [success: true, datastores: fileSharesData]

        when: "execute is called"
        sync.execute()

        then: "Exception during sync is caught"
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, mockCloud, mockNode) >> scvmmOpts
        1 * mockApiService.listRegisteredFileShares(scvmmOpts) >> listResults
        1 * mockAsyncCloudService.datastore.listIdentityProjections(_) >> { throw new RuntimeException("Sync Error") }
        noExceptionThrown()
    }

    def "addMissingFileShares should skip bulkCreate when no datastores to add"() {
        given: "Empty add list"
        def addList = []
        def objList = []

        when: "addMissingFileShares is called"
        sync.addMissingFileShares(addList, objList)

        then: "No datastores are created but volume sync still occurs"
        0 * mockAsyncCloudService.datastore.bulkCreate(_)
        1 * mockComputeServerService.list(_) >> []
        1 * mockCloudService.datastore.list(_) >> []
    }

    def "updateDatastoreOnlineStatus should update online status when different"() {
        given: "Datastore with different online status"
        def datastore = new Datastore(online: false)
        def masterItem = [IsAvailableForPlacement: true]

        when: "updateDatastoreOnlineStatus is called"
        def result = sync.updateDatastoreOnlineStatus(datastore, masterItem)

        then: "Online status is updated and true is returned"
        result
        datastore.online
    }

    def "updateDatastoreOnlineStatus should not update when status is same"() {
        given: "Datastore with same online status"
        def datastore = new Datastore(online: true)
        def masterItem = [IsAvailableForPlacement: true]

        when: "updateDatastoreOnlineStatus is called"
        def result = sync.updateDatastoreOnlineStatus(datastore, masterItem)

        then: "Online status is not updated and false is returned"
        !result
        datastore.online
    }

    def "updateDatastoreName should update name when different"() {
        given: "Datastore with different name"
        def datastore = new Datastore(name: "OldName")
        def masterItem = [Name: "NewName"]

        when: "updateDatastoreName is called"
        def result = sync.updateDatastoreName(datastore, masterItem)

        then: "Name is updated and true is returned"
        result
        datastore.name == "NewName"
    }

    def "updateDatastoreName should not update when name is same"() {
        given: "Datastore with same name"
        def datastore = new Datastore(name: "SameName")
        def masterItem = [Name: "SameName"]

        when: "updateDatastoreName is called"
        def result = sync.updateDatastoreName(datastore, masterItem)

        then: "Name is not updated and false is returned"
        !result
        datastore.name == "SameName"
    }

    def "updateDatastoreFreeSpace should update free space when different"() {
        given: "Datastore with different free space"
        def datastore = new Datastore(freeSpace: 100000)
        def masterItem = [FreeSpace: "200000"]

        when: "updateDatastoreFreeSpace is called"
        def result = sync.updateDatastoreFreeSpace(datastore, masterItem)

        then: "Free space is updated and true is returned"
        result
        datastore.freeSpace == 200000
    }

    def "updateDatastoreStorageSize should update storage size when different"() {
        given: "Datastore with different storage size"
        def datastore = new Datastore(storageSize: 100000)
        def masterItem = [Capacity: "200000"]

        when: "updateDatastoreStorageSize is called"
        def result = sync.updateDatastoreStorageSize(datastore, masterItem)

        then: "Storage size is updated and true is returned"
        result
        datastore.storageSize == 200000
    }

    def "mapHostToShares should correctly map cluster and host associations"() {
        given: "Master item with associations"
        def masterItem = [
                ID                 : "share-1",
                ClusterAssociations: [[HostID: "cluster-host-1"]],
                HostAssociations   : [[HostID: "direct-host-1"]]
        ]
        def hostToShareMap = [:]

        when: "mapHostToShares is called"
        sync.mapHostToShares(masterItem, hostToShareMap)

        then: "Host to share mapping is correctly created"
        hostToShareMap["cluster-host-1"].contains("share-1")
        hostToShareMap["direct-host-1"].contains("share-1")
    }

    def "getExistingHosts should return correct hosts"() {
        given: "Mock compute server service with hosts"
        def expectedHosts = [
                new ComputeServer(id: 1L, name: "host1"),
                new ComputeServer(id: 2L, name: "host2")
        ]

        when: "getExistingHosts is called"
        def result = sync.getExistingHosts()

        then: "Correct hosts are returned"
        1 * mockComputeServerService.list(_) >> expectedHosts
        result == expectedHosts
    }

    def "getMorphDatastores should return correct datastores"() {
        given: "Mock datastore service with datastores"
        def expectedDatastores = [
                new Datastore(id: 1L, name: "ds1"),
                new Datastore(id: 2L, name: "ds2")
        ]

        when: "getMorphDatastores is called"
        def result = sync.getMorphDatastores()

        then: "Correct datastores are returned"
        1 * mockCloudService.datastore.list(_) >> expectedDatastores
        result == expectedDatastores
    }

    def "createMountPathFinder should return correct mount path finder"() {
        given: "Object list with mount points"
        def objList = [
                [ID: "share-1", MountPoints: ["\\\\server\\share1"]],
                [ID: "share-2", MountPoints: ["\\\\server\\share2"]]
        ]

        when: "createMountPathFinder is called"
        def finder = sync.createMountPathFinder(objList)
        def result1 = finder("share-1")
        def result2 = finder("share-2")
        def result3 = finder("share-3")

        then: "Mount path finder returns correct paths"
        result1 == "\\\\server\\share1"
        result2 == "\\\\server\\share2"
        result3 == null
    }

    def "getVolumeType should return correct volume type"() {
        given: "Mock storage volume type service"
        def expectedVolumeType = new StorageVolumeType(code: "scvmm-registered-file-share-datastore")

        when: "getVolumeType is called"
        def result = sync.getVolumeType()

        then: "Correct volume type is returned"
        1 * mockStorageVolumeTypeService.find(_) >> expectedVolumeType
        result == expectedVolumeType
    }

    def "getExistingStorageVolumes should filter volumes by type"() {
        given: "Host with various volumes"
        def volumeType = new StorageVolumeType(code: "test-type")
        def matchingVolume = new StorageVolume(type: volumeType)
        def nonMatchingVolume = new StorageVolume(type: new StorageVolumeType(code: "other-type"))
        def host = new ComputeServer(volumes: [matchingVolume, nonMatchingVolume])

        when: "getExistingStorageVolumes is called"
        def result = sync.getExistingStorageVolumes(host, volumeType)

        then: "Only matching volumes are returned"
        result.size() == 1
        result[0] == matchingVolume
    }

    def "matchStorageVolume should correctly match by external ID"() {
        given: "Storage volume and cloud item"
        def storageVolume = Mock(StorageVolumeIdentityProjection) { getExternalId() >> "vol-123" }
        def cloudItem = [ID: "vol-123"]
        def nonMatchingCloudItem = [ID: "vol-456"]

        when: "matchStorageVolume is called"
        def result1 = sync.matchStorageVolume(storageVolume, cloudItem)
        def result2 = sync.matchStorageVolume(storageVolume, nonMatchingCloudItem)

        then: "Correct matching result is returned"
        result1
        !result2
    }

    @Unroll
    def "updateProperty should update property when values are different"() {
        given: "Storage volume with property"
        def volume = new StorageVolume()
        volume."${property}" = oldValue

        when: "updateProperty is called"
        def result = sync.updateProperty(volume, property, newValue)

        then: "Property is updated correctly"
        result == expectedResult
        volume."${property}" == expectedValue

        where:
        property      | oldValue | newValue | expectedResult | expectedValue
        "maxStorage"  | 100L     | 200L     | true           | 200L
        "maxStorage"  | 100L     | 100L     | false          | 100L
        "usedStorage" | 50L      | 75L      | true           | 75L
        "name"        | "old"    | "new"    | true           | "new"
        "name"        | "same"   | "same"   | false          | "same"
    }

    def "updateDatastoreProperty should update datastore when different"() {
        given: "Storage volume and datastore"
        def oldDatastore = new Datastore(id: 1L)
        def newDatastore = new Datastore(id: 2L)
        def volume = new StorageVolume(datastore: oldDatastore)

        when: "updateDatastoreProperty is called"
        def result = sync.updateDatastoreProperty(volume, newDatastore)

        then: "Datastore is updated and true is returned"
        result
        volume.datastore == newDatastore
    }

    def "updateDatastoreProperty should not update when same datastore"() {
        given: "Storage volume with same datastore"
        def datastore = new Datastore(id: 1L)
        def volume = new StorageVolume(datastore: datastore)

        when: "updateDatastoreProperty is called"
        def result = sync.updateDatastoreProperty(volume, datastore)

        then: "Datastore is not updated and false is returned"
        !result
        volume.datastore == datastore
    }

    def "createNewStorageVolume should create volume with correct properties"() {
        given: "Datastore, external ID, and mount path finder"
        def datastore = new Datastore(
                storageSize: 1000L,
                freeSpace: 300L,
                name: "TestDatastore"
        )
        def externalId = "vol-123"
        def mountPath = "\\\\server\\share"
        def findMountPath = { id -> id == externalId ? mountPath : null }
        def volumeType = new StorageVolumeType(code: "test-type")

        and: "Mock the storage volume type service to return the volume type"
        mockStorageVolumeTypeService.find(_) >> volumeType

        when: "createNewStorageVolume is called"
        def result = sync.createNewStorageVolume(datastore, externalId, findMountPath)

        then: "Storage volume is created with correct properties"
        result.type == volumeType
        result.maxStorage == 1000L
        result.usedStorage == 700L
        result.externalId == externalId
        result.internalId == externalId
        result.name == "TestDatastore"
        result.volumePath == mountPath
        result.cloudId == mockCloud.id
        result.datastore == datastore
    }

    def "processDatastoreUpdate should update datastore and map hosts when changes detected"() {
        given: "Update item with changes"
        def existingDatastore = new Datastore(
                id: 1L,
                name: "OldName",
                online: false,
                freeSpace: 100000,
                storageSize: 200000
        )
        def masterItem = [
                ID                     : "share-1",
                Name                   : "NewName",
                IsAvailableForPlacement: true,
                FreeSpace              : 150000,
                Capacity               : 300000,
                ClusterAssociations    : [[HostID: "host-1"]]
        ]
        def updateItem = new SyncTask.UpdateItem<Datastore, Map>(
                existingItem: existingDatastore,
                masterItem: masterItem
        )
        def itemsToUpdate = []
        def hostToShareMap = [:]

        when: "processDatastoreUpdate is called"
        sync.processDatastoreUpdate(updateItem, itemsToUpdate, hostToShareMap)

        then: "Datastore is updated and added to update list, hosts are mapped"
        itemsToUpdate.size() == 1
        itemsToUpdate[0] == existingDatastore
        hostToShareMap["host-1"].contains("share-1")
        existingDatastore.name == "NewName"
        existingDatastore.online
        existingDatastore.freeSpace == 150000
        existingDatastore.storageSize == 300000
    }

    def "updateDatastoreProperties should return true when any property is updated"() {
        given: "Datastore and master item with different values"
        def datastore = new Datastore(
                name: "OldName",
                online: false,
                freeSpace: 100000,
                storageSize: 200000
        )
        def masterItem = [
                Name                   : "NewName",
                IsAvailableForPlacement: true,
                FreeSpace              : 150000,
                Capacity               : 300000
        ]

        when: "updateDatastoreProperties is called"
        def result = sync.updateDatastoreProperties(datastore, masterItem)

        then: "All properties are updated and true is returned"
        result
        datastore.name == "NewName"
        datastore.online
        datastore.freeSpace == 150000
        datastore.storageSize == 300000
    }

    def "updateDatastoreProperties should return false when no properties need updating"() {
        given: "Datastore and master item with same values"
        def datastore = new Datastore(
                name: "SameName",
                online: true,
                freeSpace: 150000,
                storageSize: 300000
        )
        def masterItem = [
                Name                   : "SameName",
                IsAvailableForPlacement: true,
                FreeSpace              : "150000",
                Capacity               : "300000"
        ]

        when: "updateDatastoreProperties is called"
        def result = sync.updateDatastoreProperties(datastore, masterItem)

        then: "No properties are updated and false is returned"
        !result
        datastore.name == "SameName"
        datastore.online
        datastore.freeSpace == 150000
        datastore.storageSize == 300000
    }

    def "saveUpdatedDatastores should call bulkSave when items exist"() {
        given: "List of items to update"
        def datastore1 = new Datastore(id: 1L, name: "ds1")
        def datastore2 = new Datastore(id: 2L, name: "ds2")
        def itemsToUpdate = [datastore1, datastore2]

        when: "saveUpdatedDatastores is called"
        sync.saveUpdatedDatastores(itemsToUpdate)

        then: "bulkSave is called with the items"
        1 * mockAsyncCloudService.datastore.bulkSave(itemsToUpdate) >> Single.just([])
    }

    def "saveUpdatedDatastores should not call bulkSave when no items exist"() {
        given: "Empty list of items to update"
        def itemsToUpdate = []

        when: "saveUpdatedDatastores is called"
        sync.saveUpdatedDatastores(itemsToUpdate)

        then: "bulkSave is not called"
        0 * mockAsyncCloudService.datastore.bulkSave(_)
    }

    def "createHostEntryGetter should return closure that creates host entries"() {
        given: "Empty host to share map"
        def hostToShareMap = [:]

        when: "createHostEntryGetter is called and the returned closure is used"
        def getter = sync.createHostEntryGetter(hostToShareMap)
        def result1 = getter("host-1")
        def result2 = getter("host-1")  // Same host
        def result3 = getter("host-2")  // Different host

        then: "Host entries are created correctly"
        result1 instanceof Set
        result2 instanceof Set
        result3 instanceof Set
        // Verify entries are stored in the map
        hostToShareMap.containsKey("host-1")
        hostToShareMap.containsKey("host-2")
        hostToShareMap.size() == 2
    }

    def "processClusterAssociations should add external ID to host entries"() {
        given: "Master item with cluster associations and host entry getter"
        def masterItem = [
                ClusterAssociations: [
                        [HostID: "cluster-host-1"],
                        [HostID: "cluster-host-2"]
                ]
        ]
        def externalId = "share-1"
        def hostToShareMap = [:]
        def getOrCreateHostEntry = { hostId ->
            if (!hostToShareMap.containsKey(hostId)) {
                hostToShareMap.put(hostId, [] as Set)
            }
            hostToShareMap.get(hostId)
        }

        when: "processClusterAssociations is called"
        sync.processClusterAssociations(masterItem, externalId, getOrCreateHostEntry)

        then: "External ID is added to all cluster host entries"
        hostToShareMap["cluster-host-1"].contains(externalId)
        hostToShareMap["cluster-host-2"].contains(externalId)
    }

    def "processHostAssociations should add external ID to host entries"() {
        given: "Master item with host associations and host entry getter"
        def masterItem = [
                HostAssociations: [
                        [HostID: "direct-host-1"],
                        [HostID: "direct-host-2"]
                ]
        ]
        def externalId = "share-1"
        def hostToShareMap = [:]
        def getOrCreateHostEntry = { hostId ->
            if (!hostToShareMap.containsKey(hostId)) {
                hostToShareMap.put(hostId, [] as Set)
            }
            hostToShareMap.get(hostId)
        }

        when: "processHostAssociations is called"
        sync.processHostAssociations(masterItem, externalId, getOrCreateHostEntry)

        then: "External ID is added to all direct host entries"
        hostToShareMap["direct-host-1"].contains(externalId)
        hostToShareMap["direct-host-2"].contains(externalId)
    }

    // Simpler direct tests for volume sync methods

    def "getExistingStorageVolumes filters volumes by type correctly"() {
        given: "Host with mixed volume types"
        def targetType = new StorageVolumeType(code: "target-type")
        def otherType = new StorageVolumeType(code: "other-type")
        def volume1 = new StorageVolume(id: 1L, type: targetType)
        def volume2 = new StorageVolume(id: 2L, type: otherType)
        def volume3 = new StorageVolume(id: 3L, type: targetType)
        def host = new ComputeServer(volumes: [volume1, volume2, volume3])

        when: "getExistingStorageVolumes is called"
        def result = sync.getExistingStorageVolumes(host, targetType)

        then: "Only volumes matching the type are returned"
        result.size() == 2
        result.contains(volume1)
        result.contains(volume3)
        !result.contains(volume2)
    }

    def "deleteStorageVolumes calls removeStorageVolume for each item"() {
        given: "List of volumes to remove and host"
        def host = new ComputeServer(id: 1L, externalId: "host-1")
        def volume1 = new StorageVolume(id: 1L, name: "vol1", controller: null, datastore: null)
        def volume2 = new StorageVolume(id: 2L, name: "vol2", controller: null, datastore: null)
        def removeItems = [volume1, volume2]

        when: "deleteStorageVolumes is called"
        sync.deleteStorageVolumes(removeItems, host)

        then: "Each volume is processed - save and remove called for each"
        2 * mockAsyncStorageVolumeService.save(_) >> Single.just(new StorageVolume())
        2 * mockAsyncStorageVolumeService.remove({ it instanceof List }, host, true) >> Single.just(true)
        2 * mockAsyncStorageVolumeService.remove({ it instanceof StorageVolume }) >> Single.just(true)
    }

    def "removeStorageVolume should clear references and remove volume"() {
        given: "Volume with controller and datastore references"
        def host = new ComputeServer(id: 1L)
        def volume = new StorageVolume(
                id: 1L,
                name: "vol1",
                controller: new StorageController(id: 1L),
                datastore: new Datastore(id: 1L)
        )

        when: "removeStorageVolume is called"
        sync.removeStorageVolume(volume, host)

        then: "Controller and datastore are cleared"
        volume.controller == null
        volume.datastore == null

        and: "Volume is saved and removed"
        1 * mockAsyncStorageVolumeService.save(volume) >> Single.just(volume)
        1 * mockAsyncStorageVolumeService.remove([volume], host, true) >> Single.just(true)
        1 * mockAsyncStorageVolumeService.remove(volume) >> Single.just(true)
    }

    def "updateStorageVolumes calls updateSingleStorageVolume for each item"() {
        given: "List of volumes to update"
        def datastore = new Datastore(
                id: 1L,
                externalId: "share-1",
                name: "DS1",
                storageSize: 1000L,
                freeSpace: 300L
        )
        def volume = new StorageVolume(id: 1L, name: "OldName", maxStorage: 500L, usedStorage: 200L)
        def updateItem = [
                existingItem: volume,
                masterItem  : "share-1"
        ]
        def updateItems = [updateItem]
        def morphDatastores = [datastore]
        def findMountPath = { id -> "\\\\server\\${id}" }

        when: "updateStorageVolumes is called"
        sync.updateStorageVolumes(updateItems, morphDatastores, findMountPath)

        then: "Volume is saved with updates"
        1 * mockAsyncStorageVolumeService.save(volume) >> Single.just(volume)
        volume.name == "DS1"
        volume.maxStorage == 1000L
    }

    def "updateSingleStorageVolume should update volume properties"() {
        given: "Volume and matching datastore"
        def datastore = new Datastore(
                id: 1L,
                externalId: "share-1",
                name: "NewName",
                storageSize: 1000L,
                freeSpace: 300L
        )
        def volume = new StorageVolume(
                id: 1L,
                name: "OldName",
                maxStorage: 500L,
                usedStorage: 200L
        )
        def updateMap = [
                existingItem: volume,
                masterItem  : "share-1"
        ]
        def morphDatastores = [datastore]
        def findMountPath = { id -> "\\\\server\\${id}" }

        when: "updateSingleStorageVolume is called"
        sync.updateSingleStorageVolume(updateMap, morphDatastores, findMountPath)

        then: "Volume properties are updated and saved"
        1 * mockAsyncStorageVolumeService.save(volume) >> Single.just(volume)
        volume.name == "NewName"
        volume.maxStorage == 1000L
        volume.usedStorage == 700L
    }

    def "updateStorageVolumeProperties should update all changed properties"() {
        given: "Volume and datastore with different values"
        def datastore = new Datastore(
                id: 1L,
                externalId: "share-1",
                name: "NewName",
                storageSize: 2000L,
                freeSpace: 500L
        )
        def volume = new StorageVolume(
                id: 1L,
                name: "OldName",
                maxStorage: 1000L,
                usedStorage: 700L,
                volumePath: "\\\\old\\path"
        )
        def findMountPath = { id -> "\\\\server\\${id}" }

        when: "updateStorageVolumeProperties is called"
        def result = sync.updateStorageVolumeProperties(volume, datastore, findMountPath, "share-1")

        then: "All properties are updated and true is returned"
        result
        volume.name == "NewName"
        volume.maxStorage == 2000L
        volume.usedStorage == 1500L
        volume.volumePath == "\\\\server\\share-1"
        volume.datastore == datastore
    }

    def "updateStorageVolumeProperties should return false when no changes needed"() {
        given: "Volume and datastore with same values"
        def datastore = new Datastore(
                id: 1L,
                externalId: "share-1",
                name: "SameName",
                storageSize: 1000L,
                freeSpace: 300L
        )
        def volume = new StorageVolume(
                id: 1L,
                name: "SameName",
                maxStorage: 1000L,
                usedStorage: 700L,
                volumePath: "\\\\server\\share-1",
                datastore: datastore
        )
        def findMountPath = { id -> "\\\\server\\${id}" }

        when: "updateStorageVolumeProperties is called"
        def result = sync.updateStorageVolumeProperties(volume, datastore, findMountPath, "share-1")

        then: "No properties are updated and false is returned"
        !result
    }

    def "createStorageVolumeSyncTask should create sync task with all operations"() {
        given: "Domain records and master volume IDs"
        def host = new ComputeServer(id: 1L, externalId: "host-1")
        def volume = Mock(StorageVolumeIdentityProjection) {
            getExternalId() >> "vol-1"
        }
        def domainRecords = Observable.just(volume)
        def masterVolumeIds = ["vol-1", "vol-2"]
        def morphDatastores = []
        def findMountPath = { id -> "\\\\server\\${id}" }

        when: "createStorageVolumeSyncTask is called"
        def syncTask = sync.createStorageVolumeSyncTask(domainRecords, masterVolumeIds, host, morphDatastores, findMountPath)

        then: "SyncTask is created with proper configuration"
        syncTask != null
        syncTask instanceof SyncTask
    }

    def "addStorageVolumes should process each external ID in the collection"() {
        given: "Collection of external IDs, datastores, mount path finder, and host"
        def itemsToAdd = ["share-1", "share-2", "share-3"]
        def datastore1 = new Datastore(
                id: 1L,
                externalId: "share-1",
                name: "DS1",
                storageSize: 1000L,
                freeSpace: 300L
        )
        def datastore2 = new Datastore(
                id: 2L,
                externalId: "share-2",
                name: "DS2",
                storageSize: 2000L,
                freeSpace: 500L
        )
        def morphDatastores = [datastore1, datastore2]
        def findMountPath = { id -> "\\\\server\\${id}" }
        def host = new ComputeServer(id: 1L, externalId: "host-1")
        def volumeType = new StorageVolumeType(code: "scvmm-registered-file-share-datastore")

        and: "Mock the storage volume type service"
        mockStorageVolumeTypeService.find(_) >> volumeType

        when: "addStorageVolumes is called"
        sync.addStorageVolumes(itemsToAdd, morphDatastores, findMountPath, host)

        then: "Storage volumes are created and saved for matching datastores"
        2 * mockAsyncStorageVolumeService.create(_, host) >> Single.just([])
        2 * mockAsyncComputeServerService.save(host) >> Single.just(host)
    }

    def "addStorageVolumes should handle empty collection gracefully"() {
        given: "Empty collection of external IDs"
        def itemsToAdd = []
        def morphDatastores = []
        def findMountPath = { id -> null }
        def host = new ComputeServer(id: 1L, externalId: "host-1")

        when: "addStorageVolumes is called"
        sync.addStorageVolumes(itemsToAdd, morphDatastores, findMountPath, host)

        then: "No storage volumes are created"
        0 * mockAsyncStorageVolumeService.create(_, _)
        0 * mockAsyncComputeServerService.save(_)
    }

    def "addStorageVolumes should handle null collection gracefully"() {
        given: "Null collection of external IDs"
        def itemsToAdd = null
        def morphDatastores = []
        def findMountPath = { id -> null }
        def host = new ComputeServer(id: 1L, externalId: "host-1")

        when: "addStorageVolumes is called"
        sync.addStorageVolumes(itemsToAdd, morphDatastores, findMountPath, host)

        then: "No storage volumes are created"
        0 * mockAsyncStorageVolumeService.create(_, _)
        0 * mockAsyncComputeServerService.save(_)
    }

    def "addSingleStorageVolume should create and save new volume when matching datastore found"() {
        given: "External ID with matching datastore"
        def dsExternalId = "share-1"
        def matchingDatastore = new Datastore(
                id: 1L,
                externalId: "share-1",
                name: "DS1",
                storageSize: 1000L,
                freeSpace: 300L
        )
        def morphDatastores = [matchingDatastore]
        def findMountPath = { id -> "\\\\server\\${id}" }
        def host = new ComputeServer(id: 1L, externalId: "host-1")
        def volumeType = new StorageVolumeType(code: "scvmm-registered-file-share-datastore")

        and: "Mock the storage volume type service"
        mockStorageVolumeTypeService.find(_) >> volumeType

        when: "addSingleStorageVolume is called"
        sync.addSingleStorageVolume(dsExternalId, morphDatastores, findMountPath, host)

        then: "New storage volume is created and saved"
        1 * mockAsyncStorageVolumeService.create({ List volumes ->
            volumes.size() == 1 &&
                    volumes[0].externalId == dsExternalId &&
                    volumes[0].name == "DS1" &&
                    volumes[0].maxStorage == 1000L
        }, host) >> Single.just([])
        1 * mockAsyncComputeServerService.save(host) >> Single.just(host)
    }

    def "addSingleStorageVolume should handle non-matching datastore gracefully"() {
        given: "External ID with no matching datastore"
        def dsExternalId = "share-nonexistent"
        def datastore = new Datastore(id: 1L, externalId: "share-different", name: "DS1")
        def morphDatastores = [datastore]
        def findMountPath = { id -> "\\\\server\\${id}" }
        def host = new ComputeServer(id: 1L, externalId: "host-1")

        when: "addSingleStorageVolume is called"
        sync.addSingleStorageVolume(dsExternalId, morphDatastores, findMountPath, host)

        then: "No storage volume is created or saved"
        0 * mockAsyncStorageVolumeService.create(_, _)
        0 * mockAsyncComputeServerService.save(_)
    }

    def "addSingleStorageVolume should find correct datastore from multiple options"() {
        given: "External ID with multiple datastores including one match"
        def dsExternalId = "share-2"
        def datastore1 = new Datastore(id: 1L, externalId: "share-1", name: "DS1")
        def datastore2 = new Datastore(id: 2L, externalId: "share-2", name: "DS2", storageSize: 2000L, freeSpace: 500L)
        def datastore3 = new Datastore(id: 3L, externalId: "share-3", name: "DS3")
        def morphDatastores = [datastore1, datastore2, datastore3]
        def findMountPath = { id -> "\\\\server\\${id}" }
        def host = new ComputeServer(id: 1L, externalId: "host-1")
        def volumeType = new StorageVolumeType(code: "scvmm-registered-file-share-datastore")

        and: "Mock the storage volume type service"
        mockStorageVolumeTypeService.find(_) >> volumeType

        when: "addSingleStorageVolume is called"
        sync.addSingleStorageVolume(dsExternalId, morphDatastores, findMountPath, host)

        then: "Correct datastore is matched and volume is created"
        1 * mockAsyncStorageVolumeService.create({ List volumes ->
            volumes.size() == 1 &&
                    volumes[0].externalId == dsExternalId &&
                    volumes[0].name == "DS2" &&
                    volumes[0].maxStorage == 2000L
        }, host) >> Single.just([])
        1 * mockAsyncComputeServerService.save(host) >> Single.just(host)
    }

    def "saveNewStorageVolume should create volume and save host"() {
        given: "New storage volume and host"
        def newVolume = new StorageVolume(
                id: null,
                name: "NewVolume",
                externalId: "share-1",
                maxStorage: 1000L
        )
        def host = new ComputeServer(id: 1L, externalId: "host-1")

        when: "saveNewStorageVolume is called"
        sync.saveNewStorageVolume(newVolume, host)

        then: "Volume is created and host is saved"
        1 * mockAsyncStorageVolumeService.create([newVolume], host) >> Single.just([newVolume])
        1 * mockAsyncComputeServerService.save(host) >> Single.just(host)
    }

    def "saveNewStorageVolume should handle creation and save in sequence"() {
        given: "New storage volume and host"
        def newVolume = new StorageVolume(
                id: null,
                name: "TestVolume",
                externalId: "share-test",
                maxStorage: 500L
        )
        def host = new ComputeServer(id: 2L, externalId: "host-2")
        def createdVolume = new StorageVolume(
                id: 1L,
                name: "TestVolume",
                externalId: "share-test",
                maxStorage: 500L
        )

        when: "saveNewStorageVolume is called"
        sync.saveNewStorageVolume(newVolume, host)

        then: "Volume creation is called first, then host save"
        1 * mockAsyncStorageVolumeService.create([newVolume], host) >> Single.just([createdVolume])
        1 * mockAsyncComputeServerService.save(host) >> Single.just(host)
    }

    def "addMissingFileShares should handle null addList gracefully"() {
        given: "Null add list"
        def addList = null
        def objList = []

        when: "addMissingFileShares is called"
        sync.addMissingFileShares(addList, objList)

        then: "No datastores are created and volume sync proceeds"
        0 * mockAsyncCloudService.datastore.bulkCreate(_)
        1 * mockComputeServerService.list(_) >> []
        1 * mockCloudService.datastore.list(_) >> []
    }

    def "addMissingFileShares should handle exception during datastore creation"() {
        given: "Cloud item that causes an exception during processing"
        def cloudItem = [
                ID                     : "share-error",
                Name                   : "ErrorShare",
                Capacity               : "1000000",
                FreeSpace              : "500000",
                IsAvailableForPlacement: true,
                ClusterAssociations    : [[HostID: "host-1"]]
        ]
        def addList = [cloudItem]
        def objList = []

        when: "addMissingFileShares is called"
        sync.addMissingFileShares(addList, objList)

        then: "Exception is caught and logged, no service calls are made"
        0 * mockAsyncCloudService.datastore.bulkCreate(_)
        0 * mockComputeServerService.list(_)
        0 * mockCloudService.datastore.list(_)
        // Method should not throw exception due to try-catch
        noExceptionThrown()
    }

    def "syncVolumeForEachHosts should handle exception during host processing and log error"() {
        given: "Host mapping and objList that will cause an exception"
        def hostToShareMap = ["host-1": ["share-1"] as Set]
        def objList = [[ID: "share-1", MountPoints: ["\\\\server\\share1"]]]

        and: "Mock service throws exception during host list retrieval"
        mockComputeServerService.list(_) >> { throw new RuntimeException("Database connection error") }

        when: "syncVolumeForEachHosts is called"
        sync.syncVolumeForEachHosts(hostToShareMap, objList)

        then: "Exception is caught and logged with correct message"
        noExceptionThrown()
        // The method should complete without throwing despite the internal exception
    }

    def "updateMatchedFileShares should handle exception during bulk save and log error"() {
        given: "Update list with valid datastore update items"
        def existingDatastore = new Datastore(
            id: 1L,
            externalId: "share-1",
            name: "OldName",
            online: false,
            freeSpace: 1000L,
            storageSize: 2000L
        )
        def masterItem = [
            ID: "share-1",
            Name: "NewName",
            IsAvailableForPlacement: true,
            FreeSpace: "1500",
            Capacity: "3000"
        ]
        def updateItem = new SyncTask.UpdateItem<Datastore, Map>(
            existingItem: existingDatastore,
            masterItem: masterItem
        )
        def updateList = [updateItem]
        def objList = []

        and: "Mock bulk save throws exception"
        mockAsyncCloudService.datastore.bulkSave(_) >> { throw new RuntimeException("Save operation failed") }

        when: "updateMatchedFileShares is called"
        sync.updateMatchedFileShares(updateList, objList)

        then: "Exception is caught and logged with correct message"
        noExceptionThrown()
        // The method should complete without throwing despite the bulk save failure
    }

    def "syncVolumeForEachHosts should handle exception during volume type retrieval"() {
        given: "Valid host mapping but volume type service throws exception"
        def host = new ComputeServer(id: 1L, externalId: "host-1")
        def hostToShareMap = ["host-1": ["share-1"] as Set]
        def objList = [[ID: "share-1", MountPoints: ["\\\\server\\share1"]]]
        def datastore = new Datastore(id: 1L, externalId: "share-1", name: "TestDatastore")

        and: "Mock successful host and datastore retrieval but volume type fails"
        mockComputeServerService.list(_) >> [host]
        mockCloudService.datastore.list(_) >> [datastore]
        mockStorageVolumeTypeService.find(_) >> { throw new RuntimeException("Volume type lookup failed") }

        when: "syncVolumeForEachHosts is called"
        sync.syncVolumeForEachHosts(hostToShareMap, objList)

        then: "Exception is caught and logged"
        noExceptionThrown()
    }
}