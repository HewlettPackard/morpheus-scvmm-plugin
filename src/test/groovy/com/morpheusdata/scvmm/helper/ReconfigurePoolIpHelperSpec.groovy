// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.helper

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.network.MorpheusNetworkPoolIpService
import com.morpheusdata.core.network.MorpheusNetworkPoolService
import com.morpheusdata.core.network.MorpheusNetworkService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkPoolIpService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkPoolService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkDomain
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.NetworkPoolIp
import com.morpheusdata.model.NetworkPoolType
import com.morpheusdata.scvmm.ScvmmApiService
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Subject

class ReconfigurePoolIpHelperSpec extends Specification {

    static final Map OPTS = [sshHost: 'scvmm.local']

    MorpheusContext context = Mock()
    MorpheusServices services = Mock()
    MorpheusSynchronousNetworkService syncNetworkService = Mock()
    MorpheusSynchronousNetworkPoolService syncPoolService = Mock()
    MorpheusSynchronousNetworkPoolIpService syncPoolIpService = Mock()
    MorpheusAsyncServices asyncServices = Mock()
    MorpheusNetworkService asyncNetworkService = Mock()
    MorpheusNetworkPoolService asyncPoolService = Mock()
    MorpheusNetworkPoolIpService asyncPoolIpService = Mock()
    ScvmmApiService apiService = Mock()

    @Subject
    ReconfigurePoolIpHelper helper

    NetworkPool scvmmPool = new NetworkPool(id: 5L, externalId: 'pool-ext', name: 'Pool A', type: new NetworkPoolType(code: 'scvmm'),
            gateway: '10.0.0.1', netmask: '255.255.255.0', dnsServers: ['10.0.0.2', '10.0.0.3'])
    Network network = new Network(id: 20L, externalId: 'net-ext', gateway: '10.0.9.1', netmask: '255.255.0.0', dnsPrimary: '8.8.8.8')
    ComputeServer server = new ComputeServer(id: 100L, name: 'vm01', hostname: 'vm01')
    ComputeServerInterface iface = new ComputeServerInterface(id: 7L, name: 'eth1', externalId: 'adapter-7', macAddress: '00:15:5D:00:00:07')

    def setup() {
        context.services >> services
        services.network >> syncNetworkService
        syncNetworkService.pool >> syncPoolService
        syncPoolService.poolIp >> syncPoolIpService
        context.async >> asyncServices
        asyncServices.network >> asyncNetworkService
        asyncNetworkService.pool >> asyncPoolService
        asyncPoolService.poolIp >> asyncPoolIpService
        helper = new ReconfigurePoolIpHelper(context, apiService)
    }

    // ---------------------------------------------------------------- resolveScvmmPool

    def "resolveScvmmPool returns null when the network has no pool"() {
        expect:
        helper.resolveScvmmPool(new Network(id: 1L)) == null
        helper.resolveScvmmPool(null) == null
    }

    def "resolveScvmmPool uses the attached pool when it is already fully loaded"() {
        given:
        network.pool = scvmmPool

        when:
        NetworkPool rtn = helper.resolveScvmmPool(network)

        then:
        0 * syncPoolService.get(_)
        rtn.is(scvmmPool)
    }

    def "resolveScvmmPool reloads a pool that only carries an id"() {
        given:
        network.pool = new NetworkPool(id: 5L)

        when:
        NetworkPool rtn = helper.resolveScvmmPool(network)

        then:
        1 * syncPoolService.get(5L) >> scvmmPool
        rtn.is(scvmmPool)
    }

    def "resolveScvmmPool returns null for a non-scvmm pool or a pool without an external id"() {
        given:
        network.pool = pool

        expect:
        helper.resolveScvmmPool(network) == null

        where:
        pool << [
                new NetworkPool(id: 6L, externalId: 'x', type: new NetworkPoolType(code: 'infoblox')),
                new NetworkPool(id: 7L, type: new NetworkPoolType(code: 'scvmm'))
        ]
    }

    def "resolveScvmmPool survives a failing reload"() {
        given:
        network.pool = new NetworkPool(id: 5L)

        when:
        NetworkPool rtn = helper.resolveScvmmPool(network)

        then:
        1 * syncPoolService.get(5L) >> { throw new RuntimeException('db down') }
        rtn == null
    }

