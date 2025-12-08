package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusComputeTypeSetService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusOsTypeService
import com.morpheusdata.core.MorpheusProcessService
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.MorpheusStorageVolumeService
import com.morpheusdata.core.MorpheusVirtualImageService
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.library.MorpheusWorkloadTypeService
import com.morpheusdata.core.network.MorpheusNetworkService
import com.morpheusdata.core.synchronous.MorpheusSynchronousAccountCredentialService
import com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService
import com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudTypeService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.synchronous.library.MorpheusSynchronousWorkloadTypeService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkProxyService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudType
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.NetworkProxy
import org.junit.jupiter.api.BeforeEach
import spock.lang.Specification
import spock.lang.Unroll

class ScvmmOptionSourceProviderSpec extends Specification{
    private MorpheusContext morpheusContext
    private ScvmmPlugin plugin
    private ScvmmOptionSourceProvider optionSourceProvider
    private ScvmmApiService mockApiService
    private MorpheusSynchronousComputeServerService computeServerService
    private MorpheusComputeServerService asyncComputeServerService
    private MorpheusComputeTypeSetService asyncComputeTypeSetService
    private MorpheusProcessService processService
    private MorpheusSynchronousWorkloadTypeService workloadTypeService
    private MorpheusWorkloadTypeService asyncWorkloadTypeService
    private MorpheusCloudService asyncCloudService
    private MorpheusOsTypeService asyncOsTypeService
    private MorpheusSynchronousCloudService cloudService
    private MorpheusSynchronousAccountCredentialService accountCredentialService
    private MorpheusSynchronousNetworkService networkService
    private MorpheusSynchronousStorageVolumeService storageVolumeService
    private MorpheusSynchronousResourcePermissionService resourcePermissionService
    private MorpheusStorageVolumeService asyncStorageVolumeService
    private MorpheusSynchronousVirtualImageService virtualImageService
    private MorpheusVirtualImageService asyncVirtualImageService
    private MorpheusAsyncServices morpheusAsyncServices

    private ComputeServer controller1, controller2, controller3

    @BeforeEach
    void setup() {
        // Setup mock context and services
        morpheusContext = Mock(MorpheusContext)
        plugin = Mock(ScvmmPlugin)

        // Mock services
        computeServerService = Mock(MorpheusSynchronousComputeServerService)
        asyncComputeServerService = Mock(MorpheusComputeServerService)
        asyncComputeTypeSetService = Mock(MorpheusComputeTypeSetService)
        processService = Mock(MorpheusProcessService)
        asyncCloudService = Mock(MorpheusCloudService)
        def asyncNetworkService = Mock(MorpheusNetworkService)
        workloadTypeService = Mock(MorpheusSynchronousWorkloadTypeService)
        asyncWorkloadTypeService = Mock(MorpheusWorkloadTypeService)
        storageVolumeService = Mock(MorpheusSynchronousStorageVolumeService)
        resourcePermissionService = Mock(MorpheusSynchronousResourcePermissionService)
        cloudService = Mock(MorpheusSynchronousCloudService)
        accountCredentialService = Mock(MorpheusSynchronousAccountCredentialService)
        networkService = Mock(MorpheusSynchronousNetworkService)
        asyncStorageVolumeService = Mock(MorpheusStorageVolumeService)
        virtualImageService = Mock(MorpheusSynchronousVirtualImageService)
        asyncVirtualImageService = Mock(MorpheusVirtualImageService)
        asyncOsTypeService = Mock(MorpheusOsTypeService)

        def morpheusServices = Mock(MorpheusServices) {
            getComputeServer() >> computeServerService
            getCloud() >> cloudService
            getWorkloadType() >> workloadTypeService
            getStorageVolume() >> storageVolumeService
            getVirtualImage() >> virtualImageService
            getResourcePermission() >> resourcePermissionService
            getAccountCredential() >> accountCredentialService
            getNetwork() >> networkService
        }
        morpheusAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> asyncCloudService
            getNetwork() >> asyncNetworkService
            getComputeServer() >> asyncComputeServerService
            getStorageVolume() >> asyncStorageVolumeService
            getVirtualImage() >> asyncVirtualImageService
            getComputeTypeSet() >> asyncComputeTypeSetService
            getWorkloadType() >> asyncWorkloadTypeService
            getOsType() >> asyncOsTypeService
        }

        // Configure context mocks
        morpheusContext.getAsync() >> morpheusAsyncServices
        morpheusContext.getServices() >> morpheusServices


