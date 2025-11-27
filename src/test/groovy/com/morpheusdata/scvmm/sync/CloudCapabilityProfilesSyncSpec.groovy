package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.scvmm.ScvmmApiService
import spock.lang.Specification
import spock.lang.Unroll

class CloudCapabilityProfilesSyncSpec extends Specification {

    private TestableCloudCapabilityProfilesSync cloudCapabilityProfilesSync
    private MorpheusContext morpheusContext
    private ScvmmApiService mockApiService
    private MorpheusSynchronousCloudService cloudService
    private MorpheusSynchronousComputeServerService computeServerService
    private Cloud cloud
    private ComputeServer server

    // Test class that allows us to inject mock apiService
    private static class TestableCloudCapabilityProfilesSync extends CloudCapabilityProfilesSync {
        ScvmmApiService testApiService

        TestableCloudCapabilityProfilesSync(MorpheusContext morpheusContext, Cloud cloud, ScvmmApiService apiService) {
            super(morpheusContext, cloud)
            this.testApiService = apiService
            // Use reflection to set the private apiService field
            def field = CloudCapabilityProfilesSync.class.getDeclaredField('apiService')
            field.setAccessible(true)
            field.set(this, apiService)
        }
    }

    def setup() {
        // Setup mock context and services
        morpheusContext = Mock(MorpheusContext)
        mockApiService = Mock(ScvmmApiService)

        // Mock services
        cloudService = Mock(MorpheusSynchronousCloudService)
        computeServerService = Mock(MorpheusSynchronousComputeServerService)

        def morpheusServices = Mock(MorpheusServices) {
            getCloud() >> cloudService
            getComputeServer() >> computeServerService
        }

        morpheusContext.getServices() >> morpheusServices

        // Create test objects
        cloud = new Cloud(
                id: 1L,
                name: "test-scvmm-cloud",
                regionCode: null
        )

        server = new ComputeServer(
                id: 2L,
                name: "scvmm-server",
                cloud: cloud
        )

        // Create testable CloudCapabilityProfilesSync instance
        cloudCapabilityProfilesSync = new TestableCloudCapabilityProfilesSync(morpheusContext, cloud, mockApiService)
    }

    @Unroll
    def "execute should sync capability profiles successfully when regionCode is not set"() {
        given: "A cloud without regionCode and capability profiles API response"
        cloud.regionCode = null
        def scvmmOpts = [username: "user", password: "pass", host: "scvmm-host"]
        def capabilityProfilesResponse = [
                success: true,
                capabilityProfiles: [
                        [Name: "High Performance"],
                        [Name: "Balanced"],
                        [Name: "Energy Saving"]
                ]
        ]

        when: "execute is called"
        cloudCapabilityProfilesSync.execute()

        then: "services are called and capability profiles are synced"
        1 * cloudService.get(cloud.id) >> cloud
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.getCapabilityProfiles(scvmmOpts) >> capabilityProfilesResponse
        1 * cloudService.save(cloud)

        and: "capability profiles are set on cloud config"
        cloud.getConfigProperty('capabilityProfiles') == ["High Performance", "Balanced", "Energy Saving"]
    }

    @Unroll
    def "execute should sync capability profiles successfully when regionCode is set"() {
        given: "A cloud with regionCode and cloud API response"
        cloud.regionCode = "us-east-1"
        def scvmmOpts = [username: "user", password: "pass", host: "scvmm-host"]
        def cloudResponse = [
                success: true,
                cloud: [
                        CapabilityProfiles: [
                                "Premium Performance",
                                "Standard",
                                "Basic"
                        ]
                ]
        ]

        when: "execute is called"
        cloudCapabilityProfilesSync.execute()

        then: "services are called and capability profiles are synced from cloud response"
        1 * cloudService.get(cloud.id) >> cloud
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.getCloud(scvmmOpts) >> cloudResponse
        1 * cloudService.save(cloud)

        and: "capability profiles are set on cloud config from cloud response"
        cloud.getConfigProperty('capabilityProfiles') == ["Premium Performance", "Standard", "Basic"]
    }

