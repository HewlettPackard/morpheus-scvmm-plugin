package com.morpheusdata.scvmm

import com.morpheusdata.PrepareHostResponse
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataAndFilter
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.HostProvisionProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.ResourcePermission
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.ComputeTypeSet
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.HostType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Instance
import com.morpheusdata.model.NetAddress
import com.morpheusdata.model.Network
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.OsType
import com.morpheusdata.model.ProcessEvent
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.Workload
import com.morpheusdata.model.WorkloadType
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.InitializeHypervisorResponse
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import com.morpheusdata.scvmm.util.MorpheusUtil
import com.morpheusdata.core.providers.CloudProvider
import groovy.json.JsonSlurper

@SuppressWarnings(['CompileStatic', 'MethodCount', 'ClassSize'])
class ScvmmProvisionProvider extends AbstractProvisionProvider implements WorkloadProvisionProvider,
        HostProvisionProvider, ProvisionProvider.HypervisorProvisionFacet, HostProvisionProvider.ResizeFacet,
        WorkloadProvisionProvider.ResizeFacet, ProvisionProvider.BlockDeviceNameFacet {
    public static final String PROVIDER_CODE = 'scvmm.provision'
    public static final String PROVISION_TYPE_CODE = 'scvmm'
    protected static final String[] DISK_NAMES = ['sda', 'sdb', 'sdc', 'sdd', 'sde', 'sdf', 'sdg', 'sdh',
                                                  'sdi', 'sdj', 'sdk', 'sdl']
    // Add these new constants
    private static final String DEFAULT_CAPABILITY_PROFILE = '-1'
    private static final String COMPUTE_SERVER_TYPE_CODE_FIELD = 'computeServerType.code'
    private static final String SHARED_CONTROLLER_KEY = 'sharedController'
    private static final String SCVMM_CONTROLLER_CODE = 'scvmmController'
    private static final String CONFIG_CONTEXT = 'config'
    private static final String WINDOWS_PLATFORM = 'windows'
    private static final String LINUX_PLATFORM = 'linux'
    private static final String ID_FIELD = 'id'
    private static final String PROVISIONED_STATUS = 'provisioned'
    private static final String COMPUTE_ZONE_REF_TYPE = 'ComputeZone'
    private static final String DATASTORE_REF_TYPE_FIELD = 'datastore.refType'
    private static final String EXTERNAL_ID_FIELD = 'externalId'
    private static final String MAX_MEMORY_FIELD = 'maxMemory'
    private static final String MAX_CORES_FIELD = 'maxCores'
    private static final String HOST_ID_FIELD = 'hostId'

    // Field and option constants
    private static final String SCVMM_CAPABILITY_PROFILE_FIELD = 'scvmmCapabilityProfile'
    private static final String SCVMM_CODE = 'scvmm'
    private static final String PROVISION_TYPE_SCVMM_CATEGORY = 'provisionType.scvmm'
    private static final String PROVISION_TYPE_SCVMM_CUSTOM_CATEGORY = 'provisionType.scvmm.custom'
    private static final String DOMAIN_FIELD_CONTEXT = 'domain'
    private static final String CONTAINER_TYPE_FIELD_CONTEXT = 'containerType'
    private static final String OPTIONS_FIELD_GROUP = 'Options'
    private static final String SELECT_NO_SELECTION = 'Select'

    private static final String ERROR_CREATING_SERVER_MSG = 'Error creating server'
    private static final String VM_CONFIG_ERROR_MSG = 'vm config error'

    // Query and filter constants
    private static final String CODE_FILTER = 'code'
    private static final String STANDARD_VALUE = 'standard'

// Service plan descriptions
    private static final String ONE_CORE_1GB_DESC = '1 Core, 1GB Memory'
    private static final String ONE_CORE_2GB_DESC = '1 Core, 2GB Memory'

// Common numeric constants
    private static final Integer FIVE_TWELVE_INT = 512

    private static final Integer DISPLAY_ORDER_6 = 6
    private static final Integer DISPLAY_ORDER_7 = 7
    private static final Integer DISPLAY_ORDER_8 = 8
    private static final Integer DISPLAY_ORDER_9 = 9

    private static final Integer DISPLAY_ORDER_10 = 10
    private static final Integer DISPLAY_ORDER_11 = 11
    private static final Integer DISPLAY_ORDER_20 = 20
    private static final Integer DISPLAY_ORDER_30 = 30
    private static final Integer DISPLAY_ORDER_40 = 40

    private static final Integer FOUR_INT = 4
    private static final Long BYTES_IN_KB = 1024L
    private static final Long TEN_LONG = 10L
    private static final Long TWENTY_LONG = 20L
    private static final Long FORTY_LONG = 40L
    private static final Long EIGHTY_LONG = 80L
    private static final Long ONE_SIXTY_LONG = 160L
    private static final Long TWO_FORTY_LONG = 240L
    private static final Long THREE_TWENTY_LONG = 320L

    private static final Long ONE_GB_STORAGE = TEN_LONG * BYTES_IN_KB * BYTES_IN_KB * BYTES_IN_KB
    private static final Long TWO_GB_STORAGE = TWENTY_LONG * BYTES_IN_KB * BYTES_IN_KB * BYTES_IN_KB
    private static final Long FORTY_GB_STORAGE = FORTY_LONG * BYTES_IN_KB * BYTES_IN_KB * BYTES_IN_KB
    private static final Long EIGHTY_GB_STORAGE = EIGHTY_LONG * BYTES_IN_KB * BYTES_IN_KB * BYTES_IN_KB
    private static final Long ONE_SIXTY_GB_STORAGE = ONE_SIXTY_LONG * BYTES_IN_KB * BYTES_IN_KB * BYTES_IN_KB
    private static final Long TWO_FORTY_GB_STORAGE = TWO_FORTY_LONG * BYTES_IN_KB * BYTES_IN_KB * BYTES_IN_KB
    private static final Long THREE_TWENTY_GB_STORAGE = THREE_TWENTY_LONG * BYTES_IN_KB * BYTES_IN_KB * BYTES_IN_KB

    // Additional constants for remaining duplicates
    private static final String ONE_CORE_4GB_DESC = '1 Core, 4GB Memory'
    private static final String TWO_CORE_8GB_DESC = '2 Core, 8GB Memory'
    private static final String TWO_CORE_16GB_DESC = '2 Core, 16GB Memory'
    private static final String FOUR_CORE_24GB_DESC = '4 Core, 24GB Memory'
    private static final String FOUR_CORE_32GB_DESC = '4 Core, 32GB Memory'
    private static final String CUSTOM_SCVMM_DESC = 'Custom SCVMM'
    private static final String VM_NOT_FOUND_MSG = 'vm not found'
    private static final String VM_TYPE = 'vm'
    private static final String DEV_SDA_PATH = '/dev/sda'
    private static final String ZERO_STRING = '0'
    private static final Long ZERO_LONG = 0
    private static final Long ONE_LONG = 1
    private static final Long TWO_LONG = 2
    private static final Long FOUR_LONG = 4
    private static final Long EIGHT_LONG = 8
    private static final Long SIXTEEN_LONG = 16
    private static final Long THIRTY_TWO_LONG = 32
    private static final Integer ZERO_INT = 0
    private static final String VIRTUAL_IMAGE_ID_FIELD = 'virtualImage.id'
    private static final String CLOUD_ID_FIELD = 'cloud.id'
    private static final String COMPUTE_SERVER_TYPE_FIELD = 'computeServerType'
    private static final String SCVMM_HYPERVISOR_TYPE = 'scvmmHypervisor'
    private static final String REF_TYPE_FIELD = 'refType'
    private static final String REF_ID_FIELD = 'refId'
    private static final String IN_OPERATOR = 'in'
    private static final String VISIBILITY_FIELD = 'visibility'
    private static final String PUBLIC_VISIBILITY = 'public'
    private static final String OWNER_ID_FIELD = 'owner.id'
    private static final String FREE_SPACE_FIELD = 'freeSpace'

    private static final Long FIVE_TWELVE_LONG = 512L
    private static final String MORPHEUS_UBUNTU_TAGS = 'morpheus, ubuntu'
    private static final String VHD_CONTAINER_TYPE = 'vhd'
    private static final String OSX_PLATFORM = 'osx'
    private static final String SCVMM_GENERATION1_VALUE = 'generation1'
    private static final String GENERATION_FIELD = 'generation'

    //private static final String STANDARD_VALUE = 'standard'
    private static final String NEWLINE = '\n'
    private static final String EMPTY_STRING = ''
    private static final String DATASTORE_REF_ID_FIELD = 'datastore.refId'

// Numeric constants
    private static final Integer SORT_ORDER_1 = 1
    private static final Integer INTEGER_FIVE = 5
    private static final Integer MINUS_ONE = -1

// Sort order and numeric constants
    private static final Integer SORT_ORDER_2 = 2
    private static final Integer SORT_ORDER_3 = 3
    private static final Integer SORT_ORDER_4 = 4
    private static final Integer SORT_ORDER_5 = 5
    private static final Integer SORT_ORDER_6 = 6
    private static final Integer SORT_ORDER_7 = 7
    private static final Integer SORT_ORDER_100 = 100
    private static final Integer CORES_TWO = 2
    private static final Integer CORES_FOUR = 4

    protected MorpheusContext context
    protected ScvmmPlugin plugin
    ScvmmApiService apiService
    private final LogInterface log = LogWrapper.instance

    ScvmmProvisionProvider(ScvmmPlugin plugin, MorpheusContext context) {
        super()
        this.@context = context
        this.@plugin = plugin
        this.apiService = new ScvmmApiService(context)
    }

    /**
     * Initialize a compute server as a Hypervisor. Common attributes defined in
     * the {@link InitializeHypervisorResponse} will be used
     * to update attributes on the hypervisor, including capacity information. Additional details can be
     * updated by the plugin provider
     * using the `context.services.computeServer.save(server)` API.
     * @param cloud cloud associated to the hypervisor
     * @param server representing the hypervisor
     * @return a {@link ServiceResponse} containing an {@link InitializeHypervisorResponse}. The response
     * attributes will be
     * used to fill in necessary attributes of the server.
     */
    @Override
    ServiceResponse<InitializeHypervisorResponse> initializeHypervisor(Cloud cloud, ComputeServer server) {
        log.debug("initializeHypervisor: cloud: {}, server: {}", cloud, server)
        InitializeHypervisorResponse initializeHypervisorResponse = new InitializeHypervisorResponse()
        ServiceResponse<InitializeHypervisorResponse> rtn = ServiceResponse.prepare(initializeHypervisorResponse)
        try {
            def sharedController = cloud.getConfigProperty(SHARED_CONTROLLER_KEY)
            if (sharedController) {
                // No controller needed.. we are sharing another cloud's controller
                rtn.success = true
            } else {
                def opts = apiService.getScvmmZoneOpts(context, cloud)
                opts += apiService.getScvmmControllerOpts(cloud, server)
                def serverInfo = apiService.getScvmmServerInfo(opts)
                String versionCode
                versionCode = apiService.extractWindowsServerVersion(serverInfo.osName)
                log.debug("serverInfo: ${serverInfo}")
                if (serverInfo.success == true && serverInfo.hostname) {
                    server.hostname = serverInfo.hostname
                }
                def maxStorage = serverInfo?.disks ? serverInfo?.disks.toLong() : 0L
                def maxMemory = serverInfo?.memory ? serverInfo?.memory.toLong() : 0L
                def maxCores = 1

                // Create proper OS code format
                rtn.data.serverOs = new OsType(code: versionCode)
                rtn.data.commType = 'winrm' //ssh, minrm
                rtn.data.maxMemory = maxMemory
                rtn.data.maxCores = maxCores
                rtn.data.maxStorage = maxStorage
                rtn.success = true
                if (server.agentInstalled != true) {
                    apiService.prepareNode(opts)
                }
            }
        } catch (e) {
            log.error("initialize hypervisor error:${e}", e)
        }
        return rtn
    }

    /**
     * This method is called before runWorkload and provides an opportunity to perform action or obtain configuration
     * that will be needed in runWorkload. At the end of this method, if deploying a ComputeServer with a VirtualImage,
     * the sourceImage on ComputeServer should be determined and saved.
     * @param workload the Workload object we intend to provision along with some of the associated data needed
     * to determine how best to provision the workload
     * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
     *                        in running the Workload. This will be passed along into runWorkload
     * @param opts additional configuration options that may have been passed during provisioning
     * @return Response from API
     */
    @Override
    ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload,
                                                             WorkloadRequest workloadRequest, Map opts) {
        log.debug("prepare workload scvmm")
        PrepareWorkloadResponse prepareResponse = new PrepareWorkloadResponse(workload: workload)
        ServiceResponse<PrepareWorkloadResponse> resp = ServiceResponse.prepare(prepareResponse)
        resp.success = true
        resp.msg = EMPTY_STRING
        resp.errors = null
        return resp
    }

    /**
     * Some older clouds have a provision type code that is the exact same as the cloud code. This allows one to set it
     * to match and in doing so the provider will be fetched via the cloud
     * providers {@link CloudProvider#getDefaultProvisionTypeCode()} method.
     * @return code for overriding the ProvisionType record code property
     */
    @SuppressWarnings('GetterMethodCouldBeProperty')
    @Override
    String getProvisionTypeCode() {
        return PROVISION_TYPE_CODE
    }

    /**
     * Provide an icon to be displayed for ServicePlans, VM detail page, etc.
     * where a circular icon is displayed
     * @since 0.13.6
     * @return Icon
     */
    @Override
    Icon getCircularIcon() {
        // TODO: change icon paths to correct filenames once added to your project
        return new Icon(path: 'provision-circular.svg', darkPath: 'provision-circular-dark.svg')
    }

    /**
     * Provides a Collection of OptionType inputs that need to be made available to various provisioning Wizards
     * @return Collection of OptionTypes
     */
    @Override
    Collection<OptionType> getOptionTypes() {
        Collection<OptionType> options = []
        options << new OptionType(
                name: 'skip agent install',
                code: 'provisionType.scvmm.noAgent',
                category: PROVISION_TYPE_SCVMM_CATEGORY,
                inputType: OptionType.InputType.CHECKBOX,
                fieldName: 'noAgent',
                fieldContext: CONFIG_CONTEXT,
                fieldCode: 'gomorpheus.optiontype.SkipAgentInstall',
                fieldLabel: 'Skip Agent Install',
                fieldGroup: 'Advanced Options',
                displayOrder: FOUR_INT,
                required: false,
                enabled: true,
                editable: false,
                global: false,
                placeHolder: null,
                helpBlock: 'Skipping Agent installation will result in a lack of logging and' +
                        ' guest operating system statistics. Automation scripts may also be adversely affected.',
                defaultValue: null,
                custom: false,
                fieldClass: null
        )

        options << new OptionType(
                name: 'capability profile',
                code: 'provisionType.scvmm.capabilityProfile',
                category: PROVISION_TYPE_SCVMM_CATEGORY,
                inputType: OptionType.InputType.SELECT,
                fieldName: SCVMM_CAPABILITY_PROFILE_FIELD,
                fieldContext: CONFIG_CONTEXT,
                fieldCode: 'gomorpheus.optiontype.CapabilityProfile',
                fieldLabel: 'Capability Profile',
                fieldGroup: OPTIONS_FIELD_GROUP,
                displayOrder: DISPLAY_ORDER_11,
                required: true,
                enabled: true,
                editable: true,
                global: false,
                defaultValue: null,
                custom: false,
                fieldClass: null,
                optionSource: SCVMM_CAPABILITY_PROFILE_FIELD,
                optionSourceType: SCVMM_CODE
        )

        options << new OptionType(
                code: 'provisionType.scvmm.host',
                inputType: OptionType.InputType.SELECT,
                name: 'host',
                category: PROVISION_TYPE_SCVMM_CATEGORY,
                optionSourceType: SCVMM_CODE,
                fieldName: HOST_ID_FIELD,
                fieldCode: 'gomorpheus.optiontype.Host',
                fieldLabel: 'Host',
                fieldContext: CONFIG_CONTEXT,
                fieldGroup: OPTIONS_FIELD_GROUP,
                required: false,
                enabled: true,
                optionSource: 'scvmmHost',
                editable: false,
                global: false,
                defaultValue: null,
                custom: false,
                displayOrder: 102,
                fieldClass: null
        )

        return options
    }

    /**
     * Provides a Collection of OptionType inputs for configuring node types
     * @since 0.9.0
     * @return Collection of OptionTypes
     */
    @Override
    @SuppressWarnings('MethodSize')
    Collection<OptionType> getNodeOptionTypes() {
        Collection<OptionType> nodeOptions = []
        nodeOptions << new OptionType(
                name: 'virtual image type',
                code: 'scvmm-node-virtual-image-type',
                fieldContext: CONFIG_CONTEXT,
                fieldName: 'virtualImageSelect',
                fieldCode: null,
                fieldLabel: null,
                fieldGroup: null,
                inputType: OptionType.InputType.RADIO,
                displayOrder: DISPLAY_ORDER_9,
                fieldClass: 'inline',
                required: false,
                editable: true,
                optionSource: 'virtualImageTypeList'
        )

        nodeOptions << new OptionType(
                code: 'scvmm-node-image',
                inputType: OptionType.InputType.SELECT,
                name: 'virtual image',
                optionSourceType: SCVMM_CODE,
                optionSource: 'scvmmVirtualImages',
                fieldName: VIRTUAL_IMAGE_ID_FIELD,
                fieldCode: 'gomorpheus.label.vmImage',
                fieldLabel: 'VM Image',
                fieldContext: DOMAIN_FIELD_CONTEXT,
                noSelection: SELECT_NO_SELECTION,
                required: false,
                enabled: true,
                editable: true,
                global: false,
                defaultValue: null,
                custom: false,
                displayOrder: DISPLAY_ORDER_10,
                fieldClass: null,
                visibleOnCode: 'config.virtualImageSelect:vi',
        )

        nodeOptions << new OptionType(
                name: 'osType',
                code: 'scvmm-node-os-type',
                fieldContext: DOMAIN_FIELD_CONTEXT,
                fieldName: 'osType.id',
                fieldCode: 'gomorpheus.optiontype.OsType',
                fieldLabel: 'OsType',
                inputType: OptionType.InputType.SELECT,
                displayOrder: DISPLAY_ORDER_11,
                fieldClass: null,
                required: false,
                editable: true,
                noSelection: SELECT_NO_SELECTION,
                global: false,
                optionSource: 'osTypes',
                visibleOnCode: 'config.virtualImageSelect:os'
        )

        nodeOptions << new OptionType(
                name: 'log folder',
                code: 'scvmm-node-log-folder',
                fieldContext: DOMAIN_FIELD_CONTEXT,
                fieldName: 'mountLogs',
                fieldCode: 'gomorpheus.optiontype.LogFolder',
                fieldLabel: 'Log Folder',
                fieldGroup: null,
                inputType: OptionType.InputType.TEXT,
                displayOrder: DISPLAY_ORDER_20,
                required: false,
                enabled: true,
                editable: true,
                global: false,
                placeHolder: null,
                defaultValue: null,
                custom: false,
                fieldClass: null
        )
        nodeOptions << new OptionType(
                name: 'config folder',
                code: 'scvmm-node-config-folder',
                fieldContext: DOMAIN_FIELD_CONTEXT,
                fieldName: 'mountConfig',
                fieldCode: 'gomorpheus.optiontype.ConfigFolder',
                fieldLabel: 'Config Folder',
                fieldGroup: null,
                inputType: OptionType.InputType.TEXT,
                displayOrder: DISPLAY_ORDER_30,
                required: false,
                enabled: true,
                editable: true,
                global: false,
                placeHolder: null,
                defaultValue: null,
                custom: false,
                fieldClass: null,
        )
        nodeOptions << new OptionType(
                name: 'deploy folder',
                code: 'scvmm-node-deploy-folder',
                fieldContext: DOMAIN_FIELD_CONTEXT,
                fieldName: 'mountData',
                fieldCode: 'gomorpheus.optiontype.DeployFolder',
                fieldLabel: 'Deploy Folder',
                fieldGroup: null,
                inputType: OptionType.InputType.TEXT,
                displayOrder: DISPLAY_ORDER_40,
                required: false,
                enabled: true,
                editable: true,
                global: false,
                placeHolder: null,
                helpTextI18nCode: "gomorpheus.help.deployFolder",
                defaultValue: null,
                custom: false,
                fieldClass: null
        )

        nodeOptions << new OptionType(
                code: 'provisionType.scvmm.custom.containerType.statTypeCode',
                inputType: OptionType.InputType.HIDDEN,
                name: 'stat type code',
                category: PROVISION_TYPE_SCVMM_CUSTOM_CATEGORY,
                fieldName: 'statTypeCode',
                fieldCode: 'gomorpheus.optiontype.StatTypeCode',
                fieldLabel: 'Stat Type Code',
                fieldContext: CONTAINER_TYPE_FIELD_CONTEXT,
                required: false,
                enabled: true,
                editable: false,
                global: false,
                defaultValue: SCVMM_CODE,
                custom: false,
                displayOrder: DISPLAY_ORDER_6,
                fieldClass: null
        )

        nodeOptions << new OptionType(
                code: 'provisionType.scvmm.custom.containerType.logTypeCode',
                inputType: OptionType.InputType.HIDDEN,
                name: 'log type code',
                category: PROVISION_TYPE_SCVMM_CUSTOM_CATEGORY,
                fieldName: 'logTypeCode',
                fieldCode: 'gomorpheus.optiontype.LogTypeCode',
                fieldLabel: 'Log Type Code',
                fieldContext: CONTAINER_TYPE_FIELD_CONTEXT,
                required: false,
                enabled: true,
                editable: false,
                global: false,
                defaultValue: SCVMM_CODE,
                custom: false,
                displayOrder: DISPLAY_ORDER_7,
                fieldClass: null
        )

        nodeOptions << new OptionType(
                code: 'provisionType.scvmm.custom.instanceTypeLayout.description',
                inputType: OptionType.InputType.HIDDEN,
                name: 'layout description',
                category: PROVISION_TYPE_SCVMM_CUSTOM_CATEGORY,
                fieldName: 'description',
                fieldCode: 'gomorpheus.optiontype.LayoutDescription',
                fieldLabel: 'Layout Description',
                fieldContext: 'instanceTypeLayout',
                required: false,
                enabled: true,
                editable: false,
                global: false,
                defaultValue: 'This will provision a single vm container',
                custom: false,
                displayOrder: DISPLAY_ORDER_8,
                fieldClass: null
        )

        nodeOptions << new OptionType(
                code: 'provisionType.scvmm.custom.instanceType.backupType',
                inputType: OptionType.InputType.HIDDEN,
                name: 'backup type',
                category: PROVISION_TYPE_SCVMM_CUSTOM_CATEGORY,
                fieldName: 'backupType',
                fieldCode: 'gomorpheus.optiontype.BackupType',
                fieldLabel: 'Backup Type',
                fieldContext: 'instanceType',
                required: false,
                enabled: true,
                editable: false,
                global: false,
                defaultValue: 'scvmmSnapshot',
                custom: false,
                displayOrder: INTEGER_FIVE,
                fieldClass: null
        )

        return nodeOptions
    }

    /**
     * Provides a Collection of StorageVolumeTypes that are available for root StorageVolumes
     * @return Collection of StorageVolumeTypes
     */
    @Override
    Collection<StorageVolumeType> getRootVolumeStorageTypes() {
        return context.async.storageVolume.storageVolumeType.list(
                new DataQuery().withFilter(CODE_FILTER, STANDARD_VALUE)).toList().blockingGet()
    }

    /**
     * Provides a Collection of StorageVolumeTypes that are available for data StorageVolumes
     * @return Collection of StorageVolumeTypes
     */
    @Override
    Collection<StorageVolumeType> getDataVolumeStorageTypes() {
        return context.async.storageVolume.storageVolumeType.list(
                new DataQuery().withFilter(CODE_FILTER, STANDARD_VALUE)).toList().blockingGet()
    }

    /**
     * Provides a Collection of ${@link ServicePlan} related to this ProvisionProvider that can be seeded in.
     * Some clouds do not use this as they may be synced in from the public cloud. This is more of a factor for
     * On-Prem clouds that may wish to have some precanned plans provided for it.
     * @return Collection of ServicePlan sizes that can be seeded in at plugin startup.
     */
    @Override
    Collection<ServicePlan> getServicePlans() {
        def servicePlans = []

        servicePlans << new ServicePlan([code            : 'scvmm-1024', editable: true, name: ONE_CORE_1GB_DESC,
                                         description     : ONE_CORE_1GB_DESC, sortOrder: SORT_ORDER_1,
                                         maxStorage      : ONE_GB_STORAGE,
                                         maxMemory       : ONE_LONG * BYTES_IN_KB * BYTES_IN_KB * BYTES_IN_KB,
                                         maxCpu          : ZERO_LONG,
                                         maxCores        : ONE_LONG,
                                         customMaxStorage: true, customMaxDataStorage: true, addVolumes: true,])

        servicePlans << new ServicePlan([code            : 'scvmm-2048', editable: true, name: ONE_CORE_2GB_DESC,
                                         description     : ONE_CORE_2GB_DESC, sortOrder: SORT_ORDER_2,
                                         maxStorage      : TWO_GB_STORAGE,
                                         maxMemory       : TWO_LONG * BYTES_IN_KB * BYTES_IN_KB * BYTES_IN_KB,
                                         maxCpu          : ZERO_LONG,
                                         maxCores        : ONE_LONG,
                                         customMaxStorage: true, customMaxDataStorage: true, addVolumes: true,])

        servicePlans << new ServicePlan([code            : 'scvmm-4096', editable: true, name: ONE_CORE_4GB_DESC,
                                         description     : ONE_CORE_4GB_DESC, sortOrder: SORT_ORDER_3,
                                         maxStorage      : FORTY_GB_STORAGE,
                                         maxMemory       : FOUR_LONG * BYTES_IN_KB * BYTES_IN_KB * BYTES_IN_KB,
                                         maxCpu          : ZERO_LONG,
                                         maxCores        : ONE_LONG,
                                         customMaxStorage: true, customMaxDataStorage: true, addVolumes: true,])

        servicePlans << new ServicePlan([code            : 'scvmm-8192', editable: true, name: TWO_CORE_8GB_DESC,
                                         description     : TWO_CORE_8GB_DESC, sortOrder: SORT_ORDER_4,
                                         maxStorage      : EIGHTY_GB_STORAGE,
                                         maxMemory       : EIGHT_LONG * BYTES_IN_KB * BYTES_IN_KB * BYTES_IN_KB,
                                         maxCpu          : ZERO_LONG,
                                         maxCores        : CORES_TWO,
                                         customMaxStorage: true, customMaxDataStorage: true, addVolumes: true,])

        servicePlans << new ServicePlan([code            : 'scvmm-16384', editable: true, name: TWO_CORE_16GB_DESC,
                                         description     : TWO_CORE_16GB_DESC, sortOrder: SORT_ORDER_5,
                                         maxStorage      : ONE_SIXTY_GB_STORAGE,
                                         maxMemory       : SIXTEEN_LONG * BYTES_IN_KB * BYTES_IN_KB * BYTES_IN_KB,
                                         maxCpu          : ZERO_LONG,
                                         maxCores        : CORES_TWO,
                                         customMaxStorage: true, customMaxDataStorage: true, addVolumes: true,])

        servicePlans << new ServicePlan([code            : 'scvmm-24576', editable: true, name: FOUR_CORE_24GB_DESC,
                                         description     : FOUR_CORE_24GB_DESC, sortOrder: SORT_ORDER_6,
                                         maxStorage      : TWO_FORTY_GB_STORAGE,
                                         maxMemory       : THIRTY_TWO_LONG * BYTES_IN_KB * BYTES_IN_KB * BYTES_IN_KB,
                                         maxCpu          : ZERO_LONG,
                                         maxCores        : FOUR_LONG,
                                         customMaxStorage: true, customMaxDataStorage: true, addVolumes: true,])

        servicePlans << new ServicePlan([code            : 'scvmm-32768', editable: true, name: FOUR_CORE_32GB_DESC,
                                         description     : FOUR_CORE_32GB_DESC, sortOrder: SORT_ORDER_7,
                                         maxStorage      : THREE_TWENTY_GB_STORAGE,
                                         maxMemory       : THIRTY_TWO_LONG * BYTES_IN_KB * BYTES_IN_KB * BYTES_IN_KB,
                                         maxCpu          : ZERO_LONG, maxCores: CORES_FOUR,
                                         customMaxStorage: true, customMaxDataStorage: true, addVolumes: true,])

        servicePlans << new ServicePlan([code           : 'scvmm-hypervisor', editable: false,
                                         name           : 'SCVMM hypervisor',
                                         description    : 'custom hypervisor plan', sortOrder: SORT_ORDER_100,
                                         hidden         : true,
                                         maxCores       : ONE_LONG, maxCpu: ONE_LONG,
                                         maxStorage     : TWO_GB_STORAGE,
                                         maxMemory      : (long) (ONE_LONG * BYTES_IN_KB * BYTES_IN_KB * BYTES_IN_KB),
                                         active         : true,
                                         customCores    : true, customMaxStorage: true, customMaxDataStorage: true,
                                         customMaxMemory: true,])

        servicePlans << new ServicePlan([code            : 'internal-custom-scvmm', editable: false,
                                         name            : CUSTOM_SCVMM_DESC, description: CUSTOM_SCVMM_DESC,
                                         sortOrder       : ZERO_INT,
                                         customMaxStorage: true, customMaxDataStorage: true, addVolumes: true,
                                         customCpu       : true, customCores: true, customMaxMemory: true,
                                         deletable       : false,
                                         provisionable   : false, maxStorage: ZERO_LONG, maxMemory: ZERO_LONG,
                                         maxCpu          : ZERO_LONG,])

        return servicePlans
    }

    /**
     * Validates the provided provisioning options of a workload. A return of success = false will halt the
     * creation and display errors
     * @param opts options
     * @return Response from API. Errors should be returned in the errors Map with the key being the
     * field name and the error
     * message as the value.
     */
    @Override
    ServiceResponse validateWorkload(Map opts) {
        return ServiceResponse.success()
    }

    /**
     * This method is a key entry point in provisioning a workload. This could be a vm, a container, or something else.
     * Information associated with the passed Workload object is used to kick off the workload provision request
     * @param workload the Workload object we intend to provision along with some of the associated data needed
     * to determine
     *                 how best to provision the workload
     * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
     *                        in running the Workload
     * @param opts additional configuration options that may have been passed during provisioning
     * @return Response from API
     */

    // Refactored runWorkload method
    @SuppressWarnings(['UnnecessarySetter', 'UnnecessaryGetter'])
    @Override
    ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
        log.info "runWorkload: ${workload} ${workloadRequest} ${opts}"
        ProvisionResponse provisionResponse = new ProvisionResponse(
                success: true,
                installAgent: !opts?.noAgent,
                noAgent: opts?.noAgent
        )
        ServiceResponse<ProvisionResponse> rtn = new ServiceResponse()
        def server = workload.server
        Cloud cloud = server.cloud
        def scvmmOpts = [:]
        try {
            def containerConfig = workload.configMap
            opts.server = server

            def controllerNode = pickScvmmController(cloud)
            scvmmOpts = apiService.getScvmmZoneOpts(context, cloud)
            scvmmOpts.name = server.name
            scvmmOpts.controllerServerId = controllerNode.id

            def externalPoolId = resolveExternalPoolId(containerConfig, server, opts, workloadRequest)
            log.info("externalPoolId: ${externalPoolId}")

            def hostDatastoreResult = selectHostAndDatastore([
                    cloud          : cloud,
                    server         : server,
                    containerConfig: containerConfig,
                    workload       : workload,
                    opts           : opts,
                    externalPoolId : externalPoolId,
            ])
            if (!hostDatastoreResult.success) {
                return hostDatastoreResult.response
            }
            scvmmOpts += hostDatastoreResult.scvmmOpts

            scvmmOpts += apiService.getScvmmControllerOpts(cloud, controllerNode)
            def imageResult = handleImageSelection(containerConfig, server, workload, scvmmOpts, cloud)
            if (!imageResult.success) {
                return imageResult.response
            }
            scvmmOpts += imageResult.scvmmOpts

            def serverCreationResult = createServerAndFinalize([
                    scvmmOpts      : scvmmOpts,
                    server         : server,
                    workload       : workload,
                    workloadRequest: workloadRequest,
                    opts           : opts,
                    nodeId         : hostDatastoreResult.nodeId,
                    imageId        : imageResult.imageId,
                    virtualImage   : imageResult.virtualImage,
                    controllerNode : controllerNode,
            ])
            rtn = serverCreationResult
            if (rtn.data?.success) {
                def status = rtn.data.skipNetworkWait ? 'waiting for server status' : 'waiting for network'
                context.process.startProcessStep(workloadRequest.process,
                        new ProcessEvent(type: ProcessEvent.ProcessType.provisionNetwork), status).blockingGet()
            }
        } catch (e) {
            log.error("runWorkload error:${e}", e)
            provisionResponse.setError(e.message)
            rtn.success = false
            rtn.msg = e.message
            rtn.error = e.message
            rtn.data = provisionResponse
        } finally {
            cloneParentCleanup(scvmmOpts, rtn)
        }
        return rtn
    }

    // Helper to resolve externalPoolId
    protected String resolveExternalPoolId(Map containerConfig, ComputeServer server, Map opts,
                                           WorkloadRequest workloadRequest) {
        def externalPoolId = null
        log.info("runWorkload: containerConfig.resourcePool: ${containerConfig?.resourcePool} " +
                "server.resourcePool: ${server?.resourcePool?.code}")
        if (containerConfig.resourcePool || (opts.cloneContainerId && workloadRequest.hasProperty(CONFIG_CONTEXT) &&
                workloadRequest?.config?.resourcePoolId)) {
            try {
                externalPoolId = server.resourcePool?.externalId
            } catch (ignored) {
                externalPoolId = containerConfig.resourcePool
            }
        }
        return externalPoolId
    }

    protected Map selectHostAndDatastore(Map args) {
        Cloud cloud = args.cloud
        ComputeServer server = args.server
        Map containerConfig = args.containerConfig
        Workload workload = args.workload
        Map opts = args.opts
        String externalPoolId = args.externalPoolId
        def result = [success: true, scvmmOpts: [:], nodeId: null]
        try {
            def storageVolumes = server.volumes
            def rootVolume = storageVolumes.find { vol -> vol.rootVolume == true }
            def maxStorage = getContainerRootSize(workload)
            def maxMemory = workload.maxMemory ?: workload.instance.plan.maxMemory
            setDynamicMemory(result.scvmmOpts, workload.instance.plan)

            handleCloneContainer(opts, server)

            result.scvmmOpts.volumePaths = []
            def hostDatastoreInfo = getHostDatastoreInfo([
                    cloud          : cloud,
                    server         : server,
                    containerConfig: containerConfig,
                    rootVolume     : rootVolume,
                    maxStorage     : maxStorage,
                    maxMemory      : maxMemory,
                    externalPoolId : externalPoolId,
                    workload       : workload,
            ])
            result.nodeId = hostDatastoreInfo.nodeId
            result.scvmmOpts << hostDatastoreInfo.scvmmOpts

            updateRootVolume(rootVolume, hostDatastoreInfo.datastore)

            updateDataVolumes([
                    storageVolumes : storageVolumes,
                    cloud          : cloud,
                    server         : server,
                    containerConfig: containerConfig,
                    maxStorage     : maxStorage,
                    maxMemory      : maxMemory,
                    externalPoolId : externalPoolId,
                    workload       : workload,
                    scvmmOpts      : result.scvmmOpts,
            ])
        } catch (e) {
            log.error("Error in determining host and datastore: {}", e.message, e)
            def provisionResponse = new ProvisionResponse(success: false,
                    message: 'Error in determining host and datastore')
            result.success = false
            result.response = new ServiceResponse(success: false, msg: provisionResponse.message,
                    error: provisionResponse.message, data: provisionResponse)
        }
        return result
    }

    protected void handleCloneContainer(Map opts, ComputeServer server) {
        if (opts.cloneContainerId) {
            Workload cloneContainer = context.services.workload.get(opts.cloneContainerId.toLong())
            cloneContainer.server.volumes?.eachWithIndex { vol, i ->
                server.volumes[i].datastore = vol.datastore
                context.services.storageVolume.save(server.volumes[i])
            }
        }
    }

    protected Map getHostDatastoreInfo(Map args) {
        Cloud cloud = args.cloud
        ComputeServer server = args.server
        Map containerConfig = args.containerConfig
        StorageVolume rootVolume = args.rootVolume
        Long maxStorage = args.maxStorage
        Long maxMemory = args.maxMemory
        String externalPoolId = args.externalPoolId
        Workload workload = args.workload
        def node, datastore, volumePath, highlyAvailable
        (node, datastore, volumePath, highlyAvailable) =
                getHostAndDatastore(cloud, server.account, externalPoolId, containerConfig.hostId,
                        rootVolume?.datastore, rootVolume?.datastoreOption,
                        maxStorage, workload.instance.site?.id, maxMemory, true)
        def scvmmOpts = [
                datastoreId    : datastore?.externalId,
                hostExternalId : node?.externalId,
                volumePath     : volumePath,
                volumePaths    : [volumePath],
                highlyAvailable: highlyAvailable,
        ]
        return [nodeId: node?.id, scvmmOpts: scvmmOpts, datastore: datastore]
    }

    protected void updateRootVolume(StorageVolume rootVolume, Datastore datastore) {
        if (rootVolume) {
            rootVolume.datastore = datastore
            context.services.storageVolume.save(rootVolume)
        }
    }

    protected void updateDataVolumes(Map args) {
        List<StorageVolume> storageVolumes = args.storageVolumes
        Cloud cloud = args.cloud
        ComputeServer server = args.server
        Map containerConfig = args.containerConfig
        Long maxStorage = args.maxStorage
        Long maxMemory = args.maxMemory
        String externalPoolId = args.externalPoolId
        Workload workload = args.workload
        Map scvmmOpts = args.scvmmOpts
        storageVolumes?.each { vol ->
            if (!vol.rootVolume) {
                def tmpNode, tmpDatastore, tmpVolumePath, tmpHighlyAvailable
                (tmpNode, tmpDatastore, tmpVolumePath, tmpHighlyAvailable) =
                        getHostAndDatastore(cloud, server.account, externalPoolId,
                                containerConfig.hostId, vol?.datastore, vol?.datastoreOption,
                                maxStorage, workload.instance.site?.id, maxMemory)
                vol.datastore = tmpDatastore
                if (tmpVolumePath) {
                    vol.volumePath = tmpVolumePath
                    scvmmOpts.volumePaths << tmpVolumePath
                }
                context.services.storageVolume.save(vol)
            }
        }
    }

    // Helper to handle image selection and preparation
    protected Map handleImageSelection(Map containerConfig, ComputeServer server, Workload workload,
                                       Map scvmmOpts, Cloud cloud) {
        def result = [success: true, scvmmOpts: [:], response: null, imageId: null, virtualImage: null]
        def imageId
        def virtualImage = server.sourceImage

        if (containerConfig.template || virtualImage?.id) {
            virtualImage = resolveVirtualImage(containerConfig, virtualImage)
            setScvmmImageProperties(scvmmOpts, virtualImage)

            imageId = resolveImageId(scvmmOpts, virtualImage, cloud)
            log.debug("imageId: ${imageId}")

            if (!imageId) {
                def cloudFiles = fetchCloudFiles(virtualImage)
                log.debug("cloudFiles?.size(): ${cloudFiles?.size()}")
                if (cloudFiles?.size() == ZERO_INT) {
                    setCloudFilesError(server, result, virtualImage)
                }
                imageId = handleContainerImage([
                        scvmmOpts   : scvmmOpts,
                        workload    : workload,
                        virtualImage: virtualImage,
                        cloudFiles  : cloudFiles,
                        cloud       : cloud,
                        result      : result,
                ])
            }

            if (scvmmOpts.templateId && scvmmOpts.isSyncdImage) {
                scvmmOpts.additionalTemplateDisks = additionalTemplateDisksConfig(workload, scvmmOpts)
                log.debug "scvmmOpts.additionalTemplateDisks ${scvmmOpts.additionalTemplateDisks}"
            }
        }

        result.imageId = imageId
        result.virtualImage = virtualImage
        return result
    }

    protected VirtualImage resolveVirtualImage(Map containerConfig, VirtualImage virtualImage) {
        if (containerConfig.template) {
            return context.services.virtualImage.get(containerConfig.template?.toLong())
        }
        return virtualImage
    }

    protected void setScvmmImageProperties(Map scvmmOpts, VirtualImage virtualImage) {
        scvmmOpts.scvmmGeneration = virtualImage?.getConfigProperty(GENERATION_FIELD) ?: SCVMM_GENERATION1_VALUE
        scvmmOpts.isSyncdImage = virtualImage?.refType == COMPUTE_ZONE_REF_TYPE
        scvmmOpts.isTemplate = !(virtualImage?.remotePath != null) && !virtualImage?.systemImage
        scvmmOpts.templateId = virtualImage?.externalId
    }

    protected String resolveImageId(Map scvmmOpts, VirtualImage virtualImage, Cloud cloud) {
        if (scvmmOpts.isSyncdImage) {
            scvmmOpts.diskExternalIdMappings = getDiskExternalIds(virtualImage, cloud)
            return scvmmOpts.diskExternalIdMappings.find { vol -> vol.rootVolume == true }?.externalId
        }
        return virtualImage.externalId
    }

    protected List fetchCloudFiles(VirtualImage virtualImage) {
        return context.async.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()
    }

    @SuppressWarnings('UnnecessarySetter')
    protected void setCloudFilesError(ComputeServer server, Map result, VirtualImage virtualImage) {
        server.statusMessage = 'Failed to find cloud files'
        def provisionResponse = new ProvisionResponse(success: false)
        provisionResponse.setError("Cloud files could not be found for ${virtualImage}")
        result.success = false
        result.response = new ServiceResponse(success: false, msg: "Cloud files could not be found for ${virtualImage}",
                data: provisionResponse)
    }

    @SuppressWarnings('DuplicateMapLiteral')
    protected String handleContainerImage(Map args) {
        Map scvmmOpts = args.scvmmOpts
        Workload workload = args.workload
        VirtualImage virtualImage = args.virtualImage
        List cloudFiles = args.cloudFiles
        Cloud cloud = args.cloud
        Map result = args.result
        def containerImage = [
                name          : virtualImage.name ?: workload.workloadType.imageCode,
                minDisk       : INTEGER_FIVE,
                minRam        : FIVE_TWELVE_INT * ComputeUtility.ONE_MEGABYTE,
                virtualImageId: virtualImage.id,
                tags          : MORPHEUS_UBUNTU_TAGS,
                imageType     : virtualImage.imageType,
                containerType : VHD_CONTAINER_TYPE,
                cloudFiles    : cloudFiles,
        ]
        scvmmOpts.image = containerImage
        scvmmOpts.userId = workload.instance.createdBy?.id
        log.debug "scvmmOpts: ${scvmmOpts}"
        def imageResults = apiService.insertContainerImage(scvmmOpts)
        log.debug("imageResults: ${imageResults}")
        if (imageResults.success == true) {
            def imageId = imageResults.imageId
            def locationConfig = [
                    virtualImage: virtualImage,
                    code        : "scvmm.image.${cloud.id}.${virtualImage.externalId}",
                    internalId  : virtualImage.externalId,
                    externalId  : virtualImage.externalId,
                    imageName   : virtualImage.name,
            ]
            VirtualImageLocation location = new VirtualImageLocation(locationConfig)
            context.services.virtualImage.location.create(location)
            return imageId
        }
        def provisionResponse = new ProvisionResponse(success: false)
        result.success = false
        result.response = new ServiceResponse(success: false, msg: provisionResponse.message,
                error: provisionResponse.message, data: provisionResponse)
        return null
    }

    protected ServiceResponse<ProvisionResponse> createServerAndFinalize(Map args) {
        Map scvmmOpts = args.scvmmOpts
        ComputeServer server = args.server
        Workload workload = args.workload
        WorkloadRequest workloadRequest = args.workloadRequest
        Map opts = args.opts
        Long nodeId = args.nodeId
        String imgId = args.imageId
        VirtualImage virtImage = args.virtualImage
        ComputeServer controlNode = args.controllerNode
        ProvisionResponse provisionResponse = new ProvisionResponse(success: true,
                installAgent: !opts?.noAgent, noAgent: opts?.noAgent)
        ServiceResponse<ProvisionResponse> rtn = new ServiceResponse()
        def imageId = imgId
        def virtualImage = virtImage

        if (!imageId) {
            handleImageUploadFailure(server, provisionResponse, rtn)
            return rtn
        }

        prepareScvmmOpts(scvmmOpts, virtualImage, workloadRequest, opts)
        prepareServer(server, workload, virtualImage, scvmmOpts, nodeId)
        scvmmOpts.imageId = imageId
        scvmmOpts.server = server
        scvmmOpts += getScvmmContainerOpts(workload)
        prepareNetworkAndLicense(scvmmOpts, server, opts, workloadRequest)

        if (virtualImage?.isCloudInit || scvmmOpts.isSysprep) {
            handleCloudInit(scvmmOpts, workload, workloadRequest, opts, virtualImage)
        }

        if (opts.cloneContainerId) {
            handleCloneContainerOpts([
                    scvmmOpts      : scvmmOpts,
                    opts           : opts,
                    server         : server,
                    workloadRequest: workloadRequest,
                    virtualImage   : virtualImage,
                    controlNode    : controlNode,
            ])
        }

        log.debug("create server: ${scvmmOpts}")
        def createResults = apiService.createServer(scvmmOpts)
        log.debug("createResults: ${createResults}")
        scvmmOpts.deleteDvdOnComplete = createResults.deleteDvdOnComplete

        // Adding the deleteDvdOnComplete to the workload config for future reference in the finalize step.
        // This is done as adding it to scvmmOpts above doesn't persist it anywhere.
        def workloadConfig = workload.configMap
        workloadConfig.deleteDvdOnComplete = createResults.deleteDvdOnComplete
        workload.configMap = workloadConfig
        context.async.workload.save(workload).blockingGet()

        if (createResults.success == true) {
            handleServerReady([
                    createResults    : createResults,
                    scvmmOpts        : scvmmOpts,
                    server           : server,
                    opts             : opts,
                    nodeId           : nodeId,
                    workloadRequest  : workloadRequest,
                    provisionResponse: provisionResponse,
            ])
        } else {
            handleServerCreateFailure(createResults, server, provisionResponse)
        }

        finalizeProvisionResponse(provisionResponse, rtn)
        return rtn
    }

    protected void handleImageUploadFailure(ComputeServer server, ProvisionResponse provisionResponse,
                                            ServiceResponse rtn) {
        server.statusMessage = 'Failed to upload image'
        context.async.computeServer.save(server).blockingGet()
        provisionResponse.success = false
        rtn.success = false
        rtn.data = provisionResponse
    }

    protected void prepareScvmmOpts(Map scvmmOpts, VirtualImage virtualImage,
                                    WorkloadRequest workloadRequest, Map opts) {
        scvmmOpts.isSysprep = virtualImage?.isSysprep
        if (scvmmOpts.isSysprep) {
            scvmmOpts.OSName = apiService.getMapScvmmOsType(virtualImage.osType.code, false)
        }
        opts.installAgent = (virtualImage ? virtualImage.installAgent : true) &&
                !workloadRequest.cloudConfigOpts?.noAgent
        opts.skipNetworkWait = virtualImage?.imageType == 'iso' || !virtualImage?.vmToolsInstalled
    }

    @SuppressWarnings('ParameterReassignment')
    protected void prepareServer(ComputeServer server, Workload workload, VirtualImage virtualImage,
                                 Map scvmmOpts, Long nodeId) {
        assignUserGroups(workload)
        assignSourceImageAndParent(server, virtualImage, scvmmOpts, nodeId)
        assignOsType(server, virtualImage)
        assignServerType(server)
        server = saveAndGetMorpheusServer(server, true)
    }

    protected void assignUserGroups(Workload workload) {
        def userGroups = workload.instance?.userGroups?.toList() ?: []
        if (workload.instance?.userGroup && !userGroups.contains(workload.instance.userGroup)) {
            userGroups << workload.instance.userGroup
        }
    }

    protected void assignSourceImageAndParent(ComputeServer server, VirtualImage virtualImage, Map scvmmOpts,
                                              Long nodeId) {
        server.sourceImage = virtualImage
        server.externalId = scvmmOpts.name
        server.parentServer = context.services.computeServer.get(nodeId)
        server.serverOs = server.serverOs ?: virtualImage.osType
    }

    protected void assignOsType(ComputeServer server, VirtualImage virtualImage) {
        def osplatform = virtualImage?.osType?.platform?.toString()?.toLowerCase()
                ?: virtualImage?.platform?.toString()?.toLowerCase()
        server.osType = [WINDOWS_PLATFORM, OSX_PLATFORM].contains(osplatform) ? osplatform : LINUX_PLATFORM
    }

    protected void assignServerType(ComputeServer server) {
        def newType = this.findVmNodeServerTypeForCloud(server.cloud.id,
                server.osType, PROVISION_TYPE_CODE)
        if (newType && server.computeServerType != newType) {
            server.computeServerType = newType
        }
    }

    protected void prepareNetworkAndLicense(Map scvmmOpts, ComputeServer server,
                                            Map opts, WorkloadRequest workloadRequest) {
        scvmmOpts.hostname = server.externalHostname
        scvmmOpts.domainName = server.externalDomain
        scvmmOpts.fqdn = scvmmOpts.hostname
        if (scvmmOpts.domainName) {
            scvmmOpts.fqdn += '.' + scvmmOpts.domainName
        }
        scvmmOpts.networkConfig = opts.networkConfig
        if (scvmmOpts.networkConfig?.primaryInterface?.network?.pool) {
            scvmmOpts.networkConfig.primaryInterface.poolType =
                    scvmmOpts.networkConfig.primaryInterface.network.pool.type.code
        }
        scvmmOpts.licenses = workloadRequest.cloudConfigOpts.licenses
        log.debug("scvmmOpts.licenses: ${scvmmOpts.licenses}")
        if (scvmmOpts.licenses) {
            def license = scvmmOpts.licenses[0]
            scvmmOpts.license = [fullName: license.fullName, productKey: license.licenseKey, orgName: license.orgName]
        }
    }

    protected void handleCloudInit(Map scvmmOpts, Workload workload, WorkloadRequest workloadRequest,
                                   Map opts, VirtualImage virtualImage) {
        def initOptions = constructCloudInitOptions(workload, workloadRequest,
                opts.installAgent, scvmmOpts.platform, virtualImage, scvmmOpts.licenses, scvmmOpts)
        scvmmOpts.cloudConfigUser = initOptions.cloudConfigUser
        scvmmOpts.cloudConfigMeta = initOptions.cloudConfigMeta
        scvmmOpts.cloudConfigBytes = initOptions.cloudConfigBytes
        scvmmOpts.cloudConfigNetwork = initOptions.cloudConfigNetwork
        if (initOptions.licenseApplied) {
            opts.licenseApplied = true
        }
        opts.unattendCustomized = initOptions.unattendCustomized
    }

    protected void handleCloneContainerOpts(Map args) {
        Map scvmmOpts = args.scvmmOpts
        Map opts = args.opts
        ComputeServer server = args.server
        WorkloadRequest workloadRequest = args.workloadRequest
        VirtualImage virtualImage = args.virtualImage
        ComputeServer controlNode = args.controlNode

        Workload parentContainer = getParentContainer(opts)
        setCloneContainerIds(scvmmOpts, parentContainer)
        handleCloneVmStatus(parentContainer, scvmmOpts)

        log.debug "Handling startup of the original VM"
        def cloneBaseOpts = buildCloneBaseOpts(parentContainer, workloadRequest,
                opts, scvmmOpts, virtualImage, server, controlNode)
        scvmmOpts.cloneBaseOpts = cloneBaseOpts
    }

    protected Workload getParentContainer(Map opts) {
        return context.services.workload.get(opts.cloneContainerId.toLong())
    }

    protected void setCloneContainerIds(Map scvmmOpts, Workload parentContainer) {
        scvmmOpts.cloneContainerId = parentContainer.id
        scvmmOpts.cloneVMId = parentContainer.server.externalId
    }

    protected void handleCloneVmStatus(Workload parentContainer, Map scvmmOpts) {
        if (parentContainer.status == Workload.Status.running) {
            stopWorkload(parentContainer)
            scvmmOpts.startClonedVM = true
        }
    }

    @SuppressWarnings('ParameterCount')
    protected Map buildCloneBaseOpts(Workload parentContainer, WorkloadRequest workloadRequest, Map opts, Map scvmmOpts,
                                     VirtualImage virtualImage, ComputeServer server, ComputeServer controlNode) {
        def cloneBaseOpts = [:]
        cloneBaseOpts.cloudInitIsoNeeded = (parentContainer.server.sourceImage &&
                parentContainer.server.sourceImage.isCloudInit &&
                parentContainer.server.serverOs?.platform != WINDOWS_PLATFORM)
        if (cloneBaseOpts.cloudInitIsoNeeded) {
            def initOptions = constructCloudInitOptions(parentContainer, workloadRequest,
                    opts.installAgent, scvmmOpts.platform, virtualImage, scvmmOpts.licenses, scvmmOpts)
            def clonedScvmmOpts = apiService.getScvmmZoneOpts(context, server.cloud)
            clonedScvmmOpts += apiService.getScvmmControllerOpts(server.cloud, controlNode)
            clonedScvmmOpts += getScvmmContainerOpts(parentContainer)
            cloneBaseOpts.imageFolderName = clonedScvmmOpts.serverFolder
            cloneBaseOpts.diskFolder = "${clonedScvmmOpts.diskRoot}\\${cloneBaseOpts.imageFolderName}"
            cloneBaseOpts.cloudConfigBytes = initOptions.cloudConfigBytes
            cloneBaseOpts.cloudConfigNetwork = initOptions.cloudConfigNetwork
            cloneBaseOpts.clonedScvmmOpts = clonedScvmmOpts
            cloneBaseOpts.clonedScvmmOpts.controllerServerId = controlNode.id
            if (initOptions.licenseApplied) {
                opts.licenseApplied = true
            }
            opts.unattendCustomized = initOptions.unattendCustomized
        }
        return cloneBaseOpts
    }

    @SuppressWarnings('UnnecessarySetter')
    protected void handleServerReady(Map args) {
        Map createResults = args.createResults
        Map scvmmOpts = args.scvmmOpts
        ComputeServer server = args.server
        Long nodeId = args.nodeId
        ProvisionResponse provisionResponse = args.provisionResponse
        def node = context.services.computeServer.get(nodeId)
        if (createResults.server) {
            updateServerAfterCreation(createResults, server)
            handleServerDetails(scvmmOpts, server, provisionResponse, createResults, node)
        } else {
            handleServerCreateFailure(createResults, server, provisionResponse)
        }
    }

    protected void updateServerAfterCreation(Map createResults, ComputeServer server) {
        def serverDisks = createResults.server.disks
        if (serverDisks && server.volumes) {
            def storageVolumes = server.volumes
            def rootVolume = storageVolumes.find { vol -> vol.rootVolume == true }
            updateRootVolumeAfterCreation(rootVolume, serverDisks, server)
            updateDataVolumesAfterCreation(storageVolumes, serverDisks, server)
        }
    }

    @SuppressWarnings('MethodParameterTypeRequired')
    protected void updateRootVolumeAfterCreation(StorageVolume rootVolume, def serverDisks, ComputeServer server) {
        if (rootVolume) {
            rootVolume.externalId = serverDisks.diskMetaData[serverDisks.osDisk?.externalId]?.VhdID
            context.services.storageVolume.save(rootVolume)
            rootVolume.datastore = loadDatastoreForVolume(
                    server.cloud,
                    serverDisks.diskMetaData[rootVolume.externalId]?.HostVolumeId,
                    serverDisks.diskMetaData[rootVolume.externalId]?.FileShareId,
                    serverDisks.diskMetaData[rootVolume.externalId]?.PartitionUniqueId
            ) ?: rootVolume.datastore
            context.services.storageVolume.save(rootVolume)
        }
    }

    @SuppressWarnings('MethodParameterTypeRequired')
    protected void updateDataVolumesAfterCreation(List<StorageVolume> storageVolumes, def serverDisks,
                                                  ComputeServer server) {
        storageVolumes.each { storageVolume ->
            def dataDisk = serverDisks.dataDisks.find { vol -> vol.id == storageVolume.id }
            if (dataDisk) {
                def newExternalId = serverDisks.diskMetaData[dataDisk.externalId]?.VhdID
                if (newExternalId) {
                    storageVolume.externalId = newExternalId
                }
                storageVolume.datastore = loadDatastoreForVolume(
                        server.cloud,
                        serverDisks.diskMetaData[storageVolume.externalId]?.HostVolumeId,
                        serverDisks.diskMetaData[storageVolume.externalId]?.FileShareId,
                        serverDisks.diskMetaData[storageVolume.externalId]?.PartitionUniqueId
                ) ?: storageVolume.datastore
                context.services.storageVolume.save(storageVolume)
            }
        }
    }

    protected void handleServerDetails(Map scvmmOpts, ComputeServer server,
                                       ProvisionResponse provisionResponse, Map createResults, ComputeServer node) {
        def updatedServer = context.services.computeServer.get(server.id)
        updatedServer.with {
            externalId = createResults.server.id
            internalId = createResults.server.VMId
            parentServer = node
            osDevice = DEV_SDA_PATH
            dataDevice = DEV_SDA_PATH
            lvmEnabled = false
            managed = true
            capacityInfo = new ComputeCapacityInfo(maxCores: scvmmOpts.maxCores,
                    maxMemory: scvmmOpts.maxMemory, maxStorage: scvmmOpts.maxTotalStorage)
            status = PROVISIONED_STATUS
        }
        MorpheusUtil.saveAndGetMorpheusServer(context, updatedServer)

        // start it
        log.info("Starting Server  ${scvmmOpts.name}")
        apiService.startServer(scvmmOpts, scvmmOpts.externalId)
        provisionResponse.success = true
        // By default installAgent is true.
        // 1. The below section instructs the subsequent code to
        // 1a. skip agent installation for Linux VMs (as cloud init will take care of installing agent)
        // 1b. If we are in a Clone scenario, we don't want to skip agent installation here.
        if (server?.platform == LINUX_PLATFORM && !scvmmOpts.cloneVMId) {
            provisionResponse.installAgent = false
        }
        log.debug("provisionResponse.success: ${provisionResponse.success}")
    }

    protected void handleServerCreateFailure(Map createResults, ComputeServer server,
                                             ProvisionResponse provisionResponse) {
        if (createResults.server?.externalId) {
            server.externalId = createResults.server.externalId
        }
        server.statusMessage = 'Failed to create server'
        context.async.computeServer.save(server).blockingGet()
        provisionResponse.success = false
    }

    protected void finalizeProvisionResponse(ProvisionResponse provisionResponse, ServiceResponse rtn) {
        if (provisionResponse.success != true) {
            rtn.success = false
            rtn.msg = provisionResponse.message ?: VM_CONFIG_ERROR_MSG
            rtn.error = provisionResponse.message
            rtn.data = provisionResponse
        } else {
            rtn.success = true
            rtn.data = provisionResponse
        }
    }

    MorpheusContext getContext() {
        return this.context
    }

    // Refactored cloneParentCleanup
    @SuppressWarnings('CatchException')
    protected void cloneParentCleanup(Map<String, Object> scvmmOpts, ServiceResponse rtn) {
        if (scvmmOpts.cloneVMId && scvmmOpts.cloneContainerId) {
            try {
                handleParentVmStartup(scvmmOpts)
                handleDvdIsoCleanup(scvmmOpts)
            } catch (Exception ex) {
                log.error("Error during parent VM cleanup for ${scvmmOpts.cloneVMId}: ${ex.message}", ex)
                rtn.warning = true
                rtn.msg = "Error during parent VM cleanup for ${scvmmOpts.cloneVMId}: ${ex.message}"
            }
        }
    }

    protected void handleParentVmStartup(Map<String, Object> scvmmOpts) {
        if (scvmmOpts.startClonedVM) {
            log.debug "Handling startup of the original VM: ${scvmmOpts.cloneVMId}"
            def startServerOpts = [async: true]
            if (scvmmOpts.cloneBaseOpts?.clonedScvmmOpts) {
                startServerOpts += scvmmOpts.cloneBaseOpts.clonedScvmmOpts
            }
            def startResults = apiService.startServer(startServerOpts, scvmmOpts.cloneVMId)
            if (!startResults.success) {
                log.error "Failed to start the parent VM ${scvmmOpts.cloneVMId}: ${startResults.msg}"
            }
            Workload savedContainer = context.services.workload.find(
                    new DataQuery().withFilter(ID_FIELD, scvmmOpts.cloneContainerId.toLong()))
            if (savedContainer) {
                savedContainer.userStatus = Workload.Status.running.toString()
                savedContainer.status = Workload.Status.running
                context.services.workload.save(savedContainer)
            }
            ComputeServer savedServer = context.services.computeServer.get(savedContainer.server?.id)
            if (savedServer) {
                context.async.computeServer.updatePowerState(savedServer.id, ComputeServer.PowerState.on)
            }
        }
    }

    protected void handleDvdIsoCleanup(Map<String, Object> scvmmOpts) {
        if (scvmmOpts.cloneBaseOpts?.clonedScvmmOpts) {
            log.debug "Checking for DVD/ISO cleanup on parent VM: ${scvmmOpts.cloneVMId}"
            def setCdromResults = apiService.setCdrom(scvmmOpts.cloneBaseOpts.clonedScvmmOpts)
            if (!setCdromResults.success) {
                log.error "Failed to unmount DVD of parent VM. Please unmount manually as this may cause" +
                        " issues for further clone operations."
            }
            if (scvmmOpts.deleteDvdOnComplete?.deleteIso) {
                log.debug "Deleting ISO of parent VM: ${scvmmOpts.deleteDvdOnComplete.deleteIso}"
                apiService.deleteIso(scvmmOpts.cloneBaseOpts.clonedScvmmOpts,
                        scvmmOpts.deleteDvdOnComplete.deleteIso)
            }
        }
    }

    Map getUserAddedVolumes(Workload workload) {
        def configs = workload.configs
        if (configs instanceof String) {
            configs = new JsonSlurper().parseText(configs)
        }
        def volumesList = configs?.volumes ?: []
        def storageVolumeProps = StorageVolume.metaClass.properties*.name as Set
        def nonRootVolumes = volumesList.findAll { vol -> !vol.rootVolume }
        def nonRootCount = nonRootVolumes.size()
        def storageVolumes = volumesList.findAll { volList -> !volList.rootVolume }.collect { volMap ->
            def filteredVolMap = volMap.findAll { k, v -> storageVolumeProps.contains(k) }
            if (filteredVolMap.id == MINUS_ONE) {
                new StorageVolume(filteredVolMap)
            }
        }.findAll { v -> v != null }
        return [count: nonRootCount, volumes: storageVolumes]
    }

    List additionalTemplateDisksConfig(Workload workload, Map scvmmOpts) {
        // Determine what additional disks need to be added after provisioning
        def additionalTemplateDisks = []
        def totalDataVolsAndNewVols = getUserAddedVolumes(workload)
        def totalDataVols = totalDataVolsAndNewVols.count
        def userAddedVolumes = totalDataVolsAndNewVols.volumes
        def dataDisks = getContainerDataDiskList(workload)
        log.debug "dataDisks: ${dataDisks} ${dataDisks?.size()}"

        // if totalDataVols == getUserAddedVolumes its a new vm creation
        // else its a clone and we only want to add the new volumes
        def nonRootAdditionalVolumes = []
        if (totalDataVols == userAddedVolumes.size()) {
            log.debug "New VM creation - adding all non root volumes"
            nonRootAdditionalVolumes = dataDisks
        } else {
            log.debug "Clone Scenario - adding all user added volumes"
            nonRootAdditionalVolumes = userAddedVolumes
        }

        // scvmmOpts.diskExternalIdMappings will usually contain the virtualImage disk externalId
        def diskExternalIdMappings = scvmmOpts.diskExternalIdMappings
        def additionalDisksRequired = dataDisks?.size() + SORT_ORDER_1 > diskExternalIdMappings?.size()
        def busNumber = ZERO_STRING
        if (additionalDisksRequired) {
            def diskCounter = diskExternalIdMappings.size()
            // These new volumes will be added after the VM is created.
            nonRootAdditionalVolumes?.eachWithIndex { StorageVolume sv, index ->
                additionalTemplateDisks << [idx     : index + 1, diskCounter: diskCounter,
                                            diskSize: sv.maxStorage, busNumber: busNumber]
                diskCounter++
            }
        }

        log.debug "returning additionalTemplateDisks ${additionalTemplateDisks}"
        return additionalTemplateDisks
    }

    @SuppressWarnings('ParameterCount')
    protected Map constructCloudInitOptions(Workload container, WorkloadRequest workloadRequest, boolean installAgent,
                                            String platform, VirtualImage virtualImage,
                                            Object licenses, Map scvmmOpts) {
        log.debug("constructCloudInitOptions: ${container}, ${installAgent}, ${platform}")
        def rtn = [:]
        ComputeServer server = container.server
        Cloud zone = server.cloud
        def cloudConfigOpts =
                context.services.provision.buildCloudConfigOptions(zone, server, installAgent, scvmmOpts)

        // Special handling for install agent on SCVMM (determine if we are installing via cloud init)
        cloudConfigOpts.installAgent = false
        if (installAgent == true) {
            if (zone.agentMode == 'cloudInit' && (platform != WINDOWS_PLATFORM || scvmmOpts.isSysprep)) {
                cloudConfigOpts.installAgent = true
            }
        }
        rtn.installAgent = installAgent && (cloudConfigOpts.installAgent != true)
        // If cloudConfigOpts.installAgent == true, it means we are installing the agent via cloud config..
        // so do NOT install is via morpheus
        cloudConfigOpts.licenses = licenses
        rtn.cloudConfigUser = workloadRequest?.cloudConfigUser ?: null
        rtn.cloudConfigMeta = workloadRequest?.cloudConfigMeta ?: null
        rtn.cloudConfigNetwork = workloadRequest?.cloudConfigNetwork ?: null
        if (cloudConfigOpts.licenseApplied) {
            rtn.licenseApplied = true
        }
        rtn.unattendCustomized = cloudConfigOpts.unattendCustomized
        rtn.cloudConfigUnattend = workloadRequest.cloudConfigUser
        def isoBuffer = context.services.provision.buildIsoOutputStream(virtualImage.isSysprep,
                PlatformType.valueOf(platform), rtn.cloudConfigMeta, rtn.cloudConfigUnattend, rtn.cloudConfigNetwork)
        rtn.cloudConfigBytes = isoBuffer
        return rtn
    }

    List getDiskExternalIds(VirtualImage virtualImage, Cloud cloud) {
        // The mapping of volumes is off of the VirtualImageLocation
        VirtualImageLocation location = getVirtualImageLocation(virtualImage, cloud)

        def rtn = []
        def rootVolume = location.volumes.find { sv -> sv.rootVolume }
        rtn << [rootVolume: true, externalId: rootVolume.externalId, idx: ZERO_INT,]
        location.volumes?.eachWithIndex { vol, index ->
            if (!vol.rootVolume) {
                rtn << [rootVolume: false, externalId: vol.externalId, idx: SORT_ORDER_1 + index,]
            }
        }
        return rtn
    }

    protected void setDynamicMemory(Map targetMap, ServicePlan plan) {
        log.debug "setDynamicMemory: ${plan}"
        if (plan) {
            def ranges = plan.getConfigProperty('ranges') ?: [:]
            targetMap.minDynamicMemory = ranges.minMemory ?: null
            targetMap.maxDynamicMemory = ranges.maxMemory ?: null
        }
    }

    protected VirtualImageLocation getVirtualImageLocation(VirtualImage virtualImage, Cloud cloud) {
        def location = context.services.virtualImage.location.find(new DataQuery().withFilters(
                new DataFilter(VIRTUAL_IMAGE_ID_FIELD, virtualImage.id),
                new DataOrFilter(
                        new DataAndFilter(
                                new DataFilter(REF_TYPE_FIELD, COMPUTE_ZONE_REF_TYPE),
                                new DataFilter(REF_ID_FIELD, cloud.id)
                        ),
                        new DataAndFilter(
                                new DataFilter('virtualImage.owner.id', cloud.owner.id),
                                new DataFilter('imageRegion', cloud.regionCode)
                        )
                )
        ))
        return location
    }

    /**
     * This method is called after successful completion of runWorkload and provides an opportunity to
     * perform some final actions during the provisioning process. For example, ejected CDs, cleanup actions, etc
     * @param workload the Workload object that has been provisioned
     * @return Response from the API
     */
    @Override
    ServiceResponse finalizeWorkload(Workload workload) {
        def scvmmOpts = getAllScvmmOpts(workload)
        // Fetch the workload again to get the latest configMap with deleteDvdOnComplete
        def fetchedWorkload = context.async.workload.get(workload.id).blockingGet()
        // Handle DVD cleanup if needed
        if (fetchedWorkload.configMap?.deleteDvdOnComplete?.removeIsoFromDvd) {
            apiService.cdrom = scvmmOpts
            if (fetchedWorkload.configMap?.deleteDvdOnComplete?.deleteIso) {
                apiService.deleteIso(scvmmOpts, fetchedWorkload.configMap.deleteDvdOnComplete.deleteIso)
            }
        }
        return ServiceResponse.success()
    }

    /**
     * Issues the remote calls necessary top stop a workload element from running.
     * @param workload the Workload we want to shut down
     * @return Response from API
     */
    @Override
    ServiceResponse stopWorkload(Workload workload) {
        def rtn = ServiceResponse.prepare()
        try {
            if (workload.server?.externalId) {
                def scvmmOpts = getAllScvmmOpts(workload)
                def results = apiService.stopServer(scvmmOpts, scvmmOpts.vmId)
                if (results.success == true) {
                    rtn.success = true
                }
            } else {
                rtn.success = false
                rtn.msg = VM_NOT_FOUND_MSG
            }
        } catch (e) {
            log.error("stopWorkload error: ${e}", e)
            rtn.msg = e.message
        }
        return rtn
    }

    ComputeServer pickScvmmController(Cloud cloud) {
        // Could be using a shared controller
        def sharedControllerId = cloud.getConfigProperty(SHARED_CONTROLLER_KEY)
        def sharedController =
                sharedControllerId ? context.services.computeServer.get(sharedControllerId?.toLong()) : null
        if (sharedController) {
            return sharedController
        }
        def rtn = context.services.computeServer.find(new DataQuery()
                .withFilter(CLOUD_ID_FIELD, cloud.id)
                .withFilter(COMPUTE_SERVER_TYPE_CODE_FIELD, SCVMM_CONTROLLER_CODE)
                .withJoin(COMPUTE_SERVER_TYPE_FIELD))
        if (rtn == null) {
            // old zone with wrong type
            rtn = context.services.computeServer.find(new DataQuery()
                    .withFilter(CLOUD_ID_FIELD, cloud.id)
                    .withFilter(COMPUTE_SERVER_TYPE_CODE_FIELD, SCVMM_HYPERVISOR_TYPE)
                    .withJoin(COMPUTE_SERVER_TYPE_FIELD))
            if (rtn == null) {
                rtn = context.services.computeServer.find(new DataQuery()
                        .withFilter(CLOUD_ID_FIELD, cloud.id)
                        .withFilter('serverType', 'hypervisor'))
            }
            // if we have tye type
            if (rtn) {
                rtn.computeServerType = new ComputeServerType(code: SCVMM_CONTROLLER_CODE)
                context.services.computeServer.save(rtn)
            }
        }
        return rtn
    }

    Long getContainerRootSize(Workload container) {
        def rtn
        def rootDisk = getContainerRootDisk(container)
        if (rootDisk) {
            rtn = rootDisk.maxStorage
        } else {
            rtn = container.maxStorage ?: container.instance.plan.maxStorage
        }
        return rtn
    }

    StorageVolume getContainerRootDisk(Workload container) {
        def rtn = container.server?.volumes?.find { vol -> vol.rootVolume == true }
        return rtn
    }

    Long getContainerVolumeSize(Workload container) {
        def rtn = container.maxStorage ?: container.instance.plan.maxStorage
        if (container.server?.volumes?.size() > ZERO_INT) {
            def newMaxStorage = container.server.volumes.sum { vol -> vol.maxStorage ?: 0 }
            if (newMaxStorage > rtn) {
                rtn = newMaxStorage
            }
        }
        return rtn
    }

    static List<StorageVolume> getContainerDataDiskList(Workload container) {
        def rtn = container.server?.volumes
                ?.findAll { vol -> vol.rootVolume == false }
                ?.sort { sv -> sv.id }
        return rtn
    }

    // Refactored getScvmmContainerOpts
    Map getScvmmContainerOpts(Workload container) {
        def serverConfig = container.server.configMap
        def containerConfig = container.configMap
        def network = getNetwork(containerConfig)
        def serverFolder = "morpheus\\morpheus_server_${container.server.id}"
        def maxMemory = getMaxMemory(container)
        def maxCpu = getMaxCpu(container)
        def maxCores = getMaxCores(container)
        def maxStorage = getContainerRootSize(container)
        def maxTotalStorage = getContainerVolumeSize(container)
        def dataDisks = getContainerDataDiskList(container)
        def resourcePool = getResourcePool(container)
        def platform = getPlatform(container)
        def scvmmCapabilityProfile = getScvmmCapabilityProfile(containerConfig)
        def accountId = container.account?.id

        return [
                config                : serverConfig,
                vmId                  : container.server.externalId,
                name                  : container.server.externalId,
                server                : container.server,
                serverId              : container.server?.id,
                memory                : maxMemory,
                maxCpu                : maxCpu,
                maxCores              : maxCores,
                serverFolder          : serverFolder,
                hostname              : container.hostname,
                network               : network,
                networkId             : network?.id,
                platform              : platform,
                externalId            : container.server.externalId,
                networkType           : containerConfig.networkType,
                containerConfig       : containerConfig,
                resourcePool          : resourcePool?.externalId,
                hostId                : containerConfig.hostId,
                osDiskSize            : maxStorage,
                maxTotalStorage       : maxTotalStorage,
                dataDisks             : dataDisks,
                scvmmCapabilityProfile: scvmmCapabilityProfile,
                accountId             : accountId,
        ]
    }

    protected Network getNetwork(Map containerConfig) {
        return context.services.cloud.network.get(containerConfig.networkId?.toLong())
    }

    protected Long getMaxMemory(Workload container) {
        return container.maxMemory ?: container.instance.plan.maxMemory
    }

    protected Long getMaxCpu(Workload container) {
        return container.maxCpu ?: container.instance.plan?.maxCpu ?: 1
    }

    protected Long getMaxCores(Workload container) {
        return container.maxCores ?: container.instance.plan.maxCores ?: 1
    }

    protected CloudPool getResourcePool(Workload container) {
        return container.server?.resourcePool ?: null
    }

    protected String getPlatform(Workload container) {
        return (container.server.serverOs?.platform == WINDOWS_PLATFORM ||
                container.server.osType == WINDOWS_PLATFORM) ? WINDOWS_PLATFORM : LINUX_PLATFORM
    }

    @SuppressWarnings('MethodReturnTypeRequired')
    protected getScvmmCapabilityProfile(Map containerConfig) {
        return containerConfig.scvmmCapabilityProfile?.toString() == DEFAULT_CAPABILITY_PROFILE
                ? null
                : containerConfig.scvmmCapabilityProfile
    }

    Map getAllScvmmOpts(Workload workload) {
        def controllerNode = pickScvmmController(workload.server.cloud)
        def rtn = apiService.getScvmmCloudOpts(context, workload.server.cloud, controllerNode)
        rtn += apiService.getScvmmControllerOpts(workload.server.cloud, controllerNode)
        rtn += getScvmmContainerOpts(workload)
        return rtn
    }

    /**
     * Issues the remote calls necessary to start a workload element for running.
     * @param workload the Workload we want to start up.
     * @return Response from API
     */
    @Override
    ServiceResponse startWorkload(Workload workload) {
        log.debug("startWorkload: ${workload?.id}")
        def rtn = ServiceResponse.prepare()
        try {
            if (workload.server?.externalId) {
                def scvmmOpts = getAllScvmmOpts(workload)
                def results = apiService.startServer(scvmmOpts, scvmmOpts.vmId)
                if (results.success == true) {
                    rtn.success = true
                }
            } else {
                rtn.success = false
                rtn.msg = VM_NOT_FOUND_MSG
            }
        } catch (e) {
            log.error("startWorkload error: ${e}", e)
            rtn.msg = e.message
        }
        return rtn
    }

    /**
     * Issues the remote calls to restart a workload element. In some cases this is just a simple alias
     * call to do a stop/start, however, in some cases cloud providers provide a direct restart call which
     * may be preferred for speed.
     * @param workload the Workload we want to restart.
     * @return Response from API
     */
    @Override
    ServiceResponse restartWorkload(Workload workload) {
        // Generally a call to stopWorkLoad() and then startWorkload()
        log.info("Executing restartWorkload, args: [server:${workload.name}]")
        log.debug("Dump of params server: ${workload.dump()}")
        log.info("Executing stopWorkload, args: [server:${workload.name}]")
        def res = stopWorkload(workload)
        if (res.success) {
            log.info("Executing startWorkload, args: [server:${workload.name}]")
            res = startWorkload(workload)
        }
        return res
    }

    /**
     * This is the key method called to destroy / remove a workload. This should make the remote calls necessary
     * to remove any assets associated with the workload.
     * @param workload to remove
     * @param opts map of options
     * @return Response from API
     */
    @Override
    ServiceResponse removeWorkload(Workload workload, Map opts) {
        log.debug("removeWorkload: opts: ${opts}")
        ServiceResponse response = ServiceResponse.prepare()
        try {
            log.debug("Removing container: ${workload?.dump()}")
            if (workload.server?.externalId) {
                def scvmmOpts = getAllScvmmOpts(workload)
                def deleteResults = apiService.deleteServer(scvmmOpts, scvmmOpts.externalId)
                log.debug "deleteResults: ${deleteResults?.dump()}"
                if (deleteResults.success == true) {
                    response.success = true
                } else {
                    response.msg = 'Failed to remove vm'
                }
            } else {
                response.msg = VM_NOT_FOUND_MSG
            }
        } catch (e) {
            log.error("removeWorkload error: ${e}", e)
            response.error = e.message
        }
        return response
    }

    /**
     * Method called after a successful call to runWorkload to obtain the details of the ComputeServer.
     * Implementations should not return until the server is successfully created in the underlying cloud
     * or the server fails to create.
     * @param server to check status
     * @return Response from API. The publicIp and privateIp set on the WorkloadResponse will be utilized to update the
     * ComputeServer
     */
    @Override
    ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
        def fetchedServer = context.async.computeServer.get(server.id).blockingGet()
        def opts = fetchScvmmConnectionDetails(fetchedServer)
        opts.server = fetchedServer
        opts.waitForIp = true
        def serverDetails = apiService.checkServerReady(opts, fetchedServer.externalId)
        if (serverDetails.success == true) {
            def agentWait = waitForAgentInstall(fetchedServer)
            if (agentWait.success) {
                fetchedServer = context.async.computeServer.get(server.id).blockingGet()
            }
            fetchedServer.externalIp = serverDetails.server?.ipAddress
            fetchedServer.powerState = ComputeServer.PowerState.on
            fetchedServer = MorpheusUtil.saveAndGetMorpheusServer(context, fetchedServer, true)
            def newIpAddress = serverDetails.server?.ipAddress
            def macAddress = serverDetails.server?.macAddress
            applyComputeServerNetworkIp(fetchedServer, newIpAddress, newIpAddress, 0, macAddress)
            return new ServiceResponse<ProvisionResponse>(true, null, null,
                    new ProvisionResponse(privateIp: fetchedServer.internalIp,
                            publicIp: fetchedServer.externalIp, success: true))
        }
        return new ServiceResponse(success: false, msg: serverDetails.message ?: 'Failed to get server details',
                error: serverDetails.message, data: serverDetails)
    }

    Map waitForAgentInstall(ComputeServer server, int maxAttempts = 1800) {
        Map rtn = [:]
        rtn.success = false
        try {
            int attempts = 0
            while (attempts < maxAttempts) {
                def fetchedServer = context.async.computeServer.get(server.id).blockingGet()
                if (fetchedServer?.agentInstalled) {
                    rtn.success = true
                    break
                } else {
                    attempts++
                    sleep(1000)
                }
            }
            if (!rtn.success) {
                rtn.msg = "Timed out waiting for agent connectivity from host. " +
                        "Verify the appliance url configuration is correct."
            }
        } catch (e) {
            log.error("waitForAgentInstall error: ${e}", e)
        }
        return rtn
    }

    /**
     * Method called before runWorkload to allow implementers to create resources required before runWorkload is called
     * @param workload that will be provisioned
     * @param opts additional options
     * @return Response from API
     */
    @Override
    ServiceResponse createWorkloadResources(Workload workload, Map opts) {
        return ServiceResponse.success()
    }

    /**
     * Stop the server
     * @param computeServer to stop
     * @return Response from API
     */
    @Override
    ServiceResponse stopServer(ComputeServer computeServer) {
        def rtn = [success: false, msg: null]
        try {
            if (computeServer?.externalId) {
                def scvmmOpts = getAllScvmmServerOpts(computeServer)
                def stopResults = apiService.stopServer(scvmmOpts, scvmmOpts.externalId)
                if (stopResults.success == true) {
                    rtn.success = true
                }
            } else {
                rtn.msg = VM_NOT_FOUND_MSG
            }
        } catch (e) {
            log.error("stopServer error: ${e}", e)
            rtn.msg = e.message
        }
        return ServiceResponse.create(rtn)
    }

    Long getServerRootSize(ComputeServer server) {
        def rtn
        def rootDisk = getServerRootDisk(server)
        if (rootDisk) {
            rtn = rootDisk.maxStorage
        } else {
            rtn = server.maxStorage ?: server.plan.maxStorage
        }
        return rtn
    }

    StorageVolume getServerRootDisk(ComputeServer server) {
        def rtn = server?.volumes?.find { vol -> vol.rootVolume == true }
        return rtn
    }

    Long getServerVolumeSize(ComputeServer server) {
        def rtn = server.maxStorage ?: server.plan.maxStorage
        if (server?.volumes?.size() > 0) {
            def newMaxStorage = server.volumes.sum { vol -> vol.maxStorage ?: 0 }
            if (newMaxStorage > rtn) {
                rtn = newMaxStorage
            }
        }
        return rtn
    }

    List<StorageVolume> getServerDataDiskList(ComputeServer server) {
        def rtn = server?.volumes?.findAll { vol -> vol.rootVolume == false }?.sort { a, b -> a.id <=> b.id }
        return rtn
    }

    Map getScvmmServerOpts(ComputeServer server) {
        def serverName = server.name // cleanName(server.name)
        def serverConfig = server.configMap
        def maxMemory = server.maxMemory ?: server.plan.maxMemory
        def maxCpu = server.maxCpu ?: server.plan?.maxCpu ?: 1
        def maxCores = server.maxCores ?: server.plan.maxCores ?: 1
        def maxStorage = getServerRootSize(server)
        def maxTotalStorage = getServerVolumeSize(server)
        def dataDisks = getServerDataDiskList(server)
        def network = context.services.cloud.network.get(serverConfig.networkId?.toLong())
        def serverFolder = "morpheus\\morpheus_server_${server.id}"
        return [name                  : serverName, vmId: server.externalId, config: serverConfig, server: server,
                serverId              : server.id, memory: maxMemory, osDiskSize: maxStorage,
                externalId            : server.externalId,
                maxCpu                : maxCpu, maxCores: maxCores, serverFolder: serverFolder,
                hostname              : server.externalHostname, network: network, networkId: network?.id,
                maxTotalStorage       : maxTotalStorage, dataDisks: dataDisks,
                scvmmCapabilityProfile: (serverConfig.scvmmCapabilityProfile?.toString() ==
                        DEFAULT_CAPABILITY_PROFILE) ?
                        null : serverConfig.scvmmCapabilityProfile,
                accountId             : server.account?.id]
    }

    Map getAllScvmmServerOpts(ComputeServer server) {
        def controllerNode = pickScvmmController(server.cloud)
        def rtn = apiService.getScvmmCloudOpts(context, server.cloud, controllerNode)
        rtn += apiService.getScvmmControllerOpts(server.cloud, controllerNode)
        rtn += getScvmmServerOpts(server)
        return rtn
    }

    /**
     * Start the server
     * @param computeServer to start
     * @return Response from API
     */
    @Override
    ServiceResponse startServer(ComputeServer computeServer) {
        log.debug("startServer: computeServer.id: ${computeServer?.id}")
        def rtn = ServiceResponse.prepare()
        try {
            if (computeServer?.externalId) {
                def scvmmOpts = getAllScvmmServerOpts(computeServer)
                def results = apiService.startServer(scvmmOpts, scvmmOpts.externalId)
                if (results.success == true) {
                    rtn.success = true
                }
            } else {
                rtn.msg = 'externalId not found'
            }
        } catch (e) {
            log.error("startServer error:${e}", e)
        }
        return rtn
    }

    /**
     * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
     *
     * @return an implementation of the MorpheusContext for running Future based rxJava queries
     */
    @Override
    MorpheusContext getMorpheus() {
        return this.@context
    }

    /**
     * Returns the instance of the Plugin class that this provider is loaded from
     * @return Plugin class contains references to other providers
     */
    @Override
    Plugin getPlugin() {
        return this.@plugin
    }

    /**
     * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
     * that is seeded or generated related to this provider will reference it by this code.
     * @return short code string that should be unique across all other plugin implementations.
     */
    @SuppressWarnings('GetterMethodCouldBeProperty')
    @Override
    String getCode() {
        return PROVIDER_CODE
    }

    /**
     * Provides the provider name for reference when adding to the Morpheus Orchestrator
     * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
     *
     * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
     */
    @SuppressWarnings('GetterMethodCouldBeProperty')
    @Override
    String getName() {
        return 'SCVMM'
    }

    @Override
    Boolean hasNetworks() {
        return true
    }

    @SuppressWarnings('GetterMethodCouldBeProperty')
    @Override
    Integer getMaxNetworks() {
        return 1
    }

    @Override
    Boolean canAddVolumes() {
        return true
    }

    @Override
    Boolean canCustomizeRootVolume() {
        return true
    }

    @Override
    Boolean canResizeRootVolume() {
        return true
    }

    @Override
    Boolean canCustomizeDataVolumes() {
        return true
    }

    @Override
    Boolean hasDatastores() {
        return true
    }

    @Override
    HostType getHostType() {
        return HostType.vm
    }

    @Override
    String serverType() {
        return VM_TYPE
    }

    @Override
    Boolean supportsCustomServicePlans() {
        return true
    }

    @Override
    Boolean multiTenant() {
        return false
    }

    @Override
    Boolean aclEnabled() {
        return false
    }

    @Override
    Boolean customSupported() {
        return true
    }

    @Override
    Boolean lvmSupported() {
        return true
    }

    @SuppressWarnings('GetterMethodCouldBeProperty')
    @Override
    String getDeployTargetService() {
        return "vmDeployTargetService"
    }

    @SuppressWarnings('GetterMethodCouldBeProperty')
    @Override
    String getNodeFormat() {
        return VM_TYPE
    }

    @Override
    Boolean hasSecurityGroups() {
        return false
    }

    @Override
    Boolean hasNodeTypes() {
        return true
    }

    @SuppressWarnings('GetterMethodCouldBeProperty')
    @Override
    String getHostDiskMode() {
        return 'lvm'
    }

    /**
     * For most provision types, a default instance type is created upon plugin registration.  Override this method if
     * you do NOT want to create a default instance type for your provision provider
     * @return defaults to true
     */
    @Override
    Boolean createDefaultInstanceType() {
        return false
    }

    /**
     * Determines if this provision type has ComputeZonePools that can be selected or not.
     * @return Boolean representation of whether or not this provision type has ComputeZonePools
     */
    @Override
    Boolean hasComputeZonePools() {
        return true
    }

    // Refactored validateHost
    @Override
    ServiceResponse validateHost(ComputeServer server, Map opts = [:]) {
        log.debug("validateHostConfiguration:$opts")
        try {
            if (server.computeServerType?.vmHypervisor == true) {
                return ServiceResponse.success()
            }
            return validateNonHypervisorHost(opts)
        } catch (e) {
            log.error("error in validateHost:${e.message}", e)
            return ServiceResponse.success()
        }
    }

