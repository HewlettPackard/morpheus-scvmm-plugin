// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.NetworkPoolIp
import com.morpheusdata.model.NetworkPoolServer
import spock.lang.Specification
import spock.lang.Subject

class ScvmmIPAMProviderSpec extends Specification {

    static final Map SCVMM_OPTS = [sshHost: '1.2.3.4']

    ScvmmPlugin plugin = Mock(ScvmmPlugin)
    MorpheusContext context = Mock(MorpheusContext)
    ScvmmApiService apiService = Mock(ScvmmApiService)

    @Subject
    ScvmmIPAMProvider provider

    def setup() {
        provider = Spy(ScvmmIPAMProvider, constructorArgs: [plugin, context])
        provider.apiService = apiService
    }

    def "identifies itself with the scvmm-ipam code"() {
        expect:
        provider.code == 'scvmm-ipam'
        provider.name == 'SCVMM IPAM'
        provider.morpheus.is(context)
        provider.plugin.is(plugin)
        !provider.creatable
    }

    def "declares the scvmm network pool type matching the appliance seed"() {
        when:
        def poolTypes = provider.networkPoolTypes

        then:
        poolTypes.size() == 1
        with(poolTypes.first()) {
            code == 'scvmm'
            name == 'SCVMM'
            description == 'SCVMM network ip pool'
            !creatable
            !rangeSupportsCidr
            !hostRecordEditable
        }
    }

    def "pool server lifecycle methods reject anything but the hidden per-cloud server"() {
        expect:
        [
                provider.verifyNetworkPoolServer(poolServer, [:]),
                provider.createNetworkPoolServer(poolServer, [:]),
                provider.updateNetworkPoolServer(poolServer, [:]),
                provider.initializeNetworkPoolServer(poolServer, [:])
        ].every { !it.success && it.msg.contains('cannot be managed as a standalone IPAM integration') }

        where:
        poolServer << [null, new NetworkPoolServer(internalId: null), new NetworkPoolServer(internalId: 'infoblox.3')]
    }

    def "pool server lifecycle methods accept the hidden per-cloud server"() {
        given:
        def poolServer = new NetworkPoolServer(internalId: 'scvmm-ipam.3')

        expect:
        [
                provider.verifyNetworkPoolServer(poolServer, [:]),
                provider.createNetworkPoolServer(poolServer, [:]),
                provider.updateNetworkPoolServer(poolServer, [:]),
                provider.initializeNetworkPoolServer(poolServer, [:])
        ].every { it.success }
    }

    def "createHostRecord grants an address from the SCVMM pool and stamps it on the pool ip"() {
        given:
        def pool = new NetworkPool(externalId: 'pool-1')
        def poolIp = new NetworkPoolIp()

        when:
        def response = provider.createHostRecord(null, pool, poolIp, null, false, false)

        then:
        1 * provider.getScvmmOpts(pool, null) >> SCVMM_OPTS
        1 * apiService.reserveIPAddress(SCVMM_OPTS, 'pool-1', null) >> [success: true, ipAddress: [ID: 'ip-99', Address: '10.0.0.5']]

        and:
        response.success
        poolIp.ipAddress == '10.0.0.5'
        poolIp.externalId == 'ip-99'
        poolIp.staticIp
    }

    def "createHostRecord requests the user-chosen address when one is already on the pool ip"() {
        given:
        def poolServer = new NetworkPoolServer(internalId: 'scvmm-ipam.3')
        def pool = new NetworkPool(externalId: 'pool-1')
        def poolIp = new NetworkPoolIp(ipAddress: '10.0.0.42')

        when:
        def response = provider.createHostRecord(poolServer, pool, poolIp, null, false, false)

        then:
        1 * provider.getScvmmOpts(pool, poolServer) >> SCVMM_OPTS
        1 * apiService.reserveIPAddress(SCVMM_OPTS, 'pool-1', '10.0.0.42') >> [success: true, ipAddress: [ID: 'ip-42', Address: '10.0.0.42']]

        and:
        response.success
        poolIp.ipAddress == '10.0.0.42'
        poolIp.externalId == 'ip-42'
    }

    def "createHostRecord fails when SCVMM grants no address"() {
        given:
        def pool = new NetworkPool(externalId: 'pool-1')

        when:
        def response = provider.createHostRecord(null, pool, new NetworkPoolIp(), null, false, false)

        then:
        1 * provider.getScvmmOpts(pool, null) >> SCVMM_OPTS
        1 * apiService.reserveIPAddress(SCVMM_OPTS, 'pool-1', null) >> [success: false, msg: 'boom']

        and:
        !response.success
        response.msg == 'boom'
    }

    def "createHostRecord fails when no SCVMM controller can be resolved"() {
        given:
        def pool = new NetworkPool(id: 3L, externalId: 'pool-1')

        when:
        def response = provider.createHostRecord(null, pool, new NetworkPoolIp(), null, false, false)

        then:
        1 * provider.getScvmmOpts(pool, null) >> null
        0 * apiService.reserveIPAddress(_, _, _)

        and:
        !response.success
        response.msg.contains('Unable to resolve an SCVMM controller')
    }

    def "deleteHostRecord revokes the address in SCVMM"() {
        given:
        def pool = new NetworkPool(externalId: 'pool-1')
        def poolIp = new NetworkPoolIp(externalId: 'ip-99', ipAddress: '10.0.0.5')

        when:
        def response = provider.deleteHostRecord(pool, poolIp, true)

        then:
        1 * provider.getScvmmOpts(pool, null) >> SCVMM_OPTS
        1 * apiService.releaseIPAddress(SCVMM_OPTS, 'pool-1', 'ip-99') >> [success: true]

        and:
        response.success
    }

