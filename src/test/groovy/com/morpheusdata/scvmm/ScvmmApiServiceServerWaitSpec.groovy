// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.model.ComputeServer
import com.morpheusdata.scvmm.support.Domain
import com.morpheusdata.scvmm.support.ScvmmSpec
import com.morpheusdata.scvmm.support.VmmPayloads
import spock.lang.Subject

/**
 * checkServerCreated / checkServerReady polling loops. The delay goes through the pause() seam; the loops
 * stop when {@code attempts > 600} / {@code attempts > 300 || notFoundAttempts > 10}, i.e. after 601 / 301 polls.
 */
class ScvmmApiServiceServerWaitSpec extends ScvmmSpec {

    static final String VM_ID = 'vm-ext-1'

    @Subject
    ScvmmApiService svc

    ComputeServer server = Domain.server(id: 10L, externalId: VM_ID)

    def setup() {
        svc = Spy(ScvmmApiService, constructorArgs: [context])
        svc.pause(_) >> null
        svc.refreshVM(_, _) >> [success: true]
        svc.discardSavedState(_, _) >> [success: true]
    }

    private static Map detail(Map overrides = [:]) {
        [success: true, server: VmmPayloads.serverDetail(overrides), networkAdapters: [], error: null]
    }

    private static Map disks(int count, Map overrides = [:]) {
        detail([VirtualDiskDrives: (1..count).collect { "vdd-$it".toString() }] + overrides)
    }

    // ---------------------------------------------------------------- checkServerCreated

    def "checkServerCreated waits until the VM has 1 + dataDisks - additionalTemplateDisks drives"() {
        given:
        Map opts = [dataDisks: [[:], [:]], additionalTemplateDisks: [[:]]]
        Map second = disks(2)

        when:
        def rtn = svc.checkServerCreated(opts, VM_ID)

        then:
        2 * svc.getServerDetails(opts, VM_ID) >>> [disks(1), second]
        2 * svc.pause(5000L) >> null
        rtn.success
        rtn.server.is(second.server)
    }

    def "checkServerCreated expects #expected drive(s) when dataDisks=#dataDisks and additionalTemplateDisks=#templateDisks"() {
        given:
        Map opts = [:]
        if (dataDisks != null) opts.dataDisks = [[:]] * dataDisks
        if (templateDisks != null) opts.additionalTemplateDisks = [[:]] * templateDisks

        when:
        def rtn = svc.checkServerCreated(opts, VM_ID)

        then:
        1 * svc.getServerDetails(_, _) >> disks(expected)
        rtn.success

        where:
        dataDisks | templateDisks | expected
        null      | null          | 1
        1         | null          | 2
        3         | 0             | 4
        3         | 2             | 2
    }

    def "checkServerCreated keeps polling while the VM is UnderCreation even if the drive count matches"() {
        when:
        def rtn = svc.checkServerCreated([:], VM_ID)

        then:
        3 * svc.getServerDetails(_, _) >>> [disks(1, [Status: 'UnderCreation']), disks(1, [Status: 'UnderCreation']), disks(1)]
        rtn.success
    }

    def "checkServerCreated discards a Saved state once the VM is created"() {
        given:
        Map opts = [:]

        when:
        def rtn = svc.checkServerCreated(opts, VM_ID)

        then:
        1 * svc.getServerDetails(_, _) >> disks(1, [Status: 'Saved'])
        1 * svc.discardSavedState(opts, VM_ID) >> [success: true]
        rtn.success
    }

    def "checkServerCreated stops at the first CreationFailed status without an error message"() {
        // plan expected an 'error' key; the code returns [success:false] only
        when:
        def rtn = svc.checkServerCreated([dataDisks: [[:]]], VM_ID)

        then:
        1 * svc.getServerDetails(_, _) >> disks(1, [Status: 'CreationFailed'])
        rtn == [success: false]
    }

    def "checkServerCreated gives up after 601 polls when getServerDetails never succeeds"() {
        when:
        def rtn = svc.checkServerCreated([:], VM_ID)

        then:
        601 * svc.getServerDetails(_, _) >> [success: false, server: null, error: null]
        601 * svc.pause(5000L) >> null
        rtn == [success: false]
    }

    def "checkServerCreated treats a successful lookup with no server payload as not ready"() {
        when:
        def rtn = svc.checkServerCreated([:], VM_ID)

        then:
        2 * svc.getServerDetails(_, _) >>> [[success: true, server: null], disks(1)]
        noExceptionThrown()
        rtn.success
    }

    def "checkServerCreated returns success=false instead of throwing when getServerDetails throws"() {
        when:
        def rtn = svc.checkServerCreated([:], VM_ID)

        then:
        1 * svc.getServerDetails(_, _) >> { throw new RuntimeException('winrm timeout') }
        noExceptionThrown()
        rtn == [success: false]
    }

    // ---------------------------------------------------------------- checkServerReady

    def "checkServerReady returns the adapter IPv4 reported by SCVMM once the VM is Running"() {
        given:
        Map opts = [server: server, waitForIp: true]
        computeServerService.get(10L) >> server
        Map ready = detail(internalIp: '10.0.0.5', ipAddress: '10.0.0.5',
            NetworkAdapters: [VmmPayloads.vmAdapter(IPv4Addresses: ['10.0.0.5'], IPv4AddressType: 'Static')])

        when:
        def rtn = svc.checkServerReady(opts, VM_ID)

        then:
        1 * svc.refreshVM(opts, VM_ID) >> [success: true]
        1 * svc.getServerDetails(opts, VM_ID) >> ready
        rtn.success
        rtn.server.is(ready.server)
        rtn.server.ipAddress == '10.0.0.5'
        server.internalIp == '10.0.0.5'
        opts.server.is(server)
    }

