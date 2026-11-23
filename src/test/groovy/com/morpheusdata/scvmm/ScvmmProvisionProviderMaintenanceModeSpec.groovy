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
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Datastore
import spock.lang.Specification
import spock.lang.Subject

/**
 * SCVMM rejects placement on a host in maintenance mode with Error ID 10730. Placement must skip such hosts when
 * picking automatically, and fail with a clear message when one is targeted explicitly or inherited by a clone.
 */
class ScvmmProvisionProviderMaintenanceModeSpec extends Specification {

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
    // explicit host
    // ----------------------------------------------------------------------------------------------------------

    def "an explicitly selected host in maintenance mode fails with a clear error"() {
        given:
        def drained = host(5L, 'HYPERV-A', 64L, true)
        computeServerService.get(5L) >> drained

        when:
        provider.getHostAndDatastore(cloud, account, null, '5', datastore(1L, 'ds1', 500L), null, 10L, null, 8L)

        then:
        def e = thrown(Exception)
        e.message.contains('HYPERV-A')
        e.message.contains('maintenance mode')
    }

    def "an explicitly selected healthy host is used"() {
        given:
        def healthy = host(5L, 'HYPERV-A', 64L, false)
        computeServerService.get(5L) >> healthy

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, null, '5', datastore(1L, 'ds1', 500L), null, 10L, null, 8L)

