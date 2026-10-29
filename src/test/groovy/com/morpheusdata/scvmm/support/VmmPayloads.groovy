// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.support

/**
 * Builders for SCVMM payload maps as {@code wrapExecuteCommand} returns them (ConvertTo-Json -> JsonSlurper).
 * Keys mirror the {@code New-Object PSObject -property @{...}} blocks in {@code ScvmmApiService}; pass overrides
 * to change or add keys. Every builder returns a {@link JsonMap} so sync code calling {@code encodeAsJSON()} works.
 */
class VmmPayloads {

    /** LinkedHashMap that answers the Grails {@code encodeAsJSON()} call made by sync code (e.g. HostSync rawData). */
    static class JsonMap extends LinkedHashMap {
        JsonMap(Map values) {
            super(values)
        }

        Object encodeAsJSON() {
            return this
        }
    }

    /** Element of {@code listVirtualMachines().virtualMachines}. */
    static JsonMap vm(Map overrides = [:]) {
        json([
            ID                    : 'vm-1', ObjectType: 'VM', VMId: 'hv-vm-1', Name: 'vm-1',
            CPUCount              : 2, Memory: 4096, VirtualMachineState: 'Running',
            MemoryAvailablePercentage: 0, CPUUtilization: 0, TotalSize: 0, UsedSize: 0, HostId: 'host-1',
            Disks                 : [], NetworkAdapters: [], IpAddress: '', InternalIp: '',
            OperatingSystem       : 'Ubuntu Linux (64 bit)', OperatingSystemWindows: false,
            DynamicMemoryEnabled  : false, MemoryAssignedMB: 4096, DynamicMemoryMinimumMB: 0,
            DynamicMemoryMaximumMB: 0, Generation: 2
        ], overrides)
    }

    /** {@code getServerDetails().server}: the shape the provisioning wait loops consume. */
    static JsonMap serverDetail(Map overrides = [:]) {
        json([
            ID                   : 'vm-1', VMId: 'hv-vm-1', Name: 'vm-1', Status: 'Running',
            VirtualMachineState  : 'Running', VirtualHardDiskDrives: ['vhd-1'], VirtualDiskDrives: ['vdd-1'],
            ipAddress            : '', internalIp: '', HostId: 'host-1', NetworkAdapters: []
        ], overrides)
    }

    static JsonMap vmDisk(Map overrides = [:]) {
        json([
            ID          : 'disk-1', ObjectType: 'VirtualDiskDrive', Name: 'disk-1.vhdx', VHDType: 'DynamicallyExpanding',
            VHDFormat   : 'VHDX', Location: 'C:\\VMs\\vm-1\\disk-1.vhdx', TotalSize: 42949672960L, UsedSize: 4194304L,
            HostId      : 'host-1', HostVolumeId: 'vol-1', PartitionUniqueId: 'part-1', VolumeType: 'BootAndSystem'
        ], overrides)
    }

    /** Element of {@code NetworkAdapters} in both {@code vm()} and {@code serverDetail()}. */
    static JsonMap vmAdapter(Map overrides = [:]) {
        json([
            ID              : 'nic-1', Name: 'nic-1', IPv4Addresses: [], IPv4AddressType: 'Dynamic',
            IPv6Addresses   : [], IPv6AddressType: 'Dynamic', MacAddress: '00:15:5D:00:00:01',
            VirtualNetworkId: 'vmnet-1', VMNetwork: 'vmnet-1', VLanID: 0, SlotId: 0, Enabled: true
        ], overrides)
    }

    /**
     * Element of {@code listHosts().hosts}. Defaults are deliberately the minimal capacity set HostSync needs;
     * the full report also has name, computerName, status, hostGroup, cluster, os, hypervVersion, etc.
     */
    static JsonMap host(Map overrides = [:]) {
        json([
            id             : 'host-id',
            cpuCount       : '2',
            coresPerCpu    : '4',
            totalMemory    : '0',
            availableMemory: '0',
            totalStorage   : '0',
            usedStorage    : '0',
            hyperVState    : 'Unknown'
        ], overrides)
    }

