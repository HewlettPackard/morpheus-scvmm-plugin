package com.morpheusdata.scvmm

import com.morpheusdata.model.BackupProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkSubnetType
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.OsType
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.scvmm.helper.morpheus.types.StorageVolumeTypeHelper
import com.morpheusdata.scvmm.helper.morpheus.types.OptionTypeHelper
import com.morpheusdata.scvmm.helper.morpheus.types.ComputeServerTypeHelper
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import com.morpheusdata.scvmm.sync.CloudCapabilityProfilesSync
import com.morpheusdata.scvmm.sync.ClustersSync
import com.morpheusdata.scvmm.sync.DatastoresSync
import com.morpheusdata.scvmm.sync.HostSync
import com.morpheusdata.scvmm.sync.IpPoolsSync
import com.morpheusdata.scvmm.sync.IsolationNetworkSync
import com.morpheusdata.scvmm.sync.RegisteredStorageFileSharesSync
import com.morpheusdata.scvmm.sync.NetworkSync
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.core.util.MorpheusUtils
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.scvmm.sync.TemplatesSync
import com.morpheusdata.scvmm.sync.VirtualMachineSync
import java.time.Instant

@SuppressWarnings(['CompileStatic', 'MethodCount'])
class ScvmmCloudProvider implements CloudProvider {
    public static final String CLOUD_PROVIDER_CODE = 'scvmm'
    private static final String HOST_CONFIG_PROPERTY = 'host'
    private static final String SCVMM_CIRCULAR_SVG = 'scvmm-circular.svg'
    private static final String SHARED_CONTROLLER_REQUIRED_MSG = 'You must specify a shared controller'
    private static final String SCVMM_CONTROLLER_CODE = 'scvmmController'
    private static final String ZONE_ID_FIELD = 'zone.id'
    private static final String COMPUTE_SERVER_TYPE_CODE_FIELD = 'computeServerType.code'
    // Add these constants to the existing ones at the top of the class
    private static final String SERVER_TYPE_HYPERVISOR_FIELD = 'serverType'
    private static final String SERVER_TYPE_HYPERVISOR_VALUE = 'hypervisor'
    private static final String SHARED_CONTROLLER_ERROR_KEY = 'sharedController'

    // Add these constants to the existing ones at the top of the class
    private static final String ENABLED_FIELD = 'enabled'
    private static final String ACCOUNT_ID_FIELD = 'account.id'

    // Add these constants to the existing ones at the top of the class
    private static final int SCVMM_PORT = 5985
    private static final String ERROR_CONNECTING_MSG = 'error connecting'
    private static final String ERROR_CONNECTING_TO_CONTROLLER_MSG = 'error connecting to controller'
    private static final String POWER_STATE_ON = 'on'
    private static final String POWER_STATE_UNKNOWN = 'unknown'
    private static final String STATUS_ERROR = 'error'

    // Add these constants to the existing ones at the top of the class
    private static final String WORKING_PATH_CONFIG = 'workingPath'
    private static final String DISK_PATH_CONFIG = 'diskPath'
    private static final String LIBRARY_SHARE_CONFIG = 'libraryShare'
    private static final String INSTALL_AGENT_CONFIG = 'installAgent'
    private static final String ENTER_USERNAME_MSG = 'Enter a username'
    private static final String ENTER_PASSWORD_MSG = 'Enter a password'
    private static final String NO_ZONE_FOUND_MSG = 'No zone found'
    private static final String CONTROLLER_NOT_FOUND_MSG = 'controller not found'
    private static final int TOKEN_INDEX = 2

    // Add these constants to fix duplicate string literals
    private static final String CODE_FIELD = 'code'
    private static final String WINDOWS_OS = 'windows'
    private static final String IMPORT_EXISTING_CONFIG = 'importExisting'
    private static final String TRUE_STRING = 'true'
    private static final String COMPUTE_SERVER_TYPE_JOIN = 'computeServerType'
    private static final String HOST_ONLINE_LOG_MSG = 'hostOnline: {}'
    static final Map DEFAULT_FAILURE = [success: false]

    protected MorpheusContext context
    protected ScvmmPlugin plugin
    ScvmmApiService apiService
    private final LogInterface log = LogWrapper.instance

