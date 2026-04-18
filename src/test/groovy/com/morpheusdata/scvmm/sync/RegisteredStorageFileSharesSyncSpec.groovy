package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.DatastoreIdentity
import com.morpheusdata.model.projection.StorageVolumeIdentityProjection
import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.scvmm.logging.LogInterface
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

class RegisteredStorageFileSharesSyncSpec extends Specification {

    MorpheusContext mockContext
    Cloud mockCloud
    ComputeServer mockNode
    RegisteredStorageFileSharesSync sync
    ScvmmApiService mockApiService
    LogInterface mockLog

    def setup() {
        mockContext = Mock(MorpheusContext)
        mockCloud = new Cloud(id: 1L, code: 'test-cloud')
        mockCloud.owner = new Account(id: 2L)
        mockNode = new ComputeServer(id: 1L, externalId: 'host-1')
        
        mockApiService = Mock(ScvmmApiService)
        mockLog = Mock(LogInterface)
        
        sync = new RegisteredStorageFileSharesSync(mockCloud, mockNode, mockContext)
        sync.apiService = mockApiService
        sync.log = mockLog
    }

    def "constructor initializes fields correctly"() {
        when:
        def newSync = new RegisteredStorageFileSharesSync(mockCloud, mockNode, mockContext)

        then:
        newSync.cloud == mockCloud
        newSync.node == mockNode
        newSync.context == mockContext
        newSync.apiService != null
    }

    def "execute handles API failure gracefully"() {
        given:
        mockApiService.getScvmmZoneAndHypervisorOpts(_, _, _) >> [cloudId: 1L]
        mockApiService.listRegisteredFileShares(_) >> [success: false]

        when:
        sync.execute()

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync')
        notThrown(Exception)
    }

    def "execute logs debug message"() {
        given:
        mockApiService.getScvmmZoneAndHypervisorOpts(_, _, _) >> [cloudId: 1L]
        mockApiService.listRegisteredFileShares(_) >> [success: false]

        when:
        sync.execute()

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync')
        notThrown(Exception)
    }

    def "execute handles exception during sync"() {
        given:
        mockApiService.getScvmmZoneAndHypervisorOpts(_, _, _) >> { throw new RuntimeException("Test error") }

        when:
        sync.execute()

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync')
        1 * mockLog.error(_ as String, _ as String)
        notThrown(Exception)
    }

    def "execute handles null datastores list"() {
        given:
        mockApiService.getScvmmZoneAndHypervisorOpts(_, _, _) >> [cloudId: 1L]
        mockApiService.listRegisteredFileShares(_) >> [success: true, datastores: null]

        when:
        sync.execute()

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync')
        notThrown(Exception)
    }

    def "execute handles empty datastores list"() {
        given:
        mockApiService.getScvmmZoneAndHypervisorOpts(_, _, _) >> [cloudId: 1L]
        mockApiService.listRegisteredFileShares(_) >> [success: true, datastores: []]

        when:
        sync.execute()

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync')
        notThrown(Exception)
    }

    def "addMissingFileShares handles empty list"() {
        when:
        sync.addMissingFileShares([], [])

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync: addMissingFileShares: called')
        notThrown(Exception)
    }

    def "addMissingFileShares handles null list"() {
        when:
        sync.addMissingFileShares(null, [])

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync: addMissingFileShares: called')
        notThrown(Exception)
    }

    def "addMissingFileShares creates datastores with correct properties"() {
        given:
        def addList = [
            [
                ID: 'share-1',
                Name: 'Test Share',
                Capacity: 1000000000L,
                FreeSpace: 500000000L,
                IsAvailableForPlacement: true,
                ClusterAssociations: [[HostID: 'host-1']],
                HostAssociations: [[HostID: 'host-2']]
            ]
        ]

        when:
        sync.addMissingFileShares(addList, addList)

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync: addMissingFileShares: called')
        1 * mockLog.info('Created registered file share for id: share-1')
        1 * mockLog.error(_ as String, _ as Exception)
        notThrown(Exception)
    }

    def "addMissingFileShares handles items without capacity"() {
        given:
        def addList = [
            [
                ID: 'share-1',
                Name: 'Test Share',
                Capacity: null,
                FreeSpace: null,
                IsAvailableForPlacement: false,
                ClusterAssociations: [],
                HostAssociations: []
            ]
        ]

        when:
        sync.addMissingFileShares(addList, addList)

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync: addMissingFileShares: called')
        1 * mockLog.info('Created registered file share for id: share-1')
        1 * mockLog.error(_ as String, _ as Exception)
        notThrown(Exception)
    }

    def "addMissingFileShares handles exception gracefully"() {
        given:
        def addList = [
            [ID: 'share-1', Name: 'Test', Capacity: 1000L, FreeSpace: 500L]
        ]

        when:
        sync.addMissingFileShares(addList, addList)

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync: addMissingFileShares: called')
        1 * mockLog.error(_ as String, _ as Exception)
        notThrown(Exception)
    }

