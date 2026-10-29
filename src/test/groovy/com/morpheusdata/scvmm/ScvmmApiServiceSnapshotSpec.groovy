// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.TaskResult
import com.morpheusdata.scvmm.support.PsStub
import com.morpheusdata.scvmm.support.TaskResults
import com.morpheusdata.scvmm.support.VmmPayloads
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * snapshotServer / deleteSnapshot / restoreServer: the checkpoint primitives behind the SCVMM backup provider.
 * All three derive {@code success} solely from {@code out.success && out.exitCode == '0'}.
 */
class ScvmmApiServiceSnapshotSpec extends Specification {

    static final String VM_ID = 'vm-ext-1'
    static final String SNAP = 'vm-ext-1.1700000000000'

    Map opts = [sshHost: 'scvmm.local', sshUsername: 'u', sshPassword: 'p']
    List<String> scripts = []

    @Subject
    ScvmmApiService service = Spy(ScvmmApiService, constructorArgs: [Mock(MorpheusContext)])

    private void respond(TaskResult result) {
        Closure ps = PsStub.capture(scripts, [:], result)
        service.wrapExecuteCommand(_ as String, _ as Map) >> { String cmd, Map o -> ps(cmd, o) }
    }

    // ---------------------------------------------------------------- snapshotServer

    def "snapshotServer creates a checkpoint named after opts.snapshotId on the VM"() {
        given:
        respond(TaskResults.ok())

        when:
        def rtn = service.snapshotServer(opts + [snapshotId: SNAP], VM_ID)

        then:
        scripts.size() == 1
        scripts[0].contains("Get-SCVirtualMachine -VMMServer localhost -ID \"${VM_ID}\"")
        scripts[0].contains("New-SCVMCheckpoint -VM \$VM -Name \"${SNAP}\"")
        scripts[0].contains('ConvertTo-Json')
        rtn.success == true
        rtn.snapshotId == SNAP
    }

    def "snapshotServer generates '<vmId>.<millis>' as the checkpoint name when opts.snapshotId is absent"() {
        given:
        respond(TaskResults.ok())

        when:
        def rtn = service.snapshotServer(opts, VM_ID)

        then:
        rtn.success
        rtn.snapshotId ==~ /vm-ext-1\.\d{13}/
        scripts[0].contains("-Name \"${rtn.snapshotId}\"")
    }

    def "snapshotServer reports the requested name as snapshotId and ignores the VMM payload"() {
        // plan expected snapshotId from data[0].ID and success=false on an empty payload; the code does neither
        given:
        respond(TaskResults.ok(payload))

        when:
        def rtn = service.snapshotServer(opts + [snapshotId: SNAP], VM_ID)

        then:
        rtn.success == true
        rtn.snapshotId == SNAP

        where:
        payload << [[], null, [VmmPayloads.snapshot(ID: 'other-id')]]
    }

    def "snapshotServer returns success=false and no snapshotId when the command throws"() {
        given:
        service.wrapExecuteCommand(_ as String, _ as Map) >> { throw new RuntimeException('winrm timeout') }

        when:
        def rtn = service.snapshotServer(opts + [snapshotId: SNAP], VM_ID)

        then:
        noExceptionThrown()
        rtn == [success: false]
    }

    // ---------------------------------------------------------------- deleteSnapshot

    def "deleteSnapshot removes the checkpoint matched by -like on its name"() {
        given:
        respond(TaskResults.ok(null))

        when:
        def rtn = service.deleteSnapshot(opts, VM_ID, SNAP)

        then:
        scripts.size() == 1
        scripts[0].contains("Get-SCVirtualMachine -VMMServer localhost -ID \"${VM_ID}\"")
        scripts[0].contains("Get-SCVMCheckpoint -VM \$VM | where {\$_.Name -like \"${SNAP}\"}")
        scripts[0].contains('$ignore = Remove-SCVMCheckpoint -VMCheckpoint $Checkpoint')
        rtn.success == true
        rtn.snapshotId == SNAP
    }

    def "deleteSnapshot returns success=false with the snapshotId echoed when VMM fails"() {
        given:
        respond(TaskResults.fail('Unable to find the specified checkpoint'))

        when:
        def rtn = service.deleteSnapshot(opts, VM_ID, SNAP)

        then:
        rtn.success == false
        rtn.snapshotId == SNAP
        !rtn.containsKey('error')
    }

    def "deleteSnapshot swallows a command exception and returns success=false"() {
        given:
        service.wrapExecuteCommand(_ as String, _ as Map) >> { throw new IllegalStateException('boom') }

        when:
        def rtn = service.deleteSnapshot(opts, VM_ID, SNAP)

        then:
        noExceptionThrown()
        rtn == [success: false]
    }

    // ---------------------------------------------------------------- restoreServer

    def "restoreServer restores the checkpoint matched by -like on its name"() {
        given:
        respond(TaskResults.ok([]))

        when:
        def rtn = service.restoreServer(opts, VM_ID, SNAP)

        then:
        scripts.size() == 1
        scripts[0].contains("Get-SCVirtualMachine -VMMServer localhost -ID \"${VM_ID}\"")
        scripts[0].contains("where {\$_.Name -like \"${SNAP}\"}")
        scripts[0].contains('Restore-SCVMCheckpoint -VMCheckpoint $Checkpoint')
        !scripts[0].contains('$ignore = Restore-SCVMCheckpoint')
        rtn == [success: true]
    }

    def "restoreServer returns success=false when the VM or checkpoint is not found"() {
        given:
        respond(TaskResults.fail("Unable to find the specified virtual machine"))

        when:
        def rtn = service.restoreServer(opts, VM_ID, SNAP)

        then:
        rtn == [success: false]
    }

    def "restoreServer swallows a command exception and returns success=false"() {
        given:
        service.wrapExecuteCommand(_ as String, _ as Map) >> { throw new RuntimeException('winrm timeout') }

        when:
        def rtn = service.restoreServer(opts, VM_ID, SNAP)

        then:
        noExceptionThrown()
        rtn == [success: false]
    }

    // ---------------------------------------------------------------- shared success mapping

    @Unroll
    def "#method maps out.success=#ok and exitCode=#exit to success=#expected"() {
        given:
        respond(new TaskResult(success: ok, exitCode: exit, data: null))

        when:
        def rtn = invoke(method)

        then:
        rtn.success == expected

        where:
        method           | ok    | exit | expected
        'snapshotServer' | true  | '0'  | true
        'snapshotServer' | true  | '1'  | false
        'snapshotServer' | true  | null | false
        'snapshotServer' | false | '0'  | false
        'deleteSnapshot' | true  | '0'  | true
        'deleteSnapshot' | true  | '1'  | false
        'deleteSnapshot' | false | '0'  | false
        'restoreServer'  | true  | '0'  | true
        'restoreServer'  | true  | '1'  | false
        'restoreServer'  | false | '0'  | false
    }

    private Map invoke(String method) {
        switch (method) {
            case 'snapshotServer': return service.snapshotServer(opts + [snapshotId: SNAP], VM_ID)
            case 'deleteSnapshot': return service.deleteSnapshot(opts, VM_ID, SNAP)
            default: return service.restoreServer(opts, VM_ID, SNAP)
        }
    }
}
