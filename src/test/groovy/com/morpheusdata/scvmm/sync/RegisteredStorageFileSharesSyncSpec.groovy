// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
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
}
