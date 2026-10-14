// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.helper

import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.NetAddress
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.NetworkPoolIp
import spock.lang.Specification

/**
 * The appliance deletes the plugin's pool server, pools and NetworkPoolIp rows on every plugin upgrade; SCVMM keeps
 * the grants. Reconciliation must rebuild the rows from SCVMM, attribute them to the right server/NIC, and never
 * invent, duplicate or move leases.
 */
class PoolIpReconcilerSpec extends Specification {

    NetworkPool pool = new NetworkPool(id: 3L, name: 'cxo-1-pool', externalId: '4784149f', gateway: '10.157.232.1',
            netmask: '255.255.252.0', dnsServers: ['10.157.232.245'])

    ComputeServerInterface eth0 = new ComputeServerInterface(id: 1275L, name: 'eth0', externalId: '2aa292cf', macAddress: '00:1D:D8:B7:1C:08',
            addresses: [new NetAddress(type: NetAddress.AddressType.IPV4, address: '10.157.232.111')])
    ComputeServer server63 = new ComputeServer(id: 63L, name: 'p189-1nic-static-blank', hostname: 'p189-1nic-static-blank', interfaces: [eth0])

    PoolIpReconciler.OwnerIndex owners = new PoolIpReconciler.OwnerIndex([server63])

    static Map grant(Map o = [:]) {
        [PoolID: '4784149f', ID: 'g-1', Address: '10.157.232.111', State: 'Assigned', AssignedToType: 'VirtualNetworkAdapter', AllocatedToObjectID: null, Description: null] + o
    }

    def "restores a lost lease and attributes it to the server whose NIC holds the address"() {
        when:
        def plan = PoolIpReconciler.plan(pool, [], [grant()], owners)

        then:
        plan.updates.empty && plan.removes.empty
        plan.adds.size() == 1
        with(plan.adds[0]) {
            ipAddress == '10.157.232.111'
            externalId == 'g-1'
            staticIp
            ipType == 'assigned'
            refType == 'ComputeServer'
            refId == 63L
            subRefId == 1275L
            interfaceName == 'eth0'
            macAddress == '00:1D:D8:B7:1C:08'
            hostname == 'p189-1nic-static-blank'
            gatewayAddress == '10.157.232.1'
            subnetMask == '255.255.252.0'
            dnsServer == '10.157.232.245'
            networkPool.is(pool)
            startDate != null
        }
    }

    def "attributes by SCVMM adapter id before falling back to the address"() {
        given: 'the adapter id points at a different interface than the address does'
        ComputeServerInterface eth1 = new ComputeServerInterface(id: 1276L, name: 'eth1', externalId: '64eb56a6')
        ComputeServer other = new ComputeServer(id: 70L, name: 'other', interfaces: [eth1])
        def idx = new PoolIpReconciler.OwnerIndex([server63, other])

        when:
        def plan = PoolIpReconciler.plan(pool, [], [grant(AllocatedToObjectID: '64EB56A6')], idx)

        then:
        plan.adds[0].refId == 70L
        plan.adds[0].subRefId == 1276L
    }

    def "a grant nobody in Morpheus owns becomes a visible reserved record, not an assignment"() {
        when:
        def plan = PoolIpReconciler.plan(pool, [], [grant(ID: 'g-orphan', Address: '10.157.232.105', State: 'UnAssigned', Description: 'left over')], owners)

        then:
        with(plan.adds[0]) {
            ipType == 'reserved'
            refType == null
            refId == null
            subRefId == null
            description == 'left over'
        }
    }

    def "an existing row matching the grant is left untouched"() {
        given:
        NetworkPoolIp row = new NetworkPoolIp(id: 10L, ipAddress: '10.157.232.111', externalId: 'g-1', staticIp: true,
                ipType: 'assigned', refType: 'ComputeServer', refId: 63L, subRefId: 1275L)

        when:
        def plan = PoolIpReconciler.plan(pool, [row], [grant()], owners)

        then:
        plan.empty
        plan.matched == ['g-1'] as Set
    }

    def "an existing row without externalId is matched by address and gets the grant id back-filled"() {
        given:
        NetworkPoolIp row = new NetworkPoolIp(id: 10L, ipAddress: '10.157.232.111', externalId: null, staticIp: true,
                ipType: 'assigned', refType: 'ComputeServer', refId: 63L, subRefId: 1275L)

        when:
        def plan = PoolIpReconciler.plan(pool, [row], [grant()], owners)

        then:
        plan.adds.empty && plan.removes.empty
        plan.updates == [row]
        row.externalId == 'g-1'
    }

