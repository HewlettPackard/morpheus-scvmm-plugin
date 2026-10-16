// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import spock.lang.Specification

/**
 * Grant-SCIPAddress must be scoped to the object that will use the address: an anonymous grant followed by
 * Set-SCVirtualNetworkAdapter -IPv4AddressPools makes VMM allocate a second address of its own.
 */
class ScvmmApiServiceReserveIPAddressSpec extends Specification {

    static final String POOL = '4784149f-a6ed-4944-8868-9a0df5185f4d'
    static final String PREFIX = '$ippool = Get-SCStaticIPAddressPool -VMMServer localhost -ID "' + POOL + '"; '
    static final String SUFFIX = ' | Select-Object ID,Address'

    def "default grant is an anonymous VirtualMachine grant for the next free address (provisioning path, unchanged)"() {
        expect:
        ScvmmApiService.buildReserveIPAddressCommand(POOL) ==
            PREFIX + 'Grant-SCIPAddress -GrantToObjectType "VirtualMachine" -StaticIPAddressPool $ippool' + SUFFIX
    }

    def "a requested address is passed with -IPAddress"() {
        expect:
        ScvmmApiService.buildReserveIPAddressCommand(POOL, '10.157.232.120') ==
            PREFIX + 'Grant-SCIPAddress -GrantToObjectType "VirtualMachine" -StaticIPAddressPool $ippool -IPAddress "10.157.232.120"' + SUFFIX
    }

    def "a grant scoped to an adapter carries -GrantToObjectType VirtualNetworkAdapter, -GrantToObjectID and -Description"() {
        expect:
        ScvmmApiService.buildReserveIPAddressCommand(POOL, '10.157.232.120',
            [objectType: 'VirtualNetworkAdapter', objectId: '19319a59-78e8-4d42-9a51-1f30fd98d63b', description: 'morpheus:p189-2nic']) ==
            PREFIX + 'Grant-SCIPAddress -GrantToObjectType "VirtualNetworkAdapter" -StaticIPAddressPool $ippool' +
            ' -GrantToObjectID "19319a59-78e8-4d42-9a51-1f30fd98d63b" -IPAddress "10.157.232.120" -Description "morpheus:p189-2nic"' + SUFFIX
    }

    def "a grant scoped to a VM uses the VM object type and id"() {
        expect:
        ScvmmApiService.buildReserveIPAddressCommand(POOL, null, [objectType: 'VirtualMachine', objectId: 'vm-guid']) ==
            PREFIX + 'Grant-SCIPAddress -GrantToObjectType "VirtualMachine" -StaticIPAddressPool $ippool -GrantToObjectID "vm-guid"' + SUFFIX
    }

    def "description quotes are escaped for PowerShell"() {
        expect:
        ScvmmApiService.buildReserveIPAddressCommand(POOL, null, [description: 'vm "one"']).contains('-Description "vm `"one`""')
    }

    def "empty or null grant map behaves like the default"() {
        expect:
        ScvmmApiService.buildReserveIPAddressCommand(POOL, null, grant) == ScvmmApiService.buildReserveIPAddressCommand(POOL)

        where:
        grant << [null, [:], [objectType: null, objectId: null, description: null]]
    }
}
