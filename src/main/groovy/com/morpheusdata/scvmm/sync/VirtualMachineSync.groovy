package com.morpheusdata.scvmm.sync

import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.core.util.SyncUtils
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.ResourcePermission
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.Workload
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.StorageVolumeIdentityProjection
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper

import io.reactivex.rxjava3.core.Observable
import java.time.Instant

/**
 * @author rahul.ray
 */

class VirtualMachineSync {

    // Constants for duplicate string literals
    private static final String ZONE_ID = 'zone.id'
    private static final String COMPUTE_SERVER_TYPE_CODE = 'computeServerType.code'
    private static final String NOT_EQUALS = '!='
    private static final String SCVMM_HYPERVISOR = 'scvmmHypervisor'
    private static final String SCVMM_CONTROLLER = 'scvmmController'
    private static final String SCVMM_UNMANAGED = 'scvmmUnmanaged'
    private static final String CODE = 'code'
    private static final String BACKSLASH = '\\'
    private static final String PASSWORD = 'password'
    private static final String USERNAME = 'username'
    private static final String VMRDP = 'vmrdp'
    private static final String DUNNO = 'dunno'
    private static final String TRUE_STRING = 'true'
    private static final String OTHER = 'other'
    private static final String PROVISIONING = 'provisioning'
    private static final String RESIZING = 'resizing'
    private static final String STATUS = 'status'
    private static final String SERVER_ID = 'server.id'
    private static final String ID = 'id'
    private static final String IN = 'in'
    private static final String RUNNING = 'Running'
    private static final String BOOT_AND_SYSTEM = 'BootAndSystem'
    private static final String STANDARD = 'standard'
    private static final String EXTERNAL_ID = 'externalId'
    private static final String COMPUTE_ZONE = 'ComputeZone'
    private static final String DATASTORE_REF_TYPE = 'datastore.refType'
    private static final String DATASTORE_REF_ID = 'datastore.refId'
    private static final String REF_TYPE = 'refType'

    // Constants for duplicate numbers
    private static final long KILOBYTE = 1024L
    private static final long BYTES_PER_MB = KILOBYTE * KILOBYTE
    private static final int DEFAULT_CONSOLE_PORT = 2179
    private static final long ZERO_LONG = 0L

    ComputeServer node
    private final Cloud cloud
    private final MorpheusContext context
    private final ScvmmApiService apiService
    private final CloudProvider cloudProvider
    private final LogInterface log = LogWrapper.instance

    VirtualMachineSync(ComputeServer node, Cloud cloud, MorpheusContext context, CloudProvider cloudProvider) {
        this.node = node
        this.cloud = cloud
        this.context = context
        this.apiService = new ScvmmApiService(context)
        this.@cloudProvider = cloudProvider
    }

    def execute(Boolean createNew) {
        log.debug 'VirtualMachineSync'

        try {
            def executionContext = initializeExecutionContext()
            def listResults = apiService.listVirtualMachines(executionContext.scvmmOpts)
            log.debug("VM List acquired in ${Instant.now().toEpochMilli() - executionContext.startTime.toEpochMilli()}ms")

            if (listResults.success == true) {
                performVirtualMachineSync(listResults, executionContext, createNew)
            }
        } catch (ex) {
            log.error("cacheVirtualMachines error: ${ex}", ex)
        }
    }

    private Map initializeExecutionContext() {
        def now = Instant.now()
        def consoleEnabled = cloud.getConfigProperty('enableVnc') ? true : false
        def scvmmOpts = apiService.getScvmmZoneAndHypervisorOpts(context, cloud, node)

        return [
                startTime: now,
                consoleEnabled: consoleEnabled,
                scvmmOpts: scvmmOpts,
        ]
    }

    private void performVirtualMachineSync(Map listResults, Map executionContext, Boolean createNew) {
        def syncData = prepareSyncData()
        def existingVms = getExistingVirtualMachines()

        executeSyncTask(existingVms, listResults, syncData, executionContext, createNew)
    }

    private Map prepareSyncData() {
        def hosts = context.services.computeServer.list(new DataQuery()
                .withFilter(ZONE_ID, cloud.id)
                .withFilter(COMPUTE_SERVER_TYPE_CODE, SCVMM_HYPERVISOR))

        Collection<ServicePlan> availablePlans = context.services.servicePlan.list(new DataQuery().withFilters(
                new DataFilter('active', true),
                new DataFilter('deleted', NOT_EQUALS, true),
                new DataFilter('provisionType.code', 'scvmm')
        ))

        ServicePlan fallbackPlan = context.services.servicePlan.find(new DataQuery().withFilter(CODE, 'internal-custom-scvmm'))

        Collection<ResourcePermission> availablePlanPermissions = []
        if (availablePlans) {
            availablePlanPermissions = context.services.resourcePermission.list(new DataQuery().withFilters(
                    new DataFilter('morpheusResourceType', 'ServicePlan'),
                    new DataFilter('morpheusResourceId', IN, availablePlans*.id)
            ))
        }

        def serverType = context.async.cloud.findComputeServerTypeByCode(SCVMM_UNMANAGED).blockingGet()

        return [
                hosts: hosts,
                availablePlans: availablePlans,
                fallbackPlan: fallbackPlan,
                availablePlanPermissions: availablePlanPermissions,
                serverType: serverType,
        ]
    }

