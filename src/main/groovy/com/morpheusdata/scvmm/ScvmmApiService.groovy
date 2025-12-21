package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.TaskResult
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import groovy.json.JsonOutput
import com.bertramlabs.plugins.karman.CloudFile

@SuppressWarnings(['CompileStatic', 'MethodCount', 'ClassSize'])
class ScvmmApiService {
// At the top of ScvmmApiService.groovy

    static final String CMD_SEPARATOR = ";"
    static final String OBJECT_TYPE_1 = '1'
    static final String ARRAY_INIT = "@()"
    static final String WIN2022_STD_CORE = '2022.std.core'
    static final String WIN2022_DC_CORE = '2022.dc.core'
    static final String DEFAULT_PAGE_SIZE = 50
    static final String EXIT_CODE_SUCCESS = '0'
    static final String VMID_PLACEHOLDER = "<%vmid%>"
    static final String EXCEPTION_MSG = 'An Exception Has Occurred'
    static final String TAR_GZ_EXTENSION = '.tar.gz'
    static final Integer MAX_IMPORT_ATTEMPTS = 5
    static final String JOB_STATUS_COMPLETED = 'completed'
    static final String JOB_STATUS_SUCCEED_WITH_INFO = 'succeedwithinfo'
    static final List<String> DISK_NAME_LIST = ['sda', 'sdb', 'sdc', 'sdd', 'sde', 'sdf', 'sdg',
                                                'sdh', 'sdi', 'sdj', 'sdk', 'sdl']

    static final String GET_SC_LOGICAL_NETWORK_CMD = "Get-SCLogicalNetwork -VMMServer localhost | Select ID,Name"
    static final String VHDNAME_PLACEHOLDER = "<%vhdname%>"
    static final String SET_VIRTUAL_DVD_NO_MEDIA_CMD = "\$ignore = Set-SCVirtualDVDDrive -VirtualDVDDrive \$dvd " +
            "-Bus \$dvd.Bus -LUN \$dvd.Lun -NoMedia"
    static final String SET_HARDWARE_PROFILE_CMD = " { Set-SCHardwareProfile -HardwareProfile \$HardwareProfile "
    static final String IGNORE_IF_NOT = "\$ignore = If (-not "
    static final String CLOSE_BRACE = "}"
    static final String IGNORE_NEW_HARDWARE_PROFILE = "\$ignore = New-SCHardwareProfile -VMMServer localhost "
    static final String HARDWARE_PROFILE_GET_CMD = "\$HardwareProfile = Get-SCHardwareProfile -VMMServer localhost |"
    static final String IGNORE_SET_HARDWARE_PROFILE = "\$ignore = Set-SCHardwareProfile " +
            "-HardwareProfile \$HardwareProfile "

// Groovy
    static final String IGNORE_NEW_VIRTUAL_DISK_DRIVE = "\$ignore = New-SCVirtualDiskDrive -VMMServer "
    static final String VM_CONFIGURATION_PARAM = "-VMConfiguration \$virtualMachineConfiguration"

    static final String IGNORE_SET_VM_CONFIGURATION = "\$ignore = Set-SCVMConfiguration " + VM_CONFIGURATION_PARAM
    static final String IGNORE_UPDATE_VM_CONFIGURATION = "\$ignore = Update-SCVMConfiguration " + VM_CONFIGURATION_PARAM
    static final String RUN_ASYNCHRONOUSLY_STOP_ACTION = "-RunAsynchronously -StopAction \"SaveVM\""
    static final String ELSE_CLOSE = "} else {"
    static final String SET_VIRTUAL_NETWORK_ADAPTER = "Set-SCVirtualNetworkAdapter " +
            "-VirtualNetworkAdapter \$VirtualNetworkAdapter "
    static final String CLOUD_HARDWARE_PROFILE_START_ACTION = "-Cloud \$cloud " +
            "-HardwareProfile \$HardwareProfile -StartAction "
    static final String TURN_ON_VM_IF_RUNNING_WHEN_VS_STOPPED = "TurnOnVMIfRunningWhenVSStopped -StopAction SaveVM"
    static final String IF_STARTUP_GT_MIN_DYNAMIC_MEMORY = "If (\$startupMemory -gt \$minimumDynamicMemory) " +
            "{ \$minimumDynamicMemory = \$startupMemory };"
    static final String IF_STARTUP_GT_MAX_DYNAMIC_MEMORY = "If (\$startupMemory -gt \$maximumDynamicMemory) " +
            "{ \$maximumDynamicMemory = \$startupMemory };"

    static final String NEWLINE_CHAR = '\n'
    static final String ACCOUNT_ID = "accountId"
    static final String LIBRARY_SHARE = 'libraryShare'
    static final String FORWARD_SLASH = '/'
    static final String UNDERSCORE = '_'
    static final String DOT_GZ = '.gz'
    static final String DISKS_FOLDER = '\\Disks'

    static final String METADATA_JSON = 'metadata.json'
    static final String GENERATION1 = 'generation1'
    static final Integer TAKE_36 = 36
    static final Long SLEEP_MILLISECONDS = 1000L
    static final Long SLEEP_SECONDS = 5L
    static final String CREATION_FAILED = 'CreationFailed'
    static final Integer MAX_NOT_FOUND_ATTEMPTS = 10
    static final String VM_STATE_RUNNING = 'Running'
    static final String CONFIG_ISO = 'config.iso'
    static final String FIELD_NETWORK_INTERFACE = 'networkInterface'
    static final String MSG_NETWORK_REQUIRED = 'Network is required'
    static final String IP_MODE_STATIC = 'static'
    static final String MSG_ENTER_IP = 'You must enter an ip address'
    static final String FIELD_NETWORK_ID = 'networkId'
    static final String FIELD_NODE_COUNT = 'nodeCount'
    static final String POWERSHELL_NULL = '$null'

    static final String WINDOWS_8_64 = 'windows.8.64'
    static final String WINDOWS_SERVER_2008 = 'windows.server.2008'
    static final String WINDOWS_SERVER_2008_R2 = 'windows.server.2008.r2'
    static final String WINDOWS_SERVER_2012 = 'windows.server.2012'
    static final String WINDOWS = 'windows'
    static final String CENT = 'cent'
    static final String OTHER = 'other'
    static final String ORACLE_32 = 'oracle.32'
    static final String ORACLE_LINUX_64 = 'oracle.linux.64'
    static final String REDHAT = 'redhat'
    static final String LINUX_32 = 'linux.32'
    static final String LINUX_64 = 'linux.64'
    static final String SUSE = 'suse'
    static final String UBUNTU = 'ubuntu'
    static final String UBUNTU_64 = 'ubuntu.64'
    static final String WINDOWS_8 = 'windows.8'
    static final String WINDOWS_SERVER_2016 = 'windows.server.2016'
    static final String WINDOWS_SERVER_2019 = 'windows.server.2019'
    static final String WINDOWS_SERVER_2025 = 'windows.server.2025'
    static final Integer INDEX_NOT_FOUND = -1

    static String defaultRoot = 'C:\\morpheus'
    static final Map DEFAULT_RESULT = [success: false]
    static final Map DEFAULT_HOST_GROUP_RESULT = [success: false, hostGroups: []]
    static final Map DEFAULT_DATASTORE_RESULT = [success: false, datastores: []]
    static final Map DEFAULT_NETWORK_RESULT = [success: true, networks: []]
    static final Map DEFAULT_IMAGE_RESULT = [success: false, imageExists: false]

    MorpheusContext morpheusContext
    private final LogInterface log = LogWrapper.instance

    ScvmmApiService(MorpheusContext morpheusContext) {
        this.morpheusContext = morpheusContext
    }

    TaskResult executeCommand(String command, Map opts) {
        def winrmPort = opts.sshPort && opts.sshPort != 22 ? opts.sshPort : 5985
        def output = morpheusContext.executeWindowsCommand(opts.sshHost, winrmPort?.toInteger(),
                opts.sshUsername, opts.sshPassword, command, null, false).blockingGet()
        return output
    }

    void prepareNode(Map opts) {
        def zoneRoot = opts.zoneRoot ?: defaultRoot
        def command = "mkdir \"${zoneRoot}\\images\""
        executeCommand(command, opts)
        command = "mkdir \"${zoneRoot}\\export\""
        executeCommand(command, opts)
        command = "mkdir \"${opts.diskRoot}\""
        executeCommand(command, opts)
    }

    String generateCommandString(String command) {
        // FormatEnumeration causes lists to show ALL items
        // width value prevents wrapping
        // TODO make sure command does NOT end in a newline otherwise this fails
        return "\$FormatEnumerationLimit =-1; ${command} | ConvertTo-Json -Depth 3"
    }

    Map insertContainerImage(Map opts) {
        log.debug "insertContainerImage: ${opts}"
        def rtn = DEFAULT_IMAGE_RESULT.clone()
        def image = opts.image
        def imageName = image.name
        // def imageType = image.imageType
        def imageFolderName = formatImageFolder(imageName)
        def rootSharePath = opts.rootSharePath ?: getRootSharePath(opts)
        def tgtFolder = "${rootSharePath}\\images\\$imageFolderName"
        def vhdBlocks = getVhdBlocks(tgtFolder, opts)

        if (vhdBlocks.size() == 0) {
            rtn = handleImageUploadAndImport(opts, image, imageName, tgtFolder)
        } else {
            rtn.success = true
            rtn.imageId = vhdBlocks.first().ID
        }
        return rtn
    }

    protected List getVhdBlocks(String tgtFolder, Map opts) {
        def cmdString = generateCommandString("Get-SCVirtualHardDisk -VMMServer localhost |" +
                " where {\$_.SharePath -like \"${tgtFolder}\\*\"} | Select ID")
        def out = wrapExecuteCommand(cmdString, opts)
        if (!out.success) {
            throw new IllegalStateException("Error in getting Get-SCVirtualHardDisk")
        }
        return out.data ?: []
    }

    protected Map handleImageUploadAndImport(Map opts, Map image, String imageName, String tgtFolder) {
        def rtn = DEFAULT_IMAGE_RESULT.clone()
        def match = findImage(opts, imageName)
        log.info("findImage: ${match}")
        if (match.imageExists == false) {
            def transferResults = transferImage(opts, image.cloudFiles, imageName)
            log.debug "transferImage: ${transferResults}"
            rtn.success = transferResults.success == true
            if (!rtn.success) {
                rtn.msg = 'Error transferring image'
            }
        } else {
            rtn.success = true
        }
        if (rtn.success) {
            importPhysicalResourceAndHandleErrors(opts, imageName, tgtFolder, rtn)
        }
        return rtn
    }

    protected void importPhysicalResourceAndHandleErrors(Map opts, String imageName, String tgtFolder, Map rtn) {
        Boolean uploadSuccess = true
        def sourcePath = findImage(opts, imageName)?.imageName
        def commands = []
        commands << "\$ignore = Import-SCLibraryPhysicalResource -SourcePath \"$sourcePath\"" +
                " -SharePath \"$tgtFolder\" -OverwriteExistingFiles -VMMServer localhost"
        commands << "Get-SCVirtualHardDisk | where {\$_.SharePath -like \"${tgtFolder}\\*\"} | Select ID"
        def importRes = wrapExecuteCommand(generateCommandString(commands.join(CMD_SEPARATOR)), opts)
        rtn.imageId = importRes.data?.getAt(0)?.ID
        if (importRes.error != null) {
            log.info("Import-SCLibraryPhysicalResource failed for error: ${importRes?.error}. Trying with Copy-Item")
            uploadSuccess = handleCopyItemFallback(opts, sourcePath, tgtFolder, rtn)
        }
        if (uploadSuccess) {
            deleteImage(opts, imageName)
        } else {
            throw new IllegalStateException("Error in importing physical resource")
        }
    }

    protected Boolean handleCopyItemFallback(Map opts, String sourcePath, String tgtFolder, Map rtn) {
        def copyCommands = [
                "\$ignore = Copy-Item \"$sourcePath\" \"$tgtFolder\"",
                "\$ignore = Get-SCLibraryShare -VMMServer localhost | Read-SCLibraryShare",
                "Get-SCVirtualHardDisk | where {\$_.SharePath -like \"${tgtFolder}\\*\"} | Select ID",
        ]
        def copyResult = wrapExecuteCommand(generateCommandString(copyCommands.join(CMD_SEPARATOR)), opts)
        if (copyResult.error != null) {
            log.error("Error in Copy-Item: ${copyResult.error}")
            return false
        }
        rtn.imageId = copyResult.data?.getAt(0)?.ID
        return true
    }

    Map createServer(Map opts) {
        log.debug("createServer: ${opts}")
        def rtn = DEFAULT_RESULT.clone()
        try {
            def createCommands
            def launchCommand
            def createData
            def cloudInitIsoPath
            def removeTemplateCommands = []

            ComputeServer server
            initializeServerOptions(opts)
            def diskRoot = opts.diskRoot
            def imageFolderName = opts.serverFolder
            def diskFolder = "${diskRoot}\\${imageFolderName}"

            if (opts.isSysprep) {
                opts.unattendPath = importScript(opts.cloudConfigUser, diskFolder,
                        imageFolderName, [fileName: 'Unattend.xml'] + opts)
            }

            createCommands = buildCreateServerCommands(opts)
            removeTemplateCommands = buildRemoveTemplateCommands(createCommands)
            launchCommand = createCommands.launchCommand

            log.info("launchCommand: ${launchCommand}")
            createData = wrapExecuteCommand(generateCommandString(launchCommand), opts)
            log.debug "run server: ${createData}"

            handleCreateDataErrors(createData, rtn)

            server = morpheusContext.services.computeServer.get(opts.serverId)
            log.info "Create results: ${createData}"

            def newServerExternalId = extractServerExternalId(createData)
            if (!newServerExternalId) {
                throw new IllegalStateException(
                        "Failed to create VM with command: ${launchCommand}: ${createData.error}")
            }
            opts.externalId = newServerExternalId
            server.externalId = newServerExternalId
            server = morpheusContext.services.computeServer.save(server)

            def serverCreated = checkServerCreated(opts, opts.externalId)
            log.debug "Servercreated: ${serverCreated}"

            removeTemporaryTemplatesAndProfiles(removeTemplateCommands, opts)

            if (serverCreated.success == true) {
                loadControllerServer(opts)
                handleAdditionalDisks(opts)
                handleClonedVM(opts, rtn)
                def disks = buildDiskMapping(opts)
                resizeDisks(opts, disks)
                cloudInitIsoPath = handleCloudInit(opts, diskFolder, imageFolderName)
                startAndCheckServer(opts, disks, rtn)
            }

            if (cloudInitIsoPath) {
                rtn.deleteDvdOnComplete = [removeIsoFromDvd: true, deleteIso: cloudInitIsoPath]
            }
            if (opts.unattendPath) {
                deleteUnattend(opts, opts.unattendPath)
            }
            removeTemporaryTemplatesAndProfiles(removeTemplateCommands, opts)
        } catch (e) {
            log.error("createServer error: ${e}", e)
        }
        return rtn
    }

// --- Helper Methods ---

    protected void initializeServerOptions(Map opts) {
        opts.network = morpheusContext.services.network.get(opts.networkId)
        opts.zone = morpheusContext.services.cloud.get(opts.zoneId)
        loadControllerServer(opts)
    }

    protected List buildRemoveTemplateCommands(Map createCommands) {
        def removeTemplateCommands = []
        if (createCommands.hardwareProfileName) {
            removeTemplateCommands << "\$HWProfile = Get-SCHardwareProfile -VMMServer localhost |" +
                    " where { \$_.Name -eq \"${createCommands.hardwareProfileName}\"} ;" +
                    " \$ignore = Remove-SCHardwareProfile -HardwareProfile \$HWProfile;"
        }
        if (createCommands.templateName) {
            removeTemplateCommands << "\$template = Get-SCVMTemplate -VMMServer localhost" +
                    " -Name \"${createCommands.templateName}\";" +
                    "  \$ignore = Remove-SCVMTemplate -VMTemplate \$template -RunAsynchronously;"
        }
        return removeTemplateCommands
    }

    protected void handleCreateDataErrors(TaskResult createData, Map rtn) {
        if (createData.success != true) {
            if (createData.error?.contains('which includes generation 2')) {
                rtn.errorMsg = 'The virtual hard disk selected is not compatible with the template which' +
                        ' include generation 2 virtual machine functionality.'
            } else if (createData.error?.contains('which includes generation 1')) {
                rtn.errorMsg = 'The virtual hard disk selected is not compatible with the template which include ' +
                        'generation 1 virtual machine functionality.'
            }
            throw new IllegalStateException("Error in launching VM: ${createData}")
        }
    }

    protected String extractServerExternalId(TaskResult createData) {
        return createData.data && createData.data.size() == 1 &&
                createData.data[0].ObjectType?.toString() == OBJECT_TYPE_1 ? createData.data[0].ID : null
    }

    protected void removeTemporaryTemplatesAndProfiles(List removeTemplateCommands, Map opts) {
        if (removeTemplateCommands) {
            log.info("createServer - removing Temporary Templates and Hardware Profiles")
            def command = removeTemplateCommands.join(CMD_SEPARATOR)
            command += ARRAY_INIT
            wrapExecuteCommand(generateCommandString(command), opts)
        }
    }

    @SuppressWarnings('UnnecessaryToString')
    protected void handleAdditionalDisks(Map opts) {
        def vmDisk = listVirtualDiskDrives(opts, opts.externalId)
        if (vmDisk.success) {
            log.info("createServer - received current Disk configuration for VM ${opts.externalId}")
            log.info("createServer - additional volumes - " +
                    "opts.additionalTemplateDisks: ${opts.additionalTemplateDisks}")
            opts.additionalTemplateDisks?.each { diskConfig ->
                def diskSpec = [
                        vhdName  : "data-${UUID.randomUUID().toString()}",
                        vhdType  : null,
                        vhdFormat: null,
                        vhdPath  : null,
                        sizeMb   : (int) (diskConfig.diskSize.toLong()) / (ComputeUtility.ONE_MEGABYTE),
                ]
                def result = createAndAttachDisk(opts, diskSpec)
                log.debug("createServer - add disk result ${result}")
            }
            log.debug "createServer - finished with adding additionalDisks: ${opts.additionalTemplateDisks}"
        }
    }

    protected void handleClonedVM(Map opts, Map rtn) {
        if (opts.cloneVMId) {
            changeVolumeTypeForClonedBootDisk(opts, opts.cloneVMId, opts.externalId)
            if (opts.cloneBaseOpts && opts.cloneBaseOpts.cloudInitIsoNeeded) {
                rtn.cloneBaseResults = [
                        cloudInitIsoPath: importAndMountIso(
                                opts.cloneBaseOpts.cloudConfigBytes,
                                opts.cloneBaseOpts.diskFolder,
                                opts.cloneBaseOpts.imageFolderName,
                                opts.cloneBaseOpts.clonedScvmmOpts,
                        )
                ]
            }
        }
    }

