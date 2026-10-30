// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusProcessService
import com.morpheusdata.core.MorpheusStorageVolumeService
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Process
import com.morpheusdata.model.ProcessEvent
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.TaskResult
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

// A data disk SCVMM can't add after create/clone must not fail the provision: the VM is usable, so the volume is
// dropped from Morpheus (the guest will never see it) and SCVMM's reason is shown in the instance History.
class ScvmmDiskAttachFailureSpec extends Specification {
    private static final long GB = 1024L * 1024L * 1024L

    private static TaskResult taskResult(boolean success, data, String error = null) {
        def out = new TaskResult()
        out.success = success
        out.data = data
        out.error = error
        out
    }

    def "createAndAttachDisk returns SCVMM's reason when the add-disk job fails"() {
        given:
        ScvmmApiService service = Spy(ScvmmApiService, constructorArgs: [Mock(MorpheusContext)])
        service.wrapExecuteCommand(_ as String, _ as Map) >> taskResult(true, [[success: false, errOut: 'There is not enough space on the disk.']])

        when:
        def result = service.createAndAttachDisk([externalId: 'vm-1'], [vhdName: 'data-x', sizeMb: 20480])

        then: 'no exception from listing a disk that was never created'
        result == [success: false, errOut: 'There is not enough space on the disk.']
        0 * service.listVirtualDiskDrives(*_)
    }

    def "createAndAttachDisk reports a failed SCVMM command"() {
        given:
        ScvmmApiService service = Spy(ScvmmApiService, constructorArgs: [Mock(MorpheusContext)])
        service.wrapExecuteCommand(_ as String, _ as Map) >> taskResult(false, null, 'WinRM connection refused')

        expect:
        !service.createAndAttachDisk([externalId: 'vm-1'], [vhdName: 'data-x', sizeMb: 1024]).success
    }

    def "createAndAttachDisk returns the new drive when the disk is added"() {
        given:
        ScvmmApiService service = Spy(ScvmmApiService, constructorArgs: [Mock(MorpheusContext)])
        service.wrapExecuteCommand(_ as String, _ as Map) >> taskResult(true, [[success: true, vhdId: 'vhd-1']])
        service.listVirtualDiskDrives(_, 'vm-1', 'data-x') >> [success: true, disks: [[ID: 'drive-1', VhdID: 'vhd-1']]]

        expect:
        service.createAndAttachDisk([externalId: 'vm-1'], [vhdName: 'data-x', sizeMb: 1024]) == [success: true, disk: [ID: 'drive-1', VhdID: 'vhd-1']]
    }

    private Map providerWithContext() {
        def context = Mock(MorpheusContext)
        def async = Mock(MorpheusAsyncServices)
        def storageVolumes = Mock(MorpheusStorageVolumeService)
        def process = Mock(MorpheusProcessService)
        context.getAsync() >> async
        async.getStorageVolume() >> storageVolumes
        context.getProcess() >> process
        [provider: new ScvmmProvisionProvider(Mock(ScvmmPlugin), context), storageVolumes: storageVolumes, process: process]
    }

    def "a volume whose disk failed to attach is removed from Morpheus with a customer-facing reason"() {
        given: 'instance A clone: data-1 inherited, data-2 (20 GB) failed to attach'
        def m = providerWithContext()
        def dataTwo = new StorageVolume(id: 603L, name: 'data-2', maxStorage: 20 * GB)
        def server = new ComputeServer(id: 10L)
        server.volumes = [new StorageVolume(id: 601L, rootVolume: true, maxStorage: 40 * GB), new StorageVolume(id: 602L, name: 'data-1', maxStorage: 10 * GB), dataTwo]
        def scvmmOpts = [maxTotalStorage: 70 * GB]
        def createResults = [failedDisks: [[volumeId: 603L, diskSize: 20 * GB, error: 'There is not enough space on the disk.']], diskWarnings: []]

        when:
        def warnings = m.provider.removeFailedVolumes(server, createResults, scvmmOpts)

        then:
        1 * m.storageVolumes.remove([dataTwo], server, true) >> Single.just(true)
        scvmmOpts.maxTotalStorage == 50 * GB
        warnings == ["Volume 'data-2' (20 GB) could not be added to the VM: There is not enough space on the disk. It has been removed from this instance; add it again with Reconfigure once the issue is resolved."]
    }

    def "a failed disk without a known volume still produces a warning, plus any other disk warnings"() {
        given:
        def m = providerWithContext()
        def server = new ComputeServer(id: 10L)
        server.volumes = []
        def createResults = [failedDisks: [[volumeId: null, diskSize: 5 * GB, error: 'boom']], diskWarnings: ['A disk removed from the clone request (VHD v1) is still attached to the VM: denied. Remove it from the VM in SCVMM.']]

        when:
        def warnings = m.provider.removeFailedVolumes(server, createResults, [:])

        then:
        0 * m.storageVolumes.remove(*_)
        warnings.size() == 2
        warnings[0].startsWith('A 5 GB volume could not be added to the VM: boom.')
        warnings[1].contains('VHD v1')
    }

    def "volume warnings are recorded as a completed History step carrying the reason"() {
        given:
        def m = providerWithContext()
        def process = new Process(id: 7L)
        def warning = "Volume 'data-2' (20 GB) could not be added to the VM: There is not enough space on the disk."

        when:
        m.provider.reportVolumeWarnings(process, [warning])

        then:
        1 * m.process.startProcessStep(process, { ProcessEvent e -> e.type == ProcessEvent.ProcessType.provisionVolumes }, 'adding volumes') >> Single.just(true)
        1 * m.process.endProcessStep(process, MorpheusProcessService.STATUS_COMPLETE, "WARNING: ${warning}".toString()) >> Single.just(true)
    }

    def "no History step is added when every disk attached"() {
        given:
        def m = providerWithContext()

        when:
        m.provider.reportVolumeWarnings(new Process(id: 7L), [])

        then:
        0 * m.process._
    }
}