    ScvmmCloudProvider(ScvmmPlugin plugin, MorpheusContext context) {
        super()
        this.@plugin = plugin
        this.@context = context
        this.apiService = new ScvmmApiService(context)
    }

    /**
     * Grabs the description for the CloudProvider
     * @return String
     */
    @SuppressWarnings('GetterMethodCouldBeProperty')
    @Override
    String getDescription() {
        return 'System Center Virtual Machine Manager'
    }

    /**
     * Returns the Cloud logo for display when a user needs to view or add this cloud. SVGs are preferred.
     * @since 0.13.0
     * @return Icon representation of assets stored in the src/assets of the project.
     */
    @Override
    Icon getIcon() {
        return new Icon(path: 'scvmm.svg', darkPath: 'scvmm-dark.svg')
    }

    /**
     * Returns the circular Cloud logo for display when a user needs to view or add this cloud. SVGs are preferred.
     * @since 0.13.6
     * @return Icon
     */
    @Override
    Icon getCircularIcon() {
        // TODO: change icon paths to correct filenames once added to your project
        return new Icon(path: SCVMM_CIRCULAR_SVG, darkPath: SCVMM_CIRCULAR_SVG)
    }

    /**
     * Provides a Collection of OptionType inputs that define the required input fields for defining
     * a cloud integration
     * @return Collection of OptionType
     */
    @SuppressWarnings('UnnecessaryGetter')
    @Override
    Collection<OptionType> getOptionTypes() {
        return OptionTypeHelper.getAllOptionTypes()
    }

    /**
     * Grabs available provisioning providers related to the target Cloud Plugin. Some clouds have multiple provisioning
     * providers or some clouds allow for service based providers on top like (Docker or Kubernetes).
     * @return Collection of ProvisionProvider
     */
    @Override
    Collection<ProvisionProvider> getAvailableProvisionProviders() {
        return this.@plugin.getProvidersByType(ProvisionProvider) as Collection<ProvisionProvider>
    }

    /**
     * Grabs available backup providers related to the target Cloud Plugin.
     * @return Collection of BackupProvider
     */
    @Override
    Collection<BackupProvider> getAvailableBackupProviders() {
        Collection<BackupProvider> providers = []
        return providers
    }

    /**
     * Provides a Collection of {@link NetworkType} related to this CloudProvider
     * @return Collection of NetworkType
     */
    @Override
    Collection<NetworkType> getNetworkTypes() {
        Collection<NetworkType> networks = []
        return networks
    }

    /**
     * Provides a Collection of {@link NetworkSubnetType} related to this CloudProvider
     * @return Collection of NetworkSubnetType
     */
    @Override
    Collection<NetworkSubnetType> getSubnetTypes() {
        Collection<NetworkSubnetType> subnets = []
        return subnets
    }

    /**
     * Provides a Collection of {@link StorageVolumeType} related to this CloudProvider
     * @return Collection of StorageVolumeType
     */
    @SuppressWarnings(['UnnecessaryGetter'])
    @Override
    Collection<StorageVolumeType> getStorageVolumeTypes() {
        return StorageVolumeTypeHelper.getAllStorageVolumeTypes()
    }

    /**
     * Provides a Collection of {@link StorageControllerType} related to this CloudProvider
     * @return Collection of StorageControllerType
     */
    @Override
    Collection<StorageControllerType> getStorageControllerTypes() {
        Collection<StorageControllerType> controllerTypes = []
        return controllerTypes
    }

    /**
     * Grabs all {@link ComputeServerType} objects that this CloudProvider can represent during
     * a sync or during a provision.
     * @return collection of ComputeServerType
     */
    @SuppressWarnings('UnnecessaryGetter')
    @Override
    Collection<ComputeServerType> getComputeServerTypes() {
        return ComputeServerTypeHelper.getAllComputeServerTypes()
    }

