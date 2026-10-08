// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import spock.lang.Specification
import spock.lang.Subject

/**
 * Covers the multi-NIC command generation in {@link ScvmmApiService#buildCreateServerCommands}:
 * secondary adapters are added to the hardware profile, and static pool IP assignment is scoped
 * to the correct adapter configuration instead of being applied to every adapter on the VM.
 */
class ScvmmApiServiceBuildCreateServerCommandsSpec extends Specification {

    private static final String PRIMARY_NET = '11111111-1111-1111-1111-111111111111'
    private static final String EXTRA_NET_0 = '22222222-2222-2222-2222-222222222222'
    private static final String EXTRA_NET_1 = '33333333-3333-3333-3333-333333333333'
    private static final String EXTRA_SUBNET_1 = '44444444-4444-4444-4444-444444444444'
    private static final String PRIMARY_POOL = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
    private static final String EXTRA_POOL = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'

    @Subject
    private ScvmmApiService apiService

    void setup() {
        apiService = new ScvmmApiService(Mock(MorpheusContext))
    }

    private static Map baseOpts(Map networkConfig, Map overrides = [:]) {
        [
            networkConfig : networkConfig,
            maxCores      : 2,
            memory        : 4L * 1024L * 1024L * 1024L,
            zone          : [regionCode: null],
            vmId          : 'test-vm',
            imageId       : 'image-1',
            hostExternalId: 'host-1',
            volumePath    : 'C:\\VMs',
            dataDisks     : [],
            isSysprep     : false,
            isTemplate    : false,
        ] + overrides
    }

    private static Map dynamicPrimary() {
        [doStatic: false, primaryInterface: [network: [externalId: PRIMARY_NET], vlanId: 0]]
    }

    private static Map poolPrimary() {
        [
            doStatic        : true,
            primaryInterface: [
                network    : [externalId: PRIMARY_NET],
                poolType   : 'scvmm',
                ipAddress  : '10.0.0.10',
                networkPool: [externalId: PRIMARY_POOL],
                vlanId     : 0,
            ],
        ]
    }

    private static List<String> lines(Map rtn) {
        rtn.launchCommand.split('\n') as List<String>
    }

    private static List<String> adapterCommands(List<String> cmds) {
        cmds.findAll { it.contains('New-SCVirtualNetworkAdapter ') }
    }

    private static List<String> adapterConfigSets(List<String> cmds) {
        cmds.findAll { it.contains('Set-SCVirtualNetworkAdapterConfiguration ') }
    }

