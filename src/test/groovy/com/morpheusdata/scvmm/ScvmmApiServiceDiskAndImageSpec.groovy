// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.bertramlabs.plugins.karman.CloudFile
import com.morpheusdata.core.synchronous.MorpheusSynchronousFileCopyService
import com.morpheusdata.model.TaskResult
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.scvmm.support.Domain
import com.morpheusdata.scvmm.support.GrailsCodecs
import com.morpheusdata.scvmm.support.PsStub
import com.morpheusdata.scvmm.support.ScvmmSpec
import com.morpheusdata.scvmm.support.TaskResults
import groovy.json.JsonException
import io.reactivex.rxjava3.core.Single
import spock.lang.Subject
import spock.lang.Timeout

/**
 * ScvmmApiService disk, server-update and image/ISO builders. A real service with
 * {@code context.executeWindowsCommand} stubbed (validateServerConfig's closures touch the private {@code log} and
 * break on a Spy); {@link #spy} is used only where {@code pause()} must be stubbed (resizeDisk's job wait).
 */
@Timeout(10)
class ScvmmApiServiceDiskAndImageSpec extends ScvmmSpec {

    static final long GB = 1024L * 1024L * 1024L

    MorpheusSynchronousFileCopyService fileCopyService = Mock()

    @Subject
    ScvmmApiService svc

    ScvmmApiService spy

    List<String> scripts = []
    Map opts = [zone: Domain.cloud(), sshHost: 'scvmm.local', externalId: 'vm-1', zoneRoot: 'C:\\morpheus',
                rootSharePath: '\\\\lib\\share', hypervisor: Domain.controller()]

    def setup() {
        services.getFileCopy() >> fileCopyService
        svc = new ScvmmApiService(context)
        spy = Spy(ScvmmApiService, constructorArgs: [context])
        spy.pause(_) >> null
        GrailsCodecs.install()
    }

    def cleanup() {
        GrailsCodecs.uninstall()
    }

    private void respond(Map byCommand, Object fallback = TaskResults.raw('')) {
        Closure ps = fallback instanceof TaskResult ? PsStub.capture(scripts, byCommand, fallback)
                : PsStub.capture(scripts, byCommand + [(~/.*/): fallback])
        context.executeWindowsCommand(*_) >> { args -> Single.just(ps(args[4] as String, [:])) }
    }

    // ================================================================ createAndAttachDisk

    def "createAndAttachDisk fills the template and returns the drive found by its VHD name"() {
        given:
        respond(['New-SCVirtualDiskDrive @addDiskParams': TaskResults.json([success: true, BUS: 0, LUN: 2, vhdId: 'vhd-9']),
                 'Get-SCVirtualDiskDrive -VM $VM'       : TaskResults.json([[ID: 'drive-9', VhdID: 'vhd-9', VhdName: 'data-1']])])

        when:
        def rtn = svc.createAndAttachDisk(opts, [vhdName: 'data-1', sizeMb: 10240, vhdType: 'DynamicallyExpanding', vhdFormat: 'VHDX', vhdPath: 'D:\\vms'])

        then:
        rtn == [success: true, disk: [ID: 'drive-9', VhdID: 'vhd-9', VhdName: 'data-1']]
        with(scripts[0]) {
            contains('$vmId = "vm-1"')
            contains('$vhdName = "data-1"')
            contains('$sizeMB = 10240')
            contains('$vhdType = "DynamicallyExpanding"')
            contains('$vhdFormat = "VHDX"')
            contains('$vhdPath = "D:\\vms"')
        }
        scripts[1].contains('$vhdName = "data-1"')
    }

    def "createAndAttachDisk leaves type, format and path blank for the script defaults (FixedSize, VHDX on gen2)"() {
        given:
        respond(['Get-SCVirtualDiskDrive -VM $VM': TaskResults.json([[ID: 'drive-1']])], TaskResults.raw(''))

        when:
        svc.createAndAttachDisk(opts, [sizeMb: 1024])

        then:
        scripts[0] =~ ('\\$vhdName = "data-[0-9a-f-]{36}"')
        with(scripts[0]) {
            contains('$vhdType = ""')
            contains('$vhdFormat = ""')
            contains('$vhdPath = ""')
            contains('if ($vhdType -eq "") {$vhdType = "FixedSize"}')
            contains('if ($vhdType -eq "FixedSize") {$addDiskParams.Add("Fixed",$true)}')
        }
    }

