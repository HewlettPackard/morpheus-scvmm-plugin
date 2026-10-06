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
import com.morpheusdata.scvmm.sync.DatastoresSync
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

    def "clones in a cluster are always Highly Available regardless of the datastore"() {
        given:
        def local = datastore(1L, 'HYPERV-A : C:\\', 500L, [clusterA], DatastoresSync.EXTERNAL_TYPE_HOST_VOLUME)
        poolService.find(_) >> clusterA
        computeServerService.list(_) >> [hostWithMemory('HYPERV-A', 64L)]

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, clusterA.id, null, local, null, 10L, null, 8L, true)

        then:
        highlyAvailable == true
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
