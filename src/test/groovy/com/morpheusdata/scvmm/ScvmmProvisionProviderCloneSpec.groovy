// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Instance
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.Workload
import com.morpheusdata.response.PrepareCloneInstanceResponse
import spock.lang.Specification
import spock.lang.Subject

/**
 * Covers MORPH-19193: the clone handshake with core. {@code prepareCloneInstance} must return the source
 * <em>workload</em> id as {@code cloneContainerId}, because core echoes it back into
 * {@code runWorkload} as {@code provisionOpts.cloneContainerId} where it is resolved via
 * {@code context.services.workload.get(...)}. Returning the source instance id only works by accident
 * when instance and workload ids happen to coincide.
 */
class ScvmmProvisionProviderCloneSpec extends Specification {

    MorpheusContext context = Mock()
    ScvmmPlugin plugin = Mock()

    @Subject
    ScvmmProvisionProvider provider = new ScvmmProvisionProvider(plugin, context)

    def "prepareCloneInstance returns the source workload id, not the source instance id"() {
        given: 'an instance whose workload id differs from its own id (as on the MORPH-19193 appliance)'
        VirtualImage image = new VirtualImage(id: 49L)
        ComputeServer sourceServer = new ComputeServer(id: 78L, externalId: 'c0c5c78f-8d33-45ed-a5cb-61ea0daf3c72', sourceImage: image)
        Workload sourceWorkload = new Workload(id: 54L, server: sourceServer)
        Instance instance = new Instance(id: 52L, containers: [sourceWorkload])

        when:
        def result = provider.prepareCloneInstance(instance, [cloneInstanceId: 52L])

        then:
        result.success
        PrepareCloneInstanceResponse response = result.data
        response.cloneContainerId == 54L
        response.cloneVmExternalId == 'c0c5c78f-8d33-45ed-a5cb-61ea0daf3c72'
        response.image.id == 49L
        response.instance.is(instance)
        response.snapshotToWorkloadMap == [:]
    }

    def "prepareCloneInstance fails when the source server has no externalId"() {
        given:
        Workload sourceWorkload = new Workload(id: 54L, server: new ComputeServer(id: 78L, sourceImage: new VirtualImage(id: 49L)))
        Instance instance = new Instance(id: 52L, containers: [sourceWorkload])

        when:
        def result = provider.prepareCloneInstance(instance, [cloneInstanceId: 52L])

        then:
        !result.success
        result.msg.contains('externalId')
        result.data == null
    }

    def "prepareCloneInstance fails when the source server has no source image"() {
        given:
        Workload sourceWorkload = new Workload(id: 54L, server: new ComputeServer(id: 78L, externalId: 'vm-guid'))
        Instance instance = new Instance(id: 52L, containers: [sourceWorkload])

        when:
        def result = provider.prepareCloneInstance(instance, [cloneInstanceId: 52L])

        then:
        !result.success
        result.msg.contains('source image')
        result.data == null
    }
}
