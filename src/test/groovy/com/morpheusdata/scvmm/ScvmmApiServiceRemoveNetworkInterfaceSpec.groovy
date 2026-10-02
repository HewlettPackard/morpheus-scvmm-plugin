// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.TaskResult
import spock.lang.Specification
import spock.lang.Subject

class ScvmmApiServiceRemoveNetworkInterfaceSpec extends Specification {

    static final String VM_ID = 'vm-1111-2222'
    static final String ADAPTER_ID = 'adapter-aaaa-bbbb'
    static final String MAC = '00:15:5D:01:02:03'

    MorpheusContext context = Mock()
    Map opts = [sshHost: 'scvmm.local', sshUsername: 'u', sshPassword: 'p']

    @Subject
    ScvmmApiService service

    String capturedCommand

    def setup() {
        service = Spy(ScvmmApiService, constructorArgs: [context])
    }

    private void stubExecute(Map result) {
        service.wrapExecuteCommand(_ as String, _ as Map) >> { String cmd, Map o ->
            capturedCommand = cmd
            TaskResult out = new TaskResult()
            out.success = result.success
            out.exitCode = result.exitCode
            out.error = result.error
            out.msg = result.msg
            out.data = result.data
            return out
        }
    }

    def "returns an error and does not call SCVMM when neither adapter id nor MAC is given"() {
        when:
        def rtn = service.removeNetworkInterface(opts, VM_ID, props)

        then:
        0 * service.wrapExecuteCommand(*_)
        !rtn.success
        rtn.error == 'No adapter identifier provided for NIC removal'

        where:
        props << [[:], [adapterId: null, macAddress: null], [adapterId: '']]
    }

    def "builds a Remove-SCVirtualNetworkAdapter command locating the adapter by ID"() {
        given:
        stubExecute(success: true, exitCode: '0', data: 'true')

        when:
        def rtn = service.removeNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID, macAddress: MAC])

        then:
        rtn.success
        !rtn.error
        capturedCommand.contains("\$VM = Get-SCVirtualMachine -VMMServer localhost -ID \"${VM_ID}\"")
        capturedCommand.contains('if (-not $VM) { Write-Error "Virtual machine vm-1111-2222 not found"; Exit 23 }')
        capturedCommand.contains("Get-SCVirtualNetworkAdapter -VMMServer localhost -VM \$VM | where { \$_.ID -eq \"${ADAPTER_ID}\" } | Select-Object -First 1")
        capturedCommand.contains('if (-not $VirtualNetworkAdapter) { Write-Error "Network adapter not found"; Exit 24 }')
        capturedCommand.contains('Remove-SCVirtualNetworkAdapter -VirtualNetworkAdapter $VirtualNetworkAdapter')
        capturedCommand.contains('if (-not $?) { Exit 27 }')
        capturedCommand.endsWith('| ConvertTo-Json -Depth 3')
        !capturedCommand.contains('MACAddress -eq')
        !capturedCommand.contains('New-SCVirtualNetworkAdapter')
        !capturedCommand.contains('Set-SCVirtualNetworkAdapter')
    }

    def "falls back to locating the adapter by MAC address when no adapter id is given"() {
        given:
        stubExecute(success: true, exitCode: '0', data: 'true')

        when:
        def rtn = service.removeNetworkInterface(opts, VM_ID, [macAddress: MAC])

        then:
        rtn.success
        capturedCommand.contains("where { \$_.MACAddress -eq \"${MAC}\" }")
        !capturedCommand.contains('$_.ID -eq')
    }

    def "fails when the command exits non-zero and surfaces the SCVMM error"() {
        given:
        stubExecute(success: false, exitCode: exitCode, error: error, msg: msg)

        when:
        def rtn = service.removeNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID])

        then:
        !rtn.success
        rtn.error == expectedError

        where:
        exitCode | error                       | msg               || expectedError
        '23'     | 'Virtual machine not found' | null              || 'Virtual machine not found'
        '24'     | 'Network adapter not found' | null              || 'Network adapter not found'
        '27'     | null                        | 'remove failed'   || 'remove failed'
        '27'     | null                        | null              || 'Failed to remove network adapter'
    }

    def "fails when the command succeeded at transport level but exit code is non-zero"() {
        given:
        stubExecute(success: true, exitCode: '24', data: null)

        when:
        def rtn = service.removeNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID])

        then:
        !rtn.success
        rtn.error == 'Failed to remove network adapter'
    }

    def "returns the exception message when command execution throws"() {
        given:
        service.wrapExecuteCommand(_ as String, _ as Map) >> { throw new RuntimeException('ssh boom') }

        when:
        def rtn = service.removeNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID])

        then:
        !rtn.success
        rtn.error == 'ssh boom'
    }
}
