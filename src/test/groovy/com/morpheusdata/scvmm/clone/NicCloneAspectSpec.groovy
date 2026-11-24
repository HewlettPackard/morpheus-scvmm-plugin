// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.clone

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.compute.MorpheusComputeServerInterfaceService
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.Network
import com.morpheusdata.model.ProcessEvent
import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.scvmm.helper.ReconfigurePoolIpHelper
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

/**
 * MORPH-20363: the network aspect of the clone wizard reconciler.
 */
class NicCloneAspectSpec extends Specification {

    static final String NET0 = '11111111-1111-1111-1111-111111111111'
    static final String NET1 = '22222222-2222-2222-2222-222222222222'

    MorpheusContext morpheusContext = Mock()
    MorpheusAsyncServices async = Mock()
    MorpheusComputeServerService asyncServers = Mock()
    MorpheusComputeServerInterfaceService interfaceService = Mock()
    ScvmmApiService apiService = Mock()
    ReconfigurePoolIpHelper poolIpHelper = Mock()
    NicCloneAspect aspect = new NicCloneAspect()

    def setup() {
        morpheusContext.getAsync() >> async
        async.getComputeServer() >> asyncServers
        asyncServers.getComputeServerInterface() >> interfaceService
    }

    private CloneContext ctx(ComputeServer server = null, Map networkConfig = null) {
        new CloneContext(morpheusContext: morpheusContext, apiService: apiService, poolIpHelper: poolIpHelper, scvmmOpts: [sshHost: 'scvmm'],
                server: server, sourceVmId: 'src-vm', cloneVmId: 'clone-vm', networkConfig: networkConfig)
    }

    private static ComputeServerInterface row(long id, int order, String netName, boolean primary = false) {
        new ComputeServerInterface(id: id, name: "eth${order}", displayOrder: order, primaryInterface: primary,
                network: new Network(id: id, name: netName))
    }

    private static Map plan(int sourceCount, int requestedCount) {
        def source = (0..<sourceCount).collect { [ID: "src-${it}", SlotId: it] }
        def extras = (1..<requestedCount).collect { [network: [externalId: NET1], vlanId: 0] }
        CloneNicPlanner.build(source, [primaryInterface: [network: [externalId: NET0], vlanId: 0], extraInterfaces: extras])
    }

    def "is the 'network' aspect and reports in a network History step"() {
        expect:
        aspect.name == 'network'
        aspect.historyType == ProcessEvent.ProcessType.provisionNetwork
        aspect.historyStatus == 'configuring network interfaces'
    }

    // ------------------------------------------------------------------ plan

    def "plans from the source VM's adapters as SCVMM reports them"() {
        given:
        def config = [primaryInterface: [network: [externalId: NET0], vlanId: 0]]

        when:
        def rtn = aspect.plan(ctx(null, config))

        then:
        1 * apiService.getServerDetails([sshHost: 'scvmm'], 'src-vm') >> [success: true, server: [NetworkAdapters: [[ID: 'src-1', SlotId: 1], [ID: 'src-0', SlotId: 0]]]]
        rtn.success
        rtn.updates*.adapterId == ['src-0']
        rtn.removes*.adapterId == ['src-1']
    }

    def "a single adapter reported as an object rather than a list is still planned"() {
        when:
        def rtn = aspect.plan(ctx(null, [primaryInterface: [network: [externalId: NET0]]]))

        then:
        1 * apiService.getServerDetails(_, 'src-vm') >> [success: true, server: [NetworkAdapters: [ID: 'src-0', SlotId: 0]]]
        rtn.updates*.adapterId == ['src-0']
    }

    def "an unreadable source fails the plan with SCVMM's reason"() {
        when:
        def rtn = aspect.plan(ctx(null, [primaryInterface: [network: [externalId: NET0]]]))

        then:
        1 * apiService.getServerDetails(_, 'src-vm') >> [success: false, error: 'VM_NOT_FOUND']
        !rtn.success
        rtn.error.contains('network adapters')
        rtn.error.contains('VM_NOT_FOUND')
    }

