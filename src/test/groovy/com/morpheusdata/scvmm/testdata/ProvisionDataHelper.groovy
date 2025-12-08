package com.morpheusdata.scvmm.testdata

import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.NetAddress
import com.morpheusdata.model.Network
import com.morpheusdata.model.OsType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.Workload
import com.morpheusdata.model.WorkloadType

class ProvisionDataHelper {
    static storageVols() {
        def vol1 = new StorageVolume(
                displayOrder: 0,
                name: "root",
                datastoreOption: "auto",
                rootVolume: true,
                id: 507,
                resizeable: true,
                shortName: "root",
                maxStorage: 42949672960
        )
        def vol2 = new StorageVolume(
                displayOrder: 2,
                name: "data-2",
                rootVolume: false,
                id: 509,
                resizeable: true,
                shortName: "data-2",
                maxStorage: 9663676416
        )
        vol1.setExternalId("external-id-1")
        vol1.setExternalId("external-id-2")
        return [vol1, vol2]
    }

     static def configsMap = [
            maxMemory: 4294967296,
            maxStorage: 71940702208,
            maxCpu: 0,
            maxCores: 1,
            maxGpus: 0,
            template: 168,
            backup: [
                    backupJob: [
                            syntheticFullEnabled: false,
                            retentionCount: null,
                            syntheticFullSchedule: null,
                            scheduleTypeId: null
                    ],
                    veeamManagedServer: "",
                    createBackup: false,
                    jobAction: "new",
                    providerBackupType: -1
            ],
            isVpcSelectable: true,
            customOptions: [:],
            hostId: 2,
            createBackup: true,
            hasNoUser: false,
            expose: [],
            lbInstances: [],
            isEC2: false,
            resourcePoolId: "",
            layoutSize: 1,
            createUser: true,
            memoryDisplay: "MB",
            scvmmCapabilityProfile: "Hyper-V",
            noAgent: true,
            vm: true,
            networkInterfaces: [
                    [
                            id: "network-1",
                            network: [
                                    id: 1,
                                    group: null,
                                    subnet: null,
                                    dhcpServer: true,
                                    name: "vlanbaseVmNetwork",
                                    pool: null
                            ],
                            ipAddress: null,
                            macAddress: null,
                            networkInterfaceTypeId: null,
                            networkInterfaces: [],
                            ipMode: null
                    ]
            ],
            volumes: [
                    // Volume details included here...
                    [
                            displayOrder: 0,
                            name: "root",
                            datastoreOption: "auto",
                            rootVolume: true,
                            id: 507,
                            resizeable: true,
                            shortName: "root",
                            maxStorage: 42949672960
                    ],
                    [
                            displayOrder: 2,
                            name: "data-2",
                            rootVolume: false,
                            id: 509,
                            resizeable: true,
                            shortName: "data-2",
                            maxStorage: 9663676416
                    ]
            ],
            storageController: [],
            provisionPoweredOff: false,
            skipNetworkWait: false,
            userData: null,
            resourcePool: "",
            hosts: [container35: "127.0.0.1"],
            evars: [:]
    ]

    static runWorkload_getScvmmOpts(account, cloud) {
        return [
                account: account,
                zoneConfig: [libraryShare: "\\\\server\\share", diskPath: "C:\\Disks", workingPath: "C:\\VMs"],
                zone: cloud,
                zoneId: cloud.id,
                publicKey: "ssh-rsa AAAAB...",
                privateKey: "-----BEGIN RSA PRIVATE KEY-----\nMIIEpA...",
                rootSharePath: "\\\\server\\share",
                regionCode: "default"
        ]
    }

    static def runWorkload_getControllerServer() {
        return new ComputeServer(
                id: 10L,
                name: "controller-01",
                serverType: new ComputeServerType(code: "scvmm-controller"),
                computeServerType: new ComputeServerType(code: "scvmm-controller"),
                sshHost: "10.0.0.5",
                sshUsername: "admin",
                sshPassword: "password123"
        )
    }