    /**
     * Validates the submitted cloud information to make sure it is functioning correctly.
     * If a {@link ServiceResponse} is not marked as successful then the validation results will be
     * bubbled up to the user.
     * @param cloudInfo cloud
     * @param validateCloudRequest Additional validation information
     * @return ServiceResponse
     */
    @SuppressWarnings('UnnecessaryGetter')
    @Override
    ServiceResponse validate(Cloud cloudInfo, ValidateCloudRequest validateCloudRequest) {
        log.debug("validate cloud: {}", cloudInfo)
        def rtn = [success: false, zone: cloudInfo, errors: [:]]
        try {
            if (rtn.zone) {
                def requiredFields = [HOST_CONFIG_PROPERTY,
                                      WORKING_PATH_CONFIG, DISK_PATH_CONFIG, LIBRARY_SHARE_CONFIG]
                def scvmmOpts = apiService.getScvmmZoneOpts(context, cloudInfo)
                def zoneConfig = cloudInfo.getConfigMap()
                rtn.errors = validateRequiredConfigFields(requiredFields, zoneConfig)
                // Verify that a shared controller is selected if we already have an scvmm zone
                // pointed to this host (MUST SHARE THE CONTROLLER)
                def validateControllerResults = validateSharedController(cloudInfo)
                if (!validateControllerResults.success) {
                    rtn.errors[SHARED_CONTROLLER_ERROR_KEY] =
                            validateControllerResults.msg ?: SHARED_CONTROLLER_REQUIRED_MSG
                }
                if (!validateCloudRequest?.credentialUsername) {
                    rtn.msg = ENTER_USERNAME_MSG
                    rtn.errors.username = ENTER_USERNAME_MSG
                } else if (!validateCloudRequest?.credentialPassword) {
                    rtn.msg = ENTER_PASSWORD_MSG
                    rtn.errors.password = ENTER_PASSWORD_MSG
                }
                if (rtn.errors.size() == 0) {
                    // set install agent
                    def installAgent = MorpheusUtils.parseBooleanConfig(zoneConfig.installAgent)
                    cloudInfo.setConfigProperty(INSTALL_AGENT_CONFIG, installAgent)
                    // build opts
                    scvmmOpts += [
                            hypervisor : [:],
                            sshHost    : zoneConfig.host,
                            sshUsername: validateCloudRequest?.credentialUsername,
                            sshPassword: validateCloudRequest?.credentialPassword,
                            zoneRoot   : zoneConfig.workingPath,
                            diskRoot   : zoneConfig.diskPath,
                    ]
                    def vmSitches = apiService.listClouds(scvmmOpts)
                    log.debug("vmSitches: ${vmSitches}")
                    if (vmSitches.success == true) {
                        rtn.success = true
                    }
                    if (rtn.success == false) {
                        rtn.msg = 'Error connecting to scvmm'
                    }
                }
            } else {
                rtn.message = NO_ZONE_FOUND_MSG
            }
        } catch (e) {
            log.error("An Exception Has Occurred", e)
        }
        return ServiceResponse.create(rtn)
    }

    /**
     * Called when a Cloud From Morpheus is first saved. This is a hook provided to take care of initial state
     * assignment that may need to take place.
     * @param cloudInfo instance of the cloud object that is being initialized.
     * @return ServiceResponse
     */
    @Override
    ServiceResponse initializeCloud(Cloud cloudInfo) {
        log.debug('initializing cloud: {}', cloudInfo.code)
        ServiceResponse rtn = ServiceResponse.prepare()
        try {
            if (cloudInfo) {
                if (cloudInfo.enabled == true) {
                    def initResults = initializeHypervisor(cloudInfo)
                    log.debug("initResults: {}", initResults)
                    if (initResults.success == true) {
                        refresh(cloudInfo)
                    }
                    rtn.success = true
                }
            } else {
                rtn.msg = NO_ZONE_FOUND_MSG
            }
        } catch (e) {
            log.error("initialize cloud error: {}", e)
        }
        return rtn
    }

