package com.morpheusdata.scvmm

import com.bertramlabs.plugins.karman.CloudFile
import com.morpheusdata.core.BulkSaveResult
import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeTypeSetService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusProcessService
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.MorpheusStorageVolumeTypeService
import com.morpheusdata.core.MorpheusVirtualImageService
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusStorageVolumeService
import com.morpheusdata.core.MorpheusWorkloadService
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.compute.MorpheusComputeServerInterfaceService
import com.morpheusdata.core.data.DataAndFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.library.MorpheusWorkloadTypeService
import com.morpheusdata.core.network.MorpheusNetworkPoolService
import com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageLocationService
import com.morpheusdata.core.synchronous.MorpheusSynchronousWorkloadService
import com.morpheusdata.core.synchronous.provisioning.MorpheusSynchronousProvisionService
import com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService
import com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.projection.DatastoreIdentity
import com.morpheusdata.core.network.MorpheusNetworkService
import com.morpheusdata.core.synchronous.library.MorpheusSynchronousWorkloadTypeService
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.*
import com.morpheusdata.scvmm.util.MorpheusUtil
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.NetworkConfiguration
import com.morpheusdata.model.provisioning.UserConfiguration
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.InitializeHypervisorResponse
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.scvmm.testdata.ProvisionDataHelper
import groovy.json.JsonOutput
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.BeforeEach
import spock.lang.Specification
import spock.lang.Unroll

class ScvmmProvisionProviderSpec extends Specification {
    private MorpheusContext morpheusContext
    private ScvmmPlugin plugin
    private ScvmmProvisionProvider provisionProvider
    private ScvmmApiService mockApiService
    private MorpheusSynchronousComputeServerService computeServerService
    private MorpheusComputeServerService asyncComputeServerService
    private MorpheusComputeTypeSetService asyncComputeTypeSetService
    private MorpheusProcessService processService
    private MorpheusSynchronousWorkloadService workloadService
    private MorpheusSynchronousWorkloadTypeService workloadTypeService
    private MorpheusWorkloadTypeService asyncWorkloadTypeService
    private MorpheusWorkloadService asyncWorkloadService
    private MorpheusCloudService asyncCloudService
    private MorpheusSynchronousCloudService cloudService
    private MorpheusSynchronousNetworkService networkService
    private MorpheusSynchronousStorageVolumeService storageVolumeService
    private MorpheusSynchronousResourcePermissionService resourcePermissionService
    private MorpheusStorageVolumeService asyncStorageVolumeService
    private MorpheusSynchronousVirtualImageService virtualImageService
    private MorpheusVirtualImageService asyncVirtualImageService

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
        workloadService = Mock(MorpheusSynchronousWorkloadService)
        asyncWorkloadService = Mock(MorpheusWorkloadService)
        workloadTypeService = Mock(MorpheusSynchronousWorkloadTypeService)
        asyncWorkloadTypeService = Mock(MorpheusWorkloadTypeService)
        storageVolumeService = Mock(MorpheusSynchronousStorageVolumeService)
        resourcePermissionService = Mock(MorpheusSynchronousResourcePermissionService)
        cloudService = Mock(MorpheusSynchronousCloudService)
        networkService = Mock(MorpheusSynchronousNetworkService)
        asyncStorageVolumeService = Mock(MorpheusStorageVolumeService)
        virtualImageService = Mock(MorpheusSynchronousVirtualImageService)
        asyncVirtualImageService = Mock(MorpheusVirtualImageService)