    static def runWorkload_getCloud() {
        return new Cloud(id: 1L, name: "test-cloud")
    }
    static def runWorkload_getAccount() {
        return new Account(id: 1L, name: "test-account")
    }
    static def runWorkload_getOsType() {
        return new OsType(platform: "linux")
    }
    static def runWorkload_getVirtualImage(OsType osType) {
        //def osType = runWorkload_getOsType()
        return new VirtualImage(id: 2L, name: "test-image",
                refType: "ComputeZone", remotePath: "somePath", osType: osType,
                externalId: "external-id-1")
    }
    static def runWorkload_getServicePlan() {
        return new ServicePlan(id: 3L, maxMemory: 42949672960L, maxCores: 2)
    }
    static def runWorkload_getInstance(ServicePlan servicePlan) {
        //def servicePlan = runWorkload_getServicePlan()
        return new Instance(id: 4L, name: "test-instance", plan: servicePlan)
    }
    static def runWorkload_getConcreteComputeServer(cloud,  virtualImage,  storageVols) {
        //def cloud = runWorkload_getCloud()
        //def virtualImage = runWorkload_getVirtualImage()
        return new ComputeServer(
                id: 1L,
                name: "test-server",
                externalId: "vm-123",
                cloud: cloud,
                sourceImage: virtualImage,
                volumes: storageVols,
                interfaces: []
        )
    }
    static def runWorkload_getWorkloadType() {
        return new WorkloadType(refId: 1L, id: 19L, code: "test-workload-type")
    }
    static def runWorkload_getWorkload(computerServer, workloadType, instance) {
        return new Workload(
                id: 5L,
                internalName: "testWorkload",
                server: computerServer,
                workloadType: workloadType,
                instance: instance
        )
    }

    static def runWorkload_getNodeServer(){
        return new ComputeServer(
                id: 2L,
                name: "node-01",
                externalId: "host-123",
                sshHost: "10.0.0.10",
                serverType: new ComputeServerType(code: "hypervisor"),
                hostname: "scvmm-host-01"
        )
    }

    static def runWorkload_getMockedControllerOpts(controllerServer){
        return [
                hypervisorConfig: [diskPath: "C:\\VMs\\Disks", workingPath: "C:\\VMs"],
                hypervisor: controllerServer,
                sshHost: "10.0.0.5",        // Using controllerNode.sshHost or default
                sshUsername: "admin",       // Using controllerNode.sshUsername or default
                sshPassword: "password123", // Using controllerNode.sshPassword or default
                zoneRoot: "C:\\VMs",        // Working path
                diskRoot: "C:\\VMs\\Disks"  // Disk path
        ]
    }

    static def runWorkload_getScvmmContainerOptsResponse(container) {
        return [
                config: [serverConfig: 'value'],
                vmId: 'vm-123',
                name: 'vm-123',
                server: container.server,
                serverId: container.server?.id,
                memory: 4294967296,
                maxCpu: 1,
                maxCores: 2,
                serverFolder: 'morpheus\\morpheus_server_1',
                hostname: 'testVM',
                network: [id: 1, name: 'vlanbaseVmNetwork'],
                networkId: 1,
                platform: 'linux',
                externalId: 'vm-123',
                networkType: 'vlan',
                containerConfig: [scvmmCapabilityProfile: 'Hyper-V'],
                resourcePool: 'Resource Pool 1',
                hostId: 2,
                osDiskSize: 42949672960,
                maxTotalStorage: 71940702208,
                dataDisks: [
                        new StorageVolume(
                                displayOrder: 2,
                                name: "data-2",
                                rootVolume: false,
                                id: 509,
                                resizeable: true,
                                shortName: "data-2",
                                maxStorage: 9663676416
                        )
                ],
                scvmmCapabilityProfile: 'Hyper-V',
                accountId: 1
        ]
    }

