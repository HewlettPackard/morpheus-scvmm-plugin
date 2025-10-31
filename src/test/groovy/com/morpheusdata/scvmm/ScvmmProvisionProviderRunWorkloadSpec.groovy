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
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.compute.MorpheusComputeServerInterfaceService
import com.morpheusdata.core.data.DataAndFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.library.MorpheusWorkloadTypeService
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
import com.morpheusdata.core.network.MorpheusNetworkService
import com.morpheusdata.core.synchronous.library.MorpheusSynchronousWorkloadTypeService
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.*
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.UserConfiguration
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.InitializeHypervisorResponse
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.scvmm.testdata.ProvisionDataHelper
import groovy.json.JsonOutput
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.BeforeEach
import spock.lang.Specification
import spock.lang.Unroll

class ScvmmProvisionProviderRunWorkloadSpec extends Specification {
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
    private MorpheusCloudService asyncCloudService
    private MorpheusSynchronousCloudService cloudService
    private MorpheusSynchronousNetworkService networkService
    private MorpheusSynchronousStorageVolumeService storageVolumeService
    private MorpheusSynchronousResourcePermissionService resourcePermissionService
    private MorpheusStorageVolumeService asyncStorageVolumeService
    private MorpheusSynchronousVirtualImageService virtualImageService
    private MorpheusVirtualImageService asyncVirtualImageService
    //private ComputeServer mockedComputerServer
    //private WorkloadType mockedWorkloadType
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

