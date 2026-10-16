// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.error

import spock.lang.Specification

class ScvmmExceptionSpec extends Specification {

    def "ScvmmException falls back to message when no userMessage is given"() {
        when:
        def e = new ScvmmException('technical detail')

        then:
        e.message == 'technical detail'
        e.userMessage == 'technical detail'
        e.errorType == 'ScvmmException'
        e.toDetailMap().errorType == 'ScvmmException'
        !e.toDetailMap().containsKey('userMessage')
    }

    def "ScvmmException exposes userMessage and cause in detail map"() {
        when:
        def e = new ScvmmException('technical', 'friendly', new IllegalStateException('root'))

        then:
        e.userMessage == 'friendly'
        e.toDetailMap().userMessage == 'friendly'
        e.toDetailMap().cause == 'IllegalStateException: root'
    }

    def "ScvmmConnectionException builds a default user message with host and port"() {
        when:
        def e = new ScvmmConnectionException('WinRM failed', 'scvmm.example.com', 5985)

        then:
        e.userMessage.contains('scvmm.example.com:5985')
        e.userMessage.contains('WinRM')
        e.toDetailMap().host == 'scvmm.example.com'
        e.toDetailMap().port == 5985
    }

    def "ScvmmCommandException.fromOutput sanitizes the command and keeps first error line"() {
        given:
        String command = '$FormatEnumerationLimit =-1; New-Object PSCredential -Password "s3cret" ; Get-SCCloud | ConvertTo-Json -Depth 3'

        when:
        def e = ScvmmCommandException.fromOutput(command, 'Access is denied.\nAt line:1 char:1', '1')

        then:
        !e.commandSummary.contains('s3cret')
        !e.message.contains('s3cret')
        !e.commandSummary.contains('ConvertTo-Json')
        !e.commandSummary.contains('FormatEnumerationLimit')
        e.commandSummary.contains('Get-SCCloud')
        e.message.contains('exit code 1')
        e.message.contains('Access is denied.')
        !e.message.contains('At line:1')
        e.errorOutput.contains('At line:1')
        e.exitCode == '1'
        e.toDetailMap().command == e.commandSummary
    }

    def "ScvmmResponseParseException.fromPayload includes a truncated payload sample"() {
        given:
        String payload = 'x' * 2000

        when:
        def e = ScvmmResponseParseException.fromPayload('Get-Foo', payload, new RuntimeException('Unexpected character'))

        then:
        e.payloadSample.length() <= ScvmmCommandSanitizer.MAX_PAYLOAD_SAMPLE_LENGTH
        e.payloadSample.endsWith('...')
        e.message.contains('Unexpected character')
        e.userMessage.contains('non-JSON')
        e.commandSummary == 'Get-Foo'
    }

    def "ScvmmJobFailedException carries job details and prefers ErrorInfo for the user message"() {
        when:
        def e = new ScvmmJobFailedException('job-1', 'Create virtual machine', 'Failed', '[12700] VMM cannot complete the host operation.\nmore')

        then:
        e.jobId == 'job-1'
        e.message.contains("finished with status 'Failed'")
        e.message.contains('12700')
        e.userMessage == "SCVMM job 'Create virtual machine' failed: [12700] VMM cannot complete the host operation."
        e.toDetailMap().jobStatus == 'Failed'
    }

    def "ScvmmJobFailedException without ErrorInfo points at the VMM Jobs view"() {
        expect:
        new ScvmmJobFailedException('job-2', null, 'Failed', null).userMessage.contains('Jobs view')
    }

    def "ScvmmTimeoutException describes what was awaited, how long, and the last state"() {
        when:
        def e = new ScvmmTimeoutException('VM abc to be Running', 300, 1_500_000L, 'VirtualMachineState=PowerOff')

        then:
        e.message == 'Timed out waiting for VM abc to be Running after 25m (300 attempts). Last observed state: VirtualMachineState=PowerOff.'
        e.userMessage == e.message
        e.toDetailMap().attempts == 300
        e.toDetailMap().durationMs == 1_500_000L
    }

    def "formatDuration renders #ms as #expected"() {
        expect:
        ScvmmTimeoutException.formatDuration(ms) == expected

        where:
        ms        | expected
        null      | 'unknown'
        4_000L    | '4s'
        60_000L   | '1m'
        95_000L   | '1m 35s'
        1_500_000L| '25m'
    }
}
