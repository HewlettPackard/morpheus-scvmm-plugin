// Copyright 2026 Hewlett Packard Enterprise Development LP
package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.ComputeServer
import spock.lang.Specification

class ScvmmApiServicePollingSpec extends Specification {
    MorpheusContext context = Mock()
    MorpheusServices services = Mock()
    MorpheusSynchronousComputeServerService servers = Mock()
    NoSleepApi service = Spy(NoSleepApi, constructorArgs: [context])
    ComputeServer server = new ComputeServer(id: 1L, name: 'test-vm', externalId: 'vm1')

    def setup() {
        context.services >> services
        services.computeServer >> servers
        servers.get(1L) >> server
        service.refreshVM(_, _) >> [success: true]
    }

    def "creation failure is terminal even when the expected disks exist"() {
        given:
        service.getServerDetails(_, _) >> [success: true,
                server: [Status: 'CreationFailed', VirtualDiskDrives: ['disk1']]]

        when:
        def result = service.checkServerCreated([name: 'test-vm'], 'vm1')

        then:
        !result.success
        result.msg.contains('CreationFailed')
        service.polls == 1
    }

    def "readiness fails immediately for CreationFailed without waiting for an IP"() {
        given:
        service.getServerDetails(_, _) >> [success: true,
                server: [Status: 'CreationFailed', VirtualMachineState: 'Running']]

        when:
        def result = service.checkServerReady([server: server, waitForIp: true], 'vm1')

        then:
        !result.success
        result.msg.contains('CreationFailed')
        service.polls == 1
    }

    def "failed or canceled jobs surface VMM ErrorInfo"() {
        given:
        service.getJob(_, _) >> [success: true,
                jobDetail: [Status: state, Name: 'Create VM', ErrorInfo: '[12700] Host operation failed']]

        when:
        def result = service.waitForJobToComplete([:], 'job1')

        then:
        !result.success
        result.msg.contains('[12700] Host operation failed')
        result.jobStatus == state
        result.errorInfo == '[12700] Host operation failed'
        service.polls == 1

        where:
        state << ['Failed', 'Canceled', 'Cancelled']
    }

    def "job timeout reports the exact attempt limit and last state"() {
        given:
        service.getJob(_, _) >> [success: true, jobDetail: [Status: 'Running', Progress: 37]]

        when:
        def result = service.waitForJobToComplete([:], 'job1')

        then:
        result.timedOut
        result.msg.contains('job1')
        result.msg.contains("(${ScvmmApiService.JOB_MAX_ATTEMPTS} attempts)")
        result.msg.contains('Status=Running, Progress=37')
        result.data.durationMs >= 0
        service.polls == ScvmmApiService.JOB_MAX_ATTEMPTS
    }

    def "creation and readiness timeouts report their attempt limits and last state"() {
        given:
        service.getServerDetails(_, _) >> [success: true, server: [Status: 'UnderCreation',
                VirtualMachineState: 'PowerOff', VirtualDiskDrives: []]]

        when:
        def result = ready ?
                service.checkServerReady([server: server, waitForIp: true], 'vm1') :
                service.checkServerCreated([name: 'test-vm'], 'vm1')

        then:
        result.timedOut
        result.msg.contains('vm1')
        result.msg.contains("(${limit} attempts)")
        result.msg.contains('UnderCreation')
        service.polls == limit

        where:
        ready | limit
        false | ScvmmApiService.SERVER_CREATED_MAX_ATTEMPTS
        true  | ScvmmApiService.SERVER_READY_MAX_ATTEMPTS
    }

    def "polling preserves connectivity diagnostics rather than waiting until timeout"() {
        given:
        Map failure = [success: false, msg: 'WinRM listener unavailable', connectionFailure: true,
                errorType: 'ScvmmConnectionException', data: [host: 'vmm']]
        service.getServerDetails(_, _) >> failure
        service.getJob(_, _) >> failure

        expect:
        service.checkServerCreated([:], 'vm1').msg == failure.msg
        service.checkServerReady([server: server], 'vm1').data.host == 'vmm'
        service.waitForJobToComplete([:], 'job1').jobId == 'job1'
        service.polls == 3
    }

    def "not-found limit counts consecutive observations rather than total misses"() {
        given:
        int lookups = 0
        service.getServerDetails(_, _) >> {
            lookups++
            lookups % 2 ? [success: false, error: 'VM_NOT_FOUND'] :
                    [success: true, server: [Status: 'UnderCreation', VirtualMachineState: 'PowerOff']]
        }

        when:
        def result = service.checkServerReady([server: server, waitForIp: true], 'vm1')

        then:
        result.timedOut
        lookups == ScvmmApiService.SERVER_READY_MAX_ATTEMPTS
    }

    static class NoSleepApi extends ScvmmApiService {
        int polls
        NoSleepApi(MorpheusContext context) { super(context) }
        void sleep(long milliseconds) { polls++ }
    }
}