    static def runWorkload_constructCloudInitOptionsResponse(args) {
        return [
                cloudConfigUser: '''
            #cloud-config
            users:
              - name: morpheus
                sudo: ALL=(ALL) NOPASSWD:ALL
                shell: /bin/bash
                ssh-authorized-keys:
                  - ssh-rsa AAAAB3Nz...
        ''',
                cloudConfigMeta: '''
            instance-id: i-test123
            local-hostname: testVM
        ''',
                cloudConfigBytes: Base64.encoder.encodeToString('test data'.bytes),
                cloudConfigNetwork: '''
            version: 1
            config:
              - type: physical
                name: eth0
                subnets:
                  - type: dhcp
        ''',
                licenseApplied: true,
                unattendCustomized: args[3]?.toLowerCase() == 'windows'
        ]
    }

    static Map reunWorkload_getServerDetailsResponse(String serverId = null) {
        return [
                success: true,
                server: [
                        id: serverId ?: 'vm-12345',
                        name: 'testVM',
                        status: 'Running',
                        VMId: 'VMm-123456',
                        ipAddress: '10.0.1.100',
                        osType: 'Windows',
                        generation: 'Generation 2',
                        description: 'Test virtual machine',
                        diskSizeMB: 40960,
                        memoryMB: 4096,
                        cpuCount: 2,
                        hostName: 'scvmm-host-01',
                        hostId: 'host-123',
                        datastoreName: 'datastore1',
                        datastoreId: 'ds-123',
                        diskLayout: [
                                systemDisk: '/dev/sda',
                                dataDisk: '/dev/sda'
                        ]
                ],
                msg: 'Server details retrieved successfully'
        ]
    }

    static def runWorkload_applyComputeServerNetworkIpResponse(internalIp, externalIp) {
        return new ComputeServerInterface(
                id: 1L,
                ipAddress: internalIp,
                publicIpAddress: externalIp,
                externalId: 'nic-123',
                primaryInterface: true,
                network: new Network(id: 1L, name: 'vlanbaseVmNetwork')
        )
    }

    static def runWorkload_createServerResponse() {
        return [
                success: true,
                server: [
                        id: 'vm-12345',
                        ipAddress: '10.0.1.100',
                        name: 'testVM',
                        status: 'Running',
                        MId: 'VMm-123456',
                        disks: [
                                osDisk: [
                                        externalId: 'os-disk-id-1'
                                ],
                                dataDisks: [
                                        [
                                                id: 509,  // This should match the ID of your non-root volume
                                                externalId: 'data-disk-id-1'
                                        ]
                                ],
                                diskMetaData: [
                                        'os-disk-id-1': [
                                                VhdID: 'vhd-os-123',
                                                HostVolumeId: 'hvol-1',
                                                FileShareId: 'fs-1',
                                                PartitionUniqueId: 'part-1'
                                        ],
                                        'data-disk-id-1': [
                                                VhdID: 'vhd-data-123',
                                                HostVolumeId: 'hvol-2',
                                                FileShareId: 'fs-2',
                                                PartitionUniqueId: 'part-2'
                                        ],
                                        'vhd-os-123': [
                                                HostVolumeId: 'hvol-1',
                                                FileShareId: 'fs-1',
                                                PartitionUniqueId: 'part-1'
                                        ],
                                        'vhd-data-123': [
                                                HostVolumeId: 'hvol-2',
                                                FileShareId: 'fs-2',
                                                PartitionUniqueId: 'part-2'
                                        ]
                                ]
                        ]
                ],
                deleteDvdOnComplete: [
                        removeIsoFromDvd: true,
                        deleteIso: false
                ],
                cloudConfig: [
                        isoAttached: true,
                        dvdDriveId: 'dvd-123'
                ],
                errors: [],
                msg: 'Server created successfully'
        ]
    }

    static def runWorkload_checkServerReadyResponse(String serverId = null) {
        return [
                success: true,
                server: [
                        id: serverId ?: 'vm-12345',
                        ipAddress: '10.0.1.100',
                        name: 'testVM',
                        status: 'Running',
                        VMId: 'VMm-123456'
                ],
                hasIp: true,
                ready: true,
                waitComplete: true,
                msg: 'Server is ready'
        ]
    }

