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

    def "test parseCliXml #description"() {
        when:
        List<String> result = PowerShellUtil.parseCliXml(cliXml)

        then:
        if (expectedPatterns == null) {
            assert result == null
        } else {
            assert result != null
            assert result.size() == expectedSize
            result.each { message ->
                assert expectedPatterns.any { pattern -> message.contains(pattern) }
            }
        }

        where:
        [description, cliXml, expectedSize, expectedPatterns] << [
                // === Error Cases ===
                [
                        'Null input',
                        null,
                        0,
                        null,
                ],
                [
                        'Empty string',
                        '',
                        0,
                        null,
                ],
                [
                        'Invalid format - no CLIXML header',
                        '<Objs Version="1.1.0.1"><S S="Error">Error message</S></Objs>',
                        0,
                        null,
                ],
                [
                        'Oversized input (exceeds 10 MB limit)',
                        '#< CLIXML\r\n' + ('x' * (11 * 1024 * 1024)),
                        0,
                        null,
                ],
                [
                        'Malformed XML - missing closing tag',
                        '#< CLIXML\r\n<Objs Version="1.1.0.1"><S S="Error">Error message',
                        0,
                        null,
                ],
                [
                        'Empty XML',
                        '#< CLIXML\r\n<Objs Version="1.1.0.1"></Objs>',
                        0,
                        [],
                ],

                // === Valid Cases ===
                [
                        'Single error message',
                        '#< CLIXML\r\n<Objs Version="1.1.0.1"><S S="Error">Test error message</S></Objs>',
                        1,
                        ['[ERROR]', 'Test error message'],
                ],
                [
                        'Single warning message',
                        '#< CLIXML\r\n<Objs Version="1.1.0.1"><S S="Warning">Test warning message</S></Objs>',
                        1,
                        ['[WARNING]', 'Test warning message'],
                ],
                [
                        'Multiple messages',
                        '#< CLIXML\r\n<Objs Version="1.1.0.1"><S S="Error">Error 1</S><S S="Warning">Warning 1</S><S S="Error">Error 2</S></Objs>',
                        3,
                        ['[ERROR]', '[WARNING]', 'Error 1', 'Warning 1', 'Error 2'],
                ],
                [
                        'Message with hex-encoded line breaks',
                        '#< CLIXML\r\n<Objs Version="1.1.0.1"><S S="Error">Line 1_x000D__x000A_Line 2</S></Objs>',
                        1,
                        ['[ERROR]', 'Line 1', 'Line 2'],
                ],
                [
                        'Message with hex-encoded tab',
                        '#< CLIXML\r\n<Objs><S S="Error">Column1_x0009_Column2</S></Objs>',
                        1,
                        ['[ERROR]', 'Column1', 'Column2'],
                ],
                [
                        'Message with invalid control character (replaced with ?)',
                        '#< CLIXML\r\n<Objs><S S="Error">Invalid_x0001_Char</S></Objs>',
                        1,
                        ['[ERROR]', 'Invalid?Char'],
                ],
                [
                        'Message with lowercase hex codes',
                        '#< CLIXML\r\n<Objs><S S="Error">test_x000d__x000a_msg</S></Objs>',
                        1,
                        ['[ERROR]', 'test', 'msg'],
                ],
                [
                        'Message with mixed case hex codes',
                        '#< CLIXML\r\n<Objs><S S="Error">Test_x000D__x000a_Message</S></Objs>',
                        1,
                        ['[ERROR]', 'Test', 'Message'],
                ],
                [
                        'Missing S attribute (defaults to UNKNOWN)',
                        '#< CLIXML\r\n<Objs Version="1.1.0.1"><S>Unknown type message</S></Objs>',
                        1,
                        ['[UNKNOWN]', 'Unknown type message'],
                ],
                [
                        'Empty stream type (defaults to UNKNOWN)',
                        '#< CLIXML\r\n<Objs Version="1.1.0.1"><S S="">Empty type message</S></Objs>',
                        1,
                        ['[UNKNOWN]', 'Empty type message'],
                ],
                [
                        'Empty message content (skipped)',
                        '#< CLIXML\r\n<Objs Version="1.1.0.1"><S S="Error">   </S></Objs>',
                        0,
                        [],
                ],
                [
                        'Lowercase stream type uppercase in output',
                        '#< CLIXML\r\n<Objs Version="1.1.0.1"><S S="error">lowercase error</S></Objs>',
                        1,
                        ['[ERROR]', 'lowercase error'],
                ],
        ]
    }

    def "test parseCliXml message size limit"() {
        given:
        String largeContent = 'x' * (2 * 1024 * 1024)  // 150 KB - exceeds 1 MiB limit
        String cliXml = "#< CLIXML\r\n<Objs Version=\"1.1.0.1\"><S S=\"Error\">${largeContent}</S></Objs>"

        when:
        List<String> result = PowerShellUtil.parseCliXml(cliXml)

        then:
        result == null
    }

    def "test parseCliXml message count limit"() {
        given:
        StringBuilder cliXmlBuilder = new StringBuilder('#< CLIXML\r\n<Objs Version="1.1.0.1">')
        1500.times { i ->
            cliXmlBuilder.append("<S S=\"Error\">Message ${i}</S>")
        }
        cliXmlBuilder.append('</Objs>')
        String cliXml = cliXmlBuilder.toString()

        when:
        List<String> result = PowerShellUtil.parseCliXml(cliXml)

        then:
        result.size() == 1000  // Should be limited to 1000 messages
        result[0].contains('Message 0')
        !result.any { it.contains('Message 1499') }  // Last message should not be included
    }

    def "test parseCliXml with invalid hex code"() {
        given:
        String cliXml = '#< CLIXML\r\n<Objs Version="1.1.0.1"><S S="Error">Invalid_xGGGG_Hex</S></Objs>'

        when:
        List<String> result = PowerShellUtil.parseCliXml(cliXml)

        then:
        result.size() == 1
        // Invalid hex (GGGG is not valid hex) won't match the pattern, so original text is preserved
        result[0].contains('[ERROR]')
        result[0].contains('Invalid_xGGGG_Hex')
    }


}