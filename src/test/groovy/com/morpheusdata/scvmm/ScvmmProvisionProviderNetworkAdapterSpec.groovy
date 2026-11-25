// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.NetAddress
import spock.lang.Specification

/**
 * MORPH-16995: the primary interface created during provisioning must be bound to the SCVMM network
 * adapter (MAC address + adapter ID) reported by getServerDetails.
 */
class ScvmmProvisionProviderNetworkAdapterSpec extends Specification {

    private static Map adapter(String id, Integer slot, String mac, List<String> ipv4 = [], List<String> ipv6 = []) {
        [ID: id, SlotId: slot, MacAddress: mac, IPv4Addresses: ipv4, IPv6Addresses: ipv6]
    }

    def "returns null when server detail has no network adapters"() {
        expect:
        ScvmmProvisionProvider.findPrimaryNetworkAdapter(null, '10.0.0.5') == null
        ScvmmProvisionProvider.findPrimaryNetworkAdapter([:], '10.0.0.5') == null
        ScvmmProvisionProvider.findPrimaryNetworkAdapter([NetworkAdapters: []], '10.0.0.5') == null
        ScvmmProvisionProvider.findPrimaryNetworkAdapter([NetworkAdapters: [null]], '10.0.0.5') == null
    }

    def "prefers slot 0 even when the detected ip belongs to a secondary adapter"() {
        given: 'slot 1 has its pool address before slot 0 has a DHCP lease'
        def detail = [NetworkAdapters: [
            adapter('nic-1', 1, '00:15:5D:00:00:02', ['10.0.0.5']),
            adapter('nic-0', 0, '00:15:5D:00:00:01')
        ]]

        when:
        def result = ScvmmProvisionProvider.findPrimaryNetworkAdapter(detail, '10.0.0.5')

        then:
        result.ID == 'nic-0'
        result.MacAddress == '00:15:5D:00:00:01'
    }

    def "prefers the adapter holding the detected ip when there is no slot 0"() {
        given:
        def detail = [NetworkAdapters: [
            adapter('nic-1', 1, '00:15:5D:00:00:02', ['10.0.0.4']),
            adapter('nic-2', 2, '00:15:5D:00:00:03', ['10.0.0.5'])
        ]]

        expect:
        ScvmmProvisionProvider.findPrimaryNetworkAdapter(detail, '10.0.0.5').ID == 'nic-2'
    }

    def "matches ipv6 addresses and tolerates surrounding whitespace"() {
        given:
        def detail = [NetworkAdapters: [
            adapter('nic-2', 2, '00:15:5D:00:00:01'),
            adapter('nic-1', 1, '00:15:5D:00:00:02', [], ['fe80::1'])
        ]]

        expect:
        ScvmmProvisionProvider.findPrimaryNetworkAdapter(detail, " fe80::1\n").ID == 'nic-1'
    }

    def "matches when ConvertTo-Json collapsed the address arrays into strings"() {
        given:
        def detail = [NetworkAdapters: [
            [ID: 'nic-2', SlotId: 2, MacAddress: '00:15:5D:00:00:01', IPv4Addresses: '10.0.0.4', IPv6Addresses: ''],
            [ID: 'nic-1', SlotId: 1, MacAddress: '00:15:5D:00:00:02', IPv4Addresses: '10.0.0.5 10.0.0.6', IPv6Addresses: 'fe80::2']
        ]]

        expect:
        ScvmmProvisionProvider.findPrimaryNetworkAdapter(detail, '10.0.0.6').ID == 'nic-1'
        ScvmmProvisionProvider.findPrimaryNetworkAdapter(detail, 'fe80::2').ID == 'nic-1'
        // a single character must never match as an address
        ScvmmProvisionProvider.findPrimaryNetworkAdapter(detail, '5').ID == 'nic-2'
    }

    def "falls back to slot 0 when no adapter reports the ip yet"() {
        given:
        def detail = [NetworkAdapters: [
            adapter('nic-1', 1, '00:15:5D:00:00:02'),
            adapter('nic-0', 0, '00:15:5D:00:00:01')
        ]]

        expect:
        ScvmmProvisionProvider.findPrimaryNetworkAdapter(detail, '10.0.0.5').ID == 'nic-0'
        ScvmmProvisionProvider.findPrimaryNetworkAdapter(detail, null).ID == 'nic-0'
    }

    def "falls back to the first adapter when neither ip nor slot 0 matches"() {
        given:
        def detail = [NetworkAdapters: [
            adapter('nic-2', 2, '00:15:5D:00:00:03'),
            adapter('nic-3', 3, '00:15:5D:00:00:04')
        ]]

        expect:
        ScvmmProvisionProvider.findPrimaryNetworkAdapter(detail, '10.0.0.5').ID == 'nic-2'
    }

    // ---------------------------------------------------------------- findInterfaceForAdapter

    private static ComputeServerInterface row(Long id, Integer order, Map extra = [:]) {
        def iface = new ComputeServerInterface([id: id, name: "eth${order}".toString(), displayOrder: order, primaryInterface: order == 0] + extra)
        iface
    }

    def "matches a row by its addresses"() {
        given:
        def eth0 = row(1L, 0)
        def eth1 = row(2L, 1, [addresses: [new NetAddress(type: NetAddress.AddressType.IPV4, address: '10.0.1.200')]])
        def slot1 = adapter('slot1', 1, 'BB', ['10.0.1.200'])

        expect:
        ScvmmProvisionProvider.findInterfaceForAdapter([eth0, eth1], slot1, null, 0, [adapter('slot0', 0, 'AA'), slot1]).is(eth1)
    }

    def "maps a secondary adapter by rank when slot IDs and display orders have gaps"() {
        given: 'slot 1 was removed in SCVMM and display order 1 in Morpheus'
        def rows = [row(1L, 0), row(2L, 2), row(3L, 3)]
        def adapters = [adapter('slot0', 0, 'AA'), adapter('slot2', 2, 'BB'), adapter('slot3', 3, 'CC')]

        expect:
        ScvmmProvisionProvider.findInterfaceForAdapter(rows, adapters[1], null, 0, adapters).is(rows[1])
        ScvmmProvisionProvider.findInterfaceForAdapter(rows, adapters[2], null, 0, adapters).is(rows[2])
    }

    def "does not guess a row for a secondary adapter when rows and adapters do not line up"() {
        given:
        def rows = [row(1L, 0), row(2L, 2)]
        def adapters = [adapter('slot0', 0, 'AA'), adapter('slot1', 1, 'BB'), adapter('slot2', 2, 'CC')]

        expect:
        ScvmmProvisionProvider.findInterfaceForAdapter(rows, adapters[2], null, 0, adapters) == null
    }

    def "treats the lowest slot as primary when there is no slot 0"() {
        given:
        def rows = [row(1L, 0), row(2L, 1)]
        def adapters = [adapter('slot2', 2, 'BB'), adapter('slot1', 1, 'AA')]

        expect:
        ScvmmProvisionProvider.adapterRank(adapters[1], adapters) == 0
        ScvmmProvisionProvider.findInterfaceForAdapter(rows, adapters[1], null, 0, adapters).is(rows[0])
        ScvmmProvisionProvider.findInterfaceForAdapter(rows, adapters[0], null, 0, adapters).is(rows[1])
    }
}