    @SuppressWarnings('AbcMetric')
    Map initializeHypervisor(Cloud cloud) {
        def rtn = DEFAULT_FAILURE.clone()
        log.debug("cloud: ${cloud}")
        def sharedController = cloud.getConfigProperty(SHARED_CONTROLLER_ERROR_KEY)
        if (sharedController) {
            // No controller needed.. we are sharing another cloud's controller
            rtn.success = true
        } else {
            ComputeServer newServer
            def opts = apiService.getScvmmInitializationOpts(cloud)
            def serverInfo = apiService.getScvmmServerInfo(opts)
            String versionCode
            versionCode = apiService.extractWindowsServerVersion(serverInfo.osName)
            if (serverInfo.success == true && serverInfo.hostname) {
                newServer = context.services.computeServer.find(new DataQuery().withFilters(
                        new DataFilter(ZONE_ID_FIELD, cloud.id),
                        new DataOrFilter(
                                new DataFilter('hostname', serverInfo.hostname),
                                new DataFilter('name', serverInfo.hostname)
                        )
                ))
                if (!newServer) {
                    newServer = new ComputeServer()
                    newServer.account = cloud.account
                    newServer.cloud = cloud
                    newServer.computeServerType =
                            context.async.cloud.findComputeServerTypeByCode(SCVMM_CONTROLLER_CODE).blockingGet()
                    // Create proper OS code format
                    newServer.serverOs = new OsType(code: versionCode)
                    newServer.name = serverInfo.hostname
                    newServer = context.services.computeServer.create(newServer)
                }
                if (serverInfo.hostname) {
                    newServer.hostname = serverInfo.hostname
                }
                newServer.with {
                    sshHost = cloud.getConfigProperty(HOST_CONFIG_PROPERTY)
                    internalIp = sshHost
                    externalIp = sshHost
                    sshUsername = apiService.getUsername(cloud)
                    sshPassword = apiService.getPassword(cloud)
                    setConfigProperty(WORKING_PATH_CONFIG, cloud.getConfigProperty(WORKING_PATH_CONFIG))
                    setConfigProperty(DISK_PATH_CONFIG, cloud.getConfigProperty(DISK_PATH_CONFIG))
                }
            }

            def maximumStorage = getMaxStorage(serverInfo)
            def maximumMemory = getMaxMemory(serverInfo)
            def maximumCores = 1
            newServer.serverOs =
                    context.async.osType.find(new DataQuery().withFilter(CODE_FIELD, versionCode)).blockingGet()
            newServer.platform = WINDOWS_OS
            def tokens = versionCode.split("\\.")
            def version = tokens.length > TOKEN_INDEX ? tokens[TOKEN_INDEX] : ""
            if (newServer.serverOs == null) {
                newServer.serverOs =
                        context.async.osType.find(
                                new DataQuery().withFilter(
                                        CODE_FIELD, "windows.server.${version}")).blockingGet()
            }

            newServer.with {
                platform = WINDOWS_OS
                platformVersion = version
                statusDate = Date.from(Instant.now())
                status = 'provisioning'
                powerState = POWER_STATE_ON
                serverType = SERVER_TYPE_HYPERVISOR_VALUE
                osType = WINDOWS_OS // linux, windows, unmanaged
                maxMemory = maximumMemory
                maxCores = maximumCores
                maxStorage = maximumStorage
            }

            // initializeHypervisor from context
            log.debug("newServer: ${newServer}")
            context.services.computeServer.save(newServer)
            if (newServer) {
                context.async.hypervisorService.initialize(newServer)
                rtn.success = true
            }
        }
        return rtn
    }

