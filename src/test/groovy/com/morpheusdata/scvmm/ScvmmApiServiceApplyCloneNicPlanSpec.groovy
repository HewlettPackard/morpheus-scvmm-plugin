// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.scvmm.clone.CloneNicPlanner
import spock.lang.Specification
import spock.lang.Subject

/**
 * MORPH-20363 (NicCloneAspect, before-start phase): after the clone exists and before its first start, copies the wizard dropped are removed and NICs
 * the wizard added are created (the VM is off, so this works on Generation 1 too). A NIC that can't be added
 * doesn't fail the clone; it is reported back so the provider can drop its Morpheus row and release its lease.
 */
class ScvmmApiServiceApplyCloneNicPlanSpec extends Specification {

    static final String CLONE_VM = 'clone-vm'
    static final String NET0 = '11111111-1111-1111-1111-111111111111'
    static final String NET1 = '22222222-2222-2222-2222-222222222222'
    static final String SUBNET1 = '33333333-3333-3333-3333-333333333333'
    static final String POOL1 = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

    Map opts = [sshHost: 'scvmm.local']

    @Subject
    ScvmmApiService service

    def setup() {
        service = Spy(ScvmmApiService, constructorArgs: [Mock(MorpheusContext)])
    }

    private static Map nic(String net, Map extra = [:]) {
        [network: [externalId: net], vlanId: 0] + extra
    }

    private static Map details(List<Map> adapters) {
        [success: true, server: [ID: CLONE_VM, NetworkAdapters: adapters]]
    }

    private static Map cloneAdapter(String id, slot, String mac = null) {
        [ID: id, SlotId: slot, MacAddress: mac]
    }

    def "removes the clone's copies of the adapters the wizard dropped, located by slot on the clone"() {
        given: 'source adapters src-0/src-1/src-2; the clone has its own adapter IDs in the same slots'
        def plan = CloneNicPlanner.build([[ID: 'src-0', SlotId: 0], [ID: 'src-1', SlotId: 1], [ID: 'src-2', SlotId: 2]], [primaryInterface: nic(NET0)])
        service.getServerDetails(opts, CLONE_VM) >>> [
                details([cloneAdapter('c-0', 0), cloneAdapter('c-1', 1), cloneAdapter('c-2', 2)]),
                details([cloneAdapter('c-0', 0)])]

        when:
        def rtn = service.applyCloneNicPlan(opts, CLONE_VM, plan)

        then:
        1 * service.removeNetworkInterface(opts, CLONE_VM, { it.adapterId == 'c-1' }) >> [success: true]
        1 * service.removeNetworkInterface(opts, CLONE_VM, { it.adapterId == 'c-2' }) >> [success: true]
        0 * service.addNetworkInterface(*_)
        rtn.success
        rtn.bound == [0: 'c-0']
        rtn.adapters*.ID == ['c-0']
        rtn.failedAdds == []
        rtn.warnings == []
    }

    def "creates the NICs added in the wizard on their own networks, subnets and VLANs"() {
        given:
        def plan = CloneNicPlanner.build([[ID: 'src-0', SlotId: 0]],
                [primaryInterface: nic(NET0), extraInterfaces: [nic(NET1, [subnet: [externalId: SUBNET1], vlanId: 42, ipMode: 'dhcp'])]])
        service.getServerDetails(opts, CLONE_VM) >> details([cloneAdapter('c-0', 0), cloneAdapter('c-new', 1)])

        when:
        def rtn = service.applyCloneNicPlan(opts, CLONE_VM, plan)

        then:
        1 * service.addNetworkInterface(opts, CLONE_VM, { it.networkExternalId == NET1 && it.subnetExternalId == SUBNET1 && it.vlanEnabled == true && it.vlanId == 42 }) >>
                [success: true, adapterId: 'c-new', slotId: 1]
        0 * service.updateNetworkInterface(*_)
        rtn.success
        rtn.bound == [0: 'c-0', 1: 'c-new']
        rtn.adapters*.ID == ['c-0', 'c-new']
        rtn.failedAdds == []
    }

