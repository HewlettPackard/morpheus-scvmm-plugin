// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.network.MorpheusNetworkPoolIpService
import com.morpheusdata.core.network.MorpheusNetworkPoolService
import com.morpheusdata.core.network.MorpheusNetworkService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkPoolService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.NetAddress
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.NetworkPoolIp
import com.morpheusdata.model.projection.NetworkPoolIpIdentityProjection
import com.morpheusdata.scvmm.ScvmmApiService
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

/**
 * reconcilePoolIps wiring: SCVMM grants are listed once, planned per pool, and persisted through the pool IP service.
 */
class IpPoolsSyncReconcileSpec extends Specification {

    MorpheusContext context = Mock()
    MorpheusServices services = Mock()
    MorpheusAsyncServices async = Mock()
    MorpheusSynchronousCloudService cloudService = Mock()
    MorpheusSynchronousNetworkService syncNetwork = Mock()
    MorpheusSynchronousNetworkPoolService syncPool = Mock()
    MorpheusNetworkService asyncNetwork = Mock()
    MorpheusNetworkPoolService asyncPool = Mock()
    MorpheusNetworkPoolIpService poolIpService = Mock()
    ScvmmApiService apiService = Mock()

    Cloud cloud = new Cloud(id: 1L, name: 'cxo', account: new Account(id: 1L))
    NetworkPool pool = new NetworkPool(id: 3L, name: 'cxo-1-pool', externalId: '4784149f', gateway: '10.157.232.1', netmask: '255.255.252.0')
    ComputeServer server = new ComputeServer(id: 63L, name: 'p189', interfaces: [
            new ComputeServerInterface(id: 1275L, name: 'eth0', externalId: 'ad-1', addresses: [new NetAddress(type: NetAddress.AddressType.IPV4, address: '10.157.232.111')])])

    IpPoolsSync sync

    def setup() {
        context.services >> services
        context.async >> async
        services.cloud >> cloudService
        cloudService.network >> syncNetwork
        syncNetwork.pool >> syncPool
        async.network >> asyncNetwork
        asyncNetwork.pool >> asyncPool
        asyncPool.poolIp >> poolIpService

        sync = Spy(IpPoolsSync, constructorArgs: [context, cloud, null])
        sync.@apiService = apiService
        sync.listCloudServers() >> [server]
    }

    def "restores a lease SCVMM still holds that Morpheus lost, and drops one SCVMM no longer has"() {
        given:
        NetworkPoolIp stale = new NetworkPoolIp(id: 9L, ipAddress: '10.157.232.119', externalId: 'g-gone')

        when:
        sync.reconcilePoolIps([:])

        then:
        1 * apiService.listPoolIPAddresses([:]) >> [success: true, ipAddresses: [
                [PoolID: '4784149f', ID: 'g-111', Address: '10.157.232.111', State: 'Assigned'],
                [PoolID: 'other-pool', ID: 'g-x', Address: '10.1.1.1', State: 'Assigned']]]
        1 * syncPool.list(_) >> [pool]
        1 * poolIpService.listIdentityProjections(3L) >> Observable.fromIterable([new NetworkPoolIpIdentityProjection(9L, 'g-gone', '10.157.232.119')])
        1 * poolIpService.listById([9L]) >> Observable.fromIterable([stale])
        1 * poolIpService.remove(3L, { it*.id == [9L] }) >> Single.just(true)
        1 * poolIpService.create(pool, { List<NetworkPoolIp> adds ->
            adds.size() == 1 && adds[0].ipAddress == '10.157.232.111' && adds[0].externalId == 'g-111' &&
                    adds[0].refId == 63L && adds[0].subRefId == 1275L && adds[0].ipType == 'assigned'
        }) >> Single.just(true)
        0 * poolIpService.save(_)
    }

    def "does nothing when Morpheus already mirrors SCVMM"() {
        given:
        NetworkPoolIp row = new NetworkPoolIp(id: 10L, ipAddress: '10.157.232.111', externalId: 'g-111', staticIp: true, ipType: 'assigned', refType: 'ComputeServer', refId: 63L, subRefId: 1275L)

        when:
        sync.reconcilePoolIps([:])

        then:
        1 * apiService.listPoolIPAddresses([:]) >> [success: true, ipAddresses: [[PoolID: '4784149F', ID: 'g-111', Address: '10.157.232.111', State: 'Assigned']]]
        1 * syncPool.list(_) >> [pool]
        1 * poolIpService.listIdentityProjections(3L) >> Observable.fromIterable([new NetworkPoolIpIdentityProjection(10L, 'g-111', '10.157.232.111')])
        1 * poolIpService.listById([10L]) >> Observable.fromIterable([row])
        0 * poolIpService.create(*_)
        0 * poolIpService.save(_)
        0 * poolIpService.remove(*_)
    }

    def "a failed SCVMM listing is a warning and touches nothing"() {
        when:
        sync.reconcilePoolIps([:])

        then:
        1 * apiService.listPoolIPAddresses([:]) >> [success: false, msg: 'winrm down']
        0 * syncPool.list(_)
        0 * poolIpService._
    }

    def "no SCVMM pools in Morpheus means nothing to reconcile"() {
        when:
        sync.reconcilePoolIps([:])

        then:
        1 * apiService.listPoolIPAddresses([:]) >> [success: true, ipAddresses: [[PoolID: 'p', ID: 'g', Address: '1.1.1.1']]]
        1 * syncPool.list(_) >> []
        0 * poolIpService._
    }
}
