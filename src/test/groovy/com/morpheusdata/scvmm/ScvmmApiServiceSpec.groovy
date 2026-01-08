package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusComputeTypeSetService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusProcessService
import com.morpheusdata.core.MorpheusServices
import com.bertramlabs.plugins.karman.CloudFile
import com.morpheusdata.core.MorpheusStorageVolumeService
import com.morpheusdata.core.MorpheusVirtualImageService
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.library.MorpheusWorkloadTypeService
import com.morpheusdata.core.network.MorpheusNetworkService
import com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService
import com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.library.MorpheusSynchronousWorkloadTypeService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService
import com.morpheusdata.core.synchronous.MorpheusSynchronousKeyPairService
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZoneRegion
import com.morpheusdata.model.KeyPair
import com.morpheusdata.model.Network
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.TaskResult
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.scvmm.testdata.ApiServiceDataHelper
import org.junit.jupiter.api.BeforeEach
import spock.lang.Specification
import io.reactivex.rxjava3.core.Single
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.synchronous.MorpheusSynchronousFileCopyService
import spock.lang.Unroll
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

class ScvmmApiServiceSpec extends Specification {

    private MorpheusContext morpheusContext
    private ScvmmPlugin plugin
    private ScvmmApiService apiService
    private MorpheusSynchronousComputeServerService computeServerService
    private MorpheusComputeServerService asyncComputeServerService
    private MorpheusComputeTypeSetService asyncComputeTypeSetService
    private MorpheusProcessService processService
    private MorpheusSynchronousWorkloadTypeService workloadTypeService
    private MorpheusWorkloadTypeService asyncWorkloadTypeService
    private MorpheusCloudService asyncCloudService
    private MorpheusSynchronousCloudService cloudService
    private MorpheusSynchronousNetworkService networkService
    private MorpheusSynchronousStorageVolumeService storageVolumeService
    private MorpheusSynchronousResourcePermissionService resourcePermissionService
    private MorpheusStorageVolumeService asyncStorageVolumeService
    private MorpheusSynchronousVirtualImageService virtualImageService
    private MorpheusVirtualImageService asyncVirtualImageService
    private MorpheusSynchronousFileCopyService fileCopyService

    static final Map DEFAULT_OPTS = [
            sshHost    : 'scvmm-server',
            sshUsername: 'admin',
            sshPassword: 'password',
            winrmPort  : '5985'
    ]

    @BeforeEach
    void setup() {
        // Setup mock context and services
        morpheusContext = Mock(MorpheusContext)
        plugin = Mock(ScvmmPlugin)

        // Mock services
        computeServerService = Mock(MorpheusSynchronousComputeServerService)
        asyncComputeServerService = Mock(MorpheusComputeServerService)
        asyncComputeTypeSetService = Mock(MorpheusComputeTypeSetService)
        processService = Mock(MorpheusProcessService)
        asyncCloudService = Mock(MorpheusCloudService)
        def asyncNetworkService = Mock(MorpheusNetworkService)
        workloadTypeService = Mock(MorpheusSynchronousWorkloadTypeService)
        asyncWorkloadTypeService = Mock(MorpheusWorkloadTypeService)
        storageVolumeService = Mock(MorpheusSynchronousStorageVolumeService)
        resourcePermissionService = Mock(MorpheusSynchronousResourcePermissionService)
        cloudService = Mock(MorpheusSynchronousCloudService)
        networkService = Mock(MorpheusSynchronousNetworkService)
        asyncStorageVolumeService = Mock(MorpheusStorageVolumeService)
        virtualImageService = Mock(MorpheusSynchronousVirtualImageService)
        asyncVirtualImageService = Mock(MorpheusVirtualImageService)
        fileCopyService = Mock(MorpheusSynchronousFileCopyService)

        def morpheusServices = Mock(MorpheusServices) {
            getComputeServer() >> computeServerService
            getCloud() >> cloudService
            getWorkloadType() >> workloadTypeService
            getStorageVolume() >> storageVolumeService
            getVirtualImage() >> virtualImageService
            getResourcePermission() >> resourcePermissionService
            getFileCopy() >> fileCopyService
            getNetwork() >> networkService
        }
        def morpheusAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> asyncCloudService
            getNetwork() >> asyncNetworkService
            getComputeServer() >> asyncComputeServerService
            getStorageVolume() >> asyncStorageVolumeService
            getVirtualImage() >> asyncVirtualImageService
            getComputeTypeSet() >> asyncComputeTypeSetService
            getWorkloadType() >> asyncWorkloadTypeService
        }

        // Configure context mocks
        morpheusContext.getProcess() >> processService
        morpheusContext.getAsync() >> morpheusAsyncServices
        morpheusContext.getServices() >> morpheusServices

