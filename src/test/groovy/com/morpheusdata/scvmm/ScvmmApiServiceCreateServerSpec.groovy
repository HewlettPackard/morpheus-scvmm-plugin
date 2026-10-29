// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.scvmm.support.SpecFixtures
import com.morpheusdata.scvmm.support.Domain
import com.morpheusdata.scvmm.support.PsStub
import com.morpheusdata.scvmm.support.ScvmmSpec
import com.morpheusdata.scvmm.support.TaskResults

/**
 * {@code ScvmmApiService.createServer} orchestration. The script builder
 * ({@code buildCreateServerCommands}) and every sub-call that has its own spec (checkServerCreated,
 * listVirtualDiskDrives, resizeDisk, createAndAttachDisk, ISO helpers, getServerDetails) are stubbed so this
 * spec pins only the sequencing, result mapping and error handling of createServer itself.
 */
class ScvmmApiServiceCreateServerSpec extends ScvmmSpec {

    static final String LAUNCH = 'New-SCVirtualMachine -Name "vm-1"'

    ScvmmApiService svc
    List<String> scripts = []
    Map psResponses
    Cloud cloud = Domain.cloud()
    ComputeServer server = Domain.server(externalId: null)

    def setup() {
        svc = Spy(SpecFixtures.LoggingApiService, constructorArgs: [context])
        svc.pause(_) >> null
        psResponses = [('New-SCVirtualMachine'): TaskResults.ok([[ID: 'vm-new', ObjectType: 1]])]
        svc.wrapExecuteCommand(_ as String, _ as Map) >> { String cmd, Map o -> PsStub.capture(scripts, psResponses).call(cmd, o) }
        svc.buildCreateServerCommands(_) >> [launchCommand: LAUNCH, hardwareProfileName: 'hw-tmp', templateName: 'tpl-tmp']
        svc.listVirtualDiskDrives(_, _) >> [success: true, disks: [
                [ID: 'dd-0', VolumeType: 'BootAndSystem', VhdID: 'vhd-0', HostVolumeId: 'hv-0', FileShareId: null, VhdLocation: 'C:\\0.vhdx', PartitionUniqueId: 'p0'],
                [ID: 'dd-1', VolumeType: 'None', VhdID: 'vhd-1', HostVolumeId: 'hv-1', FileShareId: 'fs-1', VhdLocation: 'C:\\1.vhdx', PartitionUniqueId: 'p1']]]
        svc.getServerDetails(_, _) >> [success: true, server: [VMId: 'hv-guid']]
        cloudService.get(_) >> cloud
        computeServerService.get(_) >> server
        computeServerService.save(_) >> { args -> args[0] }
    }

    private Map baseOpts(Map extra = [:]) {
        [name: 'vm-1', zoneId: 1L, serverId: 10L, diskRoot: 'C:\\disks', serverFolder: 'vm-1',
         dataDisks: [Domain.volume(id: 61L, name: 'data-1', rootVolume: false, externalId: 'dd-1', maxStorage: 10737418240L)]] + extra
    }

    def "createServer returns the new VM id, VMId and a disk mapping keyed by VirtualDiskDrive id"() {
        when:
        def rtn = svc.createServer(baseOpts())

        then:
        1 * svc.checkServerCreated(_, 'vm-new') >> [success: true]
        rtn.success == true
        rtn.server.id == 'vm-new'
        rtn.server.VMId == 'hv-guid'
        rtn.server.name == 'vm-1'
        rtn.server.disks.osDisk.externalId == 'dd-0'
        rtn.server.disks.dataDisks == [[id: 61L, externalId: 'dd-1']]
        rtn.server.disks.diskMetaData['dd-0'].VhdID == 'vhd-0'
        rtn.server.disks.diskMetaData['dd-1'] == [HostVolumeId: 'hv-1', FileShareId: 'fs-1', dataDisk: true, VhdID: 'vhd-1', Location: 'C:\\1.vhdx', PartitionUniqueId: 'p1']
        !rtn.errorMsg
        !rtn.containsKey('deleteDvdOnComplete')
    }

    def "createServer saves the new externalId on the server before waiting for creation"() {
        when:
        svc.createServer(baseOpts())

        then:
        1 * computeServerService.save({ it.externalId == 'vm-new' }) >> { args -> args[0] }

        then:
        1 * svc.checkServerCreated(_, 'vm-new') >> [success: true]
    }

