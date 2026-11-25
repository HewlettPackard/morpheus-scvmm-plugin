// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.MorpheusStorageVolumeService
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.cloud.MorpheusDatastoreService
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.projection.DatastoreIdentity
import com.morpheusdata.model.ComputeServerType
import io.reactivex.rxjava3.core.Maybe
import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.scvmm.support.VmmPayloads
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Subject

/**
 * Registered file shares are scoped to the clusters they are associated with in SCVMM, either directly via
 * {@code ClusterAssociations} or through the hosts they are mounted on, so that selecting a resource pool during
 * provisioning only offers shares that cluster can actually use.
 */
class RegisteredStorageFileSharesSyncSpec extends Specification {

    Cloud cloud = new Cloud(id: 1L)
    MorpheusContext context = Mock()

    @Subject
    RegisteredStorageFileSharesSync sync = new RegisteredStorageFileSharesSync(cloud, null, context)

    CloudPool clusterA = new CloudPool(id: 10L, externalId: 'cluster-a-guid')
    CloudPool clusterB = new CloudPool(id: 20L, externalId: 'cluster-b-guid')
    List<CloudPool> clusters = [clusterA, clusterB]

    ComputeServer hostA = host('host-a-guid', clusterA)
    ComputeServer hostB = host('host-b-guid', clusterB)
    ComputeServer standalone = host('host-s-guid', null)
    List<ComputeServer> hosts = [hostA, hostB, standalone]

    def "a share associated with a cluster is assigned to that cluster"() {
        given:
        def share = [ClusterAssociations: [[ClusterID: 'cluster-a-guid']]]

        expect:
        sync.findOwningPools(share, clusters, hosts)*.id == [clusterA.id]
    }

    def "a share mounted on a host is assigned to the host's cluster"() {
        given:
        def share = [HostAssociations: [[HostID: 'host-b-guid']]]

        expect:
        sync.findOwningPools(share, clusters, hosts)*.id == [clusterB.id]
    }

    def "host ids inside cluster associations are honoured as well"() {
        given:
        def share = [ClusterAssociations: [[HostID: 'host-a-guid']]]

        expect:
        sync.findOwningPools(share, clusters, hosts)*.id == [clusterA.id]
    }

    def "cluster and host associations are combined and de-duplicated"() {
        given:
        def share = [
                ClusterAssociations: [[ClusterID: 'cluster-a-guid', HostID: 'host-a-guid']],
                HostAssociations   : [[HostID: 'host-a-guid'], [HostID: 'host-b-guid']]
        ]

        when:
        def pools = sync.findOwningPools(share, clusters, hosts)

        then:
        pools*.id as Set == [clusterA.id, clusterB.id] as Set
        pools.size() == 2
    }

    def "a share on a standalone host or an unknown cluster is assigned to no pool"() {
        expect:
        sync.findOwningPools([HostAssociations: [[HostID: 'host-s-guid']]], clusters, hosts).isEmpty()
        sync.findOwningPools([ClusterAssociations: [[ClusterID: 'unknown']]], clusters, hosts).isEmpty()
        sync.findOwningPools([:], clusters, hosts).isEmpty()
    }

    def "null association entries are tolerated"() {
        given:
        def share = [ClusterAssociations: [[ClusterID: null, HostID: null]], HostAssociations: [[:]]]

        expect:
        sync.findOwningPools(share, clusters, hosts).isEmpty()
    }

    def "owning pools are lightweight references carrying only the id"() {
        when:
        def pools = sync.findOwningPools([ClusterAssociations: [[ClusterID: 'cluster-a-guid']]], clusters, hosts)

        then:
        pools[0].id == clusterA.id
        pools[0].externalId == null
    }

    private static ComputeServer host(String externalId, CloudPool pool) {
        def server = new ComputeServer(externalId: externalId, hostname: externalId)
        server.resourcePool = pool
        return server
    }

    def "a share with a cluster association is typed as cluster storage"() {
        expect:
        RegisteredStorageFileSharesSync.externalTypeFor([ClusterAssociations: [[ClusterID: 'cluster-a-guid']]]) == RegisteredStorageFileSharesSync.EXTERNAL_TYPE_CLUSTER_FILE_SHARE
        RegisteredStorageFileSharesSync.externalTypeFor([ClusterAssociations: [[ClusterID: 'cluster-a-guid', HostID: 'host-a1-guid']], HostAssociations: []]) == RegisteredStorageFileSharesSync.EXTERNAL_TYPE_CLUSTER_FILE_SHARE
    }

