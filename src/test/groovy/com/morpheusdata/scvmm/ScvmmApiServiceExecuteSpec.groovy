// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.TaskResult
import com.morpheusdata.scvmm.error.ScvmmCommandException
import com.morpheusdata.scvmm.error.ScvmmConnectionException
import com.morpheusdata.scvmm.error.ScvmmResponseParseException
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

class ScvmmApiServiceExecuteSpec extends Specification {

    MorpheusContext context = Mock()
    ScvmmApiService service = new ScvmmApiService(context)
    Map opts = [sshHost: 'scvmm-host', sshUsername: 'DOMAIN\\admin', sshPassword: 'Sup3rS3cret!']

    private static TaskResult result(Map props) {
        TaskResult rtn = new TaskResult()
        props.each { k, v -> rtn[k] = v }
        rtn
    }

    def "executeCommand uses the default WinRM port and passes credentials through"() {
        given:
        TaskResult ok = result(success: true, data: 'hi', exitCode: '0')

        when:
        def out = service.executeCommand('hostname', opts)

        then:
        1 * context.executeWindowsCommand('scvmm-host', ScvmmApiService.DEFAULT_WINRM_PORT, 'DOMAIN\\admin', 'Sup3rS3cret!', 'hostname', null, false) >> Single.just(ok)
        out.is(ok)
    }

    def "executeCommand honours a custom sshPort"() {
        when:
        service.executeCommand('hostname', opts + [sshPort: 5986])

        then:
        1 * context.executeWindowsCommand('scvmm-host', 5986, _, _, 'hostname', null, false) >> Single.just(result(success: true))
    }

    def "executeCommand wraps transport exceptions in ScvmmConnectionException without leaking the password"() {
        given:
        context.executeWindowsCommand(*_) >> { throw new java.net.ConnectException('Connection refused') }

        when:
        service.executeCommand('hostname', opts)

        then:
        ScvmmConnectionException e = thrown()
        e.host == 'scvmm-host'
        e.port == ScvmmApiService.DEFAULT_WINRM_PORT
        e.cause instanceof java.net.ConnectException
        !e.message.contains('Sup3rS3cret!')
    }

    def "executeCommand treats a null TaskResult as a connection failure"() {
        given:
        context.executeWindowsCommand(*_) >> Single.fromCallable { (TaskResult) null }

        when:
        service.executeCommand('hostname', opts)

        then:
        thrown(ScvmmConnectionException)
    }

    def "executeCommand classifies a failed result with WinRM auth text as a connection failure"() {
        given:
        context.executeWindowsCommand(*_) >> Single.just(result(success: false, error: 'The remote server returned an error: (401) Unauthorized.'))

        when:
        service.executeCommand('hostname', opts)

        then:
        ScvmmConnectionException e = thrown()
        e.userMessage.contains('Authentication')
    }

    def "executeCommand returns an ordinary failed result so tolerant callers can inspect it"() {
        given:
        TaskResult failed = result(success: false, error: 'mkdir : An item with the specified name already exists.', exitCode: '1')
        context.executeWindowsCommand(*_) >> Single.just(failed)

        when:
        def out = service.executeCommand('mkdir x', opts)

        then:
        out.is(failed)
        out.success == false
    }

    def "wrapExecuteCommand parses a JSON object into a single-element list"() {
        given:
        context.executeWindowsCommand(*_) >> Single.just(result(success: true, exitCode: '0', data: '{"ID":"abc","Name":"Cloud1"}'))

        when:
        def out = service.wrapExecuteCommand(service.generateCommandString('Get-SCCloud'), opts)

        then:
        out.success
        out.data instanceof List
        out.data.size() == 1
        out.data[0].ID == 'abc'
    }

    def "wrapExecuteCommand parses a JSON array as-is"() {
        given:
        context.executeWindowsCommand(*_) >> Single.just(result(success: true, exitCode: '0', data: '[{"ID":"a"},{"ID":"b"}]'))

        when:
        def out = service.wrapExecuteCommand('cmd', opts)

        then:
        out.data*.ID == ['a', 'b']
    }

    def "wrapExecuteCommand leaves data untouched when the host returns nothing"() {
        given:
        context.executeWindowsCommand(*_) >> Single.just(result(success: true, exitCode: '0', data: ''))

        when:
        def out = service.wrapExecuteCommand('cmd', opts)

        then:
        out.success
        !out.data
    }

    def "wrapExecuteCommand throws ScvmmCommandException when the command reports failure"() {
        given:
        String command = service.generateCommandString('New-Object PSCredential -Password "Sup3rS3cret!"; Get-SCCloud')
        context.executeWindowsCommand(*_) >> Single.just(result(success: false, exitCode: '1', error: 'Get-SCCloud : You cannot access VMM management server\nAt line:1'))

        when:
        service.wrapExecuteCommand(command, opts)

        then:
        ScvmmCommandException e = thrown()
        e.exitCode == '1'
        e.errorOutput.startsWith('Get-SCCloud : You cannot access')
        e.message.contains('You cannot access VMM management server')
        e.message.contains('Get-SCCloud')
        !e.message.contains('Sup3rS3cret!')
        !e.commandSummary.contains('Sup3rS3cret!')
        !e.commandSummary.contains('ConvertTo-Json')
    }

