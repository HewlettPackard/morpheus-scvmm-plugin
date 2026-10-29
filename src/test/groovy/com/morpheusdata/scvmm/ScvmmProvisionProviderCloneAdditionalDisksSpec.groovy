// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Instance
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.Workload
import groovy.json.JsonOutput
import spock.lang.Specification
import spock.lang.Subject

// A clone copies only the source's disks, so volumes added in the clone wizard must be returned as
// additionalTemplateDisks (attached after creation) or checkServerCreated waits for a disk that never appears.
class ScvmmProvisionProviderCloneAdditionalDisksSpec extends Specification {
    private static final long GB = 1024L * 1024L * 1024L

    @Subject
    private ScvmmProvisionProvider provider = new ScvmmProvisionProvider(Mock(ScvmmPlugin), Mock(MorpheusContext))

    private static Workload workload(List<Map> configVolumes, List<StorageVolume> serverVolumes) {
        def server = new ComputeServer(id: 10L)
        server.volumes = serverVolumes
        def w = new Workload(id: 20L, server: server)
        w.configs = JsonOutput.toJson([volumes: configVolumes])
        w
    }

    private static StorageVolume vol(long id, boolean root, long size) {
        new StorageVolume(id: id, rootVolume: root, maxStorage: size)
    }

    def "clone of a non-synced image with one added volume attaches it after creation"() {
        given: 'source has only a root disk; the clone wizard adds one 5 GB volume'
        def w = workload(
                [[id: 1, rootVolume: true, maxStorage: 20 * GB], [id: -1, rootVolume: false, maxStorage: 5 * GB]],
                [vol(101, true, 20 * GB), vol(102, false, 5 * GB)])
        def scvmmOpts = [diskExternalIdMappings: null]

        when:
        def disks = provider.additionalTemplateDisksConfig(w, scvmmOpts, 0)

        then: 'no NPE on missing diskExternalIdMappings and the new volume is queued'
        disks.size() == 1
        disks[0].diskSize == 5 * GB
        disks[0].diskCounter == 1

        and: 'checkServerCreated then expects exactly the source disk count'
        1 + (getContainerDataDisks(w).size() - disks.size()) == 1
    }

    def "clone keeps the source's data disks and adds only the new volume"() {
        given: 'source has one data disk (config id carried over), wizard adds one more'
        def w = workload(
                [[id: 1, rootVolume: true, maxStorage: 20 * GB], [id: 2, rootVolume: false, maxStorage: 10 * GB],
                 [id: -1, rootVolume: false, maxStorage: 5 * GB]],
                [vol(101, true, 20 * GB), vol(102, false, 10 * GB), vol(103, false, 5 * GB)])

        when:
        def disks = provider.additionalTemplateDisksConfig(w, [:], 1)

        then:
        disks*.diskSize == [5 * GB]
        disks[0].diskCounter == 2
        1 + (getContainerDataDisks(w).size() - disks.size()) == 2
    }

    def "clone without added volumes attaches nothing"() {
        given:
        def w = workload(
                [[id: 1, rootVolume: true, maxStorage: 20 * GB], [id: 2, rootVolume: false, maxStorage: 10 * GB]],
                [vol(101, true, 20 * GB), vol(102, false, 10 * GB)])

        expect:
        provider.additionalTemplateDisksConfig(w, [diskExternalIdMappings: null], 1) == []
    }

    def "clone attaches the config's new volume at its requested size even when it is listed before an inherited one"() {
        given: 'API payload lists the new 10 GB volume first, so its StorageVolume id is lower than the inherited 50 GB one'
        def w = workload(
                [[id: 1, rootVolume: true, maxStorage: 40 * GB], [id: -1, rootVolume: false, maxStorage: 10 * GB],
                 [id: 2, rootVolume: false, maxStorage: 50 * GB]],
                [vol(101, true, 40 * GB), vol(102, false, 10 * GB), vol(103, false, 50 * GB)])

        when:
        def disks = provider.additionalTemplateDisksConfig(w, [:], 1)

        then: 'the size comes from the id:-1 entry, not from the trailing data disk'
        disks*.diskSize == [10 * GB]
    }

    def "clone where an inherited volume was removed in the wizard still attaches the new volume"() {
        given: 'source has one 50 GB data disk; the wizard removes it and adds a 5 GB volume'
        def w = workload(
                [[id: 1, rootVolume: true, maxStorage: 40 * GB], [id: -1, rootVolume: false, maxStorage: 5 * GB]],
                [vol(101, true, 40 * GB), vol(102, false, 5 * GB)])

        when:
        def disks = provider.additionalTemplateDisksConfig(w, [:], 1)

        then: 'counts disagree (0 expected new) but the requested volume is attached, after the source disk'
        disks == [[idx: 1, diskCounter: 2, diskSize: 5 * GB, busNumber: '0']]
    }

    def "clone attaches every id:-1 config volume when more are sent than the disk counts imply"() {
        given: 'every config volume carries id -1 (e.g. an API caller omitted ids)'
        def w = workload(
                [[id: -1, rootVolume: true, maxStorage: 20 * GB], [id: -1, rootVolume: false, maxStorage: 10 * GB],
                 [id: -1, rootVolume: false, maxStorage: 5 * GB]],
                [vol(101, true, 20 * GB), vol(102, false, 10 * GB), vol(103, false, 5 * GB)])

        when:
        def disks = provider.additionalTemplateDisksConfig(w, [:], 1)

        then: 'no positional guess; config is used as sent'
        disks*.diskSize == [10 * GB, 5 * GB]
    }