    static def runWorkload_setCdromResponse() {
        return [
                success: true,
                data: [
                        dvdRemoved: true
                ],
                msg: 'CD-ROM settings updated successfully'
        ]
    }

    static ComputeServer waitForHost_getComputeServer(Cloud cloud) {
        return new ComputeServer(
                id: 100L,
                cloud: cloud,
                externalId: 'vm-123',
                name: 'test-server',
                config: '{"hostId":"200"}'
        )
    }

    static ComputeServer waitForHost_getControllerServer(Cloud cloud){
        new ComputeServer(
                id: 200L,
                cloud: cloud,
                computeServerType: new ComputeServerType(code: 'scvmmController')
        )
    }

    static def waitForHost_getServerDetail() {
        return [
                success: true,
                server: [ipAddress: '10.0.0.100']
        ]
    }

    static def waitForHost_applyComputeServerNetworkIpResponse() {
        return new ComputeServerInterface(
                id: 1L,
                ipAddress: '10.0.0.5',
                name: 'eth0',
                primaryInterface: true,
                publicIpAddress: '10.0.0.5',
                macAddress: '00:11:22:33:44:55',
                displayOrder: 1,
                addresses: [new NetAddress(type: NetAddress.AddressType.IPV4, address: '10.0.0.5')]
        )
    }

    static ComputeServer finalizeHost_getComputeServer(Cloud cloud) {
        return new ComputeServer(
                id: 100L,
                cloud: cloud,
                externalId: 'vm-123',
                name: 'test-server',
                config: '{"hostId":"200"}'
        )
    }

    static ComputeServer finalizeHost_getControllerNode(Cloud cloud) {
        return new ComputeServer(
                id: 200L,
                cloud: cloud,
                computeServerType: new ComputeServerType(code: 'scvmmController')
        )
    }

    static Map finalizeHost_getServerDetail() {
        return [
                success: true,
                server: [ipAddress: '10.0.0.100']
        ]
    }

    static def finalizeHost_applyComputeServerNetworkIpResponse() {
        return new ComputeServerInterface(
                id: 1L,
                ipAddress: '192.168.1.100',
                macAddress: '00:11:22:33:44:55'
        )
    }

    static def getServerDetails_forComputeServer(type) {
        switch(type) {
            case "bothIps":
                return new ComputeServer(
                        id: 100L,
                        name: "server-both-ips",
                        internalIp: "192.168.1.100",
                        externalIp: "10.0.1.100"
                )
            case "internalOnly":
                return new ComputeServer(
                        id: 101L,
                        name: "server-internal-only",
                        internalIp: "192.168.1.101",
                        externalIp: null
                )
            case "externalOnly":
                return new ComputeServer(
                        id: 102L,
                        name: "server-external-only",
                        internalIp: null,
                        externalIp: "10.0.1.102"
                )
            case "noIps":
                return new ComputeServer(
                        id: 103L,
                        name: "server-no-ips",
                        internalIp: null,
                        externalIp: null
                )
            default:
                return null
        }
    }

    static def getWorkloadData(){
        return new Workload(
                id: 100L,
                internalName: "test-workload",
                server: new ComputeServer(id: 200L, name: "test-server")
        )
    }

