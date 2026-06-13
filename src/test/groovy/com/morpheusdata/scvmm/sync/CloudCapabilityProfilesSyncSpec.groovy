package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.scvmm.logging.LogInterface
import spock.lang.Specification

class CloudCapabilityProfilesSyncSpec extends Specification {

    MorpheusContext mockContext
    ScvmmApiService mockApiService
    LogInterface mockLog

    def setup() {
        mockContext = Mock(MorpheusContext)
        mockApiService = Mock(ScvmmApiService)
        mockLog = Mock(LogInterface)
    }
    
    def "constructor initializes fields correctly"() {
        given:
        def realCloud = new Cloud(id: 1L)
        
        when:
        def sync = new CloudCapabilityProfilesSync(mockContext, realCloud)

        then:
        sync.@cloud == realCloud
        sync.@morpheusContext == mockContext
        sync.@apiService != null
    }
    
    def "execute logs debug message"() {
        given:
        def realCloud = new Cloud(id: 1L, regionCode: null)
        realCloud.account = new Account(id: 1L)
        def realServer = new ComputeServer(id: 10L)
        
        def mockCloudService = Mock(Object)
        def mockComputeServerService = Mock(Object)
        def mockServices = Mock(MorpheusServices)
        
        mockServices.getCloud() >> mockCloudService
        mockServices.getComputeServer() >> mockComputeServerService
        mockContext.getServices() >> mockServices
        mockCloudService.get(1L) >> realCloud
        mockComputeServerService.find(_) >> realServer
        mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, realCloud, realServer) >> [:]
        mockApiService.getCapabilityProfiles([:]) >> [success: false]
        
        def sync = new CloudCapabilityProfilesSync(mockContext, realCloud)
        sync.@apiService = mockApiService
        sync.@log = mockLog

        when:
        sync.execute()

        then:
        1 * mockLog.debug("CloudCapabilityProfilesSync")
    }

    def "execute handles exception and logs error"() {
        given:
        def realCloud = new Cloud(id: 2L)
        def mockServices = Mock(MorpheusServices)
        mockContext.getServices() >> mockServices
        mockServices.getCloud() >> { throw new RuntimeException("Test error") }
        
        def sync = new CloudCapabilityProfilesSync(mockContext, realCloud)
        sync.@apiService = mockApiService
        sync.@log = mockLog

        when:
        sync.execute()

        then:
        1 * mockLog.debug("CloudCapabilityProfilesSync")
        1 * mockLog.error(_ as String, _ as Throwable)
    }
}
