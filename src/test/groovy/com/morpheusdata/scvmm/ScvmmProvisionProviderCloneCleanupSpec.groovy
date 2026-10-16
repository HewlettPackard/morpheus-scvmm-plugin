// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Subject

/**
 * Covers MORPH-15705: after cloning, only the parent VM's re-created cloud-init ISO may be deleted in
 * cloneParentCleanup. The clone's own ISO must remain mounted until finalizeWorkload so cloud-init can run.
 */
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

    def "deletes only the parent ISO and never the clone's ISO"() {
        given:
        def scvmmOpts = cloneOpts()
        def rtn = new ServiceResponse(success: true)

        when:
        provider.cloneParentCleanup(scvmmOpts, rtn)

        then:
        1 * apiService.setCdrom(clonedScvmmOpts) >> [success: true]
        1 * apiService.deleteIso(clonedScvmmOpts, PARENT_ISO) >> [success: true]
        0 * apiService.deleteIso(_, CLONE_ISO)
        0 * apiService.startServer(_, _)
        rtn.success
        !rtn.warning
    }

    def "does not delete any ISO when the parent had no cloud-init ISO re-created"() {
        given:
        def scvmmOpts = cloneOpts(cloneBaseResults: null)

        when:
        provider.cloneParentCleanup(scvmmOpts, new ServiceResponse(success: true))

        then:
        1 * apiService.setCdrom(clonedScvmmOpts) >> [success: true]
        0 * apiService.deleteIso(_, _)
    }

    def "does nothing when not cloning"() {
        when:
        provider.cloneParentCleanup([deleteDvdOnComplete: [deleteIso: CLONE_ISO]], new ServiceResponse(success: true))

        then:
        0 * apiService._
    }

    def "does nothing when cloneBaseOpts has no parent context"() {
        given:
        def scvmmOpts = cloneOpts(cloneBaseOpts: [:])

        when:
        provider.cloneParentCleanup(scvmmOpts, new ServiceResponse(success: true))

        then:
        0 * apiService._
    }

    def "cleanup failure is reported as a warning without failing the provision"() {
        given:
        def scvmmOpts = cloneOpts()
        def rtn = new ServiceResponse(success: true)

        when:
        provider.cloneParentCleanup(scvmmOpts, rtn)

        then:
        1 * apiService.setCdrom(clonedScvmmOpts) >> { throw new RuntimeException('boom') }
        0 * apiService.deleteIso(_, _)
        rtn.success
        rtn.warning
        rtn.msg.contains('boom')
    }
}