    def "createAndAttachDisk returns success false with a reason when the attach is not confirmed"() {
        given:
        respond([:], result)

        expect:
        svc.createAndAttachDisk(opts, [sizeMb: 1024], returnDrives) == expected

        where:
        result                 | returnDrives || expected
        TaskResults.raw('', 1) | true         || [success: false, errOut: 'Failed to run the add-disk command on SCVMM']
        TaskResults.raw('')    | false        || [success: false, errOut: 'SCVMM did not confirm the disk was added']
    }

    def "createAndAttachDisk returns SCVMM's reason when the add-disk job fails, without looking up the drive"() {
        given:
        respond(['New-SCVirtualDiskDrive @addDiskParams': TaskResults.json([success: false, errOut: 'There is not enough space on the disk.'])])

        when:
        def rtn = svc.createAndAttachDisk(opts, [vhdName: 'data-1', sizeMb: 1024])

        then:
        rtn == [success: false, errOut: 'There is not enough space on the disk.']
        scripts.size() == 1
    }

    def "createAndAttachDisk returns success false when the added drive can't be found (#why)"() {
        given:
        respond(['New-SCVirtualDiskDrive @addDiskParams': TaskResults.json([success: true, vhdId: 'vhd-1']),
                 'Get-SCVirtualDiskDrive -VM $VM'       : result])

        expect:
        svc.createAndAttachDisk(opts, [vhdName: 'data-1', sizeMb: 1024]) == [success: false, errOut: 'Disk data-1 was added but could not be found on the VM']

        where:
        why             | result
        'an empty list' | TaskResults.raw('[]')
        'empty stdout'  | TaskResults.raw('')
    }

    // ================================================================ listVirtualDiskDrives / removeDisk

    def "listVirtualDiskDrives filters by VHD name when given and returns #expected"() {
        given:
        respond([:], result)

        when:
        def rtn = svc.listVirtualDiskDrives(opts, 'vm-1', name)

        then:
        rtn == expected
        scripts[0].contains("\$vhdName = \"${name ?: ''}\"")
        scripts[0].contains('$vmId = "vm-1"')

        where:
        name     | result                                     || expected
        'data-1' | TaskResults.json([[ID: 'd1']])              || [success: true, disks: [[ID: 'd1']]]
        null     | TaskResults.raw('')                         || [success: true, disks: '']
        null     | TaskResults.raw('', 1)                      || [success: false, disks: []]
    }

    def "removeDisk detaches the drive by VHD id in one job group and returns the raw result"() {
        given:
        respond([:], TaskResults.raw(''))

        when:
        def out = svc.removeDisk(opts, 'vhd-9')

        then:
        out.success
        scripts[0].contains('$VM = Get-SCVirtualMachine -VMMServer localhost -ID "vm-1"')
        scripts[0].contains('where { $_.VirtualHardDiskId -eq "vhd-9" }')
        (scripts[0] =~ /-JobGroup ([0-9a-f-]{36})/).collect { it[1] }.unique().size() == 1
    }

    // ================================================================ resizeDisk

    def "resizeDisk expands to the size in whole GB and waits for the job"() {
        given:
        respond(['Expand-SCVirtualDiskDrive': TaskResults.json([success: true, jobId: 'job-1', errOut: null]),
                 'Get-SCJob'                : [TaskResults.json([ID: 'job-1', Status: 'Running']), TaskResults.json([ID: 'job-1', Status: 'Completed'])]])

        when:
        def rtn = spy.resizeDisk(opts, 'vhd-9', 21L * GB + 5)

        then:
        rtn == [success: true]
        scripts[0].contains('$newSize = 21')
        scripts[0].contains('$diskId = "vhd-9"')
        scripts[0].contains('VirtualHardDiskSizeGB=$newSize')
        scripts.count { it.contains('Get-SCJob -VMMServer localhost -ID "job-1"') } == 2
    }

    def "resizeDisk returns success=false when the expand job ends #status"() {
        given:
        respond(['Expand-SCVirtualDiskDrive': TaskResults.json([success: true, jobId: 'job-1']),
                 'Get-SCJob'                : TaskResults.json([ID: 'job-1', Status: status])])

        expect:
        spy.resizeDisk(opts, 'vhd-9', 21L * GB) == [success: false]

        where:
        status << ['Failed']
    }