    def "a share only registered to hosts is typed as host storage"() {
        expect:
        RegisteredStorageFileSharesSync.externalTypeFor([HostAssociations: [[HostID: 'host-s-guid']]]) == RegisteredStorageFileSharesSync.EXTERNAL_TYPE_HOST_FILE_SHARE
        RegisteredStorageFileSharesSync.externalTypeFor([ClusterAssociations: [[ClusterID: null]]]) == RegisteredStorageFileSharesSync.EXTERNAL_TYPE_HOST_FILE_SHARE
        RegisteredStorageFileSharesSync.externalTypeFor([:]) == RegisteredStorageFileSharesSync.EXTERNAL_TYPE_HOST_FILE_SHARE
        RegisteredStorageFileSharesSync.externalTypeFor(null) == RegisteredStorageFileSharesSync.EXTERNAL_TYPE_HOST_FILE_SHARE
    }

    // ---------------------------------------------------------------- execute wiring

    MorpheusServices services = Mock()
    MorpheusAsyncServices asyncServices = Mock()
    MorpheusSynchronousCloudService syncCloud = Mock()
    MorpheusCloudService asyncCloud = Mock()
    MorpheusSynchronousCloudPoolService poolService = Mock()
    MorpheusSynchronousDatastoreService datastoreService = Mock()
    MorpheusDatastoreService asyncDatastoreService = Mock()
    MorpheusSynchronousComputeServerService computeServerService = Mock()
    MorpheusSynchronousStorageVolumeService storageVolumeService = Mock()
    MorpheusSynchronousStorageVolumeTypeService volumeTypeService = Mock()
    MorpheusStorageVolumeService asyncStorageVolumeService = Mock()
    ScvmmApiService apiService = Mock()

    private void wire(def target) {
        context.services >> services
        context.async >> asyncServices
        services.cloud >> syncCloud
        asyncServices.cloud >> asyncCloud
        syncCloud.pool >> poolService
        syncCloud.datastore >> datastoreService
        asyncCloud.datastore >> asyncDatastoreService
        services.computeServer >> computeServerService
        services.storageVolume >> storageVolumeService
        storageVolumeService.storageVolumeType >> volumeTypeService
        asyncServices.storageVolume >> asyncStorageVolumeService
        target.@apiService = apiService
    }

    StorageVolumeType shareVolumeType = new StorageVolumeType(id: 8L, code: 'scvmm-registered-file-share-datastore')

    private void common(List shares, List<ComputeServer> dbHosts) {
        wire(sync)
        poolService.list(_ as DataQuery) >> clusters
        asyncCloud.findComputeServerTypeByCode(_) >> Maybe.just(new ComputeServerType())
        computeServerService.list(_ as DataQuery) >> dbHosts
        volumeTypeService.find(_ as DataQuery) >> shareVolumeType
        apiService.listRegisteredFileShares(_) >> [success: true, datastores: shares]
    }

    def "execute creates the share datastore and a volume on each associated host"() {
        given:
        def h = new ComputeServer(id: 30L, externalId: 'host-a-guid', volumes: [])
        common([VmmPayloads.fileShare(Capacity: 100L, FreeSpace: null, HostAssociations: [[HostID: 'host-a-guid']])], [h])
        computeServerService.get(30L) >> h
        asyncDatastoreService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
        def persisted = new Datastore(id: 60L, externalId: 'share-1', name: 'share-1', storageSize: 100L, freeSpace: 0L)
        datastoreService.list(_ as DataQuery) >> [persisted]
        List<Datastore> created
        StorageVolume volume
        def asyncComputeServers = Mock(com.morpheusdata.core.MorpheusComputeServerService)
        asyncServices.computeServer >> asyncComputeServers

        when:
        sync.execute()

        then:
        1 * asyncDatastoreService.bulkCreate(_ as List) >> { args -> created = args[0]; Single.just(true) }
        1 * asyncStorageVolumeService.create(_ as List, h) >> { List l, ComputeServer s -> volume = l[0]; Single.just(true) }
        1 * asyncComputeServers.save(h) >> Single.just(h)
        created[0].externalId == 'share-1'
        created[0].storageSize == 100L
        created[0].freeSpace == 0L
        created[0].category == 'scvmm.registered.file.share.datastore.1'
        created[0].externalType == RegisteredStorageFileSharesSync.EXTERNAL_TYPE_HOST_FILE_SHARE
        volume.externalId == 'share-1'
        volume.volumePath == '\\\\fs\\share-1'
        volume.datastore.is(persisted)
        volume.type.is(shareVolumeType)
    }

