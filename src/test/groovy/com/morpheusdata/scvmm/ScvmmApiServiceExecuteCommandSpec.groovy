// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.synchronous.MorpheusSynchronousKeyPairService
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.TaskResult
import com.morpheusdata.scvmm.support.Domain
import com.morpheusdata.scvmm.support.ScvmmSpec
import com.morpheusdata.scvmm.support.TaskResults
import io.reactivex.rxjava3.core.Single
import spock.lang.Subject
import spock.lang.Timeout
import spock.lang.Unroll

/**
 * Verifies controller command execution routing between the Morpheus Windows Agent
 * ({@code executeCommandOnServer}) and direct WinRM ({@code executeWindowsCommand}),
 * lazy {@code controllerServerId} resolution, error/exit-code propagation, and CRLF-safe
 * JSON unwrapping in {@link ScvmmApiService#wrapExecuteCommand}.
 */
@Timeout(10)
class ScvmmApiServiceExecuteCommandSpec extends ScvmmSpec {

    @Subject
    ScvmmApiService svc

    def setup() {
        svc = new ScvmmApiService(context)
    }

    private static ComputeServer controller(Map props = [:]) {
        new ComputeServer([
            id            : 42L,
            name          : 'scvmm-ctrl',
            sshHost       : 'scvmm.local',
            sshUsername   : 'ctrl-user',
            sshPassword   : 'ctrl-pass',
            agentInstalled: true
        ] + props)
    }

    private static Map baseOpts(Map overrides = [:]) {
        [
            zone       : Domain.cloud(),
            sshHost    : 'scvmm.local',
            sshPort    : 5985,
            sshUsername: 'cloud-user',
            sshPassword: 'cloud-pass'
        ] + overrides
    }

    // ------------------------------------------------------------------ agent routing

    def "executeCommand routes through executeCommandOnServer when controllerServer has id and agentInstalled=true"() {
        given:
        def ctrl = controller()
        def opts = baseOpts(controllerServer: ctrl)
        def expected = TaskResults.raw('[{"ID":"vm-1"}]', 0)

        when:
        def out = svc.executeCommand('Get-SCVirtualMachine', opts)

        then:
        1 * context.executeCommandOnServer(ctrl, 'Get-SCVirtualMachine', null, 'cloud-user', 'cloud-pass', null, null, null, null, false) >> Single.just(expected)
        0 * context.executeWindowsCommand(*_)
        out.is(expected)
    }

    @Unroll
    def "executeCommand propagates agent exit code #exitCode and PowerShell error cleanly"() {
        given:
        def ctrl = controller()
        def opts = baseOpts(controllerServer: ctrl)
        def rawError = '#< CLIXML\n<Objs Version="1.1.0.1"><S S="Error">VM network not found_x000D__x000A_</S></Objs>'
        def agentResult = new TaskResult(success: false, exitCode: exitCode, error: rawError, data: '')

        when:
        def out = svc.wrapExecuteCommand('Exit ' + exitCode, opts)

        then:
        1 * context.executeCommandOnServer(ctrl, 'Exit ' + exitCode, null, 'cloud-user', 'cloud-pass', null, null, null, null, false) >> Single.just(agentResult)
        0 * context.executeWindowsCommand(*_)
        !out.success
        out.exitCode == exitCode
        ScvmmApiService.cleanPowerShellError(out.error) == 'VM network not found'

        where:
        exitCode << ['23', '24', '25', '26', '27', '28']
    }

    def "executeCommand lazily loads controllerServer from controllerServerId and routes over agent when installed"() {
        given:
        def ctrl = controller(id: 77L, agentInstalled: true)
        def opts = baseOpts(controllerServerId: 77L)
        def expected = TaskResults.raw('SCVMM01', 0)

        when:
        def out = svc.executeCommand('hostname', opts)

        then:
        1 * computeServerService.get(77L) >> ctrl
        1 * context.executeCommandOnServer(ctrl, 'hostname', null, 'cloud-user', 'cloud-pass', null, null, null, null, false) >> Single.just(expected)
        0 * context.executeWindowsCommand(*_)
        opts.controllerServer.is(ctrl)
        out.data == 'SCVMM01'
    }