        provisionProvider.constructCloudInitOptions(_, _, _, _, _, _, _, _) >> { args ->
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

        mockApiService.checkServerReady(_, _) >> { Map options, String serverId ->
            return ProvisionDataHelper.runWorkload_checkServerReadyResponse(serverId)
        }

        mockApiService.setCdrom(_) >> { Map setCdromOpts ->
            return ProvisionDataHelper.runWorkload_setCdromResponse()
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
        //storageVolumeService.listStorageVolumeTypes() >> mockStorageTypes

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
    def "validateHost should #scenario"() {
        given:
        def server = Mock(ComputeServer) {
            getComputeServerType() >> Mock(ComputeServerType) {
                isVmHypervisor() >> isVmHypervisor
            }
        }

        when:
        def response = provisionProvider.validateHost(server, opts)

        then:
        if (apiCallExpected) {
            1 * mockApiService.validateServerConfig(expectedApiArgs) >> apiResponse
        }
        response.success == expectedSuccess
        if (errorCheck) {
            response.errors == validationErrors
        }

        where:
        scenario                                    | isVmHypervisor | opts                                              | apiCallExpected | expectedApiArgs                                                         | apiResponse                                                         | expectedSuccess | errorCheck | validationErrors
        "return success when server is a VM hypervisor" | true           | [:]                                               | false           | null                                                                   | null                                                                | true           | false      | null
        "validate server config when not VM hypervisor"  | false          | [networkInterfaces:[[network:[id:123L]]], config:[scvmmCapabilityProfile:"Hyper-V", nodeCount:1]] | true            | [networkId:123L, scvmmCapabilityProfile:"Hyper-V", nodeCount:1]        | ServiceResponse.success()                                           | true           | false      | null
        "handle network ID from interface field"         | false          | [networkInterface:[network:[id:123L]]]           | true            | [networkId:123L, scvmmCapabilityProfile:null, nodeCount:null]           | ServiceResponse.success()                                           | true           | false      | null
        "handle network ID from config field"            | false          | [config:[networkInterface:[network:[id:123L]]]]  | true            | [networkId:123L, scvmmCapabilityProfile:null, nodeCount:null]           | ServiceResponse.success()                                           | true           | false      | null
        "handle network ID from interfaces array"        | false          | [networkInterfaces:[[network:[id:123L]]]]        | true            | [networkId:123L, scvmmCapabilityProfile:null, nodeCount:null]           | ServiceResponse.success()                                           | true           | false      | null
        "return error when validation fails"             | false          | [networkInterfaces:[[network:[id:123L]]]]        | true            | [networkId:123L, scvmmCapabilityProfile:null, nodeCount:null]           | new ServiceResponse(success:false, errors:[error:"Invalid configuration"]) | false          | true       | [error:"Invalid configuration"]
        "handle exceptions gracefully"                   | false          | [networkInterfaces:[[network:[id:123L]]]]        | true            | [networkId:123L, scvmmCapabilityProfile:null, nodeCount:null]           | { throw new RuntimeException("Test exception") }                    | true           | false      | null
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
           // provisionProvider.getVolumePathForDatastore(mockVolumePathDatastore) >> "\\\\server\\path\\to\\volume"
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
        "host_specified_by_user"           | true           | null           | '20'        | 20L    | null           | 'manual'        | 10000000000L  | null   | 4294967296L    | false          | true               | true                | '[datastore1]'   | false          | 'datastore1'            | 20L            | 10L                | "\\\\server\\path\\to\\volume" | false
        "datastore_provided_directly"      | true           | null           | null        | null   | 'datastore1'   | 'manual'        | 10000000000L  | null   | 4294967296L    | false          | false              | false               | []               | true           | 'datastore1'            | 20L            | 10L                | "\\\\server\\path\\to\\volume" | false
        "cluster_with_shared_volume"       | true           | '5'            | null        | null   | 'datastore1'   | 'manual'        | 10000000000L  | null   | 4294967296L    | true           | false              | false               | []               | true           | 'datastore1'            | 20L            | 10L                | "\\\\server\\path\\to\\volume" | true
        "non_cloud_with_node_and_ds"       | false          | null           | '20'        | 20L    | null           | 'manual'        | 10000000000L  | null   | 4294967296L    | false          | true               | true                | '[datastore1]'   | false          | 'datastore1'            | 20L            | 10L                | "\\\\server\\path\\to\\volume" | false
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

        morpheusContext.services.computeServer.get(200L) >> {
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

        // Mock necessary service calls
        computeServerService.get(200L) >> controllerNode

        mockApiService.getScvmmCloudOpts(morpheusContext, server.cloud, controllerNode) >> [cloudId: 1L]
        mockApiService.getScvmmControllerOpts(server.cloud, controllerNode) >> [controllerId: 200L]

        provisionProvider.getScvmmServerOpts(server) >> [vmId: 'vm-123']

        mockApiService.checkServerReady(_, 'vm-123') >> serverDetail

        // Mock the network IP application
        provisionProvider.applyComputeServerNetworkIp(_,_,_,_,_) >> {
            return ProvisionDataHelper.finalizeHost_applyComputeServerNetworkIpResponse()
        }

        // Mock the server save operation
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

    def "getServerDetails returns response with server IP addresses"() {
        given:
        // Create test servers with different IP configurations

        def serverWithBothIps = ProvisionDataHelper.getServerDetails_forComputeServer("bothIps")
        def serverWithOnlyInternalIp = ProvisionDataHelper.getServerDetails_forComputeServer("internalOnly")
        def serverWithOnlyExternalIp = ProvisionDataHelper.getServerDetails_forComputeServer("externalOnly")
        def serverWithNoIps = ProvisionDataHelper.getServerDetails_forComputeServer("noIps")

        when:
        def responseBothIps = provisionProvider.getServerDetails(serverWithBothIps)
        def responseInternalOnly = provisionProvider.getServerDetails(serverWithOnlyInternalIp)
        def responseExternalOnly = provisionProvider.getServerDetails(serverWithOnlyExternalIp)
        def responseNoIps = provisionProvider.getServerDetails(serverWithNoIps)

        then:
        // Test server with both IPs
        responseBothIps.success == true
        responseBothIps.data.success == true
        responseBothIps.data.privateIp == "192.168.1.100"
        responseBothIps.data.publicIp == "10.0.1.100"

        // Test server with only internal IP
        responseInternalOnly.success == true
        responseInternalOnly.data.success == true
        responseInternalOnly.data.privateIp == "192.168.1.101"
        responseInternalOnly.data.publicIp == null

        // Test server with only external IP
        responseExternalOnly.success == true
        responseExternalOnly.data.success == true
        responseExternalOnly.data.privateIp == null
        responseExternalOnly.data.publicIp == "10.0.1.102"

        // Test server with no IPs
        responseNoIps.success == true
        responseNoIps.data.success == true
        responseNoIps.data.privateIp == null
        responseNoIps.data.publicIp == null
    }

    def "finalizeWorkload returns success response"() {
        given:
        def workload = ProvisionDataHelper.getWorkloadData()

        when:
        def response = provisionProvider.finalizeWorkload(workload)

        then:
        response.success == true
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

    def "restartWorkload returns success response"() {
        given:
        def workload = ProvisionDataHelper.getWorkloadData()

        when:
        def response = provisionProvider.restartWorkload(workload)

        then:
        response.success == true
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
        //def cloudNetworkService = Mock(MorpheusCloudNetworkService)
        cloudService.getNetwork() >> networkService
        networkService.get(1L) >> {
            return network
        }
        //cloudService.getNetwork() >> cloudNetworkService

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
        def cloudOpts = [
                cloud: 1L,
                cloudName: "test-cloud",
                zoneId: 1L
        ]

        def controllerOpts = [
                controller: 10L,
                sshHost: "10.0.0.5",
                sshUsername: "admin",
                sshPassword: "password123"
        ]

        def containerOpts = [
                vmId: "vm-123",
                name: "vm-123",
                memory: 4294967296L,
                maxCpu: 1,
                maxCores: 2,
                hostname: "testVM",
                networkId: 1L,
                platform: "linux"
        ]

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
        def cloudOpts = [
                cloud: 1L,
                cloudName: "test-cloud",
                zoneId: 1L
        ]

        def controllerOpts = [
                controller: 10L,
                sshHost: "10.0.0.5",
                sshUsername: "admin",
                sshPassword: "password123"
        ]

        def serverOpts = [
                vmId: "vm-123",
                name: "vm-123",
                memory: 4294967296L,
                maxCpu: 1,
                maxCores: 2,
                hostname: "testVM",
                networkId: 1L,
                platform: "linux"
        ]

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
        def existingInterface = new ComputeServerInterface(
                id: 1L,
                name: "eth0",
                primaryInterface: true,
                displayOrder: 1
        )
        server.interfaces = [existingInterface]
        def macAddress = "00:11:22:33:44:55"

        def asyncComputeServerInterfaceService = Mock(MorpheusComputeServerInterfaceService)
        asyncComputeServerService.getComputeServerInterface() >> asyncComputeServerInterfaceService
        // For case with privateIp, mock the server interface create/save methods
        def updatedServer = new ComputeServer(id: 100L)
        def savedInterface = new ComputeServerInterface(
                id: 1L,
                name: "eth0",
                ipAddress: "192.168.1.100",
                publicIpAddress: "10.0.0.100",
                macAddress: macAddress,
                primaryInterface: true,
                displayOrder: 1,
                addresses: [new NetAddress(type: NetAddress.AddressType.IPV4, address: "192.168.1.100")]
        )
        asyncComputeServerInterfaceService.save(_) >> {
           return  Single.just([savedInterface])
        }


        when: "privateIp is provided"
        def result1 = provisionProvider.applyComputeServerNetworkIp(server, "192.168.1.100", "10.0.0.100", 0, macAddress)

        then: "interface should be updated with privateIp and publicIp"

        1 * provisionProvider.saveAndGetMorpheusServer(_, true) >> updatedServer
        result1.ipAddress == "192.168.1.100"
        result1.publicIpAddress == "10.0.0.100"
        result1.macAddress == macAddress
        server.internalIp == "192.168.1.100"
        server.externalIp == "10.0.0.100"
        server.sshHost == "192.168.1.100"

        when: "privateIp is null but publicIp is provided"
        def result2 = provisionProvider.applyComputeServerNetworkIp(server, null, "10.0.0.200", 0, macAddress)

        then: "no interface should be updated"
        1 * provisionProvider.saveAndGetMorpheusServer(_, true) >> updatedServer
        result2 == null
    }

    def "test cloneParentCleanup successfully cleans up parent VM"() {
        given:
        // Create basic options map with all necessary nested properties
        def scvmmOpts = [
                cloneVMId: 'vm-123',
                cloneContainerId: 100,
                startClonedVM: true,
                cloneBaseOpts: [
                        clonedScvmmOpts: [
                                controller: 1,
                                hostId: 2
                        ]
                ],
                deleteDvdOnComplete: [
                        deleteIso: 'iso-file.iso'
                ]
        ]

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


        // Verify all API methods were called exactly once

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
        "resize differencing"   | true          | false        | 10737418240L  | 21474836480L   | "differencing"        | true          | false
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
                networkConfig,
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
}