    protected Map buildDiskMapping(Map opts) {
        def disks = [osDisk: [externalId: ''],
                     dataDisks: opts.dataDisks?.collect { disk -> [id: disk.id] }, diskMetaData: [:]]
        def diskDrives = listVirtualDiskDrives(opts, opts.externalId)
        def bookDiskIndex = findBootDiskIndex(diskDrives)
        diskDrives.disks?.eachWithIndex { disk, diskIndex ->
            if (diskIndex == bookDiskIndex) {
                disks.osDisk.externalId = disk.ID
                disks.diskMetaData[disk.ID] = [HostVolumeId: disk.HostVolumeId, FileShareId: disk.FileShareId,
                                               VhdID: disk.VhdID, Location: disk.VhdLocation,
                                               PartitionUniqueId: disk.PartitionUniqueId]
            } else {
                disks.dataDisks[diskIndex - 1].externalId = disk.ID
                disks.diskMetaData[disk.ID] = [HostVolumeId: disk.HostVolumeId, FileShareId: disk.FileShareId,
                                               dataDisk: true, VhdID: disk.VhdID, Location: disk.VhdLocation,
                                               PartitionUniqueId: disk.PartitionUniqueId]
            }
            log.debug("createServer - instance volume metadata (disks) : ${disks}")
        }
        return disks
    }

    protected void resizeDisks(Map opts, Map disks) {
        // def diskRoot = opts.diskRoot
        // def imageFolderName = opts.serverFolder
        // def diskFolder = "${diskRoot}\\${imageFolderName}"
        def resizeResponse
        log.debug ".. about to resize disk ${opts.osDiskSize}"
        if (opts.osDiskSize) {
            def osDiskVhdID = disks.diskMetaData[disks.osDisk?.externalId]?.VhdID
            resizeResponse = resizeDisk(opts, osDiskVhdID, opts.osDiskSize)
            log.debug("createServer - resizeDisk Response ${resizeResponse}")
        }
        resizeDataDisksIfTemplate(opts, disks)
    }

    protected void resizeDataDisksIfTemplate(Map opts, Map disks) {
        if (opts.isTemplate && opts.templateId && opts.dataDisks) {
            disks.diskMetaData?.each { externalId, map ->
                def storageVolume = opts.dataDisks.find { d -> d.externalId == externalId }
                if (storageVolume) {
                    def diskVhdID = disks.diskMetaData[externalId]?.VhdID
                    def resizeResponse = resizeDisk(opts, diskVhdID, storageVolume.maxStorage)
                    log.debug("createServer - resizeDisk Response ${resizeResponse}")
                }
            }
        }
    }

    protected String handleCloudInit(Map opts, String diskFolder, String imageFolderName) {
        if (opts.cloudConfigBytes && !opts.isSysprep) {
            createDVD(opts)
            return importAndMountIso(opts.cloudConfigBytes, diskFolder, imageFolderName, opts)
        }
        return null
    }

    protected void startAndCheckServer(Map opts, Map disks, Map rtn) {
        log.info("Starting Server  ${opts.name}")
        startServer(opts, opts.externalId)
        log.info("SCVMM get Server Details ${opts.name}")
        def serverDetail = getServerDetails(opts, opts.externalId)
        if (serverDetail.success == true) {
            rtn.server = [name: opts.name, id: opts.externalId, VMId: serverDetail.server?.VMId,
                          disks: disks]
            rtn.success = true
        } else {
            rtn.server = [name: opts.name, id: opts.externalId, VMId: serverDetail.server?.VMId,
                          disks: disks]
        }
    }

    Map getServerDetails(Map opts, String externalId) {
        log.debug "getServerDetails: ${externalId}"
        def rtn = [success: false, server: null, networkAdapters: [], error: null]
        try {
            def out = wrapExecuteCommand(generateCommandString(
                    """\$vm = Get-SCVirtualMachine -VMMServer localhost -ID \"${externalId}\";
\$report = @()
if(\$vm) {
    \$networkAdapters = Get-SCVirtualNetworkAdapter -VMMServer localhost -VM \$vm | where { \$_.Enabled -eq \$true };
    \$data = New-Object PSObject -property @{
        ID=\$vm.ID
        VMId=\$vm.VMId
        Name=\$vm.Name
        Status=([Microsoft.VirtualManager.Utils.VMComputerSystemState]\$vm.Status).toString()
        VirtualMachineState=([Microsoft.VirtualManager.Utils.VMComputerSystemState]\$vm.VirtualMachineState).toString()
        VirtualHardDiskDrives=@(\$vm.VirtualDiskDrives.VirtualHardDisk.ID)
        VirtualDiskDrives=@(\$vm.VirtualDiskDrives.ID)
        ipAddress=''
        internalIp=''
    }
    foreach (\$na in \$networkAdapters) {
        foreach (\$ip in \$na.IPv4Addresses) {
            if([string]::IsNullOrEmpty(\$data.ipAddress)) {
                \$data.ipAddress = \$ip
                \$data.internalIp = \$ip
            }
        }
    }
    \$report += \$data
} else {
    \$data = New-Object PSObject -property @{
        Error='VM_NOT_FOUND'
    }
    \$report += \$data
}
\$report """
            ), opts)
            if (out.success) {
                def serverData = out.data?.size() > 0 ? out.data.first() : null
                if (serverData?.Error) {
                    rtn.error = serverData.Error
                } else {
                    rtn.server = serverData
                    rtn.success = out.success
                }
            }
        } catch (e) {
            log.error("getServerDetails error: ${e}", e)
        }
        return rtn
    }

    Map refreshVM(Map opts, String externalId) {
        def rtn = DEFAULT_RESULT.clone()
        try {
            def out = wrapExecuteCommand(generateCommandString(
                    """\$vm = Get-SCVirtualMachine -VMMServer localhost -ID \"${externalId}\";
\$ignore = Read-SCVirtualMachine -VM \$vm"""), opts)
            rtn.success = out.success
        } catch (e) {
            log.error("refreshVM error: ${e}", e)
        }
        return rtn
    }

    Map discardSavedState(Map opts, String externalId) {
        log.debug "discardSavedState: ${opts}, ${externalId}"
        def rtn = [success: false, server: null, networkAdapters: []]
        try {
            executeCommand("\$vm = Get-SCVirtualMachine -VMMServer localhost -ID \"${externalId}\";" +
                    " Use-SCDiscardSavedStateVM -VM \$vm;", opts)
        } catch (e) {
            log.error("discardSavedState error: ${e}", e)
        }
        return rtn
    }

    String extractWindowsServerVersion(String osName) {
        // Static map for Windows Server 2022 variants
        def server2022Map = [
                'standard core'     : WIN2022_STD_CORE,
                'standard desktop'  : '2022.std.desktop',
                'datacenter core'   : WIN2022_DC_CORE,
                'datacenter desktop': '2022.dc.desktop',
                'standard'          : WIN2022_STD_CORE,
                'datacenter'        : WIN2022_DC_CORE,
        ]
        // Normalize input
        def lowerName = osName.toLowerCase()

        // Check for 2022 and match variant
        if (lowerName.contains('2022')) {
            def matched = server2022Map.find { variant, code ->
                lowerName.contains(variant)
            }
            return matched ? "windows.server.${matched.value}" : "windows.server.2022"
        }

        // Fallback: extract year and return as-is
        def versionMatch = osName =~ /\b(20\d{2}|2008|2003)\b/
        def version = versionMatch.find() ? versionMatch.group(1) : '2012'
        return "windows.server.${version}"
    }

    Map getScvmmServerInfo(Map opts) {
        def rtn = DEFAULT_RESULT.clone()
        def command = 'hostname'
        def out = executeCommand(command, opts)
        log.debug("out: ${out.data}")
        rtn.hostname = cleanData(out.data)
        command = '(Get-ComputerInfo).OsName'
        out = executeCommand(command, opts)
        log.debug("out: ${out.data}")
        rtn.osName = cleanData(out.data)
        command = '(Get-CimInstance Win32_PhysicalMemory | Measure-Object -Property capacity -Sum).sum'
        out = executeCommand(command, opts)
        log.debug("out: ${out.data}")
        rtn.memory = cleanData(out.data, 'TotalPhysicalMemory')
        command = '(Get-CimInstance Win32_DiskDrive | Measure-Object -Property Size -Sum).sum'
        out = executeCommand(command, opts)
        log.debug("out: ${out.data}")
        rtn.disks = cleanData(out.data, 'Size')
        rtn.success = true
        return rtn
    }

    @SuppressWarnings(['LineLength', 'NoTabCharacter'])
    Map getCloud(Map opts) {
        def rtn = [success: false, cloud: null]
        def command = generateCommandString("""\$cloud = Get-SCCloud -VMMServer localhost | where { \$_.ID -eq \'${opts.zone.regionCode}\' }
\$report = @()
if(\$cloud) {
	\$data = New-Object PSObject -property @{
		ID=\$cloud.ID
		Name=\$cloud.Name
		CapabilityProfiles=@(\$cloud.CapabilityProfiles.Name)
	}
	\$report += \$data
}
\$report """)
        def out = wrapExecuteCommand(command, opts)
        log.debug("out: ${out.data}")
        if (out.success) {
            def cloudBlocks = out.data
            if (cloudBlocks) {
                rtn.cloud = cloudBlocks.first()
            }
            rtn.success = true
        }
        return rtn
    }

    Map getCapabilityProfiles(Map opts) {
        def rtn = [success: false, capabilityProfiles: null]
        def command = generateCommandString(
                "Get-SCCapabilityProfile -VMMServer localhost | Select ID,Name")
        def out = wrapExecuteCommand(command, opts)
        log.debug("out: ${out.data}")
        if (out.success) {
            def cloudBlocks = out.data
            if (cloudBlocks) {
                rtn.capabilityProfiles = cloudBlocks
            }
            rtn.success = true
        }
        return rtn
    }

    Map listClouds(Map opts) {
        def rtn = [success: false, clouds: []]
        def command = generateCommandString('Get-SCCloud -VMMServer localhost | Select ID, Name')
        def out = wrapExecuteCommand(command, opts)
        if (out.success) {
            rtn.clouds = out.data
            rtn.success = true
        }
        return rtn
    }

    @SuppressWarnings(['LineLength', 'TrailingWhitespace', 'InvertedIfElse', 'MethodSize'])
    Map listVirtualMachines(Map opts) {
        def rtn = [success: false, virtualMachines: []]
        def hostGroup = opts.zoneConfig?.hostGroup
        def hasMore = true
        def pageSize = DEFAULT_PAGE_SIZE
        def fetch = { offset ->
            def commandStr = """\$report = @()"""
            if (!hostGroup) {
                if (opts.zone.regionCode) {
                    commandStr += """
\$cloud = Get-SCCloud -VMMServer localhost | where { \$_.ID -eq \'${opts.zone.regionCode}\' } 
"""
                    commandStr += """
	\$VMs = Get-SCVirtualMachine -VMMServer localhost -Cloud \$cloud | where { \$_.Status -ne 'Missing' } | Sort-Object -Property ID | Select-Object -Skip $offset -First $pageSize
	"""
                } else {
                    commandStr += """
	\$VMs = Get-SCVirtualMachine -VMMServer localhost -All | Sort-Object -Property ID | Select-Object -Skip $offset -First $pageSize
	"""
                }
            } else {
                if (opts.zone.regionCode) {
                    commandStr += """
\$cloud = Get-SCCloud -VMMServer localhost | where { \$_.ID -eq \'${opts.zone.regionCode}\' } 
"""
                    commandStr += """
	\$VMs = Get-SCVirtualMachine -VMMServer localhost -Cloud \$cloud | where { \$_.Status -ne 'Missing' -and \$_.HostGroupPath -like '${
                        hostGroup
                    }*' } | Sort-Object -Property ID | Select-Object -Skip $offset -First $pageSize
	"""
                } else {
                    commandStr += """
	\$VMs = Get-SCVirtualMachine -VMMServer localhost | where { \$_.Status -ne 'Missing' -and \$_.HostGroupPath -like '${
                        hostGroup
                    }*' } | Sort-Object -Property ID | Select-Object -Skip $offset -First $pageSize
	"""
                }
            }
            commandStr += """
				foreach (\$VM in \$VMs) {
					\$data = New-Object PSObject -property @{
						ID=\$VM.ID
						ObjectType=\$VM.ObjectType.ToString()
						VMId=\$VM.VMId
						Name=\$VM.Name
						CPUCount=\$VM.CPUCount
						Memory=\$VM.Memory
						VirtualMachineState=([Microsoft.VirtualManager.Utils.VMComputerSystemState]\$VM.VirtualMachineState).toString()
						MemoryAvailablePercentage=\$VM.MemoryAvailablePercentage
						CPUUtilization=\$VM.CPUUtilization
						TotalSize=0
						UsedSize=0
						HostId=\$VM.HostId
						Disks=@()
						IpAddress=''
						InternalIp=''
						OperatingSystem=\$VM.OperatingSystem.Name
						OperatingSystemWindows=\$VM.OperatingSystem.IsWindows
						DynamicMemoryEnabled=\$VM.DynamicMemoryEnabled
						MemoryAssignedMB=\$VM.MemoryAssignedMB
						DynamicMemoryMinimumMB=\$VM.DynamicMemoryMinimumMB
						DynamicMemoryMaximumMB=\$VM.DynamicMemoryMaximumMB

					}
		
					\$VHDs = \$VM | Get-SCVirtualDiskDrive
					foreach (\$VHDconf in \$VHDs){
						\$VHD = \$VHDconf.VirtualHardDisk
						\$disk = New-Object PSObject -property @{
							ID=\$VHD.ID
							ObjectType=\$VHDConf.ObjectType.ToString()
							Name=\$VHD.Name
							VHDType=\$VHD.VHDType.ToString()
                            VHDFormat=\$VHD.VHDFormatType.ToString()
							Location=\$VHD.Location
							TotalSize=\$VHD.MaximumSize
							UsedSize=\$VHD.Size
							HostId=\$VHD.HostId
							HostVolumeId=\$VHD.HostVolumeId
							PartitionUniqueId=\$VHD.HostVolume.PartitionUniqueId
							VolumeType=([Microsoft.VirtualManager.Remoting.VolumeType]\$VHDconf.VolumeType).toString()
						}
						\$data.Disks += \$disk
						\$data.TotalSize += \$VHD.MaximumSize
						\$data.UsedSize += \$VHD.Size
					}

					\$VNAs = \$VM | Get-SCVirtualNetworkAdapter
					foreach (\$VNA in \$VNAs) {
						foreach (\$ip in \$VNA.IPv4Addresses) {
							if([string]::IsNullOrEmpty(\$data.IpAddress)) {
								\$data.IpAddress = \$ip
								\$data.InternalIp = \$ip
							}
						}
					}

					\$report +=\$data
				}
				\$report """

            def command = generateCommandString(commandStr)
            def out = wrapExecuteCommand(command, opts)
            log.debug("out: ${out.data}")
            if (out.success) {
                hasMore = (out.data != '' && out.data != null)
                if (out.data) {
                    rtn.virtualMachines += out.data
                }
                rtn.success = true
            } else {
                hasMore = false
            }
        }

        def currentOffset = 0
        while (hasMore) {
            fetch(currentOffset)
            currentOffset += pageSize
        }

        return rtn
    }

    Map listTemplates(Map opts) {
        def rtn = [success: false, templates: []]
        def commandStr = """\$report = @()
\$VMTemplates = Get-SCVMTemplate -VMMServer localhost -All | where { \$_.ID -ne \$_.Name -and \$_.Status -eq 'Normal'}
foreach (\$Template in \$VMTemplates) {
	\$data = New-Object PSObject -property @{
		ID=\$Template.ID
		ObjectType=\$Template.ObjectType.ToString()
		Name=\$Template.Name
		CPUCount=\$Template.CPUCount
		Memory=\$Template.Memory
		OperatingSystem=\$Template.OperatingSystem.Name
		TotalSize=0
		UsedSize=0
		Generation=\$Template.Generation
		Disks=@()
	}

	foreach (\$VHDconf in \$Template.VirtualDiskDrives){
		\$VHD = \$VHDconf.VirtualHardDisk
		\$disk = New-Object PSObject -property @{
			ID=\$VHD.ID
			Name=\$VHD.Name
			VHDType=\$VHD.VHDType.ToString()
            VHDFormat=\$VHD.VHDFormatType.ToString()
			Location=\$VHD.Location
			TotalSize=\$VHD.MaximumSize
			UsedSize=\$VHD.Size
			HostId=\$VHD.HostId
			HostVolumeId=\$VHD.HostVolumeId
			VolumeType=([Microsoft.VirtualManager.Remoting.VolumeType]\$VHDconf.VolumeType).toString()
		}
		\$data.Disks += \$disk
		\$data.TotalSize += \$VHD.MaximumSize
		\$data.UsedSize += \$VHD.Size
	}
	\$report += \$data
}

\$Disks = Get-SCVirtualHardDisk -VMMServer localhost
foreach (\$VHDconf in \$Disks) {
	\$data = New-Object PSObject -property @{
		ID=\$VHDconf.ID
		Name=\$VHDconf.Name
		Location=\$VHDconf.Location
		OperatingSystem=\$VHDconf.OperatingSystem.Name
		TotalSize=\$VHDconf.MaximumSize
		VHDFormatType= ([Microsoft.VirtualManager.Remoting.VHDFormatType]\$VHDconf.VHDFormatType).toString()
		UsedSize=0
		Disks=@()
	}
	\$disk = New-Object PSObject -property @{
		ID=\$VHDconf.ID
		ObjectType=\$VHDConf.ObjectType.ToString()
		Name=\$VHDconf.Name
		VHDType=\$VHD.VHDType.ToString()
        VHDFormat=\$VHD.VHDFormatType.ToString()
		Location=\$VHDconf.Location
		TotalSize=\$VHDconf.MaximumSize
		UsedSize=\$VHDconf.Size
		HostId=\$VHDconf.HostId
		HostVolumeId=\$VHDconf.HostVolumeId
	}
	\$data.Disks += \$disk
	\$report += \$data
}
\$report """
        def command = generateCommandString(commandStr)
        def out = wrapExecuteCommand(command, opts)
        log.debug("out: ${out.data}")
        if (out.success) {
            rtn.templates = out.data
            rtn.success = true
        }
        return rtn
    }

    Map listClusters(Map opts) {
        def rtn = [success: false, clusters: []]
        def commandStr = """\$report = @()
		\$Clusters = Get-SCVMHostCluster -VMMServer localhost
		foreach (\$Cluster in \$Clusters) {
			\$data = New-Object PSObject -property @{
				id=\$Cluster.ID
				name=\$Cluster.Name
				hostGroup=\$Cluster.HostGroup.Path
				sharedVolumes=@(\$Cluster.SharedVolumes.Name)
				description=\$Cluster.Description
			}
			\$report +=\$data
		}
		\$report """
        def command = generateCommandString(commandStr)
        def out = wrapExecuteCommand(command, opts)
        if (out.success) {
            rtn.clusters = out.data

            // Scope it down to the HostGroup for the zone (or ALL)
            def clusterScope = opts.zone.getConfigProperty('hostGroup')
            if (clusterScope) {
                rtn.clusters = rtn.clusters?.findAll { cluster -> cluster.hostGroup?.startsWith(clusterScope) }
            }

            rtn.success = true
        }

        return rtn
    }

    @SuppressWarnings('LineLength')
    Map internalListHostGroups(Map opts) {
        def rtn = DEFAULT_HOST_GROUP_RESULT.clone()
        def commandStr = """Get-SCVMHostGroup -VMMServer localhost | Select-Object @{Name="id";Expression={\$_.ID.Guid}}, @{Name="name";Expression={\$_.Name}}, @{Name="path";Expression={\$_.Path}}, @{Name="parent";Expression={\$_.ParentHostGroup.Name}}, @{Name="root";Expression={\$_.IsRoot}}"""

        def command = generateCommandString(commandStr)
        def out = wrapExecuteCommand(command, opts)
        if (out.success) {
            rtn.hostGroups = out.data
            rtn.success = true
        }
        return rtn
    }