    static JsonMap datastore(Map overrides = [:]) {
        json([
            id                     : 'ds-1', name: 'C:\\', storageVolumeID: 'sv-1', partitionUniqueID: 'part-1',
            capacity               : 107374182400L, freeSpace: 53687091200L, isClusteredSharedVolume: false,
            vmHost                 : 'host-1', isAvailableForPlacement: true, hostDisk: 'disk0', size: 107374182400L,
            mountPoints            : ['C:\\']
        ], overrides)
    }

    static JsonMap template(Map overrides = [:]) {
        json([
            ID        : 'tpl-1', ObjectType: 'VMTemplate', Name: 'template-1', CPUCount: 1, Memory: 2048,
            OperatingSystem: 'Windows Server 2022 Standard', TotalSize: 0, UsedSize: 0, Generation: 2,
            Disks     : []
        ], overrides)
    }

    static JsonMap templateDisk(Map overrides = [:]) {
        json([
            ID          : 'tpl-disk-1', Name: 'tpl-disk-1.vhdx', VHDType: 'DynamicallyExpanding', VHDFormat: 'VHDX',
            Location    : '\\\\lib\\share\\tpl-disk-1.vhdx', TotalSize: 42949672960L, UsedSize: 4194304L,
            HostId      : 'lib-1', HostVolumeId: 'lib-vol-1', VolumeType: 'BootAndSystem'
        ], overrides)
    }

    /** Element of {@code listNetworks().networks}; {@code Subnets} holds {@link #vlan} entries. */
    static JsonMap network(Map overrides = [:]) {
        json([ID: 'vmnet-1', Name: 'vmnet-1', LogicalNetwork: 'logical-1', Subnets: []], overrides)
    }

    /** VLAN/subnet entry, as nested in {@code network().Subnets} or returned by {@code listNoIsolationVLans}. */
    static JsonMap vlan(Map overrides = [:]) {
        json([
            ID              : 'subnet-1-100', Name: 'subnet-1', NetworkName: 'logical-1',
            LogicalNetworkID: 'ln-1', Subnet: '10.0.100.0/24', VLanID: 100
        ], overrides)
    }

    static JsonMap logicalNetwork(Map overrides = [:]) {
        json([ID: 'ln-1', Name: 'logical-1'], overrides)
    }

    static JsonMap ipPool(Map overrides = [:]) {
        json([
            ID                : 'pool-1', Name: 'pool-1', NetworkID: 'vmnet-1', LogicalNetworkID: 'ln-1',
            Subnet            : '10.0.100.0/24', SubnetID: 'subnet-1', DefaultGateways: ['10.0.100.1'],
            TotalAddresses    : 100, AvailableAddresses: 90, DNSSearchSuffixes: [], DNSServers: ['10.0.0.53'],
            IPAddressRangeStart: '10.0.100.10', IPAddressRangeEnd: '10.0.100.109'
        ], overrides)
    }

    static JsonMap cluster(Map overrides = [:]) {
        json([id: 'cluster-1', name: 'cluster-1', hostGroup: 'All Hosts\\group-1', sharedVolumes: [], description: ''], overrides)
    }

    static JsonMap fileShare(Map overrides = [:]) {
        json([
            ID                 : 'share-1', Name: 'share-1', Capacity: 107374182400L, FreeSpace: 53687091200L,
            IsAvailableForPlacement: true, MountPoints: ['\\\\fs\\share-1'], ClusterAssociations: [], HostAssociations: []
        ], overrides)
    }

    static JsonMap capabilityProfile(Map overrides = [:]) {
        json([ID: 'cap-1', Name: 'Hyper-V'], overrides)
    }

    /** No script reports checkpoints today; shape follows {@code Get-SCVMCheckpoint | Select ID,Name}. */
    static JsonMap snapshot(Map overrides = [:]) {
        json([ID: 'checkpoint-1', Name: 'vm-1.1700000000000'], overrides)
    }

    private static JsonMap json(Map defaults, Map overrides) {
        new JsonMap(defaults + (overrides ?: [:]))
    }
}