// Groovy
    protected ServiceResponse validateNonHypervisorHost(Map opts) {
        def rtn = ServiceResponse.success()
        def validationOpts = [
                networkId             : extractNetworkId(opts),
                scvmmCapabilityProfile: extractCapabilityProfile(opts),
                nodeCount             : extractNodeCount(opts),
        ]
        def validationResults = apiService.validateServerConfig(validationOpts)
        if (!validationResults.success) {
            rtn.success = false
            rtn.errors += validationResults.errors
        }
        return rtn
    }

    // modified to reduce the cyclomatic complexity
    protected Long extractNetworkId(Map opts) {
        Long networkId = null
        if (opts?.networkInterface?.network?.id) {
            networkId = opts.networkInterface.network.id
        } else if (opts?.config?.networkInterface?.network?.id) {
            networkId = opts.config.networkInterface.network.id
        } else if (opts?.networkInterfaces && opts.networkInterfaces.size() > 0) {
            def firstInterface = opts.networkInterfaces[0]
            if (firstInterface?.network?.id) {
                networkId = firstInterface.network.id
            }
        }
        return networkId
    }

    @SuppressWarnings('MethodReturnTypeRequired')
    protected extractCapabilityProfile(Map opts) {
        return opts?.config?.scvmmCapabilityProfile ?: opts?.scvmmCapabilityProfile
    }

    protected Integer extractNodeCount(Map opts) {
        return opts?.config?.nodeCount
    }

    protected ComputeServer saveAndGet(ComputeServer server) {
        def saveResult = context.async.computeServer.bulkSave([server]).blockingGet()
        def updatedServer
        if (saveResult.success == true) {
            updatedServer = saveResult.persistedItems.find { item -> item.id == server.id }
        } else {
            updatedServer = saveResult.failedItems.find { item -> item.id == server.id }
            log.warn("Error saving server: ${server?.id}")
        }
        return updatedServer ?: server
    }

    @Override
    ServiceResponse<PrepareHostResponse> prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
        log.debug "prepareHost: ${server} ${hostRequest} ${opts}"

        def prepareResponse = new PrepareHostResponse(computeServer: server, disableCloudInit: false,
                options: [sendIp: true])
        ServiceResponse<PrepareHostResponse> rtn = ServiceResponse.prepare(prepareResponse)
        if (server.sourceImage) {
            rtn.success = true
            return rtn
        }

        try {
            VirtualImage virtualImage
            Long computeTypeSetId = server.typeSet?.id
            if (computeTypeSetId) {
                ComputeTypeSet computeTypeSet = morpheus.async.computeTypeSet.get(computeTypeSetId).blockingGet()
                if (computeTypeSet?.workloadType) {
                    WorkloadType workloadType =
                            morpheus.async.workloadType.get(computeTypeSet.workloadType.id).blockingGet()
                    virtualImage = workloadType.virtualImage
                }
            }
            if (virtualImage) {
                server.sourceImage = virtualImage
                saveAndGet(server)
                rtn.success = true
            } else {
                rtn.msg = "No virtual image selected"
            }
        } catch (e) {
            rtn.msg = "Error in prepareHost: ${e}"
            log.error("${rtn.msg}, ${e}", e)
        }
        return rtn
    }

    protected ComputeServer getMorpheusServer(Long id) {
        return context.services.computeServer.find(
                new DataQuery().withFilter(ID_FIELD, id).withJoin("interfaces.network")
        )
    }

    protected ComputeServer saveAndGetMorpheusServer(ComputeServer server, Boolean fullReload = false) {
        def saveResult = context.async.computeServer.bulkSave([server]).blockingGet()
        def updatedServer
        if (saveResult.success == true) {
            if (fullReload) {
                updatedServer = getMorpheusServer(server.id)
            } else {
                updatedServer = saveResult.persistedItems.find { cs -> cs.id == server.id }
            }
        } else {
            updatedServer = saveResult.failedItems.find { cs -> cs.id == server.id }
            log.warn("Error saving server: ${server?.id}")
        }
        return updatedServer ?: server
    }

    String getVolumePathForDatastore(Datastore datastore) {
        log.debug "getVolumePathForDatastore: ${datastore}"
        def volumePath
        if (datastore) {
            StorageVolume storageVolume = context.services.storageVolume.find(new DataQuery()
                    .withFilter('datastore.id', datastore.id)
                    .withFilter('volumePath', '!=', null))
            volumePath = storageVolume?.volumePath
        }
        log.debug "volumePath: ${volumePath}"
        return volumePath
    }

    // Helper to select datastore
    @SuppressWarnings(['UseCollectMany', 'ParameterCount'])
    protected Datastore selectDatastore(Cloud cloud, Account account, String clusterId, Integer hostId,
                                        Datastore datastore, Long size, Long siteId) {
        if (datastore) {
            return datastore
        }
        // If hostId specifed.. gather all the datastoreIds for the host via storage volumes
        def datastoreIds = context.services.resourcePermission.listAccessibleResources(account.id,
                ResourcePermission.ResourceType.Datastore, siteId, null)
        def hasFilteredDatastores = false
        if (hostId) {
            hasFilteredDatastores = true
            def scopedDatastoreIds = context.services.computeServer
                    .list(new DataQuery().withFilter(HOST_ID_FIELD, hostId.toLong()).withJoin('volumes.datastore'))
                    .collect { cs -> cs.volumes.collect { vol -> vol.datastore?.id } }
                    .flatten()
                    .unique()
            datastoreIds = scopedDatastoreIds
        }
        def query = new DataQuery()
                .withFilter(REF_TYPE_FIELD, COMPUTE_ZONE_REF_TYPE)
                .withFilter(REF_ID_FIELD, cloud.id)
                .withFilter('type', 'generic')
                .withFilter('online', true)
                .withFilter('active', true)
                .withFilter(FREE_SPACE_FIELD, '>', size)
        def dsQuery = hasFilteredDatastores ?
                query.withFilters(
                        new DataFilter(ID_FIELD, IN_OPERATOR, datastoreIds),
                        new DataOrFilter(
                                new DataFilter(VISIBILITY_FIELD, PUBLIC_VISIBILITY),
                                new DataFilter(OWNER_ID_FIELD, account.id)
                        )
                ) :
                query.withFilters(
                        new DataOrFilter(
                                new DataFilter(ID_FIELD, IN_OPERATOR, datastoreIds),
                                new DataOrFilter(
                                        new DataFilter(VISIBILITY_FIELD, PUBLIC_VISIBILITY),
                                        new DataFilter(OWNER_ID_FIELD, account.id)
                                )
                        )
                )
        if (clusterId) {
            if (clusterId.toString().number) {
                dsQuery = dsQuery.withFilter('zonePool.id', clusterId.toLong())
            } else {
                dsQuery = dsQuery.withFilter('zonePool.externalId', clusterId)
            }
        }
        def dsList = context.services.cloud.datastore.list(dsQuery.withSort(FREE_SPACE_FIELD,
                DataQuery.SortOrder.desc))
        // Return the first one
        return dsList?.size() > 0 ? dsList[0] : null
    }

    // Helper to select host
    protected ComputeServer selectHost(Cloud cloud, Integer hostId, Datastore datastore, Long maxMemory) {
        // If host specified by the user, then use it
        if (hostId) {
            return context.services.computeServer.get(hostId.toLong())
        }
        if (datastore) {
            // We've grabbed a datastore.. now pick a host that has this datastore
            def nodes = context.services.computeServer.list(new DataQuery()
                    .withFilter(CLOUD_ID_FIELD, cloud.id)
                    .withFilter('enabled', true)
                    .withFilter(COMPUTE_SERVER_TYPE_CODE_FIELD, SCVMM_HYPERVISOR_TYPE)
                    .withFilter('volumes.datastore.id', datastore.id)
                    .withFilter('powerState', ComputeServer.PowerState.on))
            nodes = nodes.findAll { cs ->
                cs.capacityInfo?.maxMemory - cs.capacityInfo?.usedMemory > maxMemory
            }?.sort { compServ ->
                -(compServ.capacityInfo?.maxMemory - compServ.capacityInfo?.usedMemory)
            }
            return nodes?.size() > 0 ? nodes.first() : null
        }
        return null
    }

    // Helper to get volume path
    protected String getVolumePath(Datastore datastore) {
        return getVolumePathForDatastore(datastore)
    }

    // Helper to check highly available
    protected boolean isHighlyAvailable(String clusterId, Datastore datastore, boolean isClone) {
        return clusterId && (datastore?.zonePool || isClone)
    }

    // Refactored getHostAndDatastore
    @SuppressWarnings('ParameterCount')
    List getHostAndDatastore(Cloud cloud, Account account, String clusterId, Integer hostId, Datastore datastore,
                             String datastoreOption, Long size, Long siteId = null, Long maxMemory,
                             boolean isClone = false) {
        log.info "getHostAndDatastore : clusterId: ${clusterId}, hostId: ${hostId}, " +
                "datastore: ${datastore}, datastoreOption: ${datastoreOption}, size: ${size}, " +
                "siteId: ${siteId}, maxMemory ${maxMemory}"
        // If clusterId (resourcePool) is not specified AND host not specified AND datastore is 'auto',
        // then we are just deploying to the cloud (so... can not select the datastore, nor host)
        def tempClusterId = clusterId && clusterId != 'null' ? clusterId : null
        def tempHostId = hostId && hostId.toString().trim() != EMPTY_STRING ? hostId : null
        def zoneHasCloud = cloud.regionCode != null && cloud.regionCode != EMPTY_STRING
        if (zoneHasCloud && !tempClusterId && !tempHostId && !datastore &&
                (datastoreOption == 'auto' || !datastoreOption)) {
            return [null, null, null, false]
        }
        def selectedDatastore = selectDatastore(cloud, account, tempClusterId, tempHostId,
                datastore, size, siteId)
        def selectedHost = selectHost(cloud, tempHostId, selectedDatastore, maxMemory)
        if (!zoneHasCloud && (!selectedHost || !selectedDatastore)) {
            // Need a node and a datastore for non-cloud scoped zones
            throw new IllegalStateException('Unable to obtain datastore and host for options selected')
        }
        // Get the volumePath (used during provisioning to tell SCVMM where to place the disks)
        def volumePath = getVolumePath(selectedDatastore)
        // Highly Available (in the Failover Cluster Manager) if we are in a cluster and
        // the datastore is a shared volume
        def highlyAvailable = isHighlyAvailable(tempClusterId, selectedDatastore, isClone)
        return [selectedHost, selectedDatastore, volumePath, highlyAvailable]
    }

    @SuppressWarnings('MethodReturnTypeRequired')
    def loadDatastoreForVolume(Cloud cloud, String hostVolumeId = null, String fileShareId = null,
                               String partitionUniqueId = null) {
        log.debug "loadDatastoreForVolume: ${hostVolumeId}, ${fileShareId}"
        if (hostVolumeId) {
            StorageVolume storageVolume = context.services.storageVolume.find(
                    new DataQuery()
                            .withFilter('internalId', hostVolumeId)
                            .withFilter(DATASTORE_REF_TYPE_FIELD, COMPUTE_ZONE_REF_TYPE)
                            .withFilter(DATASTORE_REF_ID_FIELD, cloud.id)
            )
            def ds = storageVolume?.datastore
            if (!ds && partitionUniqueId) {
                storageVolume = context.services.storageVolume.find(
                        new DataQuery()
                                .withFilter(EXTERNAL_ID_FIELD, partitionUniqueId)
                                .withFilter(DATASTORE_REF_TYPE_FIELD, COMPUTE_ZONE_REF_TYPE)
                                .withFilter(DATASTORE_REF_ID_FIELD, cloud.id)
                )
                ds = storageVolume?.datastore
            }
            return ds
        } else if (fileShareId) {
            Datastore datastore = context.services.cloud.datastore.find(new DataQuery()
                    .withFilter(EXTERNAL_ID_FIELD, fileShareId)
                    .withFilter(REF_TYPE_FIELD, COMPUTE_ZONE_REF_TYPE)
                    .withFilter(REF_ID_FIELD, cloud.id))
            return datastore
        }
        null
    }

    @Override
    @SuppressWarnings(['CatchRuntimeException', 'CouldBeElvis', 'ParameterReassignment', 'UnnecessarySetter'])
    ServiceResponse<ProvisionResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
        log.debug("runHost: ${server} ${hostRequest} ${opts}")
        ProvisionResponse provisionResponse = new ProvisionResponse()
        try {
            def config = server.configMap
            config.resourcePool = ""
            Cloud cloud = server.cloud
            def account = server.account
            def controllerNode = pickScvmmController(cloud)
            def scvmmOpts = prepareScvmmOpts(context, cloud, controllerNode, server)
            def clusterId = resolveClusterId(config, server)
            def rootVolume = getServerRootDisk(server)
            def maxStorage = getServerRootSize(server)
            def maxMemory = server.maxMemory ?: server.plan.maxMemory

            def hostDatastoreInfo = getHostDatastoreInfoForRoot(cloud, account, clusterId, config, rootVolume,
                    maxStorage, server.provisionSiteId, maxMemory)
            updateScvmmOptsWithHostDatastore(scvmmOpts, hostDatastoreInfo)

            updateRootVol(rootVolume, hostDatastoreInfo.datastore)
            updateServerVolumes(server.volumes, cloud, account, clusterId, config.hostId,
                    maxStorage, server.provisionSiteId, maxMemory)

            scvmmOpts += apiService.getScvmmControllerOpts(cloud, controllerNode)
            def imageInfo = resolveImageInfo(config, server)
            def imageId = imageInfo.imageId
            def virtualImage = imageInfo.virtualImage

            if (!imageId) {
                imageId = handleImageUpload(scvmmOpts, server, virtualImage)
            }

            if (imageId) {
                prepareServerForProvision(server, virtualImage, scvmmOpts, hostDatastoreInfo.node, imageId)
                scvmmOpts += getScvmmServerOpts(server)
                setCloudConfig(scvmmOpts, hostRequest, server)
                server = saveAndGetMorpheusServer(server, true)
                def createResults = apiService.createServer(scvmmOpts)
                log.debug "create server results:${createResults}"
                provisionResponse = handleServerCreation(createResults, server,
                        hostDatastoreInfo.node?.id, cloud, scvmmOpts)
            } else {
                server.statusMessage = ERROR_CREATING_SERVER_MSG
            }

            if (provisionResponse.success != true) {
                return new ServiceResponse(success: false,
                        msg: provisionResponse.message ?: VM_CONFIG_ERROR_MSG,
                        error: provisionResponse.message,
                        data: provisionResponse)
            }
            return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
        } catch (RuntimeException e) {
            log.error("Error in runHost method: ${e.message}", e)
            provisionResponse.setError(e.message)
            return new ServiceResponse(success: false, msg: e.message, error: e.message, data: provisionResponse)
        }
    }

