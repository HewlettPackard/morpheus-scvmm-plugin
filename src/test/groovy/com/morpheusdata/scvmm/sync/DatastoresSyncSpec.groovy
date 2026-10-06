// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * Covers the pool-scoping decisions made by {@link DatastoresSync}: which cluster owns a Cluster Shared Volume,
 * which clusters a datastore is assigned to, and how host names are matched. These are the rules that drive the
 * resource pool based datastore filtering on the provisioning form.
 */
class DatastoresSyncSpec extends Specification {

    Cloud cloud = new Cloud(id: 1L, name: 'scvmm-cloud')
    MorpheusContext context = Mock()

    @Subject
    DatastoresSync sync = new DatastoresSync(null, cloud, context)

    CloudPool clusterA = cluster(10L, 'cluster-a', ['Volume1', 'Volume2'])
    CloudPool clusterB = cluster(20L, 'cluster-b', ['Volume1', 'Volume3'])

    ComputeServer hostA1 = host('HYPERV-A1.lab.local', clusterA)
    ComputeServer hostA2 = host('HYPERV-A2.lab.local', clusterA)
    ComputeServer hostB1 = host('HYPERV-B1.lab.local', clusterB)
    ComputeServer standaloneHost = host('HYPERV-S1.lab.local', null)

    // ----------------------------------------------------------------------------------------------------------
    // findSharedVolumeCluster
    // ----------------------------------------------------------------------------------------------------------

    def "non shared volumes never resolve to a cluster"() {
        expect:
        sync.findSharedVolumeCluster([name: 'Volume1', isClusteredSharedVolume: false], [clusterA, clusterB], hostA1) == null
    }

    def "same-named shared volumes are attributed to the reporting host's cluster"() {
        given: 'Volume1 is exposed by both clusters'
        def volume = [name: 'Volume1', isClusteredSharedVolume: true]

        expect: 'the cluster of the host that reported the volume wins'
        sync.findSharedVolumeCluster(volume, [clusterA, clusterB], hostA1).id == clusterA.id
        sync.findSharedVolumeCluster(volume, [clusterA, clusterB], hostB1).id == clusterB.id
    }

    def "shared volume name matching is exact, not a substring match"() {
        given: 'a volume whose name is a prefix of a listed shared volume'
        def volume = [name: 'Volume', isClusteredSharedVolume: true]

        expect: 'no cluster lists exactly "Volume", so without a host there is no match'
        sync.findSharedVolumeCluster(volume, [clusterA, clusterB], null) == null
    }

    def "a shared volume known by a single cluster resolves without a host"() {
        expect:
        sync.findSharedVolumeCluster([name: 'Volume2', isClusteredSharedVolume: true], [clusterA, clusterB], null).id == clusterA.id
        sync.findSharedVolumeCluster([name: 'Volume3', isClusteredSharedVolume: true], [clusterA, clusterB], null).id == clusterB.id
    }

    def "an ambiguous shared volume with no host is left unresolved rather than guessed"() {
        expect:
        sync.findSharedVolumeCluster([name: 'Volume1', isClusteredSharedVolume: true], [clusterA, clusterB], null) == null
    }

    def "falls back to the host's cluster when no cluster lists the volume"() {
        given: 'a CSV that none of the clusters report in sharedVolumes (e.g. stale cluster config)'
        def volume = [name: 'Unknown', isClusteredSharedVolume: true]

        expect:
        sync.findSharedVolumeCluster(volume, [clusterA, clusterB], hostB1).id == clusterB.id
    }

    def "host cluster is resolved against the synced cluster list so the full record is returned"() {
        given: 'the host only carries a reference with an id'
        def hostWithRef = host('HYPERV-A9.lab.local', new CloudPool(id: clusterA.id))

        when:
        def result = sync.findSharedVolumeCluster([name: 'Unknown', isClusteredSharedVolume: true], [clusterA, clusterB], hostWithRef)

        then: 'the matching full cluster (with its name) is returned, not the bare reference'
        result.is(clusterA)
    }

    // ----------------------------------------------------------------------------------------------------------
    // sharedVolumeNames
    // ----------------------------------------------------------------------------------------------------------

    @Unroll
    def "sharedVolumeNames accepts #description"() {
        given:
        def pool = new CloudPool(id: 1L)
        pool.setConfigProperty('sharedVolumes', raw)

        expect:
        DatastoresSync.sharedVolumeNames(pool) == expected as Set

        where:
        description                 | raw                    || expected
        'a list'                    | ['Volume1', 'Volume2'] || ['Volume1', 'Volume2']
        'a list containing nulls'   | ['Volume1', null]      || ['Volume1']
        'the string form of a list' | '[Volume1, Volume2]'   || ['Volume1', 'Volume2']
        'a single name'             | 'Volume1'              || ['Volume1']
        'an empty string'           | ''                     || []
        'null'                      | null                   || []
    }

    // ----------------------------------------------------------------------------------------------------------
    // findOwningPools
    // ----------------------------------------------------------------------------------------------------------

