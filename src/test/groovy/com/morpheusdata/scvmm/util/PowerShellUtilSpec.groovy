// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.util

import spock.lang.Shared
import spock.lang.Specification

class PowerShellUtilSpec extends Specification {
    @Shared
    String mockScript = '''
$vmId = '<%vmid%>'
$server = '<%server%>'
# PowerShell script content using $vmId and $server
Write-Output "VM ID: $vmId, Server: $server"
'''

    def "test loadPowerShellScript #description"() {
        when:
        Exception exception = null
        String result = null
        try {
            result = PowerShellUtil.loadPowerShellScript(scriptName)
        } catch (e) {
            exception = e
        }

        then:
        if (expectedException) {
            assert exception
            assert exception.message == expectedException
            assert !result
        } else {
            assert !exception
            assert result.contains(contains)
        }

        where:
        [description, scriptName, contains, expectedException] << [
                [
                        'Null script name',
                        null,
                        null,
                        'PowerShell script not found: /scripts/powershell/null',
                ],
                [
                        'Bogus script name',
                        'bogusScript.ps1',
                        null,
                        'PowerShell script not found: /scripts/powershell/bogusScript.ps1',
                ],
                [
                        'Success',
                        'listLibraryShares.ps1',
                        '$shares = Get-SCLibraryShare -VMMServer localhost',
                        null,
                ],
        ]
    }

    def "test loadPowerShellScriptWithTokens #description"() {
        given:
        PowerShellUtil.metaClass.'static'.loadPowerShellScript = { String scriptName ->
            assert scriptName == 'mockScript.ps1'
            return mockScript
        }

        when:
        String result = PowerShellUtil.loadPowerShellScriptWithTokens('mockScript.ps1', replacements)

        then:
        result == expectedResult

        cleanup:
        PowerShellUtil.metaClass.'static'.loadPowerShellScript = null

        where:
        [description, replacements, expectedResult] << [
                [
                        'Null replacements',
                        null,
                        '''
$vmId = '<%vmid%>'
$server = '<%server%>'
# PowerShell script content using $vmId and $server
Write-Output "VM ID: $vmId, Server: $server"
''',
                ],
                [
                        'Single replacement',
                        ['<%vmid%>': '12345'],
                        '''
$vmId = '12345'
$server = '<%server%>'
# PowerShell script content using $vmId and $server
Write-Output "VM ID: $vmId, Server: $server"
''',
                ],
                [
                        'Double replacement',
                        ['<%vmid%>': '54321', '<%server%>': 'mock-server'],
                        '''
$vmId = '54321'
$server = 'mock-server'
# PowerShell script content using $vmId and $server
Write-Output "VM ID: $vmId, Server: $server"
''',
                ],
        ]
    }

    def "test prettyPrintPowerShellScriptError #description"() {
        when:
        String result = PowerShellUtil.prettyPrintPowerShellScriptError(input)

        then:
        result == expectedOutput

        where:
        [description, input, expectedOutput] << [
                [
                        'Null input',
                        null,
                        null,
                ],
                [
                        'Empty string input',
                        '',
                        '',
                ],
                [
                        'Non-CLIXML error',
                        'This is a regular error message',
                        'This is a regular error message',
                ],
                [
                        'Oversized XML (exceeds 10 MB limit)',
                        '#< CLIXML\r\n' + '<Objs>' + 'x' * (11 * 1024 * 1024) + '</Objs>',
                        '#< CLIXML\r\n' + '<Objs>' + 'x' * (11 * 1024 * 1024) + '</Objs>',
                ],
                [
                        'Simple CLIXML with formatting',
                        '#< CLIXML\r\n<Objs Version="1.1.0.1"><S S="Error">Error message 1</S>\r\n\r\n' +
                                '<S S="Error">Error message 2</S></Objs>',
                        '<?xml version="1.0" encoding="UTF-8"?>' +
                                '<Objs Version="1.1.0.1">\n' +
                                '  <S S="Error">Error message 1</S>\n' +
                                '  <S S="Error">Error message 2</S>\n' +
                                '</Objs>\n',
                ],
                [
                        'CLIXML with multiple blank lines',
                        '#< CLIXML\r\n<root>\r\n\r\n\r\n<item>data</item>\r\n\r\n</root>',
                        '<?xml version="1.0" encoding="UTF-8"?>' +
                                '<root>\n' +
                                '  <item>data</item>\n' +
                                '</root>\n',
                ],
                [
                        'Malformed XML (invalid syntax)',
                        '#< CLIXML\r\n<Objs><unclosed>tag</Objs>',
                        '#< CLIXML\r\n<Objs><unclosed>tag</Objs>',
                ],
                [
                        'CLIXML with only header, no content',
                        '#< CLIXML\r\n',
                        '#< CLIXML\r\n',
                ],
                [
                        'Real PowerShell error CLIXML format',
                        '#< CLIXML\r\n' +
                                '<Objs Version="1.1.0.1" xmlns="http://schemas.microsoft.com/powershell/2004/04">' +
                                '<S S="Error">Get-SCVirtualMachine : Cannot bind parameter</S>' +
                                '<S S="Error">At line:2 char:82</S>' +
                                '</Objs>',
                        '<?xml version="1.0" encoding="UTF-8"?>' +
                                '<Objs xmlns="http://schemas.microsoft.com/powershell/2004/04" Version="1.1.0.1">\n' +
                                '  <S S="Error">Get-SCVirtualMachine : Cannot bind parameter</S>\n' +
                                '  <S S="Error">At line:2 char:82</S>\n' +
                                '</Objs>\n',
                ],
        ]
    }
}
