package com.morpheusdata.scvmm.testdata

import com.bertramlabs.plugins.karman.CloudFile
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.ComputeServer

class ApiServiceDataHelper {
    static def buildCreateServerCommands_getPowerShellOpts =  [
            // Basic VM configuration
            name: 'test-vm',
            hostname: 'test-hostname',
            memory: 4294967296L, // 4GB
            maxCpu: 2,
            maxCores: 4,

            // Dynamic memory settings
            minDynamicMemory: 2147483648L, // 2GB
            maxDynamicMemory: 8589934592L, // 8GB

            // VM identifiers
            vmId: 'vm-12345',
            cloneVMId: 'clone-vm-6789',
            imageId: 'img-9876',

            // VM type and generation
            scvmmCapabilityProfile: 'Hyper-V',
            scvmmGeneration: 'generation2',

            // Template information
            isTemplate: false,
            templateId: 'template-5678',

            // Storage configuration
            volumePath: 'C:\\ClusterStorage\\Volume1\\VMs',
            volumePaths: [
                    'C:\\ClusterStorage\\Volume1\\VMs\\test-vm\\disk0.vhdx',
                    'C:\\ClusterStorage\\Volume1\\VMs\\test-vm\\disk1.vhdx',
                    'C:\\ClusterStorage\\Volume1\\VMs\\test-vm\\disk2.vhdx'
            ],

            // Data disks configuration
            dataDisks: [
                    [name: 'data1', maxStorage: 10737418240L],
                    [name: 'data2', maxStorage: 21474836480L]
            ],

            // Disk external ID mappings for clone operations
            diskExternalIdMappings: [
                    'disk-ext-id-1',
                    'disk-ext-id-2'
            ],

            // Host and availability settings
            hostExternalId: 'host-ext-id-456',
            highlyAvailable: true,

            // Image configuration
            isSyncdImage: true,

            // Sysprep settings
            isSysprep: true,
            unattendPath: 'C:\\Temp\\unattend.xml',
            OSName: 'Windows Server 2019',

            // Zone/Region configuration
            zone: [
                    id: 10L,
                    name: 'Test Zone',
                    regionCode: 'us-east'
            ],

            // Network configuration
            networkConfig: [
                    doStatic: true,
                    primaryInterface: [
                            ipAddress: '192.168.1.100',
                            poolType: 'scvmm',
                            networkPool: [
                                    externalId: 'pool-123'
                            ],
                            vlanId: 42,
                            network: [
                                    externalId: 'network-abcd1234-5678-90ef-ghij-klmnopqrstuv'
                            ],
                            subnet: [
                                    externalId: 'subnet-abcd1234-5678-90ef-ghij-klmnopqrstuv'
                            ]
                    ]
            ]
    ]

    def static buildCreateServerCommands_getTemplateOpts = [
            // Basic VM configuration
            name: 'test-vm',
            hostname: 'test-hostname',
            memory: 4294967296L, // 4GB
            maxCpu: 2,
            maxCores: 4,
            memoryMB: 4096,

            // Dynamic memory settings
            minDynamicMemory: 2147483648L, // 2GB
            maxDynamicMemory: 8589934592L, // 8GB
            minDynamicMemoryMB: 2048,
            maxDynamicMemoryMB: 8192,

            // Template-specific settings - these are needed to trigger our condition
            isTemplate: true,
            templateId: 'template-5678',
            hardwareProfileName: 'test-hw-profile',
            hardwareGuid: '{12345678-1234-5678-1234-567812345678}',
            generationNumber: 2,
            highlyAvailable: true,
            scvmmCapabilityProfile: 'Hyper-V',

            // Zone/Region configuration
            zone: [
                    id: 10L,
                    name: 'Test Zone',
                    regionCode: 'us-east'
            ],

            // Template values that should be preserved
            template: [
                    CPUExpectedUtilizationPercent: 30,
                    DiskIops: 500,
                    CPUMaximumPercent: 90,
                    NetworkUtilizationMbps: 100,
                    DynamicMemoryEnabled: true,
                    Memory: 2048,
                    DynamicMemoryMinimumMB: 1024,
                    DynamicMemoryMaximumMB: 4096,
                    DynamicMemoryBufferPercentage: 20,
                    FirstBootDevice: 'CD',
                    NumaIsolationRequired: true,
                    CPUPerVirtualNumaNodeMaximum: 2,
                    MemoryPerVirtualNumaNodeMaximumMB: 2048,
                    VirtualNumaNodesPerSocketMaximum: 2
            ],
            // Network configuration
            networkConfig: [
                    doStatic: true,
                    primaryInterface: [
                            ipAddress: '192.168.1.100',
                            poolType: 'scvmm',
                            networkPool: [
                                    externalId: 'pool-123'
                            ],
                            vlanId: 42,
                            network: [
                                    externalId: 'network-abcd1234-5678-90ef-ghij-klmnopqrstuv'
                            ],
                            subnet: [
                                    externalId: 'subnet-abcd1234-5678-90ef-ghij-klmnopqrstuv'
                            ]
                    ]
            ]
    ]