    // ------------------------------------------------------------------ before start

    def "applies the plan to the clone through the API service"() {
        given:
        def p = plan(1, 2)

        when:
        def rtn = aspect.applyBeforeStart(ctx(), p)

        then:
        1 * apiService.applyCloneNicPlan([sshHost: 'scvmm'], 'clone-vm', p) >> [success: true, adapters: []]
        rtn.success
    }

    // ------------------------------------------------------------------ records

    def "binds each interface row to the clone adapter in the same position"() {
        given: 'rows arrive out of order; adapters come back in slot order'
        def server = new ComputeServer(id: 10L, interfaces: [row(102, 1, 'cxo-1'), row(101, 0, 'cxo-1', true)])
        def applyResult = [success: true, bound: [0: 'c-0', 1: 'c-1'], adapters: [[ID: 'c-0', SlotId: 0, MacAddress: 'AA'], [ID: 'c-1', SlotId: 1, MacAddress: null]],
                           failedAdds: [], warnings: []]

        when:
        def warnings = aspect.reconcileRecords(ctx(server), plan(1, 2), applyResult)

        then:
        1 * interfaceService.save({ List rows -> rows*.id as Set == [101L, 102L] as Set }) >> Single.just(true)
        warnings == []
        server.interfaces.find { it.id == 101L }.externalId == 'c-0'
        server.interfaces.find { it.id == 101L }.macAddress == 'AA'
        server.interfaces.find { it.id == 102L }.externalId == 'c-1'
    }

    def "a NIC SCVMM could not add is removed from Morpheus, its lease released, and the reason returned"() {
        given:
        def failed = row(102, 1, 'cxo-2')
        def server = new ComputeServer(id: 10L, name: 'vm-clone', interfaces: [row(101, 0, 'cxo-1', true), failed, row(103, 2, 'cxo-1')])
        def applyResult = [success: true, bound: [0: 'c-0', 2: 'c-2'], adapters: [[ID: 'c-0', SlotId: 0], [ID: 'c-2', SlotId: 1]],
                           failedAdds: [[index: 1, error: 'Error ID: 25246 no virtual switch.']], warnings: []]

        when:
        def warnings = aspect.reconcileRecords(ctx(server), plan(1, 3), applyResult)

        then:
        1 * poolIpHelper.releaseLeases([sshHost: 'scvmm'], server, failed) >> [success: true]
        1 * interfaceService.remove([failed], server) >> Single.just(true)
        1 * interfaceService.save(_) >> Single.just(true)
        server.interfaces*.id == [101L, 103L]
        server.interfaces.find { it.id == 101L }.externalId == 'c-0'
        server.interfaces.find { it.id == 103L }.externalId == 'c-2'
        warnings.size() == 1
        warnings[0] == "Network interface 'eth1' (cxo-2) could not be added to the VM: Error ID: 25246 no virtual switch. It has been removed from this instance; add it again with Reconfigure once the issue is resolved."
    }

    def "warnings from removing dropped copies are passed on"() {
        given:
        def server = new ComputeServer(id: 10L, interfaces: [row(101, 0, 'cxo-1', true)])
        def applyResult = [success: true, bound: [0: 'c-0'], adapters: [[ID: 'c-0', SlotId: 0]], failedAdds: [],
                           warnings: ['A network adapter removed from the clone request (slot 1) is still attached to the VM, disconnected: locked']]

        when:
        def warnings = aspect.reconcileRecords(ctx(server), plan(2, 1), applyResult)

        then:
        1 * interfaceService.save(_) >> Single.just(true)
        warnings == ['A network adapter removed from the clone request (slot 1) is still attached to the VM, disconnected: locked']
    }