    // ---------------------------------------------------------------- shouldLeaseFromPool

    def "shouldLeaseFromPool is true for a pool unless the row is explicitly dhcp"() {
        expect:
        ReconfigurePoolIpHelper.shouldLeaseFromPool(pool, ipMode) == expected

        where:
        pool                    | ipMode   || expected
        null                    | null     || false
        null                    | 'pool'   || false
        new NetworkPool(id: 1L) | null     || true
        new NetworkPool(id: 1L) | 'pool'   || true
        new NetworkPool(id: 1L) | 'static' || true
        new NetworkPool(id: 1L) | 'dhcp'   || false
    }

    // ---------------------------------------------------------------- leasePoolIp

    def "leasePoolIp grants the next free address and builds an unpersisted NetworkPoolIp attached to the NIC"() {
        given:
        network.networkDomain = new NetworkDomain(name: 'corp.local')

        when:
        Map rtn = helper.leasePoolIp(OPTS, scvmmPool, network, server, iface)

        then:
        1 * apiService.reserveIPAddress(OPTS, 'pool-ext', null) >> [success: true, ipAddress: [ID: 'ip-guid', Address: '10.0.0.50']]
        0 * asyncPoolIpService.create(*_)
        rtn.success
        with(rtn.poolIp as NetworkPoolIp) {
            networkPool.is(scvmmPool)
            ipAddress == '10.0.0.50'
            externalId == 'ip-guid'
            staticIp
            ipType == 'assigned'
            gatewayAddress == '10.0.0.1'
            subnetMask == '255.255.255.0'
            dnsServer == '10.0.0.2,10.0.0.3'
            interfaceName == 'eth1'
            macAddress == '00:15:5D:00:00:07'
            hostname == 'vm01'
            fqdn == 'vm01.corp.local'
            domainName == 'corp.local'
            refType == 'ComputeServer'
            refId == 100L
            subRefId == 7L
            startDate != null
        }
    }

    def "leasePoolIp asks SCVMM for the specific address when one is requested"() {
        when:
        Map rtn = helper.leasePoolIp(OPTS, scvmmPool, network, server, iface, '10.0.0.77')

        then:
        1 * apiService.reserveIPAddress(OPTS, 'pool-ext', '10.0.0.77') >> [success: true, ipAddress: [ID: 'ip-77', Address: '10.0.0.77']]
        rtn.success
        rtn.poolIp.ipAddress == '10.0.0.77'
    }

    def "leasePoolIp falls back to the network's gateway / netmask / dns when the pool has none"() {
        given:
        NetworkPool bare = new NetworkPool(id: 5L, externalId: 'pool-ext', type: new NetworkPoolType(code: 'scvmm'))

        when:
        Map rtn = helper.leasePoolIp(OPTS, bare, network, server, iface)

        then:
        1 * apiService.reserveIPAddress(*_) >> [success: true, ipAddress: [ID: 'ip-1', Address: '10.0.0.5']]
        rtn.poolIp.gatewayAddress == '10.0.9.1'
        rtn.poolIp.subnetMask == '255.255.0.0'
        rtn.poolIp.dnsServer == '8.8.8.8'
    }

    def "leasePoolIp reports a failure when SCVMM cannot grant an address"() {
        when:
        Map rtn = helper.leasePoolIp(OPTS, scvmmPool, network, server, iface, requested)

        then:
        1 * apiService.reserveIPAddress(OPTS, 'pool-ext', requested) >> result
        !rtn.success
        rtn.poolIp == null
        rtn.error == expectedError

        where:
        requested   | result                                        || expectedError
        null        | [success: false]                              || 'Unable to reserve an IP address from SCVMM pool Pool A'
        '10.0.0.77' | [success: false]                              || 'Unable to reserve IP address 10.0.0.77 from SCVMM pool Pool A'
        null        | [success: false, msg: 'pool exhausted']       || 'pool exhausted'
        null        | [success: true, ipAddress: []]                || 'Unable to reserve an IP address from SCVMM pool Pool A'
    }

