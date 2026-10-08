// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import spock.lang.Specification

/**
 * SCVMM errors reach the plugin as PowerShell CLIXML on stderr; the UI must show the actual message.
 */
class ScvmmApiServiceCleanPowerShellErrorSpec extends Specification {

    static final String ERROR_640 = '''#< CLIXML
<Objs Version="1.1.0.1" xmlns="http://schemas.microsoft.com/powershell/2004/04"><S S="Error">New-SCVirtualNetworkAdapter : The virtual machine must be in either the Poweroff or Stored state before SCVMM can _x000D__x000A_</S><S S="Error">perform the specified hardware changes. (Error ID: 640, Detailed Error: )_x000D__x000A_</S><S S="Error"> _x000D__x000A_</S><S S="Error">Turn off the virtual machine or store it to the library, and then try the operation again._x000D__x000A_</S><S S="Error"> _x000D__x000A_</S><S S="Error">To restart the job, run the following command:_x000D__x000A_</S><S S="Error">PS&gt; Restart-Job -Job (Get-VMMServer localhost | Get-Job | where { $_.ID -eq "{c28eab08-f575-4efe-97c7-1093aac4e540}"})_x000D__x000A_</S><S S="Error">At line:2 char:431_x000D__x000A_</S><S S="Error">+ ... 25 };$NIC = New-SCVirtualNetworkAdapter -VM $VM -VMNetwork $VMNetwork ..._x000D__x000A_</S><S S="Error">+                 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~_x000D__x000A_</S><S S="Error">    + CategoryInfo          : ReadError: (:) [New-SCVirtualNetworkAdapter], CarmineException_x000D__x000A_</S><S S="Error">    + FullyQualifiedErrorId : 640,Microsoft.SystemCenter.VirtualMachineManager.Cmdlets.NewNICCmdlet_x000D__x000A_</S></Objs>'''

    def "extracts the SCVMM message from CLIXML and drops the restart-job and stack noise"() {
        when:
        def message = ScvmmApiService.cleanPowerShellError(ERROR_640)

        then:
        message == 'New-SCVirtualNetworkAdapter : The virtual machine must be in either the Poweroff or Stored state before SCVMM can perform the specified hardware changes. (Error ID: 640, Detailed Error: ) Turn off the virtual machine or store it to the library, and then try the operation again.'
        !message.contains('CLIXML')
        !message.contains('Restart-Job')
        !message.contains('CategoryInfo')
    }

    def "unescapes XML entities inside the message"() {
        expect:
        ScvmmApiService.cleanPowerShellError('<Objs><S S="Error">Value &lt;x&gt; &amp; &quot;y&quot;_x000D__x000A_</S></Objs>') == 'Value <x> & "y"'
    }

    def "returns plain text untouched apart from trimming"() {
        expect:
        ScvmmApiService.cleanPowerShellError('  Network adapter not found \n') == 'Network adapter not found'
    }

    def "returns null for empty input"() {
        expect:
        ScvmmApiService.cleanPowerShellError(input) == null

        where:
        input << [null, '', '   ']
    }

    def "falls back to the raw text when CLIXML carries no error strings"() {
        given:
        def raw = '<Objs Version="1.1.0.1"><S S="Progress">working</S></Objs>'

        expect:
        ScvmmApiService.cleanPowerShellError(raw) == raw
    }
}