    def "non-clone synced template path is unchanged"() {
        given: 'synced template with one disk; user adds one data volume at provision time'
        def w = workload(
                [[id: -1, rootVolume: true, maxStorage: 20 * GB], [id: -1, rootVolume: false, maxStorage: 5 * GB]],
                [vol(101, true, 20 * GB), vol(102, false, 5 * GB)])
        def scvmmOpts = [diskExternalIdMappings: [[rootVolume: true, externalId: 'vhd-root']]]

        when:
        def disks = provider.additionalTemplateDisksConfig(w, scvmmOpts)

        then:
        disks == [[idx: 1, diskCounter: 1, diskSize: 5 * GB, busNumber: '0', volumeId: 102L]]
    }

    def "non-clone synced template that already has the data disks adds nothing"() {
        given:
        def w = workload(
                [[id: -1, rootVolume: true, maxStorage: 20 * GB], [id: -1, rootVolume: false, maxStorage: 5 * GB]],
                [vol(101, true, 20 * GB), vol(102, false, 5 * GB)])
        def scvmmOpts = [diskExternalIdMappings: [[rootVolume: true], [rootVolume: false]]]

        expect:
        provider.additionalTemplateDisksConfig(w, scvmmOpts) == []
    }

    private static List<StorageVolume> cloneVolumes(List<List> specs) {
        // [configIndex, id, root, size, externalId?]
        specs.collect { s ->
            new StorageVolume(id: s[1], rootVolume: s[2], maxStorage: s[3], uniqueId: "morpheus-vol-5-20-${s[0]}", externalId: s.size() > 4 ? s[4] : null)
        }
    }

    private static Workload sourceWorkload(List<StorageVolume> volumes) {
        def server = new ComputeServer(id: 9L)
        server.volumes = volumes
        new Workload(id: 19L, server: server)
    }

    private static Workload cloneWorkload(List<Map> configVolumes, List<StorageVolume> serverVolumes) {
        def w = workload(configVolumes, serverVolumes)
        w.instance = new Instance(id: 5L)
        w
    }

    // Instance A: root + data-1 (10 GB, source volume 502, disk ext-d1). Sizes: data-2 20 GB, data-3 30 GB.
    private static Workload instanceA() {
        sourceWorkload([vol(501, true, 40 * GB), new StorageVolume(id: 502L, rootVolume: false, maxStorage: 10 * GB, externalId: 'ext-d1')])
    }

    def "clone plan: regular wizard flow keeps data-1 and adds data-2"() {
        given:
        def w = cloneWorkload(
                [[id: 501, rootVolume: true], [id: 502, rootVolume: false], [id: -1, rootVolume: false]],
                cloneVolumes([[0, 601L, true, 40 * GB], [1, 602L, false, 10 * GB], [2, 603L, false, 20 * GB]]))

        when:
        def plan = provider.buildCloneDiskPlan(w, instanceA())

        then:
        plan.inherited == [[volumeId: 602L, sourceExternalId: 'ext-d1']]
        plan.added == [[volumeId: 603L, diskSize: 20 * GB]]
        plan.removedSourceExternalIds == []
        ScvmmProvisionProvider.cloneDiskPlanAttachList(plan, 1) == [[idx: 1, diskCounter: 2, diskSize: 20 * GB, busNumber: '0', volumeId: 603L]]
    }

    def "clone plan: data-1 deleted in the wizard and data-2 added removes data-1's copy"() {
        given:
        def w = cloneWorkload(
                [[id: 501, rootVolume: true], [id: -1, rootVolume: false]],
                cloneVolumes([[0, 601L, true, 40 * GB], [1, 602L, false, 20 * GB]]))

        when:
        def plan = provider.buildCloneDiskPlan(w, instanceA())

        then:
        plan.inherited == []
        plan.added == [[volumeId: 602L, diskSize: 20 * GB]]
        plan.removedSourceExternalIds == ['ext-d1']
    }

    def "clone plan: API payload without ids, data-2 listed before data-1, uses each volume's own size"() {
        given: 'every entry is id -1, so nothing is inherited; data-1 is re-created at its requested size'
        def w = cloneWorkload(
                [[id: -1, rootVolume: true], [id: -1, rootVolume: false], [id: -1, rootVolume: false]],
                cloneVolumes([[0, 601L, true, 40 * GB], [1, 602L, false, 20 * GB], [2, 603L, false, 10 * GB]]))

        when:
        def plan = provider.buildCloneDiskPlan(w, instanceA())

        then:
        plan.added == [[volumeId: 602L, diskSize: 20 * GB], [volumeId: 603L, diskSize: 10 * GB]]
        plan.removedSourceExternalIds == ['ext-d1']
    }

    def "clone plan is null when a clone volume can't be located by uniqueId"() {
        given:
        def w = cloneWorkload(
                [[id: 501, rootVolume: true], [id: -1, rootVolume: false]],
                [vol(601, true, 40 * GB), vol(602, false, 20 * GB)])

        expect:
        provider.buildCloneDiskPlan(w, instanceA()) == null
    }

    private static List getContainerDataDisks(Workload w) {
        ScvmmProvisionProvider.getContainerDataDiskList(w)
    }
}