    def "leasePoolIp turns an exception into an error result"() {
        when:
        Map rtn = helper.leasePoolIp(OPTS, scvmmPool, network, server, iface)

        then:
        1 * apiService.reserveIPAddress(*_) >> { throw new RuntimeException('winrm timeout') }
        !rtn.success
        rtn.error == 'Error reserving an IP address from SCVMM: winrm timeout'
    }

    // ---------------------------------------------------------------- persistLease / revokeLease

    def "persistLease creates the record under its pool"() {
        given:
        NetworkPoolIp poolIp = new NetworkPoolIp(networkPool: scvmmPool, ipAddress: '10.0.0.50')

        when:
        boolean rtn = helper.persistLease(poolIp)

        then:
        1 * asyncPoolIpService.create(scvmmPool, [poolIp]) >> Single.just(true)
        rtn
    }

    def "persistLease returns false on failure instead of throwing"() {
        given:
        NetworkPoolIp poolIp = new NetworkPoolIp(networkPool: scvmmPool, ipAddress: '10.0.0.50')

        when:
        boolean rtn = helper.persistLease(poolIp)

        then:
        1 * asyncPoolIpService.create(scvmmPool, [poolIp]) >> Single.error(new RuntimeException('boom'))
        !rtn
    }

    def "revokeLease releases the grant in SCVMM by its id"() {
        given:
        NetworkPoolIp poolIp = new NetworkPoolIp(externalId: 'ip-guid', ipAddress: '10.0.0.50')

        when:
        Map rtn = helper.revokeLease(OPTS, scvmmPool, poolIp)

        then:
        1 * apiService.releaseIPAddress(OPTS, 'pool-ext', 'ip-guid') >> [success: true]
        rtn.success
    }

    def "revokeLease is a no-op for a record without an SCVMM id"() {
        when:
        Map rtn = helper.revokeLease(OPTS, scvmmPool, new NetworkPoolIp(ipAddress: '10.0.0.50'))

        then:
        0 * apiService.releaseIPAddress(*_)
        rtn.success
    }

    def "revokeLease surfaces SCVMM failures and exceptions"() {
        given:
        NetworkPoolIp poolIp = new NetworkPoolIp(externalId: 'ip-guid', ipAddress: '10.0.0.50')

        when:
        Map rtn = helper.revokeLease(OPTS, scvmmPool, poolIp)

        then:
        1 * apiService.releaseIPAddress(*_) >> { if (result instanceof Throwable) throw result; result }
        !rtn.success
        rtn.error == expectedError

        where:
        result                                  || expectedError
        [success: false]                        || 'Unable to release IP address 10.0.0.50 back to SCVMM pool pool-ext'
        [success: false, msg: 'revoke failed']  || 'revoke failed'
        new RuntimeException('winrm timeout')   || 'Error releasing an IP address back to SCVMM: winrm timeout'
    }

    // ---------------------------------------------------------------- findLeases / releaseLeases

    def "findLeases queries by refType / server id / interface id"() {
        given:
        NetworkPoolIp lease = new NetworkPoolIp(id: 1L)
        DataQuery captured

        when:
        List<NetworkPoolIp> rtn = helper.findLeases(server, iface)

        then:
        1 * syncPoolIpService.list(_ as DataQuery) >> { DataQuery q -> captured = q; [lease] }
        rtn == [lease]
        captured.filters.find { it.name == 'refType' }.value == 'ComputeServer'
        captured.filters.find { it.name == 'refId' }.value == 100L
        captured.filters.find { it.name == 'subRefId' }.value == 7L
    }

    def "findLeases returns an empty list when the server or interface is unsaved or the query fails"() {
        when:
        List<NetworkPoolIp> a = helper.findLeases(new ComputeServer(), iface)
        List<NetworkPoolIp> b = helper.findLeases(server, new ComputeServerInterface())
        List<NetworkPoolIp> c = helper.findLeases(server, iface)

        then:
        1 * syncPoolIpService.list(_ as DataQuery) >> { throw new RuntimeException('db down') }
        a == []
        b == []
        c == []
    }

