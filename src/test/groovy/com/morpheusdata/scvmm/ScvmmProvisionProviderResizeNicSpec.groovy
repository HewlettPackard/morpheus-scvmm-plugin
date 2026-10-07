// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

/**
 * Hyper-V only hot-adds/removes network adapters on Generation 2 VMs. A NIC add on a running Generation 1 VM
 * is rejected by SCVMM with Error 640, and that failure must not be reported as a successful resize.
 */
class ScvmmProvisionProviderResizeNicSpec extends Specification {

    MorpheusContext context = Mock()
    ScvmmProvisionProvider provider

    def setup() {
        provider = Spy(ScvmmProvisionProvider, constructorArgs: [null, context])
    }

    private static ComputeServer server(String generation, Map props = [:]) {
        def server = new ComputeServer([id: 17L, externalId: 'vm-17', maxMemory: 1024L * 1024 * 1024, maxCores: 1L, hotResize: false, cpuHotResize: false] + props)
        if (generation != null) server.setConfigProperty('generation', generation)
        return server
    }

    private static ResizeRequest request(Map props = [:]) {
        new ResizeRequest([maxMemory: 1024L * 1024 * 1024, maxCores: 1L] + props)
    }

    def "getResizeConfig requires a stop for NIC add/remove unless the VM is Generation 2"() {
        when:
        def config = provider.getResizeConfig(null, server(generation), new ServicePlan(), [:], request(nicChange))

        then:
        config.hotResize == hotResize

        where:
        generation    | nicChange                                                 || hotResize
        'generation1' | [interfacesAdd: [[network: [id: 1]]]]                     || false
        'generation1' | [interfacesDelete: [new ComputeServerInterface()]]        || false
        null          | [interfacesAdd: [[network: [id: 1]]]]                     || false
        'generation2' | [interfacesAdd: [[network: [id: 1]]]]                     || true
        'generation2' | [interfacesDelete: [new ComputeServerInterface()]]        || true
        'generation1' | [interfacesUpdate: [new ComputeServerInterface()]]        || true
        'generation1' | [:]                                                       || true
    }

    def "a NIC add on a Generation 1 VM stops the VM, applies the change and starts it again"() {
        given:
        def gen1 = server('generation1')
        provider.getMorpheusServer(17L) >> gen1
        provider.saveAndGet(_ as ComputeServer) >> { ComputeServer s -> s }
        provider.getAllScvmmServerOpts(_) >> [:]
        provider.reconfigureResizedInterfaces(_, _, _, _) >> ServiceResponse.success()

        when:
        def result = provider.resizeServer(gen1, request(interfacesAdd: [[network: [id: 1]]]), [:])

        then:
        1 * provider.stopServer(_) >> ServiceResponse.success()
        1 * provider.startServer(_) >> ServiceResponse.success()
        result.success
        gen1.status == 'provisioned'
    }

    def "a NIC add on a Generation 2 VM is applied hot"() {
        given:
        def gen2 = server('generation2')
        provider.getMorpheusServer(17L) >> gen2
        provider.saveAndGet(_ as ComputeServer) >> { ComputeServer s -> s }
        provider.getAllScvmmServerOpts(_) >> [:]
        provider.reconfigureResizedInterfaces(_, _, _, _) >> ServiceResponse.success()

        when:
        def result = provider.resizeServer(gen2, request(interfacesAdd: [[network: [id: 1]]]), [:])

        then:
        0 * provider.stopServer(_)
        0 * provider.startServer(_)
        result.success
    }

    def "a NIC reconfigure failure is reported as a failed resize instead of being swallowed"() {
        given:
        def gen1 = server('generation1')
        provider.getMorpheusServer(17L) >> gen1
        provider.saveAndGet(_ as ComputeServer) >> { ComputeServer s -> s }
        provider.getAllScvmmServerOpts(_) >> [:]
        provider.stopServer(_) >> ServiceResponse.success()
        provider.startServer(_) >> ServiceResponse.success()
        provider.reconfigureResizedInterfaces(_, _, _, _) >> ServiceResponse.error('The virtual machine must be in either the Poweroff or Stored state before SCVMM can perform the specified hardware changes. (Error ID: 640)')

        when:
        def result = provider.resizeServer(gen1, request(interfacesAdd: [[network: [id: 1]]]), [:])

        then:
        !result.success
        result.error.contains('Error ID: 640')
        result.msg == result.error
        gen1.status == 'provisioned'
    }

    def "a failed stop is reported as a failed resize and the NIC change is not attempted"() {
        given:
        def gen1 = server('generation1')
        provider.getMorpheusServer(17L) >> gen1
        provider.saveAndGet(_ as ComputeServer) >> { ComputeServer s -> s }
        provider.getAllScvmmServerOpts(_) >> [:]
        provider.stopServer(_) >> ServiceResponse.error('stop failed')
        provider.startServer(_) >> ServiceResponse.success()

        when:
        def result = provider.resizeServer(gen1, request(interfacesAdd: [[network: [id: 1]]]), [:])

        then:
        0 * provider.reconfigureResizedInterfaces(_, _, _, _)
        !result.success
        result.error == 'Server never stopped so resize could not be performed'
    }
}
