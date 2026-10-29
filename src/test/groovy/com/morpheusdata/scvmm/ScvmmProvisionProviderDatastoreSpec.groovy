// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.scvmm.sync.DatastoresSync
import com.morpheusdata.scvmm.sync.RegisteredStorageFileSharesSync
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * Covers the provisioning-time decisions that depend on datastore pool scoping: which datastore is picked
 * automatically for a selected cluster, how the cluster is resolved, and whether the VM is created Highly
 * Available.
 */
class ScvmmProvisionProviderDatastoreSpec extends Specification {

    MorpheusContext context = Mock()
    MorpheusServices services = Mock()
    MorpheusSynchronousCloudService cloudService = Mock()
    MorpheusSynchronousCloudPoolService poolService = Mock()
    MorpheusSynchronousDatastoreService datastoreService = Mock()
    MorpheusSynchronousComputeServerService computeServerService = Mock()
    MorpheusSynchronousResourcePermissionService permissionService = Mock()
    MorpheusSynchronousStorageVolumeService storageVolumeService = Mock()
    ScvmmPlugin plugin = Mock()

    @Subject
    ScvmmProvisionProvider provider = new ScvmmProvisionProvider(plugin, context)

    Cloud cloud = new Cloud(id: 1L, regionCode: null)
    Account account = new Account(id: 1L)
    CloudPool clusterA = new CloudPool(id: 10L, externalId: 'cluster-a-guid', name: 'cluster-a')
    CloudPool clusterB = new CloudPool(id: 20L, externalId: 'cluster-b-guid', name: 'cluster-b')

    def setup() {
        context.services >> services
        services.cloud >> cloudService
        cloudService.pool >> poolService
        cloudService.datastore >> datastoreService
        services.computeServer >> computeServerService
        services.resourcePermission >> permissionService
        services.storageVolume >> storageVolumeService
        permissionService.listAccessibleResources(*_) >> []
        storageVolumeService.find(_) >> null
    }

    // ----------------------------------------------------------------------------------------------------------
    // isClusteredSharedVolume (drives the Highly Available flag)
    // ----------------------------------------------------------------------------------------------------------

    @Unroll
    def "isClusteredSharedVolume: externalType=#externalType zonePool=#hasZonePool -> #expected"() {
        given:
        def ds = new Datastore(externalType: externalType)
        ds.zonePool = hasZonePool ? clusterA : null

        expect:
        ScvmmProvisionProvider.isClusteredSharedVolume(ds) == expected

        where:
        externalType                                        | hasZonePool || expected
        DatastoresSync.EXTERNAL_TYPE_CLUSTERED_SHARED_VOLUME | false       || true
        DatastoresSync.EXTERNAL_TYPE_CLUSTERED_SHARED_VOLUME | true        || true
        DatastoresSync.EXTERNAL_TYPE_HOST_VOLUME             | false       || false
        DatastoresSync.EXTERNAL_TYPE_HOST_VOLUME             | true        || false  // externalType wins over legacy zonePool
        null                                                | true        || true   // not re-synced yet: legacy behaviour
        null                                                | false       || false
    }

    def "isClusteredSharedVolume tolerates a missing datastore"() {
        expect:
        !ScvmmProvisionProvider.isClusteredSharedVolume(null)
    }

    // ----------------------------------------------------------------------------------------------------------
    // findClusterPool
    // ----------------------------------------------------------------------------------------------------------

    def "findClusterPool resolves a numeric morpheus id"() {
        when:
        def result = provider.findClusterPool(cloud, '10')

        then:
        1 * poolService.find({ DataQuery q -> filterValue(q, 'id') == 10L && filterValue(q, 'refId') == cloud.id }) >> clusterA
        result.is(clusterA)
    }

    def "findClusterPool resolves an SCVMM cluster id through externalId"() {
        when:
        def result = provider.findClusterPool(cloud, 'cluster-b-guid')

        then:
        1 * poolService.find({ DataQuery q -> filterValue(q, 'externalId') == 'cluster-b-guid' && filterValue(q, 'refId') == cloud.id }) >> clusterB
        result.is(clusterB)
    }

    // ----------------------------------------------------------------------------------------------------------
    // getHostAndDatastore – automatic datastore selection
    // ----------------------------------------------------------------------------------------------------------

    def "auto datastore selection with a cluster only considers datastores assigned to that cluster"() {
        given: 'the largest datastore belongs to another cluster'
        def biggestOnB = datastore(1L, 'B : big', 500L, [clusterB])
        def onA = datastore(2L, 'A : small', 100L, [clusterA])
        def standalone = datastore(3L, 'S : medium', 300L, [])
        poolService.find(_) >> clusterA
        datastoreService.list(_) >> [biggestOnB, standalone, onA]
        computeServerService.list(_) >> [hostWithMemory('HYPERV-A', 64L)]

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, clusterA.id, null, null, 'auto', 10L, null, 8L)