    @SuppressWarnings('TrailingWhitespace')
    Map listLibraryShares(Map opts) {
        def rtn = [success: false, libraryShares: []]
        def command = """\$report = @()
\$shares = Get-SCLibraryShare -VMMServer localhost 
foreach(\$share in \$shares) {
  \$data = New-Object PSObject -property @{
    ID=\$share.ID
    Name=\$share.Name
    Path=\$share.Path
}
\$report += \$data
}
\$report"""

        def out = wrapExecuteCommand(generateCommandString(command), opts)
        if (out.success) {
            rtn.libraryShares = out.data
            rtn.success = true
        }
        return rtn
    }

    @SuppressWarnings('CouldBeElvis')
    Map listHostGroups(Map opts) {
        def rtn = DEFAULT_HOST_GROUP_RESULT.clone()
        if (opts.zone.regionCode) {
            def commandStr = """\$report = @()
\$clouds = Get-SCCloud -VMMServer localhost
foreach (\$cloud in \$clouds) {
  \$data = New-Object PSObject -property @{
    ID=\$cloud.ID
    HostGroup=@(\$cloud.HostGroup.Path)
  }
  \$report += \$data
}
\$report"""
            def command = generateCommandString(commandStr)
            def out = wrapExecuteCommand(command, opts)
            log.debug("out: ${out.data}")
            if (out.success) {
                def clouds = out.data
                def cloud = clouds?.find { c -> c.ID == opts.zone?.regionCode }
                def cloudHostGroupPaths = cloud?.HostGroup
                def hostGroups = internalListHostGroups(opts)?.hostGroups
                rtn.hostGroups = hostGroups?.findAll { hg ->
                    def foundMatch = false
                    def currentPath = hg.path
                    cloudHostGroupPaths?.each { cloudHostGroupPath ->
                        if (!foundMatch) {
                            foundMatch = isHostInHostGroup(currentPath, cloudHostGroupPath)
                        }
                    }
                    return foundMatch
                }
                rtn.success = true
            }
        } else {
            def hostGroupsResult = internalListHostGroups(opts)
            rtn.hostGroups = hostGroupsResult?.hostGroups
            rtn.success = hostGroupsResult.success
        }

        return rtn
    }

    @SuppressWarnings(['LineLength', 'TrailingWhitespace'])
    Map listHosts(Map opts) {
        def rtn = [success: false, hosts: []]

        def hasMore = true
        def pageSize = MAX_NOT_FOUND_ATTEMPTS
        def fetch = { offset ->
            def commandStr = """\$report = @()
			\$HostNodes = Get-SCVMHost -VMMServer localhost | Sort-Object -Property ID | Select-Object -Skip $offset -First $pageSize
			foreach (\$HostNode in \$HostNodes) {
				\$data = New-Object PSObject -property @{
					id=\$HostNode.ID
					name=\$HostNode.Name
					status=\$HostNode.OverallStateString
					computerName=\$HostNode.ComputerName
					description=\$HostNode.Description
					cpuReserve=\$HostNode.CPUPercentReserve
					cpuUtilization=\$HostNode.CpuUtilization
					hostGroup=\$HostNode.VMHostGroup.Path
					cluster=\$HostNode.HostCluster.Name
					vmPaths=\$HostNode.VMPaths
					enabled=\$HostNode.AvailableForPlacement
					cpuCount=\$HostNode.PhysicalCPUCount
					coresPerCpu=\$HostNode.CoresPerCPU
					diskReserve=\$HostNode.DiskSpaceReserveMB
					totalStorage=\$HostNode.TotalStorageCapacity
					availableStorage=\$HostNode.AvailableStorageCapacity
					usedStorage=\$HostNode.UsedStorageCapacity
					memoryReserve=\$HostNode.MemoryReserveMB
					diskPaths=\$HostNode.BaseDiskPaths
					totalMemory=\$HostNode.TotalMemory
					availableMemory=\$HostNode.AvailableMemory  
					os=\$HostNode.OperatingSystem.Name
					liveMigration=\$HostNode.SupportsLiveMigration
					remoteEnabled=\$HostNode.RemoteConnectEnabled
					reportPort=\$HostNode.RemoveConnectPort
					migrationSubnets=\$HostNode.MigrationSubne			
					tz=\$HostNode.TimeZone
					hypervVersion=\$HostNode.HyperVVersion
					maxMemoryPerVm=\$HostNode.MaximumMemoryPerVM
					gpus=\$HostNode.GPUs
					hyperVState=([Microsoft.VirtualManager.Remoting.ServiceState]\$HostNode.HyperVState).toString()
				}
				\$report +=\$data
			}
			\$report """

            // availableMemory is in MB
            // totalMemory is in bytes
            // totalStorage in bytes
            // availableStorage in bytes
            // usedStorage in bytes
            // cpuUtilization is percent

            def command = generateCommandString(commandStr)
            def out = wrapExecuteCommand(command, opts)
            if (out.success) {
                hasMore = (out.data != '' && out.data != null)
                if (out.data) {
                    rtn.hosts += out.data
                }
                rtn.success = true
            } else {
                hasMore = false
            }
        }

        def currentOffset = 0
        while (hasMore) {
            fetch(currentOffset)
            currentOffset += pageSize
        }

        return rtn
    }

    @SuppressWarnings(['LineLength', 'TrailingWhitespace'])
    Map listDatastores(Map opts) {
        def rtn = DEFAULT_DATASTORE_RESULT.clone()

        def hasMore = true
        def pageSize = DEFAULT_PAGE_SIZE
        def fetchStorageVolumes = { offset ->
            def commandStr = """\$report = @()
				\$cloud = Get-SCCloud -VMMServer localhost | where { \$_.ID -eq \'${opts.zone.regionCode ?: ''}\' } 
				\$StorageVolumes = Get-SCStorageVolume -VMMServer localhost | Sort-Object -Property ID | Select-Object -Skip $offset -First $pageSize
				If (-not ([string]::IsNullOrEmpty(\$cloud))) {
					\$AllowedClassifications = \$cloud.StorageClassifications.Name
					\$StorageVolumes = \$StorageVolumes | where { \$_.Classification -in \$AllowedClassifications }
				}
				foreach (\$StorageVolume in \$StorageVolumes) {
					\$data = New-Object PSObject -property @{
						id=\$StorageVolume.ID
						name=\$StorageVolume.Name
						storageVolumeID=\$StorageVolume.StorageVolumeID
						partitionUniqueID=\$StorageVolume.PartitionUniqueID
						capacity=\$StorageVolume.Capacity
						freeSpace=\$StorageVolume.FreeSpace
						isClusteredSharedVolume=\$StorageVolume.IsClusterSharedVolume
						vmHost=\$StorageVolume.VMHost.Name
						isAvailableForPlacement=\$StorageVolume.IsAvailableForPlacement
						hostDisk=\$StorageVolume.HostDisk.Name
						size=\$StorageVolume.Size
						mountPoints=\$StorageVolume.MountPoints
					}
					\$report +=\$data
				}
				\$report """

            def command = generateCommandString(commandStr)
            def out = wrapExecuteCommand(command, opts)
            log.debug "listDatastores results: ${out}"
            if (out.success) {
                hasMore = (out.data != '' && out.data != null)
                if (out.data) {
                    rtn.datastores += out.data
                }
                rtn.success = true
            } else {
                log.debug "Return not successful: ${out}"
                hasMore = false
            }
        }

        def currentOffset = 0
        while (hasMore) {
            fetchStorageVolumes(currentOffset)
            currentOffset += pageSize
        }

        return rtn
    }

    @SuppressWarnings(['LineLength', 'TrailingWhitespace'])
    Map listRegisteredFileShares(Map opts) {
        def rtn = DEFAULT_DATASTORE_RESULT.clone()

        def hasMore = true
        def pageSize = DEFAULT_PAGE_SIZE
        def fetchFileShares = { offset ->
            def commandStr = """\$report = @()
\$FileShares = Get-SCStorageFileShare -VMMServer localhost | Sort-Object -Property ID | Select-Object -Skip $offset -First $pageSize
foreach (\$FileShare in \$FileShares){
    \$fileShareDisk = New-Object PSObject -property @{
        ID=\$FileShare.ID
        Name=\$FileShare.Name
        Capacity=\$FileShare.Capacity
        FreeSpace=\$FileShare.FreeSpace
        IsAvailableForPlacement=\$FileShare.IsAvailableForPlacement
        MountPoints=\$FileShare.MountPoints
        ClusterAssociations=@()
        HostAssociations=@()
    }
    
    foreach (\$CA in \$FileShare.ClusterAssociations) {
        \$tmpCluster = New-Object PSObject -property @{
            ClusterID=\$CA.Cluster.ID
            ClusterName=\$CA.Cluster.Name
            HostID=\$CA.Host.ID
            HostName=\$CA.Host.Name
        }
        \$fileShareDisk.ClusterAssociations += \$tmpCluster
    }

    foreach (\$HA in \$FileShare.HostAssociations) {
        \$tmpHost = New-Object PSObject -property @{
            HostID=\$HA.Host.ID
            HostName=\$HA.Host.Name
        }
        \$fileShareDisk.HostAssociations += \$tmpHost
    }

    \$report += \$fileShareDisk
}
\$report """
            def command = generateCommandString(commandStr)
            def out = wrapExecuteCommand(command, opts)
            log.debug "listDatastores results: ${out}"
            if (out.success) {
                hasMore = (out.data != '' && out.data != null)
                if (out.data) {
                    rtn.datastores += out.data
                }
                rtn.success = true
            } else {
                log.debug "Return not successful: ${out}"
                hasMore = false
            }
        }

        def currentOffset = 0
        while (hasMore) {
            fetchFileShares(currentOffset)
            currentOffset += pageSize
        }

        return rtn
    }

    @SuppressWarnings(['LineLength', 'NestedBlockDepth'])
    Map listAllNetworks(Map opts) {
        def rtn = DEFAULT_NETWORK_RESULT.clone()
        try {
            def command = generateCommandString(GET_SC_LOGICAL_NETWORK_CMD)
            def out = wrapExecuteCommand(command, opts)
            log.debug("listNetworks: ${out}")
            if (out.success && out.exitCode == EXIT_CODE_SUCCESS && out.data?.size() > 0) {
                def logicalNetworks = out.data
                command = generateCommandString("""\$report = @()
\$networks = Get-SCVMNetwork -VMMServer localhost | Select ID,Name,LogicalNetwork | Sort-Object -Property ID | Select-Object -First 1
foreach (\$network in \$networks) {
	\$data = New-Object PSObject -property @{
		ID=\$network.ID
		Name=\$network.Name
		LogicalNetwork=\$network.LogicalNetwork.Name
	}
	\$report += \$data
}
\$report """)
                out = wrapExecuteCommand(command, opts)
                log.debug("get of networks: ${out}")
                if (out.success && out.exitCode == EXIT_CODE_SUCCESS) {
                    if (out.data) {
                        log.debug("list logical networks: ${out}")
                        def networks = out.data
                        logicalNetworks?.each { logicalNetwork ->
                            rtn.networks += networks.findAll { n -> n.LogicalNetwork == logicalNetwork.Name }
                        }
                    }
                } else {
                    if (out.exitCode != EXIT_CODE_SUCCESS) {
                        log.info "Fetch of networks resulted in non-zero exit value: ${out}"
                    }
                }
            } else {
                log.info "Error in fetching network info: ${out}"
                rtn.success = false
            }
        } catch (ex) {
            rtn.success = false
            rtn.msg = "Error fetching all networks list from SCVMM Host"
            log.error("An error occurred attempting to list all networks on SCVMM Host: ${ex.message}", ex)
        }
        return rtn
    }

    @SuppressWarnings('LineLength')
    Map removeOrphanedResourceLibraryItems(Map opts) {
        log.debug "removeOrphanedResourceLibraryItems: ${opts}"

        def command = """
\$ISOs = Get-SCISO -VMMServer localhost | where { (\$_.State -match "Missing") -and (\$_.Directory.ToString() -like "*morpheus_server_*") }
\$ignore = \$ISOs | Remove-SCISO -RunAsynchronously

\$Scripts = Get-SCScript -VMMServer localhost | where { (\$_.State -match "Missing") -and (\$_.Directory.ToString() -like "*morpheus_server_*") }
\$ignore = \$Scripts | Remove-SCScript -RunAsynchronously"""
        def out = wrapExecuteCommand(generateCommandString(command), opts)
        if (!out.success) {
            log.warn "Error in removeOrphanedResourceLibraryItems: ${out}"
        }
    }

    @SuppressWarnings(['LineLength', 'TrailingWhitespace', 'NestedBlockDepth'])
    Map listNetworks(Map opts) {
        def rtn = DEFAULT_NETWORK_RESULT.clone()
        try {
            def hasMore = true
            def pageSize = DEFAULT_PAGE_SIZE
            def fetch = { offset ->
                // Must grab the logical networks for the cloud.. then fetch the VMNetworks for each logical network
                def command
                if (opts.zone.regionCode) {
                    def commandStr = """
\$cloud = Get-SCCloud -VMMServer localhost | where { \$_.ID -eq \'${opts.zone.regionCode}\' }
Get-SCLogicalNetwork -VMMServer localhost -Cloud \$cloud | Select ID,Name"""
                    command = generateCommandString(commandStr)
                } else {
                    command = generateCommandString(GET_SC_LOGICAL_NETWORK_CMD)
                }

                def out = wrapExecuteCommand(command, opts)
                log.debug("listNetworks: ${out}")
                if (out.success && out.exitCode == EXIT_CODE_SUCCESS && out.data?.size() > 0) {
                    def logicalNetworks = out.data
                    command = generateCommandString("""\$report = @()
\$networks = Get-SCVMNetwork -VMMServer localhost | where {\$_.IsolationType -ne "NoIsolation"} | Select ID,Name,LogicalNetwork,VMSubnet | Sort-Object -Property ID | Select-Object -Skip $offset -First $pageSize
foreach (\$network in \$networks) {
	\$logicalNetwork = \$network.LogicalNetwork
	\$data = New-Object PSObject -property @{
		ID=\$network.ID
		Name=\$network.Name
		LogicalNetwork=\$logicalNetwork.Name
		Subnets=@()
	}
	\$subnets = \$network.VMSubnet.SubnetVLans | where {\$_.IsVlanEnabled -eq "True" }
	foreach (\$vlan in \$subnets) {	
		\$subnetData = New-Object PSObject -property @{
			ID=\$network.VMSubnet.ID.toString() + "-" + \$vlan.VLanId
			Name=\$network.VMSubnet.Name
			NetworkName=\$logicalNetwork.Name
			LogicalNetworkID=\$logicalNetwork.ID
			Subnet=\$vlan.Subnet
			VLanID=\$vlan.VLanId
		}
		\$data.Subnets += \$subnetData
	}
	\$report += \$data
}
\$report""")
                    out = wrapExecuteCommand(command, opts)
                    log.debug("get of networks: ${out}")
                    if (out.success && out.exitCode == EXIT_CODE_SUCCESS) {
                        hasMore = (out.data != '' && out.data != null)
                        if (out.data) {
                            log.debug("list logical networks: ${out}")
                            def networks = out.data

                            logicalNetworks?.each { logicalNetwork ->
                                rtn.networks += networks.findAll { net -> net.LogicalNetwork == logicalNetwork.Name }
                            }
                        }
                    } else {
                        if (out.exitCode != EXIT_CODE_SUCCESS) {
                            log.info "Fetch of networks resulted in non-zero exit value: ${out}"
                        }
                        hasMore = false
                    }
                } else {
                    log.info "Error in fetching network info: ${out}"
                    hasMore = false
                    rtn.success = false
                }
            }
            def currentOffset = 0
            while (hasMore) {
                fetch(currentOffset)
                currentOffset += pageSize
            }
        } catch (ex) {
            rtn.success = false
            rtn.msg = "Error syncing networks list from SCVMM Host"
            log.error("An error occurred attempting to list networks on SCVMM Host: ${ex.message}", ex)
        }
        return rtn
    }

    @SuppressWarnings(['LineLength', 'NestedBlockDepth'])
    Map listNoIsolationVLans(Map opts) {
        def rtn = DEFAULT_NETWORK_RESULT.clone()
        try {
            def hasMore = true
            def pageSize = DEFAULT_PAGE_SIZE
            def fetch = { offset ->
                def command
                if (opts.zone.regionCode) {
                    def commandStr = """
\$cloud = Get-SCCloud -VMMServer localhost | where { \$_.ID -eq \'${opts.zone.regionCode}\' }
Get-SCLogicalNetwork -VMMServer localhost -Cloud \$cloud | Select ID,Name"""
                    command = generateCommandString(commandStr)
                } else {
                    command = generateCommandString(GET_SC_LOGICAL_NETWORK_CMD)
                }
                def out = wrapExecuteCommand(command, opts)
                log.debug("listNetworks: ${out}")
                if (out.success && out.exitCode == EXIT_CODE_SUCCESS && out.data?.size() > 0) {
                    def logicalNetworks = out.data
                    command = generateCommandString("""\$report = @()
\$logicalNetworks = Get-SCLogicalNetworkDefinition -VMMServer localhost | where {\$_.IsolationType -eq "None"} | Sort-Object -Property ID | Select-Object -Skip $offset -First $pageSize
foreach (\$logicalNetwork in \$logicalNetworks) {
    if (-not \$logicalNetwork -or -not \$logicalNetwork.LogicalNetwork) { continue }
    \$network = Get-SCVMNetwork -VMMServer localhost -LogicalNetwork \$logicalNetwork.LogicalNetwork
    if (-not \$network) { continue }
    \$subnets = \$logicalNetwork.SubnetVLans | where { \$_.IsVlanEnabled -eq \$true }
    if (-not \$subnets) { continue }
    foreach (\$vlan in \$subnets) {
        if (-not \$vlan.Subnet -or -not \$vlan.VLanId) { continue }

        \$data = New-Object PSObject -Property @{
            ID               = "\$(\$network.ID)-\$(\$vlan.VLanId)"
            Name             = "\$(\$vlan.VLanId)-\$(\$logicalNetwork.LogicalNetwork.Name)"
            NetworkName      = \$logicalNetwork.Name
            LogicalNetworkID = \$logicalNetwork.LogicalNetwork.ID
            Subnet           = \$vlan.Subnet
            VLanID           = \$vlan.VLanId
        }
        \$report += \$data
    }
}
\$report""")
                    out = wrapExecuteCommand(command, opts)
                    log.debug("get of networks: ${out}")
                    if (out.success && out.exitCode == EXIT_CODE_SUCCESS) {
                        hasMore = (out.data != '' && out.data != null)
                        if (out.data) {
                            log.debug("list logical networks: ${out}")
                            def networks = out.data

                            logicalNetworks?.each { logicalNetwork ->
                                rtn.networks += networks.findAll { network ->
                                    network.LogicalNetworkID == logicalNetwork.ID
                                }
                            }
                        }
                    } else {
                        if (out.exitCode != EXIT_CODE_SUCCESS) {
                            log.info "Fetch of networks resulted in non-zero exit value: ${out}"
                        }
                        hasMore = false
                    }
                } else {
                    log.info "Error in fetching network info: ${out}"
                    hasMore = false
                    rtn.success = false
                }
            }
            def currentOffset = 0
            while (hasMore) {
                fetch(currentOffset)
                currentOffset += pageSize
            }
        } catch (ex) {
            rtn.success = false
            rtn.msg = "Error syncing isolation networks list from SCVMM Host"
            log.error("An error occurred attempting to list isolation networks on SCVMM Host: ${ex.message}", ex)
        }
        return rtn
    }