    def "resizeDisk returns the script's error record when VMM refuses the expand"() {
        given:
        respond(['Expand-SCVirtualDiskDrive': TaskResults.json([success: false, jobId: null, errOut: 'Cannot Resize a Differencing Disk or a Disk with Checkpoints'])])

        when:
        def rtn = spy.resizeDisk(opts, 'vhd-9', 21L * GB)

        then: 'shape is {success, jobId, errOut} — callers must read errOut, not msg'
        rtn == [success: false, jobId: null, errOut: 'Cannot Resize a Differencing Disk or a Disk with Checkpoints']
        0 * spy.pause(_)
    }

    def "resizeDisk reports a synthetic errOut for an empty response"() {
        given:
        respond([:], result)

        expect:
        spy.resizeDisk(opts, 'vhd-9', 21L * GB) == [success: false, errOut: 'resizeDisk - did not receive expected response from rpc']

        where:
        result << [TaskResults.raw(''), TaskResults.raw('', 1)]
    }

    def "waitForJobToComplete accepts SucceedWithInfo and gives up after 351 polls of a missing job"() {
        given:
        respond(['Get-SCJob': result])

        expect:
        spy.waitForJobToComplete(opts, 'job-1') == [success: expected]
        scripts.size() == polls

        where:
        result                                                    || expected | polls
        TaskResults.json([ID: 'job-1', Status: 'SucceedWithInfo']) || true     | 1
        TaskResults.raw('', 1)                                     || false    | 351
    }

    // ================================================================ updateServer / validateServerConfig

    def "updateServer is a no-op success when nothing CPU or memory related changed"() {
        when:
        def rtn = svc.updateServer(opts, 'vm-1', updates)

        then:
        rtn == [success: true]
        0 * context.executeWindowsCommand(*_)

        where:
        updates << [[:], [maxSockets: 2], [maxStorage: 10L * GB]]
    }

    def "updateServer sets only the CPU count for a cores-only change"() {
        given:
        respond([:], TaskResults.raw(''))

        when:
        def rtn = svc.updateServer(opts, 'vm-1', [maxCores: 4])

        then:
        rtn.success
        scripts[0].contains('$ignore = Set-SCVirtualMachine -VM $VM -CPUCount 4;')
        !scripts[0].contains('MemoryMB')
    }

    def "updateServer writes static memory in MB with null dynamic bounds"() {
        given:
        respond([:], TaskResults.raw(''))

        when:
        svc.updateServer(opts, 'vm-1', [maxMemory: 8L * GB])

        then:
        scripts[0].contains('$maxMemory = 8192;')
        scripts[0].contains('$minDynamicMemory = $null;')
        scripts[0].contains('$maxDynamicMemory = $null;')
        scripts[0].contains('-DynamicMemoryEnabled $false -MemoryMB $maxMemory')
    }

    def "updateServer passes dynamic memory bounds in MB"() {
        given:
        respond([:], TaskResults.raw(''))

        when:
        svc.updateServer(opts, 'vm-1', [maxMemory: 4L * GB, minDynamicMemory: 1L * GB, maxDynamicMemory: 8L * GB])

        then:
        scripts[0].contains('$minDynamicMemory = 1024;')
        scripts[0].contains('$maxDynamicMemory = 8192;')
    }

    def "updateServer with only dynamic memory bounds emits no memory change"() {
        given:
        respond([:], TaskResults.raw(''))

        when:
        def rtn = svc.updateServer(opts, 'vm-1', [minDynamicMemory: 1L * GB])

        then: 'the memory block is guarded by maxMemory, so only the $VM lookup runs'
        rtn.success
        !scripts[0].contains('Set-SCVirtualMachine')
    }

    def "updateServer reports failure for #why"() {
        given:
        respond([:], result)

        expect:
        svc.updateServer(opts, 'vm-1', [maxCores: 4]) == [success: false]

        where:
        why                  | result
        'a failed command'   | TaskResults.raw('', 1)
        'a non-zero exit'    | new TaskResult(success: true, exitCode: '2', data: '')
        'non-JSON stdout'    | { String c -> TaskResults.raw('Set-SCVirtualMachine : error') }
    }

    def "validateServerConfig accepts a networkId"() {
        expect:
        svc.validateServerConfig([networkId: 5L]) == [success: true, errors: []]
    }

