// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.TaskResult
import spock.lang.Specification
import spock.lang.Subject

class ScvmmApiServiceUpdateNetworkInterfaceSpec extends Specification {

    static final String VM_ID = 'vm-1111-2222'
    static final String ADAPTER_ID = 'adapter-aaaa-bbbb'
    static final String MAC = '00:15:5D:01:02:03'
    static final String NETWORK_ID = '0d8f4c2a-1b2c-4d3e-9f10-112233445566'
    static final String SUBNET_ID = '7e6d5c4b-3a29-4817-8695-aabbccddeeff'

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

    def "returns an error and does not call SCVMM when no target network is given"() {
        when:
        def rtn = service.updateNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID])

        then:
        0 * service.wrapExecuteCommand(*_)
        !rtn.success
        rtn.error == 'No target network provided for NIC update'
    }

    def "returns an error and does not call SCVMM when neither adapter id nor MAC is given"() {
        when:
        def rtn = service.updateNetworkInterface(opts, VM_ID, [networkExternalId: NETWORK_ID])

        then:
        0 * service.wrapExecuteCommand(*_)
        !rtn.success
        rtn.error == 'No adapter identifier provided for NIC update'
    }

    def "builds a Set-SCVirtualNetworkAdapter command for a plain (non-VLAN, no subnet) network change"() {
        given:
        stubExecute(success: true, exitCode: '0', data: 'true')

        when:
        def rtn = service.updateNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID, macAddress: MAC, networkExternalId: NETWORK_ID])

        then:
        rtn.success
        !rtn.error
        capturedCommand.contains("Get-SCVirtualMachine -VMMServer localhost -ID \"${VM_ID}\"")
        // adapter id is preferred over MAC when both are present
        capturedCommand.contains("where { \$_.ID -eq \"${ADAPTER_ID}\" }")
        !capturedCommand.contains('MACAddress -eq')
        capturedCommand.contains("Get-SCVMNetwork -VMMServer localhost -ID \"${NETWORK_ID}\"")
        capturedCommand.contains('Set-SCVirtualNetworkAdapter -VirtualNetworkAdapter $VirtualNetworkAdapter -VMNetwork $VMNetwork')
        capturedCommand.contains('-VLanEnabled $false')
        !capturedCommand.contains('-VLanID')
        !capturedCommand.contains('Get-SCVMSubnet')
        !capturedCommand.contains('-VMSubnet')
        // guards for missing objects and a failing Set call
        capturedCommand.contains('if (-not $VirtualNetworkAdapter) { Write-Error "Network adapter not found"; Exit 24 }')
        capturedCommand.contains("if (-not \$VMNetwork) { Write-Error \"VM network ${NETWORK_ID} not found\"; Exit 25 }")
        capturedCommand.contains('if (-not $?) { Exit 27 }')
    }

    def "falls back to matching the adapter by MAC address when no adapter id is known"() {
        given:
        stubExecute(success: true, exitCode: '0')

        when:
        def rtn = service.updateNetworkInterface(opts, VM_ID, [macAddress: MAC, networkExternalId: NETWORK_ID])

        then:
        rtn.success
        capturedCommand.contains("where { \$_.MACAddress -eq \"${MAC}\" }")
        !capturedCommand.contains('$_.ID -eq')
    }

    def "passes the VM subnet and VLAN when provided"() {
        given:
        stubExecute(success: true, exitCode: '0')

        when:
        def rtn = service.updateNetworkInterface(opts, VM_ID, [
                adapterId        : ADAPTER_ID,
                networkExternalId: NETWORK_ID,
                subnetExternalId : SUBNET_ID,
                vlanEnabled      : true,
                vlanId           : 120
        ])

        then:
        rtn.success
        capturedCommand.contains("\$VMSubnet = Get-SCVMSubnet -VMMServer localhost -ID \"${SUBNET_ID}\"")
        capturedCommand.contains("if (-not \$VMSubnet) { Write-Error \"VM subnet ${SUBNET_ID} not found\"; Exit 26 }")
        capturedCommand.contains('-VMSubnet $VMSubnet')
        capturedCommand.contains('-VLanEnabled $true -VLanID 120')
    }

    def "VLAN is disabled when vlanEnabled is true but no VLAN id is supplied"() {
        given:
        stubExecute(success: true, exitCode: '0')

        when:
        service.updateNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID, networkExternalId: NETWORK_ID, vlanEnabled: true, vlanId: null])

        then:
        capturedCommand.contains('-VLanEnabled $false')
        !capturedCommand.contains('-VLanID')
    }

    def "strips the VLAN suffix from network and subnet external ids (first 36 chars only)"() {
        given:
        stubExecute(success: true, exitCode: '0')

        when:
        service.updateNetworkInterface(opts, VM_ID, [
                adapterId        : ADAPTER_ID,
                networkExternalId: "${NETWORK_ID}-120",
                subnetExternalId : "${SUBNET_ID}-120"
        ])

        then:
        capturedCommand.contains("Get-SCVMNetwork -VMMServer localhost -ID \"${NETWORK_ID}\"")
        capturedCommand.contains("Get-SCVMSubnet -VMMServer localhost -ID \"${SUBNET_ID}\"")
        !capturedCommand.contains("${NETWORK_ID}-120")
        !capturedCommand.contains("${SUBNET_ID}-120")
    }

    def "wraps the command with generateCommandString so the result is JSON"() {
        given:
        stubExecute(success: true, exitCode: '0')

        when:
        service.updateNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID, networkExternalId: NETWORK_ID])

        then:
        capturedCommand.startsWith('$FormatEnumerationLimit =-1;')
        capturedCommand.endsWith('| ConvertTo-Json -Depth 3')
    }

    def "reports failure with the SCVMM error when the exit code is non-zero"() {
        given:
        stubExecute(success: true, exitCode: '24', error: 'Network adapter not found')

        when:
        def rtn = service.updateNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID, networkExternalId: NETWORK_ID])

        then:
        !rtn.success
        rtn.error == 'Network adapter not found'
    }

    def "reports failure when the command itself did not succeed, falling back to msg then a default"() {
        given:
        stubExecute(success: false, exitCode: '1', error: null, msg: msg)

        when:
        def rtn = service.updateNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID, networkExternalId: NETWORK_ID])

        then:
        !rtn.success
        rtn.error == expectedError

        where:
        msg              | expectedError
        'winrm timeout'  | 'winrm timeout'
        null             | 'Failed to update network adapter'
    }

    def "captures exceptions thrown while executing and returns them as an error"() {
        given:
        service.wrapExecuteCommand(_ as String, _ as Map) >> { throw new RuntimeException('boom') }

        when:
        def rtn = service.updateNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID, networkExternalId: NETWORK_ID])

        then:
        !rtn.success
        rtn.error == 'boom'
    }
}