    def "execute saves changed share size/free space and removes shares VMM no longer reports"() {
        given:
        common([VmmPayloads.fileShare(Capacity: 200L, FreeSpace: 20L)], [])
        def ds = new Datastore(id: 60L, externalId: 'share-1', name: 'share-1', storageSize: 100L, freeSpace: 10L, online: true,
                externalType: RegisteredStorageFileSharesSync.EXTERNAL_TYPE_HOST_FILE_SHARE, assignedZonePools: [])
        def gone = new DatastoreIdentity(id: 61L, externalId: 'gone')
        asyncDatastoreService.listIdentityProjections(_ as DataQuery) >> Observable.just(new DatastoreIdentity(id: 60L, externalId: 'share-1'), gone)
        asyncDatastoreService.listById([60L]) >> Observable.just(ds)
        datastoreService.list(_ as DataQuery) >> []

        when:
        sync.execute()

        then:
        1 * asyncDatastoreService.bulkSave([ds]) >> Single.just(true)
        1 * asyncDatastoreService.remove([gone]) >> Single.just(true)
        ds.storageSize == 200L
        ds.freeSpace == 20L
    }

    def "execute does nothing when listRegisteredFileShares #why"() {
        given:
        wire(sync)
        poolService.list(_ as DataQuery) >> []
        apiService.listRegisteredFileShares(_) >> result

        when:
        sync.execute()

        then:
        0 * asyncDatastoreService._

        where:
        why        | result
        'fails'    | [success: false, datastores: []]
        'is empty' | [success: true, datastores: []]
    }

    def "a second sync updates the host's existing share volume instead of creating a duplicate"() {
        given: 'the volume type loaded on the host is a different instance from the one looked up by code'
        def existing = new StorageVolume(id: 5L, externalId: 'share-1', name: 'old', maxStorage: 1L,
                type: new StorageVolumeType(id: shareVolumeType.id, code: shareVolumeType.code))
        def h = new ComputeServer(id: 30L, externalId: 'host-a-guid', volumes: [existing])
        common([VmmPayloads.fileShare(HostAssociations: [[HostID: 'host-a-guid']])], [h])
        computeServerService.get(30L) >> h
        def ds = new Datastore(id: 60L, externalId: 'share-1', name: 'share-1', storageSize: 107374182400L, freeSpace: 53687091200L,
                online: true, externalType: RegisteredStorageFileSharesSync.EXTERNAL_TYPE_HOST_FILE_SHARE, assignedZonePools: [])
        asyncDatastoreService.listIdentityProjections(_ as DataQuery) >> Observable.just(new DatastoreIdentity(id: 60L, externalId: 'share-1'))
        asyncDatastoreService.listById([60L]) >> Observable.just(ds)
        datastoreService.list(_ as DataQuery) >> [ds]
        asyncStorageVolumeService.listById([5L]) >> Observable.just(existing)

        when:
        sync.execute()

        then:
        0 * asyncStorageVolumeService.create(_, _)
        0 * asyncStorageVolumeService.remove(*_)
        1 * asyncStorageVolumeService.save(existing) >> Single.just(existing)
        existing.name == 'share-1'
        existing.maxStorage == 107374182400L
        existing.usedStorage == 53687091200L
        existing.volumePath == '\\\\fs\\share-1'
        existing.datastore.is(ds)
    }

    def "a host without registered shares has its stale share volume removed and does not stop the other hosts"() {
        given: 'host A is synced first and no longer has any share, host B gets a new one'
        def stale = new StorageVolume(id: 6L, externalId: 'old-share', type: new StorageVolumeType(id: shareVolumeType.id))
        def other = new StorageVolume(id: 7L, externalId: 'not-a-share', type: new StorageVolumeType(id: 99L))
        def a = new ComputeServer(id: 30L, externalId: 'host-a-guid', volumes: [stale, other])
        def b = new ComputeServer(id: 31L, externalId: 'host-b-guid', volumes: [])
        common([VmmPayloads.fileShare(HostAssociations: [[HostID: 'host-b-guid']])], [a, b])
        computeServerService.get(30L) >> a
        computeServerService.get(31L) >> b
        asyncDatastoreService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
        asyncDatastoreService.bulkCreate(_ as List) >> Single.just(true)
        def persisted = new Datastore(id: 60L, externalId: 'share-1', name: 'share-1', storageSize: 100L, freeSpace: 40L)
        datastoreService.list(_ as DataQuery) >> [persisted]
        def asyncComputeServers = Mock(com.morpheusdata.core.MorpheusComputeServerService)
        asyncServices.computeServer >> asyncComputeServers
        StorageVolume created

        when:
        sync.execute()

        then:
        1 * asyncStorageVolumeService.save(stale) >> Single.just(stale)
        1 * asyncStorageVolumeService.remove([stale], a, true) >> Single.just(true)
        0 * asyncStorageVolumeService.remove([other], _, _)
        1 * asyncStorageVolumeService.create(_ as List, b) >> { List l, ComputeServer s -> created = l[0]; Single.just(true) }
        1 * asyncComputeServers.save(b) >> Single.just(b)
        created.externalId == 'share-1'
        created.usedStorage == 60L
        created.datastore.is(persisted)
    }

