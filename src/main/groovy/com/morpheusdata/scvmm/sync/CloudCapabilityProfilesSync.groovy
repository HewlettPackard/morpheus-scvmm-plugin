package com.morpheusdata.scvmm.sync

import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Cloud
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import groovy.util.logging.Slf4j

class CloudCapabilityProfilesSync {

    private MorpheusContext morpheusContext
    private Cloud cloud
    private ScvmmApiService apiService
    private LogInterface log = LogWrapper.instance

    CloudCapabilityProfilesSync(MorpheusContext morpheusContext, Cloud cloud) {
        this.cloud = cloud
        this.morpheusContext = morpheusContext
        this.apiService = new ScvmmApiService(morpheusContext)
    }

    def execute() {
        log.debug "CloudCapabilityProfilesSync"
        try {
            def scvmmCloud = morpheusContext.services.cloud.get(cloud.id)
            def server = morpheusContext.services.computeServer.find(new DataQuery().withFilter('cloud.id', scvmmCloud.id))
            def scvmmOpts = apiService.getScvmmZoneAndHypervisorOpts(morpheusContext, scvmmCloud, server)

            if(scvmmCloud.regionCode) {
                def cloudResults = apiService.getCloud(scvmmOpts)
                if(cloudResults.success == true && cloudResults?.cloud?.CapabilityProfiles) {
                    scvmmCloud.setConfigProperty('capabilityProfiles', cloudResults?.cloud?.CapabilityProfiles)
                    morpheusContext.services.cloud.save(scvmmCloud)
                }
            } else {
                def capabilityProfileResults = apiService.getCapabilityProfiles(scvmmOpts)
                if(capabilityProfileResults.success == true && capabilityProfileResults?.capabilityProfiles) {
                    scvmmCloud.setConfigProperty('capabilityProfiles', capabilityProfileResults.capabilityProfiles.collect { it.Name })
                    morpheusContext.services.cloud.save(scvmmCloud)
                }
            }
        } catch (e) {
            log.error("CloudCapabilityProfilesSync error: ${e}", e)
        }
    }
}
