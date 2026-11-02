// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

// checkServerCreated waits for an exact drive count and createServer maps SCVMM drives onto Morpheus data
// volumes by position; a clone whose volume config disagrees with the source must neither hang nor NPE.
class ScvmmApiServiceCloneDiskCountSpec extends Specification {

    @Subject
    private ScvmmApiService apiService = new ScvmmApiService(Mock(MorpheusContext))

    @Unroll
    def "expectedCreatedDiskCount for #scenario is #expected"() {
        expect:
        ScvmmApiService.expectedCreatedDiskCount(opts) == expected

        where:
        scenario                                         | opts                                                                                   || expected
        'clone: uses the source disk count'              | [expectedCreatedDiskCount: 2, dataDisks: [[id: 1], [id: 2]], additionalTemplateDisks: [[:]]] || 2
        'clone: source disk removed, new volume added'   | [expectedCreatedDiskCount: 2, dataDisks: [[id: 1]], additionalTemplateDisks: [[:]]]   || 2
        'non-clone: derived from data and template disks' | [dataDisks: [[id: 1], [id: 2]], additionalTemplateDisks: [[:]]]                       || 2
        'non-clone: no data disks'                       | [:]                                                                                    || 1
    }

    def "assignDataDiskExternalId maps a drive onto its data volume"() {
        given:
        def dataDisks = [[id: 1], [id: 2]]

        expect:
        apiService.assignDataDiskExternalId(dataDisks, 1, 'disk-b')
        dataDisks[1].externalId == 'disk-b'
        dataDisks[0].externalId == null
    }

    def "assignDataDiskExternalId skips a drive SCVMM reports beyond the Morpheus data volumes instead of throwing"() {
        given: 'clone kept a source disk the user removed, so SCVMM has one more drive than Morpheus has volumes'
        def dataDisks = [[id: 1]]

        expect:
        !apiService.assignDataDiskExternalId(dataDisks, 1, 'extra-disk')
        dataDisks[0].externalId == null
        !apiService.assignDataDiskExternalId(null, 0, 'extra-disk')
    }

    def "assignDataDiskExternalId keeps the existing negative-index mapping when the boot disk is not first"() {
        given:
        def dataDisks = [[id: 1]]

        expect:
        apiService.assignDataDiskExternalId(dataDisks, -1, 'disk-a')
        dataDisks[0].externalId == 'disk-a'
    }

    private static Map drive(String id, String vhdId, String busType, int bus, int lun) {
        [ID: id, VhdID: vhdId, BusType: busType, Bus: bus, Lun: lun]
    }

    def "resolveCloneInheritedDisks maps an inherited drive by the source drive's slot, whatever SCVMM lists first"() {
        given: 'source data-1 is SCSI 0:1; the clone lists its copy before the root'
        def sourceDrives = [drive('src-root', 'src-root-vhd', 'IDE', 0, 0), drive('src-d1', 'src-d1-vhd', 'SCSI', 0, 1)]
        def cloneDrives = [drive('cl-d1', 'cl-d1-vhd', 'SCSI', 0, 1), drive('cl-root', 'cl-root-vhd', 'IDE', 0, 0)]
        def plan = [inherited: [[volumeId: 602L, sourceExternalId: sourceExternalId]], added: [], removedSourceExternalIds: []]

        when:
        def resolved = apiService.resolveCloneInheritedDisks(sourceDrives, cloneDrives, plan)

        then:
        resolved.driveVolumeIds == ['cl-d1': 602L]
        resolved.removeVhdIds == []

        where: 'source volume externalId may be the drive ID or the VHD ID'
        sourceExternalId << ['src-d1', 'src-d1-vhd']
    }

    def "resolveCloneInheritedDisks returns the clone copy of a source disk removed in the wizard"() {
        given:
        def sourceDrives = [drive('src-root', 'src-root-vhd', 'IDE', 0, 0), drive('src-d1', 'src-d1-vhd', 'SCSI', 0, 1)]
        def cloneDrives = [drive('cl-root', 'cl-root-vhd', 'IDE', 0, 0), drive('cl-d1', 'cl-d1-vhd', 'SCSI', 0, 1)]

        when:
        def resolved = apiService.resolveCloneInheritedDisks(sourceDrives, cloneDrives,
                [inherited: [], added: [[volumeId: 602L, diskSize: 1L]], removedSourceExternalIds: ['src-d1']])

        then:
        resolved.removeVhdIds == ['cl-d1-vhd']
        resolved.driveVolumeIds == [:]
    }

    def "assignDataDiskExternalIdByVolume maps by volume id and leaves unknown drives unmapped"() {
        given:
        def dataDisks = [[id: 602L], [id: 603L]]

        expect:
        apiService.assignDataDiskExternalIdByVolume(dataDisks, 603L, 'new-disk')
        dataDisks[1].externalId == 'new-disk'
        !apiService.assignDataDiskExternalIdByVolume(dataDisks, null, 'stray-disk')
        dataDisks[0].externalId == null
    }
}