    def "createServer removes the temporary hardware profile and template twice, after creation and at the end"() {
        given:
        svc.checkServerCreated(_, _) >> [success: true]

        when:
        svc.createServer(baseOpts())

        then:
        scripts.first().contains(LAUNCH)
        scripts.count { it.contains('Remove-SCHardwareProfile') && it.contains('"hw-tmp"') && it.contains('Remove-SCVMTemplate') && it.contains('"tpl-tmp"') } == 2
    }

    def "createServer attaches one disk per additional template disk with its size in MB"() {
        given:
        svc.checkServerCreated(_, _) >> [success: true]
        def opts = baseOpts(additionalTemplateDisks: [[idx: 1, diskSize: 2147483648L], [idx: 2, diskSize: 1073741824L]])

        when:
        svc.createServer(opts)

        then:
        1 * svc.createAndAttachDisk(_, { it.sizeMb == 2048 && it.vhdName.startsWith('data-') }) >> [success: true]
        1 * svc.createAndAttachDisk(_, { it.sizeMb == 1024 }) >> [success: true]
    }

    def "createServer ignores a failed additional disk attach and still succeeds"() {
        given:
        svc.checkServerCreated(_, _) >> [success: true]
        svc.createAndAttachDisk(_, _) >> [success: false, error: 'no space']

        when:
        def rtn = svc.createServer(baseOpts(additionalTemplateDisks: [[idx: 1, diskSize: 1073741824L]]))

        then:
        rtn.success == true
    }

    def "createServer resizes the OS disk by its VHD id when osDiskSize is set and template data disks by maxStorage"() {
        given:
        svc.checkServerCreated(_, _) >> [success: true]

        when:
        svc.createServer(baseOpts(osDiskSize: 85899345920L, isTemplate: true, templateId: 'tpl-1'))

        then: 'the resize block runs inside the per-disk loop, so the OS disk is resized once per disk and a data disk once it has been mapped'
        2 * svc.resizeDisk(_, 'vhd-0', 85899345920L) >> [success: true]
        1 * svc.resizeDisk(_, 'vhd-1', 10737418240L) >> [success: true]
    }

    def "createServer still succeeds when a disk resize fails (result is only logged)"() {
        given:
        svc.checkServerCreated(_, _) >> [success: true]
        svc.resizeDisk(*_) >> [success: false, errOut: 'Cannot Resize a Differencing Disk']

        when:
        def rtn = svc.createServer(baseOpts(osDiskSize: 85899345920L))

        then:
        rtn.success == true
    }

    def "createServer on a clone fixes the boot disk VolumeType and re-mounts the source's cloud-init ISO"() {
        given:
        svc.checkServerCreated(_, _) >> [success: true]
        def cloneBase = [cloudInitIsoNeeded: true, cloudConfigBytes: [1, 2] as byte[], diskFolder: 'C:\\disks\\src', imageFolderName: 'src', clonedScvmmOpts: [externalId: 'src-vm']]

        when:
        def rtn = svc.createServer(baseOpts(cloneVMId: 'src-vm', cloneBaseOpts: cloneBase))

        then:
        1 * svc.changeVolumeTypeForClonedBootDisk(_, 'src-vm', 'vm-new') >> null
        1 * svc.importAndMountIso(cloneBase.cloudConfigBytes, 'C:\\disks\\src', 'src', [externalId: 'src-vm']) >> 'C:\\disks\\src\\config.iso'
        rtn.cloneBaseResults == [cloudInitIsoPath: 'C:\\disks\\src\\config.iso']
        rtn.success == true
    }

    def "createServer on a clone whose source needs no cloud-init ISO does not mount one"() {
        given:
        svc.checkServerCreated(_, _) >> [success: true]

        when:
        def rtn = svc.createServer(baseOpts(cloneVMId: 'src-vm', cloneBaseOpts: [cloudInitIsoNeeded: false]))

        then:
        1 * svc.changeVolumeTypeForClonedBootDisk(_, 'src-vm', 'vm-new') >> null
        0 * svc.importAndMountIso(*_)
        rtn.cloneBaseResults == null
    }

