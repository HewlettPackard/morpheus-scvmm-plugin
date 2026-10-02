// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.TaskResult
import spock.lang.Specification
import spock.lang.Subject

class ScvmmApiServiceAddNetworkInterfaceSpec extends Specification {

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

    private static List nicPayload(Map overrides = [:]) {
        [[ID: ADAPTER_ID, MACAddress: MAC, SlotId: 1, Name: 'Network Adapter 2'] + overrides]
    }

    def "returns an error and does not call SCVMM when no target network is given"() {
        when:
        def rtn = service.addNetworkInterface(opts, VM_ID, [vlanEnabled: false])

        then:
        0 * service.wrapExecuteCommand(*_)
        !rtn.success
        rtn.error == 'No target network provided for NIC add'
    }

    def "builds a New-SCVirtualNetworkAdapter -VM command for a plain (non-VLAN, no subnet) network"() {
        given:
        stubExecute(success: true, exitCode: '0', data: nicPayload())

        when:
        def rtn = service.addNetworkInterface(opts, VM_ID, [networkExternalId: NETWORK_ID])

        then:
        rtn.success
        rtn.adapterId == ADAPTER_ID
        rtn.macAddress == MAC
        rtn.slotId == 1
        capturedCommand.contains("\$VM = Get-SCVirtualMachine -VMMServer localhost -ID \"${VM_ID}\"")
        capturedCommand.contains('if (-not $VM) { Write-Error "Virtual machine vm-1111-2222 not found"; Exit 23 }')
        capturedCommand.contains("\$VMNetwork = Get-SCVMNetwork -VMMServer localhost -ID \"${NETWORK_ID}\"")
        capturedCommand.contains('Exit 25')
        capturedCommand.contains('New-SCVirtualNetworkAdapter -VM $VM -VMNetwork $VMNetwork')
        capturedCommand.contains('-VLanEnabled $false')
        capturedCommand.contains('-Synthetic')
        capturedCommand.contains('-MACAddressType Dynamic')
        capturedCommand.contains('-IPv4AddressType Dynamic -IPv6AddressType Dynamic')
        capturedCommand.contains('if (-not $? -or -not $NIC) { Exit 27 }')
        capturedCommand.contains('$NIC | Select-Object ID, MACAddress, SlotId, Name')
        capturedCommand.endsWith('| ConvertTo-Json -Depth 3')
        !capturedCommand.contains('-VLanID')
        !capturedCommand.contains('Get-SCVMSubnet')
        !capturedCommand.contains('-VMSubnet')
        !capturedCommand.contains('-JobGroup')
    }

    def "adds the VLAN arguments when the target network is a VLAN network"() {
        given:
        stubExecute(success: true, exitCode: '0', data: nicPayload())

        when:
        def rtn = service.addNetworkInterface(opts, VM_ID, [networkExternalId: NETWORK_ID, vlanEnabled: true, vlanId: 120])

        then:
        rtn.success
        capturedCommand.contains('-VLanEnabled $true -VLanID 120')
        !capturedCommand.contains('-VLanEnabled $false')
    }

    def "does not enable VLAN when vlanEnabled is true but no vlanId is given"() {
        given:
        stubExecute(success: true, exitCode: '0', data: nicPayload())

        when:
        def rtn = service.addNetworkInterface(opts, VM_ID, [networkExternalId: NETWORK_ID, vlanEnabled: true, vlanId: null])

        then:
        rtn.success
        capturedCommand.contains('-VLanEnabled $false')
        !capturedCommand.contains('-VLanID')
    }

    def "looks up the VM subnet and passes -VMSubnet when a subnet is given"() {
        given:
        stubExecute(success: true, exitCode: '0', data: nicPayload())

        when:
        def rtn = service.addNetworkInterface(opts, VM_ID, [networkExternalId: NETWORK_ID, subnetExternalId: SUBNET_ID])

        then:
        rtn.success
        capturedCommand.contains("\$VMSubnet = Get-SCVMSubnet -VMMServer localhost -ID \"${SUBNET_ID}\"")
        capturedCommand.contains('Exit 26')
        capturedCommand.contains('-VMSubnet $VMSubnet')
    }

    def "truncates network and subnet external ids to the 36-char GUID (strips VLAN suffix)"() {
        given:
        stubExecute(success: true, exitCode: '0', data: nicPayload())

        when:
        def rtn = service.addNetworkInterface(opts, VM_ID,
                [networkExternalId: "${NETWORK_ID}.120", subnetExternalId: "${SUBNET_ID}.120"])

        then:
        rtn.success
        capturedCommand.contains("Get-SCVMNetwork -VMMServer localhost -ID \"${NETWORK_ID}\"")
        capturedCommand.contains("Get-SCVMSubnet -VMMServer localhost -ID \"${SUBNET_ID}\"")
        !capturedCommand.contains("${NETWORK_ID}.120")
    }

    def "accepts a single adapter object (non-list) payload"() {
        given:
        stubExecute(success: true, exitCode: '0', data: [ID: ADAPTER_ID, MACAddress: MAC])

        when:
        def rtn = service.addNetworkInterface(opts, VM_ID, [networkExternalId: NETWORK_ID])

        then:
        rtn.success
        rtn.adapterId == ADAPTER_ID
        rtn.macAddress == MAC
    }

    def "fails when SCVMM reports success but returns no adapter ID"() {
        given:
        stubExecute(success: true, exitCode: '0', data: payload)

        when:
        def rtn = service.addNetworkInterface(opts, VM_ID, [networkExternalId: NETWORK_ID])

        then:
        !rtn.success
        rtn.error == 'Network adapter created but no adapter ID was returned'

        where:
        payload << [null, [], [[MACAddress: MAC]], [ID: null]]
    }

    def "fails when the command exits non-zero and surfaces the SCVMM error"() {
        given:
        stubExecute(success: false, exitCode: exitCode, error: error, msg: msg)

        when:
        def rtn = service.addNetworkInterface(opts, VM_ID, [networkExternalId: NETWORK_ID])

        then:
        !rtn.success
        rtn.error == expectedError

        where:
        exitCode | error                       | msg               || expectedError
        '23'     | 'Virtual machine not found' | null              || 'Virtual machine not found'
        '25'     | 'VM network not found'      | null              || 'VM network not found'
        '26'     | null                        | 'subnet missing'  || 'subnet missing'
        '27'     | null                        | null              || 'Failed to add network adapter'
    }

    def "fails when the command succeeded at transport level but exit code is non-zero"() {
        given:
        stubExecute(success: true, exitCode: '27', data: null)

        when:
        def rtn = service.addNetworkInterface(opts, VM_ID, [networkExternalId: NETWORK_ID])

        then:
        !rtn.success
        rtn.error == 'Failed to add network adapter'
    }

    def "returns the exception message when command execution throws"() {
        given:
        service.wrapExecuteCommand(_ as String, _ as Map) >> { throw new RuntimeException('ssh boom') }

        when:
        def rtn = service.addNetworkInterface(opts, VM_ID, [networkExternalId: NETWORK_ID])

        then:
        !rtn.success
        rtn.error == 'ssh boom'
    }
}