    def "checkServerReady falls back to the Morpheus externalIp when SCVMM reports no guest IP"() {
        given:
        server.externalIp = '10.0.0.9'
        computeServerService.get(10L) >> server
        svc.getServerDetails(_, _) >> detail()

        when:
        def rtn = svc.checkServerReady([server: server, waitForIp: true], VM_ID)

        then:
        rtn.success
        rtn.server.ipAddress == '10.0.0.9'
        server.internalIp == '10.0.0.9'
    }

    def "checkServerReady succeeds without an IP when waitForIp is false and the VM is Running"() {
        given:
        computeServerService.get(10L) >> server

        when:
        def rtn = svc.checkServerReady([server: server, waitForIp: false], VM_ID)

        then:
        1 * svc.getServerDetails(_, _) >> detail(NetworkAdapters: adapters)
        rtn.success
        rtn.server.ipAddress == null

        where:
        adapters << [[], null]
    }

    def "checkServerReady gives up after 301 polls when waitForIp is true and no IP ever appears"() {
        given:
        computeServerService.get(10L) >> server

        when:
        def rtn = svc.checkServerReady([server: server, waitForIp: true], VM_ID)

        then:
        301 * svc.getServerDetails(_, _) >> detail()
        301 * svc.pause(5000L) >> null
        rtn == [success: false]
    }

    def "checkServerReady stops after the 11th VM_NOT_FOUND without an error message"() {
        // plan expected an 'error' mentioning not found; the code returns [success:false] only
        given:
        computeServerService.get(10L) >> server

        when:
        def rtn = svc.checkServerReady([server: server, waitForIp: false], VM_ID)

        then:
        11 * svc.getServerDetails(_, _) >> [success: false, server: null, error: 'VM_NOT_FOUND']
        rtn == [success: false]
    }

    def "checkServerReady counts VM_NOT_FOUND cumulatively, not consecutively"() {
        // plan expected a successful lookup to reset the counter; it does not
        given:
        computeServerService.get(10L) >> server
        def notFound = [success: false, server: null, error: 'VM_NOT_FOUND']
        def stopped = detail(VirtualMachineState: 'Stopped', Status: 'Stopped')
        def responses = (1..11).collectMany { [notFound, stopped] }

        when:
        def rtn = svc.checkServerReady([server: server, waitForIp: false], VM_ID)

        then:
        21 * svc.getServerDetails(_, _) >>> responses
        rtn.success == false
    }

    def "checkServerReady does not count other lookup failures towards the not-found limit"() {
        given:
        computeServerService.get(10L) >> server
        def failures = [[success: false, server: null, error: null]] * 15

        when:
        def rtn = svc.checkServerReady([server: server, waitForIp: false], VM_ID)

        then:
        16 * svc.getServerDetails(_, _) >>> failures + [detail()]
        rtn.success
    }

    def "checkServerReady stops with success=false when the VM reports CreationFailed"() {
        given:
        computeServerService.get(10L) >> server
        Map failed = detail(VirtualMachineState: 'Stopped', Status: 'CreationFailed')

        when:
        def rtn = svc.checkServerReady([server: server, waitForIp: false], VM_ID)

        then:
        1 * svc.getServerDetails(_, _) >> failed
        rtn.success == false
        rtn.server.is(failed.server)
    }

    def "checkServerReady accepts a VM that is not yet Running once Morpheus already has its internalIp"() {
        given:
        server.internalIp = '10.0.0.7'
        computeServerService.get(10L) >> server

        when:
        def rtn = svc.checkServerReady([server: server, waitForIp: false], VM_ID)

        then:
        1 * svc.getServerDetails(_, _) >> detail(VirtualMachineState: 'Starting', Status: 'Starting')
        rtn.success
        rtn.server.ipAddress == '10.0.0.7'
    }

    def "checkServerReady keeps polling a non-running VM with no IP until it is Running"() {
        given:
        computeServerService.get(10L) >> server

        when:
        def rtn = svc.checkServerReady([server: server, waitForIp: false], VM_ID)

        then:
        3 * svc.getServerDetails(_, _) >>> [
            detail(VirtualMachineState: 'Starting', Status: 'Starting'),
            [success: true, server: null],
            detail()
        ]
        rtn.success
    }

    def "checkServerReady re-reads the Morpheus server on every poll"() {
        given:
        ComputeServer before = Domain.server(id: 10L)
        ComputeServer after = Domain.server(id: 10L, externalIp: '10.0.0.11')
        Map opts = [server: before, waitForIp: true]

        when:
        def rtn = svc.checkServerReady(opts, VM_ID)

        then:
        2 * computeServerService.get(10L) >>> [before, after]
        2 * svc.getServerDetails(_, _) >> detail()
        rtn.server.ipAddress == '10.0.0.11'
        opts.server.is(after)
    }

    def "checkServerReady returns success=false instead of throwing when #reason"() {
        given:
        computeServerService.get(_) >> { throw new IllegalStateException('db down') }

        when:
        def rtn = svc.checkServerReady(opts, VM_ID)

        then:
        noExceptionThrown()
        rtn == [success: false]

        where:
        reason                          | opts
        'the server lookup throws'      | [server: Domain.server(id: 10L), waitForIp: false]
        'opts carries no server'        | [waitForIp: false]
    }
}