    def "createServer creates a DVD and mounts the cloud-init ISO for a non-sysprep image and asks finalize to remove it"() {
        given:
        svc.checkServerCreated(_, _) >> [success: true]

        when:
        def rtn = svc.createServer(baseOpts(cloudConfigBytes: [1] as byte[]))

        then:
        1 * svc.createDVD(_) >> [success: true]
        1 * svc.importAndMountIso(_, 'C:\\disks\\vm-1', 'vm-1', _) >> 'C:\\disks\\vm-1\\config.iso'
        rtn.deleteDvdOnComplete == [removeIsoFromDvd: true, deleteIso: 'C:\\disks\\vm-1\\config.iso']
    }

    def "createServer for a sysprep image imports Unattend.xml, skips the ISO and deletes the unattend file"() {
        given:
        svc.checkServerCreated(_, _) >> [success: true]

        when:
        def rtn = svc.createServer(baseOpts(isSysprep: true, cloudConfigUser: '<unattend/>', cloudConfigBytes: [1] as byte[]))

        then:
        1 * svc.importScript('<unattend/>', 'C:\\disks\\vm-1', 'vm-1', { it.fileName == 'Unattend.xml' }) >> 'C:\\disks\\vm-1\\Unattend.xml'
        0 * svc.createDVD(_)
        0 * svc.importAndMountIso(*_)
        1 * svc.deleteUnattend(_, 'C:\\disks\\vm-1\\Unattend.xml') >> null
        rtn.success == true
        !rtn.containsKey('deleteDvdOnComplete')
    }

    def "createServer maps a #gen compatibility error from the launch to a friendly errorMsg"() {
        given:
        psResponses[('New-SCVirtualMachine')] = TaskResults.fail(vmmError)

        when:
        def rtn = svc.createServer(baseOpts())

        then:
        0 * svc.checkServerCreated(*_)
        0 * computeServerService.save(_)
        rtn.success == false
        rtn.errorMsg == expected

        where:
        gen      | vmmError                                                            | expected
        'Gen2'   | 'Error (13206) ... the template which includes generation 2 ...'    | 'The virtual hard disk selected is not compatible with the template which include generation 2 virtual machine functionality.'
        'Gen1'   | 'Error (13206) ... the template which includes generation 1 ...'    | 'The virtual hard disk selected is not compatible with the template which include generation 1 virtual machine functionality.'
        'other'  | 'Error (801) host not found'                                        | 'Error in launching VM: Error (801) host not found'
    }

    def "createServer fails without saving when the launch returns #why"() {
        given:
        psResponses[('New-SCVirtualMachine')] = TaskResults.ok(data)

        when:
        def rtn = svc.createServer(baseOpts())

        then:
        0 * computeServerService.save(_)
        0 * svc.checkServerCreated(*_)
        rtn.success == false
        rtn.errorMsg.startsWith("Failed to create VM with command: ${LAUNCH}")

        where:
        why                    | data
        'no data'              | null
        'an empty list'        | []
        'a non-VM object'      | [[ID: 'job-1', ObjectType: 9]]
        'more than one object' | [[ID: 'a', ObjectType: 1], [ID: 'b', ObjectType: 1]]
    }

    def "createServer returns success false with no errorMsg and no server when checkServerCreated fails"() {
        when:
        def rtn = svc.createServer(baseOpts())

        then:
        1 * svc.checkServerCreated(_, 'vm-new') >> [success: false]
        0 * svc.listVirtualDiskDrives(*_)
        rtn == [success: false]
        server.externalId == 'vm-new'
        scripts.count { it.contains('Remove-SCHardwareProfile') } == 2
    }

    def "createServer returns the server map but success false when getServerDetails fails"() {
        given:
        svc.checkServerCreated(_, _) >> [success: true]

        when:
        def rtn = svc.createServer(baseOpts())

        then:
        1 * svc.getServerDetails(_, 'vm-new') >> [success: false, error: 'VM_NOT_FOUND']
        rtn.success == false
        rtn.server.id == 'vm-new'
        rtn.server.VMId == null
        !rtn.errorMsg
    }

    def "createServer catches an exception from the PowerShell boundary and returns its message"() {
        given:
        psResponses[('New-SCVirtualMachine')] = { String cmd -> throw new IllegalStateException('winrm timeout') }

        when:
        def rtn = svc.createServer(baseOpts())

        then:
        rtn.success == false
        rtn.errorMsg == 'winrm timeout'
    }
}