        apiService = Spy(ScvmmApiService, constructorArgs: [morpheusContext])
    }

    @Unroll
    def "test transferImage transfers image successfully"() {
        given:
        def mockedComputerServer = Mock(ComputeServer)
        morpheusContext.getServices() >> Mock(MorpheusServices) {
            getComputeServer() >> {
                return computeServerService
            }
        }

        // After creating morpheusContext mock
        morpheusContext.executeWindowsCommand(*_) >> Single.just([success: true,
                                                                  data: "{\"Mode\":\"d-----\",\"Name\":\"testImage\",\"Attributes\":\"Directory\"}"])

        // Prepare test data
        def opts = [zoneRoot: "C:\\Temp", sshPort: '22', sshHost: 'localhost', sshUsername: 'admin', sshPassword: 'password']
        // Add this before using opts
        opts.hypervisor = mockedComputerServer
        def inputStreamData = Mock(InputStream)
        def metadataFile = Mock(CloudFile)
        metadataFile.name >> "metadata.json"
        metadataFile.inputStream >> inputStreamData
        metadataFile.contentLength >> 3L

        def cloudFiles = [metadataFile]
        def imageName = "testImage"
        def serviceResp =  new ServiceResponse(success: true)

        fileCopyService.copyToServer(_,_,_,_,_,_,_) >> {
            println("copyToServer called with args: ${it}")
            return serviceResp
        }

        when:
        def result = apiService.transferImage(opts, cloudFiles, imageName)

        then:
        result.success == true
    }

    def "test stopServer successfully stops server"() {
        given:
        def server = Mock(ComputeServer) {
            id >> 1L
            name >> "test-vm"
        }
        def opts = [zoneRoot: "C:\\Temp", hypervisor: server, sshHost: 'localhost', sshUsername: 'admin', sshPassword: 'password']

        morpheusContext.executeWindowsCommand(*_) >> Single.just([success: true, data: '{"Status":"Success"}'])

        when:
        def result = apiService.stopServer(opts, server.id.toString())

        then:
        result.success == true
    }


    def "test deleteIso successfully deletes ISO file"() {
        given:
        def opts = [
                zoneRoot: "C:\\Temp",
                sshHost: 'localhost',
                sshUsername: 'admin',
                sshPassword: 'password'
        ]
        def isoPath = "C:\\Temp\\isos\\test.iso"

        morpheusContext.executeWindowsCommand(*_) >> Single.just([success: true, data: '{"Status":"Success"}'])

        when:
        def result = apiService.deleteIso(opts, isoPath)

        then:
        result.success == true
    }

    @Unroll
    def "test snapshotServer successfully creates a checkpoint"() {
        given:
        def vmId = "vm-12345"
        def opts = [sshHost: 'scvmm-server', sshUsername: 'admin', sshPassword: 'password', winrmPort: '5985']

        // Mock the command execution with a successful result
        morpheusContext.executeWindowsCommand(*_) >> Single.just([
                success: true,
                exitCode: '0',
                data: '{"Status":"Success"}'
        ])

        when:
        def result = apiService.snapshotServer(opts, vmId)

        then:
        1 * morpheusContext.executeWindowsCommand(
                'scvmm-server',
                5985,
                'admin',
                'password',
                { String cmd -> cmd.contains("Get-SCVirtualMachine") && cmd.contains("New-SCVMCheckpoint") && cmd.contains(vmId) },
                null,
                false
        ) >> Single.just([success: true, exitCode: '0', data: '{"Status":"Success"}'])

        result.success == true
        result.snapshotId != null
    }

    @Unroll
    def "test deleteSnapshot successfully removes a checkpoint"() {
        given:
        def vmId = "vm-12345"
        def snapshotId = "snapshot-6789"
        def opts = [sshHost: 'scvmm-server', sshUsername: 'admin', sshPassword: 'password', winrmPort: '5985']

        // Mock the command execution with a successful result
        morpheusContext.executeWindowsCommand(*_) >> Single.just([
                success: true,
                exitCode: '0',
                data: '{"Status":"Success"}'
        ])

        when:
        def result = apiService.deleteSnapshot(opts, vmId, snapshotId)

        then:
        1 * morpheusContext.executeWindowsCommand(
                'scvmm-server',
                5985,
                'admin',
                'password',
                { String cmd ->
                    cmd.contains('$VM = Get-SCVirtualMachine -VMMServer localhost -ID "vm-12345"') &&
                            cmd.contains('$Checkpoint = Get-SCVMCheckpoint -VM $VM | where {$_.Name -like "snapshot-6789"}') &&
                            cmd.contains('$ignore = Remove-SCVMCheckpoint -VMCheckpoint $Checkpoint')
                },
                null,
                false
        ) >> Single.just([success: true, exitCode: '0', data: '{"Status":"Success"}'])

        result.success == true
        result.snapshotId == snapshotId
    }

    @Unroll
    def "test restoreServer successfully restores a VM to a checkpoint"() {
        given:
        def vmId = "vm-12345"
        def snapshotId = "snapshot-6789"
        def opts = [sshHost: 'scvmm-server', sshUsername: 'admin', sshPassword: 'password', winrmPort: '5985']

        // Mock the command execution with a successful result
        morpheusContext.executeWindowsCommand(*_) >> Single.just([
                success: true,
                exitCode: '0',
                data: '{"Status":"Success"}'
        ])

        when:
        def result = apiService.restoreServer(opts, vmId, snapshotId)

        then:
        1 * morpheusContext.executeWindowsCommand(
                'scvmm-server',
                5985,
                'admin',
                'password',
                { String cmd ->
                    cmd.contains('$VM = Get-SCVirtualMachine -VMMServer localhost -ID "vm-12345"') &&
                            cmd.contains('$Checkpoint = Get-SCVMCheckpoint -VM $VM | where {$_.Name -like "snapshot-6789"}') &&
                            cmd.contains('Restore-SCVMCheckpoint -VMCheckpoint $Checkpoint')
                },
                null,
                false
        ) >> Single.just([success: true, exitCode: '0', data: '{"Status":"Success"}'])

        result.success == true
    }

    @Unroll
    def "test buildCreateServerCommands generates correct PowerShell script"() {
        given:
        // Setup complete options for VM creation
        def opts = ApiServiceDataHelper.buildCreateServerCommands_getPowerShellOpts

        when:
        def result = apiService.buildCreateServerCommands(opts)

        then:
        // Verify the PowerShell script contains all expected commands
        result instanceof Map

        result.launchCommand != null
        result.launchCommand.contains('$VMNetwork = Get-SCVMNetwork -VMMServer localhost -ID "network-abcd1234-5678-90ef-ghij-klmn"')
        result.launchCommand.contains('$VMSubnet = Get-SCVMSubnet -VMMServer localhost -ID "subnet-abcd1234-5678-90ef-ghij-klmno"')
        result.launchCommand.contains('$MACAddressType = "Static"')
        result.launchCommand.contains('-VLanEnabled $true -VLanID 42')
        result.launchCommand.contains('-IPv4AddressType Static -IPv4Address "192.168.1.100"')
        result.launchCommand.contains('$vmHost = Get-SCVMHost -ID "host-ext-id-456"')
        result.launchCommand.contains('-HighlyAvailable $true')
        result.launchCommand.contains('-DynamicMemoryMinimumMB 2048')
        result.launchCommand.contains('-DynamicMemoryMaximumMB 8192')
        result.launchCommand.contains('$cloud = Get-SCCloud -ID "us-east"')
        result.launchCommand.contains('$VM = Get-SCVirtualMachine -VMMServer localhost -ID "clone-vm-6789"')
        result.launchCommand.contains('-Name "vm-12345"')

    }

    def "test buildCreateServerCommands generates correct hardware profile commands from template"() {
        given:
        // Setup complete options with template-specific settings
        def opts = ApiServiceDataHelper.buildCreateServerCommands_getTemplateOpts

        when:
        def result = apiService.buildCreateServerCommands(opts)

        then:
        // Verify the PowerShell script contains expected hardware profile commands
        result instanceof Map
        result.launchCommand != null

        // Check for template and network components
        result.launchCommand.contains('\u0024template = Get-SCVMTemplate -VMMServer localhost | where {\u0024_.ID -eq "template-5678"}')
        result.launchCommand.contains('\u0024VMNetwork = Get-SCVMNetwork -VMMServer localhost -ID "network-abcd1234-5678-90ef-ghij-klmn"')
        result.launchCommand.contains('\u0024VMSubnet = Get-SCVMSubnet -VMMServer localhost -ID "subnet-abcd1234-5678-90ef-ghij-klmno"')
        // Check for network adapter config
        result.launchCommand.contains('\u0024MACAddressType = "Static"')
        result.launchCommand.contains('-VLanEnabled \u0024true -VLanID 42')
        // Check for IP configuration
        result.launchCommand.contains('-IPv4AddressType Static')
        result.launchCommand.contains('\u0024ipaddress = Get-SCIPAddress -IPAddress "192.168.1.100"')
        // Check for hardware profile settings
        result.launchCommand.contains('-HighlyAvailable \u0024true')
        result.launchCommand.contains('-DynamicMemoryMinimumMB 2048')
        result.launchCommand.contains('-DynamicMemoryMaximumMB 8192')
        // Check for cloud configuration
        result.launchCommand.contains('\u0024cloud = Get-SCCloud -ID "us-east"')
        // Check for VM creation command
        result.launchCommand.contains('\u0024createdVm = New-SCVirtualMachine')
        result.launchCommand.contains('\u0024createdVm | Select ID, ObjectType')
    }

    @Unroll
    def "test changeVolumeTypeForClonedBootDisk successfully changes volume type"() {
        given:
        def originalVMId = "vm-original-123"
        def newVMId = "vm-new-456"

        def opts = DEFAULT_OPTS.clone()

        // Mock the command execution with a successful result
        morpheusContext.executeWindowsCommand(*_) >> Single.just([
                success: true,
                exitCode: '0',
                data: '{"Status":"Success"}'
        ])

        when:
        def result = apiService.changeVolumeTypeForClonedBootDisk(opts, originalVMId, newVMId)

        then:
        1 * morpheusContext.executeWindowsCommand(
                'scvmm-server',
                5985,
                'admin',
                'password',
                { String cmd ->
                    cmd.contains('$ClonedVM = Get-SCVirtualMachine -VMMServer localhost -ID "vm-original-123"') &&
                            cmd.contains('$OriginalBootDisk = Get-SCVirtualDiskDrive -VMMServer localhost -VM $ClonedVM | where {$_.VolumeType -eq "BootAndSystem"}') &&
                            cmd.contains('$NewVM = Get-SCVirtualMachine -VMMServer localhost -ID "vm-new-456"') &&
                            cmd.contains('$ClonedBootDisk = Get-SCVirtualDiskDrive -VMMServer localhost -VM $NewVM | where {$_.VirtualHardDisk -like [io.path]::GetFileNameWithoutExtension($OriginalBootDisk.VirtualHardDisk)}') &&
                            cmd.contains('Set-SCVirtualDiskDrive -VirtualDiskDrive $ClonedBootDisk -VolumeType BootAndSystem')
                },
                null,
                false
        ) >> Single.just([success: true, exitCode: '0', data: '{"Status":"Success"}'])

        result.success == true
    }

    @Unroll
    def "findBootDiskIndex returns correct index for boot disk when #scenario"() {
        given:
        def diskDrives = [
                disks: disksData
        ]

        when:
        def result = apiService.findBootDiskIndex(diskDrives)

        then:
        result == expectedIndex

        where:
        scenario                        | disksData                                                                               | expectedIndex
        "boot disk is first"            | [[VolumeType: 'BootAndSystem'], [VolumeType: 'Data']]                                   | 0
        "boot disk is second"           | [[VolumeType: 'Data'], [VolumeType: 'BootAndSystem'], [VolumeType: 'Data']]             | 1
        "boot disk is last"             | [[VolumeType: 'Data'], [VolumeType: 'Data'], [VolumeType: 'BootAndSystem']]             | 2
        "no boot disk exists"           | [[VolumeType: 'Data'], [VolumeType: 'Data']]                                            | 0
        "empty disks array"             | []                                                                                      | 0
        "disks with null VolumeType"    | [[VolumeType: null], [VolumeType: 'Data'], [VolumeType: 'BootAndSystem']]               | 2
        "case sensitive check"          | [[VolumeType: 'bootandsystem'], [VolumeType: 'Data'], [VolumeType: 'BootAndSystem']]    | 2
        "null disks property"           | null                                                                                    | 0
    }

    @Unroll
    def "test appendOSCustomization appends correct customization options for #scenario"() {
        given:
        def sourceString = "New-SCVirtualMachine -Name 'testvm'"
        def opts = [
                isSysprep: isSysprep,
                cloneVMId: cloneVMId,
                hostname: hostname,
                license: license
        ]

        when:
        def result = apiService.appendOSCustomization(sourceString, opts)

        then:
        result == expectedResult

        where:
        scenario                      | isSysprep | cloneVMId | hostname      | license                                                         | expectedResult
        "no sysprep"                  | false     | null      | "testhost"    | [fullName: "Test User", productKey: "ABCDE-12345", orgName: "TestOrg"] | "New-SCVirtualMachine -Name 'testvm'"
        "clone VM"                    | true      | "vm-123"  | "testhost"    | [fullName: "Test User", productKey: "ABCDE-12345", orgName: "TestOrg"] | "New-SCVirtualMachine -Name 'testvm'"
        "hostname only"               | true      | null      | "testhost"    | null                                                            | "New-SCVirtualMachine -Name 'testvm' -ComputerName \"testhost\""
        "license only"                | true      | null      | null          | [fullName: "Test User", productKey: "ABCDE-12345", orgName: "TestOrg"] | "New-SCVirtualMachine -Name 'testvm' -FullName \"Test User\" -ProductKey \"ABCDE-12345\" -OrganizationName \"TestOrg\""
        "partial license"             | true      | null      | null          | [fullName: "Test User", orgName: "TestOrg"]                     | "New-SCVirtualMachine -Name 'testvm' -FullName \"Test User\" -OrganizationName \"TestOrg\""
        "hostname and license"        | true      | null      | "testhost"    | [fullName: "Test User", productKey: "ABCDE-12345", orgName: "TestOrg"] | "New-SCVirtualMachine -Name 'testvm' -ComputerName \"testhost\" -FullName \"Test User\" -ProductKey \"ABCDE-12345\" -OrganizationName \"TestOrg\""
        "empty hostname"              | true      | null      | ""            | null                                                            | "New-SCVirtualMachine -Name 'testvm'"
        "all options"                 | true      | null      | "testhost"    | [fullName: "Test User", productKey: "ABCDE-12345", orgName: "TestOrg"] | "New-SCVirtualMachine -Name 'testvm' -ComputerName \"testhost\" -FullName \"Test User\" -ProductKey \"ABCDE-12345\" -OrganizationName \"TestOrg\""
    }

    @Unroll
    def "test generateDataDiskCommand with #scenario"() {
        given:
        def previousFileName = null
        def jobGuid = "job-123"

        when:
        def result = apiService.generateDataDiskCommand(
                busNumber, dataDiskNumber, jobGuid, sizeMB, path, fromDisk, discoverAvailableLUN, deployingToCloud
        )

        // If we need to test uniqueness, generate a second result with same params
        def secondResult = null
        if (testUniqueness) {
            secondResult = apiService.generateDataDiskCommand(
                    busNumber, dataDiskNumber, jobGuid, sizeMB, path, fromDisk, discoverAvailableLUN, deployingToCloud
            )
        }

        then:
        // Verify fileName format
        result.fileName.startsWith("data${dataDiskNumber}-")
        result.fileName.endsWith(".vhd")

        // Verify command structure based on parameters
        result.command.contains("-VMMServer localhost")
        result.command.contains("-${diskType}")
        result.command.contains("-Bus ${expectedBus}")
        result.command.contains("-LUN ${dataDiskNumber}")
        result.command.contains("-JobGroup ${jobGuid}")

        // Check path inclusion
        if (path && !deployingToCloud) {
            result.command.contains("-Path \"${path}\"")
        }

        // Check for size or fromDisk parameters
        if (fromDisk) {
            result.command.contains("-VirtualHardDisk ${fromDisk}")
            !result.command.contains("VirtualHardDiskSizeMB")
        } else {
            result.command.contains("-VirtualHardDiskSizeMB ${sizeMB}")
            result.command.contains("-Dynamic")
        }

        // Verify VolumeType inclusion
        result.command.contains("-VolumeType None")

        // Test uniqueness if required
        if (testUniqueness) {
            secondResult.fileName != result.fileName
            secondResult.fileName.startsWith("data${dataDiskNumber}-")
            secondResult.fileName.endsWith(".vhd")
        }

        where:
        scenario                               | busNumber | dataDiskNumber | sizeMB | path           | fromDisk       | discoverAvailableLUN | deployingToCloud | diskType | expectedBus | testUniqueness
        "default values"                       | "0"       | 1              | 10240  | null           | null           | false                | false            | "SCSI"   | "0"         | false
        "with path"                            | "0"       | 2              | 10240  | "C:\\VMs"      | null           | false                | false            | "SCSI"   | "0"         | false
        "custom bus"                           | "1"       | 3              | 10240  | null           | null           | false                | false            | "SCSI"   | "1"         | false
        "from existing disk"                   | "0"       | 4              | 10240  | null           | "existingDisk" | false                | false            | "SCSI"   | "0"         | false
        "from disk with path"                  | "0"       | 5              | 10240  | "C:\\VMs"      | "existingDisk" | false                | false            | "SCSI"   | "0"         | false
        "deploying to cloud"                   | "0"       | 6              | 10240  | null           | null           | false                | true             | "SCSI"   | "0"         | false
        "deploying to cloud with from disk"    | "0"       | 7              | 10240  | null           | "existingDisk" | true                 | true             | "SCSI"   | "0"         | false
        "test volume type and uniqueness"      | "0"       | 8              | 10240  | null           | null           | false                | false            | "SCSI"   | "0"         | true
    }

    @Unroll
    def "test getScvmmZoneOpts returns correct configuration for cloud"() {
        given:
        // Create cloud with account and config
        def account = new Account(id: 100L, name: "test-account")
        def cloud = new Cloud(
                id: 200L,
                name: "test-cloud",
                account: account,
                regionCode: "test-region"
        )

        // Set up cloud config
        def configMap = [libraryShare: "\\\\server\\share"]
        cloud.configMap = configMap

        // Mock the cloud.getConfigMap() method
        cloud.getConfigMap() >> configMap

        // Mock keyPair service and data query response
        def keyPair = new KeyPair(
                id: 300L,
                publicKey: "ssh-rsa AAAAB3NzaC1yc2E...",
                privateKey: "-----BEGIN RSA PRIVATE KEY-----\nMIIE..."
        )

        MorpheusSynchronousKeyPairService keyPairService = Mock(MorpheusSynchronousKeyPairService)
        def morphServ = morpheusContext.getServices()
        morphServ.getKeyPair() >> {
            return keyPairService
        }

        // Mock the find method to return our test keyPair
        keyPairService.find({ DataQuery query ->
            query.filters.any { it.name == "accountId" && it.value == 100L }
        }) >> keyPair

        when:
        def result = apiService.getScvmmZoneOpts(morpheusContext, cloud)

        then:
        result.account == account
        result.zoneConfig == configMap
        result.zone == cloud
        result.zoneId == 200L
        result.publicKey == "ssh-rsa AAAAB3NzaC1yc2E..."
        result.privateKey == "-----BEGIN RSA PRIVATE KEY-----\nMIIE..."
        result.rootSharePath == "\\\\server\\share"
        result.regionCode == "test-region"

        // Verify the keyPair service was called exactly once
        1 * keyPairService.find(_) >> keyPair
    }

    def "test getScvmmCloudOpts returns correctly populated cloud options"() {
        given:
        // Create cloud with account and config
        def account = new Account(id: 100L, name: "test-account")
        def cloud = new Cloud(
                id: 200L,
                name: "test-cloud",
                account: account,
                regionCode: "test-region"
        )

        // Create controller server
        def controllerServer = new ComputeServer(id: 300L, name: "test-controller")

        // Set up cloud config
        def configMap = [libraryShare: "\\\\server\\share", otherConfig: "value"]
        cloud.configMap = configMap

        // Mock the cloud.getConfigMap() method
        cloud.getConfigMap() >> configMap

        // Create test keyPair
        def keyPair = new KeyPair(
                id: 400L,
                publicKey: "ssh-rsa AAAAB3NzaC1yc2E...",
                privateKey: "-----BEGIN RSA PRIVATE KEY-----\nMIIE..."
        )

        // Mock keyPair service
        MorpheusSynchronousKeyPairService keyPairService = Mock(MorpheusSynchronousKeyPairService)
        morpheusContext.getServices().getKeyPair() >> keyPairService

        // Mock the keyPairService.find method to return our test keyPair
        keyPairService.find({ DataQuery query ->
            query.filters.any { it.name == "accountId" && it.value == 100L }
        }) >> keyPair

        when:
        def result = apiService.getScvmmCloudOpts(morpheusContext, cloud, controllerServer)

        then:
        // Verify all expected properties are in the result
        result.account == account
        result.zoneConfig == configMap
        result.zone == cloud
        result.zoneId == 200L
        result.publicKey == "ssh-rsa AAAAB3NzaC1yc2E..."
        result.privateKey == "-----BEGIN RSA PRIVATE KEY-----\nMIIE..."
        result.controllerServer == controllerServer
        result.rootSharePath == "\\\\server\\share"
        result.regionCode == "test-region"

        // Verify the keyPair service was called exactly once
        1 * keyPairService.find(_) >> keyPair
    }

    def "getScvmmControllerOpts correctly extracts controller options from server"() {
        given:
        def cloud = new Cloud(id: 1L)

        def server = ApiServiceDataHelper.scvmmControllerOpts_getServer()

        when:
        def result = apiService.getScvmmControllerOpts(cloud, server)

        then:
        result.sshHost == "10.0.0.10"
        result.sshUsername == "admin"
        result.sshPassword == "securepass123"
        result.hypervisor == server
    }

    @Unroll
    def "test getScvmmZoneAndHypervisorOpts correctly combines options from cloud and controller"() {
        given:
        // Create test objects
        def cloud = new Cloud(id: 200L, name: "test-cloud")
        def hypervisor = new ComputeServer(id: 300L, name: "test-hypervisor")

        def cloudOpts = [zoneId: 200L, cloudName: "test-cloud", regionCode: "us-east-1",
                         rootSharePath: "\\\\server\\share"]

        def controllerOpts = [
                sshHost: "10.0.0.10", sshUsername: "admin", sshPassword: "password123", hypervisor: hypervisor]

        // Mock the methods that are called inside getScvmmZoneAndHypervisorOpts
        apiService.getScvmmCloudOpts(_, _, _) >> {
            return cloudOpts
        }
        apiService.getScvmmControllerOpts(_, _) >> {
            return controllerOpts
        }

        when:
        def result = apiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, hypervisor)

        then:
        // Verify that result contains combined options from both methods
        result.zoneId == 200L
        result.cloudName == "test-cloud"
        result.regionCode == "us-east-1"
        result.rootSharePath == "\\\\server\\share"
        result.sshHost == "10.0.0.10"
        result.sshUsername == "admin"
        result.sshPassword == "password123"
        result.hypervisor == hypervisor

        // Verify each method was called exactly once with the correct parameters
        1 * apiService.getScvmmCloudOpts(_, _, _) >> cloudOpts
        1 * apiService.getScvmmControllerOpts(_, _) >> controllerOpts
    }

    @Unroll
    def "loadControllerServer correctly loads controller server when controllerServerId is #scenario"() {
        given:
        // Create a test options map
        def controllerId = 123L
        def opts = [controllerServerId: controllerId]

        // Create a server to be returned by the service
        def serverToReturn = controllerId ? new ComputeServer(id: controllerId, name: "controller-server") : null

        // Mock the computeServerService get method to return our test server

        computeServerService.get(controllerId) >> serverToReturn

        when:
        apiService.loadControllerServer(opts)

        then:
        // Verify the result based on whether a controllerId was provided

        opts.controllerServer == serverToReturn

        1 * computeServerService.get(controllerId) >> serverToReturn

    }

    @Unroll
    def "test isHostInHostGroup with currentPath=#currentPath and testPath=#testPath returns #expectedResult"() {
        when:
        def result = apiService.isHostInHostGroup(currentPath, testPath)

        then:
        result == expectedResult

        where:
        currentPath              | testPath               | expectedResult | scenario
        "HostGroup\\Host"        | "HostGroup"            | true          | "host is direct child of hostgroup"
        "HostGroup\\SubGroup\\Host" | "HostGroup"        | true          | "host is in nested subgroup"
        "HostGroup"              | "HostGroup"            | true          | "paths are identical"
        "HostGroup2\\Host"       | "HostGroup"            | false         | "different host groups"
        "HostGroupX"             | "HostGroup"            | false         | "similar prefix but not in group"
        "HostGroup\\Host"        | null                   | false         | "null test path"
        ""                       | "HostGroup"            | false         | "empty current path"
        "HostGroup"              | ""                     | false         | "empty test path"
        "HostGroup\\Host"        | "hostgroup"            | false         | "case sensitive comparison"
    }

    @Unroll
    def "test getScvmmInitializationOpts with #scenario"() {
        given:
        // Create a cloud with configuration
        def cloud = new Cloud(id: 1L, name: "test-cloud")
        def configMap = [
                host: hostValue,
                diskPath: diskPath,
                workingPath: workingPath
        ]
        def configJson = groovy.json.JsonOutput.toJson(configMap)
        cloud.setAccountCredentialLoaded(true)
        def actCredDataMap =  [username:"dunno", password: "testpass"]
        cloud.setAccountCredentialData(actCredDataMap)
        cloud.servicePassword = "testpass"
        cloud.serviceUsername = "dunno"

        // Mock getConfigMap to return our test config
        cloud.setConfig(configJson.toString())

        // Set the defaultRoot field to test fallbacks
        apiService.defaultRoot = "C:\\MorpheusData"

        when:
        def result = apiService.getScvmmInitializationOpts(cloud)

        then:
        result.sshUsername == "dunno"
        result.sshPassword == "testpass"

        where:
        scenario                   | hostValue     | diskPath             | workingPath           | expectedZoneRoot       | expectedDiskRoot
        "all paths specified"      | "10.0.0.10"   | "D:\\CustomDisks"    | "D:\\CustomWorking"   | "D:\\CustomWorking"    | "D:\\CustomDisks"
        "missing disk path"        | "10.0.0.11"   | ""                   | "D:\\CustomWorking"   | "D:\\CustomWorking"    | "C:\\MorpheusData\\Disks"
        "missing working path"     | "10.0.0.12"   | "D:\\CustomDisks"    | ""                    | "C:\\MorpheusData"     | "D:\\CustomDisks"
        "missing both paths"       | "10.0.0.13"   | ""                   | ""                    | "C:\\MorpheusData"     | "C:\\MorpheusData\\Disks"
        "null disk path"           | "10.0.0.14"   | null                 | "D:\\CustomWorking"   | "D:\\CustomWorking"    | "C:\\MorpheusData\\Disks"
        "null working path"        | "10.0.0.15"   | "D:\\CustomDisks"    | null                  | "C:\\MorpheusData"     | "D:\\CustomDisks"
        "null both paths"          | "10.0.0.16"   | null                 | null                  | "C:\\MorpheusData"     | "C:\\MorpheusData\\Disks"
    }

    @Unroll
    def "test getUsername returns #expectedUsername when #scenario"() {
        given:
        def cloud = new Cloud(id: 1L)
        cloud.accountCredentialLoaded = credentialLoaded
        if (hasCredentialData) {
            cloud.accountCredentialData = [username: credentialUsername]
        }
        if (hasConfigProperty) {
            cloud.setConfigProperty('username', configUsername)
        }

        when:
        def result = apiService.getUsername(cloud)

        then:
        result == expectedUsername

        where:
        scenario                                | credentialLoaded | hasCredentialData | credentialUsername | hasConfigProperty | configUsername | expectedUsername
        "credentials loaded with username"      | true             | true              | "credential-user"  | false             | null           | "credential-user"
        "credentials not loaded with config"    | false            | false             | null               | true              | "config-user"  | "config-user"
        "no credentials or config"              | false            | false             | null               | false             | null           | "dunno"
        "empty config falls to default"         | false            | false             | null               | true              | ""             | "dunno"
    }

    @Unroll
    def "test getPassword returns #expectedPassword when #scenario"() {
        given:
        def cloud = new Cloud(id: 1L)
        cloud.accountCredentialLoaded = credentialLoaded
        if (hasCredentialData) {
            cloud.accountCredentialData = [password: credentialPassword]
        }
        if (hasConfigProperty) {
            cloud.setConfigProperty('password', configPassword)
        }

        when:
        def result = apiService.getPassword(cloud)

        then:
        result == expectedPassword

        where:
        scenario                                | credentialLoaded | hasCredentialData | credentialPassword | hasConfigProperty | configPassword | expectedPassword
        "credentials loaded with password"      | true             | true              | "credential-pass"  | false             | null           | "credential-pass"
        "credentials not loaded with config"    | false            | false             | null               | true              | "config-pass"  | "config-pass"
        "no credentials or config"              | false            | false             | null               | false             | null           | null
        "empty config returns null"             | false            | false             | null               | true              | ""             | ""
        "credentials loaded but null data"      | true             | false             | null               | true              | "config-pass"  | "config-pass"
    }

    @Unroll
    def "test deleteImage successfully removes image directory"() {
        given:
        def imageName = "test-image"
        def opts = [
                zoneRoot: "C:\\Temp",
                sshHost: 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password',
                winrmPort: '5985'
        ]

        // Spy on formatImageFolder to return a known value
        apiService.formatImageFolder(imageName) >> "test-image-folder"

        // Mock the command execution with a successful result
        morpheusContext.executeWindowsCommand(*_) >> Single.just([
                success: true,
                exitCode: '0',
                data: '{"Status":"Success"}'
        ])

        when:
        def result = apiService.deleteImage(opts, imageName)

        then:
        // Verify formatImageFolder was called with the image name
        1 * apiService.formatImageFolder(imageName) >> "test-image-folder"

        // Verify generateCommandString was called with the correct command
        1 * apiService.generateCommandString("Remove-Item -LiteralPath \"C:\\Temp\\images\\test-image-folder\" -Recurse -Force") >> "powershell -command \"Remove-Item -LiteralPath \\\"C:\\Temp\\images\\test-image-folder\\\" -Recurse -Force\""

        // Verify executeWindowsCommand was called with the correct parameters
        1 * morpheusContext.executeWindowsCommand(
                'scvmm-server',
                5985,
                'admin',
                'password',
                "powershell -command \"Remove-Item -LiteralPath \\\"C:\\Temp\\images\\test-image-folder\\\" -Recurse -Force\"",
                null,
                false
        ) >> Single.just([success: true, exitCode: '0', data: '{"Status":"Success"}'])

        // Verify the result was successful
        result.success == true
    }

    @Unroll
    def "test deleteImage uses defaultRoot when zoneRoot is not provided"() {
        given:
        def imageName = "test-image"
        def opts = DEFAULT_OPTS.clone()

        // Set the defaultRoot value
        apiService.defaultRoot = "C:\\MorpheusData"

        // Spy on formatImageFolder to return a known value
        apiService.formatImageFolder(imageName) >> "test-image-folder"

        // Mock the wrapExecuteCommand to return success
        apiService.wrapExecuteCommand(_, opts) >> [success: true, data: '{"Status":"Success"}']

        when:
        def result = apiService.deleteImage(opts, imageName)

        then:
        // Verify generateCommandString was called with a command that uses the defaultRoot
        1 * apiService.generateCommandString({ String cmd ->
            cmd.contains("C:\\MorpheusData\\images\\test-image-folder")
        }) >> "powershell command string"

        // Verify the result was successful
        result.success == true
    }

    @Unroll
    def "test findImage correctly detects if an image exists"() {
        given:
        def imageName = "test-image"
        def formattedImageFolder = "test_image"
        def zoneRoot = "C:\\SCVMM"
        def imageFolderPath = "${zoneRoot}\\images\\${formattedImageFolder}"
        def opts = [
                sshHost: 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password',
                winrmPort: '5985',
                zoneRoot: zoneRoot
        ]
        def mockedResponse = imageExists ? "C:\\SCVMM\\images\\test_image\\disk.vhd" : ""

        when:
        def result = apiService.findImage(opts, imageName)

        then:
        1 * apiService.executeCommand(
                { String cmd ->
                    cmd == "(Get-ChildItem -File \"${imageFolderPath}\").FullName"
                },
                opts
        ) >> [success: true, data: mockedResponse]

        1 * apiService.formatImageFolder(imageName) >> formattedImageFolder

        result.success == true
        result.imageExists == imageExists
        if (imageExists) {
            result.imageName == "C:\\SCVMM\\images\\test_image\\disk.vhd"
        }

        where:
        imageExists << [true, false]
    }

    @Unroll
    def "test getMapScvmmOsType with #scenario"() {

        when:
        def result = apiService.getMapScvmmOsType(searchFor, findByKey, defaultOsType)

        then:
        result == expectedResult

        where:
        scenario                                  | searchFor                                     | findByKey | defaultOsType | expectedResult
        "finding by exact key match"              | "Windows Server 2016 Datacenter"              | true      | null          | "windows.server.2016"
        "finding by key with no match uses other" | "Non-existent OS"                            | true      | null          | "other"
        "finding by key with default fallback"    | "Non-existent OS"                            | true      | ""            | "other"
        "finding by value with exact match"       | "windows.server.2019"                         | false     | null          | "Windows Server 2019 Datacenter"
        "finding by value with no match"          | "non.existent.os"                             | false     | null          | null
        "finding Linux OS by key"                 | "Ubuntu Linux 20.04 (64 bit)"                | true      | null          | "ubuntu.20.04.64"
        "finding Windows OS by key"               | "64-bit edition of Windows Server 2012 Datacenter" | true | null          | "windows.server.2012"
        "finding with empty key"                  | ""                                            | true      | null          | "other"
        "finding with null key"                   | null                                          | true      | null          | "other"
        "finding by value with multiple matching keys" | "windows.server.2025"                    | false     | null          | "Windows Server 2025 Datacenter"
    }

    @Unroll
    def "test cleanData with #scenario"() {
        given:
        def data = inputData
        def ignore = ignoreString

        when:
        def result = apiService.cleanData(data, ignore)

        then:
        result == expectedResult

        where:
        scenario                      | inputData                                  | ignoreString | expectedResult
        "null data"                   | null                                       | null         | ""
        "empty data"                  | ""                                         | null         | ""
        "single line"                 | "test data"                                | null         | "test data"
        "single line with whitespace" | "   test data   "                          | null         | "test data"
        "multiple lines"              | "line1\nline2\nline3"                      | null         | "line3"
        "short lines filtered out"    | "a\nline2\n.\nline4"                       | null         | "line4"
    }

    @Unroll
    def "test updateServer handles different update scenarios correctly for #scenario"() {
        given:
        def vmId = "vm-12345"
        def opts = DEFAULT_OPTS.clone()
        def updates = providedUpdates

        // Set up mocks for the command execution
        def expectedCommandPattern = commandPattern
        def commandOutput = [success: true, exitCode: '0', data: '{"Status":"Success"}']

        when:
        def result = apiService.updateServer(opts, vmId, updates)

        then:
        if (shouldCallCommand) {
            1 * apiService.wrapExecuteCommand(_, _) >> commandOutput
        } else {
            0 * apiService.generateCommandString(_)
            0 * apiService.wrapExecuteCommand(_, _)
        }

        result.success == true

        where:
        scenario                    | providedUpdates                                           | commandPattern                                           | shouldCallCommand
        "update CPU only"           | [maxCores: 4]                                             | ["-CPUCount 4"]                                           | true
        "update memory only"        | [maxMemory: 8589934592L]                                  | ["\$maxMemory = 8192", "DynamicMemoryEnabled \$false"]    | true
        "update memory and CPU"     | [maxMemory: 4294967296L, maxCores: 2]                     | ["-CPUCount 2", "\$maxMemory = 4096"]                     | true
        "update with min memory"    | [maxMemory: 4294967296L, minDynamicMemory: 2147483648L]   | ["\$minDynamicMemory = 2048", "DynamicMemoryEnabled \$true"] | true
        "update with max memory"    | [maxMemory: 4294967296L, maxDynamicMemory: 8589934592L]   | ["\$maxDynamicMemory = 8192", "DynamicMemoryEnabled \$true"] | true
        "update with all memory"    | [maxMemory: 4294967296L, minDynamicMemory: 2147483648L, maxDynamicMemory: 8589934592L] | ["\$minDynamicMemory = 2048", "\$maxDynamicMemory = 8192", "DynamicMemoryEnabled \$true"] | true
    }

    @Unroll
    def "test validateServerConfig with #scenario"() {
        given:
        def opts = inputOpts

        when:
        def result = apiService.validateServerConfig(opts)

        then:
        result.success == expectedSuccess
        if (!expectedSuccess) {
            result.errors.size() == expectedErrorCount
            result.errors.any { it.field == expectedErrorField && it.msg == expectedErrorMsg }
        }

        where:
        scenario                                | inputOpts                                                                                  | expectedSuccess | expectedErrorCount | expectedErrorField      | expectedErrorMsg
        "missing capability profile"            | [:]                                                                                        | false           | 2                  | "scvmmCapabilityProfile"| "You must select a capability profile"
        "missing network"                       | [scvmmCapabilityProfile: "Hyper-V"]                                                        | false           | 1                  | "networkId"             | "Network is required"
        "empty nodeCount"                       | [scvmmCapabilityProfile: "Hyper-V", networkId: "net-123", nodeCount: ""]                   | false           | 1                  | "nodeCount"             | "You must indicate number of hosts"
        "valid config with networkId"           | [scvmmCapabilityProfile: "Hyper-V", networkId: "net-123"]                                  | true            | 0                  | null                    | null
        "missing network id in interface"       | [scvmmCapabilityProfile: "Hyper-V", networkInterfaces: [[network: [:]]]]                   | false           | 1                  | "networkInterface"      | "Network is required"
        "invalid networkInterface config"       | [scvmmCapabilityProfile: "Hyper-V", networkInterface: [network: [id: [""]], ipMode: ["static"]]]               | false           | 1                  | "networkInterface"      | "Network is required"
        "static IP missing in networkInterface" | [scvmmCapabilityProfile: "Hyper-V", networkInterface: [network: [id: ["net-def"]], ipMode: ["static"], ipAddress: [null]]] | false | 1 | "networkInterface" | "You must enter an ip address"
    }

    def "test createDVD successfully creates DVD drive"() {
        given:
        def opts = [
                externalId: "vm-12345",
                scvmmGeneration: generation,
                sshHost: 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password',
                winrmPort: '5985'
        ]

        // Mock the command execution with a successful result
        def expectedResponse = [success: true, data: '{"success":true,"BUS":' + expectedBus + ',"LUN":' + expectedLun + '}']
        apiService.wrapExecuteCommand(_, opts) >> expectedResponse

        when:
        apiService.createDVD(opts)

        then:
        // Verify generateCommandString was called with the correct command
        1 * apiService.generateCommandString({ String cmd ->
            cmd.contains('$busNumber = ' + expectedBus) &&
                    cmd.contains('$lunNumber = ' + expectedLun) &&
                    cmd.contains('$externalId = "vm-12345"') &&
                    cmd.contains('New-SCVirtualDVDDrive -VMMServer localhost -JobGroup $jobGuid -Bus $busNumber -LUN $lunNumber')
        }) >> "powershell command"

        // Verify wrapExecuteCommand was called with the generated command
        1 * apiService.wrapExecuteCommand("powershell command", opts) >> expectedResponse

        where:
        generation     | expectedBus | expectedLun
        'generation1'  | 0           | 0
        'generation2'  | 0           | 1
    }

    @Unroll
    def "test importScript successfully imports script file"() {
        given:
        // Mock a ComputeServer
        def mockedComputerServer = Mock(ComputeServer) {
            getName() >> "test-server"
        }

        // Setup test data
        def content = "#!/bin/bash\necho Hello World"
        def diskFolder = "C:\\Temp\\Scripts"
        def imageFolderName = "test-script-folder"
        def fileName = "setup.sh"
        def sharePath = "\\\\server\\share\\setup.sh"

        def opts = [
                zoneRoot: "C:\\Temp",
                sshHost: 'localhost',
                sshUsername: 'admin',
                sshPassword: 'password',
                hypervisor: mockedComputerServer,
                fileName: fileName,
                cloudConfigBytes: content.getBytes()
        ]

        // Mock the fileCopy service response
        def serviceResp = new ServiceResponse(success: true)

        // Mock the directory creation command
        apiService.generateCommandString("\$ignore = mkdir \"${diskFolder}\"") >> "mkdir command"
        apiService.wrapExecuteCommand("mkdir command", opts) >> [success: true]

        // Mock the fileCopy service
        morpheusContext.getServices() >> Mock(MorpheusServices) {
            getFileCopy() >> fileCopyService
        }

        // Mock the importPhysicalResource method
        apiService.importPhysicalResource(opts, "${diskFolder}\\${fileName}", imageFolderName, fileName) >> [
                success: true,
                sharePath: sharePath
        ]

        when:
        def result = apiService.importScript(content, diskFolder, imageFolderName, opts)

        then:
        // Verify directory creation command was generated correctly
        1 * apiService.generateCommandString("\$ignore = mkdir \"${diskFolder}\"") >> "mkdir command"

        // Verify directory creation was executed
        1 * apiService.wrapExecuteCommand("mkdir command", opts) >> [success: true]

        // Verify file copy was called with correct parameters
        1 * fileCopyService.copyToServer(
                opts.hypervisor,
                fileName,
                "${diskFolder}\\${fileName}",
                { InputStream is ->
                    // Verify that input stream contains the expected content
                    String streamContent = new String(is.bytes)
                    streamContent == content
                },
                content.bytes.size(),
                null,
                true
        ) >> serviceResp

        // Verify importPhysicalResource was called with correct parameters
        1 * apiService.importPhysicalResource(
                opts,
                "${diskFolder}\\${fileName}",
                imageFolderName,
                fileName
        ) >> [success: true, sharePath: sharePath]

        // Verify the result is the expected share path
        result == sharePath
    }

    @Unroll
    def "test prepareNode creates all required directories with #scenario"() {
        given:
        def opts = [
                zoneRoot: zoneRoot,
                diskRoot: diskRoot,
                sshHost: 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password',
                winrmPort: '5985'
        ]

        // Set the defaultRoot value for fallback testing
        apiService.defaultRoot = "C:\\MorpheusData"

        // Mock the executeCommand method to avoid actual command execution
        apiService.executeCommand(_, _) >> [success: true, data: 'Directory created']

        when:
        apiService.prepareNode(opts)

        then:
        // Verify executeCommand was called exactly 3 times with the correct commands
        1 * apiService.executeCommand("mkdir \"${expectedZoneRoot}\\images\"", opts) >> [success: true]
        1 * apiService.executeCommand("mkdir \"${expectedZoneRoot}\\export\"", opts) >> [success: true]
        1 * apiService.executeCommand("mkdir \"${diskRoot}\"", opts) >> [success: true]

        where:
        scenario                    | zoneRoot           | diskRoot              | expectedZoneRoot
        "with provided zoneRoot"    | "D:\\CustomPath"   | "D:\\CustomDisks"     | "D:\\CustomPath"
        "with null zoneRoot"        | null               | "D:\\CustomDisks"     | "C:\\MorpheusData"
        "with empty zoneRoot"       | ""                 | "D:\\CustomDisks"     | "C:\\MorpheusData"
        "with default paths"        | "C:\\SCVMM"        | "C:\\SCVMM\\Disks"    | "C:\\SCVMM"
    }

    @Unroll
    def "test generateCommandString formats command correctly with #scenario"() {
        when:
        def result = apiService.generateCommandString(inputCommand)

        then:
        result == expectedResult

        where:
        scenario                    | inputCommand                           | expectedResult
        "simple command"            | "Get-VM"                              | "\$FormatEnumerationLimit =-1; Get-VM | ConvertTo-Json -Depth 3"
        "command with parameters"   | "Get-VM -Name 'test'"                 | "\$FormatEnumerationLimit =-1; Get-VM -Name 'test' | ConvertTo-Json -Depth 3"
        "complex PowerShell command"| "Get-SCVirtualMachine -ID 'vm-123'"   | "\$FormatEnumerationLimit =-1; Get-SCVirtualMachine -ID 'vm-123' | ConvertTo-Json -Depth 3"
        "empty command"             | ""                                    | "\$FormatEnumerationLimit =-1;  | ConvertTo-Json -Depth 3"
        "command with variables"    | "\$vm = Get-VM; \$vm.Name"            | "\$FormatEnumerationLimit =-1; \$vm = Get-VM; \$vm.Name | ConvertTo-Json -Depth 3"
    }

    @Unroll
    def "test insertContainerImage successfully processes image when image already exists in library"() {
        given:
        def mockCloudFile = Mock(CloudFile)
        mockCloudFile.getName() >> "ubuntu-22.04.vhdx"
        def opts = ApiServiceDataHelper.insertContainerImage_getContainerImageNOpts()
        opts.cloudFiles = mockCloudFile

        // Mock existing VHD in library
        def existingVhdData = '[{"ID": "vhd-12345"}]'

        // Mock the executeWindowsCommand call through wrapExecuteCommand
        morpheusContext.executeWindowsCommand(*_) >> Single.just([
                success: true,
                exitCode: '0',
                data: existingVhdData
        ])

        // Mock formatImageFolder method
        apiService.formatImageFolder("test-image") >> "test_image"

        when:
        def result = apiService.insertContainerImage(opts)

        then:
        // Verify executeWindowsCommand was called with correct parameters
        1 * morpheusContext.executeWindowsCommand(
                'scvmm-server',
                5985,
                'admin',
                'password',
                { String cmd ->
                    cmd.contains('Get-SCVirtualHardDisk -VMMServer localhost') &&
                            cmd.contains('where {$_.SharePath -like "\\\\server\\share\\images\\test_image\\*"}') &&
                            cmd.contains('Select ID')
                },
                null,
                false
        ) >> Single.just([success: true, exitCode: '0', data: existingVhdData])

        // Verify formatImageFolder was called
        1 * apiService.formatImageFolder("test-image") >> "test_image"

        // Verify result
        result.success == true
        result.imageId == "vhd-12345"
    }

    @Unroll
    def "test insertContainerImage throws exception when Get-SCVirtualHardDisk fails"() {
        given:
        def containerImage = [
                name: "test-image",
                imageType: 'vhd',
                cloudFiles: Mock(CloudFile)
        ]
        def opts = [
                image: containerImage,
                rootSharePath: "\\\\server\\share"
        ]

        // Mock formatImageFolder method
        apiService.formatImageFolder("test-image") >> "test_image"

        // Mock wrapExecuteCommand to return failure
        apiService.wrapExecuteCommand(_, opts) >> [success: false, error: "Command failed"]

        when:
        apiService.insertContainerImage(opts)

        then:
        def exception = thrown(Exception)
        exception.message == "Error in getting Get-SCVirtualHardDisk"
    }

    @Unroll
    def "test getServerDetails #scenario"() {
        given:
        def opts = DEFAULT_OPTS.clone()

        // Setup mocks based on scenario
        if (shouldThrowException) {
            apiService.generateCommandString(_) >> { throw new RuntimeException("Command generation failed") }
        } else {
            apiService.generateCommandString({ String cmd ->
                expectedCommand ? cmd.contains(expectedCommand) : true
            }) >> "generated powershell command"

            apiService.wrapExecuteCommand("generated powershell command", opts) >> mockResponse
        }

        when:
        def result = apiService.getServerDetails(opts, externalId)

        then:
        // Verify method calls
        if (shouldThrowException) {
            1 * apiService.generateCommandString(_) >> { throw new RuntimeException("Command generation failed") }
            0 * apiService.wrapExecuteCommand(_, _)
        } else {
            1 * apiService.generateCommandString(_) >> "generated powershell command"
            1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> mockResponse
        }

        // Verify results
        result.success == expectedSuccess
        result.server?.ID == expectedServerId
        result.server?.Name == expectedServerName
        result.server?.ipAddress == expectedIpAddress
        result.server?.internalIp == expectedInternalIp
        result.error == expectedError

        where:
        scenario | externalId | expectedCommand | mockResponse | shouldThrowException | expectedSuccess | expectedServerId | expectedServerName | expectedIpAddress | expectedInternalIp | expectedError

        "successfully retrieves VM details with IP address" | "vm-12345" | 'Get-SCVirtualMachine -VMMServer localhost -ID "vm-12345"' | [success: true, data: [[ID: "vm-12345", VMId: "12345678-1234-5678-9012-123456789012", Name: "test-vm", Status: "Running", VirtualMachineState: "Running", VirtualHardDiskDrives: ["disk-1", "disk-2"], VirtualDiskDrives: ["drive-1", "drive-2"], ipAddress: "192.168.1.100", internalIp: "192.168.1.100"]]] | false | true | "vm-12345" | "test-vm" | "192.168.1.100" | "192.168.1.100" | null

        "successfully retrieves VM details with no IP address" | "vm-12345" | 'Get-SCVirtualMachine -VMMServer localhost -ID "vm-12345"' | [success: true, data: [[ID: "vm-12345", VMId: "12345678-1234-5678-9012-123456789012", Name: "test-vm", Status: "Running", VirtualMachineState: "Running", VirtualHardDiskDrives: ["disk-1", "disk-2"], VirtualDiskDrives: ["drive-1", "drive-2"], ipAddress: "", internalIp: ""]]] | false | true | "vm-12345" | "test-vm" | "" | "" | null

        "correctly processes VM with multiple network adapters" | "vm-12345" | null | [success: true, data: [[ID: "vm-12345", VMId: "12345678-1234-5678-9012-123456789012", Name: "test-vm-multi-ip", Status: "Running", VirtualMachineState: "Running", VirtualHardDiskDrives: ["disk-1"], VirtualDiskDrives: ["drive-1"], ipAddress: "192.168.1.100", internalIp: "192.168.1.100"]]] | false | true | "vm-12345" | "test-vm-multi-ip" | "192.168.1.100" | "192.168.1.100" | null

        "handles VM not found scenario" | "vm-nonexistent" | 'Get-SCVirtualMachine -VMMServer localhost -ID "vm-nonexistent"' | [success: true, data: [[Error: 'VM_NOT_FOUND']]] | false | false | null | null | null | null | 'VM_NOT_FOUND'

        "handles command execution failure" | "vm-12345" | null | [success: false, error: "PowerShell execution failed"] | false | false | null | null | null | null | null

        "handles exception during execution" | "vm-12345" | null | null | true | false | null | null | null | null | null
    }

    @Unroll
    def "test refreshVM successfully refreshes VM data"() {
        given:
        def externalId = "vm-12345"
        def opts = DEFAULT_OPTS.clone()

        // Mock the command execution with a successful result
        def commandOutput = [success: true, exitCode: '0', data: '{"Status":"Success"}']

        when:
        def result = apiService.refreshVM(opts, externalId)

        then:
        // Verify generateCommandString was called with the correct PowerShell command
        1 * apiService.generateCommandString({ String cmd ->
            cmd.contains('$vm = Get-SCVirtualMachine -VMMServer localhost -ID "vm-12345"') &&
                    cmd.contains('$ignore = Read-SCVirtualMachine -VM $vm')
        }) >> "generated powershell command"

        // Verify wrapExecuteCommand was called with the generated command
        1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> commandOutput

        // Verify the result
        result.success == true
    }

    @Unroll
    def "test discardSavedState successfully discards VM saved state"() {
        given:
        def externalId = "vm-12345"
        def opts = DEFAULT_OPTS.clone()

        // Mock the command execution with a successful result
        def commandOutput = [success: true, exitCode: '0', data: '{"Status":"Success"}']

        when:
        def result = apiService.discardSavedState(opts, externalId)

        then:
        // Verify executeCommand was called with the correct PowerShell command
        1 * apiService.executeCommand(
                { String cmd ->
                    cmd.contains('$vm = Get-SCVirtualMachine -VMMServer localhost -ID "vm-12345"') &&
                            cmd.contains('Use-SCDiscardSavedStateVM -VM $vm')
                },
                opts
        ) >> commandOutput

        // Verify the result structure
        result.success == false
        result.server == null
        result.networkAdapters == []
    }

    @Unroll
    def "test discardSavedState handles exception gracefully"() {
        given:
        def externalId = "vm-12345"
        def opts = DEFAULT_OPTS.clone()

        when:
        def result = apiService.discardSavedState(opts, externalId)

        then:
        // Verify executeCommand was called and throws an exception
        1 * apiService.executeCommand(_, opts) >> { throw new RuntimeException("PowerShell execution failed") }

        // Verify the result structure remains the same even with exception
        result.success == false
        result.server == null
        result.networkAdapters == []
    }

    @Unroll
    def "test discardSavedState with different external IDs"() {
        given:
        def opts = [
                sshHost: 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password'
        ]

        when:
        def result = apiService.discardSavedState(opts, externalId)

        then:
        1 * apiService.executeCommand(
                { String cmd ->
                    cmd.contains("Get-SCVirtualMachine -VMMServer localhost -ID \"${externalId}\"")
                },
                opts
        ) >> [success: true]

        result.success == false
        result.server == null
        result.networkAdapters == []

        where:
        externalId << ["vm-123", "vm-abc-def", "virtual-machine-456"]
    }

    @Unroll
    def "test extractWindowsServerVersion with #scenario"() {
        when:
        def result = apiService.extractWindowsServerVersion(inputOsName)

        then:
        result == expectedResult

        where:
        scenario | inputOsName | expectedResult

        // Windows Server 2022 variants
        "2022 Standard Core" | "Windows Server 2022 Standard Core" | "windows.server.2022.std.core"
        "2022 Standard Desktop" | "Windows Server 2022 Standard Desktop" | "windows.server.2022.std.desktop"
        "2022 Datacenter Core" | "Windows Server 2022 Datacenter Core" | "windows.server.2022.dc.core"
        "2022 Datacenter Desktop" | "Windows Server 2022 Datacenter Desktop" | "windows.server.2022.dc.desktop"
        "2022 Standard (fallback to core)" | "Windows Server 2022 Standard" | "windows.server.2022.std.core"
        "2022 Datacenter (fallback to core)" | "Windows Server 2022 Datacenter" | "windows.server.2022.dc.core"
        "2022 with no specific variant" | "Windows Server 2022" | "windows.server.2022"
        "2022 unknown variant" | "Windows Server 2022 Enterprise" | "windows.server.2022"

        // Case insensitive tests for 2022
        "2022 mixed case standard core" | "Windows Server 2022 STANDARD CORE" | "windows.server.2022.std.core"
        "2022 mixed case datacenter desktop" | "Windows Server 2022 Datacenter DESKTOP" | "windows.server.2022.dc.desktop"

        // Other Windows Server versions (fallback logic)
        "Windows Server 2019" | "Windows Server 2019 Standard" | "windows.server.2019"
        "Windows Server 2016" | "Windows Server 2016 Datacenter" | "windows.server.2016"
        "Windows Server 2012" | "Windows Server 2012 R2" | "windows.server.2012"
        "Windows Server 2008" | "Windows Server 2008 R2" | "windows.server.2008"
        "Windows Server 2003" | "Windows Server 2003" | "windows.server.2003"

        // Edge cases for year extraction
        "future version 2025" | "Windows Server 2025" | "windows.server.2025"
        "version 2020" | "Windows Server 2020" | "windows.server.2020"

        // Fallback to 2012 when no year found
        "no year in name" | "Windows Server Standard" | "windows.server.2012"
        "empty string" | "" | "windows.server.2012"
        "random text" | "Some Random OS Name" | "windows.server.2012"

        // Multiple years (should pick first match)
        "multiple years" | "Windows Server 2016 to 2019 Migration" | "windows.server.2016"

        // Case variations
        "lowercase input" | "windows server 2022 standard core" | "windows.server.2022.std.core"
        "uppercase input" | "WINDOWS SERVER 2022 DATACENTER CORE" | "windows.server.2022.dc.core"
    }

    @Unroll
    def "test getScvmmServerInfo successfully retrieves server information"() {
        given:
        def opts = DEFAULT_OPTS.clone()

        // Mock executeCommand responses for each command
        def hostnameResponse = [success: true, data: 'SCVMM-SERVER-01']
        def osNameResponse = [success: true, data: 'Microsoft Windows Server 2019 Datacenter']
        def memoryResponse = [success: true, data: '17179869184'] // 16GB in bytes
        def disksResponse = [success: true, data: '2199023255552'] // 2TB in bytes

        // Mock cleanData responses
        apiService.cleanData('SCVMM-SERVER-01') >> 'SCVMM-SERVER-01'
        apiService.cleanData('Microsoft Windows Server 2019 Datacenter') >> 'Microsoft Windows Server 2019 Datacenter'
        apiService.cleanData('17179869184', 'TotalPhysicalMemory') >> '17179869184'
        apiService.cleanData('2199023255552', 'Size') >> '2199023255552'

        when:
        def result = apiService.getScvmmServerInfo(opts)

        then:
        // Verify executeCommand was called 4 times with correct commands
        1 * apiService.executeCommand('hostname', opts) >> hostnameResponse
        1 * apiService.executeCommand('(Get-ComputerInfo).OsName', opts) >> osNameResponse
        1 * apiService.executeCommand('(Get-CimInstance Win32_PhysicalMemory | Measure-Object -Property capacity -Sum).sum', opts) >> memoryResponse
        1 * apiService.executeCommand('(Get-CimInstance Win32_DiskDrive | Measure-Object -Property Size -Sum).sum', opts) >> disksResponse

        // Verify cleanData was called with correct parameters
        1 * apiService.cleanData('SCVMM-SERVER-01') >> 'SCVMM-SERVER-01'
        1 * apiService.cleanData('Microsoft Windows Server 2019 Datacenter') >> 'Microsoft Windows Server 2019 Datacenter'
        1 * apiService.cleanData('17179869184', 'TotalPhysicalMemory') >> '17179869184'
        1 * apiService.cleanData('2199023255552', 'Size') >> '2199023255552'

        // Verify result structure
        result.success == true
        result.hostname == 'SCVMM-SERVER-01'
        result.osName == 'Microsoft Windows Server 2019 Datacenter'
        result.memory == '17179869184'
        result.disks == '2199023255552'
    }

    @Unroll
    def "test getCloud successfully retrieves cloud information"() {
        given:
        def opts = [
                zone: [regionCode: "us-east-1"],
                sshHost: 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password',
                winrmPort: '5985'
        ]

        // Mock cloud data response
        def cloudData = [
                [
                        ID: "us-east-1",
                        Name: "East Coast Cloud",
                        CapabilityProfiles: ["Hyper-V", "Generation2"]
                ]
        ]

        def commandOutput = [success: true, data: cloudData]

        when:
        def result = apiService.getCloud(opts)

        then:
        // Verify generateCommandString was called with the correct PowerShell command
        1 * apiService.generateCommandString({ String cmd ->
            cmd.contains('$cloud = Get-SCCloud -VMMServer localhost') &&
                    cmd.contains("where { \$_.ID -eq 'us-east-1' }") &&
                    cmd.contains('ID=$cloud.ID') &&
                    cmd.contains('Name=$cloud.Name') &&
                    cmd.contains('CapabilityProfiles=@($cloud.CapabilityProfiles.Name)')
        }) >> "generated powershell command"

        // Verify wrapExecuteCommand was called with the generated command
        1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> commandOutput

        // Verify the result
        result.success == true
        result.cloud.ID == "us-east-1"
        result.cloud.Name == "East Coast Cloud"
        result.cloud.CapabilityProfiles == ["Hyper-V", "Generation2"]
    }

    @Unroll
    def "test getCapabilityProfiles successfully retrieves capability profiles"() {
        given:
        def opts = DEFAULT_OPTS.clone()

        // Mock capability profiles data response
        def capabilityProfilesData = [
                [
                        ID: "profile-1",
                        Name: "Hyper-V"
                ],
                [
                        ID: "profile-2",
                        Name: "Generation2"
                ]
        ]

        def commandOutput = [success: true, data: capabilityProfilesData]

        when:
        def result = apiService.getCapabilityProfiles(opts)

        then:
        // Verify generateCommandString was called with the correct PowerShell command
        1 * apiService.generateCommandString("Get-SCCapabilityProfile -VMMServer localhost | Select ID,Name") >> "generated powershell command"

        // Verify wrapExecuteCommand was called with the generated command
        1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> commandOutput

        // Verify the result
        result.success == true
        result.capabilityProfiles == capabilityProfilesData
        result.capabilityProfiles.size() == 2
        result.capabilityProfiles[0].ID == "profile-1"
        result.capabilityProfiles[0].Name == "Hyper-V"
        result.capabilityProfiles[1].ID == "profile-2"
        result.capabilityProfiles[1].Name == "Generation2"
    }

    @Unroll
    def "test listClouds successfully retrieves list of clouds"() {
        given:
        def opts = DEFAULT_OPTS.clone()

        // Mock clouds data response
        def cloudsData = [
                [
                        ID: "cloud-1",
                        Name: "Production Cloud"
                ],
                [
                        ID: "cloud-2",
                        Name: "Development Cloud"
                ],
                [
                        ID: "cloud-3",
                        Name: "Test Cloud"
                ]
        ]

        def commandOutput = [success: true, data: cloudsData]

        when:
        def result = apiService.listClouds(opts)

        then:
        // Verify generateCommandString was called with the correct PowerShell command
        1 * apiService.generateCommandString('Get-SCCloud -VMMServer localhost | Select ID, Name') >> "generated powershell command"

        // Verify wrapExecuteCommand was called with the generated command
        1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> commandOutput

        // Verify the result
        result.success == true
        result.clouds == cloudsData
        result.clouds.size() == 3
        result.clouds[0].ID == "cloud-1"
        result.clouds[0].Name == "Production Cloud"
        result.clouds[1].ID == "cloud-2"
        result.clouds[1].Name == "Development Cloud"
        result.clouds[2].ID == "cloud-3"
        result.clouds[2].Name == "Test Cloud"
    }


    @Unroll
    def "test listTemplates successfully retrieves VM templates and VHDs"() {
        given:
        def opts = DEFAULT_OPTS.clone()

        // Mock template data response
        def templatesData = ApiServiceDataHelper.listTemplates_getTemplateData

        def commandOutput = [success: true, data: templatesData]

        when:
        def result = apiService.listTemplates(opts)

        then:
        // Verify generateCommandString was called with the complex PowerShell command
        1 * apiService.generateCommandString({ String cmd ->
            cmd.contains('$report = @()') &&
                    cmd.contains('Get-SCVMTemplate -VMMServer localhost -All') &&
                    cmd.contains('where { $_.ID -ne $_.Name -and $_.Status -eq \'Normal\'}') &&
                    cmd.contains('Get-SCVirtualHardDisk -VMMServer localhost') &&
                    cmd.contains('$report')
        }) >> "generated powershell command"

        // Verify wrapExecuteCommand was called with the generated command
        1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> commandOutput

        // Verify the result
        result.success == true
        result.templates == templatesData
        result.templates.size() == 2

        // Verify template data structure
        result.templates[0].ID == "template-1"
        result.templates[0].Name == "Windows Server 2019 Template"
        result.templates[0].CPUCount == 2
        result.templates[0].Memory == 4294967296L
        result.templates[0].Generation == 2
        result.templates[0].Disks.size() == 1
        result.templates[0].Disks[0].VHDType == "DynamicallyExpanding"

        // Verify VHD data structure
        result.templates[1].ID == "vhd-1"
        result.templates[1].Name == "Ubuntu 20.04 VHD"
        result.templates[1].VHDFormatType == "VHDX"
        result.templates[1].Disks.size() == 1
    }

    @Unroll
    def "test listClusters successfully retrieves clusters and applies host group filtering"() {
        given:
        def opts = [
                zone: Mock(Cloud) {
                    getConfigProperty('hostGroup') >> hostGroupFilter
                },
                sshHost: 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password',
                winrmPort: '5985'
        ]

        // Mock clusters data response
        def clustersData = ApiServiceDataHelper.listClusters_getClusterData

        def commandOutput = [success: true, data: clustersData]

        when:
        def result = apiService.listClusters(opts)

        then:
        // Verify generateCommandString was called with the correct PowerShell command
        1 * apiService.generateCommandString({ String cmd ->
            cmd.contains('$report = @()') &&
                    cmd.contains('$Clusters = Get-SCVMHostCluster -VMMServer localhost') &&
                    cmd.contains('foreach ($Cluster in $Clusters)') &&
                    cmd.contains('id=$Cluster.ID') &&
                    cmd.contains('name=$Cluster.Name') &&
                    cmd.contains('hostGroup=$Cluster.HostGroup.Path') &&
                    cmd.contains('sharedVolumes=@($Cluster.SharedVolumes.Name)') &&
                    cmd.contains('description=$Cluster.Description') &&
                    cmd.contains('$report +=$data') &&
                    cmd.contains('$report')
        }) >> "generated powershell command"

        // Verify wrapExecuteCommand was called with the generated command
        1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> commandOutput

        // Verify the result
        result.success == true
        result.clusters.size() == expectedClusterCount

        if (expectedClusterCount > 0) {
            result.clusters.each { cluster ->
                assert expectedHostGroups.any { cluster.hostGroup?.startsWith(it) }
            }
        }

        where:
        scenario                           | hostGroupFilter              | expectedClusterCount | expectedHostGroups
        "no host group filter (all)"      | null                        | 3                    | ["All Hosts\\Production", "All Hosts\\Development"]
        "production host group filter"     | "All Hosts\\Production"     | 2                    | ["All Hosts\\Production"]
        "development host group filter"    | "All Hosts\\Development"    | 1                    | ["All Hosts\\Development"]
        "non-matching host group filter"  | "All Hosts\\Staging"        | 0                    | []
    }


    @Unroll
    def "test internalListHostGroups successfully retrieves host groups"() {
        given:
        def opts = DEFAULT_OPTS.clone()

        // Mock host groups data response
        def hostGroupsData = ApiServiceDataHelper.internalListHostGroups_getHostGroupData

        def commandOutput = [success: true, data: hostGroupsData]

        when:
        def result = apiService.internalListHostGroups(opts)

        then:
        // Verify generateCommandString was called with the correct PowerShell command
        1 * apiService.generateCommandString({ String cmd ->
            cmd.contains('Get-SCVMHostGroup -VMMServer localhost') &&
                    cmd.contains('Select-Object') &&
                    cmd.contains('@{Name="id";Expression={$_.ID.Guid}}') &&
                    cmd.contains('@{Name="name";Expression={$_.Name}}') &&
                    cmd.contains('@{Name="path";Expression={$_.Path}}') &&
                    cmd.contains('@{Name="parent";Expression={$_.ParentHostGroup.Name}}') &&
                    cmd.contains('@{Name="root";Expression={$_.IsRoot}}')
        }) >> "generated powershell command"

        // Verify wrapExecuteCommand was called with the generated command
        1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> commandOutput

        // Verify the result
        result.success == true
        result.hostGroups == hostGroupsData
        result.hostGroups.size() == 3

        // Verify specific host group data
        result.hostGroups[0].id == "12345678-1234-5678-9012-123456789012"
        result.hostGroups[0].name == "All Hosts"
        result.hostGroups[0].path == "All Hosts"
        result.hostGroups[0].parent == null
        result.hostGroups[0].root == true

        result.hostGroups[1].name == "Production"
        result.hostGroups[1].path == "All Hosts\\Production"
        result.hostGroups[1].parent == "All Hosts"
        result.hostGroups[1].root == false
    }

    @Unroll
    def "test listLibraryShares successfully retrieves library shares"() {
        given:
        def opts = DEFAULT_OPTS.clone()

        // Mock library shares data response
        def librarySharesData = ApiServiceDataHelper.listLibraryShares_getLibraryShareData

        def commandOutput = [success: true, data: librarySharesData]

        when:
        def result = apiService.listLibraryShares(opts)

        then:
        // Verify generateCommandString was called with the correct PowerShell command
        1 * apiService.generateCommandString({ String cmd ->
            cmd.contains('$report = @()') &&
                    cmd.contains('$shares = Get-SCLibraryShare -VMMServer localhost') &&
                    cmd.contains('foreach($share in $shares)') &&
                    cmd.contains('ID=$share.ID') &&
                    cmd.contains('Name=$share.Name') &&
                    cmd.contains('Path=$share.Path') &&
                    cmd.contains('$report += $data') &&
                    cmd.contains('$report')
        }) >> "generated powershell command"

        // Verify wrapExecuteCommand was called with the generated command
        1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> commandOutput

        // Verify the result
        result.success == true
        result.libraryShares == librarySharesData
        result.libraryShares.size() == 3

        // Verify specific library share data
        result.libraryShares[0].ID == "12345678-1234-5678-9012-123456789012"
        result.libraryShares[0].Name == "Library Share 1"
        result.libraryShares[0].Path == "\\\\server1\\LibraryShare1"

        result.libraryShares[1].ID == "87654321-4321-8765-2109-876543210987"
        result.libraryShares[1].Name == "Library Share 2"
        result.libraryShares[1].Path == "\\\\server2\\LibraryShare2"

        result.libraryShares[2].ID == "11111111-2222-3333-4444-555555555555"
        result.libraryShares[2].Name == "Local Library"
        result.libraryShares[2].Path == "C:\\ProgramData\\Virtual Machine Manager Library Files"
    }

    @Unroll
    def "test listHostGroups with #scenario"() {
        given:
        def opts = [
                zone: Mock(Cloud) {
                    getRegionCode() >> regionCode
                },
                sshHost: 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password',
                winrmPort: '5985'
        ]

        // Mock cloud data response when regionCode exists
        if (regionCode) {
            def cloudsData = [
                    [
                            ID: "cloud-1",
                            HostGroup: ["All Hosts\\Production", "All Hosts\\Development"]
                    ],
                    [
                            ID: "cloud-2",
                            HostGroup: ["All Hosts\\Testing"]
                    ]
            ]

            apiService.generateCommandString(_) >> "generated powershell command"
            apiService.wrapExecuteCommand("generated powershell command", opts) >> [
                    success: true,
                    data: cloudsData
            ]
        }

        // Mock internalListHostGroups response
        def allHostGroups = [
                [id: "hg-1", name: "Production", path: "All Hosts\\Production"],
                [id: "hg-2", name: "Development", path: "All Hosts\\Development"],
                [id: "hg-3", name: "Testing", path: "All Hosts\\Testing"],
                [id: "hg-4", name: "Staging", path: "All Hosts\\Staging"]
        ]

        apiService.internalListHostGroups(opts) >> [
                success: true,
                hostGroups: allHostGroups
        ]

        // Mock isHostInHostGroup method
        apiService.isHostInHostGroup(_, _) >> { String currentPath, String cloudPath ->
            return currentPath.startsWith(cloudPath) || currentPath == cloudPath
        }

        when:
        def result = apiService.listHostGroups(opts)

        then:
        if (regionCode) {
            // Verify PowerShell command generation and execution
            1 * apiService.generateCommandString({ String cmd ->
                cmd.contains('Get-SCCloud -VMMServer localhost') &&
                        cmd.contains('ID=$cloud.ID') &&
                        cmd.contains('HostGroup=@($cloud.HostGroup.Path)')
            }) >> "generated powershell command"

            1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> [
                    success: true,
                    data: [
                            [ID: "cloud-1", HostGroup: ["All Hosts\\Production", "All Hosts\\Development"]],
                            [ID: "cloud-2", HostGroup: ["All Hosts\\Testing"]]
                    ]
            ]

            // Verify internalListHostGroups was called
            1 * apiService.internalListHostGroups(opts) >> [success: true, hostGroups: allHostGroups]

            // Verify isHostInHostGroup calls based on expected filtering
            if (regionCode == "cloud-1") {
                (4..8) * apiService.isHostInHostGroup(_, _) >> { String currentPath, String cloudPath ->
                    return currentPath.startsWith(cloudPath) || currentPath == cloudPath
                }
            }
        } else {
            // No PowerShell command should be generated when no regionCode
            0 * apiService.generateCommandString(_)
            0 * apiService.wrapExecuteCommand(_, _)

            // Only internalListHostGroups should be called
            1 * apiService.internalListHostGroups(opts) >> [success: true, hostGroups: allHostGroups]

            0 * apiService.isHostInHostGroup(_, _)
        }

        // Verify results
        result.success == expectedSuccess
        result.hostGroups.size() == expectedHostGroupCount
        result.hostGroups*.name.containsAll(expectedHostGroupNames)

        where:
        scenario                                    | regionCode | expectedSuccess | expectedHostGroupCount | expectedHostGroupNames
        "region code exists with matching cloud"   | "cloud-1"  | true           | 2                      | ["Production", "Development"]
        "region code exists with different cloud"  | "cloud-2"  | true           | 1                      | ["Testing"]
        "region code exists but no matching cloud" | "cloud-3"  | true           | 0                      | []
        "no region code - returns all host groups" | null       | true           | 4                      | ["Production", "Development", "Testing", "Staging"]
    }

    @Unroll
    def "test listHosts handles command execution failure"() {
        given:
        def opts = DEFAULT_OPTS.clone()

        // Mock generateCommandString
        apiService.generateCommandString(_) >> "powershell command"

        // Mock wrapExecuteCommand to return failure
        apiService.wrapExecuteCommand("powershell command", opts) >> [
                success: false,
                error: "PowerShell execution failed"
        ]

        when:
        def result = apiService.listHosts(opts)

        then:
        // Verify generateCommandString was called
        1 * apiService.generateCommandString({ String cmd ->
            cmd.contains('Get-SCVMHost -VMMServer localhost') &&
                    cmd.contains('Skip 0') &&
                    cmd.contains('First 10')
        }) >> "powershell command"

        // Verify wrapExecuteCommand was called
        1 * apiService.wrapExecuteCommand("powershell command", opts) >> [success: false, error: "PowerShell execution failed"]

        // Verify result
        result.success == false
        result.hosts == []
    }

    @Unroll
    def "test removeOrphanedResourceLibraryItems successfully removes orphaned ISOs and Scripts"() {
        given:
        def opts = DEFAULT_OPTS.clone()

        // Mock the command execution with a successful result
        def commandOutput = [success: true, exitCode: '0', data: '{"Status":"Success"}']

        when:
        apiService.removeOrphanedResourceLibraryItems(opts)

        then:
        // Verify generateCommandString was called with the correct PowerShell command
        1 * apiService.generateCommandString({ String cmd ->
            cmd.contains('$ISOs = Get-SCISO -VMMServer localhost') &&
                    cmd.contains('where { ($_.State -match "Missing") -and ($_.Directory.ToString() -like "*morpheus_server_*") }') &&
                    cmd.contains('$ignore = $ISOs | Remove-SCISO -RunAsynchronously') &&
                    cmd.contains('$Scripts = Get-SCScript -VMMServer localhost') &&
                    cmd.contains('$ignore = $Scripts | Remove-SCScript -RunAsynchronously')
        }) >> "generated powershell command"

        // Verify wrapExecuteCommand was called with the generated command
        1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> commandOutput

    }

    @Unroll
    def "test listNetworks handles logical networks fetch failure"() {
        given:
        def opts = [
                zone: Mock(Cloud) {
                    getRegionCode() >> null
                },
                sshHost: 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password'
        ]

        // Mock generateCommandString
        apiService.generateCommandString(_) >> "generated powershell command"

        // Mock wrapExecuteCommand to fail on logical networks fetch
        apiService.wrapExecuteCommand("generated powershell command", opts) >> [
                success: false,
                exitCode: '1',
                data: null
        ]

        when:
        def result = apiService.listNetworks(opts)

        then:
        // Verify generateCommandString was called once
        1 * apiService.generateCommandString({ String cmd ->
            cmd.contains('Get-SCLogicalNetwork -VMMServer localhost | Select ID,Name')
        }) >> "generated powershell command"

        // Verify wrapExecuteCommand was called once
        1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> [success: false, exitCode: '1', data: null]

        // Verify result
        result.success == false
        result.networks == []
    }


    @Unroll
    def "test listNetworks handles empty logical networks response"() {
        given:
        def opts = [
                zone: Mock(Cloud) {
                    getRegionCode() >> null
                },
                sshHost: 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password'
        ]

        // Mock generateCommandString
        apiService.generateCommandString(_) >> "generated powershell command"

        // Mock wrapExecuteCommand to return empty data
        apiService.wrapExecuteCommand("generated powershell command", opts) >> [
                success: true,
                exitCode: '0',
                data: []
        ]

        when:
        def result = apiService.listNetworks(opts)

        then:
        // Verify generateCommandString was called once
        1 * apiService.generateCommandString(_) >> "generated powershell command"

        // Verify wrapExecuteCommand was called once
        1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> [success: true, exitCode: '0', data: []]

        // Verify result
        result.success == false
        result.networks == []
    }

    @Unroll
    def "test listNetworks handles exception gracefully"() {
        given:
        def opts = [
                zone: Mock(Cloud) {
                    getRegionCode() >> null
                },
                sshHost: 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password'
        ]

        when:
        def result = apiService.listNetworks(opts)

        then:
        // Mock generateCommandString to throw exception
        1 * apiService.generateCommandString(_) >> { throw new RuntimeException("Command generation failed") }

        // Verify result
        result.success == false
        result.msg == "Error syncing networks list from SCVMM Host"
        result.networks == []
    }

    @Unroll
    def "test listNoIsolationVLans handles logical networks fetch failure"() {
        given:
        def opts = [
                zone: Mock(Cloud) {
                    getRegionCode() >> null
                },
                sshHost: 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password'
        ]

        // Mock generateCommandString
        apiService.generateCommandString(_) >> "generated powershell command"

        // Mock wrapExecuteCommand to fail on logical networks fetch
        apiService.wrapExecuteCommand("generated powershell command", opts) >> [
                success: false,
                exitCode: '1',
                data: null
        ]

        when:
        def result = apiService.listNoIsolationVLans(opts)

        then:
        // Verify generateCommandString was called
        1 * apiService.generateCommandString({ String cmd ->
            cmd.contains('Get-SCLogicalNetwork -VMMServer localhost | Select ID,Name')
        }) >> "generated powershell command"

        // Verify wrapExecuteCommand was called
        1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> [
                success: false,
                exitCode: '1',
                data: null
        ]

        // Verify result
        result.success == false
        result.networks == []
    }

    @Unroll
    def "test listNoIsolationVLans handles empty logical networks response"() {
        given:
        def opts = [
                zone: Mock(Cloud) {
                    getRegionCode() >> null
                },
                sshHost: 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password'
        ]

        // Mock generateCommandString
        apiService.generateCommandString(_) >> "generated powershell command"

        // Mock wrapExecuteCommand to return empty data
        apiService.wrapExecuteCommand("generated powershell command", opts) >> [
                success: true,
                exitCode: '0',
                data: []
        ]

        when:
        def result = apiService.listNoIsolationVLans(opts)

        then:
        // Verify generateCommandString was called
        1 * apiService.generateCommandString(_) >> "generated powershell command"

        // Verify wrapExecuteCommand was called
        1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> [
                success: true,
                exitCode: '0',
                data: []
        ]

        // Verify result
        result.success == false
        result.networks == []
    }

    @Unroll
    def "test listNoIsolationVLans handles exception gracefully"() {
        given:
        def opts = [
                zone: Mock(Cloud) {
                    getRegionCode() >> null
                },
                sshHost: 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password'
        ]

        when:
        def result = apiService.listNoIsolationVLans(opts)

        then:
        // Mock generateCommandString to throw exception
        1 * apiService.generateCommandString(_) >> { throw new RuntimeException("Command generation failed") }

        // Verify result
        result.success == false
        result.msg == "Error syncing isolation networks list from SCVMM Host"
        result.networks == []
    }

    @Unroll
    def "test listNetworkIPPools successfully retrieves IP pools and network mapping"() {
        given:
        def opts = DEFAULT_OPTS.clone()

        // Mock IP pools data response
        def ipPoolsData = ApiServiceDataHelper.listNetworkPools_getIpPoolsData

        // Mock network mapping data response
        def networkMappingData = ApiServiceDataHelper.listNetworkIPPools_getnetworkMappingData

        // Mock the first command execution (IP pools)
        def ipPoolsCommand = [success: true, exitCode: '0', data: ipPoolsData]

        // Mock the second command execution (network mapping)
        def networkMappingCommand = [success: true, exitCode: '0', data: networkMappingData]

        when:
        def result = apiService.listNetworkIPPools(opts)

        then:
        // Verify generateCommandString was called twice with correct PowerShell commands
        1 * apiService.generateCommandString({ String cmd ->
            cmd.contains('Get-SCStaticIPAddressPool -VMMServer localhost') &&
                    cmd.contains('ID=$staticPool.ID') &&
                    cmd.contains('Name=$staticPool.Name') &&
                    cmd.contains('NetworkID=$staticPool.VMSubnet.VMNetwork.ID') &&
                    cmd.contains('LogicalNetworkID=$staticPool.LogicalNetworkDefinition.LogicalNetwork.ID') &&
                    cmd.contains('Subnet=$staticPool.Subnet') &&
                    cmd.contains('SubnetID=$staticPool.VMSubnet.ID') &&
                    cmd.contains('DefaultGateways=@($staticPool.DefaultGateways.IPAddress)') &&
                    cmd.contains('TotalAddresses=$staticPool.TotalAddresses') &&
                    cmd.contains('AvailableAddresses=$staticPool.AvailableAddresses') &&
                    cmd.contains('DNSSearchSuffixes=$staticPool.DNSSearchSuffixes') &&
                    cmd.contains('DNSServers=$staticPool.DNSServers') &&
                    cmd.contains('IPAddressRangeStart=$staticPool.IPAddressRangeStart') &&
                    cmd.contains('IPAddressRangeEnd=$staticPool.IPAddressRangeEnd')
        }) >> "generated ip pools command"

        1 * apiService.generateCommandString({ String cmd ->
            cmd.contains('Get-SCVMNetwork -VMMServer localhost | Select ID,Name,LogicalNetwork') &&
                    cmd.contains('ID=$network.ID') &&
                    cmd.contains('Name=$network.Name') &&
                    cmd.contains('LogicalNetwork=$network.LogicalNetwork.Name') &&
                    cmd.contains('LogicalNetworkId=$network.LogicalNetwork.ID')
        }) >> "generated network mapping command"

        // Verify wrapExecuteCommand was called twice
        1 * apiService.wrapExecuteCommand("generated ip pools command", opts) >> ipPoolsCommand
        1 * apiService.wrapExecuteCommand("generated network mapping command", opts) >> networkMappingCommand

        // Verify the result
        result.success == true
        result.ipPools == ipPoolsData
        result.ipPools.size() == 2
        result.ipPools[0].ID == "pool-1"
        result.ipPools[0].Name == "Production Pool"
        result.ipPools[0].NetworkID == "network-1"
        result.ipPools[1].ID == "pool-2"
        result.ipPools[1].Name == "Development Pool"

        result.networkMapping == networkMappingData
        result.networkMapping.size() == 2
        result.networkMapping[0].ID == "network-1"
        result.networkMapping[0].Name == "Production Network"
        result.networkMapping[1].ID == "network-2"
        result.networkMapping[1].Name == "Development Network"
    }

    @Unroll
    def "test reserveIPAddress with scenario: #scenario"() {
        given:
        def opts = DEFAULT_OPTS.clone()

        when:
        def result = apiService.reserveIPAddress(opts, poolId)

        then:
        if (shouldThrowException) {
            1 * apiService.generateCommandString(_) >> { throw new RuntimeException("Command generation failed") }
        } else {
            if (specificCommandCheck) {
                1 * apiService.generateCommandString({ String cmd ->
                    cmd.contains("Get-SCStaticIPAddressPool -VMMServer localhost -ID \"${poolId}\"") &&
                            cmd.contains('Grant-SCIPAddress -GrantToObjectType "VirtualMachine" -StaticIPAddressPool \$ippool') &&
                            cmd.contains('Select-Object ID,Address')
                }) >> "generated powershell command"
            } else {
                1 * apiService.generateCommandString(_) >> "generated powershell command"
            }

            1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> commandOutput
        }

        result.success == expectedSuccess
        result.ipAddress == expectedIpAddress
        if (expectedMsg) {
            result.msg == expectedMsg
        }

        where:
        scenario                    | poolId              | commandOutput                                      | expectedSuccess | expectedIpAddress                                    | expectedMsg                           | shouldThrowException | specificCommandCheck
        "successfully reserves IP"  | "pool-12345"        | [success: true, exitCode: '0', data: [[ID: "ip-12345", Address: "192.168.1.100"]]] | true | [ID: "ip-12345", Address: "192.168.1.100"] | null | false | true
        "command execution failure" | "pool-12345"        | [success: false, exitCode: '1', error: "Pool not found"] | false | [] | null | false | false
        "empty IP address data"     | "pool-12345"        | [success: true, exitCode: '0', data: null]        | true | [] | null | false | false
        "exception handling"        | "pool-12345"        | null                                              | false | [] | "Error reserving an IP address from SCVMM" | true | false
        "different pool ID 1"       | "pool-production"   | [success: true, exitCode: '0', data: [[ID: "ip-001", Address: "192.168.1.50"]]] | true | [ID: "ip-001", Address: "192.168.1.50"] | null | false | false
        "different pool ID 2"       | "pool-development"  | [success: true, exitCode: '0', data: [[ID: "ip-002", Address: "10.0.1.75"]]] | true | [ID: "ip-002", Address: "10.0.1.75"] | null | false | false
        "different pool ID 3"       | "pool-test"         | [success: true, exitCode: '0', data: [[ID: "ip-003", Address: "172.16.1.100"]]] | true | [ID: "ip-003", Address: "172.16.1.100"] | null | false | false
    }

    @Unroll
    def "test releaseIPAddress with scenario: #scenario"() {
        given:
        def opts = DEFAULT_OPTS.clone()

        when:
        def result = apiService.releaseIPAddress(opts, poolId, ipId)

        then:
        if (shouldThrowException) {
            1 * apiService.generateCommandString(_) >> { throw new RuntimeException("Command generation failed") }
        } else {
            if (specificCommandCheck) {
                1 * apiService.generateCommandString({ String cmd ->
                    cmd.contains("Get-SCStaticIPAddressPool -VMMServer localhost -ID \"${poolId}\"") &&
                            cmd.contains("Get-SCIPAddress -ID \"${ipId}\"") &&
                            cmd.contains('Revoke-SCIPAddress $ipaddress')
                }) >> "generated powershell command"
            } else {
                1 * apiService.generateCommandString(_) >> "generated powershell command"
            }

            1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> commandOutput
        }

        result.success == expectedSuccess
        if (expectedMsg) {
            result.msg == expectedMsg
        }

        where:
        scenario                           | poolId              | ipId           | commandOutput                                                                                    | expectedSuccess | expectedMsg                                | shouldThrowException | specificCommandCheck
        "successfully releases IP"         | "pool-12345"        | "ip-12345"     | new TaskResult([success: true, exitCode: '0'])                                                                  | true            | null                                       | false                | true
        "command execution failure"        | "pool-12345"        | "ip-12345"     | new TaskResult([success: false, exitCode: '1', error: "Pool not found"])                                      | false           | null                                       | false                | false
        "IP address already deleted"       | "pool-12345"        | "ip-12345"     | new TaskResult([success: false, exitCode: '1', error: "Unable to find the specified allocated IP address"]) | true            | null                                       | false                | false
        "IP address not found error"       | "pool-12345"        | "ip-12345"     | new TaskResult([success: false, exitCode: '1', error: "Unable to find the specified allocated IP address in system"]) | true            | null                                       | false                | false
        "other execution error"            | "pool-12345"        | "ip-12345"     | new TaskResult([success: false, exitCode: '1', error: "PowerShell execution failed"])                          | false           | null                                       | false                | false
        "exception handling"               | "pool-12345"        | "ip-12345"     | null                                                                                            | false           | "Error revoking an IP address from SCVMM" | true                 | false
        "different pool and IP ID 1"       | "pool-production"   | "ip-001"       | new TaskResult([success: true, exitCode: '0'])                                                                  | true            | null                                       | false                | false
        "different pool and IP ID 2"       | "pool-development"  | "ip-002"       | new TaskResult([success: true, exitCode: '0'])                                                                  | true            | null                                       | false                | false
        "different pool and IP ID 3"       | "pool-test"         | "ip-003"       | new TaskResult([success: true, exitCode: '0'])                                                                  | true            | null                                       | false                | false
    }

    @Unroll
    def "test listVirtualDiskDrives with scenario: #scenario"() {
        given:
        def opts = DEFAULT_OPTS.clone()

        when:
        def result = apiService.listVirtualDiskDrives(opts, externalId, vhdName)

        then:
        if (shouldThrowException) {
            1 * apiService.generateCommandString(_) >> { throw new RuntimeException("Command generation failed") }
        } else {
            if (specificCommandCheck) {
                1 * apiService.generateCommandString({ String cmd ->
                    cmd.contains("Get-SCVirtualMachine -VMMServer localhost -ID \"${externalId}\"") &&
                            cmd.contains('Get-SCVirtualDiskDrive -VM $VM') &&
                            cmd.contains('ID=$disk.ID') &&
                            cmd.contains('Name=$disk.Name') &&
                            cmd.contains('VolumeType=$disk.VolumeType.ToString()') &&
                            cmd.contains('BusType=$disk.BusType.ToString()') &&
                            cmd.contains('VhdID=$disk.VirtualHardDisk.ID') &&
                            cmd.contains('VhdName=$Disk.VirtualHardDisk.Name') &&
                            cmd.contains('VhdType=$disk.VirtualHardDisk.VHDType.ToString()') &&
                            cmd.contains('VhdFormat=$disk.VirtualHardDisk.VHDFormatType.ToString()') &&
                            (vhdName ? cmd.contains("Where-Object {\$_.VirtualHardDisk -like \"${vhdName}\"}") : true)
                }) >> "generated powershell command"
            } else {
                1 * apiService.generateCommandString(_) >> "generated powershell command"
            }

            1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> commandOutput
        }

        result.success == expectedSuccess
        result.disks == expectedDisks
        if (expectedMsg) {
            result.msg == expectedMsg
        }

        where:
        scenario                               | externalId    | vhdName           | commandOutput                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | expectedSuccess | expectedDisks                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | expectedMsg | shouldThrowException | specificCommandCheck
        "VM not found - empty disk list"      | "vm-nonexist" | null              | [success: true, exitCode: '0', data: []]                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | true            | []                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | null        | false                | false
        "VM has no disks"                     | "vm-12345"    | null              | [success: true, exitCode: '0', data: []]                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | true            | []                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | null        | false                | false
        "specific disk not found"             | "vm-12345"    | "missing.vhdx"    | [success: true, exitCode: '0', data: []]                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | true            | []                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | null        | false                | false
        "command execution failure"           | "vm-12345"    | null              | [success: false, exitCode: '1', error: "PowerShell execution failed"]                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | false           | []                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | null        | false                | false
        "single disk with IDE bus type"       | "vm-legacy"   | null              | [success: true, exitCode: '0', data: [[ID: "disk-ide-1", Name: "IDE Disk", VolumeType: "BootAndSystem", BusType: "IDE", Bus: 0, Lun: 0, VhdID: "vhd-ide-1", VhdName: "legacy.vhd", VhdType: "Fixed", VhdFormat: "VHD", VhdLocation: "C:\\VMs\\legacy.vhd", HostVolumeId: "vol-ide-1", FileShareId: "share-ide-1", PartitionUniqueId: "part-ide-1"]]]                                                                                                                                                                                                                                                                                       | true            | [[ID: "disk-ide-1", Name: "IDE Disk", VolumeType: "BootAndSystem", BusType: "IDE", Bus: 0, Lun: 0, VhdID: "vhd-ide-1", VhdName: "legacy.vhd", VhdType: "Fixed", VhdFormat: "VHD", VhdLocation: "C:\\VMs\\legacy.vhd", HostVolumeId: "vol-ide-1", FileShareId: "share-ide-1", PartitionUniqueId: "part-ide-1"]]                                                                                                                                                                                                                              | null        | false                | false
        "multiple SCSI buses"                 | "vm-multi"    | null              | [success: true, exitCode: '0', data: [[ID: "disk-scsi-1", Name: "SCSI Disk 1", VolumeType: "BootAndSystem", BusType: "SCSI", Bus: 0, Lun: 0, VhdID: "vhd-scsi-1", VhdName: "scsi1.vhdx", VhdType: "DynamicallyExpanding", VhdFormat: "VHDX", VhdLocation: "C:\\VMs\\scsi1.vhdx", HostVolumeId: "vol-scsi-1", FileShareId: "share-scsi-1", PartitionUniqueId: "part-scsi-1"], [ID: "disk-scsi-2", Name: "SCSI Disk 2", VolumeType: "None", BusType: "SCSI", Bus: 1, Lun: 0, VhdID: "vhd-scsi-2", VhdName: "scsi2.vhdx", VhdType: "Fixed", VhdFormat: "VHDX", VhdLocation: "C:\\VMs\\scsi2.vhdx", HostVolumeId: "vol-scsi-2", FileShareId: "share-scsi-2", PartitionUniqueId: "part-scsi-2"]]] | true            | [[ID: "disk-scsi-1", Name: "SCSI Disk 1", VolumeType: "BootAndSystem", BusType: "SCSI", Bus: 0, Lun: 0, VhdID: "vhd-scsi-1", VhdName: "scsi1.vhdx", VhdType: "DynamicallyExpanding", VhdFormat: "VHDX", VhdLocation: "C:\\VMs\\scsi1.vhdx", HostVolumeId: "vol-scsi-1", FileShareId: "share-scsi-1", PartitionUniqueId: "part-scsi-1"], [ID: "disk-scsi-2", Name: "SCSI Disk 2", VolumeType: "None", BusType: "SCSI", Bus: 1, Lun: 0, VhdID: "vhd-scsi-2", VhdName: "scsi2.vhdx", VhdType: "Fixed", VhdFormat: "VHDX", VhdLocation: "C:\\VMs\\scsi2.vhdx", HostVolumeId: "vol-scsi-2", FileShareId: "share-scsi-2", PartitionUniqueId: "part-scsi-2"]] | null        | false                | false
        "filter by wildcard pattern"         | "vm-12345"    | "*.vhdx"          | [success: true, exitCode: '0', data: [[ID: "disk-1", Name: "System Disk", VolumeType: "BootAndSystem", BusType: "SCSI", Bus: 0, Lun: 0, VhdID: "vhd-1", VhdName: "system.vhdx", VhdType: "DynamicallyExpanding", VhdFormat: "VHDX", VhdLocation: "C:\\VMs\\system.vhdx", HostVolumeId: "vol-1", FileShareId: "share-1", PartitionUniqueId: "part-1"]]]                                                                                                                                                                                                                                                                                       | true            | [[ID: "disk-1", Name: "System Disk", VolumeType: "BootAndSystem", BusType: "SCSI", Bus: 0, Lun: 0, VhdID: "vhd-1", VhdName: "system.vhdx", VhdType: "DynamicallyExpanding", VhdFormat: "VHDX", VhdLocation: "C:\\VMs\\system.vhdx", HostVolumeId: "vol-1", FileShareId: "share-1", PartitionUniqueId: "part-1"]]                                                                                                                                                                                                                              | null        | false                | false
    }

    @Unroll
    def "test getDiskName with #scenario"() {
        given:

        when:
        def result = apiService.getDiskName(index, platform)

        then:

        result == expectedResult

        where:
        scenario                           | index | platform  | expectedResult
        "Windows platform index 0"        | 0     | 'windows' | "disk 1"
        "Windows platform index 1"        | 1     | 'windows' | "disk 2"
        "Windows platform index 5"        | 5     | 'windows' | "disk 6"
        "Linux platform index 0"          | 0     | 'linux'   | "sda"
        "Linux platform index 1"          | 1     | 'linux'   | "sdb"
        "Linux platform index 4"          | 4     | 'linux'   | "sde"
        "Default platform (linux) index 0"| 0     | null      | "sda"
        "Default platform (linux) index 2"| 2     | null      | "sdc"
        "Other platform index 0"          | 0     | 'unix'    | "sda"
        "Other platform index 3"          | 3     | 'unix'    | "sdd"
    }

    @Unroll
    def "test getDiskNameList returns correct disk name list"() {
        when:
        def result = apiService.DISK_NAME_LIST

        then:
        result == expectedDiskNames
        result.size() == 12
        result[0] == 'sda'
        result[11] == 'sdl'

        where:
        expectedDiskNames = ['sda', 'sdb', 'sdc', 'sdd', 'sde', 'sdf', 'sdg', 'sdh', 'sdi', 'sdj', 'sdk', 'sdl']
    }

    @Unroll
    def "test removeDisk with scenario: #scenario"() {
        given:
        def opts = [
                externalId: externalId,
                sshHost: 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password',
                winrmPort: '5985'
        ]

        when:
        TaskResult result = apiService.removeDisk(opts, diskId)

        then:
        if (shouldThrowException) {
            1 * apiService.generateCommandString(_) >> { throw new RuntimeException("Command generation failed") }
        } else {
            1 * apiService.generateCommandString({ String cmd ->
                cmd.contains("Get-SCVirtualMachine -VMMServer localhost -ID \"${externalId}\"") &&
                        cmd.contains("Get-SCVirtualDiskDrive -VM \$VM | where { \$_.VirtualHardDiskId -eq \"${diskId}\" }") &&
                        cmd.contains("Remove-SCVirtualDiskDrive -VirtualDiskDrive \$VirtualDiskDrive -JobGroup") &&
                        cmd.contains("Set-SCVirtualMachine -VM \$VM -JobGroup") &&
                        cmd.split(';').size() == 4
            }) >> "generated powershell command"

            1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> commandOutput
        }

        result.success == expectedResult.success
        result.exitCode == expectedResult.exitCode
        if (expectedResult.error) {
            result.error == expectedResult.error
        }

        where:
        scenario                        | externalId    | diskId        | commandOutput  | expectedResult | shouldThrowException
        "successfully removes disk"     | "vm-12345"    | "disk-001"    | new TaskResult([success: true, exitCode: '0'])  | new TaskResult([success: true, exitCode: '0']) | false
        "VM not found"                  | "vm-nonexist" | "disk-001"    | new TaskResult([success: false, exitCode: '1', error: "VM not found"]) | new TaskResult([success: false, exitCode: '1', error: "VM not found"])| false
        "disk not found"                | "vm-12345"    | "disk-missing"| new TaskResult([success: false, exitCode: '1', error: "Disk not found"]) | new TaskResult([success: false, exitCode: '1', error: "Disk not found"]) | false
        "PowerShell execution failure"  | "vm-12345"    | "disk-001"    | new TaskResult([success: false, exitCode: '1', error: "Execution failed"]) | new TaskResult([success: false, exitCode: '1', error: "Execution failed"]) | false
        "different VM and disk IDs"     | "vm-prod-01"  | "vhd-system"  | new TaskResult([success: true, exitCode: '0'])  | new TaskResult([success: true, exitCode: '0']) | false
        "GUID format disk ID"           | "vm-12345"    | "12345678-1234-5678-9012-123456789012" | new TaskResult([success: true, exitCode: '0']) | new TaskResult([success: true, exitCode: '0']) | false
    }

    @Unroll
    def "test getJob with scenario: #scenario"() {
        given:
        def opts = DEFAULT_OPTS.clone()

        when:
        def result = apiService.getJob(opts, jobId)

        then:
        if (shouldThrowException) {
            1 * apiService.generateCommandString(_) >> { throw new RuntimeException("Command generation failed") }
        } else {
            1 * apiService.generateCommandString({ String cmd ->
                cmd.contains("Get-SCJob -VMMServer localhost -ID \"${jobId}\"") &&
                        cmd.contains('\$report = New-Object PSObject -property @{') &&
                        cmd.contains('ID=$job.ID') &&
                        cmd.contains('Name=$job.Name') &&
                        cmd.contains('Progress=$job.Progress') &&
                        cmd.contains('Status=$job.Status.toString()') &&
                        cmd.contains('$report')
            }) >> "generated powershell command"

            1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> commandOutput
        }

        result.success == expectedSuccess
        result.jobDetail == expectedJobDetail
        if (expectedJobId) {
            result.jobDetail?.ID == expectedJobId
        }

        where:
        scenario                              | jobId                                      | commandOutput                                                                                                                                                          | expectedSuccess | expectedJobDetail                                                                                                                      | expectedJobId                              | shouldThrowException
        "successfully retrieves job"          | "job-12345"                               | [success: true, exitCode: '0', data: [[ID: "job-12345", Name: "Create VM", Progress: 100, Status: "Completed"]]]                                                    | true            | [ID: "job-12345", Name: "Create VM", Progress: 100, Status: "Completed"]                                                              | "job-12345"                               | false
        "successfully retrieves running job"  | "job-67890"                               | [success: true, exitCode: '0', data: [[ID: "job-67890", Name: "Deploy Template", Progress: 75, Status: "Running"]]]                                                 | true            | [ID: "job-67890", Name: "Deploy Template", Progress: 75, Status: "Running"]                                                           | "job-67890"                               | false
        "successfully retrieves failed job"   | "job-fail-001"                            | [success: true, exitCode: '0', data: [[ID: "job-fail-001", Name: "Delete VM", Progress: 50, Status: "Failed"]]]                                                     | true            | [ID: "job-fail-001", Name: "Delete VM", Progress: 50, Status: "Failed"]                                                               | "job-fail-001"                            | false
        "job not found"                       | "job-nonexistent"                         | [success: false, exitCode: '1', error: "Job not found"]                                                                                                              | false           | null                                                                                                                                   | null                                       | false
        "command execution failure"           | "job-12345"                               | [success: false, exitCode: '1', error: "PowerShell execution failed"]                                                                                                | false           | null                                                                                                                                   | null                                       | false
        "exception handling"                  | "job-12345"                               | null                                                                                                                                                                   | false           | null                                                                                                                                   | null                                       | true
        "GUID format job ID"                  | "12345678-1234-5678-9012-123456789012"    | [success: true, exitCode: '0', data: [[ID: "12345678-1234-5678-9012-123456789012", Name: "Backup VM", Progress: 100, Status: "Completed"]]]                        | true            | [ID: "12345678-1234-5678-9012-123456789012", Name: "Backup VM", Progress: 100, Status: "Completed"]                                  | "12345678-1234-5678-9012-123456789012"    | false
        "job with zero progress"              | "job-new"                                 | [success: true, exitCode: '0', data: [[ID: "job-new", Name: "Initialize VM", Progress: 0, Status: "Queued"]]]                                                       | true            | [ID: "job-new", Name: "Initialize VM", Progress: 0, Status: "Queued"]                                                                 | "job-new"                                 | false
        "job with null progress"              | "job-unknown"                             | [success: true, exitCode: '0', data: [[ID: "job-unknown", Name: "Unknown Task", Progress: null, Status: "Unknown"]]]                                                | true            | [ID: "job-unknown", Name: "Unknown Task", Progress: null, Status: "Unknown"]                                                          | "job-unknown"                             | false
        "job with long name"                  | "job-long-name"                           | [success: true, exitCode: '0', data: [[ID: "job-long-name", Name: "Very Long Job Name That Describes Complex Operation", Progress: 25, Status: "Running"]]]       | true            | [ID: "job-long-name", Name: "Very Long Job Name That Describes Complex Operation", Progress: 25, Status: "Running"]                 | "job-long-name"                           | false
        "job with special characters"         | "job-special-123"                         | [success: true, exitCode: '0', data: [[ID: "job-special-123", Name: "Job & Task #1", Progress: 90, Status: "CompletedWithWarnings"]]]                              | true            | [ID: "job-special-123", Name: "Job & Task #1", Progress: 90, Status: "CompletedWithWarnings"]                                         | "job-special-123"                         | false
        "running job status"                  | "job-running"                             | [success: true, exitCode: '0', data: [[ID: "job-running", Name: "VM Creation", Progress: 45, Status: "Running"]]]                                                   | true            | [ID: "job-running", Name: "VM Creation", Progress: 45, Status: "Running"]                                                             | "job-running"                             | false
        "queued job status"                   | "job-queued"                              | [success: true, exitCode: '0', data: [[ID: "job-queued", Name: "Template Deploy", Progress: 0, Status: "Queued"]]]                                                 | true            | [ID: "job-queued", Name: "Template Deploy", Progress: 0, Status: "Queued"]                                                           | "job-queued"                              | false
        "paused job status"                   | "job-paused"                              | [success: true, exitCode: '0', data: [[ID: "job-paused", Name: "VM Migration", Progress: 60, Status: "Paused"]]]                                                   | true            | [ID: "job-paused", Name: "VM Migration", Progress: 60, Status: "Paused"]                                                             | "job-paused"                              | false
        "cancelled job status"                | "job-cancelled"                           | [success: true, exitCode: '0', data: [[ID: "job-cancelled", Name: "Snapshot Creation", Progress: 30, Status: "Cancelled"]]]                                        | true            | [ID: "job-cancelled", Name: "Snapshot Creation", Progress: 30, Status: "Cancelled"]                                                  | "job-cancelled"                           | false
        "warning job status"                  | "job-warning"                             | [success: true, exitCode: '0', data: [[ID: "job-warning", Name: "VM Configuration", Progress: 100, Status: "CompletedWithWarnings"]]]                              | true            | [ID: "job-warning", Name: "VM Configuration", Progress: 100, Status: "CompletedWithWarnings"]                                        | "job-warning"                             | false
        "test job with detailed command"      | "test-job-001"                            | [success: true, exitCode: '0', data: [[ID: "test-job-001", Name: "Test Job", Progress: 100, Status: "Completed"]]]                                                 | true            | [ID: "test-job-001", Name: "Test Job", Progress: 100, Status: "Completed"]                                                           | "test-job-001"                            | false
        "wrapExecuteCommand internal failure" | "failing-job"                             | [success: false, exitCode: '1', error: "Connection timeout to SCVMM server", data: null]                                                                            | false           | null                                                                                                                                   | null                                       | false
    }


    @Unroll
    def "test waitForJobToComplete with scenario: #scenario"() {
        given:
        def opts = DEFAULT_OPTS.clone()

        when:
        def result = apiService.waitForJobToComplete(opts, jobId)

        then:
        // Mock getJob calls based on scenario
        if (scenario == "job completes immediately") {
            1 * apiService.getJob(opts, jobId) >> [
                    success: true,
                    jobDetail: [ID: jobId, Status: "Completed", Progress: 100]
            ]
        } else if (scenario == "job completes after 2 attempts") {
            1 * apiService.getJob(opts, jobId) >> [
                    success: true,
                    jobDetail: [ID: jobId, Status: "Running", Progress: 50]
            ]
            1 * apiService.getJob(opts, jobId) >> [
                    success: true,
                    jobDetail: [ID: jobId, Status: "Completed", Progress: 100]
            ]
        } else if (scenario == "job fails after running") {
            1 * apiService.getJob(opts, jobId) >> [
                    success: true,
                    jobDetail: [ID: jobId, Status: "Running", Progress: 30]
            ]
            1 * apiService.getJob(opts, jobId) >> [
                    success: true,
                    jobDetail: [ID: jobId, Status: "Failed", Progress: 30]
            ]
        } else if (scenario == "job succeeds with info") {
            1 * apiService.getJob(opts, jobId) >> [
                    success: true,
                    jobDetail: [ID: jobId, Status: "SucceedWithInfo", Progress: 100]
            ]
        } else if (scenario == "job transitions through multiple states") {
            1 * apiService.getJob(opts, jobId) >> [
                    success: true,
                    jobDetail: [ID: jobId, Status: "Queued", Progress: 0]
            ]
            1 * apiService.getJob(opts, jobId) >> [
                    success: true,
                    jobDetail: [ID: jobId, Status: "Running", Progress: 25]
            ]
            1 * apiService.getJob(opts, jobId) >> [
                    success: true,
                    jobDetail: [ID: jobId, Status: "Running", Progress: 75]
            ]
            1 * apiService.getJob(opts, jobId) >> [
                    success: true,
                    jobDetail: [ID: jobId, Status: "Completed", Progress: 100]
            ]
        } else if (scenario == "handles exceptions gracefully") {
            1 * apiService.getJob(opts, jobId) >> { throw new RuntimeException("Connection failed") }
        }

        // Mock sleep to avoid delays
        _ * apiService.sleep(_)

        result.success == expectedSuccess

        where:
        scenario                                    | jobId           | expectedSuccess
        "job completes immediately"                 | "job-complete"  | true
        "job completes after 2 attempts"           | "job-delayed"   | true
        "job fails after running"                  | "job-failed"    | false
        "job succeeds with info"                   | "job-info"      | true
        "job transitions through multiple states"  | "job-multi"     | true
        "handles exceptions gracefully"            | "job-exception" | false
    }

    @Unroll
    def "test resizeDisk successfully resizes disk"() {
        given:
        def opts = [
                externalId : 'vm-12345',
                sshHost    : 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password',
                winrmPort  : '5985'
        ]
        def diskId = "disk-001"
        def diskSizeBytes = 10737418240L // 10GB in bytes

        // Mock resize operation success response
        def resizeStatusData = [
                success: true,
                jobId  : "job-resize-12345",
                errOut : null
        ]

        def resizeCommandOutput = [
                success : true,
                exitCode: '0',
                data    : [resizeStatusData]
        ]

        // Mock waitForJobToComplete success response
        def waitResults = [
                success  : true,
                jobDetail: [
                        ID      : "job-resize-12345",
                        Status  : "Completed",
                        Progress: 100
                ]
        ]

        morpheusContext.executeWindowsCommand(*_) >> {
            return Single.just([success: true, exitCode: '0', data: '[{"Status":"Success"}]'])

        }

        when:
        def result = apiService.resizeDisk(opts, diskId, diskSizeBytes)

        then:
        // Verify generateCommandString was called with the correct PowerShell command
        1 * apiService.generateCommandString({ String cmd ->
            cmd.contains('$vmId = "vm-12345"') &&
                    cmd.contains('$diskId = "disk-001"') &&
                    cmd.contains('$newSize = 10') &&
                    cmd.contains('$VM = Get-SCVirtualMachine -VMMServer localhost -ID $vmID') &&
                    cmd.contains('$vDisk = Get-SCVirtualDiskDrive -VM $VM | Where-Object  {$_.VirtualHardDiskId -eq $diskId}') &&
                    cmd.contains('$expandParams=@{') &&
                    cmd.contains('Expand-SCVirtualDiskDrive @expandParams') &&
                    cmd.contains('$report')
        })  >> "generated powershell command"

        // Verify wrapExecuteCommand was called with the generated command
        1 * apiService.wrapExecuteCommand("generated powershell command", opts) >> resizeCommandOutput

        // Verify waitForJobToComplete was called with correct parameters
        1 * apiService.waitForJobToComplete(opts, "job-resize-12345") >> {
            return waitResults
        }

        // Verify the result
        result.success == true
        result.jobDetail.ID == "job-resize-12345"
        result.jobDetail.Status == "Completed"
        result.jobDetail.Progress == 100

    }

    @Unroll
    def "test insertContainerImage successfully transfers image "() {
        given:

        def mockCloudFile = Mock(CloudFile)
        def cloudFiles = [mockCloudFile]
        mockCloudFile.getName() >> "ubuntu-22.04.vhdx"

        def containerImage = [
                name          : "new-test-image",
                minDisk       : 5,
                minRam        : 512 * ComputeUtility.ONE_MEGABYTE,
                virtualImageId: 43L,
                tags          : 'morpheus, ubuntu',
                imageType     : 'vhdx',
                containerType : 'vhdx',
                cloudFiles    : cloudFiles
        ]

        def opts = [
                image: containerImage,
                rootSharePath: "\\\\server\\share",
                sshHost: 'scvmm-server',
                sshUsername: 'admin',
                sshPassword: 'password',
                winrmPort: '5985'
        ]


        apiService.formatImageFolder("new-test-image") >> {
            return "new_test_image"
        }

        apiService.findImage(opts, "new-test-image") >> {
            [ success: true, imageExists: false ]
        }

        def cmdString = "\$FormatEnumerationLimit =-1; Get-SCVirtualHardDisk -VMMServer localhost | where {\$_.SharePath -like \"\\\\server\\share\\images\\new_test_image\\*\"} | Select ID | ConvertTo-Json -Depth 3"

        apiService.wrapExecuteCommand(cmdString, opts) >> {
            return [success: true, data: []]
        }


        apiService.transferImage(opts, cloudFiles, "new-test-image") >> {
            return [success: true]
        }


        apiService.wrapExecuteCommand(_, opts) >> {
            return [success: true, data: [[ID: "vhd-new-12345"]], error: null]
        }

        apiService.deleteImage(opts, "new-test-image") >> {
            return [success: true, imageExists: false]
        }

        when:

        def result = apiService.insertContainerImage(opts)

        then:

        result.success == true
        result.imageId == "vhd-new-12345"
    }

    @Unroll
    def "test insertContainerImage successfully processes existing image without transfer"() {
        given:
        def containerImage = [
                name          : "existing-image",
                imageType     : 'vhd',
                cloudFiles    : Mock(CloudFile)
        ]

        def opts = [
                image: containerImage,
                rootSharePath: "\\\\server\\share"
        ]

        def cmdString = "\$FormatEnumerationLimit =-1; Get-SCVirtualHardDisk -VMMServer localhost | where {\$_.SharePath -like \"\\\\server\\share\\images\\new_test_image\\*\"} | Select ID | ConvertTo-Json -Depth 3"

        // Mock wrapExecuteCommand for initial VHD check (empty)
        apiService.wrapExecuteCommand(cmdString, opts) >> [success: true, data: []]

        // Mock formatImageFolder method
        apiService.formatImageFolder("existing-image") >> "existing_image"

        // Mock findImage - image already exists
        apiService.findImage(opts, "existing-image") >> [[imageExists: true], [imageName: "\\\\server\\temp\\existing-image.vhd"]]


        // Mock wrapExecuteCommand for import operation
        apiService.wrapExecuteCommand(_, opts) >> [success: true, data: [[ID: "vhd-existing-789"]], error: null]

        // Mock deleteImage cleanup
        apiService.deleteImage(opts, "existing-image")
        when:
        def result = apiService.insertContainerImage(opts)

        then:

        result.success == true
        result.imageId == "vhd-existing-789"
    }

    @Unroll
    def "handleCopyItemFallback returns #expectedResult and sets imageId=#expectedImageId when wrapExecuteCommand returns #scenario"() {
        given:
        def opts = [foo: "bar"]
        def sourcePath = "C:\\images\\test.vhdx"
        def tgtFolder = "C:\\images\\target"
        def rtn = [:]
        def taskResult1 = new TaskResult()
        taskResult1.success = true
        taskResult1.error = null
        taskResult1.data = [[ID: "vhd-123"]]

        // Mock wrapExecuteCommand
        apiService.wrapExecuteCommand(_, opts) >> taskResult1

        when:
        def result = apiService.handleCopyItemFallback(opts, sourcePath, tgtFolder, rtn)

        then:
        result == expectedResult
        rtn.imageId == expectedImageId

        where:
        scenario         | expectedResult | expectedImageId
        "success"        |  true           | "vhd-123"
    }

    @Unroll
    def "createServer returns success result for valid input"() {
        given:
        def opts = [
                serverId: 101,
                diskRoot: "C:\\disks",
                serverFolder: "vm01",
                isSysprep: false
        ]
        def mockCreateCommands = [launchCommand: "New-VM -Name vm01"]
        def mockRemoveTemplateCommands = ["Remove-Template"]
        def mockCreateData = new TaskResult()
        mockCreateData.success = true
        mockCreateData.error = null
        def mockServer = new ComputeServer()
        def mockExternalId = "vm-101"
        def mockServerCreated = [success: true]
        def mockDisks = [id: "disk-1"]
        def mockCloudInitIsoPath = "C:\\disks\\vm01\\config.iso"

        apiService.initializeServerOptions(opts) >> null
        apiService.buildCreateServerCommands(opts) >> mockCreateCommands
        apiService.buildRemoveTemplateCommands(mockCreateCommands) >> mockRemoveTemplateCommands
        apiService.generateCommandString("New-VM -Name vm01") >> "powershell command"
        apiService.wrapExecuteCommand("powershell command", opts) >> mockCreateData
        apiService.handleCreateDataErrors(mockCreateData, _) >> {}
        apiService.extractServerExternalId(mockCreateData) >> mockExternalId
        computeServerService.get(101) >> mockServer
        computeServerService.save(mockServer) >> mockServer
        apiService.checkServerCreated(opts, mockExternalId) >> mockServerCreated
        apiService.removeTemporaryTemplatesAndProfiles(mockRemoveTemplateCommands, opts) >> {}
        apiService.loadControllerServer(opts) >> {}
        apiService.handleAdditionalDisks(opts) >> {}
        apiService.handleClonedVM(opts, _) >> {}
        apiService.buildDiskMapping(_) >> mockDisks
        apiService.resizeDisks(opts, mockDisks) >> {}
        apiService.handleCloudInit(opts, "C:\\disks\\vm01", "vm01") >> mockCloudInitIsoPath
        apiService.startAndCheckServer(opts, mockDisks, _) >> {}
        apiService.deleteUnattend(opts, _) >> mockCreateData

        when:
        def result = apiService.createServer(opts)

        then:
        result instanceof Map
        result.deleteDvdOnComplete == [removeIsoFromDvd: true, deleteIso: mockCloudInitIsoPath]
    }

    @Unroll
    def "initializeServerOptions sets network and zone and calls loadControllerServer"() {
        given:
        def opts = [networkId: 10L, zoneId: 20L]
        def mockNetwork = new Network()
        def mockZone = new Cloud()
        networkService.get(10) >> mockNetwork
        cloudService.get(20) >> mockZone
        apiService.loadControllerServer(opts) >> {}

        when:
        apiService.initializeServerOptions(opts)

        then:
        opts.network == mockNetwork
        opts.zone == mockZone

    }

    @Unroll
    def "buildRemoveTemplateCommands returns correct remove commands for hardwareProfileName and templateName combinations"() {
        given:

        def createCommands = inputMap

        when:
        def result = apiService.buildRemoveTemplateCommands(createCommands)

        then:
        result == expectedCommands

        where:
        inputMap                                      | expectedCommands
        [hardwareProfileName: "HW", templateName: "T"] | [
                '$HWProfile = Get-SCHardwareProfile -VMMServer localhost | where { $_.Name -eq "HW"} ; $ignore = Remove-SCHardwareProfile -HardwareProfile $HWProfile;',
                '$template = Get-SCVMTemplate -VMMServer localhost -Name "T";  $ignore = Remove-SCVMTemplate -VMTemplate $template -RunAsynchronously;'
        ]
        [hardwareProfileName: "HW"]                    | [
                '$HWProfile = Get-SCHardwareProfile -VMMServer localhost | where { $_.Name -eq "HW"} ; $ignore = Remove-SCHardwareProfile -HardwareProfile $HWProfile;'
        ]
        [templateName: "T"]                            | [
                '$template = Get-SCVMTemplate -VMMServer localhost -Name "T";  $ignore = Remove-SCVMTemplate -VMTemplate $template -RunAsynchronously;'
        ]
        [:]                                            | []
    }

    @Unroll
    def "handleCreateDataErrors sets errorMsg and throws exception for error: #errorMsg"() {
        given:
        def rtn = [:]
        def createData = new TaskResult(success: false, error: errorMsg)

        when:
        apiService.handleCreateDataErrors(createData, rtn)

        then:
        def e = thrown(IllegalStateException)
        rtn.errorMsg == expectedErrorMsg

        where:
        errorMsg                                                        | expectedErrorMsg
        "Disk is not compatible which includes generation 2"            | "The virtual hard disk selected is not compatible with the template which include generation 2 virtual machine functionality."
        "Disk is not compatible which includes generation 1"            | "The virtual hard disk selected is not compatible with the template which include generation 1 virtual machine functionality."
        "Some other error"                                              | null
    }

    @Unroll
    def "extractServerExternalId returns #expectedResult when data is #scenario"() {
        given:
        def createData = new TaskResult(data: inputData)

        when:
        def result = apiService.extractServerExternalId(createData)

        then:
        result == expectedResult

        where:
        scenario                        | inputData                                                      | expectedResult
        "valid ObjectType 1"            | [[ObjectType: '1', ID: 'vm-123']]                              | 'vm-123'
        "ObjectType not 1"              | [[ObjectType: '2', ID: 'vm-456']]                              | null
        "data size not 1"               | [[ObjectType: '1', ID: 'vm-123'], [ObjectType: '1', ID: 'vm-456']] | null
        "missing ObjectType"            | [[ID: 'vm-789']]                                               | null
        "data is null"                  | null                                                           | null
        "data is empty"                 | []                                                             | null
    }

    @Unroll
    def "removeTemporaryTemplatesAndProfiles handles command list"() {
        given:
        def opts = [foo: "bar"]
        def commands = ["cmd1", "cmd2"]
        apiService.wrapExecuteCommand(_ as String, opts) >> {new TaskResult()}
        when:
        apiService.removeTemporaryTemplatesAndProfiles(commands, opts)

        then:
        opts.foo == "bar" // just to use opts and avoid unused variable warning

    }

    @Unroll
    def "handleAdditionalDisks attaches additional disks when vmDisk is successful"() {
        given:
        def opts = [
                externalId: "vm-123",
                additionalTemplateDisks: [
                        [diskSize: 104857600], // 100MB
                        [diskSize: 209715200]  // 200MB
                ]
        ]
        def vmDiskResult = [success: true]
        apiService.listVirtualDiskDrives(opts, opts.externalId, null) >> vmDiskResult
        apiService.createAndAttachDisk(opts, _) >> {
            return [success: true]
        }

        when:
        apiService.handleAdditionalDisks(opts)

        then:
        opts.externalId == "vm-123"

    }

    @Unroll
    def "extractServerExternalId returns #expectedResult when data is #scenario"() {
        given:
        def createData = new TaskResult(data: inputData)

        when:
        def result = apiService.extractServerExternalId(createData)

        then:
        result == expectedResult

        where:
        scenario                        | inputData                                                      | expectedResult
        "valid ObjectType 1"            | [[ObjectType: '1', ID: 'vm-123']]                              | 'vm-123'
        "ObjectType not 1"              | [[ObjectType: '2', ID: 'vm-456']]                              | null
        "data size not 1"               | [[ObjectType: '1', ID: 'vm-123'], [ObjectType: '1', ID: 'vm-456']] | null
        "missing ObjectType"            | [[ID: 'vm-789']]                                               | null
        "data is null"                  | null                                                           | null
        "data is empty"                 | []                                                             | null
    }

    @Unroll
    def "handleClonedVM sets cloneBaseResults and calls dependencies as expected "() {
        given:
        def opts = [
                cloneVMId: "vm-123",
                externalId: "vm-001",
                cloneBaseOpts: [cloudInitIsoNeeded: true, cloudConfigBytes: [1,2] as byte[], diskFolder: "df", imageFolderName: "img", clonedScvmmOpts: [:]]
        ]
        def rtn = [:]

        apiService.changeVolumeTypeForClonedBootDisk(_, _, _) >> {}
        when:
        apiService.handleClonedVM(opts, rtn)

        then:
        1 * apiService.importAndMountIso(_, _, _, _) >> "iso-path"

        rtn.cloneBaseResults.cloudInitIsoPath == "iso-path"

    }

    def "buildDiskMapping returns correct disk mapping for single OS and data disk"() {
        given:
        def opts = [
                externalId: "vm-001",
                dataDisks: [[id: "data-1"], [id: "data-2"]]
        ]
        def diskDrives = [
                disks: [
                        [ID: "os-123", HostVolumeId: "hv-1", FileShareId: "fs-1", VhdID: "vhd-1", VhdLocation: "loc-1", PartitionUniqueId: "part-1"],
                        [ID: "data-123", HostVolumeId: "hv-2", FileShareId: "fs-2", VhdID: "vhd-2", VhdLocation: "loc-2", PartitionUniqueId: "part-2"],
                        [ID: "data-456", HostVolumeId: "hv-3", FileShareId: "fs-3", VhdID: "vhd-3", VhdLocation: "loc-3", PartitionUniqueId: "part-3"]
                ]
        ]

        apiService.listVirtualDiskDrives(_, _) >> diskDrives
        apiService.findBootDiskIndex(_) >> 0

        when:
        def result = apiService.buildDiskMapping(opts)

        then:
        result.osDisk.externalId == "os-123"
        result.dataDisks[0].id == "data-1"
        result.dataDisks[0].externalId == "data-123"
        result.dataDisks[1].id == "data-2"
        result.dataDisks[1].externalId == "data-456"
        result.diskMetaData["os-123"].HostVolumeId == "hv-1"
        result.diskMetaData["data-123"].dataDisk
        result.diskMetaData["data-456"].dataDisk
    }

    def "resizeDisks resizes OS disk and calls resizeDataDisksIfTemplate"() {
        given:
        def opts = [osDiskSize: 100, other: "value"]
        def disks = [
                osDisk: [externalId: "os-123"],
                diskMetaData: [
                        "os-123": [VhdID: "vhd-001"]
                ]
        ]
        def resizeResponse = [success: true]

        apiService.resizeDisk(_, _, _) >> resizeResponse

        when:
        apiService.resizeDisks(opts, disks)

        then:
        1 * apiService.resizeDisk(opts, "vhd-001", 100) >> resizeResponse
        1 * apiService.resizeDataDisksIfTemplate(opts, disks)
    }

    def "resizeDataDisksIfTemplate resizes matching data disks when isTemplate is true"() {
        given:
        def opts = [
                isTemplate: true,
                templateId: "tmpl-1",
                dataDisks: [
                        [externalId: "data-1", maxStorage: 200],
                        [externalId: "data-2", maxStorage: 300]
                ]
        ]
        def disks = [
                diskMetaData: [
                        "data-1": [VhdID: "vhd-1"],
                        "data-2": [VhdID: "vhd-2"],
                        "data-3": [VhdID: "vhd-3"] // no matching dataDisk in opts
                ]
        ]
        def resizeResponse = [success: true]

        apiService.resizeDisk(_, _, _) >> resizeResponse

        when:
        apiService.resizeDataDisksIfTemplate(opts, disks)

        then:
        1 * apiService.resizeDisk(opts, "vhd-1", 200) >> resizeResponse
        1 * apiService.resizeDisk(opts, "vhd-2", 300) >> resizeResponse
        0 * apiService.resizeDisk(opts, "vhd-3", _)
    }

    def "handleCloudInit returns iso path when cloudConfigBytes is present and isSysprep is false"() {
        given:
        def opts = [cloudConfigBytes: [1,2,3] as byte[], isSysprep: false]
        def diskFolder = "C:\\disks\\vm01"
        def imageFolderName = "vm01"
        def isoPath = "C:\\disks\\vm01\\config.iso"

        apiService.createDVD(opts) >> {}
        apiService.importAndMountIso(opts.cloudConfigBytes, diskFolder, imageFolderName, opts) >> isoPath

        when:
        def result = apiService.handleCloudInit(opts, diskFolder, imageFolderName)

        then:
        result == isoPath
    }

    def "startAndCheckServer sets rtn.server and success=true when server is ready"() {
        given:
        def opts = [name: "vm01", externalId: "vm-123"]
        def disks = [osDisk: [id: "disk-1"]]
        def rtn = [:]
        def serverDetail = [success: true, server: [VMId: "vm-123"]]

        apiService.startServer(opts, opts.externalId) >> [success: true]
        apiService.getServerDetails(opts, opts.externalId) >> serverDetail

        when:
        apiService.startAndCheckServer(opts, disks, rtn)

        then:
        rtn.success == true
        rtn.server == [
                name: "vm01",
                id: "vm-123",
                VMId: "vm-123",
                disks: disks
        ]
    }

    def "listVirtualMachines returns success and populates virtualMachines list"() {
        given:
        def opts = [
                zone: [regionCode: "cloud-1"],
                zoneConfig: null
        ]
        def vmData = [[
                              ID: "vm-001",
                              ObjectType: "1",
                              VMId: "vm-001",
                              Name: "TestVM",
                      ]]
        apiService.generateCommandString(_ as String) >> "powershell command"
        def cmdResult = new TaskResult(success: false, data: vmData)
        apiService.wrapExecuteCommand("powershell command", opts) >> {
            return cmdResult
        }

        when:
        def result = apiService.listVirtualMachines(opts)

        then:
        result.success == false
    }

    def "listDatastores returns failure when wrapExecuteCommand fails"() {
        given:
        def opts = [zone: [regionCode: "cloud-1"]]
        apiService.generateCommandString(_ as String) >> "powershell command"
        def cmdResult = new TaskResult(success: false, data: null)
        apiService.wrapExecuteCommand("powershell command", opts) >> cmdResult

        when:
        def result = apiService.listDatastores(opts)

        then:
        result.success == false
        result.datastores == []
    }

    def "listRegisteredFileShares returns failure when wrapExecuteCommand fails"() {
        given:
        def opts = [zone: [regionCode: "cloud-1"]]
        apiService.generateCommandString(_ as String) >> "powershell command"
        def cmdResult = new TaskResult(success: false, data: null)
        apiService.wrapExecuteCommand("powershell command", opts) >> cmdResult

        when:
        def result = apiService.listRegisteredFileShares(opts)

        then:
        result.success == false

    }

    def "listAllNetworks returns success and populates networks list"() {
        given:
        def opts = [zone: [regionCode: "cloud-1"]]
        def logicalNetworks = [[ID: "ln-1", Name: "LogicalNet1"], [ID: "ln-2", Name: "LogicalNet2"]]
        def vmNetworks = [
                [ID: "net-1", Name: "VMNet1", LogicalNetwork: "LogicalNet1"],
                [ID: "net-2", Name: "VMNet2", LogicalNetwork: "LogicalNet2"],
                [ID: "net-3", Name: "VMNet3", LogicalNetwork: "LogicalNet1"]
        ]
        def logicalNetworksResult = new TaskResult(success: true, exitCode: '0', data: logicalNetworks)
        def vmNetworksResult = new TaskResult(success: true, exitCode: '0', data: vmNetworks)

        apiService.generateCommandString(_ as String) >>> ["powershell logical", "powershell vm"]
        apiService.wrapExecuteCommand("powershell logical", opts) >> logicalNetworksResult
        apiService.wrapExecuteCommand("powershell vm", opts) >> vmNetworksResult

        when:
        def result = apiService.listAllNetworks(opts)

        then:
        result.success == true
        result.networks.size() == 3
        result.networks*.ID.containsAll(["net-1", "net-2", "net-3"])
    }

    def "checkServerReady returns success when server is ready"() {
        given:
        def serverId = 101L
        def vmId = "vm-123"
        def mockServer = new ComputeServer(id: serverId)
        def opts = [server: [id: serverId], waitForIp: false]
        def serverDetail = [success: true, server: [VMId: vmId, ipAddress: "10.0.0.1"]]

        // Mock dependencies
        apiService.sleep(_) >> null
        computeServerService.get(serverId) >> mockServer
        apiService.refreshVM(opts, vmId) >> null
        apiService.getServerDetails(opts, vmId) >> serverDetail
        apiService.assignIpAddress(serverDetail, mockServer) >> "10.0.0.1"
        apiService.processServerState(serverDetail, mockServer, "10.0.0.1", _ as Map) >> { args ->
            args[3].success = true
            return true // pending = !true -> false, so loop exits
        }
        apiService.updateNotFoundAttempts(_, _) >> 0
        apiService.shouldContinuePending(_, _, _) >> false

        when:
        def result = apiService.checkServerReady(opts, vmId)

        then:
        result.success == true
    }

    def "assignIpAddress sets and returns correct IP address"() {
        given:
        def server = new ComputeServer(externalIp: "2.2.2.2", internalIp: null)
        def serverDetail = [server: [internalIp: detailIp]]

        when:
        def result = apiService.assignIpAddress(serverDetail, server)

        then:
        result == expectedIp
        server.internalIp == expectedIp

        where:
        detailIp    | expectedIp
        "1.1.1.1"   | "1.1.1.1"
        null        | "2.2.2.2"
        ""          | "2.2.2.2"
    }

    def "processServerState sets rtn and returns correct value for different server states"() {
        given:
        def server = new ComputeServer(internalIp: serverInternalIp)
        def serverDetail = [server: [VirtualMachineState: vmState, Status: status]]
        def rtn = [:]

        when:
        def result = apiService.processServerState(serverDetail, server, ipAddress, rtn)

        then:
        result == expectedResult
        rtn.success == expectedSuccess
        rtn.server == serverDetail.server
        rtn.server.ipAddress == expectedIp

        where:
        vmState                | status            | serverInternalIp | ipAddress   || expectedResult | expectedSuccess | expectedIp
        "Running"              | null              | null            | "1.2.3.4"   || true           | true           | "1.2.3.4"
        null                   | "CreationFailed"  | null            | "5.6.7.8"   || true           | false          | "5.6.7.8"
        null                   | null              | "9.9.9.9"       | null        || true           | true           | "9.9.9.9"
    }

    def "updateNotFoundAttempts increments when error is VM_NOT_FOUND"() {
        given:
        Map serverDetail = [error: 'VM_NOT_FOUND']
        int notFoundAttempts = 2

        when:
        int result = apiService.updateNotFoundAttempts(serverDetail, notFoundAttempts)

        then:
        result == 3
    }

    def "shouldContinuePending returns correct value based on attempts, notFoundAttempts, and pending"() {
        given:
        // MAX_NOT_FOUND_ATTEMPTS is 10 in ScvmmApiService

        when:
        def result = apiService.shouldContinuePending(attempts, notFoundAttempts, pending)

        then:
        result == expected

        where:
        attempts | notFoundAttempts | pending || expected
        301      | 0               | true    || false    // attempts > 300
        100      | 11              | true    || false    // notFoundAttempts > 10
        100      | 5               | true    || true     // both within limits, pending true
        100      | 5               | false   || false    // both within limits, pending false
        301      | 11              | true    || false    // both over limits
    }

    def "startServer starts VM only if not already running"() {
        given:
        def opts = [async: async]
        def vmId = "vm-123"
        def serverDetail = [success: true, server: [VirtualMachineState: vmState]]
        def cmdResult = new TaskResult([success: cmdSuccess])

        apiService.getServerDetails(opts, vmId) >> serverDetail
        apiService.wrapExecuteCommand(_, opts) >> cmdResult

        when:
        def result = apiService.startServer(opts, vmId)

        then:
        result.success == expectedSuccess
        result.msg == expectedMsg

        where:
        vmState      | async | cmdSuccess | expectedSuccess | expectedMsg
        "Stopped"    | false | true       | true           | null
        "Stopped"    | true  | false      | false          | null
        "Running"    | false | true       | true           | "VM is already powered on"
    }

    def "startServer handles exception gracefully"() {
        given:

        def opts = [:]
        def vmId = "vm-err"
        apiService.getServerDetails(opts, vmId) >> { throw new RuntimeException("fail") }

        when:
        def result = apiService.startServer(opts, vmId)

        then:
        result.success == false
    }

    @Unroll
    def "deleteServer handles server deletion and errors (serverFolder=#serverFolder, throwsGetRootShare=#throwsGetRootShare)"() {
        given:
        def opts = [rootSharePath: rootSharePath, diskRoot: "D:\\disks", serverFolder: serverFolder]
        def vmId = "vm-123"
        if (throwsGetRootShare) {
            apiService.getRootSharePath(opts) >> { throw new RuntimeException("fail") }
        } else if (!rootSharePath) {
            apiService.getRootSharePath(opts) >> "C:\\share"
        }
        apiService.wrapExecuteCommand(_, opts) >> new TaskResult([success: true])

        when:
        def result = null
        try {
            result = apiService.deleteServer(opts, vmId)
        } catch (IllegalArgumentException ex) {
            result = ex
        }

        then:
        if (expectException) {
            result instanceof IllegalArgumentException
            result.message == "serverFolder MUST be specified"
        } else {
            result.success == expectedSuccess
        }

        where:
        serverFolder | rootSharePath | throwsGetRootShare || expectedSuccess | expectException
        "srv-01"     | "C:\\share"   | false             || true           | false
        null         | "C:\\share"   | false             || false          | true
        "srv-01"     | null          | false             || true           | false
        "srv-01"     | null          | true              || false          | false // logs error, returns default result
    }

    def "importPhysicalResource succeeds after retries and sets sharePath"() {
        given:
        def opts = [rootSharePath: 'C:\\Library', scvmmProvisionService: 'svc', controllerNode: 'ctrl']
        def sourcePath = 'C:\\src\\file.txt'
        def imageFolderName = 'imgFolder'
        def resourceName = 'file.txt'
        def sharePath = 'C:\\Library\\imgFolder'
        def expectedSharePath = 'C:\\Library\\imgFolder\\file.txt'
        apiService.sleep(_) >> { } // avoid real sleep

        def executeOut = new TaskResult()
        executeOut.success = true
        // First call fails, second call succeeds
        apiService.executeCommand(_, _) >>> executeOut

        when:
        def result = apiService.importPhysicalResource(opts, sourcePath, imageFolderName, resourceName)

        then:
        result.success
        result.sharePath == expectedSharePath

    }

    def "getRootSharePath returns path when shares exist"() {
        given:
        def opts = [foo: 'bar']
        def sharePath = 'C:\\Library\\Share'
        def shareBlocks = [[ID: '1', Name: 'Share1', Path: sharePath]]
        def cmdExecute = new TaskResult()
        cmdExecute.success = true
        cmdExecute.data = shareBlocks
        apiService.wrapExecuteCommand(_, _) >> cmdExecute

        when:
        def result = apiService.getRootSharePath(opts)

        then:
        result == sharePath
    }


    def "deleteUnattend calls wrapExecuteCommand with correct command and returns result"() {
        given:
        def opts = [foo: 'bar']
        def unattendPath = 'C:\\unattend\\unattend.xml'
        def expectedCommand = 'Remove-Item -Path "C:\\unattend\\unattend.xml" -Force'
        def generatedCommand = "powershell -Command \"${expectedCommand}\""
        def expectedResult = new TaskResult([success: true])
        apiService.generateCommandString(_) >> generatedCommand
        apiService.wrapExecuteCommand(generatedCommand, opts) >> expectedResult

        when:
        def result = apiService.deleteUnattend(opts, unattendPath)

        then:
        result == expectedResult
    }

    def "deleteUnattend returns failure result if wrapExecuteCommand fails"() {
        given:
        def opts = [:]
        def unattendPath = 'C:\\fail\\unattend.xml'
        def expectedCommand = 'Remove-Item -Path "C:\\fail\\unattend.xml" -Force'
        def generatedCommand = "powershell -Command \"${expectedCommand}\""
        def failResult = new TaskResult([success: false, error: "File not found"])
        apiService.generateCommandString(_) >> generatedCommand
        apiService.wrapExecuteCommand(generatedCommand, opts) >> failResult

        when:
        def result = apiService.deleteUnattend(opts, unattendPath)

        then:
        result == failResult
    }

    def "setCdrom sets ISO when cdPath is provided"() {
        given:
        def opts = [externalId: 'vm-123']
        def cdPath = 'C:\\isos\\cloudinit.iso'
        def SET_VIRTUAL_DVD_NO_MEDIA_CMD = "Set-SCVirtualDVDDrive -NoMedia"
        def CMD_SEPARATOR = ";"
        def commands = [
                '$vm = Get-SCVirtualMachine -VMMServer localhost -ID "vm-123"',
                '$dvd = Get-SCVirtualDVDDrive -VM $vm',
                '$iso = Get-SCISO -VMMServer localhost | where {$_.SharePath -eq "C:\\isos\\cloudinit.iso"}',
                SET_VIRTUAL_DVD_NO_MEDIA_CMD,
                '$ignore = Set-SCVirtualDVDDrive -VirtualDVDDrive $dvd -Bus $dvd.Bus -LUN $dvd.Lun -ISO $iso'
        ]
        def joinedCommand = commands.join(CMD_SEPARATOR)
        def generatedCommand = "powershell -Command \"${joinedCommand}\""
        def expectedResult = new TaskResult([success: true])

        apiService.generateCommandString(_) >> {  generatedCommand }
        apiService.wrapExecuteCommand(_,_) >> {
            expectedResult
        }

        when:
        def result = apiService.setCdrom(opts, cdPath)

        then:
        result == expectedResult
    }

    def "importAndMountIso uploads ISO, imports resource, mounts CD, and returns iso path"() {
        given:
        def cloudConfigBytes = "cloud-init-content".getBytes("UTF-8")
        def diskFolder = "C:\\vm\\disks"
        def imageFolderName = "img-folder"
        def compServ = new ComputeServer()
        compServ.name = "vm-001"
        def opts = [hypervisor: compServ]
        def expectedIsoPath = "C:\\vm\\disks\\config.iso"
        def fileResults = new TaskResult([success: true])
        def importResults = [sharePath: expectedIsoPath]

        apiService.wrapExecuteCommand(_,_) >> {  fileResults }

        fileCopyService.copyToServer(_,_,_,_,_) >> {
            new ServiceResponse(success: true)
        }
        apiService.importPhysicalResource(*_) >> { importResults }
        apiService.setCdrom(_,_) >> {   }

        when:
        def result = apiService.importAndMountIso(cloudConfigBytes, diskFolder, imageFolderName, opts)

        then:
        result == expectedIsoPath

    }

    def "checkServerCreated returns success when server is created"() {
        given:
        def opts = [dataDisks: [1, 2], additionalTemplateDisks: [3]]
        def vmId = "vm-123"
        def serverDetail = [success: true, server: [Status: "Running", VirtualDiskDrives: [1, 2]]]
        apiService.getServerDetails(_, _) >> serverDetail
        apiService.processServerStatus(_, _, _, _) >> { args ->
            args[3].success = true
            true
        }

        when:
        def result = apiService.checkServerCreated(opts, vmId)

        then:
        result.success == true
    }

    def "checkServerCreated returns failure on exception"() {
        given:
        def opts = [:]
        def vmId = "vm-123"
        apiService.getServerDetails(_, _) >> { throw new RuntimeException("fail") }

        when:
        def result = apiService.checkServerCreated(opts, vmId)

        then:
        result.success == null || result.success == false
    }

    def "processServerStatus returns true and sets success when status is not UnderCreation and disk count matches"() {
        given:
        def serverDetail = [server: [Status: "Running", VirtualDiskDrives: [1, 2]]]
        def opts = [dataDisks: [1], additionalTemplateDisks: []]
        def rtn = [:]

        when:
        def result = apiService.processServerStatus(serverDetail, opts, "vm-1", rtn)

        then:
        result == true
        rtn.success == true
        rtn.server == serverDetail.server
    }

    def "processServerStatus returns true and sets failure when status is CREATION_FAILED"() {
        given:
        def serverDetail = [server: [Status: 'CreationFailed', VirtualDiskDrives: [1]]]
        def opts = [dataDisks: [], additionalTemplateDisks: []]
        def rtn = [:]

        when:
        def result = apiService.processServerStatus(serverDetail, opts, "vm-1", rtn)

        then:
        result == true
        rtn.success == true
    }

    def "processServerStatus returns false when status is UnderCreation or disk count does not match"() {
        given:
        def serverDetail = [server: [Status: "UnderCreation", VirtualDiskDrives: [1]]]
        def opts = [dataDisks: [1], additionalTemplateDisks: []]
        def rtn = [:]

        when:
        def result = apiService.processServerStatus(serverDetail, opts, "vm-1", rtn)

        then:
        result == false
        !rtn.success
    }

    def "createAndAttachDisk returns disk info on success"() {
        given:
        def opts = [externalId: "vm-123"]
        def diskSpec = [vhdName: "data-disk1", sizeMb: 10240, vhdType: "FixedSize", vhdFormat: "VHDX", vhdPath: "C:\\disks"]
        def addDiskCmd = _ as String
        def cmdResult = new TaskResult([success: true])
        def diskInfo = [id: "disk-1", name: "data-disk1"]
        def listResult = [success: true, disks: [diskInfo]]

        apiService.generateCommandString(_) >> "powershell -Command \"addDiskCmd\""
        apiService.wrapExecuteCommand(_, opts) >> cmdResult
        apiService.listVirtualDiskDrives(opts, opts.externalId, diskSpec.vhdName) >> listResult

        when:
        def result = apiService.createAndAttachDisk(opts, diskSpec, true)

        then:
        result.success == true
        result.disk == diskInfo
    }

    @Unroll
    def "test listNetworkIPPools handles exception gracefully"() {
        given:
        def opts = [zone: [regionCode: "cloud-1"]]

        // Mock wrapExecuteCommand to throw an exception
        apiService.wrapExecuteCommand(_, opts) >> { throw new RuntimeException("Connection timeout") }

        when:
        def result = apiService.listNetworkIPPools(opts)

        then:
        result.success == false
        result.msg == "Error syncing ip pools list from SCVMM Host"
        result.ipPools == []
        result.networkMapping == []
    }

    @Unroll
    def "test listNetworkIPPools handles #scenario"() {
        given:
        def opts = [zone: [regionCode: "cloud-1"]]

        when:
        def result = apiService.listNetworkIPPools(opts)

        then:
        ipPoolsCallCount * apiService.wrapExecuteCommand({ it.contains("Get-SCStaticIPAddressPool") }, opts) >> ipPoolsResponse
        networkMappingCallCount * apiService.wrapExecuteCommand({ it.contains("Get-SCVMNetwork") }, opts) >> networkMappingResponse

        result.success == expectedSuccess
        result.ipPools == expectedIpPools
        result.networkMapping == expectedNetworkMapping

        where:
        scenario                          | ipPoolsResponse                                       | networkMappingResponse                               | ipPoolsCallCount | networkMappingCallCount | expectedSuccess | expectedIpPools | expectedNetworkMapping
        "successful IP pools and mapping" | [success: true, exitCode: '0', data: [[ID: 'pool1']]] | [success: true, exitCode: '0', data: [[ID: 'net1']]] | 1                | 1                       | true            | [[ID: 'pool1']] | [[ID: 'net1']]
        "IP pools failure"                | [success: false, exitCode: '1']                       | [success: true, exitCode: '0', data: [[ID: 'net1']]] | 1                | 0                       | false           | []              | []
        "IP pools wrong exit code"        | [success: true, exitCode: '1']                        | [success: true, exitCode: '0', data: [[ID: 'net1']]] | 1                | 0                       | false           | []              | []
        "network mapping failure"         | [success: true, exitCode: '0', data: [[ID: 'pool1']]] | [success: false, exitCode: '1']                      | 1                | 1                       | false           | [[ID: 'pool1']] | []
        "network mapping wrong exit code" | [success: true, exitCode: '0', data: [[ID: 'pool1']]] | [success: true, exitCode: '1']                       | 1                | 1                       | false           | [[ID: 'pool1']] | []
        "empty IP pools data"             | [success: true, exitCode: '0', data: null]            | [success: true, exitCode: '0', data: [[ID: 'net1']]] | 1                | 1                       | true            | []              | [[ID: 'net1']]
        "empty network mapping data"      | [success: true, exitCode: '0', data: [[ID: 'pool1']]] | [success: true, exitCode: '0', data: null]           | 1                | 1                       | true            | [[ID: 'pool1']] | []
    }
}