    /**
     * Zones/Clouds are refreshed periodically by the Morpheus Environment.
     * This includes things like caching of brownfield
     * environments and resources such as Networks, Datastores, Resource Pools, etc.
     * @param cloudInfo cloud
     * @return ServiceResponse. If ServiceResponse.success == true, then Cloud status will be set to Cloud.Status.ok.
     * If ServiceResponse.success == false, the Cloud status will be set to ServiceResponse.data['status']
     * or Cloud.Status.error
     * if not specified. So, to indicate that the Cloud is offline,
     * return `ServiceResponse.error('cloud is not reachable', null, [status: Cloud.Status.offline])`
     */
    @SuppressWarnings('AbcMetric')
    @Override
    ServiceResponse refresh(Cloud cloudInfo) {
        log.debug("refresh: {}", cloudInfo)
        ServiceResponse response = ServiceResponse.prepare()
        try {
            def syncDate = Date.from(Instant.now())
            //why would we ever have more than 1? i don't think we would
            def scvmmController = getScvmmController(cloudInfo)
            if (scvmmController) {
                def scvmmOpts =
                        apiService.getScvmmZoneAndHypervisorOpts(context, cloudInfo, scvmmController)
                def hostOnline =
                        ConnectionUtils.testHostConnectivity(scvmmOpts.sshHost, SCVMM_PORT,
                                false, true, null)
                log.debug(HOST_ONLINE_LOG_MSG, hostOnline)
                if (hostOnline) {
                    def checkResults = checkCommunication(cloudInfo, scvmmController)
                    if (checkResults.success == true) {
                        updateHypervisorStatus(scvmmController, 'provisioned', POWER_STATE_ON, '')
                        //updateZoneStatus(zone, 'syncing', null)
                        context.async.cloud.updateCloudStatus(cloudInfo, Cloud.Status.syncing, null, syncDate)

                        def now = System.currentTimeMillis()
                        new NetworkSync(context, cloudInfo).execute()
                        log.debug("${cloudInfo.name}: NetworkSync in ${System.currentTimeMillis() - now}ms")

                        now = System.currentTimeMillis()
                        new ClustersSync(context, cloudInfo).execute()
                        log.debug("${cloudInfo.name}: ClustersSync in ${System.currentTimeMillis() - now}ms")

                        now = System.currentTimeMillis()
                        new IsolationNetworkSync(context, cloudInfo, apiService).execute()
                        log.debug("${cloudInfo.name}: IsolationNetworkSync in ${System.currentTimeMillis() - now}ms")

                        now = System.currentTimeMillis()
                        new HostSync(cloudInfo, scvmmController, context).execute()
                        log.debug("${cloudInfo.name}: HostSync in ${System.currentTimeMillis() - now}ms")

                        now = System.currentTimeMillis()
                        new DatastoresSync(scvmmController, cloudInfo, context).execute()
                        log.debug("${cloudInfo.name}: DatastoresSync in ${System.currentTimeMillis() - now}ms")

                        now = System.currentTimeMillis()
                        new RegisteredStorageFileSharesSync(cloudInfo, scvmmController, context).execute()
                        log.debug("${cloudInfo.name}: RegisteredStorageFileSharesSync" +
                                " in ${System.currentTimeMillis() - now}ms")

                        now = System.currentTimeMillis()
                        new CloudCapabilityProfilesSync(context, cloudInfo).execute()
                        log.debug("${cloudInfo.name}: CloudCapabilityProfilesSync" +
                                " in ${System.currentTimeMillis() - now}ms")

                        now = System.currentTimeMillis()
                        new TemplatesSync(cloudInfo, scvmmController, context, this).execute()
                        log.debug("${cloudInfo.name}: TemplatesSync in ${System.currentTimeMillis() - now}ms")

                        now = System.currentTimeMillis()
                        new IpPoolsSync(context, cloudInfo).execute()
                        log.debug("${cloudInfo.name}: IpPoolsSync in ${System.currentTimeMillis() - now}ms")

                        def doInventory = cloudInfo.getConfigProperty(IMPORT_EXISTING_CONFIG)
                        def createNew = (doInventory == POWER_STATE_ON ||
                                doInventory == TRUE_STRING || doInventory == true)
                        now = System.currentTimeMillis()
                        new VirtualMachineSync(scvmmController, cloudInfo, context, this).execute(createNew)
                        log.debug("${cloudInfo.name}: DatastoresSync in ${System.currentTimeMillis() - now}ms")
                        context.async.cloud.updateCloudStatus(cloudInfo, Cloud.Status.ok, null, syncDate)
                        log.debug "complete scvmm zone refresh"
                        response.success = true
                    } else {
                        updateHypervisorStatus(scvmmController, STATUS_ERROR, POWER_STATE_UNKNOWN,
                                ERROR_CONNECTING_TO_CONTROLLER_MSG)
                        context.async.cloud.updateCloudStatus(cloudInfo, Cloud.Status.error,
                                ERROR_CONNECTING_MSG, syncDate)
                    }
                } else {
                    updateHypervisorStatus(scvmmController, STATUS_ERROR, POWER_STATE_UNKNOWN,
                            ERROR_CONNECTING_TO_CONTROLLER_MSG)
                    context.async.cloud.updateCloudStatus(cloudInfo,
                            Cloud.Status.error, ERROR_CONNECTING_MSG, syncDate)
                }
            } else {
                context.async.cloud.updateCloudStatus(cloudInfo, Cloud.Status.error,
                        CONTROLLER_NOT_FOUND_MSG, syncDate)
            }
        } catch (e) {
            log.error("refresh zone error:${e}", e)
        }
        return response
    }

