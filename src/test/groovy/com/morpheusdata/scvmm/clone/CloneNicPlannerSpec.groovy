// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.clone

import spock.lang.Specification

/**
 * MORPH-20363: a clone copies the source VM's adapters, so the requested NIC list is reconciled against them by
 * slot order (as VMware does): copied adapter i takes request entry i, extra entries are added, surplus copies
 * are removed. A copied NIC carries no state Morpheus keeps (MAC and IP are reset), so no identity is needed.
 */
class CloneNicPlannerSpec extends Specification {

    private static Map adapter(String id, slot) {
        [ID: id, SlotId: slot, VMNetwork: 'cxo-1', MacAddress: '00:15:5D:00:00:0' + slot]
    }

    private static Map nic(String net, Map extra = [:]) {
        [network: [externalId: net], vlanId: 0] + extra
    }

    private static Map config(Map primary, List<Map> extras = []) {
        [primaryInterface: primary, extraInterfaces: extras]
    }

    def "same NIC count reconfigures each copied adapter in slot order"() {
        when:
        def plan = CloneNicPlanner.build([adapter('a1', 1), adapter('a0', 0)], config(nic('n0'), [nic('n1')]))

        then:
        plan.success
        plan.updates*.adapterId == ['a0', 'a1']
        plan.updates*.slotId == [0, 1]
        plan.updates*.index == [0, 1]
        plan.updates*.iface*.network*.externalId == ['n0', 'n1']
        plan.adds == []
        plan.removes == []
        plan.requested.size() == 2
    }

    def "NICs added in the wizard are planned as adds after every copied adapter"() {
        when:
        def plan = CloneNicPlanner.build([adapter('a0', 0)], config(nic('n0'), [nic('n1'), nic('n2', [ipMode: 'dhcp'])]))

        then:
        plan.success
        plan.updates*.adapterId == ['a0']
        plan.adds*.index == [1, 2]
        plan.adds*.iface*.network*.externalId == ['n1', 'n2']
        plan.removes == []
    }

    def "NICs removed in the wizard leave the trailing copies to remove"() {
        when:
        def plan = CloneNicPlanner.build([adapter('a0', 0), adapter('a1', 1), adapter('a2', 2)], config(nic('n0')))

        then:
        plan.success
        plan.updates*.adapterId == ['a0']
        plan.adds == []
        plan.removes*.adapterId == ['a1', 'a2']
        plan.removes*.slotId == [1, 2]
    }

    def "removing a middle NIC moves the next request entry onto the middle slot, so guest eth order still matches"() {
        given: 'source NICs on n0, n1, n2; the wizard drops the n1 NIC'
        def source = [adapter('a0', 0), adapter('a1', 1), adapter('a2', 2)]

        when:
        def plan = CloneNicPlanner.build(source, config(nic('n0'), [nic('n2')]))

        then:
        plan.updates*.adapterId == ['a0', 'a1']
        plan.updates*.iface*.network*.externalId == ['n0', 'n2']
        plan.removes*.adapterId == ['a2']
    }

    def "string SlotIds are ordered numerically"() {
        when:
        def plan = CloneNicPlanner.build([adapter('a10', '10'), adapter('a2', '2')], config(nic('n0'), [nic('n1')]))

        then:
        plan.updates*.adapterId == ['a2', 'a10']
    }

    def "a source with no adapters plans every requested NIC as an add"() {
        when:
        def plan = CloneNicPlanner.build([], config(nic('n0')))

        then:
        plan.success
        plan.updates == []
        plan.adds*.index == [0]
    }

    def "an unreadable source adapter list fails fast instead of guessing"() {
        when:
        def plan = CloneNicPlanner.build(null, config(nic('n0')))

        then:
        !plan.success
        plan.error.contains('network adapters')
    }

    def "a request without a primary interface fails fast"() {
        when:
        def plan = CloneNicPlanner.build([adapter('a0', 0)], [extraInterfaces: [nic('n1')]])

        then:
        !plan.success
        plan.error
    }

    def "extra interfaces without a network are ignored, matching provisioning"() {
        when:
        def plan = CloneNicPlanner.build([adapter('a0', 0)], config(nic('n0'), [[network: null], nic('n1')]))

        then:
        plan.requested.size() == 2
        plan.adds*.iface*.network*.externalId == ['n1']
        plan.adds*.index == [1]
    }

    def "more than the supported NIC count fails fast"() {
        given:
        def extras = (1..8).collect { nic("n${it}") }

        when:
        def plan = CloneNicPlanner.build([adapter('a0', 0)], config(nic('n0'), extras))

        then:
        !plan.success
        plan.error.contains('8')
    }
}