    def "wrapExecuteCommand combines error, msg and output when building the failure text"() {
        given:
        context.executeWindowsCommand(*_) >> Single.just(result(success: false, error: 'err text', msg: 'msg text', output: 'out text'))

        when:
        service.wrapExecuteCommand('cmd', opts)

        then:
        ScvmmCommandException e = thrown()
        e.errorOutput.contains('err text')
        e.errorOutput.contains('msg text')
        e.errorOutput.contains('out text')
    }

    def "wrapExecuteCommand with failOnError=false returns the failed result with error populated"() {
        given:
        context.executeWindowsCommand(*_) >> Single.just(result(success: false, exitCode: '1', msg: 'Remove-SCISO : object not found'))

        when:
        def out = service.wrapExecuteCommand('cmd', opts, false)

        then:
        notThrown(ScvmmCommandException)
        out.success == false
        out.error == 'Remove-SCISO : object not found'
    }

    def "wrapExecuteCommand with failOnError=false discards non-JSON stdout of a failed command"() {
        given:
        context.executeWindowsCommand(*_) >> Single.just(result(success: false, exitCode: '1', error: 'boom', data: 'not json at all'))

        when:
        def out = service.wrapExecuteCommand('cmd', opts, false)

        then:
        notThrown(ScvmmResponseParseException)
        out.success == false
        out.data == null
    }

    def "wrapExecuteCommand throws ScvmmResponseParseException on unparseable JSON with a payload sample"() {
        given:
        String junk = 'Get-SCCloud : The term is not recognized' + ('x' * 1000)
        context.executeWindowsCommand(*_) >> Single.just(result(success: true, exitCode: '0', data: junk))

        when:
        service.wrapExecuteCommand('cmd', opts)

        then:
        ScvmmResponseParseException e = thrown()
        e.payloadSample.startsWith('[Get-SCCloud : The term')
        e.payloadSample.length() <= com.morpheusdata.scvmm.error.ScvmmCommandSanitizer.MAX_PAYLOAD_SAMPLE_LENGTH
        e.cause != null
    }

    def "wrapExecuteCommand propagates connection failures unchanged"() {
        given:
        context.executeWindowsCommand(*_) >> { throw new java.net.SocketTimeoutException('Read timed out') }

        when:
        service.wrapExecuteCommand('cmd', opts)

        then:
        thrown(ScvmmConnectionException)
    }

    def "listClouds returns a populated result map on success"() {
        given:
        context.executeWindowsCommand(*_) >> Single.just(result(success: true, exitCode: '0', data: '[{"ID":"c1","Name":"Prod"}]'))

        when:
        def rtn = service.listClouds(opts)

        then:
        rtn.success
        rtn.clouds*.Name == ['Prod']
    }

    def "listClouds lets command failures propagate as ScvmmCommandException"() {
        given:
        context.executeWindowsCommand(*_) >> Single.just(result(success: false, exitCode: '1', error: 'Access is denied'))

        when:
        service.listClouds(opts)

        then:
        thrown(ScvmmConnectionException) // 'Access is denied' is classified as an auth/connection failure
    }

    def "startServer converts an API failure into a result map with a user message"() {
        given:
        // getServerDetails succeeds, Start-SCVirtualMachine fails
        context.executeWindowsCommand(*_) >>> [
                Single.just(result(success: true, exitCode: '0', data: '[{"ID":"vm1","VirtualMachineState":"PowerOff","Status":"PowerOff"}]')),
                Single.just(result(success: false, exitCode: '1', error: 'Start-SCVirtualMachine : Unable to find a suitable host for the virtual machine')),
        ]

        when:
        def rtn = service.startServer(opts, 'vm1')

        then:
        rtn.success == false
        rtn.msg.contains('could not place the virtual machine')
        rtn.errorType == 'ScvmmCommandException'
        rtn.errorCode == 'vmm.placement'
    }

    def "releaseIPAddress treats an already-released address as success"() {
        given:
        context.executeWindowsCommand(*_) >> Single.just(result(success: false, exitCode: '1', error: 'Revoke-SCIPAddress : Unable to find the specified allocated IP address'))

        when:
        def rtn = service.releaseIPAddress(opts, 'pool1', 'ip1')

        then:
        rtn.success == true
    }

    def "releaseIPAddress reports other failures"() {
        given:
        context.executeWindowsCommand(*_) >> Single.just(result(success: false, exitCode: '1', error: 'Revoke-SCIPAddress : something else went wrong'))

        when:
        def rtn = service.releaseIPAddress(opts, 'pool1', 'ip1')

        then:
        rtn.success == false
        rtn.msg.startsWith('Error revoking an IP address from SCVMM: ')
        rtn.msg.contains('something else went wrong')
    }
}
