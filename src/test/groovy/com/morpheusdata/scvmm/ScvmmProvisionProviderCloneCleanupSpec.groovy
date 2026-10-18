// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusWorkloadService
import com.morpheusdata.model.Workload
import com.morpheusdata.response.ServiceResponse
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Subject

// Clone cleanup: the clone's ISO must stay mounted until finalizeWorkload; the source's is cleaned up there too
// on success, or immediately when the clone failed.
class ScvmmProvisionProviderCloneCleanupSpec extends Specification {
    private static final String PARENT_ISO = '\\\\lib\\share\\parent\\config.iso'
    private static final String CLONE_ISO = '\\\\lib\\share\\clone\\config.iso'

    private ScvmmApiService apiService
    private Map clonedScvmmOpts

    @Subject
    private ScvmmProvisionProvider provider

    void setup() {
        apiService = Mock(ScvmmApiService)
        provider = new ScvmmProvisionProvider(Mock(ScvmmPlugin), Mock(MorpheusContext))
        provider.apiService = apiService
        clonedScvmmOpts = [externalId: 'parent-vm-id']
    }

    private Map cloneOpts(Map overrides = [:]) {
        [
                cloneVMId          : 'parent-vm-id',
                cloneContainerId   : 42L,
                cloneBaseOpts      : [clonedScvmmOpts: clonedScvmmOpts],
                deleteDvdOnComplete: [removeIsoFromDvd: true, deleteIso: CLONE_ISO],
                cloneBaseResults   : [cloudInitIsoPath: PARENT_ISO]
        ] + overrides
    }

    def "on success leaves both ISOs mounted for finalizeWorkload"() {
        given:
        def scvmmOpts = cloneOpts()
        def rtn = new ServiceResponse(success: true)

        when:
        provider.cloneParentCleanup(scvmmOpts, rtn)

        then:
        0 * apiService.setCdrom(_, _)
        0 * apiService.deleteIso(_, _)
        0 * apiService.startServer(_, _)
        rtn.success
        !rtn.warning
    }

    def "on failure ejects and deletes only the source's ISO, never the clone's"() {
        given:
        def scvmmOpts = cloneOpts()
        def rtn = new ServiceResponse(success: false)

        when:
        provider.cloneParentCleanup(scvmmOpts, rtn)

        then:
        1 * apiService.setCdrom(clonedScvmmOpts) >> [success: true]
        1 * apiService.deleteIso(clonedScvmmOpts, PARENT_ISO) >> [success: true]
        0 * apiService.deleteIso(_, CLONE_ISO)
        !rtn.warning
    }

    def "on failure does not delete any ISO when the source had none re-created"() {
        given:
        def scvmmOpts = cloneOpts(cloneBaseResults: null)

        when:
        provider.cloneParentCleanup(scvmmOpts, new ServiceResponse(success: false))

        then:
        1 * apiService.setCdrom(clonedScvmmOpts) >> [success: true]
        0 * apiService.deleteIso(_, _)
    }

    def "does nothing when not cloning"() {
        when:
        provider.cloneParentCleanup([deleteDvdOnComplete: [deleteIso: CLONE_ISO]], new ServiceResponse(success: false))

        then:
        0 * apiService._
    }

    def "does nothing when cloneBaseOpts has no parent context"() {
        given:
        def scvmmOpts = cloneOpts(cloneBaseOpts: [:])

        when:
        provider.cloneParentCleanup(scvmmOpts, new ServiceResponse(success: false))

        then:
        0 * apiService._
    }

    def "cleanup failure is reported as a warning"() {
        given:
        def scvmmOpts = cloneOpts()
        def rtn = new ServiceResponse(success: false)

        when:
        provider.cloneParentCleanup(scvmmOpts, rtn)

        then:
        1 * apiService.setCdrom(clonedScvmmOpts) >> { throw new RuntimeException('boom') }
        0 * apiService.deleteIso(_, _)
        rtn.warning
        rtn.msg.contains('boom')
    }

    def "finalizeWorkload ejects and deletes the clone's ISO and then the source's"() {
        given:
        def context = Mock(MorpheusContext)
        provider = Spy(ScvmmProvisionProvider, constructorArgs: [Mock(ScvmmPlugin), context])
        provider.apiService = apiService
        def workloadOpts = [externalId: 'clone-vm-id', zoneId: 1L]
        provider.getAllScvmmOpts(_) >> workloadOpts
        def workload = new Workload(id: 69L)
        workload.setConfigMap([deleteDvdOnComplete: [removeIsoFromDvd: true, deleteIso: CLONE_ISO], cloneParentDvdCleanup: [externalId: 'parent-vm-id', deleteIso: PARENT_ISO]])
        def asyncWorkload = Mock(MorpheusWorkloadService)
        def async = Mock(MorpheusAsyncServices)
        context.getAsync() >> async
        async.getWorkload() >> asyncWorkload
        asyncWorkload.get(69L) >> Maybe.just(workload)
        asyncWorkload.save(_) >> Single.just(workload)

        when:
        def result = provider.finalizeWorkload(workload)

        then:
        result.success
        1 * apiService.setCdrom(workloadOpts) >> [success: true]
        1 * apiService.deleteIso(workloadOpts, CLONE_ISO) >> [success: true]
        1 * apiService.setCdrom({ it.externalId == 'parent-vm-id' }) >> [success: true]
        1 * apiService.deleteIso({ it.externalId == 'parent-vm-id' }, PARENT_ISO) >> [success: true]
        workload.configMap.cloneParentDvdCleanup == null
    }
}