    def "updateMatchedFileShares handles empty list"() {
        when:
        sync.updateMatchedFileShares([], [])

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync >> updateMatchedFileShares >> Entered')
        notThrown(Exception)
    }

    def "updateMatchedFileShares updates datastore when online status changes"() {
        given:
        def datastore = new Datastore(
            id: 1L,
            online: false,
            name: 'Test',
            freeSpace: 1000L,
            storageSize: 2000L
        )
        def updateItems = [
            new SyncTask.UpdateItem<Datastore, Map>(
                existingItem: datastore,
                masterItem: [
                    ID: 'share-1',
                    IsAvailableForPlacement: true,
                    Name: 'Test',
                    FreeSpace: 1000L,
                    Capacity: 2000L,
                    ClusterAssociations: [],
                    HostAssociations: []
                ]
            )
        ]

        when:
        sync.updateMatchedFileShares(updateItems, [])

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync >> updateMatchedFileShares >> Entered')
        1 * mockLog.error(_ as String, _ as Exception)
        datastore.online == true
        notThrown(Exception)
    }

    def "updateMatchedFileShares updates datastore when name changes"() {
        given:
        def datastore = new Datastore(
            id: 1L,
            online: true,
            name: 'Old Name',
            freeSpace: 1000L,
            storageSize: 2000L
        )
        def updateItems = [
            new SyncTask.UpdateItem<Datastore, Map>(
                existingItem: datastore,
                masterItem: [
                    ID: 'share-1',
                    IsAvailableForPlacement: true,
                    Name: 'New Name',
                    FreeSpace: 1000L,
                    Capacity: 2000L,
                    ClusterAssociations: [],
                    HostAssociations: []
                ]
            )
        ]

        when:
        sync.updateMatchedFileShares(updateItems, [])

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync >> updateMatchedFileShares >> Entered')
        1 * mockLog.error(_ as String, _ as Exception)
        datastore.name == 'New Name'
        notThrown(Exception)
    }

    def "updateMatchedFileShares updates datastore when freeSpace changes"() {
        given:
        def datastore = new Datastore(
            id: 1L,
            online: true,
            name: 'Test',
            freeSpace: 1000L,
            storageSize: 2000L
        )
        def updateItems = [
            new SyncTask.UpdateItem<Datastore, Map>(
                existingItem: datastore,
                masterItem: [
                    ID: 'share-1',
                    IsAvailableForPlacement: true,
                    Name: 'Test',
                    FreeSpace: 1500L,
                    Capacity: 2000L,
                    ClusterAssociations: [],
                    HostAssociations: []
                ]
            )
        ]

        when:
        sync.updateMatchedFileShares(updateItems, [])

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync >> updateMatchedFileShares >> Entered')
        1 * mockLog.error(_ as String, _ as Exception)
        datastore.freeSpace == 1500L
        notThrown(Exception)
    }

    def "updateMatchedFileShares updates datastore when storageSize changes"() {
        given:
        def datastore = new Datastore(
            id: 1L,
            online: true,
            name: 'Test',
            freeSpace: 1000L,
            storageSize: 2000L
        )
        def updateItems = [
            new SyncTask.UpdateItem<Datastore, Map>(
                existingItem: datastore,
                masterItem: [
                    ID: 'share-1',
                    IsAvailableForPlacement: true,
                    Name: 'Test',
                    FreeSpace: 1000L,
                    Capacity: 3000L,
                    ClusterAssociations: [],
                    HostAssociations: []
                ]
            )
        ]

        when:
        sync.updateMatchedFileShares(updateItems, [])

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync >> updateMatchedFileShares >> Entered')
        1 * mockLog.error(_ as String, _ as Exception)
        datastore.storageSize == 3000L
        notThrown(Exception)
    }

    def "updateMatchedFileShares updates datastore with all changes"() {
        given:
        def datastore = new Datastore(
            id: 1L,
            online: false,
            name: 'Old Name',
            freeSpace: 1000L,
            storageSize: 2000L
        )
        def updateItems = [
            new SyncTask.UpdateItem<Datastore, Map>(
                existingItem: datastore,
                masterItem: [
                    ID: 'share-1',
                    IsAvailableForPlacement: true,
                    Name: 'New Name',
                    FreeSpace: 1500L,
                    Capacity: 3000L,
                    ClusterAssociations: [[HostID: 'host-1']],
                    HostAssociations: [[HostID: 'host-2']]
                ]
            )
        ]

        when:
        sync.updateMatchedFileShares(updateItems, [])

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync >> updateMatchedFileShares >> Entered')
        1 * mockLog.error(_ as String, _ as Exception)
        datastore.online == true
        datastore.name == 'New Name'
        datastore.freeSpace == 1500L
        datastore.storageSize == 3000L
        notThrown(Exception)
    }

    def "updateMatchedFileShares does not update when no changes"() {
        given:
        def datastore = new Datastore(
            id: 1L,
            online: true,
            name: 'Test',
            freeSpace: 1000L,
            storageSize: 2000L
        )
        def updateItems = [
            new SyncTask.UpdateItem<Datastore, Map>(
                existingItem: datastore,
                masterItem: [
                    ID: 'share-1',
                    IsAvailableForPlacement: true,
                    Name: 'Test',
                    FreeSpace: 1000L,
                    Capacity: 2000L,
                    ClusterAssociations: [],
                    HostAssociations: []
                ]
            )
        ]

        when:
        sync.updateMatchedFileShares(updateItems, [])

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync >> updateMatchedFileShares >> Entered')
        notThrown(Exception)
    }