    def "executeCommand falls back to WinRM when lazy controllerServerId resolves to agentless or missing server"() {
        given:
        def opts = baseOpts(controllerServerId: 88L)

        when:
        def out = svc.executeCommand('hostname', opts)

        then:
        1 * computeServerService.get(88L) >> loaded
        0 * context.executeCommandOnServer(*_)
        1 * context.executeWindowsCommand('scvmm.local', 5985, 'cloud-user', 'cloud-pass', 'hostname', null, false) >> Single.just(TaskResults.raw('SCVMM01'))
        out.data == 'SCVMM01'

        where:
        loaded << [null, controller(id: 88L, agentInstalled: false)]
    }

    // ------------------------------------------------------------------ WinRM fallback

    @Unroll
    def "executeCommand falls back to WinRM when #reason"() {
        given:
        def opts = baseOpts(controllerServer: ctrl, sshPort: 5986)
        def winrmResult = TaskResults.fail('WinRM PowerShell error', '', 25)

        when:
        def out = svc.executeCommand('Get-SCVMNetwork', opts)

        then:
        0 * context.executeCommandOnServer(*_)
        1 * context.executeWindowsCommand('scvmm.local', 5986, 'cloud-user', 'cloud-pass', 'Get-SCVMNetwork', null, false) >> Single.just(winrmResult)
        !out.success
        out.exitCode == '25'
        out.error == 'WinRM PowerShell error'

        where:
        reason                            | ctrl
        'controllerServer is null'        | null
        'controllerServer has no id'      | controller(id: null, agentInstalled: true)
        'agentInstalled is false'         | controller(agentInstalled: false)
        'agentInstalled is null'          | controller(agentInstalled: null)
    }

    // ------------------------------------------------------------------ wrapExecuteCommand CRLF & whitespace

    @Unroll
    def "wrapExecuteCommand trims CRLF/whitespace around #shape JSON payloads without double-wrapping"() {
        given:
        def ctrl = controller()
        def opts = baseOpts(controllerServer: ctrl)
        context.executeCommandOnServer(ctrl, *_) >> Single.just(TaskResults.raw(stdout))

        expect:
        svc.wrapExecuteCommand('x', opts).data == [[ID: 'vm-1']]

        where:
        shape    | stdout
        'array'  | '\r\n[{"ID":"vm-1"}]\r\n'
        'object' | '  \r\n{"ID":"vm-1"}\r\n  '
    }

    def "wrapExecuteCommand normalizes whitespace-only stdout to empty string so paginated listings terminate"() {
        given:
        def ctrl = controller()
        def opts = baseOpts(controllerServer: ctrl)
        int calls = 0
        context.executeCommandOnServer(ctrl, *_) >> {
            calls++
            Single.just(TaskResults.raw(calls == 1 ? '\r\n[{"ID":"vm-1","Name":"vm1","StatusString":"Running"}]\r\n' : '\r\n'))
        }

        when:
        def res = svc.listVirtualMachines(opts)

        then:
        calls == 2
        res.success
        res.virtualMachines.size() == 1
        res.virtualMachines[0].ID == 'vm-1'
    }

    // ------------------------------------------------------------------ opts helpers

    def "getScvmmZoneOpts leaves controllerServer null while getScvmmCloudOpts and getScvmmZoneAndHypervisorOpts populate it"() {
        given:
        def keyPairService = Mock(MorpheusSynchronousKeyPairService)
        services.getKeyPair() >> keyPairService
        def cloud = Domain.cloud(config: [libraryShare: '\\\\lib\\share', diskPath: 'C:\\disks', workingPath: 'C:\\work'])
        def ctrl = controller()

        when:
        def zoneOpts = svc.getScvmmZoneOpts(context, cloud)
        def cloudOpts = svc.getScvmmCloudOpts(context, cloud, ctrl)
        def combinedOpts = svc.getScvmmZoneAndHypervisorOpts(context, cloud, ctrl)

        then:
        zoneOpts.controllerServer == null
        cloudOpts.controllerServer.is(ctrl)
        combinedOpts.controllerServer.is(ctrl)
        combinedOpts.hypervisor.is(ctrl)
        combinedOpts.sshHost == 'scvmm.local'
    }
}