    def "an added NIC on an SCVMM pool is bound to the IP core leased for it"() {
        given:
        def poolNic = nic(NET1, [doStatic: true, ipMode: 'static', poolType: 'scvmm', ipAddress: '10.0.0.20', networkPool: [externalId: POOL1]])
        def plan = CloneNicPlanner.build([[ID: 'src-0', SlotId: 0]], [primaryInterface: nic(NET0), extraInterfaces: [poolNic]])
        service.getServerDetails(opts, CLONE_VM) >> details([cloneAdapter('c-0', 0), cloneAdapter('c-new', 1)])

        when:
        def rtn = service.applyCloneNicPlan(opts, CLONE_VM, plan)

        then:
        1 * service.addNetworkInterface(opts, CLONE_VM, _) >> [success: true, adapterId: 'c-new', slotId: 1]
        1 * service.updateNetworkInterface(opts, CLONE_VM, { it.adapterId == 'c-new' && it.ipv4AddressType == 'Static' && it.ipAddress == '10.0.0.20' && it.poolExternalId == POOL1 && it.networkExternalId == NET1 }) >>
                [success: true]
        rtn.success
        rtn.failedAdds == []
    }

    def "a NIC that SCVMM refuses to add is reported, and the remaining adds still run"() {
        given:
        def plan = CloneNicPlanner.build([[ID: 'src-0', SlotId: 0]], [primaryInterface: nic(NET0), extraInterfaces: [nic(NET1), nic(NET0)]])
        service.getServerDetails(opts, CLONE_VM) >> details([cloneAdapter('c-0', 0), cloneAdapter('c-2', 1)])

        when:
        def rtn = service.applyCloneNicPlan(opts, CLONE_VM, plan)

        then:
        1 * service.addNetworkInterface(opts, CLONE_VM, { it.networkExternalId == NET1 }) >> [success: false, error: 'Error ID: 25246 no virtual switch']
        1 * service.addNetworkInterface(opts, CLONE_VM, { it.networkExternalId == NET0 }) >> [success: true, adapterId: 'c-2', slotId: 1]
        rtn.success
        rtn.failedAdds*.index == [1]
        rtn.failedAdds[0].error.contains('25246')
        rtn.bound == [0: 'c-0', 2: 'c-2']
        rtn.adapters*.ID == ['c-0', 'c-2']
    }

    def "an added pool NIC whose IP can't be bound is removed again and reported"() {
        given:
        def poolNic = nic(NET1, [doStatic: true, ipMode: 'static', poolType: 'scvmm', ipAddress: '10.0.0.20', networkPool: [externalId: POOL1]])
        def plan = CloneNicPlanner.build([[ID: 'src-0', SlotId: 0]], [primaryInterface: nic(NET0), extraInterfaces: [poolNic]])
        service.getServerDetails(opts, CLONE_VM) >> details([cloneAdapter('c-0', 0)])

        when:
        def rtn = service.applyCloneNicPlan(opts, CLONE_VM, plan)

        then:
        1 * service.addNetworkInterface(opts, CLONE_VM, _) >> [success: true, adapterId: 'c-new', slotId: 1]
        1 * service.updateNetworkInterface(opts, CLONE_VM, _) >> [success: false, error: 'address already allocated']
        1 * service.removeNetworkInterface(opts, CLONE_VM, { it.adapterId == 'c-new' }) >> [success: true]
        rtn.success
        rtn.failedAdds*.index == [1]
        rtn.bound == [0: 'c-0']
        rtn.adapters*.ID == ['c-0']
    }

    def "an added pool NIC that can't be bound or rolled back stays unbound and is reported"() {
        given:
        def poolNic = nic(NET1, [doStatic: true, ipMode: 'static', poolType: 'scvmm', ipAddress: '10.0.0.20', networkPool: [externalId: POOL1]])
        def plan = CloneNicPlanner.build([[ID: 'src-0', SlotId: 0]], [primaryInterface: nic(NET0), extraInterfaces: [poolNic, nic(NET0)]])
        service.getServerDetails(opts, CLONE_VM) >> details([cloneAdapter('c-0', 0), cloneAdapter('c-stuck', 1), cloneAdapter('c-2', 2)])

        when:
        def rtn = service.applyCloneNicPlan(opts, CLONE_VM, plan)

        then:
        1 * service.addNetworkInterface(opts, CLONE_VM, { it.networkExternalId == NET1 }) >> [success: true, adapterId: 'c-stuck', slotId: 1]
        1 * service.updateNetworkInterface(opts, CLONE_VM, _) >> [success: false, error: 'address already allocated']
        1 * service.removeNetworkInterface(opts, CLONE_VM, { it.adapterId == 'c-stuck' }) >> [success: false, error: 'locked']
        1 * service.addNetworkInterface(opts, CLONE_VM, { it.networkExternalId == NET0 }) >> [success: true, adapterId: 'c-2', slotId: 2]
        rtn.success
        rtn.failedAdds*.index == [1]
        rtn.bound == [0: 'c-0', 2: 'c-2']
        rtn.warnings.size() == 1
        rtn.warnings[0].contains('locked')
    }