        mockApiService = Mock(ScvmmApiService)
        optionSourceProvider = Spy(ScvmmOptionSourceProvider, constructorArgs: [plugin, morpheusContext])
        optionSourceProvider.apiService = mockApiService

    }

    @Unroll
    def "loadCloudFromZoneId sets credential data and flags correctly"() {
        given:
        def cloud = new Cloud()
        def params = [zoneId: 1L, credential: credential]
        def config = [:]
        def password = testPassword

        cloudService.get(1L) >> cloud
        def creds2 = new AccountCredential(data: ["data": "creds2"])

        if (credential) {
            accountCredentialService.loadCredentialConfig(_, config) >> ["data":["data": "creds2"]]
        } else {
            accountCredentialService.loadCredentials(cloud) >> creds2
        }

        when:
        def result = optionSourceProvider.loadCloudFromZoneId(params, config, password)

        then:
        result.is(cloud)
        result.accountCredentialLoaded == expectedLoaded
        result.accountCredentialData == expectedData
        config.password == expectedPassword

        where:
        credential                        | testPassword      | expectedLoaded | expectedData | expectedPassword
        [type: "notLocal", foo: "bar"]    | "irrelevant"      | true           | ["data": "creds2"]      | null
        null                              | "irrelevant"      | true           | ["data": "creds2"]     | null
        [type: "local"]                   | "mypassword"      | false          | null         | "mypassword"
        [type: "local"]                   | "************"    | false          | null         | null
    }

    def "createNewCloud sets credential data or password as expected"() {
        given:
        def cloud = new Cloud()
        def params = [credential: credential]
        def config = [:]
        def password = testPassword

        def credentials = [data: ["user": "user", "pass":"pass"]]
        if (credential && credential.type != "local") {
            accountCredentialService.loadCredentialConfig(credential, config) >> credentials
        }

        when:
        def result = optionSourceProvider.createNewCloud(params, config, password)

        then:
        result instanceof Cloud
        result.accountCredentialLoaded == expectedLoaded
        result.accountCredentialData == expectedData
        config.password == expectedPassword

        where:
        credential                        | testPassword      | expectedLoaded | expectedData | expectedPassword
        [type: "notLocal", foo: "bar"]    | "irrelevant"      | true           | ["user": "user", "pass":"pass"]      | null
        null                              | "mypassword"      | false          | null         | "mypassword"
        [type: "local"]                   | "mypassword"      | false          | null         | "mypassword"
    }

    def "extractConfigAndPassword extracts config and password from various param shapes"() {
        given:

        when:
        def result = optionSourceProvider.extractConfigAndPassword(params)

        then:
        result.config == expectedConfig
        result.password == expectedPassword
        result.params == expectedParams

        where:
        params                                                                                 || expectedConfig                                  | expectedPassword | expectedParams
        [config: [host: "h1", username: "u1", hostGroup: "g1", password: "p1"]]                || [host: "h1", username: "u1", hostGroup: "g1"]   | "p1"            | [config: [host: "h1", username: "u1", hostGroup: "g1", password: "p1"]]
        [ "config[host]": "h2", "config[username]": "u2", "config[hostGroup]": "g2", "config[password]": "p2" ] || [host: "h2", username: "u2", hostGroup: "g2"] | "p2"            | [ "config[host]": "h2", "config[username]": "u2", "config[hostGroup]": "g2", "config[password]": "p2" ]
        [[config: [host: "h3", username: "u3", hostGroup: "g3", password: "p3"]]]              || [host: ["h3"], username: ["u3"], hostGroup: ["g3"]]   | ["p3"]            | [[config: [host: "h3", username: "u3", hostGroup: "g3", password: "p3"]]]
        [[ "config[host]": "h4", "config[username]": "u4", "config[hostGroup]": "g4", "config[password]": "p4" ]] || [host: ["h4"], username: ["u4"], hostGroup: ["g4"]] | ["p4"]            | [[ "config[host]": "h4", "config[username]": "u4", "config[hostGroup]": "g4", "config[password]": "p4" ]]
    }

    def "setupCloudConfig sets up cloud with config, regionCode, cloudType, and apiProxy"() {
        given:
        def configMap = [host: "h", username: "u", hostGroup: "g"]
        def password = "pw"
        def paramsInput = params
        def extracted = [config: configMap, password: password, params: paramsInput]
        def cloud = new Cloud()
        optionSourceProvider.loadCloudFromZoneId(_, configMap, password) >> {cloud}
        optionSourceProvider.createNewCloud(_, configMap, password) >> {cloud}
        optionSourceProvider.extractConfigAndPassword(paramsInput) >> extracted
        def cloudType = new CloudType(id:99L)
        def cloudTypeService = Mock(MorpheusSynchronousCloudTypeService)
        cloudService.getType() >> cloudTypeService
        cloudTypeService.find(_) >> cloudType

        def apiProxy = new NetworkProxy(id: 123L)
        def networkProxyService = Mock(MorpheusSynchronousNetworkProxyService)
        networkService.getNetworkProxy() >> networkProxyService
        networkProxyService.get(42L) >> apiProxy

        when:
        def result = optionSourceProvider.setupCloudConfig(paramsInput)

        then:
        result.is(cloud)
        result.configMap == configMap
        result.regionCode == expectedRegion
        result.cloudType == cloudType
        result.apiProxy.id == expectedApiProxy.id

        where:
        params                                                   || expectedRegion | expectedApiProxy
        [zoneId: 1L, config: [cloud: "region1", apiProxy: "42"]] || "region1"      | [id: 123]
        [config: [cloud: "region2", apiProxy: "42"]]             || "region2"      | [id: 123]
    }

    def "scvmmCloud returns correct options based on apiService results"() {
        given:
        def params = [foo: "bar"]
        def cloud = new Cloud()
        def apiConfig = [sshUsername: "user", sshPassword: "pass"]
        def cloudsList = testClouds
        def results = [clouds: cloudsList]

        optionSourceProvider.setupCloudConfig(params) >> cloud
        optionSourceProvider.getApiConfig(cloud) >> apiConfig
        mockApiService.listClouds(apiConfig) >> results

        when:
        def optionList = optionSourceProvider.scvmmCloud(params)

        then:
        optionList == expectedOptions

        where:
        testClouds                                                                 | expectedOptions
        [[Name: "Cloud1", ID: "1"], [Name: "Cloud2", ID: "2"]]                     | [[name: "Select a Cloud", value: ""], [name: "Cloud1", value: "1"], [name: "Cloud2", value: "2"]]
        []                                                                         | [[name: "No Clouds Found: verify credentials above", value: ""]]
        null                                                                       | [[name: "No Clouds Found: verify credentials above", value: ""]]
    }

    def "scvmmHostGroup returns correct options based on apiService results"() {
        given:
        def params = [foo: "bar"]
        def cloud = new Cloud()
        def apiConfig = [sshUsername: "user", sshPassword: "pass"]
        def hostGroupsList = testHostGroups
        def results = [hostGroups: hostGroupsList]

        optionSourceProvider.setupCloudConfig(params) >> cloud
        optionSourceProvider.getApiConfig(cloud) >> apiConfig
        mockApiService.listHostGroups(apiConfig) >> results

        when:
        def optionList = optionSourceProvider.scvmmHostGroup(params)

        then:
        optionList == expectedOptions

        where:
        testHostGroups                                   | expectedOptions
        [[path: "Group1"], [path: "Group2"]]             | [[name: "Select", value: ""], [name: "Group1", value: "Group1"], [name: "Group2", value: "Group2"]]
        []                                               | [[name: "No Host Groups found", value: ""]]
        null                                             | [[name: "No Host Groups found", value: ""]]
    }

    def "scvmmCluster returns correct options based on apiService results"() {
        given:
        def params = [foo: "bar"]
        def cloud = new Cloud()
        def apiConfig = [sshUsername: "user", sshPassword: "pass"]
        def clustersList = testClusters
        def results = [clusters: clustersList]

        optionSourceProvider.setupCloudConfig(params) >> cloud
        optionSourceProvider.getApiConfig(cloud) >> apiConfig
        mockApiService.listClusters(apiConfig) >> results

        when:
        def optionList = optionSourceProvider.scvmmCluster(params)

        then:
        optionList == expectedOptions

        where:
        testClusters                                         | expectedOptions
        [[name: "Cluster1", id: "1"], [name: "Cluster2", id: "2"]] | [[name: "All", value: ""], [name: "Cluster1", value: "1"], [name: "Cluster2", value: "2"]]
        []                                                   | [[name: "No Clusters found: check your config", value: ""]]
        null                                                 | [[name: "No Clusters found: check your config", value: ""]]
    }

    def "scvmmLibraryShares returns correct options based on apiService results"() {
        given:
        def params = [foo: "bar"]
        def cloud = new Cloud()
        def apiConfig = [sshUsername: "user", sshPassword: "pass"]
        def results = [libraryShares: testShares]

        optionSourceProvider.setupCloudConfig(params) >> cloud
        optionSourceProvider.getApiConfig(cloud) >> apiConfig
        mockApiService.listLibraryShares(apiConfig) >> results

        when:
        def optionList = optionSourceProvider.scvmmLibraryShares(params)

        then:
        optionList == expectedOptions

        where:
        testShares                                   | expectedOptions
        [[Path: "Share1"], [Path: "Share2"]]         | [[name: "Share1", value: "Share1"], [name: "Share2", value: "Share2"]]
        []                                           | [[name: "No Library Shares found", value: ""]]
        null                                         | [[name: "No Library Shares found", value: ""]]
    }

    def "scvmmSharedControllers returns correct options based on filters and results"() {
        given:
        def params = [zoneId: 1L, config: [host: "host1"]]
        controller1 = new ComputeServer(name:"Controller1", id: 10L)
        controller2 = new ComputeServer(name:"Controller2", id: 20L)
        controller3 = new ComputeServer(name:"Controller3", id: 30L)
        def sharedControllers = controller1
        def expectedOut = [name:"Controller1", value:10L]

        computeServerService.find(_) >> sharedControllers

        when:
        def optionList = optionSourceProvider.scvmmSharedControllers(params)

        then:
        optionList[0].name == expectedOut.name

    }


    def "scvmmCapabilityProfile returns correct options based on capabilityProfiles"() {
        given:
        def params = testParams
        def tmpZone = new Cloud()
        tmpZone.setConfigProperty('capabilityProfiles', ['ProfileA', 'ProfileB'])

        if (capabilityProfiles != null) {
            tmpZone.getConfigProperty('capabilityProfiles') >> capabilityProfiles
        }
        cloudService.get(_) >> tmpZone

        when:
        def result = optionSourceProvider.scvmmCapabilityProfile(params)

        then:
        result == expected

        where:
        testParams           | capabilityProfiles         | expected
        [zoneId: 1L]         | ['ProfileA', 'ProfileB']  | [[name: 'ProfileA', value: 'ProfileA'], [name: 'ProfileB', value: 'ProfileB']]
        [:]                  | null                      | [[name: 'Not required', value: -1]]
    }


    def "scvmmHost returns correct options based on params and zone config"() {
        given:
        def params = testParams
        def tmpZone = new Cloud()
        tmpZone.id = 42L
        tmpZone.account = [id: 100L]
        tmpZone.setConfigProperty('hideHostSelection') >> hideHostSelection
        cloudService.get(_) >> tmpZone

        def queryMock = Mock(com.morpheusdata.core.data.DataQuery)
        optionSourceProvider.DataQuery() >> queryMock

        if (!hideHostSelection) {
            computeServerService.listIdentityProjections(_) >> hosts
        }

        when:
        def result = optionSourceProvider.scvmmHost(params)

        then:
        result == expected

        where:
        testParams                                                                 | hideHostSelection | hosts                                                                 | expected
        [zoneId: 1L, resourcePoolId: 5L]                                           | false             | [ [name: "Host1", id: 1], [name: "Host2", id: 2] ]                   | [[name: "Host1", value: 1], [name: "Host2", value: 2]]
        [zoneId: 1L, config: [resourcePool: "7"]]                                  | false             | [ [name: "Host3", id: 3] ]                                           | [[name: "Host3", value: 3]]
        [zoneId: 1L]                                                               | false             | []                                                                   | []
        // [zoneId: 1L]                                                               | 'on'              | _                                                                    | [[name: "Auto", value: ""]]
    }

    def "scvmmVirtualImages returns correct options for found images"() {
        given:
        def params = [zoneId: 1L, accountId: 100L]
        def tmpZone = new Cloud()
        tmpZone.id = 1L
        tmpZone.regionCode = "us-east"
        cloudService.get(1L) >> tmpZone

        def queryMock = Mock(com.morpheusdata.core.data.DataQuery)
        optionSourceProvider.DataQuery() >> queryMock

        def images = [
                [name: "Image1", id: 11],
                [name: "Image2", id: 22]
        ]
        virtualImageService.listIdentityProjections(_) >> images

        when:
        def result = optionSourceProvider.scvmmVirtualImages(params)

        then:
        result == [[name: "Image1", value: 11], [name: "Image2", value: 22]]
    }

    def "getApiConfig returns merged config from apiService"() {
        given:
        def cloud = new Cloud()
        def zoneOpts = [sshUsername: "user", sshPassword: "pass"]
        def initOpts = [foo: "bar"]
        mockApiService.getScvmmZoneOpts(morpheusContext, cloud) >> zoneOpts
        mockApiService.getScvmmInitializationOpts(cloud) >> initOpts

        when:
        def result = optionSourceProvider.getApiConfig(cloud)

        then:
        result == [sshUsername: "user", sshPassword: "pass", foo: "bar"]
    }

    def "getMethodNames returns expected list"() {
        expect:
        optionSourceProvider.getMethodNames() == [
                'scvmmCloud', 'scvmmHostGroup', 'scvmmCluster', 'scvmmLibraryShares', 'scvmmSharedControllers',
                'scvmmCapabilityProfile', 'scvmmHost', 'scvmmVirtualImages'
        ]
    }

    def "getName returns correct name"() {
        expect:
        optionSourceProvider.getName() == 'SCVMM Option Source'
    }

    def "getCode returns correct code"() {
        expect:
        optionSourceProvider.getCode() == 'scvmm-option-source'
    }

    def "getMorpheus returns morpheusContext"() {
        expect:
        optionSourceProvider.getMorpheus() == morpheusContext
    }

    def "getPlugin returns plugin"() {
        expect:
        optionSourceProvider.getPlugin() == plugin
    }
}