    def "validateServerConfig reports #field when it is present but empty"() {
        when:
        def rtn = svc.validateServerConfig([networkId: 5L] + input)

        then:
        !rtn.success
        rtn.errors == [[field: field, msg: msg]]

        where:
        input                          || field                    | msg
        [scvmmCapabilityProfile: null] || 'scvmmCapabilityProfile' | 'You must select a capability profile'
        [template: '']                 || 'template'               | 'Virtual image is required'
        [nodeCount: '']                || 'nodeCount'              | 'You must indicate number of hosts'
    }

    def "validateServerConfig requires a network when none is given"() {
        expect:
        svc.validateServerConfig([:]) == [success: false, errors: [[field: 'networkId', msg: 'Network is required']]]
    }

    def "validateServerConfig reports a NIC without a network"() {
        expect:
        svc.validateServerConfig([networkInterfaces: [[network: [id: null, group: null]]]]).errors == [[field: 'networkInterface', msg: 'Network is required']]
    }

    def "validateServerConfig replaces the error list with a single map for a static NIC without an IP"() {
        when:
        def rtn = svc.validateServerConfig([networkInterfaces: [[network: [id: 5L], ipMode: 'static']]])

        then: 'pinned: errors becomes a Map (size 2), not a list entry'
        !rtn.success
        rtn.errors == [field: 'networkInterface', msg: 'You must enter an ip address']
    }

    def "validateServerConfig swallows #why and returns success=false"() {
        expect:
        !svc.validateServerConfig(input).success

        where:
        why                                                | input
        'an NPE for a NIC without a network map'          | [networkInterfaces: [[ipMode: 'dhcp']]]
        'the undefined networkInterface var (UI params)'  | [networkInterface: [network: [id: ['5']]]]
    }

    // ================================================================ insertContainerImage / transferImage / findImage

    private Map imageOpts(Map image = [:]) {
        opts + [image: [name: 'ubuntu 22.04', imageType: 'vhdx', cloudFiles: []] + image]
    }

    def "insertContainerImage reuses a VHD already in the library share without transferring"() {
        given:
        respond(['Get-SCVirtualHardDisk': TaskResults.json([[ID: 'vhd-lib']])])

        when:
        def rtn = svc.insertContainerImage(imageOpts())

        then:
        rtn == [success: true, imageExists: false, imageId: 'vhd-lib']
        scripts == ['$FormatEnumerationLimit =-1; Get-SCVirtualHardDisk -VMMServer localhost | where {$_.SharePath -like "\\\\lib\\share\\images\\ubuntu_22_04\\*"} | Select ID | ConvertTo-Json -Depth 3']
        0 * fileCopyService._
    }

    def "insertContainerImage resolves the library share when the cloud has none configured"() {
        given:
        respond(['Get-SCLibraryShare'   : TaskResults.json([[ID: 's1', Path: '\\\\vmm\\MSSCVMMLibrary']]),
                 'Get-SCVirtualHardDisk': TaskResults.json([[ID: 'vhd-lib']])])

        when:
        def rtn = svc.insertContainerImage(imageOpts() + [rootSharePath: null])

        then:
        rtn.imageId == 'vhd-lib'
        scripts[1].contains('\\\\vmm\\MSSCVMMLibrary\\images\\ubuntu_22_04\\*')
    }

    def "getRootSharePath throws '#message' when #why"() {
        given:
        respond([:], result)

        when:
        svc.getRootSharePath(opts)

        then:
        def e = thrown(Exception)
        e.message == message

        where:
        why                 | result                  || message
        'the lookup fails'  | TaskResults.raw('', 1)  || 'Error in getting library share'
        'no share exists'   | TaskResults.raw('[]')   || 'No library share found'
    }

    def "insertContainerImage imports an image already staged on the host and cleans up the staging folder"() {
        given:
        respond(['Import-SCLibraryPhysicalResource': TaskResults.json([[ID: 'vhd-new']]),
                 'Get-ChildItem'                    : TaskResults.raw('C:\\morpheus\\images\\ubuntu_22_04\\ubuntu 22.04.vhdx\r\n'),
                 'Remove-Item'                      : TaskResults.raw(''),
                 'Get-SCVirtualHardDisk'            : TaskResults.raw('')])

        when:
        def rtn = svc.insertContainerImage(imageOpts())

        then:
        rtn.success
        rtn.imageId == 'vhd-new'
        scripts.any { it.contains('Import-SCLibraryPhysicalResource -SourcePath "C:\\morpheus\\images\\ubuntu_22_04\\ubuntu 22.04.vhdx" -SharePath "\\\\lib\\share\\images\\ubuntu_22_04"') }
        scripts.any { it.contains('Remove-Item -LiteralPath "C:\\morpheus\\images\\ubuntu_22_04" -Recurse -Force') }
        0 * fileCopyService._
    }

