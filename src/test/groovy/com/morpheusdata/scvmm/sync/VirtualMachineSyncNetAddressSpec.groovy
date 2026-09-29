// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.sync

import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.NetAddress
import spock.lang.Specification

/**
 * MORPH-4114: an SCVMM adapter may report several IPv4 and IPv6 addresses, either as arrays or (when
 * ConvertTo-Json ran out of depth) as delimited strings. Every address must become exactly one NetAddress
 * of the correct type.
 */
class VirtualMachineSyncNetAddressSpec extends Specification {

    private static List<String> render(List<NetAddress> addresses) {
        addresses.collect { "${it.type}=${it.address}".toString() }
    }

    def "returns empty list when adapter reports no addresses"() {
        expect:
        VirtualMachineSync.buildNetAddresses(ipv4, ipv6) == []

        where:
        ipv4       | ipv6
        null       | null
        []         | []
        ''         | ''
        [null, ''] | ['  ']
    }

    def "builds one typed NetAddress per address for multi-IP adapters given arrays"() {
        when:
        def result = VirtualMachineSync.buildNetAddresses(
            ['10.0.0.5', '10.0.0.6', '192.168.1.20'],
            ['fe80::1', '2001:db8::10']
        )

        then:
        render(result) == [
            'IPV4=10.0.0.5', 'IPV4=10.0.0.6', 'IPV4=192.168.1.20',
            'IPV6=fe80::1', 'IPV6=2001:db8::10'
        ]
    }

    def "builds one typed NetAddress per address for multi-IP adapters given collapsed strings"() {
        when:
        def result = VirtualMachineSync.buildNetAddresses('10.0.0.5 10.0.0.6 192.168.1.20', 'fe80::1 2001:db8::10')

        then:
        render(result) == [
            'IPV4=10.0.0.5', 'IPV4=10.0.0.6', 'IPV4=192.168.1.20',
            'IPV6=fe80::1', 'IPV6=2001:db8::10'
        ]
        // the pre-fix behaviour iterated the string per character
        result.every { it.address.length() > 1 }
    }

    def "handles mixed shapes, duplicates and single-address adapters"() {
        expect:
        render(VirtualMachineSync.buildNetAddresses(ipv4, ipv6)) == expected

        where:
        ipv4                     | ipv6                   || expected
        ['10.0.0.5']             | null                   || ['IPV4=10.0.0.5']
        null                     | 'fe80::1'              || ['IPV6=fe80::1']
        ['10.0.0.5', '10.0.0.5'] | ['fe80::1', 'fe80::1'] || ['IPV4=10.0.0.5', 'IPV6=fe80::1']
        '10.0.0.5, 10.0.0.6'     | ['fe80::1']            || ['IPV4=10.0.0.5', 'IPV4=10.0.0.6', 'IPV6=fe80::1']
        ['10.0.0.5'] as Object[] | 'fe80::1;fe80::2'      || ['IPV4=10.0.0.5', 'IPV6=fe80::1', 'IPV6=fe80::2']
    }

    def "multi-IP address list survives being assigned to a ComputeServerInterface"() {
        given:
        def iface = new ComputeServerInterface()

        when:
        iface.addresses = VirtualMachineSync.buildNetAddresses(['10.0.0.5', '10.0.0.6'], ['fe80::1', '2001:db8::10'])

        then:
        render(iface.addresses) == ['IPV4=10.0.0.5', 'IPV4=10.0.0.6', 'IPV6=fe80::1', 'IPV6=2001:db8::10']
        // deprecated accessors are derived from addresses, no separate assignment needed
        iface.ipAddress in ['10.0.0.5', '10.0.0.6']
        iface.ipv6Address in ['fe80::1', '2001:db8::10']
    }

    def "documents why the deprecated ipv6Address setter must not be used alongside addresses"() {
        given:
        def iface = new ComputeServerInterface()
        iface.addresses = VirtualMachineSync.buildNetAddresses(['10.0.0.5'], ['fe80::1'])

        when: 'the plugin-api setter is invoked (it writes the IPv4 field into the IPv6 entry)'
        iface.ipv6Address = 'fe80::1'

        then: 'the IPv6 entry is corrupted, which is why syncInterfaces only assigns addresses'
        iface.addresses.find { it.type == NetAddress.AddressType.IPV6 }.address == null
    }
}