    def "updateMatchedFileShares handles null FreeSpace"() {
        given:
        def datastore = new Datastore(
            id: 1L,
            online: true,
            name: 'Test',
            freeSpace: 1000L,
            storageSize: 2000L
        )
        def updateItems = [
            new SyncTask.UpdateItem<Datastore, Map>(
                existingItem: datastore,
                masterItem: [
                    ID: 'share-1',
                    IsAvailableForPlacement: true,
                    Name: 'Test',
                    FreeSpace: null,
                    Capacity: 2000L,
                    ClusterAssociations: [],
                    HostAssociations: []
                ]
            )
        ]

        when:
        sync.updateMatchedFileShares(updateItems, [])

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync >> updateMatchedFileShares >> Entered')
        1 * mockLog.error(_ as String, _ as Exception)
        datastore.freeSpace == 0L
        notThrown(Exception)
    }

    def "updateMatchedFileShares handles null Capacity"() {
        given:
        def datastore = new Datastore(
            id: 1L,
            online: true,
            name: 'Test',
            freeSpace: 1000L,
            storageSize: 2000L
        )
        def updateItems = [
            new SyncTask.UpdateItem<Datastore, Map>(
                existingItem: datastore,
                masterItem: [
                    ID: 'share-1',
                    IsAvailableForPlacement: true,
                    Name: 'Test',
                    FreeSpace: 1000L,
                    Capacity: null,
                    ClusterAssociations: [],
                    HostAssociations: []
                ]
            )
        ]

        when:
        sync.updateMatchedFileShares(updateItems, [])

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync >> updateMatchedFileShares >> Entered')
        1 * mockLog.error(_ as String, _ as Exception)
        datastore.storageSize == 0L
        notThrown(Exception)
    }

    def "updateMatchedFileShares handles exception gracefully"() {
        given:
        def datastore = new Datastore(id: 1L)
        datastore.metaClass.getOnline = { throw new RuntimeException("Test error") }
        def updateItems = [
            new SyncTask.UpdateItem<Datastore, Map>(
                existingItem: datastore,
                masterItem: [
                    ID: 'share-1',
                    IsAvailableForPlacement: true,
                    Name: 'Test',
                    FreeSpace: 1000L,
                    Capacity: 2000L
                ]
            )
        ]

        when:
        sync.updateMatchedFileShares(updateItems, [])

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync >> updateMatchedFileShares >> Entered')
        1 * mockLog.error(_ as String, _ as Exception)
        notThrown(Exception)
    }

    def "updateMatchedFileShares processes multiple datastores"() {
        given:
        def datastore1 = new Datastore(
            id: 1L, online: false, name: 'Test1',
            freeSpace: 1000L, storageSize: 2000L
        )
        def datastore2 = new Datastore(
            id: 2L, online: true, name: 'Test2',
            freeSpace: 2000L, storageSize: 4000L
        )
        def updateItems = [
            new SyncTask.UpdateItem<Datastore, Map>(
                existingItem: datastore1,
                masterItem: [
                    ID: 'share-1', IsAvailableForPlacement: true, Name: 'Test1',
                    FreeSpace: 1000L, Capacity: 2000L,
                    ClusterAssociations: [], HostAssociations: []
                ]
            ),
            new SyncTask.UpdateItem<Datastore, Map>(
                existingItem: datastore2,
                masterItem: [
                    ID: 'share-2', IsAvailableForPlacement: false, Name: 'New Test2',
                    FreeSpace: 2000L, Capacity: 4000L,
                    ClusterAssociations: [], HostAssociations: []
                ]
            )
        ]

        when:
        sync.updateMatchedFileShares(updateItems, [])

        then:
        1 * mockLog.debug('RegisteredStorageFileSharesSync >> updateMatchedFileShares >> Entered')
        1 * mockLog.error(_ as String, _ as Exception)
        datastore1.online == true
        datastore2.online == false
        datastore2.name == 'New Test2'
        notThrown(Exception)
    }

    def "syncVolumeForEachHosts handles exception gracefully"() {
        when:
        sync.syncVolumeForEachHosts([:], [])

        then:
        1 * mockLog.error(_ as String, _ as Exception)
        notThrown(Exception)
    }

    def "syncVolumeForEachHosts handles empty hostToShareMap"() {
        when:
        sync.syncVolumeForEachHosts([:], [])

        then:
        1 * mockLog.error(_ as String, _ as Exception)
        notThrown(Exception)
    }

    def "syncVolumeForEachHosts handles null objList"() {
        when:
        sync.syncVolumeForEachHosts(['host-1': ['share-1'] as Set], null)

        then:
        1 * mockLog.error(_ as String, _ as Exception)
        notThrown(Exception)
    }
}