    @SuppressWarnings(['LineLength', 'TrailingWhitespace'])
    Map listNetworkIPPools(Map opts) {
        def rtn = [success: true, ipPools: [], networkMapping: []]
        try {
            // Fetch all the Static IP Address pools
            def command = generateCommandString("""\$report = @()   
\$staticPools = Get-SCStaticIPAddressPool -VMMServer localhost
foreach (\$staticPool in \$staticPools) {	
	\$data = New-Object PSObject -property @{
		ID=\$staticPool.ID
		Name=\$staticPool.Name
		NetworkID=\$staticPool.VMSubnet.VMNetwork.ID
		LogicalNetworkID=\$staticPool.LogicalNetworkDefinition.LogicalNetwork.ID
		Subnet=\$staticPool.Subnet
		SubnetID=\$staticPool.VMSubnet.ID
		DefaultGateways=@(\$staticPool.DefaultGateways.IPAddress)
		TotalAddresses=\$staticPool.TotalAddresses
		AvailableAddresses=\$staticPool.AvailableAddresses
		DNSSearchSuffixes=\$staticPool.DNSSearchSuffixes
		DNSServers=\$staticPool.DNSServers
		IPAddressRangeStart=\$staticPool.IPAddressRangeStart
		IPAddressRangeEnd=\$staticPool.IPAddressRangeEnd
	}
	\$report += \$data
}
\$report """)

            def out = wrapExecuteCommand(command, opts)
            log.debug("listNetworkIPPools: ${out}")
            if (out.success && out.exitCode == EXIT_CODE_SUCCESS) {
                rtn.ipPools += out.data ?: []
            } else {
                rtn.success = false
            }

            if (rtn.success) {
                // Also fetch the mapping of networks to logical networks which is needed when mapping ip pools to networks
                command = generateCommandString("""\$report = @()   
\$networks = Get-SCVMNetwork -VMMServer localhost | Select ID,Name,LogicalNetwork
foreach (\$network in \$networks) {
	\$data = New-Object PSObject -property @{
		ID=\$network.ID
		Name=\$network.Name
		LogicalNetwork=\$network.LogicalNetwork.Name
		LogicalNetworkId=\$network.LogicalNetwork.ID
	}
	\$report += \$data
}
\$report """)
                out = wrapExecuteCommand(command, opts)
                log.debug("fetch network mapping: ${out}")
                if (out.success && out.exitCode == EXIT_CODE_SUCCESS) {
                    rtn.networkMapping += out.data ?: []
                } else {
                    rtn.success = false
                }
            }
        } catch (ex) {
            rtn.success = false
            rtn.msg = "Error syncing ip pools list from SCVMM Host"
            log.error("An error occurred attempting to list ip pools on SCVMM Host: ${ex.message}", ex)
        }
        return rtn
    }

    @SuppressWarnings('LineLength')
    Map  reserveIPAddress(Map opts, String poolId) {
        def rtn = [success: true, ipAddress: []]
        try {
            def command = generateCommandString("""\$ippool = Get-SCStaticIPAddressPool -VMMServer localhost -ID \"$poolId\"; Grant-SCIPAddress -GrantToObjectType \"VirtualMachine\" -StaticIPAddressPool \$ippool | Select-Object ID,Address""")
            def out = wrapExecuteCommand(command, opts)
            log.debug("reserveIPAddress: ${out}")
            if (out.success && out.exitCode == EXIT_CODE_SUCCESS) {
                def ipAddressBlock = out.data
                if (ipAddressBlock) {
                    rtn.ipAddress = ipAddressBlock.first()
                }
            } else {
                rtn.success = false
            }
        } catch (ex) {
            rtn.success = false
            rtn.msg = "Error reserving an IP address from SCVMM"
            log.error("Error reserving an IP address from SCVMM: ${ex.message}", ex)
        }
        return rtn
    }

    Map releaseIPAddress(Map opts, String poolId, String ipId) {
        def rtn = [success: true]
        try {
            def command = generateCommandString(
                    "\$ippool = Get-SCStaticIPAddressPool -VMMServer localhost -ID \"$poolId\";" +
                            " \$ipaddress = Get-SCIPAddress -ID \"$ipId\"; \$ignore = Revoke-SCIPAddress \$ipaddress")
            def out = wrapExecuteCommand(command, opts)
            log.info("releaseIPAddress: ${out}")
            if (out.success && out.exitCode == EXIT_CODE_SUCCESS) {
                rtn.success = true
                // Do nothing
            } else {
                if (out.error?.contains("Unable to find the specified allocated IP address")) {
                    // It has already been deleted somehow
                    rtn.success = true
                } else {
                    rtn.success = false
                }
            }
        } catch (ex) {
            rtn.success = false
            rtn.msg = "Error revoking an IP address from SCVMM"
            log.error("Error revoking an IP address from SCVMM: ${ex.message}", ex)
        }

        return rtn
    }

    @SuppressWarnings('TrailingWhitespace')
    Map listVirtualDiskDrives(Map opts, String externalId, String name = null) {
        log.info("listVirtualDiskDrives - Getting Virtual Disk info for VMId :${externalId}")
        def rtn = [success: false, disks: []]

        String templateCmd = '''
		#Morpheus will replace items in <%   %>
		$vmId = "<%vmid%>"
		$vhdName = "<%vhdname%>"
		$VM =  Get-SCVirtualMachine -VMMServer localhost -ID $vmId -ErrorAction SilentlyContinue
		if ($VM) {
			if ($vhdName) {
				$disks = Get-SCVirtualDiskDrive -VM $VM | Where-Object {$_.VirtualHardDisk -like $vhdName}
			} else {
				$disks = Get-SCVirtualDiskDrive -VM $VM
			}
		} else {
			$disks = @()
		}
		$report = @()
		foreach ($disk in $disks) {
			$data = [PSCustomObject]@{
				ID=$disk.ID
				Name=$disk.Name
				VolumeType=$disk.VolumeType.ToString()
				BusType=$disk.BusType.ToString()
				Bus=$disk.Bus
				Lun=$disk.Lun
				VhdID=$disk.VirtualHardDisk.ID
				VhdName=$Disk.VirtualHardDisk.Name
				VhdType=$disk.VirtualHardDisk.VHDType.ToString()
				VhdFormat=$disk.VirtualHardDisk.VHDFormatType.ToString()
				VhdLocation=$disk.VirtualHardDisk.Location
				HostVolumeId=$disk.VirtualHardDisk.HostVolumeId
				FileShareId=$disk.VirtualHardDisk.FileShare.ID
				PartitionUniqueId=$disk.VirtualHardDisk.HostVolume.PartitionUniqueId
			}
			$report += $data
		}
		$report 
		'''
        String cmd = templateCmd.stripIndent().trim()
                .replace(VMID_PLACEHOLDER, externalId)
                .replace(VHDNAME_PLACEHOLDER, name ?: "")
        // Execute
        def out = wrapExecuteCommand(generateCommandString(cmd), opts)
        if (out.success) {
            rtn.disks = out.data
            rtn.success = true
        }
        return rtn
    }

    @SuppressWarnings('TrailingWhitespace')
    Map resizeDisk(Map opts, String diskId, Long diskSizeBytes) {
        log.info("resizeDisk - ${diskId} ${diskSizeBytes}")
        String templateCmd = """\$vmId = "<%vmid%>"
		\$diskId = "<%diskid%>"
		\$newSize = <%sizegb%>
		#No replacement code after here
		\$report = [PSCustomObject]@{success=\$true;jobId=\$null;errOut=\$null}
		\$VM = Get-SCVirtualMachine -VMMServer localhost -ID \$vmID
		\$vDisk = Get-SCVirtualDiskDrive -VM \$VM | Where-Object  {\$_.VirtualHardDiskId -eq \$diskId}
		if (\$vDisk) {
			#Check format - can it be expanded
			\$vhd = \$vDisk.VirtualHardDisk
			if (\$vhd.ParentDisk) {
				\$report.success = \$false
				\$report.errOut="Cannot Resize a Differencing Disk or a Disk with Checkpoints"
			} else {
				\$expandParams=@{
					RunAsynchronously=\$true;
					VirtualDiskDrive=\$vDisk;
					VirtualHardDiskSizeGB=\$newSize;
					JobVariable="expandJob"
					ErrorAction="Stop"
				}
				try {
					\$expandedDisk = Expand-SCVirtualDiskDrive @expandParams
					\$report.jobId = \$expandJob.ID
				}
				catch {
					\$report.success = \$false
					\$report.errout = "Expand-SCVirtualDiskDrive raised exception: {0}" -f \$_.Exception.Message
				}
			}
		} else {
			\$report.success = \$false
			\$report.errOut = "Cannot locate VirtualHardDisk ID {0}" -f \$diskId
		}
		\$report 
		"""
        String resizeCmd = templateCmd.stripIndent().trim()
                .replace(VMID_PLACEHOLDER, opts.externalId)
                .replace("<%diskid%>", diskId ?: "")
                .replace("<%sizegb%>", "${(int) (diskSizeBytes.toLong() / ComputeUtility.ONE_GIGABYTE)}")

        log.debug "resizeDisk: ${resizeCmd}"
        def resizeResults = wrapExecuteCommand(generateCommandString(resizeCmd), opts)
        // resizeResults.data is json payload array - want only the first item
        if (resizeResults.data) {
            def resizeStatus = resizeResults.data.first()
            if (resizeStatus?.success) {
                // Wait on the jobId to complete
                def waitResults = waitForJobToComplete(opts, resizeStatus.jobId)
                return waitResults
            }
            log.error("resizeDisk - Error resizing disk. Message : ${resizeStatus.errOut}")
            return resizeStatus
        }
        log.warn("resizeDisk - rpc disk not return a usable response - ${resizeResults}")
        return [success: false, errOut: "resizeDisk - did not receive expected response from rpc"]
    }

    @SuppressWarnings(['TrailingWhitespace', 'UnnecessaryToString', 'MethodSize'])
    Map createAndAttachDisk(Map opts, Map diskSpec, Boolean returnDiskDrives = true) {
        LogWrapper.instance.info("createAndAttachDisk - Adding new Virtual SCSI Disk VHDType:${diskSpec}")
        String templateCmd = '''
        #Morpheus will replace items in <%   %>
        $vmId = "<%vmid%>"
        $vhdName = "<%vhdname%>"
        $sizeMB = <%sizemb%>
        $vhdType = "<%vhdtype%>"
        $vhdFormat = "<%vhdformat%>"
        $vhdPath = "<%vhdpath%>"
        #No replacement code after here
        $report = [PSCustomObject]@{success=$false;BUS=0;LUN=0;vhdId=$null;jobStatus=$null;errOut=$null}
        try {
            $VM = Get-SCVirtualMachine -VMMServer localhost -ID $vmId -ErrorAction Stop
        }
        catch {
            $report.success = $false
            $report.errOut = $_.Exception.Message
            return $report
        }
        
        if ($VM) {
            # Default to FixedSize if not specified
            if ($vhdType -eq "") {$vhdType = "FixedSize"}
            if ($vhdFormat -eq "") {$vhdFormat = if ($VM.Generation -eq 2) {"VHDX"} else {"VHD"}}
            
            # Get both disk drives and DVD drives to check all occupied slots
            $diskDrives = Get-SCVirtualDiskDrive -VM $VM
            $dvdDrives = Get-SCVirtualDVDDrive -VM $VM
            
            # Track occupied slots in a hashtable for efficient lookup
            $occupiedSlots = @{}
            
            # Mark disk drive slots as occupied
            foreach ($drive in $diskDrives) {
                if ($drive.BusType -eq "SCSI") {
                    $slotKey = "$($drive.Bus),$($drive.Lun)"
                    $occupiedSlots[$slotKey] = $true
                }
            }
            
            # Mark DVD drive slots as occupied
            foreach ($drive in $dvdDrives) {
                if ($drive.BusType -eq "SCSI") {
                    $slotKey = "$($drive.Bus),$($drive.Lun)"
                    $occupiedSlots[$slotKey] = $true
                }
            }
    
            # Find first available SCSI slot
            $Bus = 0
            $Lun = 0
            $foundSlot = $false
    
            # Try up to 4 buses with 64 slots each
            for ($b = 0; $b -lt 4 -and -not $foundSlot; $b++) {
                for ($l = 0; $l -lt 64 -and -not $foundSlot; $l++) {
                    $slotKey = "$b,$l"
                    if (-not $occupiedSlots.ContainsKey($slotKey)) {
                        $Bus = $b
                        $Lun = $l
                        $foundSlot = $true
                        break
                    }
                }
            }
    
            # If no slots found, report error
            if (-not $foundSlot) {
                $report.success = $false
                $report.errOut = "No available SCSI slots found on any controller"
                return $report
            }
    
            $addDiskParams = @{
                VMMServer="localhost";
                VM=$VM;
                FileName=$vhdName;
                SCSI=$true;
                Bus=$Bus;
                Lun=$Lun;
                JobVariable="AddDiskJob";
                VirtualHardDiskSizeMB=$sizeMB;
                VirtualHardDiskFormatType=$vhdFormat;
                VolumeType="None";
                ErrorAction="Stop"
            }
            if ($vhdPath -ne "") {$addDiskParams.Add("Path",$vhdPath)}
            if ($vhdType -eq "FixedSize") {$addDiskParams.Add("Fixed",$true)}
            if ($vhdType -eq "DynamicallyExpanding") {$addDiskParams.Add("Dynamic",$true)}
            try {
                $VHD=New-SCVirtualDiskDrive @addDiskParams
                $report.success = $true
                $report.BUS = $Bus
                $report.LUN = $Lun
                $report.jobStatus = $AddDiskJob.Status.ToString()
                $report.vhdId = $VHD.Id
            }
            Catch {
                #Dismiss any failed jobs
                $dismiss = Repair-SCVirtualMachine -VM $VM -Dismiss -Force
                $report.success=$false
                $report.errOut = $_.Exception.Message
                $report.jobStatus = if ($AddDiskJob) { $AddDiskJob.Status.ToString() } else { "Failed" }
            }
        }
        $report
        '''
        def addDiskCmd = templateCmd.stripIndent().trim()
                .replace(VMID_PLACEHOLDER, opts.externalId ?: "")
                .replace(VHDNAME_PLACEHOLDER, diskSpec.vhdName ?: "data-${UUID.randomUUID().toString()}")
                .replace("<%sizemb%>", diskSpec.sizeMb.toString())
                .replace("<%vhdtype%>", diskSpec.vhdType ?: "")
                .replace("<%vhdformat%>", diskSpec.vhdFormat ?: "")
                .replace("<%vhdpath%>", diskSpec.vhdPath ?: "")
        // Execute
        def out = wrapExecuteCommand(generateCommandString(addDiskCmd), opts)
        if (out.success && returnDiskDrives) {
            def listResults = listVirtualDiskDrives(opts, opts.externalId, diskSpec.vhdName)
            return [success: listResults.success, disk: listResults.disks.first()]
        }
    }

    String getDiskName(Integer index, String platform = 'linux') {
        if (platform == WINDOWS) {
            return "disk ${index + 1}"
        }
        return DISK_NAME_LIST[index]
    }

    TaskResult removeDisk(Map opts, String diskId) {
        def commands = []
        def diskJobGuid = UUID.randomUUID().toString()
        commands << "\$VM = Get-SCVirtualMachine -VMMServer localhost -ID \"${opts.externalId}\""
        commands << "\$VirtualDiskDrive = Get-SCVirtualDiskDrive -VM \$VM |" +
                " where { \$_.VirtualHardDiskId -eq \"${diskId}\" }"
        commands << "\$ignore = Remove-SCVirtualDiskDrive -VirtualDiskDrive \$VirtualDiskDrive -JobGroup ${diskJobGuid}"
        commands << "\$ignore = Set-SCVirtualMachine -VM \$VM -JobGroup ${diskJobGuid}"
        def cmd = commands.join(CMD_SEPARATOR)
        log.debug "removeDisk: ${cmd}"
        return wrapExecuteCommand(generateCommandString(cmd), opts)
    }

    Map checkServerCreated(Map opts, String vmId) {
        log.debug "checkServerCreated: ${vmId}"
        def rtn = DEFAULT_RESULT.clone()
        try {
            def pending = true
            def attempts = 0
            while (pending) {
                sleep(SLEEP_MILLISECONDS * SLEEP_SECONDS)
                def serverDetail = getServerDetails(opts, vmId)
                if (serverDetail.success == true) {
                    // There isn't a state on the VM to tell us it is created.. but, if the disk size matches
                    // the expected count.. we are good
                    log.debug "serverStatus: ${serverDetail.server?.Status}," +
                            " opts.dataDisks: ${opts.dataDisks?.size()}," +
                            " additionalTemplateDisks: ${opts.additionalTemplateDisks?.size()}"
                    pending = !processServerStatus(serverDetail, opts, vmId, rtn)
                }
                attempts++
                if (attempts > 600) {
                    pending = false
                }
            }
        } catch (e) {
            log.error(EXCEPTION_MSG, e)
        }
        return rtn
    }

    protected boolean processServerStatus(Map serverDetail, Map opts, String vmId, Map rtn) {
        def status = serverDetail.server?.Status
        def diskCount = serverDetail.server?.VirtualDiskDrives?.size()
        def expectedCount = 1 + ((opts.dataDisks?.size() ?: 0) - (opts.additionalTemplateDisks?.size() ?: 0))

        if (status != 'UnderCreation' && diskCount == expectedCount) {
            // additionalTemplateDisks are created after VM creation
            // data disks are created and attached after vm creation
            // additionalTemplateDisks are created after VM creation
            rtn.success = true
            rtn.server = serverDetail.server
            if (status == 'Saved') {
                // Discard saved state... can't modify it if so
                discardSavedState(opts, vmId)
            }
            return true // done
        } else if (status == CREATION_FAILED) {
            rtn.success = false
            return true // done
        }
        return false // keep pending
    }

    Map waitForJobToComplete(Map opts, String jobId) {
        def rtn = DEFAULT_RESULT.clone()
        try {
            log.debug "waitForJobToComplete: ${opts} ${jobId}"
            def pending = true
            def attempts = 0
            while (pending) {
                sleep(SLEEP_MILLISECONDS * SLEEP_SECONDS)
                log.debug "waitForJobToComplete: ${jobId}"
                def getJobResults = getJob(opts, jobId)
                if (getJobResults.success == true && getJobResults.jobDetail) {
                    def status = getJobResults.jobDetail?.Status?.toLowerCase()
                    if ([JOB_STATUS_COMPLETED, 'failed', JOB_STATUS_SUCCEED_WITH_INFO]
                            .indexOf(status) > INDEX_NOT_FOUND) {
                        pending = false
                        if (status == JOB_STATUS_COMPLETED || status == JOB_STATUS_SUCCEED_WITH_INFO) {
                            rtn.success = true
                        }
                    }
                }
                attempts++
                if (attempts > 350) {
                    pending = false
                }
            }
        } catch (e) {
            log.error(EXCEPTION_MSG, e)
        }
        return rtn
    }