    def "single NIC provisioning only creates the primary adapter"() {
        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(dynamicPrimary())))

        then:
        // Primary adapter is created inside an If/Else on $MACAddress, so two command variants are emitted
        adapterCommands(cmds).size() == 2
        adapterCommands(cmds).every { it.contains('-VMNetwork $VMNetwork') }
        cmds.count { it.contains("Get-SCVMNetwork -VMMServer localhost -ID \"${PRIMARY_NET}\"") } == 1
        !cmds.any { it.contains('VMNetworkExtra') }
        adapterConfigSets(cmds).isEmpty()
    }

    def "single NIC with scvmm pool scopes the static IP to the first adapter configuration"() {
        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(poolPrimary())))

        then:
        cmds.contains('$VNAConfigs = @(Get-SCVirtualNetworkAdapterConfiguration -VMConfiguration $virtualMachineConfiguration)')
        cmds.contains('$VNAConfig = $VNAConfigs[0]')
        cmds.contains("\$ippool = Get-SCStaticIPAddressPool -ID \"${PRIMARY_POOL}\"".toString())
        cmds.contains('$ipaddress = Get-SCIPAddress -IPAddress "10.0.0.10"')
        adapterConfigSets(cmds) == [
            '$ignore = Set-SCVirtualNetworkAdapterConfiguration -VirtualNetworkAdapterConfiguration $VNAConfig -IPv4Address $ipaddress -IPv4AddressPool $ippool -MACAddress "00:00:00:00:00:00"'
        ]
    }

    def "extra interfaces create additional adapters on the same hardware profile job group"() {
        given:
        def networkConfig = dynamicPrimary() + [
            extraInterfaces: [
                [network: [externalId: EXTRA_NET_0], vlanId: 0],
                [network: [externalId: EXTRA_NET_1], subnet: [externalId: EXTRA_SUBNET_1], vlanId: 120],
            ]
        ]

        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(networkConfig)))

        then:
        def adapters = adapterCommands(cmds)
        adapters.size() == 6
        def jobGroups = adapters.collect { (it =~ /-JobGroup (\S+)/)[0][1] }.unique()
        jobGroups.size() == 1

        and: 'first extra NIC is dynamic without VLAN or subnet'
        cmds.contains('$MACAddressExtra0 = ""')
        cmds.contains('$MACAddressTypeExtra0 = "Dynamic"')
        cmds.contains("\$VMNetworkExtra0 = Get-SCVMNetwork -VMMServer localhost -ID \"${EXTRA_NET_0}\"".toString())
        def extra0 = adapters.findAll { it.contains('-VMNetwork $VMNetworkExtra0') }
        extra0.size() == 2
        extra0.every { it.contains('-VLanEnabled $false') && it.contains('-IPv4AddressType Dynamic') && !it.contains('-VMSubnet') }

        and: 'second extra NIC carries its VLAN and subnet'
        cmds.contains("\$VMNetworkExtra1 = Get-SCVMNetwork -VMMServer localhost -ID \"${EXTRA_NET_1}\"".toString())
        cmds.contains("\$VMSubnetExtra1 = Get-SCVMSubnet -VMMServer localhost -ID \"${EXTRA_SUBNET_1}\"".toString())
        def extra1 = adapters.findAll { it.contains('-VMNetwork $VMNetworkExtra1') }
        extra1.size() == 2
        extra1.every { it.contains('-VLanEnabled $true -VLanID 120') && it.contains('-VMSubnet $VMSubnetExtra1') }

        and: 'no static pool configuration is applied'
        adapterConfigSets(cmds).isEmpty()
        !cmds.any { it.contains('Get-SCVirtualNetworkAdapterConfiguration') }
    }

    def "extra interface externalId longer than 36 chars is trimmed to the network GUID"() {
        given:
        def networkConfig = dynamicPrimary() + [
            extraInterfaces: [[network: [externalId: "${EXTRA_NET_0}:120".toString()], vlanId: 120]]
        ]

        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(networkConfig)))

        then:
        cmds.contains("\$VMNetworkExtra0 = Get-SCVMNetwork -VMMServer localhost -ID \"${EXTRA_NET_0}\"".toString())
    }

    def "extra interface on an scvmm pool gets its own static IP assignment on its own adapter"() {
        given:
        def networkConfig = poolPrimary() + [
            extraInterfaces: [
                [network: [externalId: EXTRA_NET_0], vlanId: 0],
                [
                    network    : [externalId: EXTRA_NET_1],
                    poolType   : 'scvmm',
                    ipAddress  : '10.1.0.20',
                    networkPool: [externalId: EXTRA_POOL],
                    vlanId     : 0,
                ],
            ]
        ]

        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(networkConfig)))

        then: 'pool-backed extra NIC is created with a static MAC and static IPv4 type'
        cmds.contains('$MACAddressExtra1 = "00:00:00:00:00:00"')
        cmds.contains('$MACAddressTypeExtra1 = "Static"')
        adapterCommands(cmds).findAll { it.contains('-VMNetwork $VMNetworkExtra1') }.every { it.contains('-IPv4AddressType Static') }
        adapterCommands(cmds).findAll { it.contains('-VMNetwork $VMNetworkExtra0') }.every { it.contains('-IPv4AddressType Dynamic') }

        and: 'adapter configurations are fetched once as an array'
        cmds.count { it.contains('Get-SCVirtualNetworkAdapterConfiguration') } == 1
        cmds.contains('$VNAConfigs = @(Get-SCVirtualNetworkAdapterConfiguration -VMConfiguration $virtualMachineConfiguration)')

        and: 'primary IP is applied to adapter 0 and the extra pool IP to adapter 2 (its creation position)'
        cmds.contains('$VNAConfig = $VNAConfigs[0]')
        cmds.contains('$VNAConfigExtra1 = $VNAConfigs[2]')
        cmds.contains("\$ippoolExtra1 = Get-SCStaticIPAddressPool -ID \"${EXTRA_POOL}\"".toString())
        cmds.contains('$ipaddressExtra1 = Get-SCIPAddress -IPAddress "10.1.0.20"')
        adapterConfigSets(cmds) == [
            '$ignore = Set-SCVirtualNetworkAdapterConfiguration -VirtualNetworkAdapterConfiguration $VNAConfig -IPv4Address $ipaddress -IPv4AddressPool $ippool -MACAddress "00:00:00:00:00:00"',
            '$ignore = Set-SCVirtualNetworkAdapterConfiguration -VirtualNetworkAdapterConfiguration $VNAConfigExtra1 -IPv4Address $ipaddressExtra1 -IPv4AddressPool $ippoolExtra1 -MACAddress "00:00:00:00:00:00"',
        ]

        and: 'the assignment happens after the VM configuration exists'
        cmds.findIndexOf { it.contains('New-SCVMConfiguration') } < cmds.indexOf('$VNAConfigs = @(Get-SCVirtualNetworkAdapterConfiguration -VMConfiguration $virtualMachineConfiguration)')
    }

    def "extra pool interface is configured even when the primary NIC is not pool backed"() {
        given:
        def networkConfig = [
            doStatic        : true,
            primaryInterface: [network: [externalId: PRIMARY_NET], ipAddress: '10.0.0.10', vlanId: 0],
            extraInterfaces : [[
                network    : [externalId: EXTRA_NET_0],
                poolType   : 'scvmm',
                ipAddress  : '10.1.0.20',
                networkPool: [externalId: EXTRA_POOL],
                vlanId     : 0,
            ]],
        ]

        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(networkConfig)))

        then:
        !cmds.contains('$VNAConfig = $VNAConfigs[0]')
        cmds.contains('$VNAConfigExtra0 = $VNAConfigs[1]')
        adapterConfigSets(cmds) == [
            '$ignore = Set-SCVirtualNetworkAdapterConfiguration -VirtualNetworkAdapterConfiguration $VNAConfigExtra0 -IPv4Address $ipaddressExtra0 -IPv4AddressPool $ippoolExtra0 -MACAddress "00:00:00:00:00:00"',
        ]
    }

    def "skipped extra interface without a network does not shift adapter indexes"() {
        given:
        def networkConfig = poolPrimary() + [
            extraInterfaces: [
                [network: [:], vlanId: 0],
                [
                    network    : [externalId: EXTRA_NET_1],
                    poolType   : 'scvmm',
                    ipAddress  : '10.1.0.20',
                    networkPool: [externalId: EXTRA_POOL],
                    vlanId     : 0,
                ],
            ]
        ]

        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(networkConfig)))

        then:
        !cmds.any { it.contains('VMNetworkExtra0') }
        adapterCommands(cmds).size() == 4
        cmds.contains('$VNAConfigExtra1 = $VNAConfigs[1]')
    }

    def "extra pool interface without an allocated IP or pool falls back to dynamic addressing"() {
        given:
        def networkConfig = poolPrimary() + [
            extraInterfaces: [[network: [externalId: EXTRA_NET_0], poolType: 'scvmm', vlanId: 0]]
        ]

        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(networkConfig)))

        then:
        cmds.contains('$MACAddressTypeExtra0 = "Dynamic"')
        adapterCommands(cmds).findAll { it.contains('-VMNetwork $VMNetworkExtra0') }.every { it.contains('-IPv4AddressType Dynamic') }
        !cmds.any { it.contains('VNAConfigExtra0') }
        adapterConfigSets(cmds).size() == 1
    }

    def "extra interfaces are not added when cloning an existing VM"() {
        given:
        def networkConfig = dynamicPrimary() + [
            extraInterfaces: [[network: [externalId: EXTRA_NET_0], vlanId: 0]]
        ]

        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(networkConfig, [cloneVMId: 'source-vm'])))

        then:
        !cmds.any { it.contains('VMNetworkExtra') }
        adapterCommands(cmds).size() == 2
        !cmds.any { it.contains('Get-SCVirtualNetworkAdapterConfiguration') }
    }
}
