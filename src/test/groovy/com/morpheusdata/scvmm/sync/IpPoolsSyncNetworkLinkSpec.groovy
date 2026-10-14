// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.sync

import com.morpheusdata.model.Network
import spock.lang.Specification

/**
 * A static pool on a "No isolation" VM network has no VMSubnet, so SCVMM reports no VM network for it. The pool must
 * still be linked to the Morpheus network through the logical network, otherwise the provision/reconfigure wizards
 * offer only DHCP for that network.
 */
class IpPoolsSyncNetworkLinkSpec extends Specification {

    static final String LN_CXO = 'ln-cxo'
    Network cxo1 = new Network(id: 1L, name: 'cxo-1', externalId: 'e2579373')
    Network other = new Network(id: 2L, name: 'other', externalId: 'vmnet-other')
    List<Map> mapping = [
            [ID: 'e2579373', Name: 'cxo-1', LogicalNetwork: 'cxo-1', LogicalNetworkId: LN_CXO],
            [ID: 'vmnet-other', Name: 'other', LogicalNetwork: 'other', LogicalNetworkId: 'ln-other']
    ]

    def "links directly when the pool reports its VM network"() {
        expect:
        IpPoolsSync.resolveNetworkForPool([cxo1, other], mapping, 'e2579373', null).is(cxo1)
    }

    def "falls back to the logical network when the pool has no VM network (NoIsolation)"() {
        expect:
        IpPoolsSync.resolveNetworkForPool([cxo1, other], mapping, null, LN_CXO).is(cxo1)
    }

    def "does not guess when several VM networks share the logical network"() {
        given:
        def ambiguous = mapping + [[ID: 'vmnet-2', Name: 'cxo-2', LogicalNetwork: 'cxo-1', LogicalNetworkId: LN_CXO]]

        expect:
        IpPoolsSync.resolveNetworkForPool([cxo1, other, new Network(id: 3L, externalId: 'vmnet-2')], ambiguous, null, LN_CXO) == null
    }

    def "returns null when neither id is known"() {
        expect:
        IpPoolsSync.resolveNetworkForPool([cxo1], mapping, netId, lnId) == null

        where:
        netId     | lnId
        null      | null
        'unknown' | null
        null      | 'ln-unknown'
    }

    def "prefers the direct VM network over the logical network"() {
        expect:
        IpPoolsSync.resolveNetworkForPool([cxo1, other], mapping, 'vmnet-other', LN_CXO).is(other)
    }
}