    Map getJob(Map opts, String jobId) {
        log.debug "getJob: ${jobId}"
        def rtn = [success: false, jobDetail: null]

        try {
            def command = """\$job = Get-SCJob -VMMServer localhost -ID \"${jobId}\"
\$report = New-Object PSObject -property @{
ID=\$job.ID
Name=\$job.Name
Progress=\$job.Progress
Status=\$job.Status.toString()
}
\$report"""
            def out = wrapExecuteCommand(generateCommandString(command), opts)
            if (!out.success) {
                throw new IllegalStateException("Error in getting job")
            }

            rtn.jobDetail = out.data[0]
            rtn.success = true
        } catch (e) {
            log.error "error in calling job detail: ${e}", e
        }

        return rtn
    }

    Map checkServerReady(Map opts, String vmId) {
        def rtn = DEFAULT_RESULT.clone()
        try {
            log.debug "checkServerReady: ${opts} ${vmId}"
            def pending = true
            def attempts = 0
            def notFoundAttempts = 0
            def serverId = opts.server.id
            def waitForIp = opts.waitForIp
            while (pending) {
                sleep(SLEEP_MILLISECONDS * SLEEP_SECONDS)
                log.debug "checkServerReady: ${vmId}"
                ComputeServer server = morpheusContext.services.computeServer.get(serverId)
                opts.server = server
                // Refresh the VM in SCVMM (seems to be needed for it to get the IP for windows)
                refreshVM(opts, vmId)
                def serverDetail = getServerDetails(opts, vmId)
                if (serverDetail.success == true && serverDetail.server) {
                    def ipAddress = assignIpAddress(serverDetail, server)
                    if (waitForIp && !ipAddress) {
                        log.debug("Waiting for IP address assignment...")
                    } else {
                        // Most likely, server gets its IP from cloud-init calling back to
                        // cloudconfigcontroller/ipaddress... wait for that to happen
                        // Or... if the desire is to NOT install the agent, then we are not expecting an IP address
                        pending = !processServerState(serverDetail, server, ipAddress, rtn)
                    }
                } else {
                    notFoundAttempts = updateNotFoundAttempts(serverDetail, notFoundAttempts)
                }
                attempts++
                pending = shouldContinuePending(attempts, notFoundAttempts, pending)
            }
        } catch (e) {
            log.error(EXCEPTION_MSG, e)
        }
        return rtn
    }

// --- Helper Methods ---

    protected String assignIpAddress(Map serverDetail, ComputeServer server) {
        def ipAddress = serverDetail.server?.internalIp ?: server?.externalIp
        log.debug "ipAddress found: ${ipAddress}"
        if (ipAddress) {
            server.internalIp = ipAddress
        }
        return ipAddress
    }

    protected boolean processServerState(Map serverDetail, ComputeServer server, String ipAddress, Map rtn) {
        if (serverDetail.server?.VirtualMachineState == VM_STATE_RUNNING) {
            rtn.success = true
            rtn.server = serverDetail.server
            rtn.server.ipAddress = ipAddress ?: server?.internalIp
            return true
        } else if (serverDetail.server?.Status == CREATION_FAILED) {
            rtn.success = false
            rtn.server = serverDetail.server
            rtn.server.ipAddress = ipAddress ?: server?.internalIp
            return true
        }
        log.debug("check server loading server: ip: ${server.internalIp}")
        if (server.internalIp) {
            rtn.success = true
            rtn.server = serverDetail.server
            rtn.server.ipAddress = ipAddress ?: server.internalIp
            return true
        }
        return false
    }

    protected int updateNotFoundAttempts(Map serverDetail, int notFoundAttempts) {
        if (serverDetail.error == 'VM_NOT_FOUND') {
            return notFoundAttempts + 1
        }
        return notFoundAttempts
    }

    protected boolean shouldContinuePending(int attempts, int notFoundAttempts, boolean pending) {
        if (attempts > 300 || notFoundAttempts > MAX_NOT_FOUND_ATTEMPTS) {
            return false
        }
        return pending
    }

    @SuppressWarnings('LineLength')
    Map startServer(Map opts, String vmId) {
        def rtn = DEFAULT_RESULT.clone()
        try {
            // Only start if it isn't already running
            def serverDetail = getServerDetails(opts, vmId)
            if (serverDetail.success == true) {
                if (serverDetail.server?.VirtualMachineState != VM_STATE_RUNNING) {
                    def out = wrapExecuteCommand(generateCommandString("\$VM = Get-SCVirtualMachine -VMMServer localhost -ID \"${vmId}\"; \$ignore = Start-SCVirtualMachine -VM \$VM ${opts.async ? '-RunAsynchronously' : ''}"), opts)
                    rtn.success = out.success
                } else {
                    rtn.msg = 'VM is already powered on'
                    rtn.success = true
                }
            }
        } catch (e) {
            log.error("startServer error: ${e}", e)
        }
        return rtn
    }

    @SuppressWarnings('TrailingWhitespace')
    Map stopServer(Map opts, String vmId) {
        def rtn = DEFAULT_RESULT.clone()
        try {
            def command = """\$VM = Get-SCVirtualMachine -VMMServer localhost  -ID \"${vmId}\"
if(\$VM.Status -ne 'PowerOff') { 
	\$ignore = Stop-SCVirtualMachine -VM \$VM; 
} \$true """
            def out = wrapExecuteCommand(generateCommandString(command), opts)
            rtn.success = out.success
        } catch (e) {
            log.error("stopServer error: ${e}", e)
        }
        return rtn
    }

    @SuppressWarnings('TrailingWhitespace')
    Map deleteServer(Map opts, String vmId) {
        def rtn = DEFAULT_RESULT.clone()
        try {
            def sharePath = opts.rootSharePath ?: getRootSharePath(opts)

            if (sharePath) {
                def serverFolder = "${sharePath}\\${opts.serverFolder}"
                def diskFolder = "${opts.diskRoot}\\${opts.serverFolder}"
                if (!opts.serverFolder) {
                    throw new IllegalArgumentException("serverFolder MUST be specified")
                }
                def command = """\$VM = Get-SCVirtualMachine -VMMServer localhost -ID \"${vmId}\"
if(\$VM) { 
  \$ignore = Stop-SCVirtualMachine -VM \$VM -Force
  \$ignore = Remove-SCVirtualMachine -VM \$VM 
} 
\$ignore = Remove-Item -Path  \"${serverFolder}\" -Recurse -Force
\$ignore = Remove-Item -LiteralPath \"${diskFolder}\" -Recurse -Force"""
                wrapExecuteCommand(generateCommandString(command), opts)
                rtn.success = true
            }
        } catch (e) {
            log.error("deleteServer error: ${e}", e)
        }
        return rtn
    }

    Map importPhysicalResource(Map opts, String sourcePath, String imageFolderName, String resourceName) {
        log.debug "importPhysicalResource: ${opts}, ${sourcePath}, ${imageFolderName}, ${resourceName}"
        def rtn = DEFAULT_RESULT.clone()
        def rootSharePath = opts.rootSharePath ?: getRootSharePath(opts)

        def sharePath = "${rootSharePath}\\$imageFolderName"
        def command = "New-Item -ItemType directory -Path \"${sharePath}\";Copy-Item" +
                " -Path \"$sourcePath\" -Destination \"${sharePath}\\${resourceName}\""

        def attempts = 0
        def importOpts = [baseBoxProvisionService: opts.scvmmProvisionService,
                          controllerServer       : opts.controllerNode,] + opts
        while (!rtn.success && attempts < MAX_IMPORT_ATTEMPTS) {
            def out = executeCommand(command, importOpts)
            rtn.success = out.success
            if (!rtn.success) {
                attempts++
                sleep(5000)
            }
        }

        if (rtn.success) {
            executeCommand("\$libraryshare = Get-SCLibraryShare -VMMServer localhost" +
                    " | where { \$_.Path -eq \"${rootSharePath}\" };" +
                    " Read-SCLibraryShare -Path \"${sharePath}\" -LibraryShare \$libraryshare", importOpts)
        } else {
            throw new IllegalStateException("Error in importing physical resource: ${rtn}")
        }
        rtn.success = true
        rtn.sharePath = "${sharePath}\\${resourceName}"

        return rtn
    }

    @SuppressWarnings(['DuplicateStringLiteral', 'TrailingWhitespace'])
    String getRootSharePath(Map opts) {
        def command = """\$report = @()
\$shares = Get-SCLibraryShare -VMMServer localhost 
foreach(\$share in \$shares) {
  \$data = New-Object PSObject -property @{
    ID=\$share.ID
    Name=\$share.Name
    Path=\$share.Path
}
\$report += \$data
}
\$report"""
        def out = wrapExecuteCommand(generateCommandString(command), opts)
        if (!out.success) {
            throw new IllegalStateException("Error in getting library share")
        }

        def shareBlocks = out.data
        if (shareBlocks.size() == 0) {
            throw new IllegalStateException("No library share found")
        }

        return shareBlocks.first().Path
    }

    TaskResult deleteIso(Map opts, String sharePath) {
        def commands = []
        commands << "\$iso = Get-SCISO -VMMServer localhost | where {\$_.SharePath -eq \"$sharePath\"}"
        commands << "\$ignore = Remove-SCISO -ISO \$iso -Force"
        return wrapExecuteCommand(generateCommandString(commands.join(CMD_SEPARATOR)), opts)
    }

    TaskResult deleteUnattend(Map opts, String unattendPath) {
        def commands = []
        commands << "Remove-Item -Path \"${unattendPath}\" -Force"
        return wrapExecuteCommand(generateCommandString(commands.join(CMD_SEPARATOR)), opts)
    }

    TaskResult setCdrom(Map opts, String cdPath = null) {
        log.debug("setCdrom: ${cdPath}")
        def commands = []
        commands << "\$vm = Get-SCVirtualMachine -VMMServer localhost -ID \"$opts.externalId\""
        commands << "\$dvd = Get-SCVirtualDVDDrive -VM \$vm"
        if (cdPath) {
            commands << "\$iso = Get-SCISO -VMMServer localhost | where {\$_.SharePath -eq \"$cdPath\"}"
            commands << SET_VIRTUAL_DVD_NO_MEDIA_CMD
            commands << "\$ignore = Set-SCVirtualDVDDrive -VirtualDVDDrive" +
                    " \$dvd -Bus \$dvd.Bus -LUN \$dvd.Lun -ISO \$iso"
        } else {
            commands << SET_VIRTUAL_DVD_NO_MEDIA_CMD
        }
        return wrapExecuteCommand(generateCommandString(commands.join(CMD_SEPARATOR)), opts)
    }

    @SuppressWarnings('UnnecessaryGetter')
    String importScript(String content, String diskFolder, String imageFolderName, Map opts) {
        log.debug "importScript: ${diskFolder}, ${imageFolderName}, ${opts}"
        def scriptPath
        InputStream inputStream = new ByteArrayInputStream(content.getBytes())
        def command = "\$ignore = mkdir \"${diskFolder}\""
        wrapExecuteCommand(generateCommandString(command), opts)
        def fileResults = morpheusContext.services.fileCopy.copyToServer(
                opts.hypervisor, "${opts.fileName}", "${diskFolder}\\${opts.fileName}",
                inputStream, opts.cloudConfigBytes?.size(), null, true)
        log.debug("importScript: fileResults.success: ${fileResults.success}")
        if (!fileResults.success) {
            throw new IllegalStateException("Script Upload to SCVMM Host Failed. " +
                    "Perhaps an agent communication issue...${opts.hypervisor.name}")
        }
        def importResults = importPhysicalResource(
                opts, "${diskFolder}\\${opts.fileName}".toString(), imageFolderName, opts.fileName)
        scriptPath = importResults.sharePath
        return scriptPath
    }

    void createDVD(Map opts) {
        log.debug "createDVD: ${opts.externalId}"

        // If gen2... ALWAYS -Bus 0
        // If gen1... ALWAYS -Bus 1

        def busNumber = 0
        def lunNumber = opts.scvmmGeneration == GENERATION1 ? 0 : 1

        def command = """\$busNumber = ${busNumber}
\$lunNumber = ${lunNumber}
\$externalId = "${opts.externalId}"
\$VM = Get-SCVirtualMachine -VMMServer localhost -ID \$externalId
\$success = \$false
For (\$i=0; \$i -le 10; \$i++) {
	If (\$success -eq \$false) {
		\$jobGuid = New-Guid
		\$ignore = New-SCVirtualDVDDrive -VMMServer localhost -JobGroup \$jobGuid -Bus \$busNumber -LUN \$lunNumber
		\$ignore = Set-SCVirtualMachine -VM \$VM -JobGroup \$jobGuid
		if( -not \$? ) {
			\$lunNumber = \$lunNumber + 1
			\$ignore = Repair-SCVirtualMachine -VM \$VM -Dismiss -Force
		} else {
			\$success = \$true
		}
	}
}

\$report = New-Object -Type PSObject -Property @{
	'success'=\$success
	'BUS'=\$busNumber
	'LUN'=\$lunNumber}
\$report"""

        def out = wrapExecuteCommand(generateCommandString(command), opts)
        if (!out.success) {
            log.warn "Error in creating a DVD: ${out}"
        }
    }

    String importAndMountIso(byte[] cloudConfigBytes, String diskFolder, String imageFolderName, Map opts) {
        log.debug "importAndMountIso: ${diskFolder}, ${imageFolderName}, ${opts}"
        def cloudInitIsoPath
        def isoAction = [inline    : true, action: 'rawfile', content: cloudConfigBytes.encodeBase64(),
                         targetPath: "${diskFolder}\\config.iso".toString(), opts: [:]]

        InputStream inputStream = new ByteArrayInputStream(cloudConfigBytes)
        def command = "\$ignore = mkdir \"${diskFolder}\""
        wrapExecuteCommand(generateCommandString(command), opts)
        def fileResults = morpheusContext.services.fileCopy.copyToServer(
                opts.hypervisor, CONFIG_ISO, "${diskFolder}\\config.iso",
                inputStream, cloudConfigBytes?.size())
        log.debug("importAndMountIso: fileResults?.success: ${fileResults?.success}")
        if (!fileResults.success) {
            throw new IllegalStateException("ISO Upload to SCVMM Host Failed." +
                    " Perhaps an agent communication issue...${opts.hypervisor.name}")
        }
        def importResults = importPhysicalResource(opts, isoAction.targetPath,
                imageFolderName, CONFIG_ISO)
        cloudInitIsoPath = importResults.sharePath
        setCdrom(opts, cloudInitIsoPath)
        return cloudInitIsoPath
    }

    @SuppressWarnings(['MethodParameterTypeRequired', 'MethodReturnTypeRequired'])
    def toList(value) {
        [value].flatten()
    }

    Map validateServerConfig(Map opts = [:]) {
        log.debug("validateServerConfig: ${opts}")
        def rtn = [success: false, errors: []]
        try {
            if (!opts.scvmmCapabilityProfile) {
                rtn.errors += [field: 'scvmmCapabilityProfile', msg: 'You must select a capability profile']
            }
            validateNetworkConfig(opts, rtn)
            if (opts.containsKey(FIELD_NODE_COUNT) && opts.nodeCount == '') {
                rtn.errors += [field: FIELD_NODE_COUNT, msg: 'You must indicate number of hosts']
            }
            rtn.success = (rtn.errors.size() == 0)
            log.debug "validateServer results: ${rtn}"
        } catch (e) {
            log.error "error in validateServerConfig: ${e}", e
        }
        return rtn
    }

    protected void validateNetworkConfig(Map opts, Map rtn) {
        if (opts.networkId) {
            log.debug("validateServerConfig networkId: ${opts.networkId}")
            return
        }
        if (opts?.networkInterfaces) {
            validateNetworkInterfaces(opts.networkInterfaces, rtn)
            return
        }
        if (opts?.networkInterface) {
            validateNetworkInterface(opts.networkInterface, rtn)
            return
        }
        rtn.errors << [field: FIELD_NETWORK_ID, msg: MSG_NETWORK_REQUIRED]
    }

    protected void validateNetworkInterfaces(List networkInterfaces, Map rtn) {
        log.debug("validateServerConfig networkInterfaces: ${networkInterfaces}")
        networkInterfaces.eachWithIndex { nic, index ->
            def networkId = nic.network?.id ?: nic.network.group
            log.debug("network.id: ${networkId}")
            if (!networkId) {
                rtn.errors << [field: FIELD_NETWORK_INTERFACE, msg: MSG_NETWORK_REQUIRED]
            }
            if (nic.ipMode == IP_MODE_STATIC && !nic.ipAddress) {
                rtn.errors = [field: FIELD_NETWORK_INTERFACE, msg: MSG_ENTER_IP]
            }
        }
    }

    @SuppressWarnings('MethodParameterTypeRequired')
    protected void validateNetworkInterface(def networkInterface, Map rtn) {
        log.debug("validateServerConfig networkInterface: ${networkInterface}")
        toList(networkInterface?.network?.id)?.eachWithIndex { networkId, index ->
            log.debug("network.id: ${networkId}")
            if (networkId?.length() < 1) {
                rtn.errors << [field: FIELD_NETWORK_INTERFACE, msg: MSG_NETWORK_REQUIRED]
            }
            if (networkInterface[index].ipMode == IP_MODE_STATIC && !networkInterface[index].ipAddress) {
                rtn.errors = [field: FIELD_NETWORK_INTERFACE, msg: MSG_ENTER_IP]
            }
        }
    }

    Map updateServer(Map opts, String vmId, Map updates = [:]) {
        log.debug("updateServer: vmId: ${vmId}, updates: ${updates}")
        def rtn = DEFAULT_RESULT.clone()
        try {
            def minDynamicMemory = updates.minDynamicMemory
                    ? (int) (updates.minDynamicMemory / ComputeUtility.ONE_MEGABYTE)
                    : null
            def maxDynamicMemory = updates.maxDynamicMemory
                    ? (int) (updates.maxDynamicMemory / ComputeUtility.ONE_MEGABYTE)
                    : null

            if (updates.maxMemory || updates.maxCores || minDynamicMemory || maxDynamicMemory) {
                def command = "\$VM = Get-SCVirtualMachine -VMMServer localhost -ID \"${vmId}\";"
                if (updates.maxCores) {
                    command += "\$ignore = Set-SCVirtualMachine -VM \$VM -CPUCount ${updates.maxCores};"
                }

                if (updates.maxMemory) {
                    def maxMemory = (int) (updates.maxMemory / ComputeUtility.ONE_MEGABYTE)
                    command += "\$maxMemory = ${maxMemory};"
                    command += "\$minDynamicMemory = ${minDynamicMemory ?: POWERSHELL_NULL};"
                    command += "\$maxDynamicMemory = ${maxDynamicMemory ?: POWERSHELL_NULL};"
                    command += "\$VM = Get-SCVirtualMachine -VMMServer localhost -ID \"${vmId}\";"
                    command += "if(\$minDynamicMemory -and \$maxDynamicMemory -eq \$null)" +
                            " { \$maxDynamicMemory = [int32]::MaxValue };"
                    command += "if(\$maxDynamicMemory -and \$minDynamicMemory -eq \$null) { \$minDynamicMemory = 32 };"
                    command += "\$dynamicMemoryEnabled = (\$minDynamicMemory -ne \$null);"
                    command += "if(\$dynamicMemoryEnabled -eq \$true -and \$minDynamicMemory -gt \$maxMemory) " +
                            "{ \$maxMemory = \$minDynamicMemory };"
                    command += "if(\$dynamicMemoryEnabled) { \$ignore = Set-SCVirtualMachine " +
                            "-VM \$VM -DynamicMemoryEnabled \$true -MemoryMB \$maxMemory -DynamicMemoryMinimumMB" +
                            " \$minDynamicMemory -DynamicMemoryMaximumMB \$maxDynamicMemory }" +
                            " else { \$ignore = Set-SCVirtualMachine -VM \$VM -DynamicMemoryEnabled \$false" +
                            " -MemoryMB \$maxMemory };"
                    command += "\$true"
                    // Add logic to handle dynamic memory... if the startup memory is lower than dynamic max memory,
                    // it won't start.  So, set them equal
                }

                log.debug "updateServer: ${command}"
                def out = wrapExecuteCommand(generateCommandString(command), opts)
                log.debug "updateServer results: ${out}"
                rtn.success = out.success && out.exitCode == EXIT_CODE_SUCCESS
            } else {
                log.debug("No updates for server: ${vmId}")
                rtn.success = true
            }
        } catch (e) {
            log.error "updateServer error: ${e}", e
        }
        return rtn
    }