    def "a host volume is assigned to the cluster of the host that reported it"() {
        given:
        def volume = [name: 'C:\\', vmHost: hostA1.hostname, partitionUniqueID: 'p-1']

        when:
        def pools = sync.findOwningPools(volume, allHosts(), [:], null)

        then:
        pools*.id == [clusterA.id]
    }

    def "a volume on a host that belongs to no cluster is assigned to no pool"() {
        given:
        def volume = [name: 'C:\\', vmHost: standaloneHost.hostname, partitionUniqueID: 'p-1']

        expect:
        sync.findOwningPools(volume, allHosts(), [:], null).isEmpty()
    }

    def "a shared volume reported by hosts in several clusters is assigned to every one of them"() {
        given: 'the de-duplicated record came from host A1 but the volume was also reported by B1'
        def volume = [name: 'Volume1', vmHost: hostA1.hostname, isClusteredSharedVolume: true, storageVolumeID: 'sv-1', partitionUniqueID: 'p-1']
        def hostNamesByDatastore = ['p-1': [hostA1.hostname.toLowerCase(), hostB1.hostname.toLowerCase()] as Set]

        when:
        def pools = sync.findOwningPools(volume, allHosts(), hostNamesByDatastore, null)

        then:
        pools*.id as Set == [clusterA.id, clusterB.id] as Set
    }

    def "owning pools are de-duplicated when several hosts of the same cluster report the volume"() {
        given:
        def volume = [name: 'Volume2', vmHost: hostA1.hostname, isClusteredSharedVolume: true, partitionUniqueID: 'p-2']
        def hostNamesByDatastore = ['p-2': [hostA1.hostname.toLowerCase(), hostA2.hostname.toLowerCase()] as Set]

        when:
        def pools = sync.findOwningPools(volume, allHosts(), hostNamesByDatastore, clusterA)

        then:
        pools*.id == [clusterA.id]
    }

    def "the shared volume cluster is added even when no reporting host belongs to it"() {
        given:
        def volume = [name: 'Volume3', vmHost: standaloneHost.hostname, isClusteredSharedVolume: true, partitionUniqueID: 'p-3']

        when:
        def pools = sync.findOwningPools(volume, allHosts(), [:], clusterB)

        then:
        pools*.id == [clusterB.id]
    }

    def "owning pools are returned as lightweight references carrying only the id"() {
        given:
        def volume = [name: 'C:\\', vmHost: hostA1.hostname, partitionUniqueID: 'p-1']

        when:
        def pools = sync.findOwningPools(volume, allHosts(), [:], null)

        then: 'only the id is set so the save only has to resolve the association'
        pools.size() == 1
        pools[0].id == clusterA.id
        pools[0].name == null
    }

    // ----------------------------------------------------------------------------------------------------------
    // host name matching
    // ----------------------------------------------------------------------------------------------------------

    @Unroll
    def "host names are matched case-insensitively and trimmed: #vmHost"() {
        expect:
        DatastoresSync.findHost(allHosts(), vmHost)?.hostname == expectedHostname

        where:
        vmHost                  || expectedHostname
        'HYPERV-A1.lab.local'   || 'HYPERV-A1.lab.local'
        'hyperv-a1.lab.local'   || 'HYPERV-A1.lab.local'
        ' HYPERV-B1.LAB.LOCAL ' || 'HYPERV-B1.lab.local'
        'HYPERV-ZZ.lab.local'   || null
        ''                      || null
        null                    || null
    }

    def "a volume on a host with mixed case is still assigned to that host's cluster"() {
        given:
        def volume = [name: 'C:\\', vmHost: 'hyperv-b1.LAB.local', partitionUniqueID: 'p-9']

        expect:
        sync.findOwningPools(volume, allHosts(), [:], null)*.id == [clusterB.id]
    }

    // ----------------------------------------------------------------------------------------------------------
    // poolIds
    // ----------------------------------------------------------------------------------------------------------

    def "poolIds compares pool sets regardless of order and ignores pools without an id"() {
        expect:
        DatastoresSync.poolIds([new CloudPool(id: 1L), new CloudPool(id: 2L)]) == DatastoresSync.poolIds([new CloudPool(id: 2L), new CloudPool(id: 1L)])
        DatastoresSync.poolIds([new CloudPool(id: 1L), new CloudPool()]) == [1L] as Set
        DatastoresSync.poolIds(null) == [] as Set
    }

    // ----------------------------------------------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------------------------------------------

    private static CloudPool cluster(Long id, String name, List sharedVolumes) {
        def pool = new CloudPool(id: id, name: name, type: 'Cluster')
        if (sharedVolumes != null) {
            pool.setConfigProperty('sharedVolumes', sharedVolumes)
        }
        return pool
    }

    private static ComputeServer host(String hostname, CloudPool pool) {
        def server = new ComputeServer(hostname: hostname, name: hostname.split('\\.')[0])
        server.resourcePool = pool
        return server
    }

    private List<ComputeServer> allHosts() {
        [hostA1, hostA2, hostB1, standaloneHost]
    }
}
