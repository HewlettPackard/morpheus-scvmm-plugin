// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.sync

import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.NetAddress
import com.morpheusdata.model.Network
import spock.lang.Specification

/**
 * syncInterfaces must adopt appliance-created interfaces that do not yet carry an SCVMM adapter ID
 * (e.g. a DHCP NIC with no address) instead of removing them and re-creating a second "eth0".
 */
class VirtualMachineSyncInterfaceMatchSpec extends Specification {

    static final String NET = 'e2579373-0bc7-4d81-a418-9f8c3149641b'

    private static Map adapter(Map overrides = [:]) {
        [ID: 'adapter', MacAddress: null, SlotId: 0, IPv4AddressType: 'Dynamic', IPv4Addresses: [], IPv6Addresses: [], VirtualNetworkId: NET] + overrides
    }

    private static ComputeServerInterface iface(Map props = [:]) {
        def addresses = (props.remove('ips') ?: []).collect { new NetAddress(type: NetAddress.AddressType.IPV4, address: it) }
        def network = props.remove('networkExternalId')
        def result = new ComputeServerInterface([primaryInterface: false] + props)
        result.addresses = addresses
        if (network) result.network = new Network(externalId: network)
        return result
    }

    def "matches by IP address first"() {
        given:
        def eth0 = iface(id: 1L, name: 'eth0', primaryInterface: true, ips: ['10.157.232.104'])
        def eth1 = iface(id: 2L, name: 'eth1', ips: ['10.157.232.105'])

        when:
        def matches = VirtualMachineSync.matchUnclaimedAdapters(
            [adapter(ID: 'a', SlotId: 0, IPv4Addresses: ['10.157.232.105']), adapter(ID: 'b', SlotId: 1, IPv4Addresses: ['10.157.232.104'])],
            [eth0, eth1])

        then:
        matches['a'].is(eth1)
        matches['b'].is(eth0)
    }

    def "a static NIC matched by IP and a DHCP NIC with no address matched by slot order both adopt existing rows"() {
        given: 'the two-stat-dhcp-cxo-1 case: appliance created eth0 (static) and eth1 (dhcp, no ip, no externalId)'
        def eth0 = iface(id: 1L, name: 'eth0', primaryInterface: true, displayOrder: 0, ips: ['10.157.232.104'], networkExternalId: NET)
        def eth1 = iface(id: 2L, name: 'eth1', primaryInterface: false, displayOrder: 1, dhcp: true, networkExternalId: NET)
        def adapters = [
            adapter(ID: '53cb8a66', MacAddress: '00:1D:D8:B7:1C:04', SlotId: 0, IPv4AddressType: 'Static', IPv4Addresses: ['10.157.232.104'], IPv6Addresses: ['fe80::21d:d8ff:feb7:1c04']),
            adapter(ID: 'e4918c74', MacAddress: '00:15:5D:E8:2A:DF', SlotId: 1, IPv4AddressType: 'Dynamic', IPv4Addresses: [], IPv6Addresses: ['fe80::7300:9bdd:4bff:65b7'])
        ]

        when:
        def matches = VirtualMachineSync.matchUnclaimedAdapters(adapters, [eth0, eth1])

        then: 'nothing is left unmatched, so nothing gets removed and re-created'
        matches.size() == 2
        matches['53cb8a66'].is(eth0)
        matches['e4918c74'].is(eth1)
    }

    def "matches by MAC address when there is no IP overlap"() {
        given:
        def eth0 = iface(id: 1L, name: 'eth0', primaryInterface: true, macAddress: '00:1d:d8:b7:1c:04')
        def eth1 = iface(id: 2L, name: 'eth1', macAddress: '00-15-5D-E8-2A-DF')

        when:
        def matches = VirtualMachineSync.matchUnclaimedAdapters(
            [adapter(ID: 'a', SlotId: 0, MacAddress: '00:15:5D:E8:2A:DF'), adapter(ID: 'b', SlotId: 1, MacAddress: '00:1D:D8:B7:1C:04')],
            [eth0, eth1])

        then:
        matches['a'].is(eth1)
        matches['b'].is(eth0)
    }

