// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Instance
import com.morpheusdata.model.OsType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.TaskResult
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.Workload
import com.morpheusdata.response.PrepareCloneInstanceResponse
import io.reactivex.rxjava3.core.Single
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

    // cloud-init on a cloned disk only re-runs if its cache is cleared on the source first; runWorkload uses the
    // result to decide whether cloud-init or Morpheus installs the clone's agent

    private static ComputeServer cloudInitLinuxServer(ComputeServer.PowerState power = ComputeServer.PowerState.on) {
        new ComputeServer(id: 107L, name: 'src', externalId: 'vm-guid', sourceImage: new VirtualImage(id: 49L, isCloudInit: true),
                serverOs: new OsType(platform: PlatformType.linux), powerState: power, sshUsername: 'morpheus', sshPassword: 'pw')
    }

    def "prepareCloneInstance does not touch the source guest"() {
        given:
        Instance instance = new Instance(id: 66L, containers: [new Workload(id: 68L, server: cloudInitLinuxServer())])

        when:
        def result = provider.prepareCloneInstance(instance, [cloneInstanceId: 66L])

        then:
        0 * context.executeCommandOnServer(*_)
        result.success
        result.data.cloneContainerId == 68L
    }

    def "resetCloudInitCacheForClone clears the cache inside a running Linux cloud-init source and returns true"() {
        when:
        def cleared = provider.resetCloudInitCacheForClone(cloudInitLinuxServer())

        then:
        1 * context.executeCommandOnServer({ it.id == 107L }, ScvmmProvisionProvider.CLOUD_INIT_CACHE_RESET_COMMAND,
                false, 'morpheus', 'pw', null, null, null, null, true, true) >> Single.just(new TaskResult(success: true))
        cleared
    }

    def "resetCloudInitCacheForClone returns false so Morpheus installs the agent when the reset #why"() {
        when:
        def cleared = provider.resetCloudInitCacheForClone(cloudInitLinuxServer())

        then:
        1 * context.executeCommandOnServer(*_) >> response
        !cleared

        where:
        why      | response
        'fails'  | Single.just(new TaskResult(success: false, error: 'permission denied'))
        'throws' | Single.error(new RuntimeException('ssh timeout'))
    }

    def "resetCloudInitCacheForClone does not touch the guest and returns false when the source is #why"() {
        when:
        def cleared = provider.resetCloudInitCacheForClone(server)

        then:
        0 * context.executeCommandOnServer(*_)
        !cleared

        where:
        why                    | server
        'powered off'          | cloudInitLinuxServer(ComputeServer.PowerState.off)
        'a Windows image'      | new ComputeServer(id: 108L, sourceImage: new VirtualImage(id: 50L, isCloudInit: true), serverOs: new OsType(platform: PlatformType.windows), powerState: ComputeServer.PowerState.on)
        'an unknown platform'  | new ComputeServer(id: 110L, sourceImage: new VirtualImage(id: 52L, isCloudInit: true), serverOs: null, powerState: ComputeServer.PowerState.on)
        'not cloud-init'       | new ComputeServer(id: 109L, sourceImage: new VirtualImage(id: 51L, isCloudInit: false), serverOs: new OsType(platform: PlatformType.linux), powerState: ComputeServer.PowerState.on)
        'missing'              | null
    }
}