    def "when the clone's adapters couldn't be read, rows are left for the next sync and the customer is told"() {
        given:
        def server = new ComputeServer(id: 10L, interfaces: [row(101, 0, 'cxo-1', true)])

        when:
        def warnings = aspect.reconcileRecords(ctx(server), plan(1, 1), [success: false, error: 'VM_NOT_FOUND'])

        then:
        0 * interfaceService.save(_)
        0 * interfaceService.remove(*_)
        warnings.size() == 1
        warnings[0].contains('VM_NOT_FOUND')
        warnings[0].contains('next cloud sync')
    }

    def "rows are bound by the adapter ID recorded for their request entry, not by position"() {
        given: 'a stuck adapter from a failed add sits between the copies and a later add'
        def server = new ComputeServer(id: 10L, interfaces: [row(100, 0, 'cxo-1', true), row(101, 1, 'cxo-1'), row(102, 2, 'cxo-2'), row(103, 3, 'cxo-1')])
        def applyResult = [success: true, bound: [0: 'c-0', 1: 'c-1', 3: 'c-3'],
                           adapters: [[ID: 'c-0', SlotId: 0], [ID: 'c-1', SlotId: 1], [ID: 'c-stuck', SlotId: 2], [ID: 'c-3', SlotId: 3, MacAddress: 'DD']],
                           failedAdds: [[index: 2, error: 'unable to assign 10.0.0.20']], warnings: []]

        when:
        aspect.reconcileRecords(ctx(server), plan(2, 4), applyResult)

        then:
        1 * poolIpHelper.releaseLeases(_, server, _) >> [success: true]
        1 * interfaceService.remove(_, server) >> Single.just(true)
        1 * interfaceService.save(_) >> Single.just(true)
        server.interfaces.find { it.id == 103L }.externalId == 'c-3'
        server.interfaces.find { it.id == 103L }.macAddress == 'DD'
        server.interfaces.find { it.id == 101L }.externalId == 'c-1'
    }

    def "a row without a recorded adapter is left for the next sync"() {
        given:
        def server = new ComputeServer(id: 10L, interfaces: [row(101, 0, 'cxo-1', true), row(102, 1, 'cxo-1')])
        def applyResult = [success: true, bound: [0: 'c-0'], adapters: [[ID: 'c-0', SlotId: 0]], failedAdds: [], warnings: []]

        when:
        def warnings = aspect.reconcileRecords(ctx(server), plan(1, 2), applyResult)

        then:
        1 * interfaceService.save({ it*.id == [101L] }) >> Single.just(true)
        server.interfaces.find { it.id == 102L }.externalId == null
        warnings == []
    }

    // ------------------------------------------------------------------ clone job (script shape is covered in
    // ScvmmApiServiceBuildCreateServerCommandsSpec; these pin the per-adapter pieces)

    def "a typed static IP outside an SCVMM pool stays Dynamic in SCVMM (cloud-init sets it in the guest)"() {
        given:
        def p = CloneNicPlanner.build([[ID: 'src-0', SlotId: 0]],
                [primaryInterface: [network: [externalId: NET0], doStatic: true, ipAddress: '10.0.0.9', vlanId: 0]])

        when:
        def commands = aspect.cloneJobCommands(p, 'job-1')

        then:
        commands.find { it.contains('Set-SCVirtualNetworkAdapter ') }.contains('-IPv4AddressType Dynamic')
        !commands.any { it.contains('10.0.0.9') }
    }

    def "every command that can fail exits the clone script with a code"() {
        when:
        def commands = aspect.cloneJobCommands(plan(2, 1), 'job-1')

        then:
        commands.count { it.contains('Exit 24') } == 2
        commands.count { it.contains('Exit 25') } == 1
        commands.count { it.contains('Exit 29') } == 2
    }

    def "no plan produces no commands"() {
        expect:
        aspect.cloneJobCommands(null, 'job-1') == []
        aspect.cloneJobCommands([updates: [], removes: []], 'job-1') == []
    }
}