    def "an unattributed row is attributed once an owner can be found, but an attributed row is never moved"() {
        given:
        NetworkPoolIp orphanRow = new NetworkPoolIp(id: 10L, ipAddress: '10.157.232.111', externalId: 'g-1', staticIp: true, ipType: 'reserved')
        NetworkPoolIp ownedRow = new NetworkPoolIp(id: 11L, ipAddress: '10.157.232.112', externalId: 'g-2', staticIp: true, ipType: 'assigned', refType: 'ComputeServer', refId: 99L, subRefId: 5L)
        ComputeServerInterface nic = new ComputeServerInterface(id: 50L, name: 'eth0', addresses: [new NetAddress(type: NetAddress.AddressType.IPV4, address: '10.157.232.112')])
        def idx = new PoolIpReconciler.OwnerIndex([server63, new ComputeServer(id: 64L, name: 'x', interfaces: [nic])])

        when:
        def plan = PoolIpReconciler.plan(pool, [orphanRow, ownedRow], [grant(), grant(ID: 'g-2', Address: '10.157.232.112')], idx)

        then:
        plan.updates == [orphanRow]
        orphanRow.refId == 63L
        orphanRow.subRefId == 1275L
        orphanRow.ipType == 'assigned'
        ownedRow.refId == 99L
        ownedRow.subRefId == 5L
    }

    def "a row whose grant SCVMM no longer holds is removed"() {
        given:
        NetworkPoolIp stale = new NetworkPoolIp(id: 12L, ipAddress: '10.157.232.119', externalId: 'g-gone', staticIp: true)

        when:
        def plan = PoolIpReconciler.plan(pool, [stale], [grant()], owners)

        then:
        plan.removes*.id == [12L]
        plan.removes[0].externalId == 'g-gone'
        plan.adds.size() == 1
    }

    def "a row with neither externalId nor a matching grant is left alone (in-flight reservation)"() {
        given:
        NetworkPoolIp inflight = new NetworkPoolIp(id: 13L, ipAddress: '10.157.232.118', externalId: null)

        when:
        def plan = PoolIpReconciler.plan(pool, [inflight], [], owners)

        then:
        plan.empty
    }

    def "two Morpheus rows for one grant keep the first and drop the duplicate"() {
        given:
        NetworkPoolIp a = new NetworkPoolIp(id: 20L, ipAddress: '10.157.232.111', externalId: 'g-1', staticIp: true, ipType: 'assigned', refType: 'ComputeServer', refId: 63L)
        NetworkPoolIp b = new NetworkPoolIp(id: 21L, ipAddress: '10.157.232.111', externalId: 'G-1', staticIp: true, ipType: 'assigned', refType: 'ComputeServer', refId: 63L)

        when:
        def plan = PoolIpReconciler.plan(pool, [a, b], [grant()], owners)

        then:
        plan.removes*.id == [21L]
        plan.adds.empty
    }

    def "grants without id or address are ignored"() {
        expect:
        PoolIpReconciler.plan(pool, [], [[ID: null, Address: '1.2.3.4'], [ID: 'x', Address: null], null], owners).empty
    }

    def "a full upgrade scenario: three VMs, one orphan, one stale row"() {
        given:
        ComputeServer vmA = new ComputeServer(id: 1L, name: 'a', interfaces: [new ComputeServerInterface(id: 101L, name: 'eth0', externalId: 'ad-a', addresses: [new NetAddress(type: NetAddress.AddressType.IPV4, address: '10.0.0.1')])])
        ComputeServer vmB = new ComputeServer(id: 2L, name: 'b', interfaces: [new ComputeServerInterface(id: 102L, name: 'eth0', externalId: 'ad-b', addresses: [new NetAddress(type: NetAddress.AddressType.IPV4, address: '10.0.0.2')])])
        def idx = new PoolIpReconciler.OwnerIndex([vmA, vmB])
        def grants = [grant(ID: 'ga', Address: '10.0.0.1'), grant(ID: 'gb', Address: '10.0.0.2', AllocatedToObjectID: 'ad-b'), grant(ID: 'go', Address: '10.0.0.9', State: 'UnAssigned')]
        NetworkPoolIp stale = new NetworkPoolIp(id: 5L, ipAddress: '10.0.0.5', externalId: 'g-old')

        when:
        def plan = PoolIpReconciler.plan(pool, [stale], grants, idx)

        then:
        plan.adds*.ipAddress.sort() == ['10.0.0.1', '10.0.0.2', '10.0.0.9']
        plan.adds.find { it.ipAddress == '10.0.0.1' }.refId == 1L
        plan.adds.find { it.ipAddress == '10.0.0.2' }.subRefId == 102L
        plan.adds.find { it.ipAddress == '10.0.0.9' }.refId == null
        plan.removes*.id == [5L]
        plan.updates.empty
    }
}
