// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.Datastore
import spock.lang.Specification
import spock.lang.Subject

/**
 * {@link ScvmmCloudProvider#filterDatastores} is the final filter Morpheus applies to the datastore dropdown on
 * the provisioning form. It has to leave the list untouched when no resource pool is selected and restrict it to
 * the storage assigned to the selected cluster(s) otherwise.
 */
class ScvmmCloudProviderFilterDatastoresSpec extends Specification {

    MorpheusContext context = Mock()
    ScvmmPlugin plugin = Mock()

    @Subject
    ScvmmCloudProvider provider = new ScvmmCloudProvider(plugin, context)

    Cloud cloud = new Cloud(id: 1L)
    CloudPool clusterA = new CloudPool(id: 10L, name: 'cluster-a')
    CloudPool clusterB = new CloudPool(id: 20L, name: 'cluster-b')

    Datastore hostVolumeA = datastore(1L, 'HYPERV-A : C:\\', [clusterA])
    Datastore hostVolumeB = datastore(2L, 'HYPERV-B : C:\\', [clusterB])
    Datastore sharedAB = datastore(3L, 'Shared Volume1', [clusterA, clusterB])
    Datastore standalone = datastore(4L, 'HYPERV-S : D:\\', [])
    Datastore legacyZonePoolOnly = legacyDatastore(5L, 'legacy CSV', clusterB)

    List<Datastore> all = [hostVolumeA, hostVolumeB, sharedAB, standalone, legacyZonePoolOnly]

    def "with no resource pool selected the full list is returned untouched"() {
        expect:
        provider.filterDatastores(cloud, all, pools).is(all)

        where:
        pools << [null, [], [new CloudPool()]]
    }

    def "with a resource pool selected only datastores assigned to that pool remain"() {
        when:
        def result = provider.filterDatastores(cloud, all, [clusterA])

        then:
        result*.id == [hostVolumeA.id, sharedAB.id]
    }

    def "datastores with no pool association are dropped once a pool is selected"() {
        given: 'a standalone host volume is not attached to any cluster'

        expect: 'it is never offered for a cluster, even though Morpheus core keeps pool-less datastores by default'
        !provider.filterDatastores(cloud, all, [clusterA]).contains(standalone)
        !provider.filterDatastores(cloud, all, [clusterB]).contains(standalone)
    }

    def "a datastore assigned to several clusters is offered for each of them"() {
        expect:
        provider.filterDatastores(cloud, all, [clusterA]).contains(sharedAB)
        provider.filterDatastores(cloud, all, [clusterB]).contains(sharedAB)
    }

    def "selecting several pools returns the union of their datastores"() {
        when:
        def result = provider.filterDatastores(cloud, all, [clusterA, clusterB])

        then:
        result*.id as Set == [hostVolumeA.id, hostVolumeB.id, sharedAB.id, legacyZonePoolOnly.id] as Set
    }

    def "datastores synced before the move to assignedZonePools are still matched through zonePool"() {
        expect:
        provider.filterDatastores(cloud, all, [clusterB]).contains(legacyZonePoolOnly)
        !provider.filterDatastores(cloud, all, [clusterA]).contains(legacyZonePoolOnly)
    }

    def "a selected pool with no matching datastores yields an empty list"() {
        expect:
        provider.filterDatastores(cloud, all, [new CloudPool(id: 99L)]).isEmpty()
    }

    def "null datastore collections are handled"() {
        expect:
        provider.filterDatastores(cloud, null, [clusterA]) == []
        provider.filterDatastores(cloud, null, []) == null
    }

    def "pool references without an id are ignored when deciding whether a pool is selected"() {
        given: 'a mix of a real pool and an empty reference'
        def pools = [new CloudPool(), clusterA]

        expect:
        provider.filterDatastores(cloud, all, pools)*.id == [hostVolumeA.id, sharedAB.id]
    }

    private static Datastore datastore(Long id, String name, List<CloudPool> assignedPools) {
        def ds = new Datastore(id: id, name: name)
        ds.assignedZonePools = assignedPools
        return ds
    }

    private static Datastore legacyDatastore(Long id, String name, CloudPool zonePool) {
        def ds = new Datastore(id: id, name: name)
        ds.zonePool = zonePool
        ds.assignedZonePools = null
        return ds
    }
}