    def "adding one share and updating another in the same sync keeps the volumes of both on the host"() {
        given:
        def existing = new StorageVolume(id: 5L, externalId: 'share-1', name: 'share-1', maxStorage: 100L, usedStorage: 60L,
                volumePath: '\\\\fs\\share-1', type: new StorageVolumeType(id: shareVolumeType.id))
        def h = new ComputeServer(id: 30L, externalId: 'host-a-guid', volumes: [existing])
        common([
                VmmPayloads.fileShare(ID: 'share-1', Name: 'share-1', Capacity: 100L, FreeSpace: 40L, HostAssociations: [[HostID: 'host-a-guid']]),
                VmmPayloads.fileShare(ID: 'share-2', Name: 'share-2', Capacity: 200L, FreeSpace: 50L, MountPoints: ['\\\\fs\\share-2'],
                        HostAssociations: [[HostID: 'host-a-guid']])], [h])
        computeServerService.get(30L) >> h
        def ds1 = new Datastore(id: 60L, externalId: 'share-1', name: 'share-1', storageSize: 100L, freeSpace: 40L, online: true,
                externalType: RegisteredStorageFileSharesSync.EXTERNAL_TYPE_HOST_FILE_SHARE, assignedZonePools: [])
        existing.datastore = ds1
        def ds2 = new Datastore(id: 61L, externalId: 'share-2', name: 'share-2', storageSize: 200L, freeSpace: 50L)
        asyncDatastoreService.listIdentityProjections(_ as DataQuery) >> Observable.just(new DatastoreIdentity(id: 60L, externalId: 'share-1'))
        asyncDatastoreService.listById([60L]) >> Observable.just(ds1)
        asyncDatastoreService.bulkCreate(_ as List) >> Single.just(true)
        datastoreService.list(_ as DataQuery) >> [ds1, ds2]
        asyncStorageVolumeService.listById([5L]) >> Observable.just(existing)
        def asyncComputeServers = Mock(com.morpheusdata.core.MorpheusComputeServerService)
        asyncServices.computeServer >> asyncComputeServers
        StorageVolume created

        when:
        sync.execute()

        then:
        0 * asyncStorageVolumeService.remove(*_)
        0 * asyncStorageVolumeService.save(_)
        1 * asyncStorageVolumeService.create(_ as List, h) >> { List l, ComputeServer s -> created = l[0]; Single.just(true) }
        1 * asyncComputeServers.save(h) >> Single.just(h)
        created.externalId == 'share-2'
        created.volumePath == '\\\\fs\\share-2'
    }

    def "isFileShareVolume matches the volume type by id or code, not by instance"() {
        expect:
        RegisteredStorageFileSharesSync.isFileShareVolume(new StorageVolume(type: new StorageVolumeType(id: 8L)), shareVolumeType)
        RegisteredStorageFileSharesSync.isFileShareVolume(new StorageVolume(type: new StorageVolumeType(code: shareVolumeType.code)), new StorageVolumeType(code: shareVolumeType.code))
        !RegisteredStorageFileSharesSync.isFileShareVolume(new StorageVolume(type: new StorageVolumeType(id: 7L, code: 'scvmm-datastore')), shareVolumeType)
        !RegisteredStorageFileSharesSync.isFileShareVolume(new StorageVolume(), shareVolumeType)
        !RegisteredStorageFileSharesSync.isFileShareVolume(new StorageVolume(type: new StorageVolumeType(id: 8L)), null)
    }

    def "buildHostToShareMap collects the shares reachable from each host through cluster or host associations"() {
        expect:
        RegisteredStorageFileSharesSync.buildHostToShareMap([
                [ID: 's1', ClusterAssociations: [[ClusterID: 'c1', HostID: 'h1'], [ClusterID: 'c1', HostID: 'h2']]],
                [ID: 's2', HostAssociations: [[HostID: 'h1'], [HostID: null]]],
                [ID: null, HostAssociations: [[HostID: 'h3']]]
        ]) == [h1: ['s1', 's2'] as Set, h2: ['s1'] as Set]
    }
}
