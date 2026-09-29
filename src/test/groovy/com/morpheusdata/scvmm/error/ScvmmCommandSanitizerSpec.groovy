// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.error

import spock.lang.Specification

class ScvmmCommandSanitizerSpec extends Specification {

    def "summarize strips JSON wrapper, collapses whitespace and truncates"() {
        given:
        String command = '$FormatEnumerationLimit =-1; ' + ('Get-SCVirtualMachine   -VMMServer localhost\n\t| Select ID ' * 20) + ' | ConvertTo-Json -Depth 3'

        when:
        String summary = ScvmmCommandSanitizer.summarize(command)

        then:
        !summary.contains('FormatEnumerationLimit')
        !summary.contains('ConvertTo-Json')
        !summary.contains('\n')
        !summary.contains('  ')
        summary.length() <= ScvmmCommandSanitizer.MAX_COMMAND_LENGTH
        summary.endsWith('...')
    }

    def "summarize and redact tolerate null"() {
        expect:
        ScvmmCommandSanitizer.summarize(null) == null
        ScvmmCommandSanitizer.redact(null) == null
        ScvmmCommandSanitizer.firstLine(null) == null
        ScvmmCommandSanitizer.redactOpts(null) == null
    }

    def "redact masks credential-looking values: #description"() {
        when:
        String out = ScvmmCommandSanitizer.redact(input)

        then:
        !out.contains(secret)
        out.contains('***')

        where:
        description               | input                                                   | secret
        'PowerShell dash-pw arg'  | 'New-Object PSCredential -Password "Sup3rS3cret!"'       | 'Sup3rS3cret!'
        'unquoted dash-pw arg'    | 'winrs -u:admin -Password Sup3rS3cret! hostname'        | 'Sup3rS3cret!'
        '-Credential argument'    | 'Invoke-Command -Credential $myCred -ScriptBlock {}'     | '$myCred'
        'password= assignment'    | 'user=admin;password=Sup3rS3cret!;host=x'               | 'Sup3rS3cret!'
        'json password'           | '{"username":"admin","password":"Sup3rS3cret!"}'         | 'Sup3rS3cret!'
        'ConvertTo-SecureString'  | '$p = ConvertTo-SecureString "Sup3rS3cret!" -AsPlainText' | 'Sup3rS3cret!'
    }

    def "redact leaves non-secret text untouched"() {
        given:
        String text = 'Get-SCVirtualMachine -VMMServer localhost -ID "abc" | Select ID,Name'

        expect:
        ScvmmCommandSanitizer.redact(text) == text
    }

    def "firstLine returns the first non-blank trimmed line"() {
        expect:
        ScvmmCommandSanitizer.firstLine('\n\n   Access is denied.  \nAt line:1') == 'Access is denied.'
        ScvmmCommandSanitizer.firstLine('   ') == null
    }

    def "truncate leaves short strings alone and appends ellipsis to long ones"() {
        expect:
        ScvmmCommandSanitizer.truncate('short', 10) == 'short'
        ScvmmCommandSanitizer.truncate('0123456789abcdef', 10) == '0123456...'
        ScvmmCommandSanitizer.truncate(null, 10) == null
    }

    def "redactOpts masks sensitive keys recursively without mutating the original"() {
        given:
        Map opts = [sshHost: 'h', sshPassword: 'p', sshUsername: 'u', nested: [password: 'x', credentialPassword: 'y', ok: 1], cloudConfigBytes: new byte[2]]

        when:
        Map redacted = ScvmmCommandSanitizer.redactOpts(opts)

        then:
        redacted.sshHost == 'h'
        redacted.sshUsername == 'u'
        redacted.sshPassword == '***'
        redacted.nested.password == '***'
        redacted.nested.credentialPassword == '***'
        redacted.nested.ok == 1
        redacted.cloudConfigBytes == '***'
        opts.sshPassword == 'p'
        opts.nested.password == 'x'
    }
}