    Map checkCommunication(Cloud cloud, ComputeServer node) {
        log.debug("checkCommunication: {} {}", cloud, node)
        def rtn = [:]
        rtn.success = false
        try {
            def scvmmOpts = apiService.getScvmmZoneAndHypervisorOpts(context, cloud, node)
            def listResults = apiService.listAllNetworks(scvmmOpts)
            if (listResults.success == true && listResults.networks) {
                rtn.success = true
            }
        } catch (e) {
            log.error("checkCommunication error:${e}", e)
        }
        return rtn
    }

    ComputeServer getScvmmController(Cloud cloud) {
        def sharedControllerId = cloud.getConfigProperty(SHARED_CONTROLLER_ERROR_KEY)
        def sharedController =
                sharedControllerId ? context.services.computeServer.get(sharedControllerId.toLong()) : null
        if (sharedController) {
            return sharedController
        }
        def rtn = context.services.computeServer.find(new DataQuery()
                .withFilter(ZONE_ID_FIELD, cloud.id)
                .withFilter(COMPUTE_SERVER_TYPE_CODE_FIELD, SCVMM_CONTROLLER_CODE)
                .withJoin(COMPUTE_SERVER_TYPE_JOIN))
        if (rtn == null) {
            // old zone with wrong type
            rtn = context.services.computeServer.find(new DataQuery()
                    .withFilter(ZONE_ID_FIELD, cloud.id)
                    .withFilter(COMPUTE_SERVER_TYPE_CODE_FIELD, SCVMM_CONTROLLER_CODE)
                    .withJoin(COMPUTE_SERVER_TYPE_JOIN))
            if (rtn == null) {
                rtn = context.services.computeServer.find(new DataQuery()
                        .withFilter(ZONE_ID_FIELD, cloud.id)
                        .withFilter(SERVER_TYPE_HYPERVISOR_FIELD, SERVER_TYPE_HYPERVISOR_VALUE))
            }
            // if we have tye type
            if (rtn) {
                def serverType =
                        context.async.cloud.findComputeServerTypeByCode(SCVMM_CONTROLLER_CODE).blockingGet()
                rtn.computeServerType = serverType
                context.async.computeServer.save(rtn).blockingGet()
            }
        }
        return rtn
    }

    /**
     * Zones/Clouds are refreshed periodically by the Morpheus Environment. This includes things
     * like caching of brownfield
     * environments and resources such as Networks, Datastores, Resource Pools, etc.
     * This represents the long term sync method that happens
     * daily instead of every 5-10 minute cycle
     * @param cloudInfo cloud
     */
    @Override
    void refreshDaily(Cloud cloudInfo) {
        log.debug("refreshDaily: {}", cloudInfo)
        try {
            def scvmmController = getScvmmController(cloudInfo)
            if (scvmmController) {
                def scvmmOpts =
                        apiService.getScvmmZoneAndHypervisorOpts(context, cloudInfo, scvmmController)
                def hostOnline =
                        ConnectionUtils.testHostConnectivity(scvmmOpts.sshHost, SCVMM_PORT,
                                false, true, null)
                log.debug(HOST_ONLINE_LOG_MSG, hostOnline)
                if (hostOnline) {
                    def checkResults = checkCommunication(cloudInfo, scvmmController)
                    if (checkResults.success == true) {
                        removeOrphanedResourceLibraryItems(cloudInfo, scvmmController)
                    }
                }
            }
        } catch (e) {
            log.error "Error on refreshDailyZone: ${e}", e
        }
    }

    /**
     * Called when a Cloud From Morpheus is removed. This is a hook provided to take care of cleaning up any state.
     * @param cloudInfo instance of the cloud object that is being removed.
     * @return ServiceResponse
     */
    @Override
    ServiceResponse deleteCloud(Cloud cloudInfo) {
        return ServiceResponse.success()
    }

