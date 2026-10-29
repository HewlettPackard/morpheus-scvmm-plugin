// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
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

    def "clone falls back to the trailing data disks when config ids don't identify the new volumes"() {
        given: 'every config volume carries id -1, which would otherwise re-add the source data disk'
        def w = workload(
                [[id: -1, rootVolume: true, maxStorage: 20 * GB], [id: -1, rootVolume: false, maxStorage: 10 * GB],
                 [id: -1, rootVolume: false, maxStorage: 5 * GB]],
                [vol(101, true, 20 * GB), vol(102, false, 10 * GB), vol(103, false, 5 * GB)])

        when:
        def disks = provider.additionalTemplateDisksConfig(w, [:], 1)

        then:
        disks*.diskSize == [5 * GB]
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
        disks == [[idx: 1, diskCounter: 1, diskSize: 5 * GB, busNumber: '0']]
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

    private static List getContainerDataDisks(Workload w) {
        ScvmmProvisionProvider.getContainerDataDiskList(w)
    }
}
