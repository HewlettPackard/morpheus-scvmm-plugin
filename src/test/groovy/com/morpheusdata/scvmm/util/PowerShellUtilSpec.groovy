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
}