    def static listTemplates_getTemplateData = [
            [
                    ID: "template-1",
                    ObjectType: "VMTemplate",
                    Name: "Windows Server 2019 Template",
                    CPUCount: 2,
                    Memory: 4294967296L,
                    OperatingSystem: "Windows Server 2019 Datacenter",
                    TotalSize: 42949672960L,
                    UsedSize: 21474836480L,
                    Generation: 2,
                    Disks: [
                            [
                                    ID: "disk-1",
                                    Name: "System Disk",
                                    VHDType: "DynamicallyExpanding",
                                    VHDFormat: "VHDX",
                                    Location: "C:\\ClusterStorage\\Volume1\\Templates\\disk1.vhdx",
                                    TotalSize: 42949672960L,
                                    UsedSize: 21474836480L,
                                    HostId: "host-1",
                                    HostVolumeId: "volume-1",
                                    VolumeType: "BootAndSystem"
                            ]
                    ]
            ],
            [
                    ID: "vhd-1",
                    Name: "Ubuntu 20.04 VHD",
                    Location: "C:\\ClusterStorage\\Volume1\\VHDs\\ubuntu.vhdx",
                    OperatingSystem: "Ubuntu Linux 20.04 (64 bit)",
                    TotalSize: 21474836480L,
                    VHDFormatType: "VHDX",
                    UsedSize: 0,
                    Disks: [
                            [
                                    ID: "vhd-1",
                                    ObjectType: "VirtualHardDisk",
                                    Name: "Ubuntu 20.04 VHD",
                                    VHDType: "DynamicallyExpanding",
                                    VHDFormat: "VHDX",
                                    Location: "C:\\ClusterStorage\\Volume1\\VHDs\\ubuntu.vhdx",
                                    TotalSize: 21474836480L,
                                    UsedSize: 10737418240L,
                                    HostId: "host-2",
                                    HostVolumeId: "volume-2"
                            ]
                    ]
            ]
    ]

    def static listClusters_getClusterData = [
            [
                    id: "cluster-1",
                    name: "Production Cluster",
                    hostGroup: "All Hosts\\Production\\Cluster1",
                    sharedVolumes: ["CSV-Volume1", "CSV-Volume2"],
                    description: "Main production cluster"
            ],
            [
                    id: "cluster-2",
                    name: "Development Cluster",
                    hostGroup: "All Hosts\\Development\\Cluster1",
                    sharedVolumes: ["CSV-Dev1"],
                    description: "Development environment cluster"
            ],
            [
                    id: "cluster-3",
                    name: "Test Cluster",
                    hostGroup: "All Hosts\\Production\\TestCluster",
                    sharedVolumes: [],
                    description: "Testing cluster"
            ]
    ]