        then: 'the only candidate attached to cluster A is picked even though it is the smallest'
        picked.is(onA)
    }

    def "auto datastore selection without a cluster keeps the freeSpace ordering from the query"() {
        given:
        def first = datastore(1L, 'first', 500L, [clusterB])
        def second = datastore(2L, 'second', 100L, [clusterA])
        datastoreService.list(_) >> [first, second]
        computeServerService.list(_) >> [hostWithMemory('HYPERV-A', 64L)]

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, null, null, null, 'auto', 10L, null, 8L)

        then:
        picked.is(first)
        0 * poolService.find(_)
    }

    def "a datastore synced before the move to assignedZonePools still matches the cluster via zonePool"() {
        given:
        def legacy = new Datastore(id: 1L, name: 'legacy', freeSpace: 500L)
        legacy.zonePool = clusterA
        legacy.assignedZonePools = null
        poolService.find(_) >> clusterA
        datastoreService.list(_) >> [legacy]
        computeServerService.list(_) >> [hostWithMemory('HYPERV-A', 64L)]

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, clusterA.id, null, null, 'auto', 10L, null, 8L)

        then:
        picked.is(legacy)
    }

    def "no datastore attached to the selected cluster fails for a non cloud-scoped zone"() {
        given:
        poolService.find(_) >> clusterA
        datastoreService.list(_) >> [datastore(1L, 'B only', 500L, [clusterB])]
        computeServerService.list(_) >> []

        when:
        provider.getHostAndDatastore(cloud, account, clusterA.id, null, null, 'auto', 10L, null, 8L)

        then:
        def e = thrown(Exception)
        e.message.contains('Unable to obtain datastore and host')
    }

    // ----------------------------------------------------------------------------------------------------------
    // getHostAndDatastore – Highly Available decision
    // ----------------------------------------------------------------------------------------------------------

    def "a VM on a Cluster Shared Volume in a cluster is provisioned Highly Available"() {
        given:
        def csv = datastore(1L, 'cluster-a : Volume1', 500L, [clusterA], DatastoresSync.EXTERNAL_TYPE_CLUSTERED_SHARED_VOLUME)
        poolService.find(_) >> clusterA
        computeServerService.list(_) >> [hostWithMemory('HYPERV-A', 64L)]

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, clusterA.id, null, csv, null, 10L, null, 8L)

        then:
        highlyAvailable == true
    }

    def "a VM on a host-local volume in a cluster is not Highly Available even though the datastore is pool-scoped"() {
        given: 'scoping via assignedZonePools must not be mistaken for a shared volume'
        def local = datastore(1L, 'HYPERV-A : C:\\', 500L, [clusterA], DatastoresSync.EXTERNAL_TYPE_HOST_VOLUME)
        poolService.find(_) >> clusterA
        computeServerService.list(_) >> [hostWithMemory('HYPERV-A', 64L)]

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, clusterA.id, null, local, null, 10L, null, 8L)

        then:
        highlyAvailable == false
    }

    def "a VM on a Cluster Shared Volume without a selected cluster is not Highly Available"() {
        given:
        def csv = datastore(1L, 'Volume1', 500L, [clusterA], DatastoresSync.EXTERNAL_TYPE_CLUSTERED_SHARED_VOLUME)
        computeServerService.list(_) >> [hostWithMemory('HYPERV-A', 64L)]

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, null, null, csv, null, 10L, null, 8L)

        then:
        highlyAvailable == false
    }

    def "clones in a cluster follow the same storage rule as fresh VMs"() {
        given: 'a cloned VM carries the source datastore; a host-local one must not force HA (MORPH-19186)'
        def local = datastore(1L, 'HYPERV-A : C:\\', 500L, [clusterA], DatastoresSync.EXTERNAL_TYPE_HOST_VOLUME)
        poolService.find(_) >> clusterA
        computeServerService.list(_) >> [hostWithMemory('HYPERV-A', 64L)]

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, clusterA.id, null, local, null, 10L, null, 8L)

        then:
        highlyAvailable == false
    }

    def "a VM on an SMB share registered to the cluster is Highly Available"() {
        given:
        def share = datastore(1L, '\\\\filer\\vms', 500L, [clusterA], RegisteredStorageFileSharesSync.EXTERNAL_TYPE_CLUSTER_FILE_SHARE)
        poolService.find(_) >> clusterA
        computeServerService.list(_) >> [hostWithMemory('HYPERV-A', 64L)]

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, clusterA.id, null, share, null, 10L, null, 8L)

        then:
        highlyAvailable == true
    }

    // ----------------------------------------------------------------------------------------------------------
    // resolveHighlyAvailable – VM level decision across all disks
    // ----------------------------------------------------------------------------------------------------------

    @Unroll
    def "resolveHighlyAvailable: cluster=#withCluster disks=#types -> #expected"() {
        given:
        def hostA = hostWithMemory('HYPERV-A', 64L)
        def datastores = types.collect { datastore(1L, "ds-${it}", 500L, [clusterA], it) }

        expect:
        ScvmmProvisionProvider.resolveHighlyAvailable(withCluster ? clusterA.id : null, datastores, datastores.collect { hostA }) == expected

        where:
        withCluster | types                                                                                                                 || expected
        true        | [DatastoresSync.EXTERNAL_TYPE_CLUSTERED_SHARED_VOLUME]                                                               || true
        true        | [DatastoresSync.EXTERNAL_TYPE_CLUSTERED_SHARED_VOLUME, DatastoresSync.EXTERNAL_TYPE_CLUSTERED_SHARED_VOLUME]           || true
        true        | [DatastoresSync.EXTERNAL_TYPE_CLUSTERED_SHARED_VOLUME, RegisteredStorageFileSharesSync.EXTERNAL_TYPE_CLUSTER_FILE_SHARE] || true
        true        | [DatastoresSync.EXTERNAL_TYPE_HOST_VOLUME]                                                                           || false
        true        | [DatastoresSync.EXTERNAL_TYPE_HOST_VOLUME, DatastoresSync.EXTERNAL_TYPE_HOST_VOLUME]                                  || false
        true        | [RegisteredStorageFileSharesSync.EXTERNAL_TYPE_HOST_FILE_SHARE]                                                      || false
        false       | [DatastoresSync.EXTERNAL_TYPE_CLUSTERED_SHARED_VOLUME]                                                               || false
        false       | [DatastoresSync.EXTERNAL_TYPE_HOST_VOLUME]                                                                           || false
    }

    def "resolveHighlyAvailable is false when deploying to a cloud without a resolved datastore"() {
        expect:
        !ScvmmProvisionProvider.resolveHighlyAvailable(clusterA.id, [null], [null])
        !ScvmmProvisionProvider.resolveHighlyAvailable(clusterA.id, [], [])
    }

    def "mixing cluster storage and host-local storage under a resource pool is rejected"() {
        given:
        def hostA = hostWithMemory('HYPERV-A', 64L)
        def csv = datastore(1L, 'cluster-a : Volume1', 500L, [clusterA], DatastoresSync.EXTERNAL_TYPE_CLUSTERED_SHARED_VOLUME)
        def local = datastore(2L, 'HYPERV-A : C:\\', 500L, [clusterA], DatastoresSync.EXTERNAL_TYPE_HOST_VOLUME)

        when:
        ScvmmProvisionProvider.resolveHighlyAvailable(clusterA.id, [csv, local], [hostA, hostA])

        then:
        def e = thrown(Exception)
        e.message.contains('cluster-a : Volume1')
        e.message.contains('HYPERV-A : C:\\')
    }

    def "mixed storage is allowed when no resource pool is selected"() {
        given:
        def hostA = hostWithMemory('HYPERV-A', 64L)
        def csv = datastore(1L, 'Volume1', 500L, [clusterA], DatastoresSync.EXTERNAL_TYPE_CLUSTERED_SHARED_VOLUME)
        def local = datastore(2L, 'C:\\', 500L, [], DatastoresSync.EXTERNAL_TYPE_HOST_VOLUME)

        expect:
        !ScvmmProvisionProvider.resolveHighlyAvailable(null, [csv, local], [hostA, hostA])
    }

    def "host-local disks resolving to different hosts under a resource pool are rejected"() {
        given:
        def hostA = hostWithMemory('HYPERV-A', 64L)
        def hostB = hostWithMemory('HYPERV-B', 64L)
        hostB.id = 2L
        def localA = datastore(1L, 'HYPERV-A : C:\\', 500L, [clusterA], DatastoresSync.EXTERNAL_TYPE_HOST_VOLUME)
        def localB = datastore(2L, 'HYPERV-B : D:\\', 500L, [clusterA], DatastoresSync.EXTERNAL_TYPE_HOST_VOLUME)

        when:
        ScvmmProvisionProvider.resolveHighlyAvailable(clusterA.id, [localA, localB], [hostA, hostB])

        then:
        def e = thrown(Exception)
        e.message.contains('HYPERV-A')
        e.message.contains('HYPERV-B')
    }

    // ----------------------------------------------------------------------------------------------------------
    // getHostAndDatastore – host / datastore resolution
    // ----------------------------------------------------------------------------------------------------------

    def "a cloud-scoped zone with nothing selected deploys to the cloud without any lookups"() {
        given:
        cloud.regionCode = 'cloud-1'

        when:
        def result = provider.getHostAndDatastore(cloud, account, clusterId, hostId, null, datastoreOption, 10L, null, 8L)

        then:
        0 * datastoreService.list(_)
        0 * computeServerService.get(_)
        0 * computeServerService.list(_)
        result == [null, null, null, false]

        where:
        clusterId | hostId | datastoreOption
        null      | null   | 'auto'
        'null'    | ' '    | null
    }

    def "a cloud-scoped zone returns no host or datastore instead of failing when nothing has capacity"() {
        given:
        cloud.regionCode = 'cloud-1'
        poolService.find(_) >> clusterA
        datastoreService.list(_) >> []

        when:
        def result = provider.getHostAndDatastore(cloud, account, clusterA.id, null, null, 'auto', 10L, null, 8L)

        then:
        result == [null, null, null, false]
    }

    def "an explicit host is used as-is and auto datastores are limited to that host's volumes"() {
        given:
        def host = hostWithMemory('HYPERV-B', 1L)
        host.id = 7L
        def onHost = datastore(4L, 'HYPERV-B : D:\\', 100L, [])
        def hostVolume = new StorageVolume(datastore: onHost)
        DataQuery datastoreQuery

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, null, '7', null, 'auto', 10L, null, 64L * 1024 * 1024 * 1024)

        then:
        1 * computeServerService.get(7L) >> host
        1 * computeServerService.list({ filterValue(it, 'hostId') == 7L }) >> [new ComputeServer(volumes: [hostVolume])]
        1 * datastoreService.list(_) >> { DataQuery q -> datastoreQuery = q; [onHost] }
        node.is(host)
        picked.is(onHost)
        highlyAvailable == false
        datastoreQuery.filters.find { it.name == 'id' }?.value == [4L]
    }

    def "an explicit datastore skips the datastore query and auto-picks the host with the most free memory"() {
        given:
        def ds = datastore(1L, 'D1', 500L, [])
        def small = hostWithMemory('SMALL', 9L)
        def big = hostWithMemory('BIG', 32L)
        def full = hostWithMemory('FULL', 4L)

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, null, null, ds, null, 10L, null, 8L * 1024 * 1024 * 1024)

        then:
        0 * datastoreService.list(_)
        1 * computeServerService.list({ filterValue(it, 'volumes.datastore.id') == 1L }) >> [small, full, big]
        node.is(big)
        picked.is(ds)
    }

    def "no host with enough free memory fails for a non cloud-scoped zone"() {
        given:
        computeServerService.list(_) >> [hostWithMemory('FULL', 4L)]

        when:
        provider.getHostAndDatastore(cloud, account, null, null, datastore(1L, 'D1', 500L, []), null, 10L, null, 8L * 1024 * 1024 * 1024)

        then:
        def e = thrown(Exception)
        e.message == 'Unable to obtain datastore and host for options selected'
    }

    def "a null maxMemory treats every powered-on host as eligible"() {
        given:
        def host = hostWithMemory('ANY', 1L)
        computeServerService.list(_) >> [host]

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, null, null, datastore(1L, 'D1', 500L, []), null, 10L, null, null)

        then:
        node.is(host)
    }

    def "the volume path comes from an existing volume on the chosen datastore"() {
        given:
        def ds = datastore(1L, 'D1', 500L, [])
        computeServerService.list(_) >> [hostWithMemory('HYPERV-A', 64L)]

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, null, null, ds, null, 10L, null, 8L)

        then:
        1 * storageVolumeService.find({ filterValue(it, 'datastore.id') == 1L }) >> new StorageVolume(volumePath: 'C:\\ClusterStorage\\Volume1')
        volumePath == 'C:\\ClusterStorage\\Volume1'
    }

    // ----------------------------------------------------------------------------------------------------------
    // provision type capabilities
    // ----------------------------------------------------------------------------------------------------------

    def "datastore selection is offered but optional"() {
        expect:
        provider.hasDatastores()
        provider.supportsAutoDatastore()
        !provider.disableRootDatastore()
    }

    // ----------------------------------------------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------------------------------------------

    private static Datastore datastore(Long id, String name, Long freeSpace, List<CloudPool> pools, String externalType = DatastoresSync.EXTERNAL_TYPE_HOST_VOLUME) {
        def ds = new Datastore(id: id, name: name, freeSpace: freeSpace, externalType: externalType)
        ds.assignedZonePools = pools
        return ds
    }

    private static ComputeServer hostWithMemory(String name, Long freeGb) {
        def server = new ComputeServer(id: 1L, name: name, hostname: name)
        server.capacityInfo = new ComputeCapacityInfo(maxMemory: freeGb * 1024L * 1024L * 1024L, usedMemory: 0L)
        return server
    }

    private static Object filterValue(DataQuery query, String name) {
        query.filters?.find { it.name == name }?.value
    }
}