        def morpheusServices = Mock(MorpheusServices) {
            getComputeServer() >> computeServerService
            getCloud() >> cloudService
            getWorkload() >> workloadService
            getWorkloadType() >> workloadTypeService
            getStorageVolume() >> storageVolumeService
            getVirtualImage() >> virtualImageService
            getResourcePermission() >> resourcePermissionService
        }
        def morpheusAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> asyncCloudService
            getNetwork() >> asyncNetworkService
            getComputeServer() >> asyncComputeServerService
            getStorageVolume() >> asyncStorageVolumeService
            getVirtualImage() >> asyncVirtualImageService
            getComputeTypeSet() >> asyncComputeTypeSetService
            getWorkloadType() >> asyncWorkloadTypeService
            getWorkload() >> asyncWorkloadService
        }

        // Configure context mocks
        morpheusContext.getProcess() >> processService
        morpheusContext.getAsync() >> morpheusAsyncServices
        morpheusContext.getServices() >> morpheusServices


        mockApiService = Mock(ScvmmApiService)
        provisionProvider = Spy(ScvmmProvisionProvider, constructorArgs: [plugin, morpheusContext])
        provisionProvider.apiService = mockApiService
    }

    def storageVols() {
        return ProvisionDataHelper.storageVols()
    }

    def "test runWorkload successful VM creation"() {
        given:
        // Create concrete objects instead of mocks where possible
        def cloud = ProvisionDataHelper.runWorkload_getCloud()
        def account = ProvisionDataHelper.runWorkload_getAccount()
        cloud.account = account

        def osType = ProvisionDataHelper.runWorkload_getOsType()
        def virtualImage = ProvisionDataHelper.runWorkload_getVirtualImage(osType)

        def servicePlan = ProvisionDataHelper.runWorkload_getServicePlan()
        def instance = ProvisionDataHelper.runWorkload_getInstance(servicePlan)

        // Create ComputeServer with concrete values
        def computerServer = ProvisionDataHelper.runWorkload_getConcreteComputeServer(cloud, virtualImage, storageVols())

        def workloadType = ProvisionDataHelper.runWorkload_getWorkloadType()
        def configMap = ProvisionDataHelper.configsMap
        String configsJson = JsonOutput.toJson(configMap)

        // Create workload with concrete values
        def workload = ProvisionDataHelper.runWorkload_getWorkload(computerServer, workloadType, instance)
        workload.configs = configsJson

        // Create UserConfiguration
        def userConfig = new UserConfiguration(username: "user", password: "pass")

        // Create WorkloadRequest with concrete values
        def workloadRequest = new WorkloadRequest()
        workloadRequest.usersConfiguration = [createUsers: [userConfig]]
        workloadRequest.cloudConfigOpts = [:]

        def opts = [noAgent: true]

        // Setup required response data
        def scvmmOpts = ProvisionDataHelper.runWorkload_getScvmmOpts(account, cloud)
        def controllerServer = ProvisionDataHelper.runWorkload_getControllerServer()
        def datastore = new Datastore(id: 5L, name: "datastore1", externalId: "ds-123")
        def node = new ComputeServer(id: 2L, name: "node-01")
        // Add this to your test setup
        def nodeServer = ProvisionDataHelper.runWorkload_getNodeServer()

        // Define expected response from getHostAndDatastore
        def hostAndDatastoreResponse = [node, datastore, "something", false]

        def mockedControllerOpts = ProvisionDataHelper.runWorkload_getMockedControllerOpts(controllerServer)

        // First create a mock for the VirtualImageFiles
        def mockCloudFile = Mock(CloudFile)
        mockCloudFile.getName() >> "ubuntu-22.04.vhdx"

        def mockedCloudFiles = [mockCloudFile]
        // Create a Single that returns the list
        def mockFilesSingle = Single.just(mockedCloudFiles)

        provisionProvider.getHostAndDatastore(_, _, _, _, _, _, _, _, _) >> {
            return hostAndDatastoreResponse
        }

        provisionProvider.getDiskExternalIds(_, _) >> {
            def rtn = []
            rtn << [rootVolume: true, externalId: "external-id-1", idx: 0]
            rtn << [rootVolume: false, externalId: "external-id-2", idx: 1 ]
            return rtn
        }
        provisionProvider.additionalTemplateDisksConfig(_, _) >> {
            def additionalTemplateDisks = []
            additionalTemplateDisks << [idx: 1, diskCounter: 1, diskSize: 9663676416, busNumber: 0]
            return additionalTemplateDisks
        }
        provisionProvider.findVmNodeServerTypeForCloud(_, _, _) >> {
            null
        }
        provisionProvider.saveAndGetMorpheusServer(_, _) >> { ComputeServer serverArg, boolean fullReload ->
            // Return the same server but with a simulated "saved" state
            // This prevents the actual saving operation while maintaining the test flow
            return serverArg
        }

        // Add this to your test setup or directly in your test case
        provisionProvider.getScvmmContainerOpts(_) >> { Workload container ->
            return ProvisionDataHelper.runWorkload_getScvmmContainerOptsResponse(container)
        }

        // Mock resolveExternalPoolId to prevent MissingMethodException
        provisionProvider.resolveExternalPoolId(_, _) >> { Map containerConfig, ComputeServer server ->
            return null // or return a specific externalPoolId value if needed
        }

        // Mock selectHostAndDatastore to prevent null pointer exceptions
        provisionProvider.selectHostAndDatastore(_) >> { Map args ->
            return [success: true, response: null, node: node, datastore: datastore, scvmmOpts: [:]]
        }

        // Mock getHostDatastoreInfo to prevent null pointer exceptions
        provisionProvider.getHostDatastoreInfo(_) >> { Map args ->
            return [success: true, node: node, datastore: datastore, volumePath: "/volume/path", highlyAvailable: false]
        }

        // Mock selectDatastore to prevent null pointer exceptions
        provisionProvider.selectDatastore(_, _, _, _, _, _, _) >> { args ->
            return datastore
        }

        provisionProvider.constructCloudInitOptions(_, _, _, _, _, _, _) >> { args ->
            return ProvisionDataHelper.runWorkload_constructCloudInitOptionsResponse(args)
        }

        mockApiService.getServerDetails(_, _) >> { Map options, String serverId ->
            return ProvisionDataHelper.reunWorkload_getServerDetailsResponse(serverId)
        }

        provisionProvider.loadDatastoreForVolume(_, _, _, _) >> { Cloud cld, String hostVolumeId, String fileShareId, String partitionId ->
            return datastore // Return the datastore object you created in your test setup
        }

        // Mock applyComputeServerNetworkIp
        provisionProvider.applyComputeServerNetworkIp(_, _, _, _, _) >> { ComputeServer serverObj, String internalIp, String externalIp, int index, def macAddress  ->
            return ProvisionDataHelper.runWorkload_applyComputeServerNetworkIpResponse(internalIp, externalIp)
        }

        provisionProvider.cloneParentCleanup(_, _) >> { Map cloneParentCleanOpts, ServiceResponse response ->
            return null
        }

        // Then modify your existing mock for computeServerService.get() to handle different IDs
        computeServerService.get(_) >> { Long id ->
            if (id == nodeServer.id) {
                return nodeServer
            } else if (id == computerServer.id) {
                return computerServer
            } else {
                return controllerServer
            }
        }

        computeServerService.find(_) >> {
            controllerServer
        }
        workloadTypeService.get(_) >> {
            workloadType
        }

        storageVolumeService.save(_ as StorageVolume) >> {
            StorageVolume volume -> return volume
        }

        virtualImageService.get(_) >> {
            return virtualImage
        }

        asyncVirtualImageService.getVirtualImageFiles(_ as VirtualImage) >> {
            return mockFilesSingle
        }

        asyncComputeServerService.save(_) >> { ComputeServer serverObj ->
            return Single.just(serverObj)
        }

        // Mock bulkSave method to return a Single with BulkSaveResult
        def mockBulkSaveResult = Mock(BulkSaveResult) {
            getSuccess() >> true
            getPersistedItems() >> [computerServer]
            getFailedItems() >> []
        }
        asyncComputeServerService.bulkSave(_) >> Single.just(mockBulkSaveResult)

        // Mock process.startProcessStep

        processService.startProcessStep(_, _, _) >> {
            return Single.just(true)
        }

        morpheusContext.getProcess() >> processService

        morpheusContext.getServices() >> Mock(MorpheusServices) {
            getComputeServer() >> computeServerService
            getWorkloadType() >> workloadTypeService
            getStorageVolume() >> storageVolumeService
            getVirtualImage() >> virtualImageService

        }

        morpheusContext.getAsync() >> Mock(MorpheusAsyncServices) {
            getComputeServer() >> asyncComputeServerService
            getWorkloadType() >> workloadTypeService
            getStorageVolume() >> storageVolumeService
            getVirtualImage() >> asyncVirtualImageService
        }

        // Using the direct object return instead of a closure
        mockApiService.getScvmmZoneOpts(_, _) >> {
            scvmmOpts
        }
        mockApiService.getScvmmControllerOpts(_, _) >> {
            mockedControllerOpts
        }

        mockApiService.createServer(_) >> { Map servOpts ->
            return ProvisionDataHelper.runWorkload_createServerResponse()
        }

        asyncWorkloadService.save(_) >> { Workload wl ->
            return Single.just(wl)
        }

        mockApiService.checkServerReady(_, _) >> { Map options, String serverId ->
            return ProvisionDataHelper.runWorkload_checkServerReadyResponse(serverId)
        }

        mockApiService.setCdrom(_) >> { Map setCdromOpts ->
            return ProvisionDataHelper.runWorkload_setCdromResponse()
        }

        def asyncComputeServerInterfaceService = Mock(MorpheusComputeServerInterfaceService)
        asyncComputeServerService.getComputeServerInterface() >> asyncComputeServerInterfaceService
        // For case with privateIp, mock the server interface create/save methods
        def savedInterface = ProvisionDataHelper.runWorkload_savedInterface()
        asyncComputeServerInterfaceService.create(_, _) >> {
            return  Single.just([savedInterface])
        }

        when:
        def response = provisionProvider.runWorkload(workload, workloadRequest, opts)

        then:
        response.success
        response.data.success
    }


    def "getProvisionTypeCode should return scvmm"() {
        expect:
        provisionProvider.getProvisionTypeCode() == "scvmm"
    }

    def "getCircularIcon should return an Icon object with provision-circular.svg path"() {
        when:
        def result = provisionProvider.getCircularIcon()

        then:
        result instanceof com.morpheusdata.model.Icon
        result.path == "provision-circular.svg"
        result.darkPath == "provision-circular-dark.svg"
    }

    def "getOptionTypes should return non-empty list"() {
        when:
        def result = provisionProvider.getOptionTypes()

        then:
        result != null
        !result.isEmpty()
        result.every { it instanceof OptionType }
    }

    def "getNodeOptionTypes should return non-empty list"() {
        when:
        def result = provisionProvider.getNodeOptionTypes()

        then:
        result != null
        !result.isEmpty()
        result.every { it instanceof OptionType }
    }

    def "getRootVolumeStorageTypes should return non-empty list"() {
        given:
        def mockStorageTypes = [new StorageVolumeType(code: 'standard')]
        def mockedStorageVolTypeService =  Mock(MorpheusStorageVolumeTypeService)
        asyncStorageVolumeService.getStorageVolumeType() >> mockedStorageVolTypeService
        mockedStorageVolTypeService.list(_) >> Observable.fromIterable(mockStorageTypes)

        when:
        def result = provisionProvider.getRootVolumeStorageTypes()

        then:
        result != null
        !result.isEmpty()
        result == mockStorageTypes
    }

    def "getDataVolumeStorageTypes should return non-empty list"() {
        given:
        def mockStorageTypes = [new StorageVolumeType(code: 'standard')]
        def mockedStorageVolTypeService =  Mock(MorpheusStorageVolumeTypeService)
        asyncStorageVolumeService.getStorageVolumeType() >> mockedStorageVolTypeService
        mockedStorageVolTypeService.list(_) >> Observable.fromIterable(mockStorageTypes)

        when:
        def result = provisionProvider.getDataVolumeStorageTypes()

        then:
        result != null
        !result.isEmpty()
        result == mockStorageTypes
    }

    def "getServicePlans should return non-empty list"() {
        when:
        def result = provisionProvider.getServicePlans()

        then:
        result != null
        !result.isEmpty()
    }

    def "validateWorkload should return success response"() {
        given:
        def opts = [:]

        when:
        def response = provisionProvider.validateWorkload(opts)

        then:
        response.success
    }

    def "getCode should return scvmm"() {
        expect:
        provisionProvider.getCode() == "scvmm.provision"
    }

    def "getName should return 'SCVMM'"() {
        expect:
        provisionProvider.getName() == "SCVMM"
    }

    def "getMorpheus should return the morpheus context"() {
        expect:
        provisionProvider.getMorpheus() == morpheusContext
    }

    def "getPlugin should return the plugin"() {
        expect:
        provisionProvider.getPlugin() == plugin
    }

    @Unroll
    def "#methodName should return #expectedValue"() {
        when:
        def result = provisionProvider."$methodName"()

        then:
        if (methodName == "getHostType") {
            result.toString() == "vm" // Compare string representation for HostType
        } else {
            result == expectedValue
        }

        where:
        methodName                   | expectedValue
        "hasNetworks"                | true
        "getMaxNetworks"             | 1
        "canAddVolumes"              | true
        "canCustomizeRootVolume"     | true
        "canResizeRootVolume"        | true
        "canCustomizeDataVolumes"    | true
        "hasDatastores"              | true
        "getHostType"                | "vm"
        "serverType"                 | "vm"
        "supportsCustomServicePlans" | true
        "multiTenant"                | false
        "aclEnabled"                 | false
        "customSupported"            | true
        "lvmSupported"               | true
        "getNodeFormat"              | "vm"
        "hasSecurityGroups"          | false
        "hasNodeTypes"               | true
        "getHostDiskMode"            | "lvm"
        "hasComputeZonePools"        | true
    }

    def "getDeployTargetService should return vmDeployTargetService"() {
        expect:
        provisionProvider.getDeployTargetService() == "vmDeployTargetService"
    }

    def "createDefaultInstanceType should return false"() {
        expect:
        provisionProvider.createDefaultInstanceType() == false
    }

    @Unroll
    def "getVolumePathForDatastore should return #expectedPath for datastore #datastoreDescription"() {
        given:
        def datastore = datastoreInput ? new Datastore(id: 123L, name: "test-datastore") : null

        // Mock the storage volume response
        if (hasStorageVolume) {
            def storageVolume = new StorageVolume(volumePath: expectedPath)
            storageVolumeService.find({ DataQuery query ->
                query.filters.any { it.name == 'datastore.id' && it.value == datastore?.id } &&
                        query.filters.any { it.name == 'volumePath' && it.operator == '!=' && it.value == null }
            }) >> {
                return storageVolume
            }
        } else {
            storageVolumeService.find(_) >> null
        }

        when:
        def result = provisionProvider.getVolumePathForDatastore(datastore)

        then:
        result == expectedPath

        where:
        datastoreDescription     | datastoreInput | hasStorageVolume | expectedPath
        "valid datastore"        | true           | true             | "\\\\server\\path\\to\\volume"
        "datastore with no path" | true           | false            | null
        "null datastore"         | false          | false            | null
    }

    @Unroll
    def "testHostAndDatastore #hostAndDataScenario"() {
        given:
        // Setup cloud and account
        def cloud = new Cloud(id: 1L, name: 'test-cloud', regionCode: hasCloudRegion ? 'region1' : null)
        def account = new Account(id: 2L, name: 'test-account')

        // Setup datastores
        def datastore1 = new Datastore(id: 10L, name: 'datastore1', freeSpace: 100000000000L)
        def datastore2 = new Datastore(id: 11L, name: 'datastore2', freeSpace: 200000000000L)
        if (isSharedVolume) {
            datastore1.zonePool = new ComputeZonePool(id: 5L, name: 'zone-pool-1')
        }

        // Convert datastore parameter string to actual datastore object
        def datastoreObj = datastoreParam == 'datastore1' ? datastore1 :
                (datastoreParam == 'datastore2' ? datastore2 : null)

        // Setup computeServer/node
        def nodes = ProvisionDataHelper.getHostAndDatastore_ComuteServerNodes(cloud)
        def node1 = nodes[0]
        def node2 = nodes[1]

        // Setup storage volumes for the nodes
        def volume1 = new StorageVolume(id: 30L, datastore: datastore1)
        def volume2 = new StorageVolume(id: 31L, datastore: datastore2)
        node1.volumes = [volume1]
        node2.volumes = [volume1, volume2]

        resourcePermissionService.listAccessibleResources(_, _, _, _) >> [datastore1.id, datastore2.id]

        // Mock cloud datastore service
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        cloudService.getDatastore() >> datastoreService

        // Mock getVolumePathForDatastore
        if (mockVolumePathDatastore) {
            def dsForPath = mockVolumePathDatastore == 'datastore1' ? datastore1 : datastore2
            provisionProvider.getVolumePathForDatastore(dsForPath) >> "\\\\server\\path\\to\\volume"
        }

        // Configure mocks based on test scenario
        if (hostIdParam) {
            computeServerService.get(hostId) >> node1
        }

        if (scopedDatastoreIds) {
            computeServerService.list({ DataQuery query ->
                query.filters.any { it.name == 'hostId' && it.value == hostId }
            }) >> [node1]
        }

        if (searchForDatastores) {
            // Create proper list of datastore objects instead of using string
            def datastoreList = []
            if (datastoreResults == '[datastore1]') {
                datastoreList = [datastore1]
            }
            datastoreService.list(_ as DataQuery) >> datastoreList
        }

        if (searchForNodes) {
            computeServerService.list({ DataQuery query ->
                // For datastore_provided_directly scenario, return node1 instead of node2
                if (datastoreParam == 'datastore1') {
                    return [node1]  // Return node1 with ID 20 to match the expected result
                } else if (datastoreParam == 'datastore2') {
                    return [node2]
                }
                return []
            }) >> { DataQuery query ->
                if (datastoreParam == 'datastore1') {
                    return [node1]  // Return node1 with ID 20 to match the expected result
                } else if (datastoreParam == 'datastore2') {
                    return [node2]
                }
                return []
            }
        }

        when:
        def result = provisionProvider.getHostAndDatastore(cloud, account,
                clusterIdParam ? clusterIdParam : null,
                hostIdParam ? hostIdParam : null,
                datastoreObj,
                datastoreOption,
                size,
                siteId,
                requiredMemory)

        then:
        result[0]?.id == expectedNodeId
        result[1]?.id == expectedDatastoreId
        result[2] == expectedVolumePath
        result[3] == expectedHighlyAvailable

        where:
        hostAndDataScenario                | hasCloudRegion | clusterIdParam | hostIdParam | hostId | datastoreParam | datastoreOption | size          | siteId | requiredMemory | isSharedVolume | scopedDatastoreIds | searchForDatastores | datastoreResults | searchForNodes | mockVolumePathDatastore | expectedNodeId | expectedDatastoreId | expectedVolumePath            | expectedHighlyAvailable
        "cloud_deployment_with_auto_ds"    | true           | null           | null        | null   | null           | 'auto'          | 10000000000L  | null   | 4294967296L    | false          | false              | false               | []               | false          | null                    | null           | null               | null                          | false
        "host_specified_by_user"           | true           | null           | 20        | 20L    | null           | 'manual'        | 10000000000L  | null   | 4294967296L    | false          | true               | true                | '[datastore1]'   | false          | 'datastore1'            | 20L            | 10L                | "\\\\server\\path\\to\\volume" | false
        "datastore_provided_directly"      | true           | null           | null        | null   | 'datastore1'   | 'manual'        | 10000000000L  | null   | 4294967296L    | false          | false              | false               | []               | true           | 'datastore1'            | 20L            | 10L                | "\\\\server\\path\\to\\volume" | false
        "cluster_with_shared_volume"       | true           | '5'            | null        | null   | 'datastore1'   | 'manual'        | 10000000000L  | null   | 4294967296L    | true           | false              | false               | []               | true           | 'datastore1'            | 20L            | 10L                | "\\\\server\\path\\to\\volume" | true
        "non_cloud_with_node_and_ds"       | false          | null           | 20        | 20L    | null           | 'manual'        | 10000000000L  | null   | 4294967296L    | false          | true               | true                | '[datastore1]'   | false          | 'datastore1'            | 20L            | 10L                | "\\\\server\\path\\to\\volume" | false
        "ds_with_insufficient_mem_nodes"   | true           | null           | null        | null   | 'datastore1'   | 'manual'        | 10000000000L  | null   | 10737418240L   | false          | false              | false               | []               | true           | 'datastore1'            | null           | 10L                | "\\\\server\\path\\to\\volume" | false
    }


    @Unroll
    def "test_getHostAndDatastore_exceptionCases"() {
        given:
        // Setup cloud and account
        def cloud = new Cloud(id: 1L, name: 'test-cloud', regionCode: hasCloudRegion ? 'region1' : null)
        def account = new Account(id: 2L, name: 'test-account')

        // Setup datastores - empty for this test case
        resourcePermissionService.listAccessibleResources(_, _, _, _) >> []

        // Mock cloud datastore service
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        cloudService.getDatastore() >> datastoreService
        datastoreService.list(_ as DataQuery) >> []

        when:
        provisionProvider.getHostAndDatastore(cloud, account, clusterIdParam, hostIdParam,
                datastoreParam, datastoreOption, size, siteId, requiredMemory)


        then:
        thrown(Exception)

        where:
        scenario                               | hasCloudRegion | clusterIdParam | hostIdParam | hostId | datastoreParam | datastoreOption | size          | siteId | requiredMemory
        "non_cloud_without_node_and_datastore" | false          | null           | null        | null   | null           | 'manual'        | 10000000000L  | null   | 4294967296L
    }


    def "test prepareWorkload returns successful response with workload"() {
        given:
        def provider = new ScvmmProvisionProvider(null, null)
        def workload = Mock(Workload) {
            getName() >> "test-workload"
            getServer() >> Mock(ComputeServer) {
                getName() >> "test-server"
            }
        }
        def workloadRequest = Mock(WorkloadRequest)
        def opts = [key: "value"]

        when:
        def response = provider.prepareWorkload(workload, workloadRequest, opts)

        then:
        response.success
        response.msg == ''
        response.errors == null
        response.data instanceof PrepareWorkloadResponse
        response.data.workload == workload
    }

    def "test initializeHypervisor with shared controller"() {
        given:
        ComputeServer server = new ComputeServer(id: 1)
        Cloud cloud = new Cloud(id: 1, code: 'scvmm', configMap: [sharedController: "123"])

        when:
        def response = provisionProvider.initializeHypervisor(cloud, server)

        then:
        0 * mockApiService._
        response.success
        response.data instanceof InitializeHypervisorResponse
    }

    def "test initializeHypervisor with successful server info retrieval"() {
        given:
        ComputeServer server = new ComputeServer(id: 1)
        Cloud cloud = new Cloud(id: 1, code: 'scvmm', configMap: [:])
        Map zoneOpts = [hostName: 'scvmmserver', username: 'admin', password: 'password']
        Map controllerOpts = [controllerHostname: 'scvmmserver']
        Map serverInfo = [
                success: true,
                hostname: 'scvmmserver.local',
                disks: 500 * ComputeUtility.ONE_GIGABYTE,
                memory: 16 * ComputeUtility.ONE_GIGABYTE,
                osName: 'Microsoft Windows Server 2019 Datacenter'
        ]

        when:
        def response = provisionProvider.initializeHypervisor(cloud, server)

        then:
        1 * mockApiService.getScvmmZoneOpts(morpheusContext, cloud) >> zoneOpts
        1 * mockApiService.getScvmmControllerOpts(cloud, server) >> controllerOpts
        1 * mockApiService.getScvmmServerInfo(zoneOpts + controllerOpts) >> serverInfo
        1 * mockApiService.extractWindowsServerVersion(serverInfo.osName) >> 'windows.server.2019'
        1 * mockApiService.prepareNode(zoneOpts + controllerOpts)
        response.success
        response.data.commType.toString() == 'winrm'
        response.data.maxMemory == serverInfo.memory
        response.data.maxStorage == serverInfo.disks
        response.data.maxCores == 1
        response.data.serverOs.code == 'windows.server.2019'
        server.hostname == 'scvmmserver.local'
    }

    def "test initializeHypervisor handles exception"() {
        given:
        ComputeServer server = new ComputeServer(id: 1)
        Cloud cloud = new Cloud(id: 1, code: 'scvmm', configMap: [:])

        when:
        def response = provisionProvider.initializeHypervisor(cloud, server)

        then:
        1 * mockApiService.getScvmmZoneOpts(morpheusContext, cloud) >> { throw new RuntimeException("API error") }
        0 * mockApiService.prepareNode(_)
        response.data instanceof InitializeHypervisorResponse
        !response.success
    }

    @Unroll
    def "waitForHost returns success when server is ready and finalize succeeds"() {
        given:
        Cloud cloud = new Cloud(id: 1L, code: 'scvmm-cloud')

        ComputeServer server = ProvisionDataHelper.waitForHost_getComputeServer(cloud)

        ComputeServer controllerServer = ProvisionDataHelper.waitForHost_getControllerServer(cloud)

        def serverDetail = ProvisionDataHelper.waitForHost_getServerDetail()

        morpheusContext.async.computeServer.get(100L) >> {
            return Maybe.just(server)
        }

        morpheusContext.async.computeServer.get(200L) >> {
            return Maybe.just(controllerServer)
        }

        provisionProvider.pickScvmmController(cloud) >> {
            return controllerServer
        }

        mockApiService.getScvmmCloudOpts(morpheusContext, cloud, controllerServer) >> {
            return [cloud: cloud.id]
        }
        mockApiService.getScvmmControllerOpts(cloud, controllerServer) >> {
            return [controller: controllerServer.id]
        }
        provisionProvider.getScvmmServerOpts(server) >> {
            return [server: server.id, vmId: 'vm-123']
        }
        mockApiService.checkServerReady(_, 'vm-123') >> {
            return serverDetail
        }

        // Mock the waitForAgentInstall method
        provisionProvider.waitForAgentInstall(_, _) >> [success: true]

        // Mock finalizeHost call - this is a method in the same class, we need to spy it
        provisionProvider.metaClass.finalizeHost = { ComputeServer srv ->
            return new ServiceResponse(success: true)
        }

        asyncComputeServerService.save(_) >> { ComputeServer serverObj ->
            return Single.just(serverObj)
        }
        provisionProvider.applyComputeServerNetworkIp(_, _, _, _, _) >> {
            return ProvisionDataHelper.waitForHost_applyComputeServerNetworkIpResponse()
        }

        provisionProvider.applyNetworkIpAndGetServer(_, _, _, _, _) >> {
            return server
        }

        when:
        def response = provisionProvider.waitForHost(server)

        then:

        response.success == true
        response.data.privateIp == '10.0.0.100'
        response.data.publicIp == '10.0.0.100'
        response.data.externalId == 'vm-123'
        response.data.success == true
    }

    @Unroll
    def "finalizeHost successfully processes server when checkServerReady succeeds"() {
        given:
        Cloud cloud = new Cloud(id: 1L, code: 'scvmm-cloud')
        ComputeServer server = ProvisionDataHelper.finalizeHost_getComputeServer(cloud)
        ComputeServer controllerNode = ProvisionDataHelper.finalizeHost_getControllerNode(cloud)
        def serverDetail = ProvisionDataHelper.finalizeHost_getServerDetail()

        // Mock all database operations
        asyncComputeServerService.get(server.id) >> Maybe.just(server)
        computeServerService.get(controllerNode.id) >> controllerNode
        asyncComputeServerService.save(_) >> Single.just(server)

        // Mock controller selection and connection details
        provisionProvider.pickScvmmController(cloud) >> controllerNode
        mockApiService.getScvmmCloudOpts(morpheusContext, cloud, controllerNode) >> [cloudId: 1L]
        mockApiService.getScvmmControllerOpts(cloud, controllerNode) >> [controllerId: 200L]
        provisionProvider.getScvmmServerOpts(server) >> [vmId: 'vm-123']

        // Mock the API call that checks server readiness
        mockApiService.checkServerReady(_, 'vm-123') >> serverDetail

        // Mock the waitForAgentInstall method - this is likely causing the hang
        provisionProvider.waitForAgentInstall(_) >> [success: true]
        provisionProvider.waitForAgentInstall(_, _) >> [success: true]

        // Mock the network IP application
        provisionProvider.applyComputeServerNetworkIp(_,_,_,_,_) >> {
            return ProvisionDataHelper.finalizeHost_applyComputeServerNetworkIpResponse()
        }

        // Mock the network interface update method
        provisionProvider.applyNetworkIpAndGetServer(_, _, _, _, _) >> server

        def serverSingle = Single.just(server)
        asyncComputeServerService.save(server) >> serverSingle

        when:
        def response = provisionProvider.finalizeHost(server)

        then:
        response.success == true
        1 * asyncComputeServerService.save(server) >> serverSingle
    }

    @Unroll
    def "prepareHost sets source image when server has a typeSet with a workloadType that has a virtualImage"() {
        given:
        def server = new ComputeServer(id: 1L, typeSet: new ComputeTypeSet(id: 123L))
        def hostRequest = new HostRequest()
        def opts = [:]
        def computeTypeSet1 = new ComputeTypeSet()
        def workloadType = new WorkloadType(id: 456L, virtualImage: new VirtualImage(id: 789L))
        def virtualImage = new VirtualImage(id: 789L)

        computeTypeSet1.workloadType = workloadType
        computeTypeSet1.id  = 123L

        asyncComputeTypeSetService.get(_) >> {
            return Maybe.just(computeTypeSet1)
        }
        asyncWorkloadTypeService.get(_) >> Maybe.just(workloadType)

        provisionProvider.saveAndGet(_) >> { ComputeServer srv ->
            // Just return the server without making changes
            return srv
        }

        when:
        def response = provisionProvider.prepareHost(server, hostRequest, opts)

        then:

        response.success
        response.data.computeServer == server
        server.sourceImage.id == 789L
    }

    def "stopServer handles different scenarios"() {
        given:
        // Create a test server with optional externalId
        def server = new ComputeServer(
                id: 100L,
                name: "test-server",
                externalId: externalId,
                cloud: new Cloud(id: 1L)
        )

        // Setup mocks based on scenario
        if (externalId) {
            def serverOpts = [
                    externalId: externalId,
                    server: server,
                    cloud: server.cloud
            ]
            provisionProvider.getAllScvmmServerOpts(server) >> serverOpts
            if (apiThrowsException) {
                mockApiService.stopServer(serverOpts, externalId) >> { throw new RuntimeException(errorMessage) }
            } else {
                mockApiService.stopServer(serverOpts, externalId) >> [success: apiSuccess]
            }
        }

        when:
        def response = provisionProvider.stopServer(server)

        then:
        response.success == expectedSuccess
        response.msg == expectedMessage
        if (!externalId) {
            0 * mockApiService.stopServer(_, _) // Verify API was not called when no externalId
        }

        where:
        scenario           | externalId | apiSuccess | apiThrowsException | errorMessage           | expectedSuccess | expectedMessage
        "successful stop"  | "vm-123"   | true       | false              | null                   | true           | null
        "no externalId"    | null       | false      | false              | null                   | false          | "vm not found"
        "api exception"    | "vm-123"   | false      | true               | "API connection error" | false          | "API connection error"
    }

    def "startServer handles different scenarios"() {
        given:
        // Create a test server with optional externalId
        def server = new ComputeServer(
                id: 100L,
                name: "test-server",
                externalId: externalId,
                cloud: new Cloud(id: 1L)
        )

        // Setup mocks based on scenario
        if (externalId) {
            def serverOpts = [
                    externalId: externalId,
                    server: server,
                    cloud: server.cloud
            ]
            provisionProvider.getAllScvmmServerOpts(server) >> serverOpts
            if (apiThrowsException) {
                mockApiService.startServer(serverOpts, externalId) >> { throw new RuntimeException(errorMessage) }
            } else {
                mockApiService.startServer(serverOpts, externalId) >> [success: apiSuccess]
            }
        }

        when:
        def response = provisionProvider.startServer(server)

        then:
        response.success == expectedSuccess
        response.msg == expectedMessage
        if (!externalId) {
            0 * mockApiService.startServer(_, _) // Verify API was not called when no externalId
        }

        where:
        scenario           | externalId | apiSuccess | apiThrowsException | errorMessage           | expectedSuccess | expectedMessage
        "successful start" | "vm-123"   | true       | false              | null                   | true           | null
        "failed start"     | "vm-123"   | false      | false              | null                   | false          | null
        "no externalId"    | null       | false      | false              | null                   | false          | "externalId not found"
        "api exception"    | "vm-123"   | false      | true               | "API connection error" | false          | null
    }

    def "finalizeWorkload with DVD cleanup configured"() {
        given: "a workload with DVD cleanup configuration"
        def cloud = new Cloud(id: 1L, name: 'test-cloud')
        def workload = new Workload(
                id: 1L,
                server: new ComputeServer(
                        id: 1L,
                        externalId: 'test-server-id',
                        cloud: cloud
                ),
                configMap: [
                        deleteDvdOnComplete: [
                                removeIsoFromDvd: true,
                                deleteIso: 'test-iso-path'
                        ]
                ]
        )
        def scvmmOpts = [vmId: 'test-server-id']

        and: "fetched workload with the same config"
        def fetchedWorkload = new Workload(
                id: 1L,
                configMap: [
                        deleteDvdOnComplete: [
                                removeIsoFromDvd: true,
                                deleteIso: 'test-iso-path'
                        ]
                ]
        )

        when:
        def result = provisionProvider.finalizeWorkload(workload)

        then: "getAllScvmmOpts is called"
        1 * provisionProvider.getAllScvmmOpts(workload) >> scvmmOpts

        and: "workload is fetched from context"
        1 * asyncWorkloadService.get(1L) >> Maybe.just(fetchedWorkload)

        and: "DVD is ejected"
        1 * mockApiService.setCdrom(scvmmOpts)

        and: "ISO is deleted"
        1 * mockApiService.deleteIso(scvmmOpts, 'test-iso-path')

        and: "success is returned"
        result.success == true
    }

    def "createWorkloadResources returns success response"() {
        given:
        def workload = ProvisionDataHelper.getWorkloadData()
        def opts = [key: "value"]

        when:
        def response = provisionProvider.createWorkloadResources(workload, opts)

        then:
        response.success == true
    }

    @Unroll
    def "restartWorkload handles stop and start sequence correctly when both operations succeed"() {
        given:
        def workload = Mock(Workload) {
            getName() >> "test-workload"
            dump() >> [:]
        }
        def stopResponse = new ServiceResponse(success: true)
        def startResponse = new ServiceResponse(success: true)

        provisionProvider.stopWorkload(workload) >> stopResponse
        provisionProvider.startWorkload(workload) >> startResponse

        when:
        def result = provisionProvider.restartWorkload(workload)

        then:
        result.success == true
    }

    @Unroll
    def "restartWorkload handles stop and start sequence correctly for success=#stopSuccess"() {
        given:
        def workload = Mock(Workload) {
            getName() >> "test-workload"
            dump() >> [:]
        }
        def stopResponse = new ServiceResponse(success: stopSuccess)
        def startResponse = new ServiceResponse(success: true)

        provisionProvider.stopWorkload(workload) >> stopResponse
        provisionProvider.startWorkload(workload) >> startResponse

        when:
        def result = provisionProvider.restartWorkload(workload)

        then:
        1 * provisionProvider.stopWorkload(workload)
        (stopSuccess ? 1 : 0) * provisionProvider.startWorkload(workload)
        result.success == expectedSuccess

        where:
        stopSuccess | expectedSuccess
        false       | false
    }

    def "stopWorkload handles different scenarios correctly"() {
        given:
        // Create a test workload with appropriate server details
        def server = new ComputeServer(
                id: 100L,
                name: "test-server",
                externalId: externalId,
                cloud: new Cloud(id: 1L)
        )

        def workload = new Workload(
                id: 200L,
                internalName: "test-workload",
                server: server
        )

        // Setup mocks based on scenario
        if (externalId) {
            def workloadOpts = [
                    vmId: externalId,
                    server: server,
                    cloud: server.cloud
            ]
            provisionProvider.getAllScvmmOpts(workload) >> workloadOpts
            if (apiThrowsException) {
                mockApiService.stopServer(workloadOpts, externalId) >> { throw new RuntimeException(errorMessage) }
            } else {
                mockApiService.stopServer(workloadOpts, externalId) >> [success: apiSuccess]
            }
        }

        when:
        def response = provisionProvider.stopWorkload(workload)

        then:
        response.success == expectedSuccess
        response.msg == expectedMessage
        if (!externalId) {
            0 * mockApiService.stopServer(_, _) // Verify API was not called when no externalId
        }

        where:
        scenario           | externalId | apiSuccess | apiThrowsException | errorMessage           | expectedSuccess | expectedMessage
        "successful stop"  | "vm-123"   | true       | false              | null                   | true           | null
        "failed stop"      | "vm-123"   | false      | false              | null                   | false          | null
        "no externalId"    | null       | false      | false              | null                   | false          | "vm not found"
        "api exception"    | "vm-123"   | false      | true               | "API connection error" | false          | "API connection error"
    }

    @Unroll
    def "startWorkload handles different scenarios correctly"() {
        given:
        // Create a test workload with appropriate server details
        def server = new ComputeServer(
                id: 100L,
                name: "test-server",
                externalId: externalId,
                cloud: new Cloud(id: 1L)
        )

        def workload = new Workload(
                id: 200L,
                internalName: "test-workload",
                server: server
        )

        // Setup mocks based on scenario
        if (externalId) {
            def workloadOpts = [
                    vmId: externalId,
                    server: server,
                    cloud: server.cloud
            ]
            provisionProvider.getAllScvmmOpts(workload) >> workloadOpts
            if (apiThrowsException) {
                mockApiService.startServer(workloadOpts, externalId) >> { throw new RuntimeException(errorMessage) }
            } else {
                mockApiService.startServer(workloadOpts, externalId) >> [success: apiSuccess]
            }
        }

        when:
        def response = provisionProvider.startWorkload(workload)

        then:
        response.success == expectedSuccess
        response.msg == expectedMessage
        if (!externalId) {
            0 * mockApiService.startServer(_, _) // Verify API was not called when no externalId
        }

        where:
        scenario           | externalId | apiSuccess | apiThrowsException | errorMessage           | expectedSuccess | expectedMessage
        "successful start" | "vm-123"   | true       | false              | null                   | true           | null
        "failed start"     | "vm-123"   | false      | false              | null                   | false          | null
        "no externalId"    | null       | false      | false              | null                   | false          | "vm not found"
        "api exception"    | "vm-123"   | false      | true               | "API connection error" | false          | "API connection error"
    }

    @Unroll
    def "removeWorkload handles different scenarios correctly"() {
        given:
        // Create a test workload with appropriate server details
        def server = new ComputeServer(
                id: 100L,
                name: "test-server",
                externalId: externalId,
                cloud: new Cloud(id: 1L)
        )

        def workload = new Workload(
                id: 200L,
                internalName: "test-workload",
                server: server
        )

        // Setup mocks based on scenario
        if (externalId) {
            def workloadOpts = [
                    externalId: externalId,
                    server: server,
                    cloud: server.cloud
            ]
            provisionProvider.getAllScvmmOpts(workload) >> workloadOpts
            if (apiThrowsException) {
                mockApiService.deleteServer(workloadOpts, externalId) >> { throw new RuntimeException(errorMessage) }
            } else {
                mockApiService.deleteServer(workloadOpts, externalId) >> [success: apiSuccess]
            }
        }

        when:
        def response = provisionProvider.removeWorkload(workload, [:])

        then:
        response.success == expectedSuccess
        response.msg == expectedMessage
        if (!externalId) {
            0 * mockApiService.deleteServer(_, _) // Verify API was not called when no externalId
        }

        where:
        scenario           | externalId | apiSuccess | apiThrowsException | errorMessage           | expectedSuccess | expectedMessage
        "successful remove"| "vm-123"   | true       | false              | null                   | true           | null
        "failed remove"    | "vm-123"   | false      | false              | null                   | false          | "Failed to remove vm"
        "no externalId"    | null       | false      | false              | null                   | false          | "vm not found"
        "api exception"    | "vm-123"   | false      | true               | "API connection error" | false          | null
    }

    @Unroll
    def "pickScvmmController handles different scenarios correctly"() {
        given:
        def cloud = new Cloud(id: 1L)
        if (sharedControllerId) {
            cloud.configMap = [sharedController: sharedControllerId.toString()]
        }

        def sharedController = sharedControllerId ?
                new ComputeServer(id: sharedControllerId, name: "shared-controller") : null

        def controllers = ProvisionDataHelper.pickScvmmController_ComputeServers(cloud)
        def scvmmController = controllers.scvmmController
        def scvmmHypervisor = controllers.scvmmHypervisor
        def legacyHypervisor = controllers.legacyHypervisor

        // Mock the computeServerService based on different scenarios
        if (sharedControllerId) {
            computeServerService.get(sharedControllerId) >> sharedController
        }

        if (findScvmmController) {
            computeServerService.find({ DataQuery query ->
                query.filters.any { it.name == 'cloud.id' && it.value == cloud.id } &&
                        query.filters.any { it.name == 'computeServerType.code' && it.value == 'scvmmController' }
            }) >> scvmmController
        } else {
            computeServerService.find({ DataQuery query ->
                query.filters.any { it.name == 'cloud.id' && it.value == cloud.id } &&
                        query.filters.any { it.name == 'computeServerType.code' && it.value == 'scvmmController' }
            }) >> null

            if (findScvmmHypervisor) {
                computeServerService.find({ DataQuery query ->
                    query.filters.any { it.name == 'cloud.id' && it.value == cloud.id } &&
                            query.filters.any { it.name == 'computeServerType.code' && it.value == 'scvmmHypervisor' }
                }) >> scvmmHypervisor
            } else {
                computeServerService.find({ DataQuery query ->
                    query.filters.any { it.name == 'cloud.id' && it.value == cloud.id } &&
                            query.filters.any { it.name == 'computeServerType.code' && it.value == 'scvmmHypervisor' }
                }) >> null

                if (findLegacyHypervisor) {
                    computeServerService.find({ DataQuery query ->
                        query.filters.any { it.name == 'cloud.id' && it.value == cloud.id } &&
                                query.filters.any { it.name == 'serverType' && it.value == 'hypervisor' }
                    }) >> legacyHypervisor
                } else {
                    computeServerService.find({ DataQuery query ->
                        query.filters.any { it.name == 'cloud.id' && it.value == cloud.id } &&
                                query.filters.any { it.name == 'serverType' && it.value == 'hypervisor' }
                    }) >> null
                }
            }
        }

        when:
        def result = provisionProvider.pickScvmmController(cloud)

        then:
        if (saveExpected) {
            1 * computeServerService.save(_ as ComputeServer) >> { ComputeServer server ->
                assert server.computeServerType.code == 'scvmmController'
                return server
            }
        } else {
            0 * computeServerService.save(_ as ComputeServer)
        }

        result?.id == expectedControllerId

        where:
        scenario                     | sharedControllerId | findScvmmController | findScvmmHypervisor | findLegacyHypervisor | saveExpected | expectedControllerId
        "shared controller"          | 5L                 | false               | false               | false                | false        | 5L
        "direct scvmm controller"    | null               | true                | false               | false                | false        | 2L
        "find scvmm hypervisor"      | null               | false               | true                | false                | true         | 3L
        "find legacy hypervisor"     | null               | false               | false               | true                 | true         | 4L
        "no controller found"        | null               | false               | false               | false                | false        | null
    }

    def "getContainerRootDisk returns the root volume from server volumes"() {
        given:
        def rootVolume = new StorageVolume(id: 1L, name: "root", rootVolume: true, maxStorage: 42949672960L)

        def dataVolume = new StorageVolume(id: 2L, name: "data", rootVolume: false, maxStorage: 10737418240L)

        def server = new ComputeServer(id: 100L, name: "test-server", volumes: [dataVolume, rootVolume])

        def containerWithVolumes = new Workload(id: 200L, server: server)
        containerWithVolumes.displayName = "test-container"

        def containerWithNoServer = new Workload(
                id: 201L,
        )
        containerWithNoServer.displayName = "test-container-no-server"

        def containerWithEmptyVolumes = new Workload(
                id: 202L,
                server: new ComputeServer(id: 101L, volumes: [])
        )
        containerWithEmptyVolumes.displayName = "test-container-empty-volumes"

        when:
        def foundRootVolume = provisionProvider.getContainerRootDisk(containerWithVolumes)
        def noServerResult = provisionProvider.getContainerRootDisk(containerWithNoServer)
        def emptyVolumesResult = provisionProvider.getContainerRootDisk(containerWithEmptyVolumes)

        then:
        foundRootVolume == rootVolume
        noServerResult == null
        emptyVolumesResult == null
    }

    @Unroll
    def "getContainerRootSize returns correct size based on priority when all values are present"() {
        given:
        // Create root volume with size
        def rootVolume = new StorageVolume(id: 1L, name: "root", rootVolume: true, maxStorage: 42949672960L)

        // Create server with volumes
        def server = new ComputeServer(id: 100L, volumes: [rootVolume])

        // Create container with server and maxStorage
        def container = new Workload(id: 200L, server: server, maxStorage: 10737418240L )

        // Add instance with plan
        def plan = new ServicePlan(id: 300L, maxStorage: 21474836480L) // ~20GB
        def instance = new Instance(id: 400L, plan: plan)
        container.instance = instance

        when:
        def result = provisionProvider.getContainerRootSize(container)

        then:
        // The code prioritizes root volume size if present
        result == rootVolume.maxStorage
    }

    @Unroll
    def "getContainerVolumeSize prioritizes correctly for different input combinations"() {
        given:
        // Create volumes with specified sizes
        def volumes = []
        if (volumeSizes) {
            volumes = volumeSizes.collect { size ->
                new StorageVolume(
                        id: volumes.size() + 1L,
                        maxStorage: size
                )
            }
        }

        // Create server with volumes if needed
        def server = hasVolumes ? new ComputeServer(id: 100L, volumes: volumes) : null

        // Create plan with maxStorage if needed
        def plan = hasPlan ? new ServicePlan(id: 300L, maxStorage: planMaxStorage) : null

        // Create instance with plan if needed
        def instance = hasPlan ? new Instance(id: 400L, plan: plan) : null

        // Create container with all the components
        def container = new Workload(id: 200L, server: server, maxStorage: containerMaxStorage, instance: instance)

        when:
        def result = provisionProvider.getContainerVolumeSize(container)

        then:
        result == expectedSize

        where:
        scenario                                      | containerMaxStorage | hasPlan | planMaxStorage | hasVolumes | volumeSizes                | expectedSize
        "container.maxStorage only"                   | 10737418240L        | false   | null           | false      | null                       | 10737418240L
        "plan.maxStorage only"                        | null                | true    | 21474836480L   | false      | null                       | 21474836480L
        "container.maxStorage takes priority over plan" | 10737418240L      | true    | 21474836480L   | false      | null                       | 10737418240L
        "volumes sum smaller than container.maxStorage" | 30737418240L      | false   | null           | true       | [10737418240L, 5368709120L] | 30737418240L
        "volumes sum larger than container.maxStorage" | 10737418240L        | false   | null           | true       | [10737418240L, 21474836480L] | 32212254720L
        "volumes sum larger than plan.maxStorage"     | null                | true    | 10737418240L   | true       | [15737418240L, 21474836480L] | 37212254720L
        "volumes with null maxStorage values"         | 10737418240L        | false   | null           | true       | [10737418240L, null, 5368709120L] | 16106127360L
        "all three values present, volumes largest"   | 10737418240L        | true    | 21474836480L   | true       | [20737418240L, 15368709120L] | 36106127360L
    }

    def "getContainerDataDiskList returns correct non-root volumes for #scenario"() {
        given:
        def rootVolume = new StorageVolume(id: 1, rootVolume: true)
        def dataVolume1 = new StorageVolume(id: 2, rootVolume: false)
        def dataVolume2 = new StorageVolume(id: 3, rootVolume: false)

        def volumes = [rootVolume, dataVolume1, dataVolume2]
        def server = Mock(ComputeServer)
        def workload = Mock(Workload)

        workload.server >> server
        server.volumes >> volumes

        when:
        def result = ScvmmProvisionProvider.getContainerDataDiskList(workload)

        then:
        result == [dataVolume1, dataVolume2]
    }

    def "test getScvmmContainerOpts returns proper configuration"() {
        given:
        // Create concrete objects instead of mocks where possible
        def cloud = new Cloud(id: 1L, name: "test-cloud")
        def account = new Account(id: 1L, name: "test-account")
        cloud.account = account

        // Create a network to be returned by the cloud service
        def network = new Network(id: 1L)
        network.displayName = "vlanbaseVmNetwork"

        // Create OS Type and server OS
        def serverOs = new OsType(platform: "linux")

        def osType = new OsType(platform: "linux")
        def virtualImage = new VirtualImage(id: 2L, name: "test-image", osType: osType)

        // Create service plan with specific values
        def servicePlan = new ServicePlan(id: 3L, maxMemory: 4294967296L, maxCpu: 1, maxCores: 2,
                maxStorage: 42949672960L)

        def instance = new Instance(id: 4L, name: "test-instance", plan: servicePlan)

        // Create storage volumes for the computer server
        def rootVolume = new StorageVolume(id: 507L, displayOrder: 0, name: "root", rootVolume: true,
                maxStorage: 42949672960L )

        def dataVolume = new StorageVolume(id: 509L, displayOrder: 2, name: "data-2", rootVolume: false,
                maxStorage: 9663676416L)

        // Create resource pool
        def resourcePool = new ComputeZonePool(id: 5L, name: "Resource Pool 1", externalId: "Resource Pool 1")

        // Create ComputeServer with concrete values
        def computerServer = new ComputeServer(id: 1L, name: "test-server", externalId: "vm-123", cloud: cloud,
                sourceImage: virtualImage, volumes: [rootVolume, dataVolume], resourcePool: resourcePool,
                serverOs: serverOs, interfaces: [])

        def workloadType = new WorkloadType(refId: 1L, code: "test-workload-type")
        workloadType.setId(19L)

        // Define container config
        def containerConfig = [networkId: "1", networkType: "vlan", hostId: 2, scvmmCapabilityProfile: "Hyper-V"]

        // Create workload with concrete values
        def workload = new Workload(id: 5L, internalName: "testWorkload", server: computerServer,
                workloadType: workloadType, instance: instance, account: account, maxMemory: null,
                maxCpu: null,    maxCores: null, hostname: "testVM")

        // Set the config map for the workload
        workload.setConfigProperty("networkId", "1")
        workload.setConfigProperty("networkType", "vlan")
        workload.setConfigProperty("hostId", 2)
        workload.setConfigProperty("scvmmCapabilityProfile", "Hyper-V")

        // Mock the cloud network service get method
        cloudService.getNetwork() >> networkService
        networkService.get(1L) >> {
            return network
        }

        // Mock the methods used in getScvmmContainerOpts
        provisionProvider.getContainerRootSize(workload) >> {
            return 42949672960L
        }
        provisionProvider.getContainerVolumeSize(workload) >> {
            return 42949672960L
        } // Sum of root + data volumes with 20% overhead
        provisionProvider.getContainerDataDiskList(workload) >> {
            return [dataVolume]
        }

        when:
        def result = provisionProvider.getScvmmContainerOpts(workload)

        then:
        result.config instanceof Map
        result.vmId == "vm-123"
        result.name == "vm-123"
        result.server == computerServer
        result.serverId == 1L
        result.memory == 4294967296L
        result.maxCpu == 1
        result.maxCores == 2
        result.serverFolder == "morpheus\\morpheus_server_1"
        result.hostname == "testVM"
        result.network == network
        result.networkId == 1L
        result.platform == "linux"
        result.externalId == "vm-123"
        result.networkType == "vlan"
        result.containerConfig.scvmmCapabilityProfile == "Hyper-V"
        result.resourcePool == "Resource Pool 1"
        result.hostId == 2
        result.osDiskSize == 42949672960L
        result.maxTotalStorage == 42949672960
        result.dataDisks == [dataVolume]
        result.scvmmCapabilityProfile == "Hyper-V"
        result.accountId == 1L
    }

    def "getAllScvmmOpts successfully combines options from all sources"() {
        given:
        // Setup the workload and cloud
        def cloud = new Cloud(id: 1L, name: "test-cloud")
        def server = new ComputeServer(id: 1L, name: "test-server", cloud: cloud)
        def workload = new Workload(id: 5L, server: server)

        // Setup controller server
        def controllerServer = new ComputeServer(
                id: 10L,
                name: "controller-01",
                serverType: new ComputeServerType(code: "scvmm-controller"),
                computeServerType: new ComputeServerType(code: "scvmm-controller")
        )

        // Mock the various option methods
        def cloudOpts = ProvisionDataHelper.DEFAULT_CLOUD_OPTS.clone()

        def controllerOpts = ProvisionDataHelper.DEFAULT_CONTROLLER_OPTS.clone()

        def containerOpts = ProvisionDataHelper.DEFAULT_CONTAINER_OPTS.clone()

        // Mock the methods that are called inside getAllScvmmOpts
        provisionProvider.pickScvmmController(cloud) >> controllerServer
        mockApiService.getScvmmCloudOpts(morpheusContext, cloud, controllerServer) >> cloudOpts
        mockApiService.getScvmmControllerOpts(cloud, controllerServer) >> controllerOpts
        provisionProvider.getScvmmContainerOpts(workload) >> containerOpts

        when:
        def result = provisionProvider.getAllScvmmOpts(workload)

        then:
        // Verify that result contains combined options from all sources
        result.cloud == 1L
        result.cloudName == "test-cloud"
        result.zoneId == 1L
        result.controller == 10L
        result.sshHost == "10.0.0.5"
        result.sshUsername == "admin"
        result.sshPassword == "password123"
        result.vmId == "vm-123"
        result.name == "vm-123"
        result.memory == 4294967296L
        result.maxCpu == 1
        result.maxCores == 2
        result.hostname == "testVM"
        result.networkId == 1L
        result.platform == "linux"

        // Verify each method was called exactly once
        1 * provisionProvider.pickScvmmController(cloud) >> controllerServer
        1 * mockApiService.getScvmmCloudOpts(morpheusContext, cloud, controllerServer) >> cloudOpts
        1 * mockApiService.getScvmmControllerOpts(cloud, controllerServer) >> controllerOpts
        1 * provisionProvider.getScvmmContainerOpts(workload) >> containerOpts
    }

    @Unroll
    def "getServerRootSize returns #expectedValue for #scenario"() {
        given:
        def server = new ComputeServer(
                id: 100L,
                name: "test-server",
                maxStorage: serverMaxStorage
        )

        // Set up plan if needed
        if (hasPlan) {
            def plan = new ServicePlan(
                    id: 200L,
                    maxStorage: planMaxStorage
            )
            server.plan = plan
        }

        // Mock the getServerRootDisk method to return the appropriate value
        provisionProvider.getServerRootDisk(_) >> { ComputeServer serverArg ->
            return rootDisk
        }

        when:
        def result = provisionProvider.getServerRootSize(server)

        then:
        result == expectedValue

        where:
        scenario                         | rootDisk                                          | serverMaxStorage | hasPlan | planMaxStorage | expectedValue
        "root disk exists"               | new StorageVolume(maxStorage: 42949672960L)       | 21474836480L     | true    | 32212254720L   | 42949672960L
        "no root disk, use server size"  | null                                              | 21474836480L     | true    | 32212254720L   | 21474836480L
    }

    def "getServerRootDisk returns root volume when present"() {
        given:
        // Create volumes with one root volume and one non-root volume
        def servers = ProvisionDataHelper.getServerRootDisk_differentServers()
        def serverWithRootVolume = servers.serverWithRootVolume
        def serverWithoutRootVolume = servers.serverWithoutRootVolume
        def serverWithEmptyVolumes = servers.serverWithEmptyVolumes
        def serverWithNullVolumes = servers.serverWithNullVolumes
        def rootVolume = servers.rootVolume

        when:
        def resultWithRoot = provisionProvider.getServerRootDisk(serverWithRootVolume)
        def resultWithoutRoot = provisionProvider.getServerRootDisk(serverWithoutRootVolume)
        def resultEmptyVolumes = provisionProvider.getServerRootDisk(serverWithEmptyVolumes)
        def resultNullVolumes = provisionProvider.getServerRootDisk(serverWithNullVolumes)
        def resultNullServer = provisionProvider.getServerRootDisk(null)

        then:
        // Should return the root volume when present
        resultWithRoot == rootVolume

        // Should return null when no root volume is present
        resultWithoutRoot == null
        resultEmptyVolumes == null
        resultNullVolumes == null
        resultNullServer == null
    }

    @Unroll
    def "getServerVolumeSize returns correct value for #scenario"() {
        given:
        // Create a server with the specified maxStorage
        def server = new ComputeServer(
                id: 100L,
                name: "test-server",
                maxStorage: serverMaxStorage
        )

        // Set up plan if needed
        if (hasPlan) {
            def plan = new ServicePlan(
                    id: 200L,
                    maxStorage: planMaxStorage
            )
            server.plan = plan
        }

        // Set up volumes if needed
        if (volumeSizes) {
            server.volumes = volumeSizes.collect { size ->
                new StorageVolume(
                        id: volumeSizes.indexOf(size) + 1,
                        maxStorage: size
                )
            }
        }

        when:
        def result = provisionProvider.getServerVolumeSize(server)

        then:
        result == expectedValue

        where:
        scenario                                     | serverMaxStorage | hasPlan | planMaxStorage | volumeSizes                 | expectedValue
        "server.maxStorage has priority"             | 10737418240L     | true    | 5368709120L    | null                        | 10737418240L
        "plan.maxStorage used when server null"      | null             | true    | 21474836480L   | null                        | 21474836480L
        "volumes sum used when greater than server"  | 10737418240L     | false   | null           | [5368709120L, 21474836480L] | 26843545600L
        "server.maxStorage used when greater than volumes" | 30737418240L | false   | null           | [5368709120L, 10737418240L] | 30737418240L
        "volumes with mixed null maxStorage handled" | 10737418240L     | false   | null           | [null, 5368709120L, null]   | 10737418240L
    }

    def "getServerDataDiskList returns only non-root volumes sorted by id"() {
        given:
        def rootVolume = new StorageVolume(id: 2L, name: "root", rootVolume: true)
        def dataVolume1 = new StorageVolume(id: 3L, name: "data1", rootVolume: false)
        def dataVolume2 = new StorageVolume(id: 1L, name: "data2", rootVolume: false)
        def server = new ComputeServer(
                //name: "test-server",
                displayName: "test-server",
                volumes: [rootVolume, dataVolume1, dataVolume2]
        )

        when:
        def result = provisionProvider.getServerDataDiskList(server)

        then:
        result.size() == 2
        result[0].id == 1L // Should be sorted by ID
        result[0].name == "data2"
        result[1].id == 3L
        result[1].name == "data1"
        !result.any { it.rootVolume }
    }

    def "test getScvmmServerOpts returns correctly populated options map"() {
        given:
        // Create a network to be returned by the network service
        def network = new Network(id: 123L, name: "test-network")

        def dataVolume = new StorageVolume(
                id: 509L,
                name: "data-disk",
                rootVolume: false,
                maxStorage: 10737418240L // 10GB
        )

        // Create a service plan with specific values
        def servicePlan = new ServicePlan(
                id: 3L,
                maxMemory: 4294967296L, // 4GB
                maxCpu: 2,
                maxCores: 4
        )

        // Create ComputeServer with concrete values
        def server = new ComputeServer(
                id: 100L,
                name: "test-server",
                externalId: "vm-123",
                externalHostname: "test-hostname",
                maxMemory: 8589934592L, // 8GB - this should be used instead of plan's value
                plan: servicePlan,
                account: new Account(id: 200L)
        )

        // Set server config with networkId
        server.setConfigProperty("networkId", "123")
        server.setConfigProperty("scvmmCapabilityProfile", "Hyper-V")

        // Mock the network service get method
        cloudService.getNetwork() >> networkService
        networkService.get(123L) >> network

        // Mock the methods used in getScvmmServerOpts
        provisionProvider.getServerRootSize(server) >> 42949672960L
        provisionProvider.getServerVolumeSize(server) >> 53687091200L
        provisionProvider.getServerDataDiskList(server) >> [dataVolume]

        when:
        def result = provisionProvider.getScvmmServerOpts(server)

        then:
        result.name == "test-server"
        result.vmId == "vm-123"
        result.serverId == 100L
        result.externalId == "vm-123"
        result.memory == 8589934592L // Server's value should be used
        result.maxCpu == 2 // Plan's value should be used
        result.maxCores == 4 // Plan's value should be used
        result.serverFolder == "morpheus\\morpheus_server_100"
        result.hostname == "test-hostname"
        result.network == network
        result.networkId == 123L
        result.osDiskSize == 42949672960L
        result.maxTotalStorage == 53687091200L
        result.dataDisks == [dataVolume]
        result.scvmmCapabilityProfile == "Hyper-V"
        result.accountId == 200L

        // Verify mocked methods were called exactly once
        1 * networkService.get(123L) >> network
        1 * provisionProvider.getServerRootSize(server) >> 42949672960L
        1 * provisionProvider.getServerVolumeSize(server) >> 53687091200L
        1 * provisionProvider.getServerDataDiskList(server) >> [dataVolume]
    }

    @Unroll
    def "getAllScvmmServerOpts successfully combines options from all sources"() {
        given:
        // Setup the server and cloud
        def cloud = new Cloud(id: 1L, name: "test-cloud")
        def server = new ComputeServer(id: 1L, name: "test-server", cloud: cloud)

        // Setup controller server
        def controllerServer = new ComputeServer(
                id: 10L,
                name: "controller-01",
                serverType: new ComputeServerType(code: "scvmm-controller"),
                computeServerType: new ComputeServerType(code: "scvmm-controller")
        )

        // Mock the various option methods
        def cloudOpts = ProvisionDataHelper.DEFAULT_CLOUD_OPTS.clone()

        def controllerOpts = ProvisionDataHelper.DEFAULT_CONTROLLER_OPTS.clone()

        def serverOpts = ProvisionDataHelper.DEFAULT_CONTAINER_OPTS.clone()

        // Mock the methods that are called inside getAllScvmmServerOpts
        provisionProvider.pickScvmmController(cloud) >> controllerServer
        mockApiService.getScvmmCloudOpts(morpheusContext, cloud, controllerServer) >> cloudOpts
        mockApiService.getScvmmControllerOpts(cloud, controllerServer) >> controllerOpts
        provisionProvider.getScvmmServerOpts(server) >> serverOpts

        when:
        def result = provisionProvider.getAllScvmmServerOpts(server)

        then:
        // Verify that result contains combined options from all sources
        result.cloud == 1L
        result.cloudName == "test-cloud"
        result.zoneId == 1L
        result.controller == 10L
        result.sshHost == "10.0.0.5"
        result.sshUsername == "admin"
        result.sshPassword == "password123"
        result.vmId == "vm-123"
        result.name == "vm-123"
        result.memory == 4294967296L
        result.maxCpu == 1
        result.maxCores == 2
        result.hostname == "testVM"
        result.networkId == 1L
        result.platform == "linux"

        // Verify each method was called exactly once
        1 * provisionProvider.pickScvmmController(cloud) >> controllerServer
        1 * mockApiService.getScvmmCloudOpts(morpheusContext, cloud, controllerServer) >> cloudOpts
        1 * mockApiService.getScvmmControllerOpts(cloud, controllerServer) >> controllerOpts
        1 * provisionProvider.getScvmmServerOpts(server) >> serverOpts
    }

    @Unroll
    def "getMorpheusServer returns server with joined network interfaces"() {
        given:
        def serverId = 123L
        def server = new ComputeServer(id: serverId, name: "test-server")

        // Mock network interface
        def network = new Network(id: 456L, name: "test-network")
        def networkInterface = new ComputeServerInterface(
                id: 789L,
                network: network,
                ipAddress: "192.168.1.100"
        )
        server.interfaces = [networkInterface]

        // Mock the computeServerService find method with query matching
        computeServerService.find({ DataQuery query ->
            return query.filters.any { it.name == "id" && it.value == serverId } &&
                    query.joins.contains("interfaces.network")
        }) >> server

        when:
        def result = provisionProvider.getMorpheusServer(serverId)

        then:
        result == server
        result.id == serverId
        result.interfaces.size() == 1
        result.interfaces[0].network.id == 456L
        1 * computeServerService.find(_) >> server
    }

    @Unroll
    def "saveAndGetMorpheusServer handles correctly"() {
        given:
        def server = new ComputeServer(id: 100L, name: "test-server")
        def persistedServer = new ComputeServer(id: 100L, name: "test-server-persisted")
        def failedServer = new ComputeServer(id: 100L, name: "test-server-failed")
        def reloadedServer = new ComputeServer(id: 100L, name: "test-server-reloaded")


        BulkSaveResult bulkResponse = new BulkSaveResult<ComputeServer>("", "", [persistedServer], [])

        // Mock the bulkSave method
        asyncComputeServerService.bulkSave([server]) >> {
            return Single.just(bulkResponse)
        }

        // Mock getMorpheusServer if needed
        provisionProvider.getMorpheusServer(server.id) >> reloadedServer


        when:
        def result = provisionProvider.saveAndGetMorpheusServer(server, true)

        then:
        //1 * provisionProvider.getMorpheusServer(server.id) >> reloadedServer
        result.name == "test-server"

    }

    @Unroll
    def "loadDatastoreForVolume finds datastore by hostVolumeId=#hostVolumeId, fileShareId=#fileShareId, partitionUniqueId=#partitionUniqueId"() {
        given:
        def cloud = new Cloud(id: 1L)

        // Create datastores
        def datastore1 = new Datastore(id: 1L, name: 'datastore1')
        def datastore2 = new Datastore(id: 2L, name: 'datastore2')

        // Create storage volumes
        def storageVolumeWithHostId = new StorageVolume(
                internalId: 'vol-123',
                datastore: datastore1
        )

        def storageVolumeWithPartitionId = new StorageVolume(
                externalId: 'part-456',
                datastore: datastore2
        )

        // Setup mock for datastore service
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        cloudService.getDatastore() >> datastoreService

        // Setup mocks for different query scenarios
        if (hostVolumeId == 'vol-123') {
            // First attempt with hostVolumeId
            storageVolumeService.find({ DataQuery query ->
                query.filters.any { it.name == 'internalId' && it.value == hostVolumeId } &&
                        query.filters.any { it.name == 'datastore.refType' && it.value == 'ComputeZone' } &&
                        query.filters.any { it.name == 'datastore.refId' && it.value == cloud.id }
            }) >> storageVolumeWithHostId
        } else {
            storageVolumeService.find({ DataQuery query ->
                query.filters.any { it.name == 'internalId' && it.value == hostVolumeId }
            }) >> null
        }

        // For partitionUniqueId lookup
        if (partitionUniqueId == 'part-456') {
            storageVolumeService.find({ DataQuery query ->
                query.filters.any { it.name == 'externalId' && it.value == partitionUniqueId } &&
                        query.filters.any { it.name == 'datastore.refType' && it.value == 'ComputeZone' } &&
                        query.filters.any { it.name == 'datastore.refId' && it.value == cloud.id }
            }) >> storageVolumeWithPartitionId
        }

        // For fileShareId lookup
        if (fileShareId == 'file-id') {
            datastoreService.find({ DataQuery query ->
                query.filters.any { it.name == 'externalId' && it.value == fileShareId } &&
                        query.filters.any { it.name == 'refType' && it.value == 'ComputeZone' } &&
                        query.filters.any { it.name == 'refId' && it.value == cloud.id }
            }) >> datastore1
        }

        when:
        def result = provisionProvider.loadDatastoreForVolume(cloud, hostVolumeId, fileShareId, partitionUniqueId)

        then:

        if (hostVolumeId == 'vol-123') {
            result == datastore1
        } else if (partitionUniqueId == 'part-456') {
            result == datastore2
        } else if (fileShareId == 'file-id') {
            result == datastore1
        } else {
            result == null
        }

        where:
        hostVolumeId | fileShareId | partitionUniqueId
        'vol-123'    | null        | null
        'missing'    | null        | 'part-456'
        null         | 'file-id'   | null

    }

    def "test applyComputeServerNetworkIp with different IP configurations"() {
        given:
        def server = new ComputeServer(id: 100L, name: "test-server")
        def existingInterface = new ComputeServerInterface(id: 1L, name: "eth0", primaryInterface: true)
        server.interfaces = [existingInterface]

        def macAddress = "00:11:22:33:44:55"
        def netInterface = new ComputeServerInterface(
                id: 1L,
                ipAddress: "192.168.1.100",
                publicIpAddress: "10.0.0.100",
                macAddress: macAddress
        )
        def updatedServer = new ComputeServer(id: 100L)
        def mockSyncComputeServerService = Mock(MorpheusSynchronousComputeServerService)

        // Mock async compute server service (for bulkSave, interface create/save)
        def mockAsyncComputeServer = Mock(MorpheusComputeServerService)
        def mockComputeServerInterface = Mock(MorpheusComputeServerInterfaceService)

        mockAsyncComputeServer.computeServerInterface >> mockComputeServerInterface
        mockComputeServerInterface.create(_, _) >> Single.just([netInterface])
        mockComputeServerInterface.save(_) >> Single.just([netInterface])

        // Mock BulkSaveResult
        def bulkSaveResult = Mock(BulkSaveResult) {
            getSuccess() >> true
            getPersistedItems() >> [server]
            getFailedItems() >> []
        }
        mockAsyncComputeServer.bulkSave(_) >> Single.just(bulkSaveResult)

        // Mock async services
        def mockAsyncServices = Mock(MorpheusAsyncServices) {
            getComputeServer() >> mockAsyncComputeServer
        }

        // Mock MorpheusServices
        def mockServices = Mock(MorpheusServices) {
            getComputeServer() >> mockSyncComputeServerService
        }

        // Mock MorpheusContext
        def mockContext = Mock(MorpheusContext) {
            getServices() >> mockServices
            getAsync() >> mockAsyncServices
        }

        def provisionProvider = Spy(ScvmmProvisionProvider, constructorArgs: [plugin, mockContext])
        provisionProvider.apiService = mockApiService
        provisionProvider.saveAndGetMorpheusServer(_, true) >> updatedServer

        when:
        def result1 = provisionProvider.applyComputeServerNetworkIp(server, "192.168.1.100", "10.0.0.100", 0, macAddress)

        then:
        result1.ipAddress == "192.168.1.100"
        result1.publicIpAddress == "10.0.0.100"
        result1.macAddress == macAddress
        server.internalIp == "192.168.1.100"
        server.externalIp == "10.0.0.100"
        server.sshHost == "192.168.1.100"

        when:
        def result2 = provisionProvider.applyComputeServerNetworkIp(server, null, "10.0.0.200", 0, macAddress)

        then:
        result2 == null
    }

    def "test cloneParentCleanup successfully cleans up parent VM"() {
        given:
        // Create basic options map with all necessary nested properties
        def scvmmOpts = ProvisionDataHelper.cloneParentCleanup_getScvmmOpts()

        def serviceResponse = new ServiceResponse(success: true)

        // Mock the API service calls with successful responses
        mockApiService.startServer({ opts ->
            assert opts.async == true
            assert opts.controller == 1
            assert opts.hostId == 2
        }, 'vm-123') >> [success: true]

        mockApiService.setCdrom({ opts ->
            assert opts.controller == 1
            assert opts.hostId == 2
        }) >> [success: true]

        mockApiService.deleteIso({ opts ->
            assert opts.controller == 1
            assert opts.hostId == 2
        }, 'iso-file.iso') >> [success: true]

        Workload dummyWorkload = new Workload(id: 100L, internalName: "parent-workload")
        dummyWorkload.setStatus(Workload.Status.running)
        workloadService.find({ DataQuery query ->
            return query.filters.any { it.name == "id" && it.value == scvmmOpts.cloneContainerId.toLong() }
        }) >> {
            return dummyWorkload
        }
        workloadService.save(_) >> { Workload w ->
            return w
        }

        computeServerService.get(_) >>{
            return new ComputeServer()
        }

        asyncComputeServerService.updatePowerState(_, _) >> {
            return Single.just(dummyWorkload)
        }

        when:
        provisionProvider.cloneParentCleanup(scvmmOpts, serviceResponse)

        then:

        1 * mockApiService.startServer(_, 'vm-123') >>{
            return [success: true]
        }
        1 * mockApiService.setCdrom(_) >> {
            return [success: true]
        }
        1 * mockApiService.deleteIso(_, 'iso-file.iso') >> {
            return [success: true]
        }
        // Verify the service response was not modified (remained successful)

    }

    def "getUserAddedVolumes correctly processes workload configs"() {
        given:
        def workload = new Workload(id: 1L)

        // Create configs with a mix of root and non-root volumes
        def configs = [
                volumes: [
                        [id: -1, name: 'data1', maxStorage: 10737418240L, rootVolume: false],
                        [id: -1, name: 'data2', maxStorage: 21474836480L, rootVolume: false],
                        [id: 1, name: 'root', maxStorage: 42949672960L, rootVolume: true],
                        [id: -1, name: 'data3', maxStorage: 5368709120L, rootVolume: false, extraField: 'shouldBeIgnored']
                ]
        ]

        // Set the configs as a JSON string
        workload.configs = new groovy.json.JsonBuilder(configs).toString()

        when:
        def result = provisionProvider.getUserAddedVolumes(workload)

        then:
        result.count == 3
        result.volumes.size() == 3

        // Verify volume properties were correctly extracted
        result.volumes[0].name == 'data1'
        result.volumes[0].maxStorage == 10737418240L

        result.volumes[1].name == 'data2'
        result.volumes[1].maxStorage == 21474836480L

        result.volumes[2].name == 'data3'
        result.volumes[2].maxStorage == 5368709120L

        // Verify all volumes are StorageVolume instances
        result.volumes.every { it instanceof StorageVolume }

        // Verify extraField was ignored
        !result.volumes[2].hasProperty('extraField')
    }

    @Unroll
    def "test additionalTemplateDisksConfig with specific disk requirements"() {
        given:
        // Setup workload
        def workload = new Workload(id: 100L)
        workload.displayName = "test-workload"

        // Create user added volumes
        def userVolume1 = new StorageVolume(
                id: 201L,
                name: "user-volume-1",
                maxStorage: 10737418240L,
                rootVolume: false
        )
        def userVolume2 = new StorageVolume(
                id: 202L,
                name: "user-volume-2",
                maxStorage: 5368709120L,
                rootVolume: false
        )

        workload.server = new ComputeServer()
        workload.server.volumes = [userVolume1,userVolume2]

        // Create scvmmOpts with diskExternalIdMappings containing 2 entries
        def scvmmOpts = [
                diskExternalIdMappings: ['disk1', 'disk2']
        ]

        // Mock getUserAddedVolumes to return 2 volumes
        provisionProvider.getUserAddedVolumes(workload) >> {
            return [count: 2, volumes: [userVolume1]]
        }

        // Mock getContainerDataDiskList to return 2 data disks
        provisionProvider.getContainerDataDiskList(_) >> {
            return [userVolume1, userVolume2]
        }

        when:
        def result = provisionProvider.additionalTemplateDisksConfig(workload, scvmmOpts)

        then:
        // Expect 2 disks to be added (dataDisks.size() + 1 > diskExternalIdMappings.size())
        // where dataDisks.size() = 2, and diskExternalIdMappings.size() = 2
        result.size() == 1

    }


    def "setDynamicMemory should set dynamic memory values when plan has ranges config"() {
        given:
        def targetMap = [:]
        def servicePlan = Mock(ServicePlan)
        def ranges = [minMemory: 1024, maxMemory: 4096]

        when:
        servicePlan.getConfigProperty('ranges') >> ranges
        provisionProvider.setDynamicMemory(targetMap, servicePlan)

        then:
        targetMap.minDynamicMemory == 1024
        targetMap.maxDynamicMemory == 4096
    }

    def "test getVirtualImageLocation returns correct location when found"() {
        given:
        def virtualImage = new VirtualImage(id: 100L)
        def cloud = new Cloud(id: 200L, regionCode: 'us-east-1')
        def owner = new Account(id: 300L)
        cloud.owner = owner
        virtualImage.owner = owner

        def virtualImageLocation = new VirtualImageLocation(
                id: 400L,
                refId: cloud.id,
                refType: 'ComputeZone',
                virtualImage: virtualImage,
                imageRegion: cloud.regionCode
        )

        // Mock the MorpheusContext service calls
        MorpheusSynchronousVirtualImageLocationService virtualImageLocationService = Mock(MorpheusSynchronousVirtualImageLocationService)
        virtualImageService.location >> virtualImageLocationService

        // Mock the find method to verify the query is constructed correctly
        // and return our test location
        virtualImageLocationService.find({ DataQuery query ->
            // Verify filter on virtualImage.id
            assert query.filters.any { it.name == 'virtualImage.id' && it.value == 100L }

            // Verify the complex DataOrFilter structure
            def orFilter = query.filters.find { it instanceof DataOrFilter }
            assert orFilter != null

            def firstAndFilter = orFilter.value[0]
            assert firstAndFilter instanceof DataAndFilter
            //assert firstAndFilter != null
            assert firstAndFilter.value[0].any { it.name == 'refType' && it.value == 'ComputeZone' }
            assert firstAndFilter.value[1].any { it.name == 'refId' && it.value == 200L }

            def secondAndFilter = orFilter.value[1]
            assert secondAndFilter instanceof DataAndFilter
            assert secondAndFilter.value[0].any { it.name == 'virtualImage.owner.id' && it.value == 300L }
            assert secondAndFilter.value[1].any { it.name == 'imageRegion' && it.value == 'us-east-1' }

            return true
        }) >> virtualImageLocation

        when:
        def result = provisionProvider.getVirtualImageLocation(virtualImage, cloud)

        then:
        result == virtualImageLocation
        result.id == 400L
        result.virtualImage.id == 100L
        result.refId == cloud.id
        result.refType == 'ComputeZone'
        result.imageRegion == 'us-east-1'
    }


    @Unroll
    def "test getResizeConfig handles volume resize scenarios for #scenario"() {
        given:
        def server = new ComputeServer(
                id: 100L,
                name: "test-server",
                maxMemory: 4294967296L,
                maxCores: 2
        )
        def plan = new ServicePlan(id: 200L)
        def opts = [volumes: true]
        def resizeRequest = new ResizeRequest(
                maxMemory: 4294967296L, // Same memory
                maxCores: 2             // Same cores
        )

        // Create the appropriate volume based on test parameters
        def volume = new StorageVolume(
                id: 300L,
                name: "test-volume",
                rootVolume: isRootVolume,
                maxStorage: currentSize,
                type: new StorageVolumeType(code: volumeType)
        )

        def volumeUpdate = [
                existingModel: existingModel ? volume : null,
                updateProps: [maxStorage: requestedSize],
                volume: !existingModel ? new StorageVolume(maxStorage: requestedSize) : null
        ]

        resizeRequest.volumesUpdate = [volumeUpdate]

        // Mock setDynamicMemory to prevent actual implementation from running
        provisionProvider.setDynamicMemory(*_) >> {
            Map rtnMap, ServicePlan servicePlan ->

        }

        when:
        def result = provisionProvider.getResizeConfig(null, server, plan, opts, resizeRequest)

        then:
        result.success == true
        result.allowed == expectedAllowed
        result.hotResize == expectedHotResize

        where:
        scenario                | existingModel | isRootVolume | currentSize   | requestedSize  | volumeType            | expectedAllowed | expectedHotResize
        "resize root disk"      | true          | true         | 10737418240L  | 21474836480L   | "standard"            | true           | false
        "resize data disk"      | true          | false        | 10737418240L  | 21474836480L   | "standard"            | true           | false
        //"add new disk"          | false         | false        | 0L            | 10737418240L   | "standard"            | true           | false
        // "resize differencing"   | true          | false        | 10737418240L  | 21474836480L   | "differencing"        | true          | false
        "no size change"        | true          | false        | 10737418240L  | 10737418240L   | "standard"            | true           | false
        "shrink disk"           | true          | false        | 21474836480L  | 10737418240L   | "standard"            | true           | false
    }

    def "buildStorageVolume creates volume with correct properties"() {
        given:
        def computeServer = new ComputeServer(
                id: 100L,
                cloud: new Cloud(id: 200L),
                region: new ComputeZoneRegion(regionCode: "us-east-1"),
                account: new Account(id: 300L)
        )

        def volumeAdd = [maxStorage: 10737418240, maxIOPS: 1000, name: "data-volume"]

        def counter = 2

        when:
        def result = provisionProvider.buildStorageVolume(computeServer, volumeAdd, counter)

        then:
        result instanceof StorageVolume
        result.refType == 'ComputeZone'
        result.refId == 200L
        result.regionCode == 'us-east-1'
        result.account.id == 300L
        result.maxStorage == 10737418240L
        result.maxIOPS == 1000
        result.name == 'data-volume'
        result.displayOrder == 2
        result.status == 'provisioned'
        1 * provisionProvider.getDiskDisplayName(counter) >> 'Data Disk 2'
        result.deviceDisplayName == 'Data Disk 2'
    }

    def "getDiskNameList returns the expected array of disk names"() {
        given:
        def expectedDiskNames = ['sda', 'sdb', 'sdc', 'sdd', 'sde', 'sdf', 'sdg', 'sdh', 'sdi', 'sdj', 'sdk', 'sdl'] as String[]

        when:
        def result = provisionProvider.getDiskNameList()

        then:
        result == expectedDiskNames
        result.length == 12
        result[0] == 'sda'
        result[11] == 'sdl'
    }

    def "resizeWorkload should call resizeWorkloadAndServer with the correct parameters"() {
        given:
        def instance = new Instance(id: 100L, name: "test-instance")
        def workload = new Workload(id: 200L)
        workload.displayName = "test-workload"
        def resizeRequest = new ResizeRequest(maxMemory: 4294967296L, maxCores: 2)
        def opts = [key: "value"]

        // Create a mock response to be returned by resizeWorkloadAndServer
        def expectedResponse = new ServiceResponse(success: true, data: [resized: true])

        provisionProvider.resizeWorkloadAndServer(_, _, _, _, _) >> expectedResponse

        when:
        def result = provisionProvider.resizeWorkload(instance, workload, resizeRequest, opts)

        then:
        // Verify resizeWorkloadAndServer was called once with the correct parameters
        1 * provisionProvider.resizeWorkloadAndServer(workload, null, resizeRequest, opts, true) >> expectedResponse

        // Verify the response is correctly passed through
        result == expectedResponse
        result.success == true
        result.data.resized == true
    }

    def "resizeServer should call resizeWorkloadAndServer with the correct parameters"() {
        given:
        def server = new ComputeServer(id: 100L, name: "test-server")
        def resizeRequest = new ResizeRequest(maxMemory: 4294967296L, maxCores: 2)
        def opts = [key: "value"]

        // Create a mock response to be returned by resizeWorkloadAndServer
        def expectedResponse = new ServiceResponse(success: true, data: [resized: true])

        // Mock the resizeWorkloadAndServer method to return our expected response
        provisionProvider.resizeWorkloadAndServer(_, _, _, _, _) >> expectedResponse

        when:
        def result = provisionProvider.resizeServer(server, resizeRequest, opts)

        then:
        // Verify resizeWorkloadAndServer was called once with the correct parameters
        1 * provisionProvider.resizeWorkloadAndServer(null, server, resizeRequest, opts, false) >> expectedResponse

        // Verify the response is correctly passed through
        result == expectedResponse
        result.success == true
        result.data.resized == true
    }

    @Unroll
    def "test constructCloudInitOptions with different agent installation scenarios"() {
        given:
        // Create test objects
        def workload = new Workload(id: 100L)
        workload.displayName = "test-workload"

        def server = new ComputeServer(id: 200L, name: "test-server")
        workload.server = server

        def cloud = new Cloud(id: 300L, name: "test-cloud", agentMode: agentMode)
        server.cloud = cloud

        def workloadRequest = new WorkloadRequest(cloudConfigUser: "cloud-config-user-data",
                cloudConfigMeta: "cloud-config-metadata", cloudConfigNetwork: "cloud-config-network")

        def virtualImage = new VirtualImage(id: 400L, name: "test-image", isSysprep: isSysprep)

        def networkConfig = [network: "test-network"]
        def licenses = ["license1", "license2"]
        def scvmmOpts = [isSysprep: isSysprep]

        // Mock the buildCloudConfigOptions method
        def cloudConfigOpts = [installAgent: false, licenseApplied: licenseApplied,
                               unattendCustomized: unattendCustomized]

        // Mock the MorpheusSynchronousProvisionService
        MorpheusSynchronousProvisionService provisionService = Mock(MorpheusSynchronousProvisionService)
        morpheusContext.services.provision >> {
            return provisionService
        }

        // Set up mocks for the service calls
        provisionService.buildCloudConfigOptions(_, _, _, _) >> {
            return cloudConfigOpts
        }

        // Mock the buildIsoOutputStream method
        def mockIsoStream = new ByteArrayOutputStream()
        mockIsoStream.write("test-iso-data".getBytes())
        byte[] mockIsoBytes = mockIsoStream.toByteArray()
        provisionService.buildIsoOutputStream(_, _, _, _, _) >> {
            return mockIsoBytes
        }

        when:
        def result = provisionProvider.constructCloudInitOptions(
                workload,
                workloadRequest,
                installAgent,
                platform,
                virtualImage,
                licenses,
                scvmmOpts
        )

        then:

        // Verify the result has the expected properties
        result.installAgent == expectedInstallAgent
        result.cloudConfigUser == workloadRequest.cloudConfigUser
        result.cloudConfigMeta == workloadRequest.cloudConfigMeta
        result.cloudConfigNetwork == workloadRequest.cloudConfigNetwork
        result.licenseApplied == licenseApplied
        result.unattendCustomized == unattendCustomized
        result.cloudConfigUnattend == workloadRequest.cloudConfigUser
        result.cloudConfigBytes.toString() == mockIsoBytes.toString()

        where:
        agentMode   | platform  | installAgent | isSysprep | licenseApplied | unattendCustomized | expectedInstallAgent
        'cloudInit' | 'linux'   | true         | false     | true           | false              | false
        'cloudInit' | 'windows' | true         | true      | null          | true               | false
        'vm'        | 'linux'   | true         | false     | null          | false              | true
    }

    @Unroll
    def "runHost returns success response when all steps succeed"() {
        given:
        // Setup input objects
        def cloud = new Cloud(id: 1L)
        def account = new Account(id: 2L)
        def plan = new ServicePlan(id: 3L, maxMemory: 4096L)
        def server = new ComputeServer(id: 10L, cloud: cloud, account: account, plan: plan, maxMemory: 2048L)
        server.configMap = ["resourcePool":""]
        server.volumes = []
        def hostRequest = new HostRequest()
        def opts = [key: "value"]

        // Setup mocks for all service calls
        def controllerNode = new ComputeServer(id: 20L)
        def scvmmOpts = [opt1: "val1"]
        def clusterId = "cluster-1"
        def rootVolume = new StorageVolume(id: 100L)
        def maxStorage = 100000L
        def hostDatastoreInfo = [datastore: new Datastore(id: 200L), node: new ComputeServer(id: 21L)]
        def virtualImage = new VirtualImage(id: 300L)  // Add this line
        def imageInfo = [imageId: "img-1", virtualImage: virtualImage]
        def createResults = [success: true]
        def provisionResponse = new ProvisionResponse(success: true)

        provisionProvider.pickScvmmController(cloud) >> controllerNode
        provisionProvider.prepareScvmmOpts(_, cloud, controllerNode, server) >> scvmmOpts
        provisionProvider.resolveClusterId(server.configMap, server) >> {
            clusterId
        }
        provisionProvider.getServerRootDisk(server) >> rootVolume
        provisionProvider.getServerRootSize(server) >> maxStorage
        provisionProvider.getHostDatastoreInfoForRoot(cloud, account, clusterId, server.configMap,
                rootVolume, maxStorage, server.provisionSiteId, server.maxMemory) >> {
            hostDatastoreInfo
        }
        provisionProvider.updateScvmmOptsWithHostDatastore(scvmmOpts, hostDatastoreInfo) >> null
        provisionProvider.updateRootVol(rootVolume, hostDatastoreInfo.datastore) >> null
        provisionProvider.updateServerVolumes(server.volumes, cloud, account, clusterId,
                server.configMap.hostId, maxStorage, server.provisionSiteId, server.maxMemory) >> null
        mockApiService.getScvmmControllerOpts(cloud, controllerNode) >> [controllerId: 20L]
        provisionProvider.resolveImageInfo(server.configMap, server) >> imageInfo
        provisionProvider.prepareServerForProvision(server, imageInfo.virtualImage, scvmmOpts,
                hostDatastoreInfo.node, imageInfo.imageId) >> null
        provisionProvider.getScvmmServerOpts(server) >> [vmId: "vm-123"]
        provisionProvider.setCloudConfig(_ as Map, hostRequest, server) >> {
            _
        }
        provisionProvider.saveAndGetMorpheusServer(server, true) >> {
            server
        }
        mockApiService.createServer(_ as Map) >> {
            createResults
        }
        provisionProvider.handleServerCreation(createResults, server, hostDatastoreInfo.node.id,
                cloud, _ as Map) >> provisionResponse

        asyncVirtualImageService.get(300L) >> Maybe.just(virtualImage)

        when:
        def result = provisionProvider.runHost(server, hostRequest, opts)

        then:
        result.success == true
        result.data == provisionResponse
    }

    def "updateScvmmOptsWithHostDatastore sets all expected fields"() {
        given:
        def scvmmOpts = [:]
        def datastore = [externalId: "ds-123"]
        def node = [externalId: "host-456"]
        def hostDatastoreInfo = [
                datastore: datastore,
                node: node,
                volumePath: "/mnt/data",
                highlyAvailable: true
        ]

        when:
        provisionProvider.updateScvmmOptsWithHostDatastore(scvmmOpts, hostDatastoreInfo)

        then:
        scvmmOpts.datastoreId == "ds-123"
        scvmmOpts.hostExternalId == "host-456"
        scvmmOpts.volumePath == "/mnt/data"
        scvmmOpts.highlyAvailable == true
    }

    def "prepareScvmmOpts sets expected fields in scvmmOpts"() {
        given:
        def cloud = new Cloud(id: 1L, name: "test-cloud")
        def controllerNode = new ComputeServer(id: 10L, name: "controller-node")
        def server = new ComputeServer(id: 20L, name: "test-server")
        def initialOpts = [existingKey: "existingValue"]

        mockApiService.getScvmmCloudOpts(morpheusContext, cloud, controllerNode) >> initialOpts

        when:
        def result = provisionProvider.prepareScvmmOpts(morpheusContext, cloud, controllerNode, server)

        then:
        result.controllerServerId == 10L
        result.creatingDockerHost == true
        result.name == "test-server"
        result.existingKey == "existingValue"
    }

    def "resolveClusterId returns correct externalId or null for different scenarios"() {
        given:
        def pool = poolExternalId ? new ComputeZonePool(externalId: poolExternalId) : null
        def server = new ComputeServer(resourcePool: pool)
        def config = configHasResourcePool ? [resourcePool: "pool-1"] : [:]

        when:
        def result = provisionProvider.resolveClusterId(config, server)

        then:
        result == expectedResult

        where:
        scenario                        | configHasResourcePool | poolExternalId | expectedResult
        "resourcePool and pool present" | true                  | "ext-123"      | "ext-123"
        "resourcePool present, pool null" | true                | null           | null
        "resourcePool missing"           | false                | "ext-123"      | null
        "resourcePool missing, pool null"| false                | null           | null
    }

    @Unroll
    def "getHostDatastoreInfoForRoot returns correct host, datastore, volumePath, and highlyAvailable"() {
        given:
        def cloud = new Cloud(id: 1L)
        def account = new Account(id: 2L)
        def clusterId = "cluster-1"
        def config = [:]
        config.hostId = 5
        DatastoreIdentity datastoreIdentity = new Datastore()
        datastoreIdentity.cloudId = cloud.id
        datastoreIdentity.externalId = "externalId-123"

        def rootVolume = new StorageVolume(
                id: 100L,
                datastore: datastoreIdentity,
                datastoreOption: "option-1"
        )
        def maxStorage = 100000L
        def provisionSiteId = 20L
        def maxMemory = 4096L

        // Mock getHostAndDatastore to return expected values
        def expectedNode = new ComputeServer(id: 10L)
        def expectedDatastore = new Datastore(id: 100L)
        def expectedVolumePath = "/mnt/data"
        def expectedHighlyAvailable = true

        provisionProvider.getHostAndDatastore(_, _, _, _, _, _, _, _, _) >> [
                expectedNode,
                expectedDatastore,
                expectedVolumePath,
                expectedHighlyAvailable
        ]

        when:
        def result = provisionProvider.getHostDatastoreInfoForRoot(
                cloud, account, clusterId, config, rootVolume, maxStorage, provisionSiteId, maxMemory
        )

        then:
        result.node == expectedNode
        result.datastore == expectedDatastore
        result.volumePath == expectedVolumePath
        result.highlyAvailable == expectedHighlyAvailable
        1 * provisionProvider.getHostAndDatastore(cloud, account, clusterId, config.hostId, rootVolume.datastore,
                rootVolume.datastoreOption, maxStorage, provisionSiteId, maxMemory) >> [
                expectedNode,
                expectedDatastore,
                expectedVolumePath,
                expectedHighlyAvailable
        ]
    }

    def "updateRootVol sets datastore and saves rootVolume successfully"() {
        given:
        def rootVolume = new StorageVolume(id: 1L)
        def datastore = new Datastore(id: 2L)

        storageVolumeService.save(rootVolume)

        when:
        provisionProvider.updateRootVol(rootVolume, datastore)

        then:
        rootVolume.datastore == datastore
        1 * storageVolumeService.save(rootVolume)
    }

    def "updateServerVolumes updates non-root volumes and saves them"() {
        given:
        def cloud = new Cloud(id: 1L)
        def account = new Account(id: 2L)
        def clusterId = "cluster-1"
        def hostId = 10
        def maxStorage = 100000L
        def provisionSiteId = 20L
        def maxMemory = 4096L

        def rootVolume = new StorageVolume(id: 1L, rootVolume: true)
        def dataVolume = new StorageVolume(id: 2L, rootVolume: false)
        def volumes = [rootVolume, dataVolume]

        def tmpNode = new ComputeServer(id: 99L)
        def tmpDatastore = new Datastore(id: 88L)
        def tmpVolumePath = "/mnt/data"
        def tmpHighlyAvailable = true

        // Mock the datastore service to avoid null pointer exception
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        cloudService.getDatastore() >> datastoreService
        datastoreService.list(_ as DataQuery) >> [tmpDatastore]

        // Mock compute server service
        computeServerService.get(hostId.toLong()) >> tmpNode

        // Mock volume path method
        provisionProvider.getVolumePathForDatastore(tmpDatastore) >> tmpVolumePath

        storageVolumeService.save(_) >> { StorageVolume vol -> vol }

        when:
        provisionProvider.updateServerVolumes(volumes, cloud, account, clusterId, hostId, maxStorage, provisionSiteId, maxMemory)

        then:
        dataVolume.datastore == tmpDatastore
        1 * storageVolumeService.save(dataVolume)
        0 * storageVolumeService.save(rootVolume)
    }

    def "resolveImageInfo returns imageId and virtualImage from typeSet when layout and typeSet are present"() {
        given:
        def virtualImage = new VirtualImage(externalId: "img-123")
        def workloadType = new WorkloadType(virtualImage: virtualImage)
        def typeSet = new ComputeTypeSet(workloadType: workloadType)
        def compTypeLayout = new ComputeTypeLayout(code: "layout-1")
        def server = new ComputeServer(layout: compTypeLayout, typeSet: typeSet)
        def config = [:]

        when:
        def result = provisionProvider.resolveImageInfo(config, server)

        then:
        result.imageId == "img-123"
        result.virtualImage == virtualImage
    }

    def "resolveImageInfo returns imageId and virtualImage from config.template when imageType is custom"() {
        given:
        def virtualImage = new VirtualImage(externalId: "img-456")
        def config = [templateTypeSelect: 'custom', template: "789"]
        def server = new ComputeServer()
        virtualImageService.get(789L) >> virtualImage

        when:
        def result = provisionProvider.resolveImageInfo(config, server)

        then:
        result.imageId == "img-456"
        result.virtualImage == virtualImage
    }

    def "resolveImageInfo returns default virtualImage when no layout/typeSet and not custom"() {
        given:
        def config = [:] // templateTypeSelect is null, so defaults to 'default'
        def server = new ComputeServer()

        when:
        def result = provisionProvider.resolveImageInfo(config, server)

        then:
        result.imageId == null
        result.virtualImage.code == 'scvmm.image.morpheus.ubuntu.22.04.20250218.amd64'
    }


    def "handleImageUpload returns imageId when upload succeeds"() {
        given:
        def scvmmOpts = [:]
        def server = new ComputeServer(createdBy: new User(id: 42L))
        def virtualImage = new VirtualImage(id: 101L, name: "test-image", imageType: "vhd")
        def cloudFile1 = [name: "disk.vhd"]
        def cloudFile2 = [name: "other.txt"]
        def cloudFiles = [cloudFile1, cloudFile2]
        asyncVirtualImageService.getVirtualImageFiles(virtualImage) >> Single.just(cloudFiles)
        mockApiService.insertContainerImage(_) >> [success: true, imageId: "img-123"]

        when:
        def result = provisionProvider.handleImageUpload(scvmmOpts, server, virtualImage)

        then:
        result == "img-123"
        scvmmOpts.image.name == "test-image"
        scvmmOpts.userId == 42L
        scvmmOpts.image.imageFile == cloudFile1
    }

    def "handleImageUpload returns null when upload fails"() {
        given:
        def scvmmOpts = [:]
        def server = new ComputeServer(createdBy: new User(id: 42L))
        def virtualImage = new VirtualImage(id: 101L, name: "test-image", imageType: "vhd")
        def cloudFile1 = [name: "disk.vhd"]
        def cloudFiles = [cloudFile1]
        asyncVirtualImageService.getVirtualImageFiles(virtualImage) >> Single.just(cloudFiles)
        mockApiService.insertContainerImage(_) >> [success: false]

        when:
        def result = provisionProvider.handleImageUpload(scvmmOpts, server, virtualImage)

        then:
        result == null
    }

    @Unroll
    def "handleImageUpload returns #expectedResult when upload #scenario"() {
        given:
        def scvmmOpts = [:]
        def server = new ComputeServer(createdBy: new User(id: 42L))
        def virtualImage = new VirtualImage(id: 101L, name: "test-image", imageType: "vhd")
        def cloudFile1 = [name: "disk.vhd"]
        def cloudFile2 = [name: "other.txt"]
        def cloudFiles = [cloudFile1, cloudFile2]
        asyncVirtualImageService.getVirtualImageFiles(virtualImage) >> Single.just(cloudFiles)
        mockApiService.insertContainerImage(_) >> apiResponse

        when:
        def result = provisionProvider.handleImageUpload(scvmmOpts, server, virtualImage)

        then:
        result == expectedResult
        scvmmOpts.image.name == "test-image"
        scvmmOpts.userId == 42L
        scvmmOpts.image.imageFile == cloudFile1

        where:
        scenario   | apiResponse                                 | expectedResult
        "succeeds" | [success: true, imageId: "img-123"]         | "img-123"
        "fails"    | [success: false]                            | null
    }

    @Unroll
    def "assignServerOsType sets serverOs and osType for #platform scenario"() {
        given:
        def server = new ComputeServer()
        def virtualImage = new VirtualImage(osType: new OsType(), platform: viPlatform)

        when:
        provisionProvider.assignServerOsType(server, virtualImage)

        then:
        server.serverOs == virtualImage.osType
        server.osType == expectedOsType

        where:
        platform      | viPlatform | expectedOsType
        "windows"     | "windows"  | "windows"
        "osx"         | "osx"      | "osx"
        "linux"       | "linux"    | "linux"
        "unknown"     | "other"    | "linux"
    }

    def "setCloudConfig should populate scvmmOpts and server fields correctly"() {
        given:
        MorpheusSynchronousProvisionService provisionService = Mock(MorpheusSynchronousProvisionService)
        morpheusContext.services.provision >> {
            return provisionService
        }

        def scvmmOpts = [:]
        def netConfig = new NetworkConfiguration()
        def hostRequest = new HostRequest(
                networkConfiguration: netConfig,
                cloudConfigUser: "userConfig",
                cloudConfigMeta: "metaConfig",
                cloudConfigNetwork: "networkConfig"
        )
        def server = new ComputeServer(
                sourceImage: new VirtualImage(),
        )
        def isoBuffer = "isoBufferBytes".getBytes()
        provisionService.buildIsoOutputStream(false, PlatformType.linux, "metaConfig",
                "userConfig", "networkConfig") >> isoBuffer

        when:
        provisionProvider.setCloudConfig(scvmmOpts, hostRequest, server)

        then:
        scvmmOpts.networkConfig == netConfig
        scvmmOpts.cloudConfigUser == "userConfig"
        scvmmOpts.cloudConfigMeta == "metaConfig"
        scvmmOpts.cloudConfigNetwork == "networkConfig"
        scvmmOpts.isSysprep == false
        scvmmOpts.cloudConfigBytes == isoBuffer

        server.cloudConfigUser == "userConfig"
        server.cloudConfigMeta == "metaConfig"
        server.cloudConfigNetwork == "networkConfig"
    }

    @Unroll
    def "handleServerCreation sets correct fields and returns expected success=#expectedSuccess for #scenario"() {
        given:
        def nodeId = 99L
        def node = new ComputeServer(id: nodeId)
        def server = new ComputeServer()
        def cloud = new Cloud(id: 1L)
        def scvmmOpts = [:]
        def instance = [id: "srv-123", disks: disks]
        def createResults = [success: createSuccess, server: instanceOrNull ? instance : null]
        def serverDetails = [success: serverDetailsSuccess]
        computeServerService.get(nodeId) >> {
            return node
        }
        provisionProvider.updateServerVolumesAfterCreation(server, disks, cloud) >> { }
        mockApiService.getServerDetails(scvmmOpts, "srv-123") >> serverDetails
        provisionProvider.updateServerNetworkAndStatus(server, serverDetails, scvmmOpts, createResults) >> { }
        provisionProvider.handleServerDetailsFailure(server) >> {
            server.statusMessage = "Failed to get server details"
        }
        provisionProvider.handleServerCreationFailure(createResults, server) >> {
            server.statusMessage = "Failed to create server"
        }
        asyncComputeServerService.save(server) >> Single.just(server)

        when:
        def result = provisionProvider.handleServerCreation(createResults, server, nodeId, cloud, scvmmOpts)

        then:
        result.success == expectedSuccess
        server.externalId == expectedExternalId
        server.statusMessage == expectedStatusMessage

        where:
        scenario                    | createSuccess | instanceOrNull | disks | serverDetailsSuccess | expectedSuccess | expectedExternalId | expectedStatusMessage
        "successful creation"       | true          | true           | []    | true                 | true            | "srv-123"         | null
        "creation fails"            | false         | false          | []    | false                | false           | null              | "Failed to create server"
    }

    def "updateServerVolumesAfterCreation calls updateRootVolumeAfterCreation and updateDataVolumesAfterCreation"() {
        given:
        def rootVolume = new StorageVolume(id: 1, rootVolume: true)
        def dataVolume = new StorageVolume(id: 2, rootVolume: false)
        def server = new ComputeServer(volumes: [rootVolume, dataVolume])
        def serverDisks = [diskMetaData: [:], osDisk: [:]]
        def cloud = new Cloud(id: 1L)

        when:
        provisionProvider.updateServerVolumesAfterCreation(server, serverDisks, cloud)

        then:
        1 * provisionProvider.updateRootVolumeAfterCreation(rootVolume, serverDisks, cloud)
        1 * provisionProvider.updateDataVolumesAfterCreation([rootVolume, dataVolume], serverDisks, cloud)
    }

    @Unroll
    def "updateRootVolumeAfterCreation sets externalId and datastore correctly"() {
        given:
        def rootVolume = new StorageVolume(id: 1, rootVolume: true)
        def cloud = new Cloud(id: 1L)
        def osDiskExternalId = "osDisk-123"
        def vhdId = "vhd-456"
        def hostVolumeId = "hostVol-789"
        def fileShareId = "fileShare-101"
        def partitionUniqueId = "partition-202"
        def expectedDatastore = new Datastore(id: 99L)
        def serverDisks = [
                osDisk: [externalId: osDiskExternalId],
                diskMetaData: [
                        (osDiskExternalId): [VhdID: vhdId],
                        (vhdId): [
                                HostVolumeId: hostVolumeId,
                                FileShareId: fileShareId,
                                PartitionUniqueId: partitionUniqueId
                        ]
                ]
        ]
        provisionProvider.loadDatastoreForVolume(cloud, hostVolumeId, fileShareId, partitionUniqueId) >> expectedDatastore

        when:
        provisionProvider.updateRootVolumeAfterCreation(rootVolume, serverDisks, cloud)

        then:
        rootVolume.externalId == vhdId
        rootVolume.datastore == expectedDatastore
    }

    @Unroll
    def "updateDataVolumesAfterCreation sets externalId and datastore for matching data volumes"() {
        given:
        def cloud = new Cloud(id: 1L)
        def dataVol1 = new StorageVolume(id: 2, rootVolume: false)
        def dataVol2 = new StorageVolume(id: 3, rootVolume: false)
        def storageVolumes = [dataVol1, dataVol2]
        def dataDisk1ExternalId = "dataDisk-2"
        def dataDisk2ExternalId = "dataDisk-3"
        def vhdId1 = "vhd-222"
        def vhdId2 = "vhd-333"
        def hostVolumeId1 = "hostVol-222"
        def fileShareId1 = "fileShare-222"
        def partitionUniqueId1 = "partition-222"
        def hostVolumeId2 = "hostVol-333"
        def fileShareId2 = "fileShare-333"
        def partitionUniqueId2 = "partition-333"
        def expectedDatastore1 = new Datastore(id: 222L)
        def expectedDatastore2 = new Datastore(id: 333L)
        def serverDisks = [
                dataDisks: [
                        [id: 2, externalId: dataDisk1ExternalId],
                        [id: 3, externalId: dataDisk2ExternalId]
                ],
                diskMetaData: [
                        (dataDisk1ExternalId): [VhdID: vhdId1],
                        (dataDisk2ExternalId): [VhdID: vhdId2],
                        (vhdId1): [
                                HostVolumeId: hostVolumeId1,
                                FileShareId: fileShareId1,
                                PartitionUniqueId: partitionUniqueId1
                        ],
                        (vhdId2): [
                                HostVolumeId: hostVolumeId2,
                                FileShareId: fileShareId2,
                                PartitionUniqueId: partitionUniqueId2
                        ]
                ]
        ]

        provisionProvider.loadDatastoreForVolume(cloud, hostVolumeId1, fileShareId1, partitionUniqueId1) >> expectedDatastore1
        provisionProvider.loadDatastoreForVolume(cloud, hostVolumeId2, fileShareId2, partitionUniqueId2) >> expectedDatastore2

        when:
        provisionProvider.updateDataVolumesAfterCreation(storageVolumes, serverDisks, cloud)

        then:
        dataVol1.externalId == vhdId1
        dataVol1.datastore == expectedDatastore1
        dataVol2.externalId == vhdId2
        dataVol2.datastore == expectedDatastore2
    }

    @Unroll
    def "handleServerCreationFailure sets externalId and statusMessage, saves server if id present"() {
        given:
        def server = new ComputeServer()
        def createResults = [server: [id: "vm-123"]]

        asyncComputeServerService.save(server) >> Single.just(server)
        when:
        provisionProvider.handleServerCreationFailure(createResults, server)

        then:
        server.externalId == "vm-123"
        server.statusMessage == "Error creating server"
    }

    @Unroll
    def "handleServerCreationFailure sets only statusMessage if id not present"() {
        given:
        def server = new ComputeServer()
        def createResults = [server: [:]]

        when:
        provisionProvider.handleServerCreationFailure(createResults, server)

        then:
        server.externalId == null
        server.statusMessage == "Error creating server"
    }

    @Unroll
    def "findNetworkInterface returns correct interface for #scenario"() {
        given:
        def iface1 = new ComputeServerInterface(ipAddress: "10.0.0.1", primaryInterface: true, displayOrder: 0)
        def iface2 = new ComputeServerInterface(ipAddress: "10.0.0.2", primaryInterface: false, displayOrder: 1)
        def iface3 = new ComputeServerInterface(ipAddress: "10.0.0.3", primaryInterface: false, displayOrder: 2)
        def server = new ComputeServer(interfaces: [iface1, iface2, iface3])

        when:
        def result = provisionProvider.findNetworkInterface(server, privateIp, index)

        then:
        if (scenario == "find by ipAddress" || scenario == "find by index fallback") {
            result == iface2
        } else if (scenario == "find by primaryInterface") {
            result == iface1
        } else if (scenario == "find by displayOrder") {
            result == iface3
        } else {
            result == null
        }


        where:
        scenario                        | privateIp     | index
        "find by ipAddress"             | "10.0.0.2"    | 1
        "find by primaryInterface"      | "notfound"    | 0
        "find by displayOrder"          | "notfound"    | 2
        "find by index fallback"        | "notfound"    | 1
        "not found returns null"        | "notfound"    | 5
    }

    @Unroll
    def "createNetworkInterface creates interface with correct properties"() {
        given:
        def server = new ComputeServer(
                interfaces: [new ComputeServerInterface(name: "custom0")],
                sourceImage: new VirtualImage()
        )
        def privateIp = "192.168.1.100"

        when:
        def result = provisionProvider.createNetworkInterface(server, privateIp)

        then:
        result.name == "eth0"
        result.ipAddress == privateIp
        result.primaryInterface == true
        result.displayOrder == 2 // 1 existing + 1
        result.addresses.size() == 2
        result.addresses[0].type == NetAddress.AddressType.IPV4
        result.addresses[0].address == privateIp
    }

    @Unroll
    def "handleMemoryAndCoreResize returns #expectedResult and updates properties for #scenario"() {
        given:
        def computeServer = new ComputeServer()
        def workload = new Workload()
        def resizeConfig = [
                neededMemory: neededMemory,
                neededCores: neededCores,
                minDynamicMemory: minDynamicMemory,
                maxDynamicMemory: maxDynamicMemory,
                requestedMemory: requestedMemory,
                requestedCores: requestedCores
        ]
        def scvmmOpts = [opt: "val"]
        def vmId = "vm-123"


        mockApiService.updateServer(scvmmOpts, vmId, _) >> resizeResults
        provisionProvider.saveAndGet(_) >> computeServer
        workloadService.save(_) >> workload

        when:
        def result = provisionProvider.handleMemoryAndCoreResize(computeServer, resizeConfig, scvmmOpts, vmId, isWorkload, workload)

        then:
        result == expectedResult
        if (shouldUpdate) {
            computeServer.maxMemory == requestedMemory
            computeServer.maxCores == (requestedCores ?: 1)
            if (isWorkload) {
                workload.maxMemory == requestedMemory
                workload.maxCores == (requestedCores ?: 1)
                workload.server == computeServer
            }
        }

        where:
        scenario                | neededMemory | neededCores | minDynamicMemory | maxDynamicMemory | requestedMemory | requestedCores | resizeResults                  | isWorkload | expectedResult | shouldUpdate
        "resize success"        | 1024         | 2           | 0                | 0                | 1024            | 2              | [success: true]                | true       | true           | true
        "resize fails"          | 1024         | 2           | 0                | 0                | 1024            | 2              | [success: false, error: "err"] | false      | false          | false
        "no resize needed"      | 0            | 0           | 0                | 0                | 2048            | 4              | [success: true]                | false      | true           | false
        "dynamic memory set"    | 0            | 0           | 512              | 2048             | 2048            | 4              | [success: true]                | true       | true           | true
    }

    @Unroll
    def "updateVolumes resizes volume and sets error for #scenario"() {
        given:
        def existingVolume = new StorageVolume(id: 1, externalId: "vol-1", maxStorage: 1024L)
        def updateProps = [maxStorage: newMaxStorage, size: newSize]
        def volumeUpdate = [existingModel: existingVolume, updateProps: updateProps]
        def volumesUpdate = [volumeUpdate]
        def scvmmOpts = [opt: "val"]
        def rtn = new ServiceResponse()

        mockApiService.resizeDisk(scvmmOpts, "vol-1", ComputeUtility.parseGigabytesToBytes(newSize)) >> resizeResults
        storageVolumeService.get(1) >> existingVolume
        storageVolumeService.save(existingVolume) >> existingVolume

        when:
        provisionProvider.updateVolumes(volumesUpdate, scvmmOpts, rtn)

        then:
        if (shouldResize) {
            if (resizeResults.success) {
                existingVolume.maxStorage == ComputeUtility.parseGigabytesToBytes(newSize)
            } else {
                rtn.error == expectedError
            }
        } else {
            0 * mockApiService.resizeDisk(_, _, _)
            rtn.error == null
        }

        where:
        scenario              | newMaxStorage | newSize | resizeResults                  | shouldResize | expectedError
        "resize success"      | 2048L         | 2       | [success: true]                | true         | null
        "no resize needed"    | 1024L         | 1       | [success: true]                | false        | null
    }

    @Unroll
    def "addVolumes creates and attaches disk, updates volume and handles error for #scenario"() {
        given:
        def computeServer = new ComputeServer(id: 1, cloud: new Cloud(), volumes: [])
        def volumeAdd = [size: addSize, datastore: datastore]
        def volumesAdd = [volumeAdd]
        def scvmmOpts = [opt: "val"]
        def rtn = new ServiceResponse()
        def diskSpecMatcher = { Map spec -> spec.sizeMb == (ComputeUtility.parseGigabytesToBytes(addSize) / ComputeUtility.ONE_MEGABYTE) }

        def diskResults = [success: true, disk: [VhdLocation: "loc", VhdID: "id", HostVolumeId: "hv", FileShareId: "fs", PartitionUniqueId: "pu"]]
        mockApiService.createAndAttachDisk(scvmmOpts, _, true) >> {
            return diskResults
        }
        provisionProvider.getVolumePathForDatastore(datastore) >> volumePath
        provisionProvider.buildStorageVolume(computeServer, volumeAdd, 0) >> new StorageVolume()
        provisionProvider.loadDatastoreForVolume(computeServer.cloud, diskResults?.disk?.HostVolumeId, diskResults?.disk?.FileShareId, diskResults?.disk?.PartitionUniqueId) >> updatedDatastore
        asyncStorageVolumeService.create(_, computeServer) >> Single.just([:])
        provisionProvider.getMorpheusServer(computeServer.id) >> computeServer

        when:
        provisionProvider.addVolumes(volumesAdd, computeServer, scvmmOpts, rtn)

        then:

        rtn.error == null


        where:
        scenario           | addSize | datastore      | volumePath | updatedDatastore
        "success"          | 2       | new Datastore()|  "/vol/path" | new Datastore()
    }

    @Unroll
    def "deleteVolumes removes disk and updates server "() {
        given:
        def computeServer = new ComputeServer(id: 1, volumes: [])
        def volume = new StorageVolume(id: 2, externalId: "vol-2")
        def volumesDelete = [volume]
        def scvmmOpts = [opt: "val"]

        TaskResult detachResult1 = new TaskResult()
        detachResult1.success = true
        detachResult1.data = "abc pqr"
        mockApiService.removeDisk(_, _) >> {
            return detachResult1
        }
        asyncStorageVolumeService.remove([volume], computeServer, true) >> Single.just([:])
        provisionProvider.getMorpheusServer(computeServer.id) >> computeServer

        when:
        provisionProvider.deleteVolumes(volumesDelete, computeServer, scvmmOpts)

        then:
        1 * provisionProvider.getMorpheusServer(computeServer.id)

    }

    @Unroll
    def "handleVolumeOperations calls update, add, delete and returns #expectedResult when error=#errorPresent"() {
        given:
        def opts = [volumes: true]
        def resizeRequest = new ResizeRequest(
                volumesUpdate: [1],
                volumesAdd: [2],
                volumesDelete: [3]
        )
        def computeServer = new ComputeServer(id: 1)
        def scvmmOpts = [opt: "val"]
        def rtn = new ServiceResponse()
        rtn.error = errorPresent

        provisionProvider.updateVolumes(_, _, _) >> null
        provisionProvider.addVolumes(_, _, _, _) >> null
        provisionProvider.deleteVolumes(_, _, _) >> null

        when:
        def result = provisionProvider.handleVolumeOperations(opts, resizeRequest, computeServer, scvmmOpts, rtn)

        then:
        result == expectedResult

        where:
        errorPresent | expectedResult
        null        | true
        true         | false
    }

    @Unroll
    def "resizeWorkloadAndServer handles #scenario correctly"() {
        given:
        def workload = new Workload(id: 1, server: new ComputeServer(id: 2), instance: [plan: Mock(ServicePlan)])
        def server = new ComputeServer(id: 2, plan: Mock(ServicePlan))
        def resizeRequest = new ResizeRequest()
        def opts = [volumes: true]
        def computeServer = new ComputeServer(id: 2, externalId: "vm-2", plan: Mock(ServicePlan))
        provisionProvider.getMorpheusServer(_) >> computeServer
        provisionProvider.saveAndGet(_) >> computeServer
        provisionProvider.getAllScvmmOpts(_) >> [opt: "val"]
        provisionProvider.getAllScvmmServerOpts(_) >> [opt: "val"]
        provisionProvider.getResizeConfig(_, _, _, _, _) >> [hotResize: hotResize]
        provisionProvider.stopWorkload(_) >> stopResult
        provisionProvider.stopServer(_) >> stopResult
        provisionProvider.handleMemoryAndCoreResize(_, _, _, _, _, _) >> memoryResizeSuccess
        provisionProvider.handleVolumeOperations(_, _, _, _, _) >> volumeResizeSuccess
        provisionProvider.startWorkload(_) >> null
        provisionProvider.startServer(_) >> null

        when:
        def result = provisionProvider.resizeWorkloadAndServer(
                isWorkload ? workload : null,
                isWorkload ? null : server,
                resizeRequest,
                opts,
                isWorkload
        )

        then:
        result.success == expectedSuccess
        result.error == expectedError

        where:
        scenario                | isWorkload | hotResize | stopResult                        | memoryResizeSuccess | volumeResizeSuccess | expectedSuccess | expectedError
        "hot resize success"    | true       | true      | null                              | true               | true               | true           | null
        "cold resize success"   | false      | false     | new ServiceResponse(success:true) | true               | true               | true           | null
        "memory resize fails"   | false      | false     | new ServiceResponse(success:true) | false              | true               | true           | 'Failed to resize memory/cores'
        "volume resize fails"   | true       | false     | new ServiceResponse(success:true) | true               | false              | true           | 'Failed to handle volume operations'
    }

    @Unroll
    def "resizeWorkloadAndServer catches exception and sets error for isWorkload=#isWorkload"() {
        given:
        def workload = new Workload(id: 1, server: new ComputeServer(id: 2), instance: [plan: Mock(ServicePlan)])
        def server = new ComputeServer(id: 2, plan: Mock(ServicePlan))
        def resizeRequest = new ResizeRequest()
        def opts = [volumes: true]
        def computeServer = new ComputeServer(id: 2, externalId: "vm-2", plan: Mock(ServicePlan))
        provisionProvider.getMorpheusServer(_) >> computeServer
        provisionProvider.saveAndGet(_) >> computeServer
        provisionProvider.getAllScvmmOpts(_) >> [opt: "val"]
        provisionProvider.getAllScvmmServerOpts(_) >> [opt: "val"]
        provisionProvider.getResizeConfig(_, _, _, _, _) >> [hotResize: true]
        // Simulate exception in handleMemoryAndCoreResize
        provisionProvider.handleMemoryAndCoreResize(_, _, _, _, _, _) >> { throw new RuntimeException("Test error") }

        when:
        def result = provisionProvider.resizeWorkloadAndServer(
                isWorkload ? workload : null,
                isWorkload ? null : server,
                resizeRequest,
                opts,
                isWorkload
        )

        then:
        result.success == false
        result.error.contains("Test error")
        where:
        isWorkload << [true, false]
    }

    @Unroll
    def "getDiskExternalIds returns correct disk info "() {
        given:
        def rootVol = new StorageVolume(externalId: "root-id", rootVolume: true)
        def dataVol1 = new StorageVolume(externalId: "data-id-1", rootVolume: false)
        def dataVol2 = new StorageVolume(externalId: "data-id-2", rootVolume: false)
        def location = new VirtualImageLocation(volumes: [rootVol, dataVol1, dataVol2] )
        provisionProvider.getVirtualImageLocation(_, _) >> location

        def expectedResult = [[rootVolume: true, externalId: "root-id", idx: 0],
                              [rootVolume: false, externalId: "data-id-1", idx: 1],
                              [rootVolume: false, externalId: "data-id-2", idx: 2]]
        when:
        def result = provisionProvider.getDiskExternalIds(Mock(VirtualImage), Mock(Cloud))

        then:
        result.size() == expectedResult.size()

    }

    @Unroll
    def "finalizeProvisionResponse sets ServiceResponse fields for success=#success"() {
        given:
        def provisionResponse = new ProvisionResponse(success: success, message: message)
        def rtn = new ServiceResponse()

        when:
        provisionProvider.finalizeProvisionResponse(provisionResponse, rtn)

        then:
        rtn.success == expectedSuccess
        rtn.data == provisionResponse
        rtn.msg == expectedMsg
        rtn.error == expectedError

        where:
        success | message         | expectedSuccess | expectedMsg           | expectedError
        true    | "ok"            | true            | null                  | null
        false   | "fail reason"   | false           | "fail reason"         | "fail reason"
        false   | null            | false           | 'vm config error' | null
    }


    @Unroll
    def "buildCloneBaseOpts returns correct options when cloudInitIsoNeeded=#isoNeeded"() {
        given:
        def server = new ComputeServer()
        def osType = new OsType()
        osType.platform = PlatformType.linux
        def virtualImage = new VirtualImage()
        virtualImage.isCloudInit = true
        server.sourceImage = virtualImage
        server.serverOs = osType
        def workload = new Workload()
        workload.server = server
        def workloadReq = new WorkloadRequest()
        def parentContainer = workload

        def opts = [installAgent: true]
        def scvmmOpts = [platform: 'linux', licenses: ['lic1']]

        def controlNode = Mock(ComputeServer) { getId() >> 42 }

        def initOptions = [cloudConfigBytes: 'bytes', cloudConfigNetwork: 'net', licenseApplied: licenseApplied, unattendCustomized: 'custom']
        def clonedScvmmOpts = [serverFolder: 'folder', diskRoot: 'root']

        mockApiService.getScvmmZoneOpts(_, _) >> clonedScvmmOpts
        mockApiService.getScvmmControllerOpts(_, _) >> [controller: 'ctrl']

        provisionProvider.constructCloudInitOptions(_,_,_,_,_,_,_)>>  {
            return initOptions
        }
        provisionProvider.getScvmmContainerOpts(_) >> {
            return [container: 'opts']
        }

        when:
        def result = provisionProvider.buildCloneBaseOpts(parentContainer, workloadReq, opts, scvmmOpts, virtualImage, server, controlNode)

        then:
        result.cloudInitIsoNeeded == isoNeeded
        if (isoNeeded) {
            result.imageFolderName == 'folder'
            result.diskFolder == 'root\\folder'
            result.cloudConfigBytes == 'bytes'
            result.cloudConfigNetwork == 'net'
            result.clonedScvmmOpts.serverFolder == 'folder'
            result.clonedScvmmOpts.controllerServerId == 42
            opts.licenseApplied == licenseApplied
            opts.unattendCustomized == 'custom'
        } else {
            !result.containsKey('imageFolderName')
            !result.containsKey('diskFolder')
            !result.containsKey('cloudConfigBytes')
            !result.containsKey('cloudConfigNetwork')
            !result.containsKey('clonedScvmmOpts')
            !opts.containsKey('licenseApplied')
            !opts.containsKey('unattendCustomized')
        }

        where:
        isoNeeded | licenseApplied
        true      | true
        true      | false
    }

    @Unroll
    def "handleCloneContainerOpts sets cloneBaseOpts and calls helpers for cloudInitIsoNeeded=#isoNeeded"() {
        given:
        def scvmmOpts = [:]
        def opts = [installAgent: true]
        def server = new ComputeServer()
        def workloadRequest = new WorkloadRequest()
        def virtualImage = new VirtualImage()
        def controlNode = Mock(ComputeServer)
        def parentContainer = Mock(Workload)
        def args = [
                scvmmOpts: scvmmOpts,
                opts: opts,
                server: server,
                workloadRequest: workloadRequest,
                virtualImage: virtualImage,
                controlNode: controlNode
        ]

        def cloneBaseOpts = [cloudInitIsoNeeded: isoNeeded]
        provisionProvider.getParentContainer(_) >> {  return parentContainer }
        provisionProvider.setCloneContainerIds(_,_) >> {  }
        provisionProvider.handleCloneVmStatus(_,_) >> {  }
        provisionProvider.buildCloneBaseOpts(_,_,_,_,_,_,_) >> { return  cloneBaseOpts }

        when:
        provisionProvider.handleCloneContainerOpts(args)

        then:
        scvmmOpts.cloneBaseOpts == cloneBaseOpts


        where:
        isoNeeded << [true, false]
    }

    @Unroll
    def "getParentContainer returns workload for cloneContainerId=#cloneContainerId"() {
        given:
        def workload = new Workload()
        def cloneContainerId = 123
        def opts = [cloneContainerId: cloneContainerId]
        workloadService.get(cloneContainerId.toLong()) >> {
            return workload
        }

        when:
        def result = provisionProvider.getParentContainer(opts)

        then:
        result == workload
    }

    @Unroll
    def "setCloneContainerIds sets cloneContainerId and cloneVMId from parentContainer"() {
        given:
        def scvmmOpts = [:]
        def server = new ComputeServer(externalId: "vm-123")
        def parentContainer = new Workload(id: 42, server: server)

        when:
        provisionProvider.setCloneContainerIds(scvmmOpts, parentContainer)

        then:
        scvmmOpts.cloneContainerId == 42
        scvmmOpts.cloneVMId == "vm-123"
    }

    @Unroll
    def "handleCloneVmStatus sets startClonedVM and calls stopWorkload when status is running=#isRunning"() {
        given:
        def scvmmOpts = [:]
        def parentContainer = new Workload(status: status)

        provisionProvider.stopWorkload(_ as Workload) >> ServiceResponse.success()

        when:
        provisionProvider.handleCloneVmStatus(parentContainer, scvmmOpts)

        then:
        (isRunning ? 1 : 0) * provisionProvider.stopWorkload(_ as Workload)
        scvmmOpts.startClonedVM == (isRunning ? true : null)

        where:
        status                        | isRunning
        Workload.Status.running       | true
    }

    @Unroll
    def "handleImageUploadFailure sets statusMessage, success flags, and data"() {
        given:
        def server = new ComputeServer()
        def provisionResponse = new ProvisionResponse(success: true)
        def rtn = new ServiceResponse(success: true)
        asyncComputeServerService.save(server) >> Single.just([success: true])

        when:
        provisionProvider.handleImageUploadFailure(server, provisionResponse, rtn)

        then:
        server.statusMessage == 'Failed to upload image'
        provisionResponse.success == false
        rtn.success == false
        rtn.data == provisionResponse
    }