        then:
        node.is(healthy)
    }

    // ----------------------------------------------------------------------------------------------------------
    // automatic host selection
    // ----------------------------------------------------------------------------------------------------------

    def "auto-pick skips a host in maintenance mode even when it has the most free memory"() {
        given:
        def drained = host(1L, 'HYPERV-A', 128L, true)
        def healthy = host(2L, 'HYPERV-B', 32L, false)
        computeServerService.list(_) >> [drained, healthy]

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, null, null, datastore(1L, 'ds1', 500L), null, 10L, null, 8L)

        then:
        node.is(healthy)
    }

    def "a host not yet re-synced (null maintenanceMode) stays eligible"() {
        given:
        def legacy = host(1L, 'HYPERV-A', 64L, null)
        computeServerService.list(_) >> [legacy]

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, null, null, datastore(1L, 'ds1', 500L), null, 10L, null, 8L)

        then:
        node.is(legacy)
    }

    def "auto datastore selection moves past a datastore whose only hosts are in maintenance mode"() {
        given: 'the largest datastore is attached only to a drained host'
        def onDrained = datastore(1L, 'big', 500L)
        def onHealthy = datastore(2L, 'small', 100L)
        def drained = host(1L, 'HYPERV-A', 128L, true)
        def healthy = host(2L, 'HYPERV-B', 32L, false)
        datastoreService.list(_) >> [onDrained, onHealthy]
        computeServerService.list({ DataQuery q -> filterValue(q, 'volumes.datastore.id') == 1L }) >> [drained]
        computeServerService.list({ DataQuery q -> filterValue(q, 'volumes.datastore.id') == 2L }) >> [healthy]

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, null, null, null, 'auto', 10L, null, 8L)

        then:
        picked.is(onHealthy)
        node.is(healthy)
    }

    def "auto datastore selection keeps the largest datastore when it has an eligible host"() {
        given:
        def big = datastore(1L, 'big', 500L)
        def small = datastore(2L, 'small', 100L)
        def healthy = host(2L, 'HYPERV-B', 32L, false)
        datastoreService.list(_) >> [big, small]
        computeServerService.list(_) >> [healthy]

        when:
        def (node, picked, volumePath, highlyAvailable) = provider.getHostAndDatastore(cloud, account, null, null, null, 'auto', 10L, null, 8L)

        then:
        picked.is(big)
        node.is(healthy)
    }

    def "a user-selected datastore attached only to maintenance hosts is not swapped for another"() {
        given:
        def chosen = datastore(1L, 'chosen', 500L)
        computeServerService.list(_) >> [host(1L, 'HYPERV-A', 128L, true)]

        when:
        provider.getHostAndDatastore(cloud, account, null, null, chosen, null, 10L, null, 8L)

        then:
        def e = thrown(Exception)
        e.message.contains('Unable to obtain datastore and host')
        0 * datastoreService.list(_)
    }

    def "every host in maintenance mode fails placement for a non cloud-scoped zone"() {
        given:
        datastoreService.list(_) >> [datastore(1L, 'ds1', 500L), datastore(2L, 'ds2', 100L)]
        computeServerService.list(_) >> [host(1L, 'HYPERV-A', 128L, true)]

        when:
        provider.getHostAndDatastore(cloud, account, null, null, null, 'auto', 10L, null, 8L)

        then:
        def e = thrown(Exception)
        e.message.contains('Unable to obtain datastore and host')
    }

    // ----------------------------------------------------------------------------------------------------------
    // clone placement
    // ----------------------------------------------------------------------------------------------------------

    def "a clone without an inherited host is not checked"() {
        when:
        provider.assertCloneHostEligible(new ComputeServer(id: 9L), null)

        then:
        noExceptionThrown()
        0 * computeServerService.get(_)
    }

    def "a clone pinned to a healthy host proceeds"() {
        given:
        computeServerService.get(5L) >> host(5L, 'HYPERV-A', 64L, false)

        when:
        provider.assertCloneHostEligible(new ComputeServer(id: 9L), 5L)

        then:
        noExceptionThrown()
    }

    def "a clone pinned to a drained host whose source has migrated points at the source's current host"() {
        given:
        def drained = host(5L, 'HYPERV-A', 64L, true)
        def current = host(6L, 'HYPERV-B', 64L, false)
        computeServerService.get(5L) >> drained
        computeServerService.get(6L) >> current
        def source = new ComputeServer(id: 9L, parentServer: current)

        when:
        provider.assertCloneHostEligible(source, 5L)

        then:
        def e = thrown(Exception)
        e.message.contains('HYPERV-A')
        e.message.contains('migrated to host HYPERV-B')
    }

    def "a clone pinned to a drained host whose source is still on it reports the host as unavailable"() {
        given:
        def drained = host(5L, 'HYPERV-A', 64L, true)
        computeServerService.get(5L) >> drained
        def source = new ComputeServer(id: 9L, parentServer: drained)

        when:
        provider.assertCloneHostEligible(source, 5L)

        then:
        def e = thrown(Exception)
        e.message.contains('HYPERV-A')
        e.message.contains('not running on another available host')
    }

    def "a clone whose source migrated onto another drained host reports the host as unavailable"() {
        given:
        def drained = host(5L, 'HYPERV-A', 64L, true)
        def alsoDrained = host(6L, 'HYPERV-B', 64L, true)
        computeServerService.get(5L) >> drained
        computeServerService.get(6L) >> alsoDrained
        def source = new ComputeServer(id: 9L, parentServer: alsoDrained)

        when:
        provider.assertCloneHostEligible(source, '5')

        then:
        def e = thrown(Exception)
        e.message.contains('not running on another available host')
    }

    // ----------------------------------------------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------------------------------------------

    private static Datastore datastore(Long id, String name, Long freeSpace) {
        def ds = new Datastore(id: id, name: name, freeSpace: freeSpace)
        ds.assignedZonePools = []
        return ds
    }

    private static ComputeServer host(Long id, String name, Long freeGb, Boolean maintenanceMode) {
        def server = new ComputeServer(id: id, name: name, hostname: name)
        server.capacityInfo = new ComputeCapacityInfo(maxMemory: freeGb * 1024L * 1024L * 1024L, usedMemory: 0L)
        server.maintenanceMode = maintenanceMode
        return server
    }

    private static Object filterValue(DataQuery query, String name) {
        query.filters?.find { it.name == name }?.value
    }
}
