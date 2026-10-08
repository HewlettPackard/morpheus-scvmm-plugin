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
        // address type is left unchanged unless explicitly requested
        !capturedCommand.contains('-IPv4AddressType')
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

    def "switches the adapter to a #type IPv4 address type when requested"() {
        given:
        stubExecute(success: true, exitCode: '0')

        when:
        def rtn = service.updateNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID, networkExternalId: NETWORK_ID, ipv4AddressType: type])

        then:
        rtn.success
        capturedCommand.contains("-VMNetwork \$VMNetwork")
        capturedCommand.contains("-IPv4AddressType ${type}")

        where:
        type << ['Dynamic', 'Static']
    }

    def "a null or empty IPv4 address type leaves the adapter type untouched"() {
        given:
        stubExecute(success: true, exitCode: '0')

        when:
        def rtn = service.updateNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID, networkExternalId: NETWORK_ID, ipv4AddressType: type])

        then:
        rtn.success
        !capturedCommand.contains('-IPv4AddressType')

        where:
        type << [null, '']
    }

    def "rejects an unknown IPv4 address type without calling SCVMM"() {
        when:
        def rtn = service.updateNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID, networkExternalId: NETWORK_ID, ipv4AddressType: 'Bogus'])

        then:
        0 * service.wrapExecuteCommand(*_)
        !rtn.success
        rtn.error == 'Invalid IPv4 address type Bogus for NIC update'
    }

    def "binds a granted static pool address: looks up the pool, forces Static and passes -IPv4Addresses / -IPv4AddressPools"() {
        given:
        stubExecute(success: true, exitCode: '0')
        String poolId = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'

        when:
        def rtn = service.updateNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID, networkExternalId: NETWORK_ID,
                ipv4AddressType: type, ipAddress: ' 10.20.30.40 ', poolExternalId: poolId])

        then:
        rtn.success
        capturedCommand.contains("\$IPPool = Get-SCStaticIPAddressPool -VMMServer localhost -ID \"${poolId}\"")
        capturedCommand.contains('if (-not $IPPool) { Write-Error "Static IP address pool ' + poolId + ' not found"; Exit 28 }')
        capturedCommand.contains('-IPv4AddressType Static')
        !capturedCommand.contains('-IPv4AddressType Dynamic')
        capturedCommand.contains('-IPv4Addresses "10.20.30.40" -IPv4AddressPools $IPPool')
        // pool lookup must happen before the Set call
        capturedCommand.indexOf('Get-SCStaticIPAddressPool') < capturedCommand.indexOf('Set-SCVirtualNetworkAdapter')

        where:
        type << [null, 'Static']
    }

    def "a pool id carrying a VLAN suffix is trimmed to the first 36 chars"() {
        given:
        stubExecute(success: true, exitCode: '0')

        when:
        def rtn = service.updateNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID, networkExternalId: NETWORK_ID,
                ipAddress: '10.0.0.1', poolExternalId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.200'])

        then:
        rtn.success
        capturedCommand.contains('-ID "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"')
        !capturedCommand.contains('eeeeeeeeeeee.200')
    }

    def "does not emit any pool lookup or address binding when no pool address is given"() {
        given:
        stubExecute(success: true, exitCode: '0')

        when:
        def rtn = service.updateNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID, networkExternalId: NETWORK_ID, ipv4AddressType: 'Dynamic'])

        then:
        rtn.success
        !capturedCommand.contains('Get-SCStaticIPAddressPool')
        !capturedCommand.contains('-IPv4Addresses')
        !capturedCommand.contains('-IPv4AddressPools')
    }

    def "rejects a pool binding when only #given is supplied"() {
        when:
        def rtn = service.updateNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID, networkExternalId: NETWORK_ID] + props)

        then:
        0 * service.wrapExecuteCommand(*_)
        !rtn.success
        rtn.error == 'Both ipAddress and poolExternalId are required to bind a static pool address'

        where:
        given            | props
        'ipAddress'      | [ipAddress: '10.0.0.1']
        'poolExternalId' | [poolExternalId: 'pool-1']
    }

    def "rejects an invalid IPv4 address for a pool binding"() {
        when:
        def rtn = service.updateNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID, networkExternalId: NETWORK_ID,
                ipAddress: ip, poolExternalId: 'pool-1'])

        then:
        0 * service.wrapExecuteCommand(*_)
        !rtn.success
        rtn.error == "Invalid IPv4 address ${ip} for NIC update"

        where:
        ip << ['10.0.0', 'fe80::1', '10.0.0.1; Remove-SCVirtualMachine', 'abc']
    }

    def "refuses to bind a pool address to a Dynamic adapter"() {
        when:
        def rtn = service.updateNetworkInterface(opts, VM_ID, [adapterId: ADAPTER_ID, networkExternalId: NETWORK_ID,
                ipv4AddressType: 'Dynamic', ipAddress: '10.0.0.1', poolExternalId: 'pool-1'])

        then:
        0 * service.wrapExecuteCommand(*_)
        !rtn.success
        rtn.error == 'A static pool address cannot be bound to a Dynamic adapter'
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