    def "an all-zero MAC never matches"() {
        given:
        def eth0 = iface(id: 1L, name: 'eth0', macAddress: '00:00:00:00:00:00')
        def eth1 = iface(id: 2L, name: 'eth1', macAddress: '00:00:00:00:00:00', displayOrder: 1)

        when:
        def matches = VirtualMachineSync.matchUnclaimedAdapters([adapter(ID: 'a', SlotId: 1, MacAddress: '00:00:00:00:00:00')], [eth0, eth1])

        then: 'falls through to positional matching rather than pairing on the placeholder MAC'
        matches['a'].is(eth0)
    }

    def "slot 0 adopts the primary interface before positional matching"() {
        given:
        def eth1 = iface(id: 2L, name: 'eth1', displayOrder: 0)
        def eth0 = iface(id: 1L, name: 'eth0', primaryInterface: true, displayOrder: 5)

        when:
        def matches = VirtualMachineSync.matchUnclaimedAdapters([adapter(ID: 'a', SlotId: 0)], [eth1, eth0])

        then:
        matches['a'].is(eth0)
    }

    def "positional matching prefers an interface on the same network"() {
        given:
        def onOther = iface(id: 1L, name: 'eth1', displayOrder: 1, networkExternalId: 'other-net')
        def onSame = iface(id: 2L, name: 'eth2', displayOrder: 2, networkExternalId: NET)

        when:
        def matches = VirtualMachineSync.matchUnclaimedAdapters([adapter(ID: 'a', SlotId: 1)], [onOther, onSame])

        then:
        matches['a'].is(onSame)
    }

    def "positional matching pairs adapters by slot with interfaces by display order"() {
        given:
        def eth1 = iface(id: 2L, name: 'eth1', displayOrder: 1)
        def eth2 = iface(id: 3L, name: 'eth2', displayOrder: 2)

        when:
        def matches = VirtualMachineSync.matchUnclaimedAdapters([adapter(ID: 'slot2', SlotId: 2), adapter(ID: 'slot1', SlotId: 1)], [eth2, eth1])

        then:
        matches['slot1'].is(eth1)
        matches['slot2'].is(eth2)
    }

    def "extra adapters stay unmatched and extra interfaces are left for removal"() {
        given:
        def eth0 = iface(id: 1L, name: 'eth0', primaryInterface: true)

        when:
        def matches = VirtualMachineSync.matchUnclaimedAdapters([adapter(ID: 'a', SlotId: 0), adapter(ID: 'b', SlotId: 1)], [eth0])

        then:
        matches.size() == 1
        matches['a'].is(eth0)
        !matches.containsKey('b')
    }

    def "handles empty input"() {
        expect:
        VirtualMachineSync.matchUnclaimedAdapters(adapters, interfaces) == [:]

        where:
        adapters           | interfaces
        null               | null
        []                 | []
        [adapter(ID: 'a')] | []
        []                 | [iface(name: 'eth0')]
    }

    def "nextInterfaceName increments the trailing index until unused"() {
        expect:
        VirtualMachineSync.nextInterfaceName(base, used) == expected

        where:
        base    | used                     || expected
        null    | []                       || 'eth0'
        'eth0'  | []                       || 'eth0'
        null    | ['eth0']                 || 'eth1'
        'eth0'  | ['eth0', 'eth1']         || 'eth2'
        'eth0'  | ['eth0', 'eth2']         || 'eth1'
        'ens3'  | ['ens3']                 || 'ens4'
        'nic'   | ['nic']                  || 'nic1'
        'nic'   | ['nic', 'nic1', 'nic2']  || 'nic3'
        'eth0'  | ['eth1']                 || 'eth0'
    }
}
