// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkPoolService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.NetworkPoolType
import spock.lang.Specification

class ScvmmProvisionProviderNetworkSpec extends Specification {

    def "provision provider advertises multi NIC support up to the Hyper-V synthetic adapter limit"() {
        given:
        def provider = new ScvmmProvisionProvider(Mock(ScvmmPlugin), Mock(MorpheusContext))

        expect:
        provider.getMaxNetworks() == ScvmmConstants.MAX_NETWORKS
        ScvmmConstants.MAX_NETWORKS == 8
    }

    def "resolveInterfacePoolType prefers the pool leased on the interface over network.pool"() {
        given:
        def provider = new ScvmmProvisionProvider(Mock(ScvmmPlugin), Mock(MorpheusContext))
        def networkInterface = [name: 'eth1', doStatic: true, networkType: 'static', ipAddress: '10.157.232.117',
                                network: [id: 7L, externalId: 'e2579373-0bc7-4d81-a418-9f8c3149641b', pool: networkPool],
                                networkPool: interfacePool]

        when:
        provider.resolveInterfacePoolType(networkInterface, 'extra0')

        then:
        networkInterface.poolType == expected

        where:
        interfacePool                       | networkPool                        || expected
        [id: 3L, type: [code: 'scvmm']]     | null                               || 'scvmm'
        [id: 3L, type: [code: 'scvmm']]     | [id: 9L, type: [code: 'infoblox']] || 'scvmm'
        null                                | [id: 9L, type: [code: 'scvmm']]    || 'scvmm'
        null                                | null                               || null
    }

    def "resolveInterfacePoolType keeps a pre-set poolType when no pool is attached"() {
        given:
        def provider = new ScvmmProvisionProvider(Mock(ScvmmPlugin), Mock(MorpheusContext))
        def networkInterface = [name: 'eth0', poolType: 'scvmm', network: [id: 7L]]

        when:
        provider.resolveInterfacePoolType(networkInterface, 'primary')

        then:
        networkInterface.poolType == 'scvmm'
    }

    def "resolveInterfacePoolType loads the pool type from the appliance when the marshalled pool has none"() {
        given:
        def poolService = Mock(MorpheusSynchronousNetworkPoolService)
        def networkService = Mock(MorpheusSynchronousNetworkService) { getPool() >> poolService }
        def services = Mock(MorpheusServices) { getNetwork() >> networkService }
        def context = Mock(MorpheusContext) { getServices() >> services }
        def provider = new ScvmmProvisionProvider(Mock(ScvmmPlugin), context)
        def networkInterface = [name: 'eth1', doStatic: true, ipAddress: '10.157.232.118',
                                networkPool: [id: 1L, externalId: '4784149f-a6ed-4944-8868-9a0df5185f4d']]

        when:
        provider.resolveInterfacePoolType(networkInterface, 'extra0')

        then:
        1 * poolService.get(1L) >> new NetworkPool(id: 1L, type: new NetworkPoolType(code: 'scvmm'))
        networkInterface.poolType == 'scvmm'
    }
}