    def "releaseLeases revokes each grant in SCVMM and removes the Morpheus record"() {
        given:
        NetworkPoolIp lease1 = new NetworkPoolIp(id: 1L, externalId: 'ip-1', ipAddress: '10.0.0.1', networkPool: new NetworkPool(id: 5L))
        NetworkPoolIp lease2 = new NetworkPoolIp(id: 2L, externalId: 'ip-2', ipAddress: '10.0.0.2', networkPool: new NetworkPool(id: 5L))

        when:
        Map rtn = helper.releaseLeases(OPTS, [lease1, lease2])

        then:
        2 * syncPoolService.get(5L) >> scvmmPool
        1 * apiService.releaseIPAddress(OPTS, 'pool-ext', 'ip-1') >> [success: true]
        1 * apiService.releaseIPAddress(OPTS, 'pool-ext', 'ip-2') >> [success: true]
        1 * asyncPoolIpService.remove(5L, [lease1]) >> Single.just(true)
        1 * asyncPoolIpService.remove(5L, [lease2]) >> Single.just(true)
        rtn.success
        rtn.released == 2
    }

    def "releaseLeases keeps the Morpheus record when the SCVMM revoke fails so teardown can retry"() {
        given:
        NetworkPoolIp lease = new NetworkPoolIp(id: 1L, externalId: 'ip-1', ipAddress: '10.0.0.1', networkPool: scvmmPool)

        when:
        Map rtn = helper.releaseLeases(OPTS, [lease])

        then:
        1 * syncPoolService.get(5L) >> scvmmPool
        1 * apiService.releaseIPAddress(OPTS, 'pool-ext', 'ip-1') >> [success: false]
        0 * asyncPoolIpService.remove(*_)
        !rtn.success
        rtn.released == 0
        rtn.error == 'Unable to release IP address 10.0.0.1 back to SCVMM pool pool-ext'
    }

    def "releaseLeases skips records that belong to a non-scvmm pool"() {
        given:
        NetworkPool infoblox = new NetworkPool(id: 9L, externalId: 'ib', type: new NetworkPoolType(code: 'infoblox'))
        NetworkPoolIp lease = new NetworkPoolIp(id: 1L, externalId: 'ip-1', ipAddress: '10.0.0.1', networkPool: infoblox)

        when:
        Map rtn = helper.releaseLeases(OPTS, [lease])

        then:
        1 * syncPoolService.get(9L) >> infoblox
        0 * apiService.releaseIPAddress(*_)
        0 * asyncPoolIpService.remove(*_)
        rtn.success
        rtn.released == 0
    }

    def "releaseLeases reports a failure when the record cannot be removed after a successful revoke"() {
        given:
        NetworkPoolIp lease = new NetworkPoolIp(id: 1L, externalId: 'ip-1', ipAddress: '10.0.0.1', networkPool: scvmmPool)

        when:
        Map rtn = helper.releaseLeases(OPTS, [lease])

        then:
        1 * syncPoolService.get(5L) >> scvmmPool
        1 * apiService.releaseIPAddress(*_) >> [success: true]
        1 * asyncPoolIpService.remove(5L, [lease]) >> Single.error(new RuntimeException('boom'))
        !rtn.success
        rtn.error == 'Released 10.0.0.1 in SCVMM but failed to remove its Morpheus record'
    }

    def "releaseLeases with a server and interface looks the leases up first"() {
        given:
        NetworkPoolIp lease = new NetworkPoolIp(id: 1L, externalId: 'ip-1', ipAddress: '10.0.0.1', networkPool: scvmmPool)

        when:
        Map rtn = helper.releaseLeases(OPTS, server, iface)

        then:
        1 * syncPoolIpService.list(_ as DataQuery) >> [lease]
        1 * syncPoolService.get(5L) >> scvmmPool
        1 * apiService.releaseIPAddress(OPTS, 'pool-ext', 'ip-1') >> [success: true]
        1 * asyncPoolIpService.remove(5L, [lease]) >> Single.just(true)
        rtn.success
        rtn.released == 1
    }

    def "releaseLeases handles an empty or null list"() {
        expect:
        helper.releaseLeases(OPTS, (List) null).success
        helper.releaseLeases(OPTS, []).released == 0
    }
}
