package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusComputeTypeSetService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusProcessService
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.MorpheusUtils
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.core.MorpheusStorageVolumeService
import com.morpheusdata.core.MorpheusVirtualImageService
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.MorpheusHypervisorService
import com.morpheusdata.core.MorpheusOsTypeService
import com.morpheusdata.core.library.MorpheusWorkloadTypeService
import com.morpheusdata.core.network.MorpheusNetworkService
import com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService
import com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.synchronous.library.MorpheusSynchronousWorkloadTypeService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OsType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.scvmm.helper.morpheus.types.StorageVolumeTypeHelper
import com.morpheusdata.scvmm.sync.CloudCapabilityProfilesSync
import com.morpheusdata.scvmm.sync.ClustersSync
import com.morpheusdata.scvmm.sync.DatastoresSync
import com.morpheusdata.scvmm.sync.HostSync
import com.morpheusdata.scvmm.sync.IpPoolsSync
import com.morpheusdata.scvmm.sync.IsolationNetworkSync
import com.morpheusdata.scvmm.sync.NetworkSync
import com.morpheusdata.scvmm.sync.RegisteredStorageFileSharesSync
import com.morpheusdata.scvmm.sync.TemplatesSync
import com.morpheusdata.scvmm.sync.VirtualMachineSync
import io.reactivex.rxjava3.core.Maybe
import org.junit.jupiter.api.BeforeEach
import spock.lang.Specification
import spock.lang.Unroll

class ScvmmCloudProviderSpec  extends Specification {
    private MorpheusContext morpheusContext
    private ScvmmPlugin plugin
    private ScvmmCloudProvider cloudProvider
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
    private MorpheusSynchronousNetworkService networkService
    private MorpheusSynchronousStorageVolumeService storageVolumeService
    private MorpheusSynchronousResourcePermissionService resourcePermissionService
    private MorpheusStorageVolumeService asyncStorageVolumeService
    private MorpheusSynchronousVirtualImageService virtualImageService
    private MorpheusVirtualImageService asyncVirtualImageService
    private MorpheusAsyncServices morpheusAsyncServices

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

        morpheusContext.getAsync() >> morpheusAsyncServices
        morpheusContext.getServices() >> morpheusServices

