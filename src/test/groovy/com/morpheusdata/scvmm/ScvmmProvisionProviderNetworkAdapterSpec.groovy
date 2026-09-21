// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

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

    def "prefers the adapter that holds the detected ip address"() {
        given:
        def detail = [NetworkAdapters: [
            adapter('nic-0', 0, '00:15:5D:00:00:01', ['10.0.0.4']),
            adapter('nic-1', 1, '00:15:5D:00:00:02', ['10.0.0.5'])
        ]]

        when:
        def result = ScvmmProvisionProvider.findPrimaryNetworkAdapter(detail, '10.0.0.5')

        then:
        result.ID == 'nic-1'
        result.MacAddress == '00:15:5D:00:00:02'
    }

    def "matches ipv6 addresses and tolerates surrounding whitespace"() {
        given:
        def detail = [NetworkAdapters: [
            adapter('nic-0', 0, '00:15:5D:00:00:01'),
            adapter('nic-1', 1, '00:15:5D:00:00:02', [], ['fe80::1'])
        ]]

        expect:
        ScvmmProvisionProvider.findPrimaryNetworkAdapter(detail, " fe80::1\n").ID == 'nic-1'
    }

    def "matches when ConvertTo-Json collapsed the address arrays into strings"() {
        given:
        def detail = [NetworkAdapters: [
            [ID: 'nic-0', SlotId: 0, MacAddress: '00:15:5D:00:00:01', IPv4Addresses: '10.0.0.4', IPv6Addresses: ''],
            [ID: 'nic-1', SlotId: 1, MacAddress: '00:15:5D:00:00:02', IPv4Addresses: '10.0.0.5 10.0.0.6', IPv6Addresses: 'fe80::2']
        ]]

        expect:
        ScvmmProvisionProvider.findPrimaryNetworkAdapter(detail, '10.0.0.6').ID == 'nic-1'
        ScvmmProvisionProvider.findPrimaryNetworkAdapter(detail, 'fe80::2').ID == 'nic-1'
        // a single character must never match as an address
        ScvmmProvisionProvider.findPrimaryNetworkAdapter(detail, '5').ID == 'nic-0'
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
}