    @Unroll
    def "execute should handle API failure gracefully when regionCode is not set"() {
        given: "A cloud without regionCode and failed API response"
        cloud.regionCode = null
        def scvmmOpts = [username: "user", password: "pass", host: "scvmm-host"]
        def failedResponse = [success: false, error: "Connection failed"]

        when: "execute is called with failed API response"
        cloudCapabilityProfilesSync.execute()

        then: "services are called but cloud is not saved due to failed response"
        1 * cloudService.get(cloud.id) >> cloud
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.getCapabilityProfiles(scvmmOpts) >> failedResponse
        0 * cloudService.save(_)

        and: "capability profiles are not set on cloud"
        cloud.getConfigProperty('capabilityProfiles') == null
    }

    @Unroll
    def "execute should handle API failure gracefully when regionCode is set"() {
        given: "A cloud with regionCode and failed API response"
        cloud.regionCode = "us-east-1"
        def scvmmOpts = [username: "user", password: "pass", host: "scvmm-host"]
        def failedResponse = [success: false, error: "Authentication failed"]

        when: "execute is called with failed API response"
        cloudCapabilityProfilesSync.execute()

        then: "services are called but cloud is not saved due to failed response"
        1 * cloudService.get(cloud.id) >> cloud
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.getCloud(scvmmOpts) >> failedResponse
        0 * cloudService.save(_)

        and: "capability profiles are not set on cloud"
        cloud.getConfigProperty('capabilityProfiles') == null
    }

    @Unroll
    def "execute should handle missing capability profiles in response when regionCode is not set"() {
        given: "A cloud without regionCode and response without capability profiles"
        cloud.regionCode = null
        def scvmmOpts = [username: "user", password: "pass", host: "scvmm-host"]
        def responseWithoutProfiles = [success: true, capabilityProfiles: null]

        when: "execute is called with response missing capability profiles"
        cloudCapabilityProfilesSync.execute()

        then: "services are called but cloud is not saved due to missing capability profiles"
        1 * cloudService.get(cloud.id) >> cloud
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.getCapabilityProfiles(scvmmOpts) >> responseWithoutProfiles
        0 * cloudService.save(_)

        and: "capability profiles are not set on cloud"
        cloud.getConfigProperty('capabilityProfiles') == null
    }

    @Unroll
    def "execute should handle missing capability profiles in cloud response when regionCode is set"() {
        given: "A cloud with regionCode and cloud response without capability profiles"
        cloud.regionCode = "us-east-1"
        def scvmmOpts = [username: "user", password: "pass", host: "scvmm-host"]
        def responseWithoutProfiles = [success: true, cloud: [OtherProperty: "value"]]

        when: "execute is called with cloud response missing capability profiles"
        cloudCapabilityProfilesSync.execute()

        then: "services are called but cloud is not saved due to missing capability profiles"
        1 * cloudService.get(cloud.id) >> cloud
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.getCloud(scvmmOpts) >> responseWithoutProfiles
        0 * cloudService.save(_)

        and: "capability profiles are not set on cloud"
        cloud.getConfigProperty('capabilityProfiles') == null
    }

    @Unroll
    def "execute should handle empty capability profiles list when regionCode is not set"() {
        given: "A cloud without regionCode and response with empty capability profiles"
        cloud.regionCode = null
        def scvmmOpts = [username: "user", password: "pass", host: "scvmm-host"]
        def responseWithEmptyProfiles = [success: true, capabilityProfiles: []]

        when: "execute is called with empty capability profiles"
        cloudCapabilityProfilesSync.execute()

        then: "services are called but cloud is not saved due to empty capability profiles list"
        1 * cloudService.get(cloud.id) >> cloud
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.getCapabilityProfiles(scvmmOpts) >> responseWithEmptyProfiles
        0 * cloudService.save(_)

        and: "capability profiles are not set on cloud"
        cloud.getConfigProperty('capabilityProfiles') == null
    }

    @Unroll
    def "execute should handle empty capability profiles list when regionCode is set"() {
        given: "A cloud with regionCode and cloud response with empty capability profiles"
        cloud.regionCode = "us-east-1"
        def scvmmOpts = [username: "user", password: "pass", host: "scvmm-host"]
        def responseWithEmptyProfiles = [success: true, cloud: [CapabilityProfiles: []]]

        when: "execute is called with empty capability profiles"
        cloudCapabilityProfilesSync.execute()

        then: "services are called but cloud is not saved due to empty capability profiles list"
        1 * cloudService.get(cloud.id) >> cloud
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.getCloud(scvmmOpts) >> responseWithEmptyProfiles
        0 * cloudService.save(_)

        and: "capability profiles are not set on cloud"
        cloud.getConfigProperty('capabilityProfiles') == null
    }