        mockApiService = Mock(ScvmmApiService)
        cloudProvider = Spy(ScvmmCloudProvider, constructorArgs: [plugin, morpheusContext])
        cloudProvider.apiService = mockApiService

    }

    @Unroll
    def "validate returns success response when all validations pass"() {
        given:
        // Create cloud info with basic configuration
        def cloudInfo = new Cloud(id: 1L, name: "test-scvmm-cloud")
        ValidateCloudRequest validateCloudRequest = new ValidateCloudRequest("testuser",
                "testpass","password", [:])
        def zoneConfig = [host: "scvmm-host.example.com", workingPath: "/var/morpheus/scvmm",
                          diskPath: "/var/morpheus/scvmm/disks", libraryShare: "\\\\server\\share", installAgent: "true"]
        def scvmmOpts = [controller: 1L, zoneId: cloudInfo.id, cloudName: cloudInfo.name]
        def vmSwitchesResponse = [success: true, data: [[id: "switch-1", name: "External Switch"],
                        [id: "switch-2", name: "Internal Switch"]]]
        def controllerValidationResult = [success: true]
        cloudInfo.setConfigMap(zoneConfig)
        mockApiService.getScvmmZoneOpts(_, _) >> {
            return scvmmOpts
        }
        GroovyMock(MorpheusUtils, global: true)
        MorpheusUtils.parseBooleanConfig("true") >> {
            return true
        }
        cloudProvider.validateRequiredConfigFields(['host', 'workingPath', 'diskPath', 'libraryShare'], zoneConfig) >> {
            return [:]
        }
        cloudProvider.validateSharedController(cloudInfo) >> controllerValidationResult

        mockApiService.listClouds(_ as LinkedHashMap) >> {return vmSwitchesResponse}

        when:
        def result = cloudProvider.validate(cloudInfo, validateCloudRequest)

        then:
        // Verify successful response
        result.success == true
        result.msg == null
        result.errors.size() == 0
        result.data.size() == 0

        // Verify API calls were made
        1 * mockApiService.getScvmmZoneOpts(_, _) >> scvmmOpts
        1 * mockApiService.listClouds(_ as LinkedHashMap) >> vmSwitchesResponse

        // Verify validation methods were called
        1 * cloudProvider.validateRequiredConfigFields(
                ['host', 'workingPath', 'diskPath', 'libraryShare'],
                zoneConfig
        ) >> [:]
        1 * cloudProvider.validateSharedController(cloudInfo) >> controllerValidationResult
    }

    @Unroll
    def "initializeCloud returns #expectedResult for #scenario"() {
        given:
        def cloudInfo = cloudEnabled ? new Cloud(
                id: 1L,
                name: "test-scvmm-cloud",
                code: "test-scvmm",
                enabled: true
        ) : (cloudExists ? new Cloud(
                id: 1L,
                name: "test-scvmm-cloud",
                code: "test-scvmm",
                enabled: false
        ) : null)

        def initHypervisorResponse = [success: initHypervisorSuccess]

        cloudProvider.refresh(_) >> {}
        cloudProvider.initializeHypervisor(_) >> {return initHypervisorResponse}

        when:
        def result = cloudProvider.initializeCloud(cloudInfo)

        then:
        result.success == expectedSuccess
        result.msg == expectedMessage

        // Verify method call counts based on scenario
        if (cloudExists && cloudEnabled) {
            1 * cloudProvider.initializeHypervisor(cloudInfo) >> initHypervisorResponse
            if (initHypervisorSuccess) {
                1 * cloudProvider.refresh(cloudInfo)
            } else {
                0 * cloudProvider.refresh(_)
            }
        } else {
            0 * cloudProvider.initializeHypervisor(_)
            0 * cloudProvider.refresh(_)
        }

        where:
        scenario                           | cloudExists | cloudEnabled | initHypervisorSuccess | expectedSuccess | expectedMessage
        "successful initialization"        | true        | true         | true                  | true           | null
        "failed hypervisor initialization" | true        | true         | false                 | true           | null

        expectedResult = expectedSuccess ? "success" : "failure"
    }

    @Unroll
    def "initializeCloud handles exceptions gracefully"() {
        given:
        def cloudInfo = new Cloud(id: 1L, name: "test-scvmm-cloud", code: "test-scvmm", enabled: true)

        // Mock initializeHypervisor to throw an exception
        cloudProvider.initializeHypervisor(_) >> {
            throw new RuntimeException("Hypervisor initialization failed")
        }

        when:
        def result = cloudProvider.initializeCloud(cloudInfo)

        then:
        // Should still return a response object even when exception occurs
        result != null
        result.success == false // ServiceResponse.prepare() creates success=false by default

        // Verify methods were called
        1 * cloudProvider.initializeHypervisor(cloudInfo) >> {
            throw new RuntimeException("Hypervisor initialization failed")
        }
        0 * cloudProvider.refresh(_) // refresh should not be called due to exception
    }

    @Unroll
    def "initializeHypervisor returns #expectedResult for #scenario"() {
        given:
        def cloud = new Cloud(id: 1L, name: "test-scvmm-cloud", account: new Account(id: 100L))

        // Set cloud config properties
        cloud.setConfigProperty('sharedController', sharedController)
        cloud.setConfigProperty('host', 'scvmm-host.example.com')
        cloud.setConfigProperty('workingPath', '/var/morpheus/scvmm')
        cloud.setConfigProperty('diskPath', '/var/morpheus/scvmm/disks')

        // Mock API service responses
        def initializationOpts = [controller: 1L, zoneId: cloud.id, host: 'scvmm-host.example.com']

        def serverInfo = serverInfoSuccess ? [
                success: true,
                hostname: 'scvmm-controller-01',
                osName: 'Microsoft Windows Server 2019 Standard'
        ] : [
                success: false,
                hostname: null,
                osName: null
        ]

        def versionCode = 'windows.server.2019'
        def maxStorage = 1099511627776L // 1TB
        def maxMemory = 17179869184L // 16GB

        // Mock compute server type and OS type
        def computeServerType = new ComputeServerType(id: 200L, code: 'scvmmController', name: 'SCVMM Controller')

        def osType = new OsType(id: 300L, code: versionCode, name: 'Windows Server 2019')

        // Create existing server if needed for test scenario
        def existingServer = existingServerFound ? new ComputeServer(
                id: 400L,
                name: 'scvmm-controller-01',
                hostname: 'scvmm-controller-01',
                account: cloud.account,
                cloud: cloud
        ) : null

        // Create new server for creation scenario
        def newServer = new ComputeServer(id: existingServerFound ? 400L : 500L,
                account: cloud.account, cloud: cloud, computeServerType: computeServerType,
                name: serverInfo.hostname ?: 'scvmm-controller-01')

        // Mock hypervisor service
        def hypervisorService = Mock(MorpheusHypervisorService)

        asyncCloudService.findComputeServerTypeByCode('scvmmController') >> {
            return Maybe.just(computeServerType)
        }

        morpheusAsyncServices.getHypervisorService() >> {
            return hypervisorService
        }

        asyncOsTypeService.find({ DataQuery query ->
            query.filters.any { it.name == 'code' && it.value == versionCode }
        }) >> {
            return Maybe.just(osType)
        }

        // Mock API service methods
        mockApiService.getScvmmInitializationOpts(cloud) >> {
            return initializationOpts
        }
        mockApiService.getScvmmServerInfo(initializationOpts) >> {
            return serverInfo
        }
        mockApiService.extractWindowsServerVersion(serverInfo.osName) >> {
            return versionCode
        }
        mockApiService.getUsername(cloud) >> {
            return 'admin'
        }
        mockApiService.getPassword(cloud) >> {
            return 'password123'
        }

        // Mock compute server service methods
        computeServerService.find({ DataQuery query ->
            query.filters.any { it.name == 'zone.id' && it.value == cloud.id } &&
                    query.filters.any { it instanceof DataOrFilter }
        }) >> existingServer

        computeServerService.create(_) >> { ComputeServer server ->
            server.id = 500L
            return server
        }

        computeServerService.save(_) >> { ComputeServer server ->
            return server
        }

        // Mock provider methods
        cloudProvider.getMaxStorage(serverInfo) >> {
            return maxStorage
        }
        cloudProvider.getMaxMemory(serverInfo) >> {
            return maxMemory
        }

        when:
        def result = cloudProvider.initializeHypervisor(cloud)

        then:
        result.success == expectedSuccess

        // Verify API service calls for non-shared controller scenarios
        if (!sharedController && serverInfoSuccess) {
            1 * mockApiService.getScvmmInitializationOpts(cloud) >> initializationOpts
            1 * mockApiService.getScvmmServerInfo(initializationOpts) >> serverInfo
            1 * mockApiService.extractWindowsServerVersion(serverInfo.osName) >> versionCode
            1 * mockApiService.getUsername(cloud) >> 'admin'
            1 * mockApiService.getPassword(cloud) >> 'password123'

            // Verify compute server operations
            1 * computeServerService.find(_) >> existingServer

            if (!existingServerFound) {
                1 * computeServerService.create(_) >> newServer
            }

            1 * computeServerService.save(_) >> newServer

            // Verify provider method calls
            1 * cloudProvider.getMaxStorage(serverInfo) >> maxStorage
            1 * cloudProvider.getMaxMemory(serverInfo) >> maxMemory

            // Verify hypervisor initialization
            1 * hypervisorService.initialize(_)
        } else if (sharedController) {
            // For shared controller, no API calls should be made
            0 * mockApiService.getScvmmInitializationOpts(_)
            0 * mockApiService.getScvmmServerInfo(_)
            0 * computeServerService.find(_)
            0 * computeServerService.create(_)
            0 * computeServerService.save(_)
            0 * hypervisorService.initialize(_)
        }

        where:
        scenario                              | sharedController | serverInfoSuccess | existingServerFound | expectedSuccess
        "shared controller enabled"           | true            | false            | false              | true
        "new server creation successful"      | false           | true             | false              | true
        "existing server update successful"   | false           | true             | true               | true

        expectedResult = expectedSuccess ? "success" : "failure"
    }

    @Unroll
    def "getMaxMemory returns #expectedValue for #scenario"() {
        given:
        def serverInfo = input

        when:
        def result = cloudProvider.getMaxMemory(serverInfo)

        then:
        result == expectedValue
        noExceptionThrown()

        where:
        scenario                           | input                              | expectedValue
        "valid memory string"              | [memory: "8589934592"]             | 8589934592L
        "valid memory integer"             | [memory: 4294967296]               | 4294967296L
        "valid memory long"                | [memory: 17179869184L]             | 17179869184L
        "zero memory"                      | [memory: "0"]                      | 0L
        "null memory"                      | [memory: null]                     | 0L
        "empty string memory"              | [memory: ""]                       | 0L
        "whitespace only memory"           | [memory: "   "]                    | 0L
        "invalid memory string"            | [memory: "invalid"]                | 0L
        "decimal memory string"            | [memory: "123.45"]                 | 0L
        "memory with units"                | [memory: "8GB"]                    | 0L
        "negative memory"                  | [memory: "-1024"]                  | -1024L
        "null serverInfo"                  | null                               | 0L
        "empty serverInfo"                 | [:]                                | 0L
        "serverInfo without memory key"    | [other: "value"]                   | 0L
        "NumberFormatException case"       | [memory: "not_a_number"]           | 0L
        "large memory values"              | [memory: Long.MAX_VALUE.toString()]| Long.MAX_VALUE
    }

    @Unroll
    def "getMaxStorage returns #expectedValue for #scenario"() {
        given:
        def serverInfo = input

        when:
        def result = cloudProvider.getMaxStorage(serverInfo)

        then:
        result == expectedValue
        noExceptionThrown()

        where:
        scenario                           | input                              | expectedValue
        "valid disks string"               | [disks: "1099511627776"]           | 1099511627776L
        "valid disks integer"              | [disks: 536870912000]              | 536870912000L
        "valid disks long"                 | [disks: 2199023255552L]            | 2199023255552L
        "zero disks"                       | [disks: "0"]                       | 0L
        "null disks"                       | [disks: null]                      | 0L
        "empty string disks"               | [disks: ""]                        | 0L
        "whitespace only disks"            | [disks: "   "]                     | 0L
        "invalid disks string"             | [disks: "invalid"]                 | 0L
        "decimal disks string"             | [disks: "123.45"]                  | 0L
        "disks with units"                 | [disks: "1TB"]                     | 0L
        "negative disks"                   | [disks: "-1024"]                   | -1024L
        "null serverInfo"                  | null                               | 0L
        "empty serverInfo"                 | [:]                                | 0L
        "serverInfo without disks key"     | [other: "value"]                   | 0L
        "NumberFormatException case"       | [disks: "not_a_number"]            | 0L
        "large disks values"               | [disks: Long.MAX_VALUE.toString()] | Long.MAX_VALUE
    }

    @Unroll
    def "cloud provider methods return expected values for #method"() {
        given:
        def cloudInfo = method == 'deleteCloud' ? new Cloud(id: 1L, name: "test-cloud") : null

        when:
        def result = method == 'deleteCloud' ?
                cloudProvider.deleteCloud(cloudInfo) :
                cloudProvider."$method"()

        then:
        if (expectedType == 'boolean') {
            result == expectedValue
        } else if (expectedType == 'ServiceResponse') {
            result.success == expectedValue
            result instanceof ServiceResponse
        } else if (expectedType == 'Collection') {
            result instanceof Collection
            result.size() == expectedValue
        } else if (expectedType == 'String') {
            result == expectedValue
        } else if (expectedType == 'Icon') {
            result instanceof Icon
            result.path == expectedValue.path
            result.darkPath == expectedValue.darkPath
        }

        where:
        method                        | expectedType   | expectedValue
        'deleteCloud'                 | 'ServiceResponse' | true
        'hasComputeZonePools'        | 'boolean'      | true
        'hasNetworks'                | 'boolean'      | true
        'hasFolders'                 | 'boolean'      | false
        'hasDatastores'              | 'boolean'      | true
        'hasBareMetal'               | 'boolean'      | false
        'hasCloudInit'               | 'boolean'      | true
        'supportsDistributedWorker'  | 'boolean'      | false
        'getStorageControllerTypes'  | 'Collection'   | 0
        'getAvailableBackupProviders'| 'Collection'   | 0
        'getNetworkTypes'            | 'Collection'   | 0
        'getSubnetTypes'             | 'Collection'   | 0
        'getDescription'             | 'String'       | 'System Center Virtual Machine Manager'
        'getIcon'                    | 'Icon'         | [path: 'scvmm.svg', darkPath: 'scvmm-dark.svg']
        'getCircularIcon'            | 'Icon'         | [path: 'scvmm-circular.svg', darkPath: 'scvmm-circular.svg']
    }

    @Unroll
    def "test getName returns correct provider name"() {
        when:
        def result = cloudProvider.getName()

        then:
        result == 'SCVMM'
    }

    @Unroll
    def "test getDefaultProvisionTypeCode returns correct provider code"() {
        when:
        def result = cloudProvider.getDefaultProvisionTypeCode()

        then:
        result == ScvmmProvisionProvider.PROVIDER_CODE
    }

    @Unroll
    def "test getPlugin returns the injected plugin instance"() {
        when:
        def result = cloudProvider.getPlugin()

        then:
        result == plugin
        result instanceof ScvmmPlugin
    }

    def "getComputeServerTypes returns all expected server types with correct properties and host option types"() {
        given:
        def mockSshOptions = [Mock(OptionType)]
        cloudProvider.createSshOptions() >> mockSshOptions

        when:
        def result = cloudProvider.getComputeServerTypes()

        then:
        result.size() == 10
        result instanceof Collection<ComputeServerType>

        // Verify all expected server type codes are present
        def serverTypeCodes = result.collect { it.code }
        serverTypeCodes.containsAll(['unmanaged', 'scvmmController', 'scvmmHypervisor', 'scvmmWindows',
                                     'scvmmVm', 'scvmmWindowsVm', 'scvmmUnmanaged', 'scvmmLinux',
                                     'scvmmKubeMaster', 'scvmmKubeWorker'])

        // Verify unmanaged server type
        def unmanagedType = result.find { it.code == 'unmanaged' }
        unmanagedType.name == 'Linux VM'
        unmanagedType.platform.toString() == 'linux'
        unmanagedType.managed == false
        unmanagedType.creatable == true
        unmanagedType.optionTypes != null

        // Verify SCVMM Controller server type
        def controllerType = result.find { it.code == 'scvmmController' }
        controllerType.name == 'SCVMM Manager'
        controllerType.platform.toString() == 'windows'
        controllerType.vmHypervisor == true
        controllerType.agentType == ComputeServerType.AgentType.node
        controllerType.provisionTypeCode == 'scvmm-hypervisor'

        // Verify SCVMM Hypervisor server type
        def hypervisorType = result.find { it.code == 'scvmmHypervisor' }
        hypervisorType.name == 'SCVMM Hypervisor'
        hypervisorType.platform.toString() == 'windows'
        hypervisorType.vmHypervisor == true
        hypervisorType.provisionTypeCode == 'scvmm-hypervisor'

        // Verify SCVMM Windows Node server type
        def windowsType = result.find { it.code == 'scvmmWindows' }
        windowsType.name == 'SCVMM Windows Node'
        windowsType.platform.toString() == 'windows'
        windowsType.managed == true
        windowsType.hasAutomation == true
        windowsType.reconfigureSupported == true
        windowsType.guestVm == true

        // Verify SCVMM VM server type
        def vmType = result.find { it.code == 'scvmmVm' }
        vmType.name == 'SCVMM Instance'
        vmType.platform.toString() == 'linux'
        vmType.agentType == ComputeServerType.AgentType.guest
        vmType.guestVm == true

        // Verify SCVMM Docker Host server type and its host option type
        def dockerType = result.find { it.code == 'scvmmLinux' }
        dockerType.name == 'SCVMM Docker Host'
        dockerType.containerHypervisor == true
        dockerType.computeTypeCode == 'docker-host'
        dockerType.optionTypes.size() == 1

        // Verify SCVMM Kubernetes Master server type and its host option type
        def kubeMasterType = result.find { it.code == 'scvmmKubeMaster' }
        kubeMasterType.name == 'SCVMM Kubernetes Master'
        kubeMasterType.nodeType == 'kube-master'
        kubeMasterType.hasMaintenanceMode == true
        kubeMasterType.creatable == true
        kubeMasterType.agentType == ComputeServerType.AgentType.host
        kubeMasterType.computeTypeCode == 'kube-master'
        kubeMasterType.optionTypes.size() == 1

        // Verify SCVMM Kubernetes Worker server type and its host option type
        def kubeWorkerType = result.find { it.code == 'scvmmKubeWorker' }
        kubeWorkerType.name == 'SCVMM Kubernetes Worker'
        kubeWorkerType.nodeType == 'kube-worker'
        kubeWorkerType.agentType == ComputeServerType.AgentType.guest
        kubeWorkerType.computeTypeCode == 'kube-worker'
        kubeWorkerType.optionTypes.size() == 1

        // Verify hostOptionType properties for server types that should have them
        [dockerType, kubeMasterType, kubeWorkerType].each { serverType ->
            def hostOption = serverType.optionTypes[0]
            hostOption.code == 'computeServerType.scvmm.capabilityProfile'
            hostOption.inputType == OptionType.InputType.SELECT
            hostOption.name == 'capability profile'
            hostOption.fieldName == 'scvmmCapabilityProfile'
            hostOption.required == true
            hostOption.enabled == true
            hostOption.optionSource == 'scvmmCapabilityProfile'
        }
    }

    def "getOptionTypes returns correct collection with proper configuration, display order, and field contexts"() {
        when:
        def result = cloudProvider.getOptionTypes()

        then:
        // Basic collection verification
        result.size() == 15
        result instanceof Collection<OptionType>

        // Verify display order increments properly
        def displayOrders = result.collect { it.displayOrder }.sort()
        displayOrders == [0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140]

        // Verify field context configurations
        def configOptions = result.findAll { it.fieldContext == 'config' }
        configOptions.size() == 13

        def credentialOptions = result.findAll { it.fieldContext == 'credential' }
        credentialOptions.size() == 1
        credentialOptions[0].code == 'zoneType.scvmm.credential'

        def domainOptions = result.findAll { it.fieldContext == 'domain' }
        domainOptions.size() == 1
        domainOptions[0].code == 'zoneType.scvmm.cloud'

        // Verify SCVMM Host option
        def hostOption = result.find { it.code == 'zoneType.scvmm.host' }
        hostOption.name == 'SCVMM Host'
        hostOption.category == 'zoneType.scvmm'
        hostOption.fieldName == 'host'
        hostOption.displayOrder == 0
        hostOption.fieldCode == 'gomorpheus.scvmm.option.host'
        hostOption.fieldLabel == 'SCVMM Host'
        hostOption.required == true
        hostOption.inputType == OptionType.InputType.TEXT
        hostOption.fieldContext == 'config'

        // Verify Credentials option
        def credentialOption = result.find { it.code == 'zoneType.scvmm.credential' }
        credentialOption.name == 'Credentials'
        credentialOption.fieldName == 'type'
        credentialOption.displayOrder == 10
        credentialOption.fieldCode == 'gomorpheus.label.credentials'
        credentialOption.required == true
        credentialOption.defaultValue == 'local'
        credentialOption.inputType == OptionType.InputType.CREDENTIAL
        credentialOption.fieldContext == 'credential'
        credentialOption.optionSource == 'credentials'
        credentialOption.config == '{"credentialTypes":["username-password"]}'

        // Verify Username option
        def usernameOption = result.find { it.code == 'zoneType.scvmm.username' }
        usernameOption.name == 'Username'
        usernameOption.fieldName == 'username'
        usernameOption.displayOrder == 20
        usernameOption.fieldCode == 'gomorpheus.optiontype.Username'
        usernameOption.required == true
        usernameOption.inputType == OptionType.InputType.TEXT
        usernameOption.fieldContext == 'config'
        usernameOption.localCredential == true

        // Verify Password option
        def passwordOption = result.find { it.code == 'zoneType.scvmm.password' }
        passwordOption.name == 'Password'
        passwordOption.fieldName == 'password'
        passwordOption.displayOrder == 30
        passwordOption.fieldCode == 'gomorpheus.optiontype.Password'
        passwordOption.required == true
        passwordOption.inputType == OptionType.InputType.PASSWORD
        passwordOption.fieldContext == 'config'
        passwordOption.localCredential == true

        // Verify Cloud option
        def cloudOption = result.find { it.code == 'zoneType.scvmm.cloud' }
        cloudOption.name == 'Cloud'
        cloudOption.fieldName == 'regionCode'
        cloudOption.optionSourceType == 'scvmm'
        cloudOption.displayOrder == 40
        cloudOption.fieldCode == 'gomorpheus.optiontype.Cloud'
        cloudOption.inputType == OptionType.InputType.SELECT
        cloudOption.optionSource == 'scvmmCloud'
        cloudOption.fieldContext == 'domain'
        cloudOption.noBlank == true
        cloudOption.dependsOn == 'config.host, config.username, config.password, credential.type, credential.username, credential.password'

        // Verify Host Group option
        def hostGroupOption = result.find { it.code == 'zoneType.scvmm.hostGroup' }
        hostGroupOption.name == 'Host Group'
        hostGroupOption.fieldName == 'hostGroup'
        hostGroupOption.displayOrder == 50
        hostGroupOption.fieldCode == 'gomorpheus.optiontype.HostGroup'
        hostGroupOption.inputType == OptionType.InputType.SELECT
        hostGroupOption.optionSource == 'scvmmHostGroup'
        hostGroupOption.fieldContext == 'config'
        hostGroupOption.noBlank == true
        hostGroupOption.dependsOn == 'config.host, config.username, config.password, credential.type, credential.username, credential.password'

        // Verify Cluster option
        def clusterOption = result.find { it.code == 'zoneType.scvmm.Cluster' }
        clusterOption.name == 'Cluster'
        clusterOption.fieldName == 'cluster'
        clusterOption.displayOrder == 60
        clusterOption.fieldCode == 'gomorpheus.optiontype.Cluster'
        clusterOption.inputType == OptionType.InputType.SELECT
        clusterOption.optionSource == 'scvmmCluster'
        clusterOption.fieldContext == 'config'
        clusterOption.noBlank == true
        clusterOption.dependsOn == 'config.host, config.username, config.password, config.hostGroup, credential.type, credential.username, credential.password'

        // Verify Library Share option
        def libraryShareOption = result.find { it.code == 'zoneType.scvmm.libraryShare' }
        libraryShareOption.name == 'Library Share'
        libraryShareOption.fieldName == 'libraryShare'
        libraryShareOption.displayOrder == 70
        libraryShareOption.fieldCode == 'gomorpheus.optiontype.LibraryShare'
        libraryShareOption.inputType == OptionType.InputType.SELECT
        libraryShareOption.optionSource == 'scvmmLibraryShares'
        libraryShareOption.fieldContext == 'config'
        libraryShareOption.noBlank == true
        libraryShareOption.dependsOn == 'config.host, config.username, config.password, credential.type, credential.username, credential.password'

        // Verify Shared Controller option
        def sharedControllerOption = result.find { it.code == 'zoneType.scvmm.sharedController' }
        sharedControllerOption.name == 'Shared Controller'
        sharedControllerOption.fieldName == 'sharedController'
        sharedControllerOption.displayOrder == 80
        sharedControllerOption.fieldCode == 'gomorpheus.optiontype.SharedController'
        sharedControllerOption.required == false
        sharedControllerOption.inputType == OptionType.InputType.SELECT
        sharedControllerOption.optionSource == 'scvmmSharedControllers'
        sharedControllerOption.fieldContext == 'config'
        sharedControllerOption.editable == false

        // Verify Working Path option
        def workingPathOption = result.find { it.code == 'zoneType.scvmm.workingPath' }
        workingPathOption.name == 'Working Path'
        workingPathOption.fieldName == 'workingPath'
        workingPathOption.displayOrder == 90
        workingPathOption.fieldCode == 'gomorpheus.optiontype.WorkingPath'
        workingPathOption.required == true
        workingPathOption.inputType == OptionType.InputType.TEXT
        workingPathOption.defaultValue == 'c:\\Temp'

        // Verify Disk Path option
        def diskPathOption = result.find { it.code == 'zoneType.scvmm.diskPath' }
        diskPathOption.name == 'Disk Path'
        diskPathOption.fieldName == 'diskPath'
        diskPathOption.displayOrder == 100
        diskPathOption.fieldCode == 'gomorpheus.optiontype.DiskPath'
        diskPathOption.required == true
        diskPathOption.inputType == OptionType.InputType.TEXT
        diskPathOption.defaultValue == 'c:\\VirtualDisks'

        // Verify Hide Host Selection option
        def hideHostOption = result.find { it.code == 'zoneType.scvmm.hideHostSelection' }
        hideHostOption.name == 'Hide Host Selection From Users'
        hideHostOption.fieldName == 'HideHostSelectionFromUsers'
        hideHostOption.displayOrder == 110
        hideHostOption.fieldLabel == 'Hide Host Selection From Users'
        hideHostOption.required == false
        hideHostOption.inputType == OptionType.InputType.CHECKBOX
        hideHostOption.fieldContext == 'config'

        // Verify Inventory Existing Instances option
        def inventoryOption = result.find { it.code == 'zoneType.scvmm.importExisting' }
        inventoryOption.name == 'Inventory Existing Instances'
        inventoryOption.fieldName == 'importExisting'
        inventoryOption.displayOrder == 120
        inventoryOption.fieldLabel == 'Inventory Existing Instances'
        inventoryOption.required == false
        inventoryOption.inputType == OptionType.InputType.CHECKBOX
        inventoryOption.fieldContext == 'config'

        // Verify Enable Hypervisor Console option
        def consoleOption = result.find { it.code == 'zoneType.scvmm.enableHypervisorConsole' }
        consoleOption.name == 'Enable Hypervisor Console'
        consoleOption.fieldName == 'enableHypervisorConsole'
        consoleOption.displayOrder == 130
        consoleOption.fieldLabel == 'Enable Hypervisor Console'
        consoleOption.required == false
        consoleOption.inputType == OptionType.InputType.CHECKBOX
        consoleOption.fieldContext == 'config'

        // Verify Install Agent option
        def installAgentOption = result.find { it.code == 'gomorpheus.label.installAgent' }
        installAgentOption.name == 'Install Agent'
        installAgentOption.inputType == OptionType.InputType.CHECKBOX
        installAgentOption.fieldName == 'installAgent'
        installAgentOption.fieldContext == 'config'
        installAgentOption.fieldCode == 'gomorpheus.label.installAgent'
        installAgentOption.fieldLabel == 'Install Agent'
        installAgentOption.fieldGroup == 'Advancedend'
        installAgentOption.displayOrder == 140
        installAgentOption.required == false
        installAgentOption.enabled == true
        installAgentOption.editable == false
        installAgentOption.global == false
        installAgentOption.custom == true
    }

    def "getAvailableProvisionProviders returns collection of provision providers from plugin"() {
        given:
        // Create mock provision providers
        def provisionProvider1 = Mock(ProvisionProvider)
        def provisionProvider2 = Mock(ProvisionProvider)
        def mockProviders = [provisionProvider1, provisionProvider2]

        // Mock the plugin's getProvidersByType method
        plugin.getProvidersByType(ProvisionProvider) >> mockProviders

        when:
        def result = cloudProvider.getAvailableProvisionProviders()

        then:
        // Verify the plugin method was called with correct parameter
        1 * plugin.getProvidersByType(ProvisionProvider) >> mockProviders

        // Verify the result is correct
        result instanceof Collection<ProvisionProvider>
        result.size() == 2
        result.containsAll([provisionProvider1, provisionProvider2])
    }

    def "getStorageVolumeTypes returns collection from StorageVolumeTypeHelper"() {
        given:
        // Create mock storage volume types
        def volumeType1 = Mock(StorageVolumeType)
        def volumeType2 = Mock(StorageVolumeType)
        def volumeType3 = Mock(StorageVolumeType)
        def expectedVolumeTypes = [volumeType1, volumeType2, volumeType3]

        // Mock the static helper method
        GroovyMock(StorageVolumeTypeHelper, global: true)
        StorageVolumeTypeHelper.getAllStorageVolumeTypes() >> expectedVolumeTypes

        when:
        def result = cloudProvider.getStorageVolumeTypes()

        then:
        // Verify the static method was called
        1 * StorageVolumeTypeHelper.getAllStorageVolumeTypes() >> expectedVolumeTypes

        // Verify the result
        result instanceof Collection<StorageVolumeType>
        result.size() == 3
        result == expectedVolumeTypes
        result.containsAll([volumeType1, volumeType2, volumeType3])
    }

    def "startServer creates provision provider and delegates to its startServer method"() {
        given:
        def computeServer = new ComputeServer(
                id: 100L,
                name: "test-server",
                hostname: "test-host"
        )

        def expectedResponse = new ServiceResponse(
                success: true,
                data: [serverStarted: true]
        )

        // Mock the provision provider constructor and startServer method
        def mockProvisionProvider = Mock(ScvmmProvisionProvider)

        // Use GroovyMock to mock the constructor
        GroovyMock(ScvmmProvisionProvider, global: true)

        when:
        def result = cloudProvider.startServer(computeServer)

        then:
        // Verify provision provider was created with correct parameters
        1 * new ScvmmProvisionProvider(plugin, morpheusContext) >> mockProvisionProvider

        // Verify startServer was called on the provision provider
        1 * mockProvisionProvider.startServer(computeServer) >> expectedResponse

        // Verify the response is correctly returned
        result == expectedResponse
        result.success == true
        result.data.serverStarted == true
    }

    def "stopServer creates provision provider and delegates to its stopServer method"() {
        given:
        def computeServer = new ComputeServer(
                id: 100L,
                name: "test-server",
                hostname: "test-host"
        )

        def expectedResponse = new ServiceResponse(
                success: true,
                data: [serverStopped: true]
        )

        // Mock the provision provider constructor and stopServer method
        def mockProvisionProvider = Mock(ScvmmProvisionProvider)

        // Use GroovyMock to mock the constructor
        GroovyMock(ScvmmProvisionProvider, global: true)

        when:
        def result = cloudProvider.stopServer(computeServer)

        then:
        // Verify provision provider was created with correct parameters
        1 * new ScvmmProvisionProvider(plugin, morpheusContext) >> mockProvisionProvider

        // Verify stopServer was called on the provision provider
        1 * mockProvisionProvider.stopServer(computeServer) >> expectedResponse

        // Verify the response is correctly returned
        result == expectedResponse
        result.success == true
        result.data.serverStopped == true
    }


    @Unroll
    def "getProvisionProvider returns correct provider when code matches"() {
        given:
        def mockProvider = Mock(ProvisionProvider)
        mockProvider.getCode() >> "scvmm"
        cloudProvider.getAvailableProvisionProviders() >>{
            return [mockProvider]
        }

        when:
        def result = cloudProvider.getProvisionProvider("scvmm")

        then:
        result == mockProvider

    }

    def "getMorpheus returns the injected MorpheusContext instance"() {
        when:
        def result = cloudProvider.getMorpheus()

        then:
        result == morpheusContext
        result instanceof MorpheusContext
    }

    @Unroll
    def "updateHypervisorStatus handles server with minimal properties"() {
        given:
        def server = new ComputeServer(id: 200L)
        // Server has no initial status or powerState (null values)

        when:
        cloudProvider.updateHypervisorStatus(server, "running", "on", "test message")

        then:
        server.status == "running"
        server.powerState.toString() == "on"
        server.statusMessage == "test message"
        server.statusDate instanceof Date

        // Verify save was called since null != "running" and null != "on"
        1 * computeServerService.save(server) >> server
    }

    @Unroll
    def "removeOrphanedResourceLibraryItems handles #scenario"() {
        given:
        def cloud = new Cloud(id: 100L, name: "test-cloud")
        def node = new ComputeServer(id: 200L, name: "test-node")

        def scvmmOpts = [
                controller: 1L,
                zoneId: cloud.id,
                hostId: node.id
        ]

        when:
        def result = cloudProvider.removeOrphanedResourceLibraryItems(cloud, node)

        then:
        // Verify getScvmmZoneAndHypervisorOpts was called with correct parameters
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, node) >> {
            if (getOptsThrows) {
                throw new RuntimeException("Failed to get SCVMM options")
            }
            return scvmmOpts
        }

        // Verify removeOrphanedResourceLibraryItems called only when getOpts succeeds
        removeLibraryItemsCalls * mockApiService.removeOrphanedResourceLibraryItems(scvmmOpts) >> {
            if (removeItemsThrows) {
                throw new RuntimeException("Failed to remove orphaned items")
            }
        }

        // Verify result is always [success: false] regardless of success/failure
        result.success == false

        where:
        scenario                              | getOptsThrows | removeItemsThrows | removeLibraryItemsCalls
        "successful execution"                | false         | false             | 1
        "getScvmmZoneAndHypervisorOpts fails" | true          | false             | 0
        "removeOrphanedResourceLibraryItems fails" | false    | true              | 1
    }

    @Unroll
    def "validateSharedController returns #expectedResult for #scenario"() {
        given:
        def cloud = new Cloud(
                id: cloudId,
                name: "test-cloud",
                account: new Account(id: 100L)
        )

        // Set cloud configuration properties
        if (sharedControllerId != null) {
            cloud.setConfigProperty('sharedController', sharedControllerId)
        }
        cloud.setConfigProperty('host', hostIp)

        // Mock existing controller in zone (if applicable)
        def existingControllerInZone = existingInZone ? new ComputeServer(
                id: 300L,
                name: "existing-controller-in-zone",
                cloud: cloud,
                computeServerType: new ComputeServerType(code: 'scvmmController')
        ) : null

        // Mock existing controller with same host (if applicable)
        def existingControllerSameHost = existingSameHost ? new ComputeServer(
                id: 400L,
                name: "existing-controller-same-host",
                externalIp: hostIp,
                enabled: true,
                account: cloud.account,
                cloud: new Cloud(id: 200L, name: "other-cloud"),
                computeServerType: new ComputeServerType(code: 'scvmmController')
        ) : null

        // Mock shared controller (if applicable)
        def sharedController = sharedControllerExists ? new ComputeServer(
                id: sharedControllerId?.toLong(),
                name: "shared-controller",
                sshHost: sharedControllerSshHost,
                externalIp: sharedControllerExternalIp
        ) : null

        // Setup service mocks
        computeServerService.find({ DataQuery query ->
            def filters = query.filters

            // Check for existing controller in zone query
            if (filters.any { it.name == 'computeServerType.code' && it.value == 'scvmmController' } &&
                    filters.any { it.name == 'zone.id' && it.value == cloud.id?.toLong() }) {
                return existingControllerInZone
            }

            // Check for existing controller with same host query
            if (filters.any { it.name == 'enabled' && it.value == true } &&
                    filters.any { it.name == 'account.id' && it.value == cloud.account.id } &&
                    filters.any { it.name == 'computeServerType.code' && it.value == 'scvmmController' } &&
                    filters.any { it.name == 'externalIp' && it.value == hostIp }) {
                return existingControllerSameHost
            }

            return null
        }) >> { DataQuery query ->
            def filters = query.filters

            if (filters.any { it.name == 'zone.id' }) {
                return existingControllerInZone
            } else {
                return existingControllerSameHost
            }
        }

        computeServerService.get(sharedControllerId?.toLong()) >> sharedController

        when:
        def result = cloudProvider.validateSharedController(cloud)

        then:
        result.success == expectedSuccess
        result.msg == expectedMessage

        where:
        scenario | cloudId | sharedControllerId | hostIp | existingInZone | existingSameHost | sharedControllerExists | sharedControllerSshHost | sharedControllerExternalIp | expectedSuccess | expectedMessage

        // No shared controller specified scenarios
        "no shared controller, new cloud, no existing controllers" | null | null | "192.168.1.100" | false | false | false | null | null | true | null
        "no shared controller, existing cloud, has controller in zone" | 1L | null | "192.168.1.100" | true | false | false | null | null | true | null
        "no shared controller, existing cloud, no controller in zone, no same host controller" | 1L | null | "192.168.1.100" | false | false | false | null | null | true | null
        "no shared controller, new cloud, existing same host controller" | null | null | "192.168.1.100" | false | true | false | null | null | false | "You must specify a shared controller"
        "no shared controller, existing cloud, no controller in zone, existing same host controller" | 1L | null | "192.168.1.100" | false | true | false | null | null | false | "You must specify a shared controller"

        // Shared controller specified scenarios
        "shared controller specified, matching sshHost" | 1L | "500" | "192.168.1.100" | false | false | true | "192.168.1.100" | "10.0.0.1" | true | null
        "shared controller specified, matching externalIp" | 1L | "500" | "192.168.1.100" | false | false | true | "10.0.0.1" | "192.168.1.100" | true | null
        "shared controller specified, no matching host" | 1L | "500" | "192.168.1.100" | false | false | true | "192.168.1.200" | "10.0.0.1" | false | "The selected controller is on a different host than specified for this cloud"
        //"shared controller specified, controller not found" | 1L | "500" | "192.168.1.100" | false | false | false | null | null | true | null

        expectedResult = expectedSuccess ? "success" : "failure"
    }

    @Unroll
    def "validateRequiredConfigFields returns #expectedResult for #scenario"() {
        given:
        def fieldArray = fields
        def config = configMap

        when:
        def result = cloudProvider.validateRequiredConfigFields(fieldArray, config)

        then:
        result == expectedErrors

        where:
        scenario                                    | fields                           | configMap                                      | expectedErrors
        "all fields valid with values"             | ['host', 'username', 'password'] | [host: 'server.com', username: 'admin', password: 'secret'] | [:]
        "empty string values"                      | ['host', 'username']             | [host: '', username: '']                       | [host: 'Enter a host', username: 'Enter a username']
        "null values (should be ignored)"          | ['host', 'username']             | [host: null, username: null]                   | [:]
        "missing fields in config"                 | ['host', 'username']             | [:]                                            | [:]
        "mixed valid and empty values"             | ['host', 'username', 'password'] | [host: 'server.com', username: '', password: 'secret'] | [username: 'Enter a username']
        "camelCase field names"                    | ['hostName', 'workingPath']      | [hostName: '', workingPath: '']                | [hostName: 'Enter a host name', workingPath: 'Enter a working path']
        "complex camelCase field names"            | ['libraryShare', 'diskPath']     | [libraryShare: '', diskPath: '']               | [libraryShare: 'Enter a library share', diskPath: 'Enter a disk path']
        "single character field"                   | ['a']                            | [a: '']                                        | [a: 'Enter a a']
        "empty field array"                        | []                               | [host: '', username: '']                       | [:]
        "whitespace-only values (treated as valid)"| ['host']                         | [host: '   ']                                  | [:]
        //"numeric values"                           | ['port']                         | [port: 8080]                                   | [:]
        //"boolean values"                           | ['enabled']                      | [enabled: false]                               | [:]
        "list values (non-empty)"                  | ['items']                        | [items: ['item1', 'item2']]                    | [:]
        "empty list values"                        | ['items']                        | [items: []]                                    | [items: 'Enter a items']

        expectedResult = expectedErrors.isEmpty() ? "no errors" : "validation errors"
    }

    @Unroll
    def "deleteServer returns #expectedResult for #scenario"() {
        given:
        def computeServer = new ComputeServer(
                id: 100L,
                name: "test-server",
                externalId: "vm-12345"
        )

        def scvmmOpts = [
                controller: 1L,
                zoneId: 200L,
                externalId: "vm-12345"
        ]

        def stopResults = [success: stopSuccess]
        def deleteResults = [success: deleteSuccess]

        // Mock the provision provider constructor and methods
        def mockProvisionProvider = Mock(ScvmmProvisionProvider)

        // Use GroovyMock to mock the constructor
        GroovyMock(ScvmmProvisionProvider, global: true)

        when:
        def result = cloudProvider.deleteServer(computeServer)

        then:
        // Verify provision provider was created with correct parameters
        1 * new ScvmmProvisionProvider(plugin, morpheusContext) >> mockProvisionProvider

        // Verify getAllScvmmServerOpts was called
        1 * mockProvisionProvider.getAllScvmmServerOpts(computeServer) >> scvmmOpts

        // Verify stopServer was called
        1 * mockApiService.stopServer(scvmmOpts, "vm-12345") >> stopResults

        // Verify deleteServer called only when stop succeeds
        deleteServerCalls * mockApiService.deleteServer(scvmmOpts, "vm-12345") >> deleteResults

        // Verify the response
        result instanceof ServiceResponse
        result.success == expectedSuccess
        result.msg == expectedMessage

        where:
        scenario                          | stopSuccess | deleteSuccess | deleteServerCalls | expectedSuccess | expectedMessage
        "successful stop and delete"      | true        | true          | 1                | true           | null
        "successful stop, failed delete"  | true        | false         | 1                | false          | null
        "failed stop"                     | false       | true          | 0                | false          | null

        expectedResult = expectedSuccess ? "success" : "failure"
    }

    def "deleteServer handles exception during provision provider creation"() {
        given:
        def computeServer = new ComputeServer(
                id: 100L,
                name: "test-server"
        )

        // Mock constructor to throw exception
        GroovyMock(ScvmmProvisionProvider, global: true)

        when:
        def result = cloudProvider.deleteServer(computeServer)

        then:
        // Verify provision provider constructor throws exception
        1 * new ScvmmProvisionProvider(plugin, morpheusContext) >> {
            throw new RuntimeException("Failed to create provision provider")
        }

        // Verify no API calls are made when constructor fails
        0 * mockApiService.stopServer(_, _)
        0 * mockApiService.deleteServer(_, _)

        // Verify error response
        result instanceof ServiceResponse
        result.success == false
        result.msg == "Failed to create provision provider"
    }

    def "deleteServer handles exception during getAllScvmmServerOpts"() {
        given:
        def computeServer = new ComputeServer(
                id: 100L,
                name: "test-server"
        )

        def mockProvisionProvider = Mock(ScvmmProvisionProvider)

        // Mock constructor and getAllScvmmServerOpts to throw exception
        GroovyMock(ScvmmProvisionProvider, global: true)

        when:
        def result = cloudProvider.deleteServer(computeServer)

        then:
        // Verify provision provider was created
        1 * new ScvmmProvisionProvider(plugin, morpheusContext) >> mockProvisionProvider

        // Verify getAllScvmmServerOpts throws exception
        1 * mockProvisionProvider.getAllScvmmServerOpts(computeServer) >> {
            throw new RuntimeException("Failed to get SCVMM options")
        }

        // Verify no API calls are made when getAllScvmmServerOpts fails
        0 * mockApiService.stopServer(_, _)
        0 * mockApiService.deleteServer(_, _)

        // Verify error response
        result instanceof ServiceResponse
        result.success == false
        result.msg == "Failed to get SCVMM options"
    }

    @Unroll
    def "refresh returns #expectedResult for #scenario"() {
        given:
        def cloudInfo = new Cloud(
                id: 100L,
                name: "test-scvmm-cloud",
                code: "test-cloud"
        )
        cloudInfo.setConfigProperty('importExisting', importExistingValue)

        def syncDate = new Date()
        def scvmmController = controllerExists ? new ComputeServer(
                id: 200L,
                name: "test-controller"
        ) : null

        def scvmmOpts = [
                sshHost: "192.168.1.100",
                controller: 1L,
                zoneId: 100L
        ]

        def checkResults = [success: communicationSuccess]

        // Mock static methods
        GroovyMock(ConnectionUtils, global: true)
        //GroovyMock(Date, global: true)

        // Mock sync classes
        def mockNetworkSync = Mock(NetworkSync)
        def mockClustersSync = Mock(ClustersSync)
        def mockIsolationNetworkSync = Mock(IsolationNetworkSync)
        def mockHostSync = Mock(HostSync)
        def mockDatastoresSync = Mock(DatastoresSync)
        def mockRegisteredStorageFileSharesSync = Mock(RegisteredStorageFileSharesSync)
        def mockCloudCapabilityProfilesSync = Mock(CloudCapabilityProfilesSync)
        def mockTemplatesSync = Mock(TemplatesSync)
        def mockIpPoolsSync = Mock(IpPoolsSync)
        def mockVirtualMachineSync = Mock(VirtualMachineSync)

        // Mock sync class constructors
        GroovyMock(NetworkSync, global: true)
        GroovyMock(ClustersSync, global: true)
        GroovyMock(IsolationNetworkSync, global: true)
        GroovyMock(HostSync, global: true)
        GroovyMock(DatastoresSync, global: true)
        GroovyMock(RegisteredStorageFileSharesSync, global: true)
        GroovyMock(CloudCapabilityProfilesSync, global: true)
        GroovyMock(TemplatesSync, global: true)
        GroovyMock(IpPoolsSync, global: true)
        GroovyMock(VirtualMachineSync, global: true)

        when:
        def result = cloudProvider.refresh(cloudInfo)

        then:
        // Mock getScvmmController
        1 * cloudProvider.getScvmmController(cloudInfo) >> scvmmController

        if (controllerExists) {
            // Mock apiService.getScvmmZoneAndHypervisorOpts
            1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloudInfo, scvmmController) >> scvmmOpts

            // Mock ConnectionUtils.testHostConnectivity
            1 * ConnectionUtils.testHostConnectivity("192.168.1.100", 5985, false, true, null) >> hostOnline

            if (hostOnline) {
                // Mock checkCommunication
                1 * cloudProvider.checkCommunication(cloudInfo, scvmmController) >> checkResults

                if (communicationSuccess) {
                    // Mock updateHypervisorStatus for success case
                    1 * cloudProvider.updateHypervisorStatus(scvmmController, 'provisioned', 'on', '')

                    // Mock cloud status update to syncing
                    1 * asyncCloudService.updateCloudStatus(cloudInfo, Cloud.Status.syncing, null, _) >> {}

                    // Mock sync class constructors and execute methods
                    1 * new NetworkSync(morpheusContext, cloudInfo) >> mockNetworkSync
                    1 * mockNetworkSync.execute()

                    1 * new ClustersSync(morpheusContext, cloudInfo) >> mockClustersSync
                    1 * mockClustersSync.execute()

                    1 * new IsolationNetworkSync(morpheusContext, cloudInfo, mockApiService) >> mockIsolationNetworkSync
                    1 * mockIsolationNetworkSync.execute()

                    1 * new HostSync(cloudInfo, scvmmController, morpheusContext) >> mockHostSync
                    1 * mockHostSync.execute()

                    1 * new DatastoresSync(scvmmController, cloudInfo, morpheusContext) >> mockDatastoresSync
                    1 * mockDatastoresSync.execute()

                    1 * new RegisteredStorageFileSharesSync(cloudInfo, scvmmController, morpheusContext) >> mockRegisteredStorageFileSharesSync
                    1 * mockRegisteredStorageFileSharesSync.execute()

                    1 * new CloudCapabilityProfilesSync(morpheusContext, cloudInfo) >> mockCloudCapabilityProfilesSync
                    1 * mockCloudCapabilityProfilesSync.execute()

                    1 * new TemplatesSync(cloudInfo, scvmmController, morpheusContext, cloudProvider) >> mockTemplatesSync
                    1 * mockTemplatesSync.execute()

                    1 * new IpPoolsSync(morpheusContext, cloudInfo) >> mockIpPoolsSync
                    1 * mockIpPoolsSync.execute()

                    1 * new VirtualMachineSync(scvmmController, cloudInfo, morpheusContext, cloudProvider) >> mockVirtualMachineSync
                    1 * mockVirtualMachineSync.execute(expectedCreateNew)

                    // Mock final cloud status update to ok
                    1 * asyncCloudService.updateCloudStatus(cloudInfo, Cloud.Status.ok, null, _) >> {}

                } else {
                    // Mock updateHypervisorStatus for communication failure
                    1 * cloudProvider.updateHypervisorStatus(scvmmController, 'error', 'unknown', 'error connecting to controller')

                    // Mock cloud status update to error
                    1 * asyncCloudService.updateCloudStatus(cloudInfo, Cloud.Status.error, 'error connecting', _) >> {}

                    // No sync operations should be called
                    0 * mockNetworkSync.execute()
                    0 * mockClustersSync.execute()
                }
            } else {
                // Mock updateHypervisorStatus for host offline
                1 * cloudProvider.updateHypervisorStatus(scvmmController, 'error', 'unknown', 'error connecting to controller')

                // Mock cloud status update to error
                1 * asyncCloudService.updateCloudStatus(cloudInfo, Cloud.Status.error, 'error connecting', _) >> {}

                // checkCommunication should not be called if host is offline
                0 * cloudProvider.checkCommunication(_, _)
            }
        } else {
            // No controller found - only cloud status update should be called
            1 * asyncCloudService.updateCloudStatus(cloudInfo, Cloud.Status.error, 'controller not found', _) >> {}

            // No other operations should be called
            0 * mockApiService.getScvmmZoneAndHypervisorOpts(_, _, _)
            0 * ConnectionUtils.testHostConnectivity(_, _, _, _, _)
        }

        // Verify result
        result instanceof ServiceResponse
        result.success == expectedSuccess

        where:
        scenario | controllerExists | hostOnline | communicationSuccess | importExistingValue | expectedCreateNew | expectedSuccess | expectedResult

        // Successful scenarios
        "controller exists, host online, communication success, import existing on" | true | true | true | "on" | true | true | "success"
        "controller exists, host online, communication success, import existing true" | true | true | true | "true" | true | true | "success"
        "controller exists, host online, communication success, import existing boolean true" | true | true | true | true | true | true | "success"
        "controller exists, host online, communication success, import existing off" | true | true | true | "off" | false | true | "success"
        "controller exists, host online, communication success, import existing false" | true | true | true | "false" | false | true | "success"
        "controller exists, host online, communication success, import existing null" | true | true | true | null | false | true | "success"

        // Failure scenarios
        "controller exists, host online, communication failure" | true | true | false | "on" | true | false | "communication failure"
        "controller exists, host offline" | true | false | false | "on" | true | false | "host offline"
        "no controller found" | false | false | false | "on" | true | false | "no controller"
    }

    def "refresh handles exceptions gracefully"() {
        given:
        def cloudInfo = new Cloud(id: 100L, name: "test-cloud")

        when:
        def result = cloudProvider.refresh(cloudInfo)

        then:
        // Mock getScvmmController to throw exception
        1 * cloudProvider.getScvmmController(cloudInfo) >> {
            throw new RuntimeException("Test exception")
        }

        // Verify result - should not be successful when exception occurs
        result instanceof ServiceResponse
        result.success == false
    }

    @Unroll
    def "checkCommunication returns #expectedResult for #scenario"() {
        given:
        def cloud = new Cloud(id: 100L, name: "test-cloud")
        def node = new ComputeServer(id: 200L, name: "test-node")

        def scvmmOpts = [
                controller: 1L,
                zoneId: cloud.id,
                hostId: node.id
        ]

        def listResults = listResultsData

        when:
        def result = cloudProvider.checkCommunication(cloud, node)

        then:
        // Verify getScvmmZoneAndHypervisorOpts was called with correct parameters
        getOptsCalls * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, node) >> {
            if (getOptsThrows) {
                throw new RuntimeException("Failed to get SCVMM options")
            }
            return scvmmOpts
        }

        // Verify listAllNetworks called only when getOpts succeeds
        listNetworksCalls * mockApiService.listAllNetworks(scvmmOpts) >> {
            if (listNetworksThrows) {
                throw new RuntimeException("Failed to list networks")
            }
            return listResults
        }

        // Verify the result
        result.success == expectedSuccess

        where:
        scenario | getOptsThrows | listNetworksThrows | listResultsData | getOptsCalls | listNetworksCalls | expectedSuccess
        "successful communication with networks" | false | false | [success: true, networks: [[id: "net-1", name: "Network 1"]]] | 1 | 1 | true
        "successful communication with empty networks list" | false | false | [success: true, networks: []] | 1 | 1 | false
        "successful communication with null networks" | false | false | [success: true, networks: null] | 1 | 1 | false
        "successful communication but listResults success is false" | false | false | [success: false, networks: [[id: "net-1", name: "Network 1"]]] | 1 | 1 | false
        "successful communication but listResults success is null" | false | false | [success: null, networks: [[id: "net-1", name: "Network 1"]]] | 1 | 1 | false
        "successful communication with missing success field" | false | false | [networks: [[id: "net-1", name: "Network 1"]]] | 1 | 1 | false
        "getScvmmZoneAndHypervisorOpts throws exception" | true | false | [:] | 1 | 0 | false
        "listAllNetworks throws exception" | false | true | [:] | 1 | 1 | false
        "both operations throw exceptions" | true | true | [:] | 1 | 0 | false
        "successful communication with multiple networks" | false | false | [success: true, networks: [[id: "net-1", name: "External Network"], [id: "net-2", name: "Internal Network"], [id: "net-3", name: "Management Network"]]] | 1 | 1 | true

        expectedResult = expectedSuccess ? "success" : "failure"
    }


    @Unroll
    def "refreshDaily executes #scenario"() {
        given:
        def cloudInfo = new Cloud(
                id: 100L,
                name: "test-scvmm-cloud"
        )

        def scvmmController = controllerExists ? new ComputeServer(
                id: 200L,
                name: "test-controller"
        ) : null

        def scvmmOpts = scvmmOptsValue

        def checkResults = [success: communicationSuccess]

        // Mock static ConnectionUtils
        GroovyMock(ConnectionUtils, global: true)

        when:
        cloudProvider.refreshDaily(cloudInfo)

        then:
        // Verify getScvmmController is always called
        getControllerCalls * cloudProvider.getScvmmController(cloudInfo) >> {
            if (getControllerThrows) {
                throw new RuntimeException("Controller lookup failed")
            }
            return scvmmController
        }

        if (controllerExists && !getControllerThrows) {
            // Verify getScvmmZoneAndHypervisorOpts is called when controller exists
            getOptsCalls * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloudInfo, scvmmController) >> {
                if (getOptsThrows) {
                    throw new RuntimeException("Failed to get SCVMM options")
                }
                return scvmmOpts
            }

            // Verify testHostConnectivity called only when getOpts succeeds and scvmmOpts is not null
            if (scvmmOpts && !getOptsThrows) {
                connectivityCalls * ConnectionUtils.testHostConnectivity(scvmmOpts.sshHost, 5985, false, true, null) >> hostOnline
            } else {
                connectivityCalls * ConnectionUtils.testHostConnectivity(null, 5985, false, true, null) >> hostOnline
            }

            if (hostOnline && !getOptsThrows && scvmmOpts) {
                // Verify checkCommunication called when host is online
                checkCommCalls * cloudProvider.checkCommunication(cloudInfo, scvmmController) >> {
                    if (checkCommThrows) {
                        throw new RuntimeException("Failed to check communication")
                    }
                    return checkResults
                }

                if (communicationSuccess && !checkCommThrows) {
                    // Verify removeOrphanedResourceLibraryItems called when communication succeeds
                    removeOrphansCalls * cloudProvider.removeOrphanedResourceLibraryItems(cloudInfo, scvmmController) >> {
                        if (removeOrphansThrows) {
                            throw new RuntimeException("Failed to remove orphans")
                        }
                    }
                } else {
                    // removeOrphanedResourceLibraryItems should not be called if communication fails
                    0 * cloudProvider.removeOrphanedResourceLibraryItems(_, _)
                }
            } else {
                // checkCommunication should not be called if host is offline, getOpts throws, or scvmmOpts is null
                0 * cloudProvider.checkCommunication(_, _)
                0 * cloudProvider.removeOrphanedResourceLibraryItems(_, _)
            }
        } else {
            // No API calls should be made if controller doesn't exist or getController throws
            0 * mockApiService.getScvmmZoneAndHypervisorOpts(_, _, _)
            0 * ConnectionUtils.testHostConnectivity(_, _, _, _, _)
            0 * cloudProvider.checkCommunication(_, _)
            0 * cloudProvider.removeOrphanedResourceLibraryItems(_, _)
        }

        // Verify that no exception is thrown and method completes successfully
        noExceptionThrown()

        where:
        scenario | controllerExists | getControllerThrows | getOptsThrows | scvmmOptsValue | hostOnline | checkCommThrows | communicationSuccess | removeOrphansThrows | getControllerCalls | getOptsCalls | connectivityCalls | checkCommCalls | removeOrphansCalls

        // Success scenarios
        "successful daily refresh with orphan cleanup" | true | false | false | [sshHost: "192.168.1.100", controller: 1L, zoneId: 100L] | true | false | true | false | 1 | 1 | 1 | 1 | 1
        "successful daily refresh but communication fails" | true | false | false | [sshHost: "192.168.1.100", controller: 1L, zoneId: 100L] | true | false | false | false | 1 | 1 | 1 | 1 | 0
        "successful daily refresh but host offline" | true | false | false | [sshHost: "192.168.1.100", controller: 1L, zoneId: 100L] | false | false | false | false | 1 | 1 | 1 | 0 | 0
        "no controller found" | false | false | false | null | false | false | false | false | 1 | 0 | 0 | 0 | 0

        // Exception scenarios
        "getScvmmController throws exception" | false | true | false | null | false | false | false | false | 1 | 0 | 0 | 0 | 0
        "getScvmmZoneAndHypervisorOpts throws exception" | true | false | true | null | false | false | false | false | 1 | 1 | 0 | 0 | 0
        "checkCommunication throws exception" | true | false | false | [sshHost: "192.168.1.100", controller: 1L, zoneId: 100L] | true | true | false | false | 1 | 1 | 1 | 1 | 0
        "removeOrphanedResourceLibraryItems throws exception" | true | false | false | [sshHost: "192.168.1.100", controller: 1L, zoneId: 100L] | true | false | true | true | 1 | 1 | 1 | 1 | 1

        // Edge cases
        "null scvmmOpts returned" | true | false | false | null | false | false | false | false | 1 | 1 | 0 | 0 | 0
        "missing sshHost in scvmmOpts" | true | false | false | [controller: 1L, zoneId: 100L] | false | false | false | false | 1 | 1 | 1 | 0 | 0
        "communication success but logs debug messages" | true | false | false | [sshHost: "192.168.1.100", controller: 1L, zoneId: 100L] | true | false | false | false | 1 | 1 | 1 | 1 | 0
    }
}