// --- Helper Methods ---

    protected void updateScvmmOptsWithHostDatastore(Map scvmmOpts, Map hostDatastoreInfo) {
        scvmmOpts.datastoreId = hostDatastoreInfo.datastore?.externalId
        scvmmOpts.hostExternalId = hostDatastoreInfo.node?.externalId
        scvmmOpts.volumePath = hostDatastoreInfo.volumePath
        scvmmOpts.highlyAvailable = hostDatastoreInfo.highlyAvailable
    }

    protected Map prepareScvmmOpts(MorpheusContext context, Cloud cloud, ComputeServer controllerNode,
                                   ComputeServer server) {
        def scvmmOpts = apiService.getScvmmCloudOpts(context, cloud, controllerNode)
        scvmmOpts.controllerServerId = controllerNode.id
        scvmmOpts.creatingDockerHost = true
        scvmmOpts.name = server.name
        return scvmmOpts
    }

    protected String resolveClusterId(Map config, ComputeServer server) {
        if (config.resourcePool) {
            def pool = server.resourcePool
            if (pool) {
                return pool.externalId
            }
        }
        return null
    }

    @SuppressWarnings('ParameterCount')
    protected Map getHostDatastoreInfoForRoot(Cloud cloud, Account account, String clusterId, Map config,
                                              StorageVolume rootVolume, Long maxStorage,
                                              Long provisionSiteId, Long maxMemory) {
        def result = [:]
        def hostAndDatastore = getHostAndDatastore(cloud, account, clusterId, config.hostId, rootVolume?.datastore,
                rootVolume?.datastoreOption, maxStorage, provisionSiteId, maxMemory)
        result.node = hostAndDatastore[0]
        result.datastore = hostAndDatastore[1]
        result.volumePath = hostAndDatastore[SORT_ORDER_2]
        result.highlyAvailable = hostAndDatastore[SORT_ORDER_3]
        return result
    }

    protected void updateRootVol(StorageVolume rootVolume, Datastore datastore) {
        if (rootVolume) {
            rootVolume.datastore = datastore
            context.services.storageVolume.save(rootVolume)
        }
    }

    @SuppressWarnings('ParameterCount')
    protected void updateServerVolumes(List<StorageVolume> storageVolumes, Cloud cloud, Account account,
                                       String clusterId, Integer hostId, Long maxStorage,
                                       Long provisionSiteId, Long maxMemory) {
        storageVolumes?.each { vol ->
            if (!vol.rootVolume) {
                def tmpNode, tmpDatastore, tmpVolumePath, tmpHighlyAvailable
                (tmpNode, tmpDatastore, tmpVolumePath, tmpHighlyAvailable) = getHostAndDatastore(cloud, account,
                        clusterId, hostId, vol?.datastore, vol?.datastoreOption, maxStorage,
                        provisionSiteId, maxMemory, true)
                vol.datastore = tmpDatastore
                context.services.storageVolume.save(vol)
            }
        }
    }

    protected Map resolveImageInfo(Map config, ComputeServer server) {
        def imageType = config.templateTypeSelect ?: 'default'
        def virtualImage
        def imageId
        def layout = server.layout
        def typeSet = server.typeSet
        if (layout && typeSet) {
            virtualImage = typeSet.workloadType.virtualImage
            imageId = virtualImage.externalId
        } else if (imageType == 'custom' && config.template) {
            def virtualImageId = config.template?.toLong()
            virtualImage = context.services.virtualImage.get(virtualImageId)
            imageId = virtualImage.externalId
        } else {
            virtualImage = new VirtualImage(code: 'scvmm.image.morpheus.ubuntu.22.04.20250218.amd64')
        }
        return [imageId: imageId, virtualImage: virtualImage]
    }

    protected String handleImageUpload(Map scvmmOpts, ComputeServer server, VirtualImage virtualImage) {
        def cloudFiles = context.async.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()
        def imageFile = cloudFiles?.find { cloudFile ->
            def name = cloudFile.name.toLowerCase()
            name.endsWith(".vhd") || name.endsWith(".vhdx") || name.endsWith(".vmdk") || name.endsWith(".vhd.tar.gz")
        }
        def containerImage = [
                name          : virtualImage.name,
                minDisk       : INTEGER_FIVE,
                minRam        : FIVE_TWELVE_LONG * ComputeUtility.ONE_MEGABYTE,
                virtualImageId: virtualImage.id,
                tags          : MORPHEUS_UBUNTU_TAGS,
                imageType     : virtualImage.imageType,
                containerType : VHD_CONTAINER_TYPE,
                imageFile     : imageFile,
                cloudFiles    : cloudFiles,
        ]
        scvmmOpts.image = containerImage
        scvmmOpts.userId = server.createdBy?.id
        log.debug("scvmmOpts: {}", scvmmOpts)
        def imageResults = apiService.insertContainerImage(scvmmOpts)
        if (imageResults.success == true) {
            return imageResults.imageId
        }
        return null
    }

    @SuppressWarnings('ParameterReassignment')
    protected void prepareServerForProvision(ComputeServer server, VirtualImage virtualImage, Map scvmmOpts,
                                             ComputeServer node, String imageId) {
        virtualImage = context.async.virtualImage.get(virtualImage.id).blockingGet()
        assignSourceImageAndExternalId(server, virtualImage, scvmmOpts)
        assignServerOsType(server, virtualImage)
        assignParentServer(server, node)
        setupScvmmOpts(scvmmOpts, virtualImage, imageId)
        saveAndGetMorpheusServer(server, true)
    }

    protected void assignSourceImageAndExternalId(ComputeServer server, VirtualImage virtualImage, Map scvmmOpts) {
        server.sourceImage = virtualImage
        server.externalId = scvmmOpts.name
    }

    protected void assignServerOsType(ComputeServer server, VirtualImage virtualImage) {
        server.serverOs = server.serverOs ?: virtualImage.osType
        def osplatform = virtualImage?.osType?.platform?.toString()?.toLowerCase()
                ?: virtualImage?.platform?.toString()?.toLowerCase()
        server.osType = [WINDOWS_PLATFORM, OSX_PLATFORM].contains(osplatform) ? osplatform : LINUX_PLATFORM
    }

    protected void assignParentServer(ComputeServer server, ComputeServer node) {
        server.parentServer = node
    }

    protected void setupScvmmOpts(Map scvmmOpts, VirtualImage virtualImage, String imageId) {
        scvmmOpts.secureBoot = virtualImage?.uefi ?: false
        scvmmOpts.imageId = imageId
        scvmmOpts.scvmmGeneration = virtualImage?.getConfigProperty(GENERATION_FIELD) ?: SCVMM_GENERATION1_VALUE
        scvmmOpts.diskMap = context.services.virtualImage.getImageDiskMap(virtualImage)
    }

    protected void setCloudConfig(Map scvmmOpts, HostRequest hostRequest, ComputeServer server) {
        scvmmOpts.networkConfig = hostRequest.networkConfiguration
        scvmmOpts.cloudConfigUser = hostRequest.cloudConfigUser
        scvmmOpts.cloudConfigMeta = hostRequest.cloudConfigMeta
        scvmmOpts.cloudConfigNetwork = hostRequest.cloudConfigNetwork
        scvmmOpts.isSysprep = server.sourceImage?.isSysprep
        def isoBuffer = context.services.provision.buildIsoOutputStream(
                scvmmOpts.isSysprep, PlatformType.valueOf(server.osType),
                scvmmOpts.cloudConfigMeta, scvmmOpts.cloudConfigUser, scvmmOpts.cloudConfigNetwork)
        scvmmOpts.cloudConfigBytes = isoBuffer
        server.cloudConfigUser = scvmmOpts.cloudConfigUser
        server.cloudConfigMeta = scvmmOpts.cloudConfigMeta
        server.cloudConfigNetwork = scvmmOpts.cloudConfigNetwork
    }

    protected ProvisionResponse handleServerCreation(Map createResults, ComputeServer server, Long nodeId,
                                                     Cloud cloud, Map scvmmOpts) {
        ProvisionResponse provisionResponse = new ProvisionResponse()
        if (createResults.success == true) {
            def instance = createResults.server
            if (instance) {
                def node = context.services.computeServer.get(nodeId)
                def serverDisks = createResults.server.disks
                if (serverDisks) {
                    updateServerVolumesAfterCreation(server, serverDisks, cloud)
                }
                server.with {
                    externalId = instance.id
                    parentServer = node
                    osDevice = DEV_SDA_PATH
                    dataDevice = DEV_SDA_PATH
                    managed = true
                    capacityInfo = new ComputeCapacityInfo(maxCores: scvmmOpts.maxCores,
                            maxMemory: scvmmOpts.memory, maxStorage: scvmmOpts.maxTotalStorage)
                    status = PROVISIONED_STATUS
                }
                context.async.computeServer.save(server).blockingGet()

                // start it
                log.info("Starting Server  ${scvmmOpts.name}")
                apiService.startServer(scvmmOpts, scvmmOpts.externalId)
                provisionResponse.success = true
                log.debug("provisionResponse.success: ${provisionResponse.success}")
            } else {
                server.statusMessage = 'Error loading created server'
            }
        } else {
            handleServerCreationFailure(createResults, server)
        }
        return provisionResponse
    }

    @SuppressWarnings('MethodParameterTypeRequired')
    protected void updateServerVolumesAfterCreation(ComputeServer server, def serverDisks, Cloud cloud) {
        def storageVolumes = server.volumes
        def rootVolume = storageVolumes.find { sv -> sv.rootVolume == true }
        updateRootVolumeAfterCreation(rootVolume, serverDisks, cloud)
        updateDataVolumesAfterCreation(storageVolumes, serverDisks, cloud)
    }

    @SuppressWarnings('MethodParameterTypeRequired')
    protected void updateRootVolumeAfterCreation(StorageVolume rootVolume, def serverDisks, Cloud cloud) {
        rootVolume.externalId = serverDisks.diskMetaData[serverDisks.osDisk?.externalId]?.VhdID
        rootVolume.datastore = loadDatastoreForVolume(
                cloud,
                serverDisks.diskMetaData[rootVolume.externalId]?.HostVolumeId,
                serverDisks.diskMetaData[rootVolume.externalId]?.FileShareId,
                serverDisks.diskMetaData[rootVolume.externalId]?.PartitionUniqueId
        ) ?: rootVolume.datastore
    }

    @SuppressWarnings('MethodParameterTypeRequired')
    protected void updateDataVolumesAfterCreation(List<StorageVolume> storageVolumes, def serverDisks, Cloud cloud) {
        storageVolumes.each { storageVolume ->
            def dataDisk = serverDisks.dataDisks.find { sd -> sd.id == storageVolume.id }
            if (dataDisk) {
                def newExternalId = serverDisks.diskMetaData[dataDisk.externalId]?.VhdID
                if (newExternalId) {
                    storageVolume.externalId = newExternalId
                }
                storageVolume.datastore = loadDatastoreForVolume(
                        cloud,
                        serverDisks.diskMetaData[storageVolume.externalId]?.HostVolumeId,
                        serverDisks.diskMetaData[storageVolume.externalId]?.FileShareId,
                        serverDisks.diskMetaData[storageVolume.externalId]?.PartitionUniqueId
                ) ?: storageVolume.datastore
            }
        }
    }

    protected void handleServerCreationFailure(Map createResults, ComputeServer server) {
        if (createResults.server?.id) {
            server.externalId = createResults.server.id
            context.async.computeServer.save(server).blockingGet()
        }
        server.statusMessage = ERROR_CREATING_SERVER_MSG
    }

    // Helper to find existing interface
    protected ComputeServerInterface findNetworkInterface(ComputeServer server, String privateIp, Integer index) {
        def iface = server.interfaces?.find { csi -> csi.ipAddress == privateIp }
        if (iface) {
            return iface
        }
        if (index == 0) {
            return server.interfaces?.find { csi -> csi.primaryInterface == true }
        }
        iface = server.interfaces?.find { dis -> dis.displayOrder == index }
        if (!iface && server.interfaces?.size() > index) {
            iface = server.interfaces[index]
        }
        return iface
    }

    // Helper to create new interface
    protected ComputeServerInterface createNetworkInterface(ComputeServer server, String privateIp) {
        def interfaceName = server.sourceImage?.interfaceName ?: 'eth0'
        def netInterface = new ComputeServerInterface(
                name: interfaceName,
                ipAddress: privateIp,
                primaryInterface: true,
                displayOrder: (server.interfaces?.size() ?: 0) + 1
        )
        netInterface.addresses += new NetAddress(type: NetAddress.AddressType.IPV4, address: privateIp)
        return netInterface
    }

    // Refactored saveAndGetNetworkInterface
    @SuppressWarnings('ParameterReassignment')
    protected Map saveAndGetNetworkInterface(ComputeServer server, String privateIp, String publicIp,
                                             Integer index, String macAddress) {
        log.debug("applyComputeServerNetworkIp: ${privateIp}")
        def rtn = [:]
        ComputeServerInterface netInterface
        if (privateIp) {
            privateIp = privateIp?.toString()?.replace(NEWLINE, "")
            server.internalIp = privateIp
            server.sshHost = privateIp
            server.macAddress = macAddress
            log.debug("Setting private ip on server:${server.sshHost}")

            netInterface = findNetworkInterface(server, privateIp, index)
            def newInterface = false
            if (netInterface) {
                // Update existing interface
                netInterface.ipAddress = privateIp
            } else {
                // Create new interface
                netInterface = createNetworkInterface(server, privateIp)
                newInterface = true
            }
            if (publicIp) {
                publicIp = publicIp?.toString()?.replace(NEWLINE, "")
                netInterface.publicIpAddress = publicIp
                server.externalIp = publicIp
            }
            netInterface.macAddress = macAddress
            if (newInterface) {
                context.async.computeServer.computeServerInterface.create([netInterface], server).blockingGet()
            } else {
                context.async.computeServer.computeServerInterface.save([netInterface]).blockingGet()
            }
        }
        def savedServer = MorpheusUtil.saveAndGetMorpheusServer(context, server, true)
        rtn.netInterface = netInterface
        rtn.server = savedServer
        return rtn
    }

    protected ComputeServerInterface applyComputeServerNetworkIp(ComputeServer server, String privateIp,
                                                                 String publicIp, Integer index, String macAddress) {
        return saveAndGetNetworkInterface(server, privateIp, publicIp, index, macAddress).netInterface
    }

    protected ComputeServer applyNetworkIpAndGetServer(ComputeServer server, String privateIp,
                                                       String publicIp, Integer index, String macAddress) {
        return saveAndGetNetworkInterface(server, privateIp, publicIp, index, macAddress).server
    }

    @Override
    ServiceResponse<ProvisionResponse> waitForHost(ComputeServer server) {
        log.debug("waitForHost: ${server}")
        def provisionResponse = new ProvisionResponse()
        ServiceResponse<ProvisionResponse> rtn = ServiceResponse.prepare(provisionResponse)
        try {
            def fetchedServer = context.async.computeServer.get(server.id).blockingGet()
            Map<String, Object> scvmmOpts = fetchScvmmConnectionDetails(fetchedServer)
            def serverDetail = apiService.checkServerReady(scvmmOpts, fetchedServer.externalId)
            if (serverDetail.success == true) {
                def agentWait = waitForAgentInstall(fetchedServer)
                if (agentWait.success) {
                    fetchedServer = context.async.computeServer.get(server.id).blockingGet()
                }
                provisionResponse.privateIp = serverDetail.server.ipAddress
                provisionResponse.publicIp = serverDetail.server.ipAddress
                provisionResponse.externalId = fetchedServer.externalId
                def finalizeResults = finalizeHost(fetchedServer)
                if (finalizeResults.success == true) {
                    provisionResponse.success = true
                    rtn.success = true
                }
            }
        } catch (e) {
            log.error("Error waitForHost: ${e.message}", e)
            rtn.success = false
            rtn.msg = "Error in waiting for Host: ${e}"
        }
        return rtn
    }

    protected Map<String, Object> fetchScvmmConnectionDetails(ComputeServer server) {
        def node = pickScvmmController(server.cloud)
        def scvmmOpts = apiService.getScvmmCloudOpts(context, server.cloud, node)
        scvmmOpts += apiService.getScvmmControllerOpts(server.cloud, node)
        scvmmOpts += getScvmmServerOpts(server)
        return scvmmOpts
    }

    @Override
    ServiceResponse finalizeHost(ComputeServer server) {
        ServiceResponse rtn = ServiceResponse.prepare()
        log.debug("finalizeHost: ${server?.id}")
        try {
            def fetchedServer = context.async.computeServer.get(server.id).blockingGet()
            Map<String, Object> scvmmOpts = fetchScvmmConnectionDetails(fetchedServer)
            def serverDetail = apiService.checkServerReady(scvmmOpts, fetchedServer.externalId)
            if (serverDetail.success == true) {
                def agentWait = waitForAgentInstall(fetchedServer)
                if (agentWait.success) {
                    fetchedServer = context.async.computeServer.get(server.id).blockingGet()
                }
                def newIpAddress = serverDetail.server?.ipAddress
                def macAddress = serverDetail.server?.macAddress
                def savedServer = applyNetworkIpAndGetServer(fetchedServer, newIpAddress, newIpAddress, 0, macAddress)
                context.async.computeServer.save(savedServer).blockingGet()
                rtn.success = true
            }
        } catch (e) {
            rtn.success = false
            rtn.msg = "Error in finalizing server: ${e.message}"
            log.error("Error in finalizeHost: ${e.message}", e)
        }
        return rtn
    }

    /**
     * Request to scale the size of the Workload. Most likely, the implementation will follow that of resizeServer
     * as the Workload usually references a ComputeServer. It is up to implementations to create the volumes,
     * set the memory, etc
     * on the underlying ComputeServer in the cloud environment. In addition, implementations of this method should
     * add, remove, and update the StorageVolumes, StorageControllers, ComputeServerInterface in the cloud environment
     * with the requested attributes
     * and then save these attributes on the models in Morpheus. This requires adding, removing, and saving the various
     * models to the ComputeServer using the appropriate contexts. The ServicePlan, memory, cores, coresPerSocket,
     * maxStorage values
     * defined on ResizeRequest will be set on the Workload and ComputeServer upon return of a
     * successful ServiceResponse
     * @param instance to resize
     * @param workload to resize
     * @param resizeRequest the resize requested parameters
     * @param opts additional options
     * @return Response from API
     */
    @Override
    ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
        log.info("resizeWorkload calling resizeWorkloadAndServer")
        return resizeWorkloadAndServer(workload, null, resizeRequest, opts, true)
    }

    @Override
    ServiceResponse resizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts) {
        log.info("resizeServer calling resizeWorkloadAndServer")
        return resizeWorkloadAndServer(null, server, resizeRequest, opts, false)
    }

    // Helper for memory and core resize
    @SuppressWarnings(['ParameterCount', 'ParameterReassignment'])
    protected boolean handleMemoryAndCoreResize(ComputeServer computeServer, Map resizeConfig,
                                                Map scvmmOpts, String vmId, Boolean isWorkload, Workload workload) {
        if (resizeConfig.neededMemory != 0 || resizeConfig.neededCores != 0 ||
                resizeConfig.minDynamicMemory || resizeConfig.maxDynamicMemory) {
            def resizeResults = apiService.updateServer(scvmmOpts, vmId, [
                    maxMemory       : resizeConfig.requestedMemory,
                    maxCores        : resizeConfig.requestedCores,
                    minDynamicMemory: resizeConfig.minDynamicMemory,
                    maxDynamicMemory: resizeConfig.maxDynamicMemory,
            ])
            log.debug("resize results: ${resizeResults}")
            if (resizeResults.success == true) {
                computeServer.setConfigProperty(MAX_MEMORY_FIELD, resizeConfig.requestedMemory)
                computeServer.setConfigProperty(MAX_CORES_FIELD, (resizeConfig.requestedCores ?: 1))
                computeServer.maxCores = (resizeConfig.requestedCores ?: 1).toLong()
                computeServer.maxMemory = resizeConfig.requestedMemory.toLong()
                computeServer = saveAndGet(computeServer)
                if (isWorkload) {
                    workload.setConfigProperty(MAX_MEMORY_FIELD, resizeConfig.requestedMemory)
                    workload.maxMemory = resizeConfig.requestedMemory.toLong()
                    workload.setConfigProperty(MAX_CORES_FIELD, (resizeConfig.requestedCores ?: 1))
                    workload.maxCores = (resizeConfig.requestedCores ?: 1).toLong()
                    workload = context.services.workload.save(workload)
                    workload.server = computeServer
                }
                return true
            }
            log.error(resizeResults.error ?: 'Failed to resize container')
            return false
        }
        return true
    }

    // Update existing volumes
    protected void updateVolumes(List volumesUpdate, Map scvmmOpts, ServiceResponse rtn) {
        log.debug("resizing vm storage: ${volumesUpdate}")
        volumesUpdate?.each { volumeUpdate ->
            StorageVolume existing = volumeUpdate.existingModel
            Map updateProps = volumeUpdate.updateProps
            if (updateProps.maxStorage > existing.maxStorage) {
                def volumeId = existing.externalId
                def diskSize = ComputeUtility.parseGigabytesToBytes(updateProps.size?.toLong())
                def resizeResults = apiService.resizeDisk(scvmmOpts, volumeId, diskSize)
                if (resizeResults.success == true) {
                    def existingVolume = context.services.storageVolume.get(existing.id)
                    existingVolume.maxStorage = diskSize
                    context.services.storageVolume.save(existingVolume)
                } else {
                    log.error "Error in resizing volume: ${resizeResults}"
                    rtn.error = resizeResults.error ?: "Error in resizing volume"
                }
            }
        }
    }

    // Add new volumes
    @SuppressWarnings(['UnnecessaryToString', 'ParameterReassignment'])
    protected void addVolumes(List volumesAdd, ComputeServer computeServer, Map scvmmOpts, ServiceResponse rtn) {
        def diskCounter = computeServer.volumes?.size()
        volumesAdd?.each { volumeAdd ->
            def diskSize = ComputeUtility.parseGigabytesToBytes(volumeAdd.size?.toLong()) / ComputeUtility.ONE_MEGABYTE
            def volumePath = getVolumePathForDatastore(volumeAdd.datastore)
            def diskSpec = [
                    vhdName  : "data-${UUID.randomUUID().toString()}",
                    vhdType  : null,
                    vhdFormat: null,
                    vhdPath  : null,
                    sizeMb   : diskSize,
            ]
            log.info("resizeContainer - volumePath: ${volumePath} - diskSpec: ${diskSpec}")
            def diskResults = apiService.createAndAttachDisk(scvmmOpts, diskSpec, true)
            log.info("create disk: ${diskResults.success}")
            if (diskResults.success == true) {
                def newVolume = buildStorageVolume(computeServer, volumeAdd, diskCounter)
                if (volumePath) {
                    newVolume.volumePath = volumePath
                }
                newVolume.internalId = diskResults.disk.VhdLocation
                newVolume.maxStorage = volumeAdd.size.toInteger() * ComputeUtility.ONE_GIGABYTE
                newVolume.externalId = diskResults.disk.VhdID
                def updatedDatastore = loadDatastoreForVolume(computeServer.cloud,
                        diskResults.disk.HostVolumeId, diskResults.disk.FileShareId,
                        diskResults.disk.PartitionUniqueId) ?: null
                if (updatedDatastore && newVolume.datastore != updatedDatastore) {
                    newVolume.datastore = updatedDatastore
                }
                context.async.storageVolume.create([newVolume], computeServer).blockingGet()
                computeServer = getMorpheusServer(computeServer.id)
                diskCounter++
            } else {
                log.error "Error in creating the volume: ${diskResults}"
                rtn.error = "Error in creating the volume"
            }
        }
    }

    // Delete removed volumes
    @SuppressWarnings('ParameterReassignment')
    protected void deleteVolumes(List volumesDelete, ComputeServer computeServer, Map scvmmOpts) {
        volumesDelete?.each { volume ->
            log.debug "Deleting volume : ${volume.externalId}"
            def detachResults = apiService.removeDisk(scvmmOpts, volume.externalId)
            log.debug("detachResults.success: ${detachResults.data}")
            if (detachResults.success == true) {
                context.async.storageVolume.remove([volume], computeServer, true).blockingGet()
                computeServer = getMorpheusServer(computeServer.id)
            }
        }
    }

    // helper for volume operations
    protected boolean handleVolumeOperations(Map opts, ResizeRequest resizeRequest, ComputeServer computeServer,
                                             Map scvmmOpts, ServiceResponse rtn) {
        if (opts.volumes && !rtn.error) {
            updateVolumes(resizeRequest.volumesUpdate, scvmmOpts, rtn)
            addVolumes(resizeRequest.volumesAdd, computeServer, scvmmOpts, rtn)
            deleteVolumes(resizeRequest.volumesDelete, computeServer, scvmmOpts)
        }
        return rtn.error ? false : true
    }

    // Refactored resizeWorkloadAndServer
    @SuppressWarnings('UnnecessarySetter')
    protected ServiceResponse resizeWorkloadAndServer(Workload workload, ComputeServer server,
                                                      ResizeRequest resizeRequest, Map opts, Boolean isWorkload) {
        log.debug("resizeWorkloadAndServer workload.id: ${workload?.id} - opts: ${opts}")
        ServiceResponse rtn = ServiceResponse.success()
        ComputeServer computeServer = isWorkload ? getMorpheusServer(workload.server?.id) : getMorpheusServer(server.id)
        try {
            computeServer.status = 'resizing'
            computeServer = saveAndGet(computeServer)
            def vmId = computeServer.externalId
            def scvmmOpts = isWorkload
                    ? getAllScvmmOpts(workload)
                    : getAllScvmmServerOpts(computeServer)
            def resizeConfig = isWorkload ?
                    getResizeConfig(workload, null, workload.instance.plan, opts, resizeRequest) :
                    getResizeConfig(null, computeServer, computeServer.plan, opts, resizeRequest)
            log.debug("resizeConfig: ${resizeConfig}")
            def stopRequired = !resizeConfig.hotResize
            def stopResults
            if (stopRequired) {
                stopResults = isWorkload ? stopWorkload(workload) : stopServer(computeServer)
            }
            log.debug("stopResults?.success: ${stopResults?.success}")
            if (!stopRequired || stopResults?.success == true) {
                boolean resizeSuccess = handleMemoryAndCoreResize(computeServer, resizeConfig, scvmmOpts,
                        vmId, isWorkload, workload)
                if (resizeSuccess) {
                    boolean volumeSuccess = handleVolumeOperations(opts, resizeRequest, computeServer, scvmmOpts, rtn)
                    if (!volumeSuccess) {
                        rtn.error = 'Failed to handle volume operations'
                    }
                } else {
                    rtn.error = 'Failed to resize memory/cores'
                }
                computeServer = getMorpheusServer(computeServer.id)
                rtn.success = true
            } else {
                rtn.success = false
                rtn.error = 'Server never stopped so resize could not be performed'
            }
            computeServer.status = PROVISIONED_STATUS
            computeServer = saveAndGet(computeServer)
            if (stopRequired) {
                isWorkload ? startWorkload(workload) : startServer(computeServer)
            }
            rtn.success = true
        } catch (e) {
            def resizeError = isWorkload
                    ? "Unable to resize workload: ${e.message}"
                    : "Unable to resize server: ${e.message}"
            log.error(resizeError, e)
            computeServer.status = PROVISIONED_STATUS
            computeServer.statusMessage = resizeError
            computeServer = saveAndGet(computeServer)
            rtn.success = false
            rtn.setError("${e}")
        }
        return rtn
    }

    // Helper for memory and core calculation
    protected void populateMemoryAndCoreConfig(Map rtn, Workload workload,
                                               ComputeServer server, ServicePlan plan, ResizeRequest resizeRequest) {
        rtn.requestedMemory = resizeRequest.maxMemory
        rtn.requestedCores = resizeRequest?.maxCores
        def currentMemory = resolveCurrentMemory(server, workload)
        def currentCores = resolveCurrentCores(server, workload)
        rtn.neededMemory = rtn.requestedMemory - currentMemory
        rtn.neededCores = (rtn.requestedCores ?: 1) - (currentCores ?: 1)
        setDynamicMemory(rtn, plan)
        rtn.hotResize = false
    }

    // Helper
    protected Long resolveCurrentMemory(ComputeServer server, Workload workload) {
        return server?.maxMemory
                ?: workload?.server?.maxMemory
                ?: workload?.maxMemory
                ?: workload?.getConfigProperty(MAX_MEMORY_FIELD)?.toLong()
    }

    // Helper
    protected Integer resolveCurrentCores(ComputeServer server, Workload workload) {
        return server?.maxCores ?: workload?.maxCores ?: 1
    }

    // Helper for disk update logic
    protected void handleDiskUpdates(Map rtn, Map opts, ResizeRequest resizeRequest) {
        if (opts.volumes) {
            resizeRequest.volumesUpdate?.each { volumeUpdate ->
                if (volumeUpdate.existingModel) {
                    def volumeCode = volumeUpdate.existingModel.type?.code ?: STANDARD_VALUE
                    if (volumeUpdate.updateProps.maxStorage > volumeUpdate.existingModel.maxStorage) {
                        if (volumeCode.contains("differencing")) {
                            log.warn("getResizeConfig - Resize is not supported on " +
                                    "Differencing Disks  - volume type ${volumeCode}")
                            rtn.allowed = false
                        } else {
                            log.info("getResizeConfig - volumeCode: ${volumeCode}. Volume Resize requested. " +
                                    "Current: ${volumeUpdate.existingModel.maxStorage} - requested " +
                                    ": ${volumeUpdate.updateProps.maxStorage}")
                            rtn.allowed = true
                        }
                        if (volumeUpdate.existingModel.rootVolume) {
                            rtn.hotResize = false
                        }
                    }
                } else {
                    log.info("getResizeConfig - Adding new volume ${volumeUpdate.volume}")
                    rtn.allowed = true
                }
            }
        }
    }

    // Refactored getResizeConfig
    protected Map getResizeConfig(Workload workload = null, ComputeServer server = null, ServicePlan plan,
                                  Map opts = [:], ResizeRequest resizeRequest) {
        log.debug("getResizeConfig: ${resizeRequest}")
        def rtn = [
                success         : true, allowed: true, hotResize: true, volumeSyncLists: null, requestedMemory: null,
                requestedCores  : null, neededMemory: null, neededCores: null, minDynamicMemory: null,
                maxDynamicMemory: null,
        ]
        try {
            populateMemoryAndCoreConfig(rtn, workload, server, plan, resizeRequest)
            handleDiskUpdates(rtn, opts, resizeRequest)
        } catch (e) {
            log.error("getResizeConfig error - ${e}", e)
        }
        return rtn
    }

    StorageVolume buildStorageVolume(ComputeServer computeServer, Map volumeAdd, Integer newCounter) {
        def newVolume = new StorageVolume(
                refType: COMPUTE_ZONE_REF_TYPE,
                refId: computeServer.cloud.id,
                regionCode: computeServer.region?.regionCode,
                account: computeServer.account,
                maxStorage: volumeAdd.maxStorage?.toLong(),
                maxIOPS: volumeAdd.maxIOPS?.toInteger(),
                name: volumeAdd.name,
                displayOrder: newCounter,
                status: PROVISIONED_STATUS,
                deviceDisplayName: getDiskDisplayName(newCounter)
        )
        return newVolume
    }

    LogInterface getLog() {
        return this.log
    }
    /**
     * Returns a String array of block device names i.e. (['vda','vdb','vdc']) in the order
     * of the disk index.
     * @return the String array
     */
    @SuppressWarnings('GetterMethodCouldBeProperty')
    @Override
    String[] getDiskNameList() {
        return DISK_NAMES
    }
}
