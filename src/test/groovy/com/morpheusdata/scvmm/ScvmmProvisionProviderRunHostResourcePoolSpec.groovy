// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.provisioning.HostRequest
import spock.lang.Specification
import spock.lang.Unroll

/**
 * MORPH-19514: hosts provisioned through runHost (Docker cluster hosts) must be placed inside the selected resource
 * pool, the same way runWorkload and embedded initializeServer pass the pool to getHostAndDatastore.
 */
class ScvmmProvisionProviderRunHostResourcePoolSpec extends Specification {

    static final String STOP = 'stop after host and datastore selection'

    MorpheusContext context = Mock()
    MorpheusServices services = Mock()
    MorpheusSynchronousCloudService cloudService = Mock()
    MorpheusSynchronousCloudPoolService poolService = Mock()
    ScvmmApiService apiService = Mock()

    ScvmmProvisionProvider provider

    Cloud cloud = new Cloud(id: 1L, regionCode: null)
    Account account = new Account(id: 1L)
    CloudPool cluster = new CloudPool(id: 10L, externalId: 'cluster-a-guid', name: 'cluster-a')

    def setup() {
        context.services >> services
        services.cloud >> cloudService
        cloudService.pool >> poolService
        provider = Spy(ScvmmProvisionProvider, constructorArgs: [Mock(ScvmmPlugin), context])
        provider.apiService = apiService
    }

    private ComputeServer dockerHost(CloudPool resourcePool, Map config) {
        def server = new ComputeServer(id: 194L, name: 'docker-host-1', cloud: cloud, account: account, maxMemory: 4096L,
                resourcePool: resourcePool)
        server.volumes = [new StorageVolume(id: 1L, rootVolume: true, maxStorage: 20L, datastoreOption: 'auto')]
        server.setConfigMap(config)
        return server
    }

    private void stubPoolLookup() {
        poolService.find(_ as DataQuery) >> { DataQuery q ->
            def id = q.filters.find { it.name == 'id' }?.value
            def externalId = q.filters.find { it.name == 'externalId' }?.value
            (id == cluster.id || externalId == cluster.externalId) ? cluster : null
        }
    }

    // ----------------------------------------------------------------------------------------------------------
    // runHost passes the pool to placement
    // ----------------------------------------------------------------------------------------------------------

    def "runHost with a selected resource pool and Auto-Datastore places the host inside that cluster"() {
        given: 'the QA Docker cluster case: pool saved on the server and in the wizard config, Auto-Datastore'
        def server = dockerHost(cluster, [resourcePool: 'pool-10'])
        provider.pickScvmmController(cloud) >> new ComputeServer(id: 2L)
        apiService.getScvmmCloudOpts(*_) >> [:]

        when:
        def result = provider.runHost(server, new HostRequest(), [:])

        then:
        1 * provider.getHostAndDatastore(cloud, account, 'cluster-a-guid', null, null, 'auto', 20L, _, 4096L) >> {
            throw new RuntimeException(STOP)
        }
        !result.success
        result.msg == STOP
    }

    def "runHost resolves the pool from the wizard config when the server does not carry it yet"() {
        given:
        stubPoolLookup()
        def server = dockerHost(null, [resourcePoolId: 'pool-10'])
        provider.pickScvmmController(cloud) >> new ComputeServer(id: 2L)
        apiService.getScvmmCloudOpts(*_) >> [:]

        when:
        def result = provider.runHost(server, new HostRequest(), [:])

        then:
        1 * provider.getHostAndDatastore(cloud, account, 'cluster-a-guid', *_) >> { throw new RuntimeException(STOP) }
        result.msg == STOP
    }

    def "runHost without a resource pool keeps the cloud-wide placement"() {
        given:
        def server = dockerHost(null, [:])
        provider.pickScvmmController(cloud) >> new ComputeServer(id: 2L)
        apiService.getScvmmCloudOpts(*_) >> [:]

        when:
        def result = provider.runHost(server, new HostRequest(), [:])

        then:
        1 * provider.getHostAndDatastore(cloud, account, null, null, null, 'auto', 20L, _, 4096L) >> {
            throw new RuntimeException(STOP)
        }
        0 * poolService.find(_)
        result.msg == STOP
    }

    // ----------------------------------------------------------------------------------------------------------
    // resolveHostClusterId
    // ----------------------------------------------------------------------------------------------------------

    def "resolveHostClusterId prefers the pool already assigned to the server"() {
        given:
        def server = dockerHost(cluster, [resourcePool: 'pool-99'])

        expect:
        provider.resolveHostClusterId(cloud, server, server.configMap) == 'cluster-a-guid'
    }

    def "resolveHostClusterId loads the server pool when only its id is populated"() {
        given:
        stubPoolLookup()
        def server = dockerHost(new CloudPool(id: 10L), [:])

        expect:
        provider.resolveHostClusterId(cloud, server, server.configMap) == 'cluster-a-guid'
    }

    @Unroll
    def "resolveHostClusterId reads config #config -> #expected"() {
        given:
        stubPoolLookup()
        def server = dockerHost(null, config)

        expect:
        provider.resolveHostClusterId(cloud, server, server.configMap) == expected

        where:
        config                                              || expected
        [resourcePool: 'pool-10']                           || 'cluster-a-guid'
        [resourcePoolId: 'pool-10']                         || 'cluster-a-guid'
        [resourcePoolId: 'pool-10', resourcePool: 'pool-7'] || 'cluster-a-guid'
        [resourcePool: '10']                                || 'cluster-a-guid'
        [resourcePool: 10]                                  || 'cluster-a-guid'
        [resourcePool: 'cluster-a-guid']                    || 'cluster-a-guid'
        [resourcePool: 'pool-7']                            || null
        [resourcePool: 'pool-']                             || null
        [resourcePool: '']                                  || null
        [resourcePool: 'null']                              || null
        [resourcePool: 'poolGroup-3']                       || null
        [:]                                                 || null
    }
}