    /**
     * Returns whether the cloud supports {@link CloudPool}
     * @return Boolean
     */
    @Override
    Boolean hasComputeZonePools() {
        return true
    }

    /**
     * Returns whether a cloud supports {@link Network}
     * @return Boolean
     */
    @Override
    Boolean hasNetworks() {
        return true
    }

    /**
     * Returns whether a cloud supports {@link CloudFolder}
     * @return Boolean
     */
    @Override
    Boolean hasFolders() {
        return false
    }

    /**
     * Returns whether a cloud supports {@link Datastore}
     * @return Boolean
     */
    @Override
    Boolean hasDatastores() {
        return true
    }

    /**
     * Returns whether a cloud supports bare metal VMs
     * @return Boolean
     */
    @Override
    Boolean hasBareMetal() {
        return false
    }

    /**
     * Indicates if the cloud supports cloud-init. Returning true will allow configuration of the Cloud
     * to allow installing the agent remotely via SSH /WinRM or via Cloud Init
     * @return Boolean
     */
    @Override
    Boolean hasCloudInit() {
        return true
    }

    /**
     * Indicates if the cloud supports the distributed worker functionality
     * @return Boolean
     */
    @Override
    Boolean supportsDistributedWorker() {
        return false
    }

    /**
     * Called when a server should be started. Returning a response of success will cause corresponding
     * updates to usage records, result in the powerState of the computeServer to be
     * set to 'on', and related instances set to 'running'
     * @param computeServer server to start
     * @return ServiceResponse
     */
    @Override
    ServiceResponse startServer(ComputeServer computeServer) {
        ScvmmProvisionProvider provisionProvider = new ScvmmProvisionProvider(plugin, context)
        return provisionProvider.startServer(computeServer)
    }

    /**
     * Called when a server should be stopped. Returning a response of success will cause
     * corresponding updates to usage records, result in the powerState of the computeServer to be set
     * to 'off', and related instances set to 'stopped'
     * @param computeServer server to stop
     * @return ServiceResponse
     */
    @Override
    ServiceResponse stopServer(ComputeServer computeServer) {
        ScvmmProvisionProvider provisionProvider = new ScvmmProvisionProvider(plugin, context)
        return provisionProvider.stopServer(computeServer)
    }

    protected long getMaxMemory(Map serverInfo) {
        def maxMemory = 0L
        if (serverInfo?.memory && serverInfo?.memory?.toString()?.trim()) {
            try {
                maxMemory = serverInfo?.memory?.toString()?.toLong()
            } catch (NumberFormatException e) {
                log.warn("Invalid memory value '${serverInfo.memory}', defaulting to 0")
            }
        }
        return maxMemory
    }

    protected long getMaxStorage(Map serverInfo) {
        def maxStorage = 0L
        if (serverInfo?.disks && serverInfo?.disks?.toString()?.trim()) {
            try {
                maxStorage = serverInfo?.disks?.toString()?.toLong()
            } catch (NumberFormatException e) {
                log.warn("Invalid disk value '${serverInfo.disks}', defaulting to 0")
            }
        }
        return maxStorage
    }

    /**
     * Called when a server should be deleted from the Cloud.
     * @param computeServer server to delete
     * @return ServiceResponse
     */
    @SuppressWarnings('DuplicateMapLiteral')
    @Override
    ServiceResponse deleteServer(ComputeServer computeServer) {
        log.debug("deleteServer: ${computeServer}")
        def rtn = DEFAULT_FAILURE.clone()
        try {
            ScvmmProvisionProvider provisionProvider = new ScvmmProvisionProvider(plugin, context)
            def scvmmOpts = provisionProvider.getAllScvmmServerOpts(computeServer)

            def stopResults = apiService.stopServer(scvmmOpts, scvmmOpts.externalId)
            if (stopResults.success == true) {
                def deleteResults = apiService.deleteServer(scvmmOpts, scvmmOpts.externalId)
                if (deleteResults.success == true) {
                    rtn.success = true
                }
            }
        } catch (e) {
            log.error("deleteServer error: ${e}", e)
            rtn.msg = e.message
        }
        return ServiceResponse.create(rtn)
    }