    @Unroll
    def "execute should handle exceptions gracefully"() {
        given: "A scenario that throws an exception"
        cloud.regionCode = null

        when: "execute is called and an exception occurs"
        cloudCapabilityProfilesSync.execute()

        then: "exception is caught and handled gracefully"
        1 * cloudService.get(cloud.id) >> { throw new RuntimeException("Database error") }
        0 * computeServerService.find(_ as DataQuery)
        0 * mockApiService.getScvmmZoneAndHypervisorOpts(_, _, _)
        0 * mockApiService.getCapabilityProfiles(_)
        0 * cloudService.save(_)

        and: "no exception is thrown from the method"
        noExceptionThrown()
    }

    @Unroll
    def "execute should handle server not found scenario"() {
        given: "A cloud but no server found"
        cloud.regionCode = null

        when: "execute is called with no server found"
        cloudCapabilityProfilesSync.execute()

        then: "services are called but process stops when server is not found"
        1 * cloudService.get(cloud.id) >> cloud
        1 * computeServerService.find(_ as DataQuery) >> null
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, null) >> [:]
        1 * mockApiService.getCapabilityProfiles([:]) >> [success: false]
        0 * cloudService.save(_)

        and: "capability profiles are not set on cloud"
        cloud.getConfigProperty('capabilityProfiles') == null
    }

    @Unroll
    def "execute should handle different capability profile structures"() {
        given: "Various capability profile response structures"
        cloud.regionCode = regionCode
        def scvmmOpts = [username: "user", password: "pass", host: "scvmm-host"]

        when: "execute is called"
        cloudCapabilityProfilesSync.execute()

        then: "services are called with appropriate method based on regionCode"
        1 * cloudService.get(cloud.id) >> cloud
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        if (regionCode) {
            1 * mockApiService.getCloud(scvmmOpts) >> apiResponse
        } else {
            1 * mockApiService.getCapabilityProfiles(scvmmOpts) >> apiResponse
        }
        saveCallCount * cloudService.save(cloud)

        and: "capability profiles are set correctly"
        cloud.getConfigProperty('capabilityProfiles') == expectedProfiles

        where:
        regionCode | apiResponse | saveCallCount | expectedProfiles
        null       | [success: true, capabilityProfiles: [[Name: "Profile1"], [Name: "Profile2"]]] | 1 | ["Profile1", "Profile2"]
        "region1"  | [success: true, cloud: [CapabilityProfiles: ["CloudProfile1", "CloudProfile2"]]] | 1 | ["CloudProfile1", "CloudProfile2"]
        null       | [success: true, capabilityProfiles: [[Name: "SingleProfile"]]] | 1 | ["SingleProfile"]
        "region1"  | [success: true, cloud: [CapabilityProfiles: ["SingleCloudProfile"]]] | 1 | ["SingleCloudProfile"]
    }

    @Unroll
    def "execute should handle capability profiles with complex name structures"() {
        given: "Capability profiles with various name formats"
        cloud.regionCode = null
        def scvmmOpts = [username: "user", password: "pass", host: "scvmm-host"]
        def complexResponse = [
                success: true,
                capabilityProfiles: [
                        [Name: "High Performance - GPU Optimized"],
                        [Name: "Standard - General Purpose"],
                        [Name: "Low Power - Energy Efficient"],
                        [Name: "Custom Profile (Development)"],
                        [Name: ""]  // Empty name
                ]
        ]

        when: "execute is called with complex capability profile names"
        cloudCapabilityProfilesSync.execute()

        then: "services are called and all profile names are extracted"
        1 * cloudService.get(cloud.id) >> cloud
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.getCapabilityProfiles(scvmmOpts) >> complexResponse
        1 * cloudService.save(cloud)

        and: "all capability profile names including empty ones are preserved"
        def profiles = cloud.getConfigProperty('capabilityProfiles')
        profiles.size() == 5
        profiles.contains("High Performance - GPU Optimized")
        profiles.contains("Standard - General Purpose")
        profiles.contains("Low Power - Energy Efficient")
        profiles.contains("Custom Profile (Development)")
        profiles.contains("")
    }

    @Unroll
    def "execute should preserve existing cloud configuration when capability profiles sync fails"() {
        given: "A cloud with existing configuration and failed API call"
        cloud.regionCode = null
        cloud.setConfigProperty('existingProperty', 'existingValue')
        cloud.setConfigProperty('capabilityProfiles', ['OldProfile1', 'OldProfile2'])
        def scvmmOpts = [username: "user", password: "pass", host: "scvmm-host"]
        def failedResponse = [success: false, error: "Network timeout"]

        when: "execute is called with failed response"
        cloudCapabilityProfilesSync.execute()

        then: "services are called but cloud is not saved"
        1 * cloudService.get(cloud.id) >> cloud
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.getCapabilityProfiles(scvmmOpts) >> failedResponse
        0 * cloudService.save(_)

        and: "existing configuration is preserved"
        cloud.getConfigProperty('existingProperty') == 'existingValue'
        cloud.getConfigProperty('capabilityProfiles') == ['OldProfile1', 'OldProfile2']
    }

    @Unroll
    def "execute should update existing capability profiles when sync succeeds"() {
        given: "A cloud with existing capability profiles and successful API call"
        cloud.regionCode = null
        cloud.setConfigProperty('capabilityProfiles', ['OldProfile1', 'OldProfile2'])
        def scvmmOpts = [username: "user", password: "pass", host: "scvmm-host"]
        def successResponse = [
                success: true,
                capabilityProfiles: [
                        [Name: "NewProfile1"],
                        [Name: "NewProfile2"],
                        [Name: "NewProfile3"]
                ]
        ]

        when: "execute is called with successful response"
        cloudCapabilityProfilesSync.execute()

        then: "services are called and cloud is saved with new profiles"
        1 * cloudService.get(cloud.id) >> cloud
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        1 * mockApiService.getCapabilityProfiles(scvmmOpts) >> successResponse
        1 * cloudService.save(cloud)

        and: "capability profiles are updated with new values"
        cloud.getConfigProperty('capabilityProfiles') == ['NewProfile1', 'NewProfile2', 'NewProfile3']
    }

    @Unroll
    def "execute should handle null cloud from service"() {
        given: "A null cloud returned from service"
        def testSync = new TestableCloudCapabilityProfilesSync(morpheusContext, new Cloud(id: 99L), mockApiService)

        when: "execute is called with null cloud"
        testSync.execute()

        then: "service is called but process stops when cloud is null"
        1 * cloudService.get(99L) >> null
        0 * computeServerService.find(_ as DataQuery)
        0 * mockApiService.getScvmmZoneAndHypervisorOpts(_, _, _)
        0 * mockApiService.getCapabilityProfiles(_)
        0 * cloudService.save(_)

        and: "no exception is thrown"
        noExceptionThrown()
    }

    @Unroll
    def "execute should handle malformed API responses gracefully"() {
        given: "Malformed API responses"
        cloud.regionCode = regionCode
        def scvmmOpts = [username: "user", password: "pass", host: "scvmm-host"]

        when: "execute is called with malformed response"
        cloudCapabilityProfilesSync.execute()

        then: "services are called but cloud is not saved due to malformed response"
        1 * cloudService.get(cloud.id) >> cloud
        1 * computeServerService.find(_ as DataQuery) >> server
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server) >> scvmmOpts
        if (regionCode) {
            1 * mockApiService.getCloud(scvmmOpts) >> malformedResponse
        } else {
            1 * mockApiService.getCapabilityProfiles(scvmmOpts) >> malformedResponse
        }
        saveCallCount * cloudService.save(_)

        and: "capability profiles are not set or remain unchanged"
        cloud.getConfigProperty('capabilityProfiles') == expectedResult

        where:
        regionCode | malformedResponse | saveCallCount | expectedResult
        null       | [success: true] | 0 | null  // Missing capabilityProfiles
        null       | [capabilityProfiles: [[Name: "Profile1"]]] | 0 | null  // Missing success
        null       | [:] | 0 | null  // Empty response
        "region1"  | [success: true] | 0 | null  // Missing cloud
        "region1"  | [success: true, cloud: [:]] | 0 | null  // Missing CapabilityProfiles in cloud
        "region1"  | [:] | 0 | null  // Empty response
    }

}