    def "a copied adapter is bound by the slot it was planned on, even when the source's slots have a gap"() {
        given: 'source adapters in slots 0 and 2; the wizard adds one NIC, which VMM may place in free slot 1'
        def plan = CloneNicPlanner.build([[ID: 'src-0', SlotId: 0], [ID: 'src-2', SlotId: 2]], [primaryInterface: nic(NET0), extraInterfaces: [nic(NET1), nic(NET0)]])
        service.getServerDetails(opts, CLONE_VM) >>> [details([cloneAdapter('c-0', 0), cloneAdapter('c-2', 2)]),
                                                      details([cloneAdapter('c-0', 0), cloneAdapter('c-new', 1), cloneAdapter('c-2', 2)])]

        when:
        def rtn = service.applyCloneNicPlan(opts, CLONE_VM, plan)

        then:
        1 * service.addNetworkInterface(opts, CLONE_VM, _) >> [success: true, adapterId: 'c-new', slotId: 1]
        rtn.bound == [0: 'c-0', 1: 'c-2', 2: 'c-new']
    }

    def "a dropped copy that can't be removed is a warning, not a failure"() {
        given:
        def plan = CloneNicPlanner.build([[ID: 'src-0', SlotId: 0], [ID: 'src-1', SlotId: 1]], [primaryInterface: nic(NET0)])
        service.getServerDetails(opts, CLONE_VM) >> details([cloneAdapter('c-0', 0), cloneAdapter('c-1', 1)])

        when:
        def rtn = service.applyCloneNicPlan(opts, CLONE_VM, plan)

        then:
        1 * service.removeNetworkInterface(opts, CLONE_VM, { it.adapterId == 'c-1' }) >> [success: false, error: 'locked']
        rtn.success
        rtn.warnings.size() == 1
        rtn.warnings[0].contains('locked')
    }

    def "nothing is changed when the clone already matches the request"() {
        given:
        def plan = CloneNicPlanner.build([[ID: 'src-0', SlotId: 0]], [primaryInterface: nic(NET0)])
        service.getServerDetails(opts, CLONE_VM) >> details([cloneAdapter('c-0', 0, '00:15:5D:00:00:01')])

        when:
        def rtn = service.applyCloneNicPlan(opts, CLONE_VM, plan)

        then:
        0 * service.removeNetworkInterface(*_)
        0 * service.addNetworkInterface(*_)
        rtn.success
        rtn.adapters == [cloneAdapter('c-0', 0, '00:15:5D:00:00:01')]
    }

    def "the clone's adapters are returned in slot order"() {
        given:
        def plan = CloneNicPlanner.build([[ID: 'src-0', SlotId: 0], [ID: 'src-1', SlotId: 1]], [primaryInterface: nic(NET0), extraInterfaces: [nic(NET1)]])
        service.getServerDetails(opts, CLONE_VM) >> details([cloneAdapter('c-1', '1'), cloneAdapter('c-0', '0')])

        expect:
        service.applyCloneNicPlan(opts, CLONE_VM, plan).adapters*.ID == ['c-0', 'c-1']
    }

    def "an unreadable clone is reported instead of guessing"() {
        given:
        def plan = CloneNicPlanner.build([[ID: 'src-0', SlotId: 0]], [primaryInterface: nic(NET0), extraInterfaces: [nic(NET1)]])
        service.getServerDetails(opts, CLONE_VM) >> [success: false, error: 'VM_NOT_FOUND']

        when:
        def rtn = service.applyCloneNicPlan(opts, CLONE_VM, plan)

        then:
        !rtn.success
        rtn.error.contains('VM_NOT_FOUND')
    }
}