    def "deleteHostRecord is a no-op for an address that was never granted in SCVMM"() {
        when:
        def response = provider.deleteHostRecord(new NetworkPool(externalId: 'pool-1'), new NetworkPoolIp(), true)

        then:
        0 * provider.getScvmmOpts(_, _)
        0 * apiService.releaseIPAddress(_, _, _)

        and:
        response.success
    }

    def "deleteHostRecord surfaces an SCVMM revoke failure"() {
        given:
        def pool = new NetworkPool(externalId: 'pool-1')
        def poolIp = new NetworkPoolIp(externalId: 'ip-99', ipAddress: '10.0.0.5')

        when:
        def response = provider.deleteHostRecord(pool, poolIp, true)

        then:
        1 * provider.getScvmmOpts(pool, null) >> SCVMM_OPTS
        1 * apiService.releaseIPAddress(SCVMM_OPTS, 'pool-1', 'ip-99') >> [success: false]

        and:
        !response.success
        response.msg.contains('Unable to release IP address 10.0.0.5')
    }

    def "getScvmmOpts ignores pools that are not scoped to a cloud"() {
        expect:
        provider.getScvmmOpts(new NetworkPool(refType: refType, refId: refId), null) == null

        where:
        refType             | refId
        'NetworkPoolServer' | '7'
        'ComputeZone'       | null
        null                | null
    }

    def "resolveCloudId prefers the pool's cloud ref and falls back to the pool server"() {
        expect:
        ScvmmIPAMProvider.resolveCloudId(pool, poolServer) == expected

        where:
        pool                                                                              | poolServer                                      | expected
        new NetworkPool(refType: 'ComputeZone', refId: '5')                               | null                                            | 5L
        new NetworkPool(refType: 'ComputeZone', refId: '5')                               | new NetworkPoolServer(internalId: 'scvmm-ipam.9') | 5L
        new NetworkPool()                                                                 | new NetworkPoolServer(internalId: 'scvmm-ipam.9') | 9L
        new NetworkPool(poolServer: new NetworkPoolServer(internalId: 'scvmm-ipam.11'))   | null                                            | 11L
        new NetworkPool()                                                                 | new NetworkPoolServer(internalId: 'other.9')    | null
        new NetworkPool()                                                                 | null                                            | null
    }

    // --- controller resolution ---

    MorpheusServices services = Mock(MorpheusServices)
    MorpheusSynchronousCloudService cloudService = Mock(MorpheusSynchronousCloudService)
    MorpheusSynchronousComputeServerService computeServerService = Mock(MorpheusSynchronousComputeServerService)

    private void wireServices() {
        context.getServices() >> services
        services.getCloud() >> cloudService
        services.getComputeServer() >> computeServerService
    }

    private static Cloud cloudWith(Map config) {
        def cloud = new Cloud(id: 5L)
        config.each { k, v -> cloud.setConfigProperty(k as String, v) }
        cloud
    }

    def "getScvmmController returns the shared controller when the cloud references one"() {
        given:
        wireServices()
        def shared = new ComputeServer(id: 77L)

        when:
        def result = provider.getScvmmController(cloudWith(sharedController: '77'))

        then:
        1 * computeServerService.get(77L) >> shared
        0 * computeServerService.find(_)
        result.is(shared)
    }

    def "getScvmmController falls back to the cloud's own controller when #reason"() {
        given:
        wireServices()
        def own = new ComputeServer(id: 3L)
        DataQuery captured

        when:
        def result = provider.getScvmmController(cloudWith(config))

        then:
        getCalls * computeServerService.get(_) >> null
        1 * computeServerService.find(_) >> { DataQuery q -> captured = q; own }
        result.is(own)
        captured.filters.find { it.name == 'zone.id' }.value == 5L
        captured.filters.find { it.name == 'computeServerType.code' }.value == 'scvmmController'
        captured.joins == ['computeServerType']

        where:
        reason                               | config                    | getCalls
        'no shared controller is configured' | [:]                       | 0
        'the shared controller is missing'   | [sharedController: '77'] | 1
    }

    def "getScvmmController returns null when the cloud has no controller"() {
        given:
        wireServices()
        computeServerService.find(_) >> null

        expect:
        provider.getScvmmController(cloudWith([:])) == null
    }

    def "getScvmmOpts builds zone+hypervisor opts from the pool's cloud and its shared controller"() {
        given:
        wireServices()
        def cloud = cloudWith(sharedController: '77')
        def shared = new ComputeServer(id: 77L)

        when:
        def result = provider.getScvmmOpts(new NetworkPool(refType: 'ComputeZone', refId: '5'), null)

        then:
        1 * cloudService.get(5L) >> cloud
        1 * computeServerService.get(77L) >> shared
        1 * apiService.getScvmmZoneAndHypervisorOpts(context, cloud, shared) >> SCVMM_OPTS
        result == SCVMM_OPTS
    }

    def "getScvmmOpts returns null when #reason"() {
        given:
        wireServices()
        cloudService.get(5L) >> cloud
        computeServerService.find(_) >> null

        when:
        def result = provider.getScvmmOpts(new NetworkPool(refType: 'ComputeZone', refId: '5'), null)

        then:
        result == null
        0 * apiService.getScvmmZoneAndHypervisorOpts(*_)

        where:
        reason                          | cloud
        'the cloud no longer exists'    | null
        'the cloud has no controller'   | new Cloud(id: 5L)
    }
}
