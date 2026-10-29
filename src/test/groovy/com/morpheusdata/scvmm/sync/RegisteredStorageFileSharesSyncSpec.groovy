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
}
