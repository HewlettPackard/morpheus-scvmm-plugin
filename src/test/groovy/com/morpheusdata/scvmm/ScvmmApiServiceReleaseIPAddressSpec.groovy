// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.TaskResult
import spock.lang.Specification
import spock.lang.Subject

class ScvmmApiServiceReleaseIPAddressSpec extends Specification {

    static final String POOL_ID = '4784149f-a6ed-4944-8868-9a0df5185f4d'
    static final String IP_ID = 'fdc0a0cd-1111-2222-3333-444444444444'

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
            out.output = result.output
            out.data = result.data
            return out
        }
    }

    def "revokes the grant when SCVMM still has it"() {
        given:
        stubExecute(success: true, exitCode: '0', data: [ScvmmApiService.RELEASE_IP_REVOKED])

        when:
        def rtn = service.releaseIPAddress(opts, POOL_ID, IP_ID)

        then:
        rtn.success
        rtn.released
        capturedCommand.contains("Get-SCIPAddress -VMMServer localhost -ID \"${IP_ID}\" -ErrorAction SilentlyContinue")
        capturedCommand.contains('Revoke-SCIPAddress $ipaddress -ReturnToPool $true')
        !capturedCommand.contains('Get-SCStaticIPAddressPool')
    }

    def "treats a grant that SCVMM has already returned to the pool as success"() {
        given:
        stubExecute(success: true, exitCode: '0', data: [ScvmmApiService.RELEASE_IP_ALREADY_RELEASED])

        when:
        def rtn = service.releaseIPAddress(opts, POOL_ID, IP_ID)

        then:
        rtn.success
        !rtn.released
    }

    def "reports failure with the SCVMM error text when the script exits non-zero"() {
        given:
        stubExecute(success: false, exitCode: '1', error: 'Revoke-SCIPAddress : access denied')

        when:
        def rtn = service.releaseIPAddress(opts, POOL_ID, IP_ID)

        then:
        !rtn.success
        rtn.msg == 'Revoke-SCIPAddress : access denied'
    }

    def "does not blow up on a TaskResult without errorData and reports failure"() {
        given:
        stubExecute(success: true, exitCode: '1', output: 'some stderr-ish output')

        when:
        def rtn = service.releaseIPAddress(opts, POOL_ID, IP_ID)

        then:
        !rtn.success
        rtn.msg == 'some stderr-ish output'
    }

    def "returns failure when command execution throws"() {
        given:
        service.wrapExecuteCommand(_ as String, _ as Map) >> { throw new RuntimeException('winrm timeout') }

        when:
        def rtn = service.releaseIPAddress(opts, POOL_ID, IP_ID)

        then:
        !rtn.success
        rtn.msg == 'Error revoking an IP address from SCVMM'
    }
}