    /**
     * Grabs the singleton instance of the provisioning provider based on the code defined in its implementation.
     * Typically Providers are singleton and instanced in the {@link Plugin} class
     * @param providerCode String representation of the provider short code
     * @return the ProvisionProvider requested
     */
    @SuppressWarnings('UnnecessaryGetter')
    @Override
    ProvisionProvider getProvisionProvider(String providerCode) {
        return getAvailableProvisionProviders().find { provider -> provider.code == providerCode }
    }

    /**
     * Returns the default provision code for fetching a {@link ProvisionProvider} for this cloud.
     * This is only really necessary if the provision type code is the exact same as the cloud code.
     * @return the provision provider code
     */
    @Override
    String getDefaultProvisionTypeCode() {
        return ScvmmProvisionProvider.PROVIDER_CODE
    }

    /**
     * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
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
        return CLOUD_PROVIDER_CODE
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

    // @SuppressWarnings(['MethodParameterTypeRequired', 'MethodReturnTypeRequired'])
    Map validateRequiredConfigFields(List<String> fieldArray, Map config) {
        def errors = [:]
        fieldArray.each { field ->
            if (config[field] != null && config[field]?.size() == 0) {
                def display = field.replaceAll(/([A-Z])/, / $1/).toLowerCase()
                errors[field] = "Enter a ${display}"
            }
        }
        return errors
    }

    @SuppressWarnings('InvertedIfElse')
    Map validateSharedController(Cloud cloud) {
        log.debug "validateSharedController: ${cloud}"
        def rtn = [success: true]

        def sharedControllerId = cloud.getConfigProperty(SHARED_CONTROLLER_ERROR_KEY)
        if (!sharedControllerId) {
            if (cloud.id) {
                def existingControllerInZone = context.services.computeServer.find(new DataQuery()
                        .withFilter(COMPUTE_SERVER_TYPE_CODE_FIELD, SCVMM_CONTROLLER_CODE)
                        .withFilter(ZONE_ID_FIELD, cloud.id.toLong()))
                if (existingControllerInZone) {
                    rtn.success = true
                    return rtn
                }
            }
            def existingController = context.services.computeServer.find(new DataQuery()
                    .withFilter(ENABLED_FIELD, true)
                    .withFilter(ACCOUNT_ID_FIELD, cloud.account.id)
                    .withFilter(COMPUTE_SERVER_TYPE_CODE_FIELD, SCVMM_CONTROLLER_CODE)
                    .withFilter('externalIp', cloud.getConfigProperty(HOST_CONFIG_PROPERTY)))
            if (existingController) {
                log.debug "Found another controller: ${existingController.id} in" +
                        " zone: ${existingController.cloud} that should be used"
                rtn.success = false
                rtn.msg = SHARED_CONTROLLER_REQUIRED_MSG
            }
        } else {
            // Verify that the controller selected has the same Host ip as defined in the cloud
            def sharedController = context.services.computeServer.get(sharedControllerId?.toLong())
            if (sharedController?.sshHost != cloud.getConfigProperty(HOST_CONFIG_PROPERTY) &&
                    sharedController?.externalIp != cloud.getConfigProperty(HOST_CONFIG_PROPERTY)) {
                rtn.success = false
                rtn.msg = 'The selected controller is on a different host than specified for this cloud'
            }
        }
        return rtn
    }

    Map removeOrphanedResourceLibraryItems(Cloud cloud, ComputeServer node) {
        log.debug("removeOrphanedResourceLibraryItems: {} {}", cloud, node)
        def rtn = DEFAULT_FAILURE.clone()
        try {
            def scvmmOpts = apiService.getScvmmZoneAndHypervisorOpts(context, cloud, node)
            apiService.removeOrphanedResourceLibraryItems(scvmmOpts)
        } catch (e) {
            log.error("removeOrphanedResourceLibraryItems error:${e}", e)
        }
        return rtn
    }

    protected void updateHypervisorStatus(ComputeServer server, String status, String powerState, String msg) {
        log.debug("server: {}, status: {}, powerState: {}, msg: {}", server, status, powerState, msg)
        if (server.status != status || server.powerState != powerState) {
            server.status = status
            server.powerState = powerState
            server.statusDate = Date.from(Instant.now())
            server.statusMessage = msg
            context.services.computeServer.save(server)
        }
    }
}