    def "insertContainerImage falls back to Copy-Item when the library import reports an error"() {
        given:
        respond(['Import-SCLibraryPhysicalResource': { String c -> new TaskResult(success: false, exitCode: '1', data: '[]', error: 'import failed') },
                 'Copy-Item'                        : TaskResults.json([[ID: 'vhd-copied']]),
                 'Get-ChildItem'                    : TaskResults.raw('C:\\morpheus\\images\\ubuntu_22_04\\img.vhdx'),
                 'Get-SCVirtualHardDisk'            : TaskResults.raw('')])

        when:
        def rtn = svc.insertContainerImage(imageOpts())

        then:
        rtn.imageId == 'vhd-copied'
        scripts.any { it.contains('Read-SCLibraryShare') }
    }

    def "insertContainerImage throws when both the import and the Copy-Item fallback fail"() {
        given:
        def failed = { String c -> new TaskResult(success: false, exitCode: '1', data: '[]', error: 'denied') }
        respond(['Import-SCLibraryPhysicalResource': failed, 'Copy-Item': failed,
                 'Get-ChildItem'                    : TaskResults.raw('C:\\img.vhdx'),
                 'Get-SCVirtualHardDisk'            : TaskResults.raw('')])

        when:
        svc.insertContainerImage(imageOpts())

        then:
        def e = thrown(Exception)
        e.message == 'Error in importing physical resource'
    }

    def "insertContainerImage throws when the library VHD query fails"() {
        given:
        respond([:], TaskResults.raw('', 1))

        when:
        svc.insertContainerImage(imageOpts())

        then:
        def e = thrown(Exception)
        e.message == 'Error in getting Get-SCVirtualHardDisk'
    }

    private CloudFile cloudFile(String name) {
        Stub(CloudFile) {
            getName() >> name
            getInputStream() >> new ByteArrayInputStream(new byte[0])
            getContentLength() >> 10L
        }
    }

    def "insertContainerImage transfers an image missing from the host before importing it"() {
        given:
        respond(['Import-SCLibraryPhysicalResource': TaskResults.json([[ID: 'vhd-new']]),
                 'Get-ChildItem'                    : [TaskResults.raw(''), TaskResults.raw('C:\\morpheus\\images\\ubuntu_22_04\\disk.vhdx')],
                 'Get-SCVirtualHardDisk'            : TaskResults.raw('')])

        when:
        def rtn = svc.insertContainerImage(imageOpts(cloudFiles: [cloudFile('ubuntu/disk.vhdx.gz')]))

        then:
        1 * fileCopyService.copyToServer(opts.hypervisor, 'disk.vhdx.gz', 'C:\\morpheus\\images\\ubuntu_22_04\\disk.vhdx', _, 10L, null, true) >> ServiceResponse.success()
        rtn.success
        rtn.imageId == 'vhd-new'
        scripts.any { it.contains('mkdir "C:\\morpheus\\images\\ubuntu_22_04"') }
    }

    def "insertContainerImage reports 'Error transferring image' and does not import when the copy fails"() {
        given:
        respond(['Get-ChildItem': TaskResults.raw(''), 'Get-SCVirtualHardDisk': TaskResults.raw('')])
        fileCopyService.copyToServer(*_) >> ServiceResponse.error('agent offline')

        when:
        def rtn = svc.insertContainerImage(imageOpts(cloudFiles: [cloudFile('disk.vhdx')]))

        then:
        rtn == [success: false, imageExists: false, msg: 'Error transferring image']
        !scripts.any { it.contains('Import-SCLibraryPhysicalResource') }
    }