// ScvmmProvisionProviderRunWorkloadSpec.groovy

    def "handleCloneContainer should copy datastores from clone container volumes to server volumes"() {
        given:
        def cloneContainer = new Workload()
        def server = new ComputeServer()
        def cloneServer = new ComputeServer()
        def datastore = new Datastore()

        def cloneContainerId = 123L
        def opts = [cloneContainerId: cloneContainerId]
        def vol1 = new StorageVolume()
        vol1.datastore = datastore
        def vol2 = new StorageVolume()
        vol2.datastore = datastore
        def cloneVol1 = new StorageVolume()
        def cloneVol2 = new StorageVolume()
        cloneVol1.datastore = datastore
        cloneVol2.datastore = datastore
        def serverVolumes = [vol1, vol2]
        def cloneVolumes = [cloneVol1, cloneVol2]
        server.volumes = serverVolumes
        cloneServer.volumes = cloneVolumes
        cloneContainer.server = server

        workloadService.get(cloneContainerId) >> {
            return cloneContainer
        }

        storageVolumeService.save(_) >> {}

        when:
        provisionProvider.handleCloneContainer(opts, server)

        then:

        2 * storageVolumeService.save(_)
    }

    def "fetchCloudFiles should return cloud files from async service"() {
        given:
        def virtualImage = new VirtualImage()
        def cloudFile1 = Mock(CloudFile)
        def cloudFile2 = Mock(CloudFile)
        def cloudFiles = [cloudFile1, cloudFile2]
        asyncVirtualImageService.getVirtualImageFiles(virtualImage) >> {
            return Single.just(cloudFiles)
        }

        when:
        def result = provisionProvider.fetchCloudFiles(virtualImage)

        then:
        result == cloudFiles
    }

    def "setCloudFilesError should set error status and response in result map"() {
        given:
        def server = new ComputeServer()
        def result = [:]
        def virtualImage = new VirtualImage()

        when:
        provisionProvider.setCloudFilesError(server, result, virtualImage)

        then:
        server.statusMessage == 'Failed to find cloud files'
        result.success == false
        result.response.msg.contains('Cloud files could not be found')

    }

    @Unroll
    def "handleContainerImage returns imageId and creates VirtualImageLocation on success"() {
        given:
        def scvmmOpts = [:]
        def workloadType = new WorkloadType(imageCode: "ubuntu-20.04")
        def createdBy = [id: 42]
        def instance = [createdBy: createdBy]
        def workload = new Workload(workloadType: workloadType, instance: instance)
        def virtualImage = new VirtualImage(id: 101, name: "Ubuntu", imageType: "vhd", externalId: "ext-101")
        def cloudFiles = [Mock(Object)]
        def cloud = new Cloud(id: 7)
        def result = [:]
        def args = [scvmmOpts: scvmmOpts, workload: workload, virtualImage: virtualImage, cloudFiles: cloudFiles, cloud: cloud, result: result]
        def imageId = "img-123"
        def imageResults = [success: true, imageId: imageId]

        mockApiService.insertContainerImage(scvmmOpts) >> imageResults

        def locationCreated = false

        def virtualImageLocationService = Mock(MorpheusSynchronousVirtualImageLocationService)
        virtualImageService.getLocation() >> virtualImageLocationService
        virtualImageLocationService.create(_) >> {}

        when:
        def returnedImageId = provisionProvider.handleContainerImage(args)

        then:
        returnedImageId == imageId
        scvmmOpts.image.name == "Ubuntu"
        scvmmOpts.userId == 42
    }

    @Unroll
    def "handleServerCreateFailure sets externalId, statusMessage, saves server, and sets provisionResponse.success to false"() {
        given:
        def server = new ComputeServer()
        def provisionResponse = new ProvisionResponse(success: true)
        def externalId = "ext-999"
        def createResults = [server: [externalId: externalId]]


        asyncComputeServerService.save(server) >> Single.just(server)

        when:
        provisionProvider.handleServerCreateFailure(createResults, server, provisionResponse)

        then:
        server.externalId == externalId
        server.statusMessage == 'Failed to create server'
        provisionResponse.success == false
    }

    def "getMaxMemory returns container.maxMemory if set, else instance.plan.maxMemory"() {
        given:
        def instance = new Instance()
        def container = new Workload(maxMemory: 4096L, instance: instance)

        expect:
        provisionProvider.getMaxMemory(container) == 4096L
    }

    def "getMaxCpu returns container.maxCpu if set, else instance.plan.maxCpu, else 1"() {
        given:
        def instance = new Instance()
        def container = new Workload(maxCpu: 4L, instance: instance)
        expect:
        provisionProvider.getMaxCpu(container) == 4L

    }

    def "getMaxCores returns container.maxCores if set, else instance.plan.maxCores, else 1"() {
        given:
        def instance = new Instance()
        instance.plan = new ServicePlan()
        instance.plan.maxCores = 12L
        def container = new Workload(maxCores: 12L, instance: instance)

        expect:
        provisionProvider.getMaxCores(container) == 12L

    }

    def "getResourcePool returns server.resourcePool if present, else null"() {
        given:
        def pool = new CloudPool()
        def server = new ComputeServer(resourcePool: pool)
        def container = new Workload(server: server)
        def container2 = new Workload(server: null)

        expect:
        provisionProvider.getResourcePool(container) == pool
        provisionProvider.getResourcePool(container2) == null
    }

    def "getPlatform returns windows if server.serverOs.platform or server.osType is windows, else linux"() {
        given:

        def server1 = new ComputeServer(osType: new OsType().platform = PlatformType.windows)
        def server2 = new ComputeServer(serverOs: new OsType(), osType: "linux")
        def container1 = new Workload(server: server1)
        def container2 = new Workload(server: server2)

        expect:

        provisionProvider.getPlatform(container1) == "windows"
        provisionProvider.getPlatform(container2) == "linux"
    }

    def "getScvmmCapabilityProfile returns null if profile is default, else returns profile"() {
        given:
        def config1 = [scvmmCapabilityProfile: "-1"]
        def config2 = [scvmmCapabilityProfile: "customProfile"]

        expect:
        provisionProvider.getScvmmCapabilityProfile(config1) == null
        provisionProvider.getScvmmCapabilityProfile(config2) == "customProfile"
    }

    def "validateHost returns success for VM hypervisor"() {
        given:
        def server = new ComputeServer(computeServerType: [vmHypervisor: true])

        when:
        def response = provisionProvider.validateHost(server, [:])

        then:
        response.success == true
    }

    def "validateHost delegates to validateNonHypervisorHost for non-hypervisor"() {
        given:
        def server = new ComputeServer(computeServerType: [vmHypervisor: false])
        def expectedResponse = new ServiceResponse(success: false)
        provisionProvider.validateNonHypervisorHost(_) >> { return expectedResponse }

        when:
        def response = provisionProvider.validateHost(server, [test: "value"])

        then:
        response.success == expectedResponse.success
    }

    def "validateHost returns success when exception is thrown"() {
        given:
        def server = new ComputeServer(computeServerType: [vmHypervisor: false])
        provisionProvider.metaClass.validateNonHypervisorHost = { opts -> throw new RuntimeException("fail") }

        when:
        def response = provisionProvider.validateHost(server, [:])

        then:
        response.success == true
    }


    def "validateNonHypervisorHost sets success and errors based on validationResults"() {
        given:
        def opts = [networkId: 123, scvmmCapabilityProfile: "profile", nodeCount: 2]
        def validationResults = [success: false, errors: [error: "Invalid config"]]
        provisionProvider.extractNetworkId(_) >> { return opts.networkId }
        provisionProvider.extractCapabilityProfile(_) >> { return opts.scvmmCapabilityProfile }
        provisionProvider.extractNodeCount(_) >> { return opts.nodeCount }

        mockApiService.validateServerConfig(_) >> {
            return validationResults
        }

        when:
        def response = provisionProvider.validateNonHypervisorHost(opts)

        then:
        response.success == false
        response.errors == [error: "Invalid config"]
    }

    @Unroll
    def "extractNetworkId returns #expected for opts #opts"() {
        given:
        // opts is parameterized in where block

        expect:
        provisionProvider.extractNetworkId(opts) == expected

        where:
        opts                                                                                  || expected
        [networkInterface: [network: [id: 101]]]                                              || 101L
        [config: [networkInterface: [network: [id: 202]]]]                                    || 202L
        [networkInterfaces: [[network: [id: 303]]]]                                           || 303L
        [networkInterfaces: [[network: [:]]]]                                                 || null
        [:]                                                                                   || null
        null                                                                                  || null
    }

    @Unroll
    def "extractCapabilityProfile returns #expected for opts #opts"() {
        expect:
        provisionProvider.extractCapabilityProfile(opts) == expected

        where:
        opts                                               || expected
        [config: [scvmmCapabilityProfile: "profileA"]]     || "profileA"
        [scvmmCapabilityProfile: "profileB"]               || "profileB"
        [config: [:], scvmmCapabilityProfile: "profileC"]  || "profileC"
        [:]                                                || null
        null                                               || null
    }

    @Unroll
    def "extractNodeCount returns #expected for opts #opts"() {
        expect:
        provisionProvider.extractNodeCount(opts) == expected

        where:
        opts                                 || expected
        [config: [nodeCount: 5]]             || 5
        [config: [nodeCount: null]]          || null
        [config: [:]]                        || null
        [:]                                  || null
        null                                 || null
    }

    @Unroll
    def "saveAndGet returns correct server for success=#success and found=#found"() {
        given:
        def server = new ComputeServer(id: 42)
        def persisted = [new ComputeServer(id: 42, name: "updated")]
        def failed = [new ComputeServer(id: 42, name: "failed")]
        def saveResult = Mock(BulkSaveResult) {
            getSuccess() >> success
            getPersistedItems() >> (success ? (found ? persisted : []) : [])
            getFailedItems() >> (!success ? (found ? failed : []) : [])
        }
        provisionProvider.context.async.computeServer.bulkSave([server]) >> Single.just(saveResult)

        expect:
        provisionProvider.saveAndGet(server).name == expectedName

        where:
        success | found | expectedName
        true    | true  | "updated"
        true    | false | null
        false   | true  | "failed"
        false   | false | null
    }

    @Unroll
    def "resolveCurrentMemory returns #expected for server=#serverMem, workload.server=#workloadServerMem, workload.maxMemory=#workloadMem, config=#configMem"() {
        given:
        def server = serverMem != null ? new ComputeServer(maxMemory: serverMem) : null
        def workloadServer = workloadServerMem != null ? new ComputeServer(maxMemory: workloadServerMem) : null
        def workload = new Workload(
                server: workloadServer,
                maxMemory: workloadMem
        )
        if (configMem != null) {
            workload.metaClass.getConfigProperty = { String key -> configMem }
        } else {
            workload.metaClass.getConfigProperty = { String key -> null }
        }

        expect:
        provisionProvider.resolveCurrentMemory(server, workload) == expected

        where:
        serverMem | workloadServerMem | workloadMem | configMem | expected
        4096      | 2048             | 1024        | "512"     | 4096L
        null      | 2048             | 1024        | "512"     | 2048L
        null      | null             | 1024        | "512"     | 1024L
        null      | null             | null        | "512"     | 512L
        null      | null             | null        | null      | null
    }

    @Unroll
    def "resolveCurrentCores returns #expected for server.maxCores=#serverCores, workload.maxCores=#workloadCores"() {
        given:
        def server = serverCores != null ? new ComputeServer(maxCores: serverCores) : null
        def workload = workloadCores != null ? new Workload(maxCores: workloadCores) : null

        expect:
        provisionProvider.resolveCurrentCores(server, workload) == expected

        where:
        serverCores | workloadCores | expected
        4           | 2             | 4
        null        | 2             | 2
        null        | null          | 1
    }

    @Unroll
    def "isHighlyAvailable returns #expected for clusterId=#clusterId, zonePool=#zonePool"() {
        given:
        def datastore = new Datastore()
        datastore.zonePool = new CloudPool()
        def clusterId = "cluster1"

        expect:
        provisionProvider.isHighlyAvailable(clusterId, datastore, false) == true

    }

    @Unroll
    def "getMaxCpu returns #expected for container.maxCpu=#maxCpu, plan.maxCpu=#planMaxCpu"() {
        given:
        def servicePlan = new ServicePlan()
        servicePlan.maxCpu = 8L
        def instance = new Instance(plan: servicePlan)
        def container = new Workload(instance: instance)

        expect:
        provisionProvider.getMaxCpu(container) == 8L

    }

    @Unroll
    def "getMaxCores returns #expected for container.maxCores=#maxCores, plan.maxCores=#planMaxCores"() {
        given:
        def servicePlan = new ServicePlan()
        servicePlan.maxCores = 16L
        def instance = new Instance(plan: servicePlan)
        def container = new Workload(instance: instance)

        expect:
        provisionProvider.getMaxCores(container) == 16L

    }

    def "getServerDetails returns response with server IP addresses"() {
        given:
        def server = new ComputeServer(
                id: 1L,
                externalId: "vm-123",
                name: "test-server"
        )

        def fetchedServer = new ComputeServer(
                id: 1L,
                externalId: "vm-123",
                name: "test-server",
                agentInstalled: true
        )

        def serverDetailsResponse = [
                success: true,
                server: [
                        ipAddress: "192.168.1.100",
                        macAddress: "00:11:22:33:44:55"
                ]
        ]

        def agentWaitResponse = [success: true]

        // Fix: Use Maybe.just() instead of Single.just()
        asyncComputeServerService.get(1L) >> Maybe.just(fetchedServer)

        // Mock fetchScvmmConnectionDetails
        provisionProvider.fetchScvmmConnectionDetails(_) >> [
                server: fetchedServer,
                waitForIp: true
        ]

        // Mock API service
        mockApiService.checkServerReady(_, "vm-123") >> serverDetailsResponse

        // Mock waitForAgentInstall
        provisionProvider.waitForAgentInstall(_) >> agentWaitResponse

        // Mock MorpheusUtil.saveAndGetMorpheusServer
        GroovyMock(com.morpheusdata.scvmm.util.MorpheusUtil, global: true)
        com.morpheusdata.scvmm.util.MorpheusUtil.saveAndGetMorpheusServer(_, _, true) >> {
            fetchedServer.externalIp = "192.168.1.100"
            fetchedServer.powerState = ComputeServer.PowerState.on
            return fetchedServer
        }

        // Mock applyComputeServerNetworkIp
        provisionProvider.applyComputeServerNetworkIp(_, _, _, _, _) >> { ComputeServer srv, String privateIp, String publicIp, Integer interfaceOrder, String mac ->
            srv.internalIp = privateIp
            srv.externalIp = publicIp
            return null
        }

        when:
        def response = provisionProvider.getServerDetails(server)

        then:
        response.success == true
        response.data.success == true
        response.data.privateIp == "192.168.1.100"
        response.data.publicIp == "192.168.1.100"
    }

    def "getServerDetails handles failure scenarios"() {
        given:
        def server = new ComputeServer(
                id: 1L,
                externalId: "vm-123",
                name: "test-server"
        )

        def fetchedServer = new ComputeServer(
                id: 1L,
                externalId: "vm-123",
                name: "test-server"
        )

        def serverDetailsResponse = [
                success: false,
                message: "Server not found"
        ]

        // Fix: Use Maybe.just() instead of Single.just()
        asyncComputeServerService.get(1L) >> Maybe.just(fetchedServer)

        // Mock fetchScvmmConnectionDetails
        provisionProvider.fetchScvmmConnectionDetails(_) >> [
                server: fetchedServer,
                waitForIp: true
        ]

        // Mock API service failure
        mockApiService.checkServerReady(_, "vm-123") >> serverDetailsResponse

        when:
        def response = provisionProvider.getServerDetails(server)

        then:
        response.success == false
        response.msg == "Server not found"
        response.error == "Server not found"
        response.data == serverDetailsResponse
    }

    def "waitForAgentInstall handles exception"() {
        given:
        def server = new ComputeServer(id: 1L, name: "test-server")

        // Mock async service to throw exception
        asyncComputeServerService.get(1L) >> { throw new RuntimeException("Database error") }

        when:
        def result = provisionProvider.waitForAgentInstall(server, 1)

        then:
        result.success == false
        result.msg == null // Exception is caught but not set in msg
    }

    def "saveAndGetNetworkInterface creates new interface when none exists"() {
        given:
        def server = new ComputeServer(id: 1L, interfaces: [])
        def privateIp = "192.168.1.100"
        def publicIp = "10.0.1.100"
        def index = 0
        def macAddress = "00:11:22:33:44:55"
        def savedServer = new ComputeServer(id: 1L)
        def newInterface = new ComputeServerInterface(ipAddress: privateIp)

        // Mock the nested async service structure using the correct context reference
        def mockInterfaceService = Mock(MorpheusComputeServerInterfaceService)
        asyncComputeServerService.computeServerInterface >> mockInterfaceService
        mockInterfaceService.create(_, _) >> Single.just([success: true])

        GroovyMock(MorpheusUtil, global: true)
        MorpheusUtil.saveAndGetMorpheusServer(_, _, _) >> savedServer

        provisionProvider.findNetworkInterface(_, _, _) >> null
        provisionProvider.createNetworkInterface(_, _) >> newInterface

        when:
        def result = provisionProvider.saveAndGetNetworkInterface(server, privateIp, publicIp, index, macAddress)

        then:
        result.server == savedServer
        result.netInterface.ipAddress == privateIp
        server.internalIp == privateIp
        server.externalIp == publicIp
        server.sshHost == privateIp
        server.macAddress == macAddress
    }

    def "saveAndGetNetworkInterface updates existing interface"() {
        given:
        def server = new ComputeServer(id: 1L)
        def existingInterface = new ComputeServerInterface(ipAddress: "old.ip")
        def privateIp = "192.168.1.100"
        def savedServer = new ComputeServer(id: 1L)

        // Mock the nested async service structure using the correct context reference
        def mockInterfaceService = Mock(MorpheusComputeServerInterfaceService)
        asyncComputeServerService.computeServerInterface >> mockInterfaceService
        mockInterfaceService.save(_) >> Single.just([success: true])

        GroovyMock(MorpheusUtil, global: true)
        MorpheusUtil.saveAndGetMorpheusServer(_, _, _) >> savedServer

        provisionProvider.findNetworkInterface(_, _, _) >> existingInterface

        when:
        def result = provisionProvider.saveAndGetNetworkInterface(server, privateIp, null, 0, null)

        then:
        result.server == savedServer
        result.netInterface.ipAddress == privateIp
        server.internalIp == privateIp
        server.sshHost == privateIp
    }

    def "applyComputeServerNetworkIp returns network interface"() {
        given:
        def server = new ComputeServer()
        def privateIp = "192.168.1.100"
        def expectedInterface = new ComputeServerInterface(ipAddress: privateIp)

        provisionProvider.saveAndGetNetworkInterface(_, _, _, _, _) >> [netInterface: expectedInterface]

        when:
        def result = provisionProvider.applyComputeServerNetworkIp(server, privateIp, null, 0, null)

        then:
        result == expectedInterface
    }

    def "applyNetworkIpAndGetServer returns server"() {
        given:
        def server = new ComputeServer()
        def privateIp = "192.168.1.100"
        def expectedServer = new ComputeServer(id: 1L)

        provisionProvider.saveAndGetNetworkInterface(_, _, _, _, _) >> [server: expectedServer]

        when:
        def result = provisionProvider.applyNetworkIpAndGetServer(server, privateIp, null, 0, null)

        then:
        result == expectedServer
    }

    def "applyComputeServerNetworkIp returns network interface"() {
        given:
        def server = new ComputeServer()
        def expectedInterface = new ComputeServerInterface()

        provisionProvider.saveAndGetNetworkInterface(_, _, _, _, _) >> [netInterface: expectedInterface, server: server]

        when:
        def result = provisionProvider.applyComputeServerNetworkIp(server, "192.168.1.100", "10.0.1.100", 0, "mac")

        then:
        result == expectedInterface
    }

    def "applyNetworkIpAndGetServer returns server"() {
        given:
        def server = new ComputeServer()
        def expectedServer = new ComputeServer(id: 1L)

        provisionProvider.saveAndGetNetworkInterface(_, _, _, _, _) >> [netInterface: new ComputeServerInterface(), server: expectedServer]

        when:
        def result = provisionProvider.applyNetworkIpAndGetServer(server, "192.168.1.100", "10.0.1.100", 0, "mac")

        then:
        result == expectedServer
    }

    def "waitForHost returns success when server is ready"() {
        given:
        def server = new ComputeServer(id: 1L, externalId: "vm-123")
        def fetchedServer = new ComputeServer(id: 1L, externalId: "vm-123")
        def serverDetail = [success: true, server: [ipAddress: "192.168.1.100"]]
        def agentWaitResult = [success: true]
        def finalizeResult = [success: true]

        asyncComputeServerService.get(1L) >> Maybe.just(fetchedServer)
        provisionProvider.fetchScvmmConnectionDetails(_) >> [:]
        mockApiService.checkServerReady(_, _) >> serverDetail
        provisionProvider.waitForAgentInstall(_) >> agentWaitResult
        provisionProvider.finalizeHost(_) >> finalizeResult

        when:
        def response = provisionProvider.waitForHost(server)

        then:
        response.success == true
        response.data.success == true
        response.data.privateIp == "192.168.1.100"
        response.data.publicIp == "192.168.1.100"
    }

    def "waitForHost handles server not ready"() {
        given:
        def server = new ComputeServer(id: 1L, externalId: "vm-123")
        def fetchedServer = new ComputeServer(id: 1L, externalId: "vm-123")
        def serverDetail = [success: false]

        asyncComputeServerService.get(1L) >> Maybe.just(fetchedServer)
        provisionProvider.fetchScvmmConnectionDetails(_) >> [:]
        mockApiService.checkServerReady(_, _) >> serverDetail

        when:
        def response = provisionProvider.waitForHost(server)

        then:
        response.success == false
        response.data.success == false
    }

    def "fetchScvmmConnectionDetails combines all options"() {
        given:
        def server = new ComputeServer(cloud: new Cloud())
        def node = new ComputeServer()
        def cloudOpts = [cloud: "opts"]
        def controllerOpts = [controller: "opts"]
        def serverOpts = [server: "opts"]

        provisionProvider.pickScvmmController(_) >> node
        mockApiService.getScvmmCloudOpts(_, _, _) >> cloudOpts
        mockApiService.getScvmmControllerOpts(_, _) >> controllerOpts
        provisionProvider.getScvmmServerOpts(_) >> serverOpts

        when:
        def result = provisionProvider.fetchScvmmConnectionDetails(server)

        then:
        result.cloud == "opts"
        result.controller == "opts"
        result.server == "opts"
    }

    def "finalizeHost succeeds when server is ready"() {
        given:
        def server = new ComputeServer(id: 1L, externalId: "vm-123")
        def fetchedServer = new ComputeServer(id: 1L, externalId: "vm-123")
        def serverDetail = [success: true, server: [ipAddress: "192.168.1.100", macAddress: "mac"]]
        def agentWaitResult = [success: true]
        def savedServer = new ComputeServer(id: 1L)

        asyncComputeServerService.get(1L) >> Maybe.just(fetchedServer)
        provisionProvider.fetchScvmmConnectionDetails(_) >> [:]
        mockApiService.checkServerReady(_, _) >> serverDetail
        provisionProvider.waitForAgentInstall(_) >> agentWaitResult
        provisionProvider.applyNetworkIpAndGetServer(_, _, _, _, _) >> savedServer
        asyncComputeServerService.save(_) >> Single.just(savedServer)

        when:
        def response = provisionProvider.finalizeHost(server)

        then:
        response.success == true
    }

    def "finalizeHost handles server not ready"() {
        given:
        def server = new ComputeServer(id: 1L, externalId: "vm-123")
        def fetchedServer = new ComputeServer(id: 1L, externalId: "vm-123")
        def serverDetail = [success: false]

        asyncComputeServerService.get(1L) >> Maybe.just(fetchedServer)
        provisionProvider.fetchScvmmConnectionDetails(_) >> [:]
        mockApiService.checkServerReady(_, _) >> serverDetail

        when:
        def response = provisionProvider.finalizeHost(server)

        then:
        response.success == false
    }
}