    @SuppressWarnings('MethodParameterTypeRequired')
    String cleanData(data, String ignoreString = null) {
        def rtn = ''
        if (data) {
            def lines = data.tokenize(NEWLINE_CHAR)
            lines = lines?.findAll { l -> l?.trim()?.length() > 1 }
            if (lines?.size() > 0) {
                lines?.each { line ->
                    def trimLine = line.trim()
                    if (rtn == null && ignoreString == null || trimLine != ignoreString) {
                        rtn = trimLine
                    }
                }
            }
        }
        return rtn
    }

    @SuppressWarnings('MethodSize')
    String getMapScvmmOsType(String searchFor, Boolean findByKey = true, String defaultOsType = null) {
        def scvmmOsTypeMap = [
                '64-bit edition of Windows 10'                       : 'windows.10.64',
                '64-bit edition of Windows 7'                        : 'windows.7.64',
                '64-bit edition of Windows 8'                        : WINDOWS_8_64,
                '64-bit edition of Windows 8.1'                      : WINDOWS_8_64,
                '64-bit edition of Windows Server 2008 Datacenter'   : WINDOWS_SERVER_2008,
                '64-bit edition of Windows Server 2008 Enterprise'   : WINDOWS_SERVER_2008,
                '64-bit edition of Windows Server 2008 R2 Datacenter': WINDOWS_SERVER_2008_R2,
                '64-bit edition of Windows Server 2008 R2 Enterprise': WINDOWS_SERVER_2008_R2,
                '64-bit edition of Windows Server 2008 R2 Standard'  : WINDOWS_SERVER_2008_R2,
                '64-bit edition of Windows Server 2008 Standard'     : WINDOWS_SERVER_2008,
                '64-bit edition of Windows Server 2012 Datacenter'   : WINDOWS_SERVER_2012,
                '64-bit edition of Windows Server 2012 Essentials'   : WINDOWS_SERVER_2012,
                '64-bit edition of Windows Server 2012 Standard'     : WINDOWS_SERVER_2012,
                '64-bit edition of Windows Vista'                    : WINDOWS,
                '64-bit edition of Windows Web Server 2008'          : WINDOWS_SERVER_2008,
                '64-bit edition of Windows Web Server 2008 R2'       : WINDOWS_SERVER_2008_R2,
                'CentOS Linux 5 (32 bit)'                            : CENT,
                'CentOS Linux 5 (64 bit)'                            : CENT,
                'CentOS Linux 6 (32 bit)'                            : 'cent.6',
                'CentOS Linux 6 (64 bit)'                            : 'cent.6.64',
                'CentOS Linux 7 (64 bit)'                            : 'cent.7.64',
                'Debian GNU/Linux 7 (32 bit)'                        : 'debian.7',
                'Debian GNU/Linux 7 (64 bit)'                        : 'debian.6.64',
                'Debian GNU/Linux 8 (32 bit)'                        : 'debian.8',
                'Debian GNU/Linux 8 (64 bit)'                        : 'debian.8.64',
                'None'                                               : OTHER,
                'Novell NetWare 5.1'                                 : OTHER,
                'Novell NetWare 6.x'                                 : OTHER,
                'Open Enterprise Server'                             : OTHER,
                'Oracle Linux 5 (32 bit)'                            : ORACLE_32,
                'Oracle Linux 5 (64 bit)'                            : ORACLE_LINUX_64,
                'Oracle Linux 6 (32 bit)'                            : ORACLE_32,
                'Oracle Linux 6 (64 bit)'                            : ORACLE_LINUX_64,
                'Oracle Linux 7 (64 bit)'                            : ORACLE_LINUX_64,
                'Other (32 bit)'                                     : 'other.32',
                'Other (64 bit)'                                     : 'other.64',
                'Other Linux (32 bit)'                               : LINUX_32,
                'Other Linux (64 bit)'                               : LINUX_64,
                'Red Hat Enterprise Linux 2'                         : REDHAT,
                'Red Hat Enterprise Linux 3'                         : REDHAT,
                'Red Hat Enterprise Linux 3 (64 bit)'                : REDHAT,
                'Red Hat Enterprise Linux 4'                         : REDHAT,
                'Red Hat Enterprise Linux 4 (64 bit)'                : REDHAT,
                'Red Hat Enterprise Linux 5'                         : REDHAT,
                'Red Hat Enterprise Linux 5 (64 bit)'                : REDHAT,
                'Red Hat Enterprise Linux 6'                         : 'redhat.6',
                'Red Hat Enterprise Linux 6 (64 bit)'                : 'redhat.64',
                'Red Hat Enterprise Linux 7 (64 bit)'                : 'redhat.7.64',
                'Sun Solaris 10 (32 bit)'                            : LINUX_32,
                'Sun Solaris 10 (64 bit)'                            : LINUX_64,
                'Suse Linux Enterprise Server 10 (32 bit)'           : SUSE,
                'Suse Linux Enterprise Server 10 (64 bit)'           : SUSE,
                'Suse Linux Enterprise Server 11 (32 bit)'           : SUSE,
                'Suse Linux Enterprise Server 11 (64 bit)'           : SUSE,
                'Suse Linux Enterprise Server 12 (64 bit)'           : 'suse.12.64',
                'Suse Linux Enterprise Server 9 (32 bit)'            : SUSE,
                'Suse Linux Enterprise Server 9 (64 bit)'            : SUSE,
                'Ubuntu Linux (32 bit)'                              : UBUNTU,
                'Ubuntu Linux (64 bit)'                              : UBUNTU_64,
                'Ubuntu Linux 12.04 (32 bit)'                        : 'ubuntu.12.04',
                'Ubuntu Linux 12.04 (64 bit)'                        : 'ubuntu.12.04.64',
                'Ubuntu Linux 14.04 (32 bit)'                        : 'ubuntu.14.04',
                'Ubuntu Linux 14.04 (64 bit)'                        : 'ubuntu.14.04.64',
                'Ubuntu Linux 16.04 (32 bit)'                        : UBUNTU,
                'Ubuntu Linux 16.04 (64 bit)'                        : UBUNTU_64,
                'Ubuntu Linux 20.04 (32 bit)'                        : 'ubuntu.20.04',
                'Ubuntu Linux 20.04 (64 bit)'                        : 'ubuntu.20.04.64',
                'Ubuntu Linux 24.04 (32 bit)'                        : 'ubuntu.24.04',
                'Ubuntu Linux 24.04 (64 bit)'                        : 'ubuntu.24.04.64',
                'Windows 10'                                         : 'windows.10',
                'Windows 2000 Advanced Server'                       : WINDOWS,
                'Windows 2000 Server'                                : WINDOWS,
                'Windows 7'                                          : 'windows.7',
                'Windows 8'                                          : WINDOWS_8,
                'Windows 8.1'                                        : WINDOWS_8,
                'Windows NT Server 4.0'                              : WINDOWS,
                'Windows Server 2003 Datacenter Edition (32-bit x86)': WINDOWS,
                'Windows Server 2003 Datacenter x64 Edition'         : WINDOWS,
                'Windows Server 2003 Enterprise Edition (32-bit x86)': WINDOWS,
                'Windows Server 2003 Enterprise x64 Edition'         : WINDOWS,
                'Windows Server 2003 Standard Edition (32-bit x86)'  : WINDOWS,
                'Windows Server 2003 Standard x64 Edition'           : WINDOWS,
                'Windows Server 2003 Web Edition'                    : WINDOWS,
                'Windows Server 2008 Datacenter 32-Bit'              : WINDOWS_SERVER_2008,
                'Windows Server 2008 Enterprise 32-Bit'              : WINDOWS_SERVER_2008,
                'Windows Server 2008 Standard 32-Bit'                : WINDOWS_SERVER_2008,
                'Windows Server 2012 R2 Datacenter'                  : WINDOWS_SERVER_2012,
                'Windows Server 2012 R2 Essentials'                  : WINDOWS_SERVER_2012,
                'Windows Server 2012 R2 Standard'                    : WINDOWS_SERVER_2012,
                'Windows Server 2016 Datacenter'                     : WINDOWS_SERVER_2016,
                'Windows Server 2016 Essentials'                     : WINDOWS_SERVER_2016,
                'Windows Server 2016 Standard'                       : WINDOWS_SERVER_2016,
                'Windows Server 2019 Datacenter'                     : WINDOWS_SERVER_2019,
                'Windows Server 2019 Essentials'                     : WINDOWS_SERVER_2019,
                'Windows Server 2019 Standard'                       : WINDOWS_SERVER_2019,
                'Windows Server 2022 Datacenter'                     : 'windows.server.2022.dc.core',
                'Windows Server 2022 Standard'                       : 'windows.server.2022.std.core',
                'Windows Server 2025 Datacenter'                     : WINDOWS_SERVER_2025,
                'Windows Server 2025 Essentials'                     : WINDOWS_SERVER_2025,
                'Windows Server 2025 Standard'                       : WINDOWS_SERVER_2025,
                'Windows Small Business Server 2003'                 : WINDOWS,
                'Windows Vista'                                      : WINDOWS,
                'Windows Web Server 2008'                            : WINDOWS_8,
                'Windows XP 64-Bit Edition'                          : WINDOWS,
                'Windows XP Professional'                            : WINDOWS,
                ''                                                   : OTHER,
        ]

        if (findByKey) {
            return scvmmOsTypeMap[searchFor] ?: scvmmOsTypeMap[defaultOsType] ?: OTHER
        }
        // Passed in the value... find the key
        def found = scvmmOsTypeMap.find { k, v -> v == searchFor }
        return found?.key
    }

    Map findImage(Map opts, String imageName) {
        def rtn = DEFAULT_IMAGE_RESULT.clone()
        def zoneRoot = opts.zoneRoot ?: defaultRoot
        def imageFolder = formatImageFolder(imageName)
        def imageFolderPath = "${zoneRoot}\\images\\${imageFolder}"
        def command = "(Get-ChildItem -File \"${imageFolderPath}\").FullName"
        log.debug("findImage command: ${command}")
        def out = executeCommand(command, opts)
        log.debug("findImage: ${out.data}")
        rtn.success = out.success
        if (out.data?.length() > 0) {
            rtn.imageExists = true
            rtn.imageName = out.data.trim()
        }
        return rtn
    }

    Map deleteImage(Map opts, String imageName) {
        def rtn = DEFAULT_IMAGE_RESULT.clone()
        def zoneRoot = opts.zoneRoot ?: defaultRoot
        def imageFolder = formatImageFolder(imageName)
        def imageFolderPath = "${zoneRoot}\\images\\${imageFolder}"
        def command = "Remove-Item -LiteralPath \"${imageFolderPath}\" -Recurse -Force"
        log.debug("deleteImage command: ${command}")
        def out = wrapExecuteCommand(generateCommandString(command), opts)
        log.debug("deleteImage: ${out.data}")
        rtn.success = out.success
        return rtn
    }

    @SuppressWarnings('UnnecessaryGetter')
    Map transferImage(Map opts, Collection<CloudFile> cloudFiles, String imageName) {
        def rtn = [success: false, results: []]
        CloudFile metadataFile = (CloudFile) cloudFiles?.find { cloudFile -> cloudFile.name == METADATA_JSON }
        List<CloudFile> vhdFiles = cloudFiles?.findAll { cloudFile ->
            cloudFile.name.indexOf(".morpkg") == INDEX_NOT_FOUND &&
                    (cloudFile.name.indexOf('.vhd') > INDEX_NOT_FOUND || cloudFile.name.indexOf('.vhdx')) &&
                    cloudFile.name.endsWith(FORWARD_SLASH) == false
        }
        log.debug("vhdFiles: ${vhdFiles}")
        def zoneRoot = opts.zoneRoot ?: defaultRoot
        def imageFolderName = formatImageFolder(imageName)
        List<Map> fileList = []
        def tgtFolder = "${zoneRoot}\\images\\${imageFolderName}"
        opts.targetImageFolder = tgtFolder
        // def cachePath = opts.cachePath
        def command = "\$ignore = mkdir \"${tgtFolder}\""
        log.debug("command: ${command}")
        wrapExecuteCommand(generateCommandString(command), opts)

        if (metadataFile) {
            fileList << [inputStream: metadataFile.inputStream, contentLength: metadataFile.contentLength,
                         targetPath : "${tgtFolder}\\metadata.json".toString(), copyRequestFileName: METADATA_JSON]
        }
        vhdFiles.each { CloudFile vhdFile ->
            def imageFileName = extractImageFileName(vhdFile.name)
            def filename = extractFileName(vhdFile.name)
            fileList << [inputStream: vhdFile.inputStream, contentLength: vhdFile.getContentLength(),
                         targetPath : "${tgtFolder}\\${imageFileName}".toString(), copyRequestFileName: filename]
        }
        fileList.each { Map fileItem ->
            Long contentLength = (Long) fileItem.contentLength
            def fileResults = morpheusContext.services.fileCopy.copyToServer(
                    opts.hypervisor, fileItem.copyRequestFileName, fileItem.targetPath,
                    fileItem.inputStream, contentLength, null, true)
            rtn.success = fileResults.success
        }

        return rtn
    }

    Map snapshotServer(Map opts, String vmId) {
        def rtn = DEFAULT_RESULT.clone()
        try {
            def snapshotId = opts.snapshotId ?: "${vmId}.${System.currentTimeMillis()}"
            def command = "\$VM = Get-SCVirtualMachine -VMMServer localhost -ID \"${vmId}\";" +
                    " \$ignore = New-SCVMCheckpoint -VM \$VM -Name \"${snapshotId}\""
            def out = wrapExecuteCommand(generateCommandString(command), opts)
            rtn.success = out.success && out.exitCode == EXIT_CODE_SUCCESS
            rtn.snapshotId = snapshotId
            log.debug("snapshot server: ${out}")
        } catch (e) {
            log.error("snapshotServer error: ${e}")
        }

        return rtn
    }

    Map deleteSnapshot(Map opts, String vmId, String snapshotId) {
        def rtn = DEFAULT_RESULT.clone()
        try {
            def commands = []
            commands << "\$VM = Get-SCVirtualMachine -VMMServer localhost -ID \"${vmId}\""
            commands << "\$Checkpoint = Get-SCVMCheckpoint -VM \$VM | where {\$_.Name -like \"${snapshotId}\"}"
            commands << "\$ignore = Remove-SCVMCheckpoint -VMCheckpoint \$Checkpoint"
            def out = wrapExecuteCommand(generateCommandString(commands.join(CMD_SEPARATOR)), opts)
            rtn.success = out.success && out.exitCode == EXIT_CODE_SUCCESS
            rtn.snapshotId = snapshotId
            log.debug("delete snapshot: ${out}")
        } catch (e) {
            log.error("deleteSnapshot error: ${e}")
        }

        return rtn
    }

    Map restoreServer(Map opts, String vmId, String snapshotId) {
        def rtn = DEFAULT_RESULT.clone()
        try {
            def commands = []
            commands << "\$VM = Get-SCVirtualMachine -VMMServer localhost -ID \"${vmId}\""
            commands << "\$Checkpoint = Get-SCVMCheckpoint -VM \$VM | where {\$_.Name -like \"${snapshotId}\"}"
            commands << "Restore-SCVMCheckpoint -VMCheckpoint \$Checkpoint"
            def out = wrapExecuteCommand(generateCommandString(commands.join(CMD_SEPARATOR)), opts)
            rtn.success = out.success && out.exitCode == EXIT_CODE_SUCCESS
            log.debug("restore server: ${out}")
        } catch (e) {
            log.error("restoreServer error: ${e}")
        }

        return rtn
    }

    Map changeVolumeTypeForClonedBootDisk(Map opts, String originalVMId, String newVMId) {
        def rtn = DEFAULT_RESULT.clone()
        try {
            def commands = []
            commands << "\$ClonedVM = Get-SCVirtualMachine -VMMServer localhost -ID \"$originalVMId\""
            commands << "\$OriginalBootDisk = Get-SCVirtualDiskDrive -VMMServer localhost -VM \$ClonedVM |" +
                    " where {\$_.VolumeType -eq \"BootAndSystem\"}"
            commands << "\$NewVM = Get-SCVirtualMachine -VMMServer localhost -ID \"$newVMId\""
            commands << "\$ClonedBootDisk = Get-SCVirtualDiskDrive -VMMServer localhost -VM \$NewVM |" +
                    " where {\$_.VirtualHardDisk" +
                    " -like [io.path]::GetFileNameWithoutExtension(\$OriginalBootDisk.VirtualHardDisk)}"
            commands << "Set-SCVirtualDiskDrive -VirtualDiskDrive \$ClonedBootDisk -VolumeType BootAndSystem"

            def out = wrapExecuteCommand(generateCommandString(commands.join(CMD_SEPARATOR)), opts)
            rtn.success = out.success && out.exitCode == EXIT_CODE_SUCCESS
            log.debug("changeVolumeTypeForClonedBootDisk: ${out}")
        } catch (e) {
            log.error("changeVolumeTypeForClonedBootDisk error: ${e}")
        }
        return rtn
    }