    static def internalListHostGroups_getHostGroupData = [
            [
                    id: "12345678-1234-5678-9012-123456789012",
                    name: "All Hosts",
                    path: "All Hosts",
                    parent: null,
                    root: true
            ],
            [
                    id: "87654321-4321-8765-2109-876543210987",
                    name: "Production",
                    path: "All Hosts\\Production",
                    parent: "All Hosts",
                    root: false
            ],
            [
                    id: "11111111-2222-3333-4444-555555555555",
                    name: "Development",
                    path: "All Hosts\\Development",
                    parent: "All Hosts",
                    root: false
            ]
    ]

    static def listLibraryShares_getLibraryShareData = [
            [
                    ID: "12345678-1234-5678-9012-123456789012",
                    Name: "Library Share 1",
                    Path: "\\\\server1\\LibraryShare1"
            ],
            [
                    ID: "87654321-4321-8765-2109-876543210987",
                    Name: "Library Share 2",
                    Path: "\\\\server2\\LibraryShare2"
            ],
            [
                    ID: "11111111-2222-3333-4444-555555555555",
                    Name: "Local Library",
                    Path: "C:\\ProgramData\\Virtual Machine Manager Library Files"
            ]
    ]

    static def listNetworkIPPools_getnetworkMappingData = [
            [
                    ID: "network-1",
                    Name: "Production Network",
                    LogicalNetwork: "Production Logical",
                    LogicalNetworkId: "logical-net-1"
            ],
            [
                    ID: "network-2",
                    Name: "Development Network",
                    LogicalNetwork: "Development Logical",
                    LogicalNetworkId: "logical-net-2"
            ]
    ]

    static def listNetworkPools_getIpPoolsData = [
            [
                    ID: "pool-1",
                    Name: "Production Pool",
                    NetworkID: "network-1",
                    LogicalNetworkID: "logical-net-1",
                    Subnet: "192.168.1.0/24",
                    SubnetID: "subnet-1",
                    DefaultGateways: ["192.168.1.1"],
                    TotalAddresses: 254,
                    AvailableAddresses: 200,
                    DNSSearchSuffixes: ["domain.com"],
                    DNSServers: ["8.8.8.8", "8.8.4.4"],
                    IPAddressRangeStart: "192.168.1.10",
                    IPAddressRangeEnd: "192.168.1.254"
            ],
            [
                    ID: "pool-2",
                    Name: "Development Pool",
                    NetworkID: "network-2",
                    LogicalNetworkID: "logical-net-2",
                    Subnet: "10.0.1.0/24",
                    SubnetID: "subnet-2",
                    DefaultGateways: ["10.0.1.1"],
                    TotalAddresses: 100,
                    AvailableAddresses: 80,
                    DNSSearchSuffixes: ["dev.domain.com"],
                    DNSServers: ["10.0.1.2"],
                    IPAddressRangeStart: "10.0.1.50",
                    IPAddressRangeEnd: "10.0.1.150"
            ]
    ]

    static ComputeServer scvmmControllerOpts_getServer() {
        def server = new ComputeServer(
                id: 10L,
                name: "controller-01",
                internalIp: "10.0.0.10",
                sshUsername: "admin",
                sshPassword: "securepass123",
                sshHost: "10.0.0.10"
        )

        // Set the hypervisorConfig with the actual paths that match the implementation
        server.setConfigProperty("hypervisorConfig", [
                workingPath: "D:\\Working",
                diskPath: "D:\\Disks"
        ])
        return server
    }

    static Map insertContainerImage_getContainerImageNOpts() {
        def containerImage = [
                name          : "test-image",
                minDisk       : 5,
                minRam        : 512 * ComputeUtility.ONE_MEGABYTE,
                virtualImageId: 42L,
                tags          : 'morpheus, ubuntu',
                imageType     : 'vhd',
                containerType : 'vhd',
        ]


        def opts = [
                image: containerImage,
                rootSharePath: "\\\\server\\share",
                sshHost: 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password',
                winrmPort: '5985'
        ]
        return opts
    }
}
