// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.OsType
import com.morpheusdata.model.VirtualImage
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * Covers boot settings and multi-NIC command generation in {@link ScvmmApiService#buildCreateServerCommands}:
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

    @Unroll
    def "disk image boot commands for #generation #platform secureBoot=#secureBoot cloud=#cloudId"() {
        given:
        def image = new VirtualImage(secureBoot: secureBoot, uefi: true, osType: new OsType(platform: platform))
        image.setConfigProperty('generation', generation)
        def provider = new ScvmmProvisionProvider(Mock(ScvmmPlugin), Mock(MorpheusContext))
        def imageOpts = provider.getScvmmImageOpts(image)

        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(dynamicPrimary(),
            imageOpts + [zone: [regionCode: cloudId]])))
        def secureBootCommands = cmds.findAll { it.contains('-SecureBoot') }

        then:
        cmds.find { it.contains('New-SCHardwareProfile ') }.contains("-Generation ${generation == 'generation2' ? '2' : '1'}")
        if (generation == 'generation2') {
            def expected = '$ignore = Set-SCHardwareProfile -HardwareProfile $HardwareProfile -SecureBootEnabled ' +
                (secureBoot == true ? '$true' : '$false') + ' -SecureBootTemplate ' + imageOpts.secureBootTemplate
            assert secureBootCommands == [expected]
            assert cmds[cmds.indexOf(expected) + 1].contains('Exit 29')
            assert cmds.indexOf(expected) < cmds.findIndexOf { it.contains('New-SCVMTemplate ') }
        } else {
            assert secureBootCommands.isEmpty()
        }

        where:
        [generation, platform, secureBoot, cloudId] << [
            ['generation1', 'generation2'],
            ['windows', 'linux', 'other', 'osx', 'solaris', 'unknown'],
            [false, true, null],
            [null, 'cloud-guid']
        ].combinations()
    }

    @Unroll
    def "SCVMM template boot settings are inherited only for #generation"() {
        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(dynamicPrimary(), [
            isTemplate: true, templateId: 'template-id', scvmmGeneration: generation,
            secureBoot: true, secureBootTemplate: 'MicrosoftUEFICertificateAuthority'
        ])))
        def secureBootCommands = cmds.findAll { it.contains('-SecureBoot') }

        then:
        secureBootCommands.size() == count
        secureBootCommands.every { it.contains('$template.SecureBoot') }
        !secureBootCommands.any { it.contains('MicrosoftUEFICertificateAuthority') }

        where:
        generation    | count
        'generation1' | 0
        'generation2' | 2
    }

    def "cloning a Generation 2 VM does not apply uploaded image Secure Boot overrides"() {
        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(dynamicPrimary(), [
            cloneVMId: 'source-vm', scvmmGeneration: 'generation2',
            secureBoot: false, secureBootTemplate: 'MicrosoftWindows'
        ])))

        then:
        !cmds.any { it.contains('-SecureBoot') }
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

    def "primary pool NIC is bound even though the appliance leaves networkConfig.doStatic false"() {
        given: "the exact shape the appliance sends: deprecated top-level flag false, mode on the interface"
        def networkConfig = [
            doStatic        : false,
            doDhcp          : false,
            havePool        : false,
            primaryInterface: [
                network    : [externalId: PRIMARY_NET],
                networkType: 'static',
                doStatic   : true,
                poolType   : 'scvmm',
                ipAddress  : '10.157.232.119',
                networkPool: [externalId: PRIMARY_POOL],
                vlanId     : 0,
            ],
        ]

        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(networkConfig)))

        then:
        adapterCommands(cmds).every { it.contains('-IPv4AddressType Static') }
        cmds.contains('$VNAConfig = $VNAConfigs[0]')
        cmds.contains("\$ipaddress = Get-SCIPAddress -IPAddress \"10.157.232.119\"".toString())
        adapterConfigSets(cmds).size() == 1
    }

    def "extra pool interface is configured when the primary NIC is DHCP"() {
        given: "the exact shape the appliance sends for a DHCP primary + static pool secondary"
        def networkConfig = [
            doStatic        : false,
            doDhcp          : true,
            primaryInterface: [network: [externalId: PRIMARY_NET], networkType: 'dhcp', doStatic: false, doDhcp: true, vlanId: 0],
            extraInterfaces : [[
                network    : [externalId: EXTRA_NET_0],
                networkType: 'static',
                doStatic   : true,
                doDhcp     : false,
                poolType   : 'scvmm',
                ipAddress  : '10.157.232.117',
                networkPool: [externalId: EXTRA_POOL],
                vlanId     : 0,
            ]],
        ]

        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(networkConfig)))

        then:
        !cmds.contains('$VNAConfig = $VNAConfigs[0]')
        cmds.contains('$VNAConfigExtra0 = $VNAConfigs[1]')
        cmds.contains("\$ipaddressExtra0 = Get-SCIPAddress -IPAddress \"10.157.232.117\"".toString())
        adapterCommands(cmds).findAll { it.contains('-VMNetwork $VMNetworkExtra0') }.every { it.contains('-IPv4AddressType Static') }
        adapterConfigSets(cmds) == [
            '$ignore = Set-SCVirtualNetworkAdapterConfiguration -VirtualNetworkAdapterConfiguration $VNAConfigExtra0 -IPv4Address $ipaddressExtra0 -IPv4AddressPool $ippoolExtra0 -MACAddress "00:00:00:00:00:00"',
        ]
    }

    def "extra interface that is explicitly DHCP is not pool bound even when the primary is static"() {
        given:
        def networkConfig = poolPrimary() + [
            extraInterfaces: [[
                network    : [externalId: EXTRA_NET_0],
                networkType: 'dhcp',
                doStatic   : false,
                poolType   : 'scvmm',
                ipAddress  : '10.1.0.20',
                networkPool: [externalId: EXTRA_POOL],
                vlanId     : 0,
            ]]
        ]

        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(networkConfig)))

        then:
        !cmds.any { it.contains('VNAConfigExtra0') }
        adapterConfigSets(cmds).size() == 1
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

    def "a host-local boot disk is created as a non Highly Available VM (MORPH-19186)"() {
        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(dynamicPrimary(), [highlyAvailable: false, volumePath: 'C:\\'])))

        then:
        cmds.count { it.contains('New-SCHardwareProfile') && it.contains('-HighlyAvailable $false') } == 1
        cmds.any { it.contains('New-SCVirtualDiskDrive') && it.contains('-Path "C:\\"') }
    }

    def "a cluster storage boot disk is created as a Highly Available VM"() {
        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(dynamicPrimary(), [highlyAvailable: true, volumePath: 'C:\\ClusterStorage\\Volume1'])))

        then:
        cmds.count { it.contains('New-SCHardwareProfile') && it.contains('-HighlyAvailable $true') } == 1
        cmds.any { it.contains('New-SCVirtualDiskDrive') && it.contains('-Path "C:\\ClusterStorage\\Volume1"') }
    }

    def "a failed host placement aborts before New-SCVirtualMachine"() {
        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(dynamicPrimary())))
        def updateIdx = cmds.findIndexOf { it.contains('Update-SCVMConfiguration') }
        def createIdx = cmds.findIndexOf { it.contains('New-SCVirtualMachine ') }

        then:
        updateIdx > 0
        cmds[updateIdx + 1].startsWith('if (-not $?) {')
        cmds[updateIdx + 1].contains('Exit 28')
        createIdx > updateIdx + 1
    }

    def "a failed cloud placement aborts before New-SCVirtualMachine"() {
        when:
        def cmds = lines(apiService.buildCreateServerCommands(baseOpts(dynamicPrimary(), [zone: [regionCode: 'cloud-guid'], hostExternalId: null, volumePath: null])))
        def updateIdx = cmds.findIndexOf { it.contains('Update-SCVMConfiguration') }
        def createIdx = cmds.findIndexOf { it.contains('New-SCVirtualMachine ') }

        then:
        updateIdx > 0
        cmds[updateIdx + 1].startsWith('if (-not $?) {')
        cmds[updateIdx + 1].contains('Exit 28')
        createIdx > updateIdx + 1
    }

    // ---------------------------------------------------------------- clone / generation / disks / sysprep

    private static final long GB = 1024L * 1024L * 1024L

    private List<String> build(Map networkConfig, Map overrides = [:]) {
        lines(apiService.buildCreateServerCommands(baseOpts(networkConfig, overrides)))
    }

    private static Map staticPrimary() {
        [primaryInterface: [network: [externalId: PRIMARY_NET], doStatic: true, ipAddress: '10.0.0.10', vlanId: 0]]
    }

    def "clone reconfigures the source adapter with #mode addressing"() {
        when:
        def cmds = build(networkConfig, [cloneVMId: 'source-vm'])

        then:
        cmds.findAll { it.startsWith('Set-SCVirtualNetworkAdapter ') }.every { it.contains(ipConfig) }
        cmds.count { it.startsWith('Set-SCVirtualNetworkAdapter ') } == 2

        where:
        mode      | networkConfig    || ipConfig
        'static'  | staticPrimary()  || '-IPv4AddressType Static -IPv4Address "10.0.0.10"'
        'dynamic' | dynamicPrimary() || '-IPv4AddressType Dynamic'
    }

    def "clone to a host group places the copy on the host and volume path"() {
        when:
        def cmds = build(dynamicPrimary(), [cloneVMId: 'source-vm'])
        def create = cmds.find { it.startsWith('$createdVm = New-SCVirtualMachine -VM $VM') }

        then:
        cmds.contains('$vmHost = Get-SCVMHost -ID "host-1"')
        create.contains('-VMHost $vmHost -Path "C:\\VMs"')
        create.contains('-UseDiffDiskOptimization')
        !create.contains('-Cloud')
        !cmds.any { it.contains('New-SCVMTemplate') || it.contains('New-SCVirtualDiskDrive') }
    }

    def "clone to an SCVMM cloud with a host targets the cloud"() {
        when:
        def create = build(dynamicPrimary(), [cloneVMId: 'source-vm', zone: [regionCode: 'cloud-guid']])
                .find { it.startsWith('$createdVm = New-SCVirtualMachine -VM $VM') }

        then:
        create.contains('-Cloud $cloud')
        !create.contains('-VMHost')
    }

    def "clone without a host always passes -Cloud, twice when deploying to a cloud and with an unset \$cloud otherwise"() {
        // Pinned as-is: the no-host clone branch hard-codes "-Cloud $cloud" in addition to the conditional one.
        when:
        def cmds = build(dynamicPrimary(), [cloneVMId: 'source-vm', hostExternalId: null, zone: [regionCode: regionCode]])
        def create = cmds.find { it.startsWith('$createdVm = New-SCVirtualMachine -VM $VM') }

        then:
        create.count('-Cloud $cloud') == count
        cmds.any { it.startsWith('$cloud = Get-SCCloud') } == (regionCode != null)

        where:
        regionCode   || count
        null         || 1
        'cloud-guid' || 2
    }

    def "generation #generation creates the boot disk on #bus and the profile as generation #number"() {
        when:
        def cmds = build(dynamicPrimary(), [scvmmGeneration: generation])

        then:
        cmds.any { it.contains('New-SCHardwareProfile') && it.contains("-Generation ${number}") }
        cmds.any { it.contains('New-SCVirtualDiskDrive') && it.contains("${bus} -Bus 0 -LUN 0") && it.contains('-VolumeType BootAndSystem') }
        cmds.any { it.contains('New-SCVMTemplate') && it.contains("-Generation ${number}") }

        where:
        generation    || bus     | number
        null          || '-IDE'  | '1'
        'generation1' || '-IDE'  | '1'
        'generation2' || '-SCSI' | '2'
    }

    def "a template deployment copies Secure Boot and hardware settings from the SCVMM template"() {
        when:
        def cmds = build(dynamicPrimary(), [isTemplate: true, templateId: 'tpl-1', scvmmGeneration: 'generation2'])

        then:
        cmds[0] == '$template = Get-SCVMTemplate -VMMServer localhost | where {$_.ID -eq "tpl-1"}'
        cmds.any { it.contains('-SecureBootEnabled $template.SecureBootEnabled') }
        cmds.any { it.contains('-SecureBootTemplate $template.SecureBootTemplate') }
        cmds.any { it.startsWith('$MACAddressTypeSetting = ') }
        !cmds.any { it.contains('Get-SCVirtualHardDisk') || it.contains('New-SCVMTemplate') }
        cmds.any { it.startsWith('$createdVm = New-SCVirtualMachine') && it.contains('-HardwareProfile $HardwareProfile') }
    }

    def "each data disk gets its own SCSI drive on bus 0 with its size and path"() {
        given:
        def disks = [[maxStorage: 10L * GB, volumePath: 'D:\\data'], [maxStorage: 20L * GB, volumePath: null]]

        when:
        def drives = build(dynamicPrimary(), [dataDisks: disks]).findAll { it.contains('New-SCVirtualDiskDrive') && it.contains('-VolumeType None') }

        then:
        drives.size() == 2
        drives[0].contains('-SCSI -Bus 0 -LUN 0') && drives[0].contains('-VirtualHardDiskSizeMB 10240') && drives[0].contains('-Path "D:\\data"')
        drives[1].contains('-SCSI -Bus 0 -LUN 1') && drives[1].contains('-VirtualHardDiskSizeMB 20480') && !drives[1].contains('-Path')
        drives.every { it.contains('-Dynamic') && it =~ /-FileName "data\d-[0-9a-f-]{36}\.vhd"/ }
    }

    def "a synced image attaches its additional disks from the mapped VHDs without a size"() {
        when:
        def cmds = build(dynamicPrimary(), [isSyncdImage: true, dataDisks: [[maxStorage: 10L * GB]],
                                            diskExternalIdMappings: [[externalId: 'boot-vhd'], [externalId: 'data-vhd-1']]])
        def drive = cmds.find { it.contains('-VolumeType None') }

        then:
        cmds.contains('$VirtualHardDisk0 = Get-SCVirtualHardDisk -VMMServer localhost -ID "data-vhd-1"')
        drive.contains('-VirtualHardDisk $VirtualHardDisk0')
        !drive.contains('-VirtualHardDiskSizeMB')
    }

    def "a Windows sysprep build customises the OS with answer file, computer name and licence"() {
        when:
        def cmds = build(dynamicPrimary(), [isSysprep: true, OSName: 'Windows Server 2019 Datacenter', unattendPath: '\\\\lib\\unattend.xml',
                                            hostname: 'web01', license: [fullName: 'Ops', productKey: 'AAAAA-BBBBB', orgName: 'HPE']])
        def create = cmds.find { it.startsWith('$createdVm = New-SCVirtualMachine') }

        then:
        cmds.contains('$OS = Get-SCOperatingSystem -VMMServer localhost | where {$_.Name -eq "Windows Server 2019 Datacenter"}')
        cmds.any { it.contains('New-SCVMTemplate') && it.contains('-OperatingSystem $OS') }
        cmds.any { it.startsWith('$AnswerFile = Get-SCScript') && it.contains('"\\\\lib\\unattend.xml"') }
        create.contains('-AnswerFile $AnswerFile')
        create.endsWith('-ComputerName "web01" -FullName "Ops" -ProductKey "AAAAA-BBBBB" -OrganizationName "HPE"')
    }

    def "a non-sysprep (Linux) build has no OS customisation even with a hostname"() {
        when:
        def cmds = build(dynamicPrimary(), [hostname: 'web01', license: [fullName: 'Ops']])

        then:
        cmds.any { it.contains('New-SCVMTemplate') && it.contains('-NoCustomization') }
        !cmds.any { it.contains('-ComputerName') || it.contains('Get-SCOperatingSystem') || it.contains('AnswerFile') }
    }

    def "a pool-backed primary NIC whose interface is DHCP is not bound to the pool (JD-1 gate)"() {
        given:
        def networkConfig = [doStatic: true, primaryInterface: [network: [externalId: PRIMARY_NET], poolType: 'scvmm', doStatic: false,
                                                                ipAddress: '10.0.0.10', networkPool: [externalId: PRIMARY_POOL], vlanId: 0]]

        when:
        def cmds = build(networkConfig)

        then:
        !cmds.any { it.contains('Set-SCVirtualNetworkAdapterConfiguration') || it.contains('-IPv4Address ') }
        adapterCommands(cmds).every { it.contains('-IPv4AddressType Dynamic') }
        cmds.contains('$MACAddressType = "Dynamic"')
    }

    def "dynamic memory bounds are applied to the hardware profile only when both are given"() {
        when:
        def cmds = build(dynamicPrimary(), [minDynamicMemory: min, maxDynamicMemory: max])

        then:
        cmds.count { it.contains('Set-SCHardwareProfile') && it.contains('DynamicMemory') } == expected

        where:
        min      | max      || expected
        1L * GB  | 8L * GB  || 2
        1L * GB  | null     || 0
        null     | null     || 0
    }

    def "a VLAN tagged primary NIC enables the VLAN on the adapter"() {
        when:
        def cmds = build([primaryInterface: [network: [externalId: PRIMARY_NET + '-100'], vlanId: 100]])

        then:
        adapterCommands(cmds).every { it.contains('-VLanEnabled $true -VLanID 100') }
        cmds.contains('$VMNetwork = Get-SCVMNetwork -VMMServer localhost -ID "' + PRIMARY_NET + '"')
    }

    def "a capability profile is looked up and attached to the hardware profile"() {
        when:
        def cmds = build(dynamicPrimary(), [scvmmCapabilityProfile: ' Hyper-V '])

        then:
        cmds.contains('$CapabilityProfile = Get-SCCapabilityProfile -VMMServer localhost | where {$_.Name -eq "Hyper-V"}')
        cmds.any { it.contains('New-SCHardwareProfile') && it.contains('-CapabilityProfile $CapabilityProfile') }
    }

    def "without a host or a cloud no New-SCVirtualMachine command is emitted"() {
        when:
        def cmds = build(dynamicPrimary(), [hostExternalId: null])

        then:
        !cmds.any { it.contains('New-SCVirtualMachine') }
        cmds.last() == '$createdVm | Select ID, ObjectType'
    }

    def "buildCreateServerCommands throws when the primary interface has no network"() {
        when:
        apiService.buildCreateServerCommands(baseOpts([primaryInterface: [:]]))

        then:
        thrown(NullPointerException)
    }
}