    static def getHostAndDatastore_ComuteServerNodes(cloud) {
        def node1 = new ComputeServer(
                id: 20L,
                name: 'node1',
                enabled: true,
                cloud: cloud,
                computeServerType: new ComputeServerType(code: 'scvmmHypervisor'),
                powerState: ComputeServer.PowerState.on,
                capacityInfo: new ComputeCapacityInfo(maxMemory: 8589934592L, usedMemory: 2147483648L)
        )
        def node2 = new ComputeServer(
                id: 21L,
                name: 'node2',
                enabled: true,
                cloud: cloud,
                computeServerType: new ComputeServerType(code: 'scvmmHypervisor'),
                powerState: ComputeServer.PowerState.on,
                capacityInfo: new ComputeCapacityInfo(maxMemory: 17179869184L, usedMemory: 4294967296L)
        )
        return [node1, node2]
    }
    static Map pickScvmmController_ComputeServers(Cloud cloud) {
        def scvmmController = new ComputeServer(
                id: 2L,
                name: "scvmm-controller",
                cloud: cloud,
                computeServerType: new ComputeServerType(code: "scvmmController")
        )

        def scvmmHypervisor = new ComputeServer(
                id: 3L,
                name: "scvmm-hypervisor",
                cloud: cloud,
                computeServerType: new ComputeServerType(code: "scvmmHypervisor")
        )

        def legacyHypervisor = new ComputeServer(
                id: 4L,
                name: "legacy-hypervisor",
                cloud: cloud,
                serverType: "hypervisor"
        )

        return [
                scvmmController: scvmmController,
                scvmmHypervisor: scvmmHypervisor,
                legacyHypervisor: legacyHypervisor
        ]
    }

    static Map getServerRootDisk_differentServers() {
        def rootVolume = new StorageVolume(
                id: 1L,
                name: "root",
                rootVolume: true,
                maxStorage: 42949672960L
        )
        def dataVolume = new StorageVolume(
                id: 2L,
                name: "data",
                rootVolume: false,
                maxStorage: 10737418240L
        )

        def serverWithRootVolume = new ComputeServer(
                id: 100L,
                name: "server-with-root",
                volumes: [dataVolume, rootVolume]
        )

        def serverWithoutRootVolume = new ComputeServer(
                id: 101L,
                name: "server-without-root",
                volumes: [dataVolume]
        )

        def serverWithEmptyVolumes = new ComputeServer(
                id: 102L,
                name: "server-empty-volumes",
                volumes: []
        )

        def serverWithNullVolumes = new ComputeServer(
                id: 103L,
                name: "server-null-volumes",
                volumes: null
        )

        return [
                serverWithRootVolume: serverWithRootVolume,
                serverWithoutRootVolume: serverWithoutRootVolume,
                serverWithEmptyVolumes: serverWithEmptyVolumes,
                serverWithNullVolumes: serverWithNullVolumes,
                rootVolume: rootVolume
        ]
    }

    static ComputeServerInterface runWorkload_savedInterface() {
        def savedInterface = new ComputeServerInterface(
                id: 1L,
                name: "eth0",
                ipAddress: "192.168.1.100",
                publicIpAddress: "10.0.0.100",
                macAddress: "00:11:22:33:44:55",
                primaryInterface: true,
                displayOrder: 1,
                addresses: [new NetAddress(type: NetAddress.AddressType.IPV4, address: "192.168.1.100")]
        )
        return savedInterface
    }

    static Map DEFAULT_CLOUD_OPTS = [
            cloud: 1L,
            cloudName: "test-cloud",
            zoneId: 1L
    ]

    static Map DEFAULT_CONTROLLER_OPTS = [
            controller: 10L,
            sshHost: "10.0.0.5",
            sshUsername: "admin",
            sshPassword: "password123"
    ]

    static Map DEFAULT_CONTAINER_OPTS = [
            vmId: "vm-123",
            name: "vm-123",
            memory: 4294967296L,
            maxCpu: 1,
            maxCores: 2,
            hostname: "testVM",
            networkId: 1L,
            platform: "linux"
    ]

    static Map cloneParentCleanup_getScvmmOpts() {
        return [
                cloneVMId: 'vm-123',
                cloneContainerId: 100,
                startClonedVM: true,
                cloneBaseOpts: [
                        clonedScvmmOpts: [
                                controller: 1,
                                hostId: 2
                        ]
                ],
                deleteDvdOnComplete: [
                        deleteIso: 'iso-file.iso'
                ]
        ]
    }
}