    // TODO needs more error handling
    @SuppressWarnings(['LineLength', 'InvertedIfElse', 'AbcMetric', 'MethodSize', 'CyclomaticComplexity',
            'UnnecessaryToString', 'VariableName', 'UnusedVariable'])
    Map buildCreateServerCommands(Map opts) {
        log.debug "buildCreateServerCommands: ${opts}"
        def rtn = [launchCommand: null, hardwareProfileName: '', templateName: '']
        def commands = []

        def hardwareGuid = UUID.randomUUID().toString()
        def networkConfig = opts.networkConfig
        def scvmmCapabilityProfile = opts.scvmmCapabilityProfile
        def scvmmGeneration = opts.scvmmGeneration ?: GENERATION1
        def hardwareProfileName = "Profile${UUID.randomUUID().toString()}"
        def maxCores = opts.maxCores
        def memoryMB = (int) (opts.memory / ComputeUtility.ONE_MEGABYTE)
        def minDynamicMemoryMB = opts.minDynamicMemory
                ? (int) (opts.minDynamicMemory / ComputeUtility.ONE_MEGABYTE)
                : null
        def maxDynamicMemoryMB = opts.maxDynamicMemory
                ? (int) (opts.maxDynamicMemory / ComputeUtility.ONE_MEGABYTE)
                : null

        def zone = opts.zone
        def cloneVMId = opts.cloneVMId
        def vmId = opts.vmId
        def imageId = opts.imageId
        def templateName = "Temporary Morpheus Template ${UUID.randomUUID().toString()}"
        def dataDisks = opts.dataDisks
        def hostExternalId = opts.hostExternalId
        def volumePath = opts.volumePath
        def highlyAvailable = opts.highlyAvailable
        def isSyncdImage = opts.isSyncdImage
        def diskExternalIdMappings = opts.diskExternalIdMappings
        def isSysprep = opts.isSysprep
        def unattendPath = opts.unattendPath
        def OSName = opts.OSName
        def isTemplate = opts.isTemplate
        def templateId = opts.templateId
        def deployingToCloud = opts.zone.regionCode ? true : false
        def volumePaths = (
                opts.volumePaths &&
                        opts.volumePaths?.size() == 1 + dataDisks?.size()
        ) ? opts.volumePaths : null

        // Static v DHCP
        def doStatic = networkConfig?.doStatic
        def doPool = doStatic && networkConfig?.primaryInterface?.poolType == 'scvmm'
        def ipAddress = networkConfig?.primaryInterface?.ipAddress
        def poolId = networkConfig?.primaryInterface?.networkPool?.externalId
        def vlanEnabled = networkConfig?.primaryInterface?.vlanId > 0
        def vlanId = networkConfig?.primaryInterface?.vlanId
        // network may be a vlan network... therefore, the externalId includes the VLAN id.. need to remove it
        def networkExternalId = networkConfig?.primaryInterface?.network?.externalId?.take(TAKE_36)
        def subnetExternalId = networkConfig?.primaryInterface?.subnet?.externalId?.take(TAKE_36)

        if (isTemplate && templateId) {
            commands << "\$template = Get-SCVMTemplate -VMMServer localhost | where {\$_.ID -eq \"$templateId\"}"
        }
        // mac settings
        def hasMACAddress = false
        if (doStatic && doPool) {  // This seems weird.. why does the static networking affect the MAC setting?
            hasMACAddress = true
            commands << "\$MACAddress = \"00:00:00:00:00:00\""
            commands << "\$MACAddressType = \"Static\""
        } else if (isTemplate && templateId) {
            // Fetch the MAC settings from the template
            commands << "\$MACAddressTypeSetting = If (-not ([string]::IsNullOrEmpty(\$template." +
                    "VirtualNetworkAdapters.MACAddressType))) { \$template.VirtualNetworkAdapters.MACAddressType}" +
                    " Else { \"Dynamic\" }"

            commands << "if( \$MACAddressTypeSetting -eq \"Static\")" +
                    " { \$MACAddress = \"00:00:00:00:00:00\"; \$MACAddressType = \"Static\"; }"

            commands << "if( \$MACAddressTypeSetting -eq \"Dynamic\")" +
                    " { \$MACAddress = \"\"; \$MACAddressType = \"Dynamic\"; }"
        } else {
            commands << "\$MACAddress = \"\""
            commands << "\$MACAddressType = \"Dynamic\""
        }

        commands << "\$ignore = New-SCVirtualScsiAdapter -VMMServer localhost " +
                "-JobGroup $hardwareGuid -AdapterID 7 -ShareVirtualScsiAdapter \$false -ScsiControllerType" +
                " DefaultTypeNoType"
        commands << "\$VMNetwork = Get-SCVMNetwork -VMMServer localhost -ID \"${networkExternalId}\""
        if (subnetExternalId) {
            commands << "\$VMSubnet = Get-SCVMSubnet -VMMServer localhost -ID \"${subnetExternalId}\""
        }
        commands << "If (-not ([string]::IsNullOrEmpty(\$MACAddress))) {"
        commands << "\$ignore = New-SCVirtualNetworkAdapter -VMMServer localhost -JobGroup $hardwareGuid" +
                " -MACAddress \$MACAddress -MACAddressType \$MACAddressType" +
                " -VLanEnabled ${vlanEnabled ? "\$true" : "\$false"} ${vlanEnabled ? "-VLanID ${vlanId}" : ''}" +
                " -Synthetic -EnableVMNetworkOptimization \$false " +
                "-EnableMACAddressSpoofing \$false -EnableGuestIPNetworkVirtualizationUpdates \$false " +
                "-IPv4AddressType ${doStatic && doPool ? 'Static' : 'Dynamic'} " +
                "-IPv6AddressType Dynamic ${subnetExternalId ? '-VMSubnet \$VMSubnet' : ''} -VMNetwork \$VMNetwork"
        commands << ELSE_CLOSE
        commands << "\$ignore = New-SCVirtualNetworkAdapter -VMMServer localhost -JobGroup $hardwareGuid " +
                "-MACAddressType \$MACAddressType " +
                "-VLanEnabled ${vlanEnabled ? "\$true" : "\$false"} ${vlanEnabled ? "-VLanID ${vlanId}" : ''} " +
                "-Synthetic -EnableVMNetworkOptimization \$false -EnableMACAddressSpoofing \$false " +
                "-EnableGuestIPNetworkVirtualizationUpdates \$false" +
                " -IPv4AddressType ${doStatic && doPool ? 'Static' : 'Dynamic'}" +
                " -IPv6AddressType Dynamic ${subnetExternalId ? '-VMSubnet \$VMSubnet' : ''} -VMNetwork \$VMNetwork"
        commands << CLOSE_BRACE

        if (scvmmCapabilityProfile) {
            commands << "\$CapabilityProfile = Get-SCCapabilityProfile -VMMServer localhost |" +
                    " where {\$_.Name -eq \"${scvmmCapabilityProfile?.trim()}\"}"
        }

        // Generation
        def generationNumber = !scvmmGeneration || scvmmGeneration == GENERATION1 ? OBJECT_TYPE_1 : '2'
        if (isTemplate && templateId) {
            // Copying all of the hardware profiles from the existing template over
            commands << "\$CPUExpectedUtilizationPercent = If (-not " +
                    "([string]::IsNullOrEmpty(\$template.CPUExpectedUtilizationPercent))) " +
                    "{\$template.CPUExpectedUtilizationPercent} Else { 20 }"
            commands << "\$DiskIops = If (-not ([string]::IsNullOrEmpty(\$template.DiskIops))) " +
                    "{\$template.DiskIops} Else { 0 }"
            commands << "\$CPUMaximumPercent = If (-not ([string]::IsNullOrEmpty(\$template.CPUMaximumPercent))) " +
                    "{\$template.CPUMaximumPercent} Else { 100 }"
            commands << "\$NetworkUtilizationMbps = If (-not " +
                    "([string]::IsNullOrEmpty(\$template.NetworkUtilizationMbps))) " +
                    "{\$template.NetworkUtilizationMbps} Else { 0 }"
            commands << "\$CPURelativeWeight = If (-not " +
                    "([string]::IsNullOrEmpty(\$template.CPURelativeWeight))) " +
                    "{\$template.CPURelativeWeight} Else { 100 }"
            commands << "\$DynamicMemoryEnabled = If (-not " +
                    "([string]::IsNullOrEmpty(\$template.DynamicMemoryEnabled))) " +
                    "{\$template.DynamicMemoryEnabled} Else { \$false }"
            commands << "\$MemoryWeight = If (-not " +
                    "([string]::IsNullOrEmpty(\$template.MemoryWeight))) {\$template.MemoryWeight} Else { 5000 }"
            commands << "\$VirtualVideoAdapterEnabled = If (-not " +
                    "([string]::IsNullOrEmpty(\$template.VirtualVideoAdapterEnabled))) " +
                    "{\$template.VirtualVideoAdapterEnabled} Else { \$false }"
            commands << "\$CPUReserve = If (-not " +
                    "([string]::IsNullOrEmpty(\$template.CPUReserve))) {\$template.CPUReserve} Else { 0 }"
            commands << "\$NumaIsolationRequired = If (-not " +
                    "([string]::IsNullOrEmpty(\$template.NumaIsolationRequired))) " +
                    "{\$template.NumaIsolationRequired} Else { \$false }"
            commands << "\$DRProtectionRequired = If (-not " +
                    "([string]::IsNullOrEmpty(\$template.DRProtectionRequired))) " +
                    "{\$template.DRProtectionRequired} Else { \$false }"
            commands << "\$CPULimitForMigration = If (-not " +
                    "([string]::IsNullOrEmpty(\$template.LimitCPUForMigration))) " +
                    "{\$template.LimitCPUForMigration} Else { \$false }"
            commands << "\$CPULimitFunctionality = If (-not " +
                    "([string]::IsNullOrEmpty(\$template.CPULimitFunctionality))) " +
                    "{\$template.CPULimitFunctionality} Else { \$false }"

            commands << IGNORE_NEW_HARDWARE_PROFILE +
                    "-Name \"$hardwareProfileName\" -Description \"Morpheus created profile\" -CPUCount ${maxCores}" +
                    " -MemoryMB ${memoryMB} -DynamicMemoryEnabled \$DynamicMemoryEnabled " +
                    "-MemoryWeight \$MemoryWeight -VirtualVideoAdapterEnabled \$VirtualVideoAdapterEnabled " +
                    "-CPUExpectedUtilizationPercent \$CPUExpectedUtilizationPercent -DiskIops \$DiskIops" +
                    " -CPUMaximumPercent \$CPUMaximumPercent -CPUReserve \$CPUReserve" +
                    " -NumaIsolationRequired \$NumaIsolationRequired -NetworkUtilizationMbps \$NetworkUtilizationMbps" +
                    " -CPURelativeWeight \$CPURelativeWeight " +
                    "-HighlyAvailable ${highlyAvailable ? '\$true' : '\$false'} " +
                    "-DRProtectionRequired \$DRProtectionRequired -CPULimitFunctionality \$CPULimitFunctionality" +
                    " -CPULimitForMigration \$CPULimitForMigration" +
                    " -CheckpointType Production" +
                    " ${scvmmCapabilityProfile ? '-CapabilityProfile \$CapabilityProfile' : ''}" +
                    " -Generation $generationNumber -JobGroup $hardwareGuid"
            commands << HARDWARE_PROFILE_GET_CMD +
                    " where {\$_.Name -eq \"$hardwareProfileName\"}"

            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.HAVMPriority)))" +
                    SET_HARDWARE_PROFILE_CMD +
                    "-HAVMPriority \$template.HAVMPriority}"

            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.ReplicationGroup)))" +
                    SET_HARDWARE_PROFILE_CMD +
                    "-ReplicationGroup \$template.ReplicationGroup}"
            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.SecureBootEnabled)))" +
                    SET_HARDWARE_PROFILE_CMD +
                    "-SecureBootEnabled \$template.SecureBootEnabled}"
            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.NumLock)))" +
                    " { Set-SCHardwareProfile -HardwareProfile \$HardwareProfile -NumLock \$template.NumLock}"
            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.Owner)))" +
                    " { Set-SCHardwareProfile -HardwareProfile \$HardwareProfile -Owner \$template.Owner}"
            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.UserRole)))" +
                    " { Set-SCHardwareProfile -HardwareProfile \$HardwareProfile -UserRole \$template.UserRole}"
            commands << "if( \$DynamicMemoryEnabled -eq \$True) {"
            // We have 3 memories to deal with... startup, min, and max
            // SCVMMM blows up if we are adjusting values and the startup memory is less than
            // the min memory, or the max memory is less than the startup memory, etc.. ugh
            commands << "\$startupMemory = ${memoryMB}; If (\$template.Memory -gt ${memoryMB}) " +
                    "{ \$startupMemory = \$template.Memory };"
            if (minDynamicMemoryMB) {
                commands << "\$minimumDynamicMemory = ${minDynamicMemoryMB}; " +
                        IF_STARTUP_GT_MIN_DYNAMIC_MEMORY
            } else {
                commands << "\$minimumDynamicMemory = \$template.DynamicMemoryMinimumMB; " +
                        IF_STARTUP_GT_MIN_DYNAMIC_MEMORY
            }
            if (maxDynamicMemoryMB) {
                commands << "\$maximumDynamicMemory = ${maxDynamicMemoryMB}; " +
                        IF_STARTUP_GT_MAX_DYNAMIC_MEMORY
            } else {
                commands << "\$maximumDynamicMemory = \$template.DynamicMemoryMaximumMB; " +
                        IF_STARTUP_GT_MAX_DYNAMIC_MEMORY
            }
            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.Memory))) " +
                    SET_HARDWARE_PROFILE_CMD +
                    "-DynamicMemoryEnabled \$True -MemoryMB \$startupMemory}"
            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.DynamicMemoryMaximumMB)))" +
                    SET_HARDWARE_PROFILE_CMD +
                    "-DynamicMemoryEnabled \$True -DynamicMemoryMinimumMB \$minimumDynamicMemory}"
            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.DynamicMemoryMaximumMB))) " +
                    SET_HARDWARE_PROFILE_CMD +
                    "-DynamicMemoryEnabled \$True -DynamicMemoryMaximumMB \$maximumDynamicMemory}"
            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.DynamicMemoryBufferPercentage)))" +
                    SET_HARDWARE_PROFILE_CMD +
                    "-DynamicMemoryEnabled \$True -DynamicMemoryBufferPercentage" +
                    " \$template.DynamicMemoryBufferPercentage}"
            commands << CLOSE_BRACE
            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.MonitorMaximumCount)))" +
                    SET_HARDWARE_PROFILE_CMD +
                    "-MonitorMaximumCount \$template.MonitorMaximumCount}"
            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.MonitorMaximumResolution))) " +
                    SET_HARDWARE_PROFILE_CMD +
                    "-MonitorMaximumResolution \$template.MonitorMaximumResolution}"
            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.RecoveryPointObjective))) " +
                    SET_HARDWARE_PROFILE_CMD +
                    "-RecoveryPointObjective \$template.RecoveryPointObjective}"
            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.ProtectionProvider))) " +
                    SET_HARDWARE_PROFILE_CMD +
                    "-ProtectionProvider \$template.ProtectionProvider}"
            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.BootOrder))) " +
                    "{ Set-SCHardwareProfile -HardwareProfile \$HardwareProfile -BootOrder \$template.BootOrder}"
            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.FirstBootDevice)) " +
                    "-and @('CD','PXE','SCSI') -contains \$template.FirstBootDevice) " +
                    SET_HARDWARE_PROFILE_CMD +
                    "-FirstBootDevice \$template.FirstBootDevice; Write-Output \"FirstBootDevice set " +
                    "successfully to: \$(\$template.FirstBootDevice)\" }"
            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.SecureBootTemplate))) " +
                    SET_HARDWARE_PROFILE_CMD +
                    "-SecureBootTemplate \$template.SecureBootTemplate}"
            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.CPUType))) " +
                    "{ Set-SCHardwareProfile -HardwareProfile \$HardwareProfile -CPUType \$template.CPUType}"
            commands << "if( \$NumaIsolationRequired -eq \$True) {"
            commands << "\$ignore = If (-not ([string]::IsNullOrEmpty(\$template.CPUPerVirtualNumaNodeMaximum))) " +
                    SET_HARDWARE_PROFILE_CMD +
                    "-CPUPerVirtualNumaNodeMaximum \$template.CPUPerVirtualNumaNodeMaximum}"
            commands << IGNORE_IF_NOT +
                    "([string]::IsNullOrEmpty(\$template.MemoryPerVirtualNumaNodeMaximumMB)))" +
                    SET_HARDWARE_PROFILE_CMD +
                    "-MemoryPerVirtualNumaNodeMaximumMB \$template.MemoryPerVirtualNumaNodeMaximumMB}"
            commands << IGNORE_IF_NOT +
                    "([string]::IsNullOrEmpty(\$template.VirtualNumaNodesPerSocketMaximum))) " +
                    SET_HARDWARE_PROFILE_CMD +
                    "-VirtualNumaNodesPerSocketMaximum \$template.VirtualNumaNodesPerSocketMaximum}"
            commands << CLOSE_BRACE
            commands << IGNORE_IF_NOT +
                    "([string]::IsNullOrEmpty(\$template.AutomaticCriticalErrorAction))) " +
                    SET_HARDWARE_PROFILE_CMD +
                    "-AutomaticCriticalErrorAction \$template.AutomaticCriticalErrorAction}"
            commands << IGNORE_IF_NOT +
                    "([string]::IsNullOrEmpty(\$template.AutomaticCriticalErrorActionTimeout))) " +
                    "{ Set-SCHardwareProfile -HardwareProfile \$HardwareProfile" +
                    " -AutomaticCriticalErrorActionTimeout \$template.AutomaticCriticalErrorActionTimeout}"
        } else {
            commands << IGNORE_NEW_HARDWARE_PROFILE +
                    "-Name \"$hardwareProfileName\" -Description \"Morpheus created profile\" -CPUCount ${maxCores}" +
                    " -MemoryMB ${memoryMB} -DynamicMemoryEnabled \$false -MemoryWeight 5000 " +
                    "-VirtualVideoAdapterEnabled \$false -CPUExpectedUtilizationPercent 20 -DiskIops 0 " +
                    "-CPUMaximumPercent 100 -CPUReserve 0 -NumaIsolationRequired \$false " +
                    "-NetworkUtilizationMbps 0 -CPURelativeWeight 100 " +
                    "-HighlyAvailable ${highlyAvailable ? '\$true' : '\$false'} " +
                    "-DRProtectionRequired \$false -CPULimitFunctionality \$false " +
                    "-CPULimitForMigration \$false -CheckpointType " +
                    "Production ${scvmmCapabilityProfile ? '-CapabilityProfile \$CapabilityProfile' : ''} " +
                    "-Generation $generationNumber -JobGroup $hardwareGuid"
            commands << HARDWARE_PROFILE_GET_CMD +
                    " where {\$_.Name -eq \"$hardwareProfileName\"}"
        }

        if (minDynamicMemoryMB && maxDynamicMemoryMB) {
            commands << IGNORE_SET_HARDWARE_PROFILE +
                    "-DynamicMemoryEnabled \$True -DynamicMemoryMinimumMB ${minDynamicMemoryMB}"
            commands << IGNORE_SET_HARDWARE_PROFILE +
                    "-DynamicMemoryEnabled \$True -DynamicMemoryMaximumMB ${maxDynamicMemoryMB}"
        }

        if (deployingToCloud) {
            commands << "\$cloud = Get-SCCloud -ID \"${zone.regionCode}\""
        }

        if (!cloneVMId) {
            if (isSysprep) {
                commands << "\$OS = Get-SCOperatingSystem -VMMServer localhost | where {\$_.Name -eq \"$OSName\"}"
            }

            def diskJobGuid = UUID.randomUUID().toString()
            // Start with an SCVMM Template or a VHD Image to create the OS Disk and volume
            if (isTemplate && templateId) {
                log.debug("Using existing Template ID to create VM: ${templateId}")
                // For a Template its all good
            } else {
                // virtualImage is an SCVMM VHD - locate this and use to form a Temporary Template
                commands << "\$VirtualHardDisk = Get-SCVirtualHardDisk -VMMServer localhost -ID \"${imageId}\""
                if (volumePath && !deployingToCloud) {
                    commands << IGNORE_NEW_VIRTUAL_DISK_DRIVE +
                            "localhost ${generationNumber == OBJECT_TYPE_1 ? '-IDE' : '-SCSI'} -Bus 0 -LUN 0 " +
                            "-JobGroup $diskJobGuid -CreateDiffDisk \$false -VirtualHardDisk \$VirtualHardDisk " +
                            "-Path \"$volumePath\" -VolumeType BootAndSystem"
                } else {
                    commands << IGNORE_NEW_VIRTUAL_DISK_DRIVE +
                            "localhost ${generationNumber == OBJECT_TYPE_1 ? '-IDE' : '-SCSI'} -Bus 0 -LUN 0 " +
                            "-JobGroup $diskJobGuid -CreateDiffDisk \$false -VirtualHardDisk \$VirtualHardDisk " +
                            "-VolumeType BootAndSystem"
                }

                dataDisks?.eachWithIndex { dataDisk, index ->
                    def fromDisk
                    if (isSyncdImage) {
                        fromDisk = "\$VirtualHardDisk${index}"
                        def diskExternalId = diskExternalIdMappings[1 + index]?.externalId
                        if (diskExternalId) {
                            commands << "${fromDisk} = Get-SCVirtualHardDisk -VMMServer " +
                                    "localhost -ID \"${diskExternalId}\""
                        }
                    }
                    def busNumber = EXIT_CODE_SUCCESS
                    def generateResults = generateDataDiskCommand(busNumber, index, diskJobGuid,
                            (int) (dataDisk.maxStorage / ComputeUtility.ONE_MEGABYTE), dataDisk.volumePath,
                            fromDisk, deployingToCloud)
                    commands << generateResults.command
                }

                // Create the Temporary Template

                commands << "\$ignore = New-SCVMTemplate -Name \"$templateName\" " +
                        "-Generation $generationNumber -HardwareProfile \$HardwareProfile " +
                        "-JobGroup $diskJobGuid ${isSysprep ? '-OperatingSystem $OS' : '-NoCustomization'}"
                commands << "if( -not \$? ) { Exit 23 }"
                commands << "\$template = Get-SCVMTemplate -All | where { \$_.Name -eq \"$templateName\" }"
            }

            // Use the Template to create a VM Configuration

            commands << "\$virtualMachineConfiguration = New-SCVMConfiguration -VMTemplate \$template -Name \"$vmId\""

            if (doStatic && doPool) {
                commands << "\$VNAConfig = Get-SCVirtualNetworkAdapterConfiguration " + VM_CONFIGURATION_PARAM
                if (doPool) {
                    commands << "\$ippool = Get-SCStaticIPAddressPool -ID \"$poolId\""
                    commands << "\$ipaddress = Get-SCIPAddress -IPAddress \"$ipAddress\""
                } else {
                    commands << "\$ipaddress = \"$ipAddress\""
                }

                commands << "\$ignore = Set-SCVirtualNetworkAdapterConfiguration -VirtualNetworkAdapterConfiguration \$VNAConfig ${doPool ? "-IPv4Address \$ipaddress -IPv4AddressPool \$ippool" : "-IPv4Address \$ipAddress"} -MACAddress \"00:00:00:00:00:00\""
            }

//			commands << "Write-Output \$virtualMachineConfiguration"

            if (isSysprep && unattendPath) {
                // Need to fetch the answerFile
                commands << "\$AnswerFile = Get-SCScript | where {\$_.IsXMLAnswerFile -eq \$True} |" +
                        " where {\$_.SharePath -eq \"$unattendPath\"}"
            }

            // OS Disk configured in Template: Prepare to deploy VM
            if (deployingToCloud) {
                // deployingToCloud - then assign cloud Only - no need to pin Storage paths
                // Deploying to a Cloud $cloud should be available
                commands << IGNORE_SET_VM_CONFIGURATION +
                        " -CapabilityProfile \$CapabilityProfile -cloud \$cloud"
                commands << IGNORE_UPDATE_VM_CONFIGURATION
                def newVMString = "\$createdVm = New-SCVirtualMachine" +
                        " -Name \"$vmId\" -VMConfiguration \$virtualMachineConfiguration" +
                        " ${isSysprep ? "-AnswerFile \$AnswerFile" : ""} -HardwareProfile \$HardwareProfile" +
                        " -JobGroup \"$diskJobGuid\" -StartAction \"TurnOnVMIfRunningWhenVSStopped\" " +
                        RUN_ASYNCHRONOUSLY_STOP_ACTION
                newVMString = appendOSCustomization(newVMString, opts)
                commands << newVMString
            } else {
                // HostGroup deployment NOT TO CLOUD
                if (hostExternalId) {
                    commands << "\$vmHost = Get-SCVMHost -ID \"$hostExternalId\""
                    commands << IGNORE_SET_VM_CONFIGURATION +
                            " -VMHost \$vmHost"
                    commands << IGNORE_UPDATE_VM_CONFIGURATION
                    // Do Not map Additional volumes here - done later
                    def newVMString = "\$createdVm = New-SCVirtualMachine -Name \"$vmId\" -VMConfiguration" +
                            " \$virtualMachineConfiguration ${isSysprep ? "-AnswerFile \$AnswerFile" : ""} " +
                            "${isTemplate ? "-HardwareProfile \$HardwareProfile" : ""} " +
                            "-JobGroup \"$diskJobGuid\" -StartAction \"TurnOnVMIfRunningWhenVSStopped\" " +
                            RUN_ASYNCHRONOUSLY_STOP_ACTION
                    newVMString = appendOSCustomization(newVMString, opts)
                    commands << newVMString
                } else {
                    log.error("buildCreateServerCommands : No Host provided")
                }
            }
        } else {
            // Clone request
            def virtualNetworkGuid = UUID.randomUUID().toString()
            commands << "\$VM = Get-SCVirtualMachine -VMMServer localhost -ID \"${cloneVMId}\""
            commands << "if (\$VMNetwork.VMSubnet) { if (\$VMNetwork.VMSubnet -is [Array] " +
                    "-or \$VMNetwork.VMSubnet -is [System.Collections.Generic.List[Microsoft.SystemCenter." +
                    "VirtualMachineManager.VMSubnet]]) { \$VMSubnet = \$VMNetwork.VMSubnet[0]; } " +
                    "else { \$VMSubnet = \$VMNetwork.VMSubnet } }"
            commands << "\$VirtualNetworkAdapter = Get-SCVirtualNetworkAdapter -VMMServer localhost -VM \$VM"
            commands << "\$VirtualNetwork = Get-SCVirtualNetwork -VMMServer localhost " +
                    "-Name \$VirtualNetworkAdapter.VirtualNetwork | Select-Object -first 1"
            def ipConfig = ""
            if (doStatic) {
                ipConfig = "-IPv4AddressType Static -IPv4Address \"${ipAddress}\""
            } else {
                ipConfig = "-IPv4AddressType Dynamic"
            }
            commands << "if (\$VMSubnet) {"
            commands << SET_VIRTUAL_NETWORK_ADAPTER +
                    "-VMNetwork \$VMNetwork -VMSubnet " +
                    "\$VMSubnet ${vlanEnabled ? "-VLanEnabled \$true" : ""} " +
                    "${vlanEnabled ? "-VLanID ${vlanId}" : ''} -VirtualNetwork \$VirtualNetwork -" +
                    "MACAddressType Dynamic ${ipConfig} -IPv6AddressType Dynamic " +
                    "-NoPortClassification -EnableVMNetworkOptimization \$false -EnableMACAddressSpoofing \$false" +
                    " -JobGroup $virtualNetworkGuid"
            commands << ELSE_CLOSE
            commands << SET_VIRTUAL_NETWORK_ADAPTER +
                    "-VMNetwork \$VMNetwork ${vlanEnabled ? "-VLanEnabled \$true" : ""}" +
                    " ${vlanEnabled ? "-VLanID ${vlanId}" : ''} -VirtualNetwork \$VirtualNetwork -MACAddressType" +
                    " Dynamic ${ipConfig} -IPv6AddressType Dynamic -NoPortClassification " +
                    "-EnableVMNetworkOptimization \$false -EnableMACAddressSpoofing \$false " +
                    "-JobGroup $virtualNetworkGuid"
            commands << CLOSE_BRACE
            if (hostExternalId) {
                commands << "\$vmHost = Get-SCVMHost -ID \"$hostExternalId\""
                def newVMString
                if (deployingToCloud) {
                    newVMString = "\$createdVm = New-SCVirtualMachine -VM \$VM -Name \"$vmId\" " +
                            "-JobGroup $virtualNetworkGuid -UseDiffDiskOptimization -RunAsynchronously " +
                            CLOUD_HARDWARE_PROFILE_START_ACTION +
                            TURN_ON_VM_IF_RUNNING_WHEN_VS_STOPPED
                } else {
                    newVMString = "\$createdVm = New-SCVirtualMachine -VM \$VM -Name \"$vmId\" " +
                            "-JobGroup $virtualNetworkGuid -UseDiffDiskOptimization -RunAsynchronously " +
                            "-VMHost \$vmHost -Path \"$volumePath\" -HardwareProfile \$HardwareProfile " +
                            "-StartAction TurnOnVMIfRunningWhenVSStopped -StopAction SaveVM"
                }
                newVMString = appendOSCustomization(newVMString, opts)
                commands << newVMString
            } else {
                def newVMString = "\$createdVm = New-SCVirtualMachine -VM \$VM " +
                        "-Name \"$vmId\" ${deployingToCloud ? "-Cloud \$cloud" : ""} " +
                        "-JobGroup $virtualNetworkGuid -UseDiffDiskOptimization -RunAsynchronously " +
                        CLOUD_HARDWARE_PROFILE_START_ACTION +
                        TURN_ON_VM_IF_RUNNING_WHEN_VS_STOPPED
                newVMString = appendOSCustomization(newVMString, opts)
                commands << newVMString
            }
        }
        commands << "\$createdVm | Select ID, ObjectType"

        rtn.launchCommand = commands.join(NEWLINE_CHAR)
        rtn.hardwareProfileName = hardwareProfileName
        rtn.templateName = templateName
        return rtn
    }

    @SuppressWarnings('MethodParameterTypeRequired')
    Integer findBootDiskIndex(diskDrives) {
        def bookDiskIndex = 0
        diskDrives.disks?.eachWithIndex { disk, diskIndex ->
            if (disk.VolumeType == 'BootAndSystem') {
                bookDiskIndex = diskIndex
            }
        }
        return bookDiskIndex
    }

    String appendOSCustomization(String sourceString, Map opts) {
        def retString = sourceString
        if (opts.isSysprep && !opts.cloneVMId) {
            if (opts.hostname) {
                retString += " -ComputerName \"${opts.hostname}\""
            }
            if (opts.license?.fullName) {
                retString += " -FullName \"${opts.license.fullName}\""
            }
            if (opts.license?.productKey) {
                retString += " -ProductKey \"${opts.license.productKey}\""
            }
            if (opts.license?.orgName) {
                retString += " -OrganizationName \"${opts.license.orgName}\""
            }
        }
        return retString
    }

    @SuppressWarnings(['ParameterCount', 'UnusedMethodParameter', 'UnnecessaryToString', 'MethodParameterTypeRequired'])
    Map generateDataDiskCommand(String busNumber = EXIT_CODE_SUCCESS, Integer dataDiskNumber,
                                String diskJobGuid, int sizeMB,
                                path = null, fromDisk = null, Boolean discoverAvailableLUN = false,
                                Boolean deployingToCloud = false) {
        def rtn = [command: null, fileName: '']

        // def diskParamMap = [
        //         [type: 'IDE', bus: '0', lun: '1'],
        //         [type: 'IDE', bus: '1', lun: '1']
        // ]

        def diskParams
        // if(dataDiskNumber > 1) {

        // } else {
        // 	diskParams = diskParamMap[dataDiskNumber]
        // }
        diskParams = [type: 'SCSI', bus: busNumber, lun: (dataDiskNumber).toString()]

        def fileName = "data${dataDiskNumber}-${UUID.randomUUID().toString()}.vhd"
        rtn.fileName = fileName

        if (fromDisk && !deployingToCloud) {
            if (path) {
                rtn.command = "\$ignore = New-SCVirtualDiskDrive -VMMServer localhost -${diskParams.type} " +
                        "-Bus ${diskParams.bus} -LUN ${diskParams.lun} -JobGroup ${diskJobGuid} " +
                        "-CreateDiffDisk \$false -VirtualHardDisk $fromDisk -FileName \"$fileName\" " +
                        "-Path \"$path\" -VolumeType None"
                // Can't set size when creating from another existing disk
            } else {
                rtn.command = "\$ignore = New-SCVirtualDiskDrive -VMMServer localhost -${diskParams.type} " +
                        "-Bus ${diskParams.bus} -LUN ${diskParams.lun} " +
                        "-JobGroup ${diskJobGuid} -CreateDiffDisk \$false -VirtualHardDisk $fromDisk " +
                        "-FileName \"$fileName\" -VolumeType None"
                // Can't set size when creating from another existing disk
            }
        } else {
            if (path && !deployingToCloud) {
                rtn.command = "\$ignore = New-SCVirtualDiskDrive -VMMServer localhost -${diskParams.type} " +
                        "-Bus ${diskParams.bus} -LUN ${diskParams.lun} -JobGroup ${diskJobGuid} " +
                        "-VirtualHardDiskSizeMB ${sizeMB} -CreateDiffDisk \$false -Dynamic " +
                        "-FileName \"$fileName\" -Path \"$path\" -VolumeType None"
            } else {
                rtn.command = "\$ignore = New-SCVirtualDiskDrive -VMMServer localhost -${diskParams.type} " +
                        "-Bus ${diskParams.bus} -LUN ${diskParams.lun} -JobGroup ${diskJobGuid} " +
                        "-VirtualHardDiskSizeMB ${sizeMB} -CreateDiffDisk \$false -Dynamic " +
                        "-FileName \"$fileName\" -VolumeType None"
            }
        }
        return rtn
    }

    Map getScvmmZoneOpts(MorpheusContext context, Cloud cloud) {
        def cloudConfig = cloud.configMap
        def keyPair = context.services.keyPair.find(
                new DataQuery().withFilter(ACCOUNT_ID, cloud?.account?.id))
        return [
                account      : cloud.account,
                zoneConfig   : cloudConfig,
                zone         : cloud,
                zoneId       : cloud?.id,
                publicKey    : keyPair?.publicKey,
                privateKey   : keyPair?.privateKey,
                // controllerServer       : controllerServer,
                rootSharePath: cloudConfig[LIBRARY_SHARE],
                regionCode   : cloud.regionCode,
        ]
        // baseBoxProvisionService: scvmmProvisionService]
    }

    Map getScvmmCloudOpts(MorpheusContext context, Cloud cloud, ComputeServer controllerServer) {
        def cloudConfig = cloud.configMap
        def keyPair = context.services.keyPair.find(new DataQuery().withFilter(
                ACCOUNT_ID, cloud?.account?.id))
        return [
                account         : cloud.account,
                zoneConfig      : cloudConfig,
                zone            : cloud,
                zoneId          : cloud?.id,
                publicKey       : keyPair?.publicKey,
                privateKey      : keyPair?.privateKey,
                controllerServer: controllerServer,
                rootSharePath   : cloudConfig[LIBRARY_SHARE],
                regionCode      : cloud.regionCode,
        ]
    }

    @SuppressWarnings('TernaryCouldBeElvis')
    Map getScvmmControllerOpts(Cloud cloud, ComputeServer hypervisor) {
        def serverConfig = hypervisor.configMap
        def zoneConfig = cloud.configMap
        log.debug("scvmm hypervisor config:${serverConfig}")
        def configuredDiskPath = zoneConfig.diskPath?.length() > 0
                ? zoneConfig.diskPath
                : serverConfig.diskPath?.length() > 0
                ? serverConfig.diskPath
                : null
        def diskRoot = configuredDiskPath ? configuredDiskPath : defaultRoot + DISKS_FOLDER
        def configuredWorkingPath = zoneConfig.workingPath?.length() > 0
                ? zoneConfig.workingPath
                : serverConfig.workingPath?.length() > 0
                ? serverConfig.workingPath
                : null
        def zoneRoot = configuredWorkingPath ? configuredWorkingPath : defaultRoot
        return [hypervisorConfig: serverConfig, hypervisor: hypervisor, sshHost: hypervisor.sshHost,
                sshUsername     : hypervisor.sshUsername,
                sshPassword     : hypervisor.sshPassword, zoneRoot: zoneRoot, diskRoot: diskRoot]
    }

    Map getScvmmZoneAndHypervisorOpts(MorpheusContext morpheusContext, Cloud cloud, ComputeServer hypervisor) {
        return getScvmmCloudOpts(morpheusContext, cloud, hypervisor) + getScvmmControllerOpts(cloud, hypervisor)
    }

    TaskResult wrapExecuteCommand(String command, Map opts = [:]) {
        def out = executeCommand(command, opts)

        if (out.data) {
            def payload = out.data
            if (!out.data.startsWith('[')) {
                payload = "[${out.data}]"
            }
            try {
                log.debug "Received: ${JsonOutput.prettyPrint(payload)}"
            } catch (e) {
//				File file = new File("/Users/bob/Desktop/bad.json")
//				file.write payload
                log.error("An error occurred : ${e.message}", e)
            }
            out.data = new groovy.json.JsonSlurper().parseText(payload)
        }
        return out
    }

    void loadControllerServer(Map opts) {
        /* if (opts.controllerServerId && opts.scvmmProvisionService) {
            opts.controllerServer = opts.scvmmProvisionService.loadControllerServer(opts.controllerServerId)
        } */
        if (opts.controllerServerId) {
            opts.controllerServer = morpheusContext.services.computeServer.get(opts.controllerServerId)
        }
    }

    Boolean isHostInHostGroup(String currentHostPath, String testHostPath) {
        return (currentHostPath == testHostPath || (testHostPath && currentHostPath?.startsWith(testHostPath + "\\")))
    }

    String extractFileName(String imageName) {
        def rtn = imageName
        def lastIndex = imageName?.lastIndexOf(FORWARD_SLASH)
        if (lastIndex > INDEX_NOT_FOUND) {
            rtn = imageName.substring(lastIndex + 1)
        }
        return rtn
    }

    String extractImageFileName(String imageName) {
        def rtn = extractFileName(imageName)
        if (rtn.indexOf(TAR_GZ_EXTENSION) > INDEX_NOT_FOUND) {
            rtn = rtn.replaceAll(TAR_GZ_EXTENSION, '')
        }
        if (rtn.indexOf(DOT_GZ) > INDEX_NOT_FOUND) {
            rtn = rtn.replaceAll(DOT_GZ, '')
        }
        return rtn
    }

    String formatImageFolder(String imageName) {
        def rtn = imageName
        rtn = rtn.replaceAll(' ', UNDERSCORE)
        rtn = rtn.replaceAll('\\.', UNDERSCORE)
        return rtn
    }

    Map getScvmmInitializationOpts(Cloud cloud) {
        def cloudConfig = cloud.configMap
        def diskRoot = cloudConfig.diskPath?.length() > 0 ? cloudConfig.diskPath : defaultRoot + DISKS_FOLDER
        def cloudRoot = cloudConfig.workingPath?.length() > 0 ? cloudConfig.workingPath : defaultRoot
        return [sshHost    : cloudConfig.host, sshUsername: getUsername(cloud),
                sshPassword: getPassword(cloud), zoneRoot: cloudRoot,
                diskRoot   : diskRoot]
    }

    protected String getUsername(Cloud cloud) {
        return (
                (cloud.accountCredentialLoaded && cloud.accountCredentialData)
                        ? cloud.accountCredentialData?.username
                        : cloud.getConfigProperty('username')
        ) ?: 'dunno'
    }

    protected String getPassword(Cloud cloud) {
        return (
                cloud.accountCredentialLoaded && cloud.accountCredentialData
        )
                ? cloud.accountCredentialData?.password
                : cloud.getConfigProperty('password')
    }

    LogInterface getLog() {
        return this.log
    }
}