    private getExistingVirtualMachines() {
        context.async.computeServer.listIdentityProjections(new DataQuery()
                .withFilter(ZONE_ID, cloud.id)
                .withFilter(COMPUTE_SERVER_TYPE_CODE, NOT_EQUALS, SCVMM_HYPERVISOR)
                .withFilter(COMPUTE_SERVER_TYPE_CODE, NOT_EQUALS, SCVMM_CONTROLLER))
    }

    private void executeSyncTask(Observable<ComputeServerIdentityProjection> existingVms, Map listResults, Map syncData, Map executionContext, Boolean createNew) {
        SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(existingVms, listResults.virtualMachines as Collection<Map>)
        syncTask.addMatchFunction { ComputeServerIdentityProjection morpheusItem, Map cloudItem ->
            morpheusItem.externalId == cloudItem.ID
        }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
            Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { item -> [(item.existingItem.id): item] }
            context.async.computeServer.listById(updateItems?.collect { item -> item.existingItem.id }).map { ComputeServer server ->
                SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map> matchItem = updateItemMap[server.id]
                new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: matchItem.masterItem)
            }
        }.onAdd { itemsToAdd ->
            if (createNew) {
                addMissingVirtualMachines(itemsToAdd, syncData.availablePlans, syncData.fallbackPlan,
                        syncData.availablePlanPermissions, syncData.hosts, executionContext.consoleEnabled, syncData.serverType)
            }
        }.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
            updateMatchedVirtualMachines(updateItems, syncData.availablePlans, syncData.fallbackPlan,
                    syncData.hosts, executionContext.consoleEnabled, syncData.serverType)
        }.onDelete { List<ComputeServerIdentityProjection> removeItems ->
            removeMissingVirtualMachines(removeItems)
        }.observe().blockingSubscribe()
    }

    def addMissingVirtualMachines(List addList, Collection<ServicePlan> availablePlans, ServicePlan fallbackPlan, Collection<ResourcePermission> availablePlanPermissions, List hosts, Boolean consoleEnabled, ComputeServerType defaultServerType) {
        try {
            for (cloudItem in addList) {
                log.debug "Adding new virtual machine: ${cloudItem.Name}"
                ComputeServer newServer = createNewVirtualMachine(cloudItem, availablePlans, fallbackPlan, availablePlanPermissions, hosts, consoleEnabled, defaultServerType)

                ComputeServer savedServer = context.async.computeServer.create(newServer).blockingGet()
                if (savedServer) {
                    syncVolumes(savedServer, cloudItem.Disks)
                } else {
                    log.error "error adding new virtual machine: ${newServer}"
                }
            }
        } catch (ex) {
            log.error("Error in adding VM: ${ex.message}", ex)
        }
    }

    private ComputeServer createNewVirtualMachine(Map cloudItem, Collection<ServicePlan> availablePlans, ServicePlan fallbackPlan,
                                                  Collection<ResourcePermission> availablePlanPermissions, List hosts, Boolean consoleEnabled, ComputeServerType defaultServerType) {
        def vmConfig = buildVmConfig(cloudItem, defaultServerType)
        ComputeServer add = new ComputeServer(vmConfig)

        configureServerResources(add, cloudItem)
        configureServerNetwork(add, cloudItem)
        configureServerParent(add, cloudItem, hosts)
        configureServerPlan(add, availablePlans, fallbackPlan, availablePlanPermissions)
        configureServerOperatingSystem(add, cloudItem)
        configureServerConsole(add, consoleEnabled)
        configureServerCapacity(add)

        return add
    }

    private void configureServerResources(ComputeServer server, Map cloudItem) {
        server.maxStorage = (cloudItem.TotalSize?.toDouble() ?: 0)
        server.usedStorage = (cloudItem.UsedSize?.toDouble() ?: 0)
        server.maxMemory = (cloudItem.Memory?.toLong() ?: 0) * BYTES_PER_MB
        server.maxCores = cloudItem.CPUCount.toLong() ?: 1
    }

    private void configureServerNetwork(ComputeServer server, Map cloudItem) {
        if (cloudItem.IpAddress) {
            server.externalIp = cloudItem.IpAddress
        }
        if (cloudItem.InternalIp) {
            server.internalIp = cloudItem.InternalIp
        }
        server.sshHost = server.internalIp ?: server.externalIp
    }

    private void configureServerParent(ComputeServer server, Map cloudItem, List hosts) {
        server.parentServer = hosts?.find { host -> host.externalId == cloudItem.HostId }
    }

    private void configureServerPlan(ComputeServer server, Collection<ServicePlan> availablePlans, ServicePlan fallbackPlan, Collection<ResourcePermission> availablePlanPermissions) {
        server.plan = SyncUtils.findServicePlanBySizing(availablePlans, server.maxMemory, server.maxCores, null, fallbackPlan, null, cloud.account, availablePlanPermissions)
    }

    private void configureServerOperatingSystem(ComputeServer server, Map cloudItem) {
        def osTypeCode = apiService.getMapScvmmOsType(cloudItem.OperatingSystem, true, 'Other Linux (64 bit)')
        def osTypeCodeStr = osTypeCode ?: OTHER
        def osType = context.services.osType.find(new DataQuery().withFilter(CODE, osTypeCodeStr))
        if (osType) {
            server.serverOs = osType
            server.osType = server.serverOs?.platform?.toString()?.toLowerCase()
            server.platform = osType?.platform
        }
    }

    private void configureServerConsole(ComputeServer server, Boolean consoleEnabled) {
        if (consoleEnabled) {
            server.consoleType = VMRDP
            server.consoleHost = server.parentServer?.name
            server.consolePort = DEFAULT_CONSOLE_PORT
            server.sshUsername = cloud.accountCredentialData?.username ?: cloud.getConfigProperty(USERNAME) ?: DUNNO
            if (server.sshUsername.contains(BACKSLASH)) {
                server.sshUsername = server.sshUsername.tokenize(BACKSLASH)[1]
            }
            server.consolePassword = cloud.accountCredentialData?.password ?: cloud.getConfigProperty(PASSWORD)
        }
    }

    private void configureServerCapacity(ComputeServer server) {
        server.capacityInfo = new ComputeCapacityInfo(maxCores: server.maxCores, maxMemory: server.maxMemory, maxStorage: server.maxStorage)
    }

    protected updateMatchedVirtualMachines(List<SyncTask.UpdateItem<ComputeServer, Map>> updateList, Collection availablePlans, ServicePlan fallbackPlan,
                                           List<ComputeServer> hosts, Boolean consoleEnabled, ComputeServerType defaultServerType) {
        log.debug('VirtualMachineSync >> updateMatchedVirtualMachines() called')
        try {
            def matchedServers = loadMatchedServers(updateList)
            List<ComputeServer> saves = processServerUpdates(updateList, matchedServers, availablePlans, fallbackPlan, hosts, consoleEnabled, defaultServerType)

            if (saves) {
                context.async.computeServer.bulkSave(saves).blockingGet()
            }
        } catch (Exception e) {
            log.error "Error in updating virtual machines: ${e.message}", e
        }
    }

    private Map loadMatchedServers(List<SyncTask.UpdateItem<ComputeServer, Map>> updateList) {
        return context.services.computeServer.list(new DataQuery().withFilter(ID, IN, updateList.collect { up -> up.existingItem.id })
                .withJoins(['account', 'zone', 'computeServerType', 'plan', 'chassis', 'serverOs', 'sourceImage', 'folder', 'createdBy', 'userGroup',
                            'networkDomain', 'interfaces', 'interfaces.addresses', 'controllers', 'snapshots', 'metadata', 'volumes',
                            'volumes.datastore', 'resourcePool', 'parentServer', 'capacityInfo'])).collectEntries { server -> [(server.id): server] }
    }

    private List<ComputeServer> processServerUpdates(List<SyncTask.UpdateItem<ComputeServer, Map>> updateList, Map matchedServers,
                                                     Collection availablePlans, ServicePlan fallbackPlan, List<ComputeServer> hosts, Boolean consoleEnabled, ComputeServerType defaultServerType) {
        List<ComputeServer> saves = []

        for (updateMap in updateList) {
            ComputeServer currentServer = matchedServers[updateMap.existingItem.id]
            def masterItem = updateMap.masterItem

            try {
                log.debug("Checking state of matched SCVMM Server ${masterItem.ID} - ${currentServer}")
                if (currentServer.status != PROVISIONING) {
                    Boolean save = updateSingleServer(currentServer, masterItem, hosts, consoleEnabled, defaultServerType, availablePlans, fallbackPlan)

                    log.debug ("updateMatchedVirtualMachines: save: ${save}")
                    if (save) {
                        saves << currentServer
                    }
                }
            } catch (RuntimeException e) {
                log.error "Error in updating server ${currentServer?.name}: ${e.message}", e
            }
        }

        return saves
    }

    private Boolean updateSingleServer(ComputeServer currentServer, Map masterItem, List<ComputeServer> hosts,
                                       Boolean consoleEnabled, ComputeServerType defaultServerType, Collection availablePlans, ServicePlan fallbackPlan) {
        try {
            Boolean save = false

            save |= updateBasicServerProperties(currentServer, masterItem, defaultServerType)
            save |= updateNetworkProperties(currentServer, masterItem)
            save |= updateResourceProperties(currentServer, masterItem)
            save |= updateParentServer(currentServer, masterItem, hosts)
            save |= updateConsoleProperties(currentServer, consoleEnabled)
            save |= updateOperatingSystem(currentServer, masterItem)
            save |= updatePowerState(currentServer, masterItem)
            save |= updateServicePlan(currentServer, availablePlans, fallbackPlan)

            updateVolumes(currentServer, masterItem)

            return save
        } catch (ex) {
            log.error("Error Updating Virtual Machine ${currentServer?.name} - ${currentServer.externalId} - ${ex}", ex)
            return false
        }
    }

    private Boolean updateBasicServerProperties(ComputeServer currentServer, Map masterItem, ComputeServerType defaultServerType) {
        Boolean save = false

        if (currentServer.name != masterItem.Name) {
            currentServer.name = masterItem.Name
            save = true
        }
        if (currentServer.internalId != masterItem.VMId) {
            currentServer.internalId = masterItem.VMId
            save = true
        }
        if (currentServer.computeServerType == null) {
            currentServer.computeServerType = defaultServerType
            save = true
        }

        return save
    }

    private Boolean updateNetworkProperties(ComputeServer currentServer, Map masterItem) {
        Boolean save = false

        if (masterItem.IpAddress && currentServer.externalIp != masterItem.IpAddress) {
            def netInterface = currentServer.interfaces.find { iface -> iface.publicIpAddress == currentServer.externalIp}
            if (netInterface) {
                netInterface.publicIpAddress = masterItem.IpAddress
                context.async.computeServer.computeServerInterface.save([netInterface]).blockingGet()
            }
            if (currentServer.externalIp == currentServer.sshHost) {
                currentServer.sshHost = masterItem.IpAddress
            }
            currentServer.externalIp = masterItem.IpAddress
            save = true
        }

        if (masterItem.InternalIp && currentServer.internalIp != masterItem.InternalIp) {
            def netInterface = currentServer.interfaces.find { iface -> iface.ipAddress == currentServer.internalIp}
            if (netInterface) {
                netInterface.ipAddress = masterItem.InternalIp
                context.async.computeServer.computeServerInterface.save([netInterface]).blockingGet()
            }
            if (currentServer.internalIp == currentServer.sshHost) {
                currentServer.sshHost = masterItem.InternalIp
            }
            currentServer.internalIp = masterItem.InternalIp
            save = true
        }

        return save
    }

    private Boolean updateResourceProperties(ComputeServer currentServer, Map masterItem) {
        Boolean save = false

        def maxCores = masterItem.CPUCount.toLong() ?: 1
        if (currentServer.maxCores != maxCores) {
            currentServer.maxCores = maxCores
            save = true
        }
        if (currentServer.capacityInfo && currentServer.capacityInfo.maxCores != maxCores) {
            currentServer.capacityInfo.maxCores = maxCores
            save = true
        }

        def maxMemory = (masterItem.Memory?.toLong() ?: 0) * BYTES_PER_MB
        if (currentServer.maxMemory != maxMemory) {
            currentServer.maxMemory = maxMemory
            save = true
        }

        return save
    }

    private Boolean updateParentServer(ComputeServer currentServer, Map masterItem, List<ComputeServer> hosts) {
        def parentServer = hosts?.find { host -> host.externalId == masterItem.HostId }
        if (parentServer != null && currentServer.parentServer != parentServer) {
            currentServer.parentServer = parentServer
            true
        } else {
            false
        }
    }

    private Boolean updateConsoleProperties(ComputeServer currentServer, Boolean consoleEnabled) {
        Boolean save = false

        def consoleConfig = buildConsoleConfiguration(consoleEnabled, currentServer)

        save |= updateConsoleType(currentServer, consoleConfig.type)
        save |= updateConsoleHost(currentServer, consoleConfig.host)
        save |= updateConsolePort(currentServer, consoleConfig.port)

        if (consoleEnabled) {
            save |= updateConsoleCredentials(currentServer, consoleConfig.username, consoleConfig.password)
        }

        return save
    }

    private Map buildConsoleConfiguration(boolean consoleEnabled, ComputeServer currentServer) {
        if (consoleEnabled) {
            def username = extractConsoleUsername()
            def password = cloud.accountCredentialData?.password ?: cloud.getConfigProperty(PASSWORD)

            [
                    type: VMRDP,
                    port: DEFAULT_CONSOLE_PORT,
                    host: currentServer.parentServer?.name,
                    username: username,
                    password: password,
            ]
        } else {
            [type: null, port: null, host: null, username: null, password: null,]
        }
    }

    private String extractConsoleUsername() {
        def username = cloud.accountCredentialData?.username ?: cloud.getConfigProperty(USERNAME) ?: DUNNO
        username.contains(BACKSLASH) ? username.tokenize(BACKSLASH)[1] : username
    }

    private Boolean updateConsoleType(ComputeServer server, String consoleType) {
        if (server.consoleType != consoleType) {
            server.consoleType = consoleType
            true
        } else {
            false
        }
    }

    private Boolean updateConsoleHost(ComputeServer server, String consoleHost) {
        if (server.consoleHost != consoleHost) {
            server.consoleHost = consoleHost
            true
        } else {
            false
        }
    }

    private Boolean updateConsolePort(ComputeServer server, Integer consolePort) {
        if (server.consolePort != consolePort) {
            server.consolePort = consolePort
            true
        } else {
            false
        }
    }

    private Boolean updateConsoleCredentials(ComputeServer server, String username, String password) {
        Boolean save = false

        if (username != server.sshUsername) {
            server.sshUsername = username
            save = true
        }

        if (password != server.consolePassword) {
            server.consolePassword = password
            save = true
        }

        save
    }

    private Boolean updateOperatingSystem(ComputeServer currentServer, Map masterItem) {
        def osTypeCode = apiService.getMapScvmmOsType(masterItem.OperatingSystem, true, masterItem.OperatingSystemWindows?.toString() == TRUE_STRING ? 'windows' : null)
        def osTypeCodeStr = osTypeCode ?: OTHER
        def osType = context.services.osType.find(new DataQuery().withFilter(CODE, osTypeCodeStr))

        if (osType && currentServer.serverOs != osType) {
            currentServer.serverOs = osType
            currentServer.osType = currentServer.serverOs?.platform?.toString()?.toLowerCase()
            currentServer.platform = osType?.platform
            true
        } else {
            false
        }
    }

    private Boolean updatePowerState(ComputeServer currentServer, Map masterItem) {
        def powerState = masterItem.VirtualMachineState == RUNNING ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
        if(powerState != currentServer.powerState) {
            currentServer.powerState = powerState
            if(currentServer.computeServerType?.guestVm) {
                if(currentServer.powerState == ComputeServer.PowerState.on) {
                    updateWorkloadAndInstanceStatuses(currentServer, Workload.Status.running, 'running')
                } else {
                    def containerStatus = currentServer.powerState == ComputeServer.PowerState.paused ? Workload.Status.suspended : Workload.Status.stopped
                    def instanceStatus = currentServer.powerState == ComputeServer.PowerState.paused ? 'suspended' : 'stopped'
                    updateWorkloadAndInstanceStatuses(currentServer, containerStatus, instanceStatus, ['stopping', 'starting'])
                }
            }
            true
        } else {
            false
        }
    }

    private Boolean updateServicePlan(ComputeServer currentServer, Collection availablePlans, ServicePlan fallbackPlan) {
        ServicePlan plan = SyncUtils.findServicePlanBySizing(availablePlans, currentServer.maxMemory, currentServer.maxCores,
                null, fallbackPlan, currentServer.plan, currentServer.account, [])
        if (currentServer.plan?.id != plan?.id) {
            currentServer.plan = plan
            true
        } else {
            false
        }
    }

    private void updateVolumes(ComputeServer currentServer, Map masterItem) {
        if (masterItem.Disks) {
            if (currentServer.status != RESIZING && currentServer.status != PROVISIONING) {
                syncVolumes(currentServer, masterItem.Disks)
            }
        }
    }

    private void updateWorkloadAndInstanceStatuses(ComputeServer server, Workload.Status workloadStatus,
                                                   String instanceStatus, List<String> additionalExcludedStatuses = []) {
        // Update workloads
        context.services.workload.list(new DataQuery().withFilter(SERVER_ID, server.id)
                .withFilter(STATUS, NOT_EQUALS, Workload.Status.deploying))?.each { workload ->
            workload.status = workloadStatus
            if (workloadStatus == Workload.Status.running) {
                workload.userStatus = workloadStatus.toString()
            }
            context.services.workload.save(workload)
        }

        // Build excluded statuses list
        def excludedStatuses = [
                'pendingReconfigureApproval',
                'pendingDeleteApproval',
                'removing',
                'restarting',
                'finishing',
                RESIZING,
        ]
        if (additionalExcludedStatuses) {
            excludedStatuses.addAll(additionalExcludedStatuses)
        }

        // Update instances
        def instanceIds = context.services.workload.list(new DataQuery()
                .withFilters(
                        new DataFilter(STATUS, NOT_EQUALS, Workload.Status.failed),
                        new DataFilter(STATUS, NOT_EQUALS, Workload.Status.deploying),
                        new DataFilter('instance.status', 'notIn', excludedStatuses),
                        new DataFilter(SERVER_ID, server.id)
                ))?.collect { workload -> workload.instance.id }?.unique()

        if(instanceIds) {
            context.services.instance.list(new DataQuery().withFilter(ID, IN, instanceIds))?.each { instance ->
                instance.status = instanceStatus
                context.services.instance.save(instance)
            }
        }
    }

    def removeMissingVirtualMachines(List<ComputeServerIdentityProjection> removeList) {
        log.debug("removeMissingVirtualMachines: ${cloud} ${removeList.size()}")
        def removeItems = context.services.computeServer.listIdentityProjections(
                new DataQuery().withFilter(ID, IN, removeList*.id)
                        .withFilter(COMPUTE_SERVER_TYPE_CODE, SCVMM_UNMANAGED)
        )
        context.async.computeServer.remove(removeItems).blockingGet()
    }

    private buildVmConfig(Map cloudItem, ComputeServerType defaultServerType) {
        def vmConfig = [
                name             : cloudItem.Name,
                cloud            : cloud,
                status           : 'provisioned',
                apiKey           : UUID.randomUUID(),
                account          : cloud.account,
                managed          : false,
                uniqueId         : cloudItem.ID,
                provision        : false,
                hotResize        : false,
                serverType       : 'vm',
                lvmEnabled       : false,
                discovered       : true,
                internalId       : cloudItem.VMId,
                externalId       : cloudItem.ID,
                displayName      : cloudItem.Name,
                singleTenant     : true,
                computeServerType: defaultServerType,
                powerState       : cloudItem.VirtualMachineState == RUNNING ? ComputeServer.PowerState.on : ComputeServer.PowerState.off,
        ]
        vmConfig
    }

    def syncVolumes(ComputeServer server, List externalVolumes) {
        log.debug "syncVolumes: ${server}, ${externalVolumes}"
        def changes = false
        try {
            def maxStorage = 0

            def existingVolumes = server.volumes
            def masterItems = externalVolumes?.findAll{ item -> item != null}

            def existingItems = Observable.fromIterable(existingVolumes)
            def diskNumber = masterItems.size()

            SyncTask<StorageVolumeIdentityProjection, Map, StorageVolume> syncTask = new SyncTask<>(existingItems, masterItems as Collection<Map>)

            syncTask.addMatchFunction { StorageVolumeIdentityProjection storageVolume, Map masterItem ->
                storageVolume.externalId == masterItem.ID
            }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<StorageVolumeIdentityProjection, StorageVolume>> updateItems ->
                context.async.storageVolume.listById(updateItems.collect { updateItem -> updateItem.existingItem.id } as List<Long>)
            }.onAdd { itemsToAdd ->
                addMissingStorageVolumes(itemsToAdd, server, diskNumber, maxStorage)
            }.onUpdate { List<SyncTask.UpdateItem<StorageVolume, Map>> updateItems ->
                updateMatchedStorageVolumes(updateItems, server, maxStorage, changes)
            }.onDelete { removeItems ->
                removeMissingStorageVolumes(removeItems, server, changes)
            }.start()

            if (server.maxStorage != maxStorage) {
                log.debug "max storage changed for ${server} from ${server.maxStorage} to ${maxStorage}"
                server.maxStorage = maxStorage
                context.async.computeServer.save(server).blockingGet()
                changes = true
            }
        } catch (e) {
            log.error("syncVolumes error: ${e}", e)
        }
        changes
    }

    def addMissingStorageVolumes(List itemsToAdd, ComputeServer server, int diskNumber, Long maxStorage) {
        def serverVolumeNames = server.volumes.collect{ volume -> volume.name }
        def currentDiskNumber = diskNumber

        itemsToAdd?.eachWithIndex { diskData, index ->
            log.debug("adding new volume: ${diskData}")

            def volumeConfig = createVolumeConfig(diskData, server, serverVolumeNames, index, currentDiskNumber)
            def storageVolume = createAndPersistStorageVolume(server, volumeConfig)

            maxStorage += storageVolume.maxStorage ?: ZERO_LONG
            currentDiskNumber++

            log.debug("added volume: ${storageVolume?.dump()}")
        }

        context.async.computeServer.bulkSave([server]).blockingGet()
    }

    private Map createVolumeConfig(Map diskData, ComputeServer server, List serverVolumeNames, int index, int diskNumber) {
        def datastore = resolveDatastore(diskData)
        def deviceName = resolveDeviceName(diskData, diskNumber)
        def volumeName = resolveVolumeName(serverVolumeNames, diskData, server, index)

        def volumeConfig = [
                name      : volumeName,
                size      : diskData.TotalSize?.toLong() ?: 0,
                rootVolume: isRootVolume(diskData, server),
                deviceName: deviceName,
                externalId: diskData.ID,
                internalId: diskData.Name,
                storageType: getStorageVolumeType("scvmm-${diskData?.VHDType}-${diskData?.VHDFormat}".toLowerCase()),
        ]

        if (datastore) {
            volumeConfig.datastoreId = datastore.id.toString()
        }

        volumeConfig
    }

    private def resolveDatastore(Map diskData) {
        return diskData.datastore ?:
                loadDatastoreForVolume(diskData.HostVolumeId, diskData.FileShareId, diskData.PartitionUniqueId)
    }

    private String resolveDeviceName(Map diskData, int diskNumber) {
        return diskData.deviceName ?: apiService.getDiskName(diskNumber)
    }

    private String resolveVolumeName(List serverVolumeNames, Map diskData, ComputeServer server, int index) {
        return serverVolumeNames?.getAt(index) ?: getVolumeName(diskData, server, index)
    }

    private boolean isRootVolume(Map diskData, ComputeServer server) {
        return diskData.VolumeType == BOOT_AND_SYSTEM || !server.volumes?.size()
    }

    private StorageVolume createAndPersistStorageVolume(ComputeServer server, Map volumeConfig) {
        def storageVolume = buildStorageVolume(server.account ?: cloud.account, server, volumeConfig)
        context.services.storageVolume.create(storageVolume)
        server.volumes.add(storageVolume)
        storageVolume
    }

    def updateMatchedStorageVolumes(List updateItems, ComputeServer server, Long maxStorage, Boolean changes) {
        def savedVolumes = []

        updateItems?.eachWithIndex { updateMap, index ->
            log.debug("updating volume: ${updateMap.masterItem}")

            def updateResult = processVolumeUpdate(updateMap, server, index)

            if (updateResult.shouldSave) {
                savedVolumes << updateResult.volume
                changes = true
            }

            maxStorage += updateResult.diskSize
        }

        if (savedVolumes.size() > 0) {
            context.async.storageVolume.bulkSave(savedVolumes).blockingGet()
        }
    }

    private Map processVolumeUpdate(Map updateMap, ComputeServer server, int index) {
        StorageVolume volume = updateMap.existingItem
        def masterItem = updateMap.masterItem
        def masterDiskSize = masterItem?.TotalSize?.toLong() ?: 0

        boolean shouldSave = false

        shouldSave |= updateVolumeSize(volume, masterDiskSize)
        shouldSave |= updateVolumeInternalId(volume, masterItem)
        shouldSave |= updateVolumeRootFlag(volume, masterItem, server)
        shouldSave |= updateVolumeName(volume, masterItem, server, index)

        [
                volume: volume,
                shouldSave: shouldSave,
                diskSize: masterDiskSize,
        ]
    }

    private boolean updateVolumeSize(StorageVolume volume, long masterDiskSize) {
        if (!masterDiskSize || volume.maxStorage == masterDiskSize) {
            return false
        }

        def sizeRange = [
                min: (volume.maxStorage - ComputeUtility.ONE_GIGABYTE),
                max: (volume.maxStorage + ComputeUtility.ONE_GIGABYTE),
        ]

        if (masterDiskSize <= sizeRange.min || masterDiskSize >= sizeRange.max) {
            volume.maxStorage = masterDiskSize
            true
        } else {
            false
        }
    }

    private boolean updateVolumeInternalId(StorageVolume volume, Map masterItem) {
        if (volume.internalId != masterItem.Name) {
            volume.internalId = masterItem.Name
            true
        } else {
            false
        }
    }

    private boolean updateVolumeRootFlag(StorageVolume volume, Map masterItem, ComputeServer server) {
        def isRootVolume = (masterItem?.VolumeType == BOOT_AND_SYSTEM) || (server.volumes.size() == 1)
        if (volume.rootVolume != isRootVolume) {
            volume.rootVolume = isRootVolume
            true
        } else {
            false
        }
    }

    private boolean updateVolumeName(StorageVolume volume, Map masterItem, ComputeServer server, int index) {
        if (volume.name == null) {
            volume.name = getVolumeName(masterItem, server, index)
            true
        } else {
            false
        }
    }


    def removeMissingStorageVolumes(List removeItems, ComputeServer server, Boolean changes) {
        removeItems?.each { currentVolume ->
            log.debug "removing volume: ${currentVolume}"
            changes = true
            currentVolume.controller = null
            currentVolume.datastore = null
            server.volumes.remove(currentVolume)
            context.async.computeServer.save(server).blockingGet()
            context.async.storageVolume.remove(currentVolume).blockingGet()
        }
    }

    def buildStorageVolume(def account, ComputeServer server, Map volume) {
        def storageVolume = new StorageVolume()
        storageVolume.name = volume.name
        storageVolume.account = account

        configureStorageVolumeBasics(storageVolume, volume)
        configureDatastore(storageVolume, volume)
        configureIdentifiers(storageVolume, volume)
        configureCloudId(storageVolume, server)
        configureVolumeProperties(storageVolume, volume, server)

        storageVolume
    }

    private void configureStorageVolumeBasics(StorageVolume storageVolume, Map volume) {
        storageVolume.maxStorage = volume?.maxStorage?.toLong() ?: volume?.size?.toLong()
        storageVolume.type = resolveStorageType(volume)
        storageVolume.rootVolume = volume.rootVolume == true
    }

    private resolveStorageType(Map volume) {
        if (volume?.storageType) {
            context.async.storageVolume.storageVolumeType.get(volume.storageType?.toLong()).blockingGet()
        } else {
            context.async.storageVolume.storageVolumeType.find(new DataQuery().withFilter(CODE, STANDARD)).blockingGet()
        }
    }

    private void configureDatastore(StorageVolume storageVolume, Map volume) {
        if (!volume.datastoreId) {
            return
        }

        storageVolume.datastoreOption = volume.datastoreId
        storageVolume.datastore = context.services.cloud.datastore.get(storageVolume.datastoreOption.toLong())

        if (storageVolume.datastore) {
            storageVolume.storageServer = storageVolume.datastore.storageServer
        }

        storageVolume.refType = 'Datastore'
        storageVolume.refId = volume.datastoreId?.toLong()
    }

    private void configureIdentifiers(StorageVolume storageVolume, Map volume) {
        if (volume.externalId) {
            storageVolume.externalId = volume.externalId
        }
        if (volume.internalId) {
            storageVolume.internalId = volume.internalId
        }
    }

    private void configureCloudId(StorageVolume storageVolume, ComputeServer server) {
        storageVolume.cloudId = determineCloudId(server)
    }

    private Long determineCloudId(ComputeServer server) {
        if (server.hasProperty('cloud') && server.cloud) {
            server.cloud.id
        } else if (server.hasProperty(REF_TYPE) && server.refType == COMPUTE_ZONE) {
            server.refId?.toLong()
        } else {
            null
        }
    }

    private void configureVolumeProperties(StorageVolume storageVolume, Map volume, ComputeServer server) {
        storageVolume.deviceName = volume.deviceName
        storageVolume.removable = storageVolume.rootVolume != true
        storageVolume.displayOrder = volume.displayOrder ?: server?.volumes?.size() ?: 0
    }

    def loadDatastoreForVolume(String hostVolumeId = null, String fileShareId = null, String partitionUniqueId = null) {
        log.debug "loadDatastoreForVolume: ${hostVolumeId}, ${fileShareId}"
        if (hostVolumeId) {
            StorageVolume storageVolume = context.services.storageVolume.find(new DataQuery().withFilter('internalId', hostVolumeId)
                    .withFilter(DATASTORE_REF_TYPE, COMPUTE_ZONE).withFilter(DATASTORE_REF_ID, cloud.id))
            def ds = storageVolume?.datastore
            if (!ds && partitionUniqueId) {
                storageVolume = context.services.storageVolume.find(new DataQuery().withFilter(EXTERNAL_ID, partitionUniqueId)
                        .withFilter(DATASTORE_REF_TYPE, COMPUTE_ZONE).withFilter(DATASTORE_REF_ID, cloud.id))
                ds = storageVolume?.datastore
            }
            return ds
        } else if (fileShareId) {
            Datastore datastore = context.services.cloud.datastore.find(new DataQuery()
                    .withFilter(EXTERNAL_ID, fileShareId)
                    .withFilter(REF_TYPE, COMPUTE_ZONE)
                    .withFilter('refId', cloud.id))
            return datastore
        }
        null
    }

    def getStorageVolumeType(String storageVolumeTypeCode) {
        log.debug("getStorageVolumeTypeId - Looking up volumeTypeCode ${storageVolumeTypeCode}")
        def storageVolumeType = context.async.storageVolume.storageVolumeType.find(new DataQuery().withFilter(CODE, storageVolumeTypeCode ?: STANDARD)).blockingGet()
        storageVolumeType.id
    }

    def getVolumeName(Map diskData, ComputeServer server, int index) {
        // Check if root volume
        boolean isRootVolume = diskData.VolumeType == BOOT_AND_SYSTEM || !server.volumes?.size()
        isRootVolume ? 'root' : "data-${index}"
    }
}