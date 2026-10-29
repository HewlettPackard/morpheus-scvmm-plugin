// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.helper

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.network.MorpheusNetworkPoolServerService
import com.morpheusdata.core.network.MorpheusNetworkPoolService
import com.morpheusdata.core.network.MorpheusNetworkService
import com.morpheusdata.model.projection.NetworkPoolIdentityProjection
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkPoolServerService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.NetworkPoolServer
import spock.lang.Specification

class NetworkPoolServerHelperSpec extends Specification {

    MorpheusContext context = Mock(MorpheusContext)
    MorpheusServices services = Mock(MorpheusServices)
    MorpheusSynchronousNetworkService networkService = Mock(MorpheusSynchronousNetworkService)
    MorpheusSynchronousNetworkPoolServerService poolServerService = Mock(MorpheusSynchronousNetworkPoolServerService)
    Cloud cloud = new Cloud(id: 7L, name: 'scvmm-east', account: new Account(id: 1L))

    NetworkPoolServerHelper helper

    def setup() {
        context.services >> services
        services.network >> networkService
        networkService.poolServer >> poolServerService
        helper = new NetworkPoolServerHelper(context)
    }

    def "derives ids and categories from the cloud"() {
        expect:
        NetworkPoolServerHelper.getInternalId(cloud) == 'scvmm-ipam.7'
        NetworkPoolServerHelper.getPoolCategory(cloud) == 'scvmm.ipPool.7'
    }

    def "extracts the cloud id only from servers it created"() {
        expect:
        NetworkPoolServerHelper.getCloudId(poolServer) == expected

        where:
        poolServer                                         | expected
        new NetworkPoolServer(internalId: 'scvmm-ipam.7')  | 7L
        new NetworkPoolServer(internalId: 'scvmm-ipam.x')  | null
        new NetworkPoolServer(internalId: 'infoblox.7')    | null
        new NetworkPoolServer()                            | null
        null                                               | null
    }

    def "ensurePoolServer reuses an existing hidden server"() {
        given:
        def existing = new NetworkPoolServer(id: 3L, internalId: 'scvmm-ipam.7')

        when:
        def result = helper.ensurePoolServer(cloud)

        then:
        1 * poolServerService.find(_) >> existing
        0 * poolServerService.create(_)
        result.is(existing)
    }

    def "ensurePoolServer creates a hidden server typed for the SCVMM IPAM provider"() {
        when:
        def result = helper.ensurePoolServer(cloud)

        then:
        1 * poolServerService.find(_) >> null
        1 * poolServerService.create({ NetworkPoolServer s ->
            s.internalId == 'scvmm-ipam.7' && s.type.code == 'scvmm-ipam' && s.visible == false &&
                    s.account.is(cloud.account) && s.name.contains('scvmm-east')
        }) >> { NetworkPoolServer s -> s.id = 9L; s }
        result.id == 9L
    }

    def "bindPool parents the pool to the server and reports whether it changed"() {
        given:
        def poolServer = new NetworkPoolServer(id: 3L)
        def pool = new NetworkPool()

        expect:
        NetworkPoolServerHelper.bindPool(pool, poolServer)
        pool.poolServer.is(poolServer)
        pool.parentType == 'NetworkPoolServer'
        pool.parentId == '3'

        and: 'a second bind is a no-op'
        !NetworkPoolServerHelper.bindPool(pool, poolServer)
    }

    def "bindPool ignores a missing or unsaved server"() {
        given:
        def pool = new NetworkPool()

        expect:
        !NetworkPoolServerHelper.bindPool(pool, null)
        !NetworkPoolServerHelper.bindPool(pool, new NetworkPoolServer())
        pool.poolServer == null
    }

    // --- removePoolServer ---

    MorpheusAsyncServices asyncServices = Mock(MorpheusAsyncServices)
    MorpheusCloudService asyncCloudService = Mock(MorpheusCloudService)
    MorpheusNetworkService asyncCloudNetworkService = Mock(MorpheusNetworkService)
    MorpheusNetworkPoolService asyncPoolService = Mock(MorpheusNetworkPoolService)
    MorpheusNetworkService asyncNetworkService = Mock(MorpheusNetworkService)
    MorpheusNetworkPoolServerService asyncPoolServerService = Mock(MorpheusNetworkPoolServerService)

    private void wireAsync() {
        context.getAsync() >> asyncServices
        asyncServices.getCloud() >> asyncCloudService
        asyncCloudService.getNetwork() >> asyncCloudNetworkService
        asyncCloudNetworkService.getPool() >> asyncPoolService
        asyncServices.getNetwork() >> asyncNetworkService
        asyncNetworkService.getPoolServer() >> asyncPoolServerService
    }

    def "removePoolServer removes the cloud's SCVMM pools and then the hidden server"() {
        given:
        wireAsync()
        def poolServer = new NetworkPoolServer(id: 3L, internalId: 'scvmm-ipam.7')
        def pools = [new NetworkPoolIdentityProjection(id: 21L), new NetworkPoolIdentityProjection(id: 22L)]
        DataQuery captured

        when:
        helper.removePoolServer(cloud)

        then:
        1 * poolServerService.find(_) >> poolServer
        1 * asyncPoolService.listIdentityProjections(_ as DataQuery) >> { DataQuery q -> captured = q; Observable.fromIterable(pools) }

        then:
        1 * asyncPoolService.remove(3L, pools) >> Single.just(true)

        then:
        1 * asyncPoolServerService.remove([poolServer]) >> Single.just(true)

        and:
        captured.filters.find { it.name == 'account.id' }.value == 1L
        captured.filters.find { it.name == 'category' }.value == 'scvmm.ipPool.7'
    }

    def "removePoolServer removes only the server when the cloud has no SCVMM pools"() {
        given:
        wireAsync()
        def poolServer = new NetworkPoolServer(id: 3L)

        when:
        helper.removePoolServer(cloud)

        then:
        1 * poolServerService.find(_) >> poolServer
        1 * asyncPoolService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
        0 * asyncPoolService.remove(*_)
        1 * asyncPoolServerService.remove([poolServer]) >> Single.just(true)
    }

    def "removePoolServer is a no-op when the cloud has no hidden server"() {
        given:
        wireAsync()

        when:
        helper.removePoolServer(cloud)

        then:
        1 * poolServerService.find(_) >> null
        0 * asyncPoolService._
        0 * asyncPoolServerService._
    }

    def "removePoolServer propagates a pool removal failure and leaves the server in place"() {
        given:
        wireAsync()
        poolServerService.find(_) >> new NetworkPoolServer(id: 3L)
        asyncPoolService.listIdentityProjections(_ as DataQuery) >> Observable.just(new NetworkPoolIdentityProjection(id: 21L))
        asyncPoolService.remove(*_) >> Single.error(new IllegalStateException('pool in use'))

        when:
        helper.removePoolServer(cloud)

        then:
        def e = thrown(IllegalStateException)
        e.message == 'pool in use'
        0 * asyncPoolServerService.remove(_)
    }
}