    def "transferImage also copies metadata.json as an image file because the vhd filter is always truthy"() {
        // Pinned: `name.indexOf('.vhdx')` is an int (-1 is truthy), so every non-folder, non-.morpkg file passes.
        given:
        respond([:], TaskResults.raw(''))
        List<String> targets = []
        fileCopyService.copyToServer(*_) >> { args -> targets << args[2]; ServiceResponse.success() }

        when:
        svc.transferImage(opts, [cloudFile('metadata.json'), cloudFile('disk.vhdx'), cloudFile('img.morpkg'), cloudFile('dir/')], 'img')

        then:
        targets == ['C:\\morpheus\\images\\img\\metadata.json', 'C:\\morpheus\\images\\img\\metadata.json', 'C:\\morpheus\\images\\img\\disk.vhdx']
    }

    def "transferImage reports failure with no files to copy"() {
        given:
        respond([:], TaskResults.raw(''))

        expect:
        svc.transferImage(opts, files, 'img').success == false

        where:
        files << [[], null]
    }

    def "findImage reports the staged file path, or no image for empty output"() {
        given:
        respond([:], result)

        expect:
        svc.findImage(opts, 'ubuntu 22.04') == expected

        where:
        result                                    || expected
        TaskResults.raw('C:\\x\\a.vhdx\r\n')      || [success: true, imageExists: true, imageName: 'C:\\x\\a.vhdx']
        TaskResults.raw('')                       || [success: true, imageExists: false]
        TaskResults.raw('', 1)                    || [success: false, imageExists: false]
    }

    // ================================================================ ISO / DVD

    def "createDVD starts probing at LUN #lun for #generation and retries up to 11 times in the script"() {
        given:
        respond([:], TaskResults.raw(''))

        when:
        def rtn = svc.createDVD(opts + [scvmmGeneration: generation])

        then:
        rtn == null
        scripts[0].contains("\$lunNumber = ${lun}")
        scripts[0].contains('$busNumber = 0')
        scripts[0].contains('For ($i=0; $i -le 10; $i++)')
        scripts[0].contains('$lunNumber = $lunNumber + 1')

        where:
        generation    || lun
        'generation1' || 0
        'generation2' || 1
        null          || 1
    }

    def "createDVD only logs a failed DVD creation"() {
        given:
        respond([:], TaskResults.raw('', 1))

        when:
        svc.createDVD(opts)

        then:
        noExceptionThrown()
    }

    def "setCdrom #action"() {
        given:
        respond([:], TaskResults.raw(''))

        when:
        svc.setCdrom(opts, path)

        then:
        scripts[0].contains('$vm = Get-SCVirtualMachine -VMMServer localhost -ID "vm-1"')
        scripts[0].count('-NoMedia') == 1
        scripts[0].contains('-ISO $iso') == (path != null)
        path == null || scripts[0].contains("\$_.SharePath -eq \"${path}\"")

        where:
        action                                 | path
        'ejects then mounts the library ISO'   | '\\\\lib\\share\\vm-1\\config.iso'
        'ejects whatever is mounted'           | null
    }

    def "deleteIso removes the ISO by share path"() {
        given:
        respond([:], TaskResults.raw(''))

        when:
        svc.deleteIso(opts, "${opts.rootSharePath}\\vm-1\\config.iso")

        then:
        scripts[0].contains('$_.SharePath -eq "\\\\lib\\share\\vm-1\\config.iso"')
        scripts[0].contains('Remove-SCISO -ISO $iso -Force')
    }

    def "importAndMountIso uploads the ISO, imports it into the library and mounts it"() {
        given:
        respond([:], TaskResults.raw(''))
        byte[] iso = 'iso-bytes'.bytes

        when:
        def path = svc.importAndMountIso(iso, 'C:\\disks\\vm-1', 'vm-1', opts)

        then:
        1 * fileCopyService.copyToServer(opts.hypervisor, 'config.iso', 'C:\\disks\\vm-1\\config.iso', _, 9L) >> ServiceResponse.success()
        path == '\\\\lib\\share\\vm-1\\config.iso'
        scripts.any { it.contains('Copy-Item -Path "C:\\disks\\vm-1\\config.iso" -Destination "\\\\lib\\share\\vm-1\\config.iso"') }
        scripts.last().contains('-ISO $iso')
    }

    def "importAndMountIso throws when the upload fails"() {
        given:
        respond([:], TaskResults.raw(''))
        fileCopyService.copyToServer(*_) >> ServiceResponse.error('agent offline')

        when:
        svc.importAndMountIso('x'.bytes, 'C:\\disks\\vm-1', 'vm-1', opts)

        then:
        def e = thrown(Exception)
        e.message.startsWith('ISO Upload to SCVMM Host Failed')
    }
}
