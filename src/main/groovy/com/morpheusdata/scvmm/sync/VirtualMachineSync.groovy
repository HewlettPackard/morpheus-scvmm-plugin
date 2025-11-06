package com.morpheusdata.scvmm.sync

import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.core.util.SyncUtils
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.StorageVolumeIdentityProjection
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

/**
 * @author rahul.ray
 */

class VirtualMachineSync {

    ComputeServer node
    private Cloud cloud
    private MorpheusContext context
    private ScvmmApiService apiService
    private CloudProvider cloudProvider
    private LogInterface log = LogWrapper.instance

    VirtualMachineSync(ComputeServer node, Cloud cloud, MorpheusContext context, CloudProvider cloudProvider) {
        this.node = node
        this.cloud = cloud
        this.context = context
        this.apiService = new ScvmmApiService(context)
        this.@cloudProvider = cloudProvider
    }

    def execute(createNew) {
        log.debug "VirtualMachineSync"

        try {
            def executionContext = initializeExecutionContext()
            def listResults = apiService.listVirtualMachines(executionContext.scvmmOpts)
            log.debug("VM List acquired in ${new Date().time - executionContext.startTime.time}ms")

            if (listResults.success == true) {
                performVirtualMachineSync(listResults, executionContext, createNew)
            }
        } catch (ex) {
            log.error("cacheVirtualMachines error: ${ex}", ex)
        }
    }

    private Map initializeExecutionContext() {
        def now = new Date()
        def consoleEnabled = cloud.getConfigProperty('enableVnc') ? true : false
        def scvmmOpts = apiService.getScvmmZoneAndHypervisorOpts(context, cloud, node)

        return [
            startTime: now,
            consoleEnabled: consoleEnabled,
            scvmmOpts: scvmmOpts
        ]
    }

    private void performVirtualMachineSync(listResults, executionContext, createNew) {
        def syncData = prepareSyncData()
        def existingVms = getExistingVirtualMachines()

        executeSyncTask(existingVms, listResults, syncData, executionContext, createNew)
    }

    private Map prepareSyncData() {
        def hosts = context.services.computeServer.list(new DataQuery()
                .withFilter('zone.id', cloud.id)
                .withFilter('computeServerType.code', 'scvmmHypervisor'))

        Collection<ServicePlan> availablePlans = context.services.servicePlan.list(new DataQuery().withFilters(
                new DataFilter('active', true),
                new DataFilter('deleted', '!=', true),
                new DataFilter('provisionType.code', 'scvmm')
        ))

        ServicePlan fallbackPlan = context.services.servicePlan.find(new DataQuery().withFilter('code', 'internal-custom-scvmm'))

        Collection<ResourcePermission> availablePlanPermissions = []
        if (availablePlans) {
            availablePlanPermissions = context.services.resourcePermission.list(new DataQuery().withFilters(
                    new DataFilter('morpheusResourceType', 'ServicePlan'),
                    new DataFilter('morpheusResourceId', 'in', availablePlans.collect { pl -> pl.id })
            ))
        }

        def serverType = context.async.cloud.findComputeServerTypeByCode("scvmmUnmanaged").blockingGet()

        return [
            hosts: hosts,
            availablePlans: availablePlans,
            fallbackPlan: fallbackPlan,
            availablePlanPermissions: availablePlanPermissions,
            serverType: serverType
        ]
    }

    private def getExistingVirtualMachines() {
        return context.async.computeServer.listIdentityProjections(new DataQuery()
                .withFilter('zone.id', cloud.id)
                .withFilter("computeServerType.code", "!=", 'scvmmHypervisor')
                .withFilter("computeServerType.code", "!=", 'scvmmController'))
    }

    private void executeSyncTask(existingVms, listResults, syncData, executionContext, createNew) {
        SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(existingVms, listResults.virtualMachines as Collection<Map>)
        syncTask.addMatchFunction { ComputeServerIdentityProjection morpheusItem, Map cloudItem ->
            morpheusItem.externalId == cloudItem.ID
        }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
            Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
            context.async.computeServer.listById(updateItems?.collect { it.existingItem.id }).map { ComputeServer server ->
                SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map> matchItem = updateItemMap[server.id]
                return new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: matchItem.masterItem)
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
                if (!savedServer) {
                    log.error "error adding new virtual machine: ${newServer}"
                } else {
                    syncVolumes(savedServer, cloudItem.Disks)
                }
            }
        } catch (ex) {
            log.error("Error in adding VM: ${ex.message}", ex)
        }
    }

    private ComputeServer createNewVirtualMachine(cloudItem, Collection<ServicePlan> availablePlans, ServicePlan fallbackPlan,
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

    private void configureServerResources(ComputeServer server, cloudItem) {
        server.maxStorage = (cloudItem.TotalSize?.toDouble() ?: 0)
        server.usedStorage = (cloudItem.UsedSize?.toDouble() ?: 0)
        server.maxMemory = (cloudItem.Memory?.toLong() ?: 0) * 1024l * 1024l
        server.maxCores = cloudItem.CPUCount.toLong() ?: 1
    }

    private void configureServerNetwork(ComputeServer server, cloudItem) {
        if (cloudItem.IpAddress) {
            server.externalIp = cloudItem.IpAddress
        }
        if (cloudItem.InternalIp) {
            server.internalIp = cloudItem.InternalIp
        }
        server.sshHost = server.internalIp ?: server.externalIp
    }

    private void configureServerParent(ComputeServer server, cloudItem, List hosts) {
        server.parentServer = hosts?.find { host -> host.externalId == cloudItem.HostId }
    }

    private void configureServerPlan(ComputeServer server, Collection<ServicePlan> availablePlans, ServicePlan fallbackPlan, Collection<ResourcePermission> availablePlanPermissions) {
        server.plan = SyncUtils.findServicePlanBySizing(availablePlans, server.maxMemory, server.maxCores, null, fallbackPlan, null, cloud.account, availablePlanPermissions)
    }

    private void configureServerOperatingSystem(ComputeServer server, cloudItem) {
        def osTypeCode = apiService.getMapScvmmOsType(cloudItem.OperatingSystem, true, "Other Linux (64 bit)")
        def osTypeCodeStr = osTypeCode ?: 'other'
        def osType = context.services.osType.find(new DataQuery().withFilter('code', osTypeCodeStr))
        if (osType) {
            server.serverOs = osType
            server.osType = server.serverOs?.platform?.toString()?.toLowerCase()
            server.platform = osType?.platform
        }
    }

    private void configureServerConsole(ComputeServer server, Boolean consoleEnabled) {
        if (consoleEnabled) {
            server.consoleType = 'vmrdp'
            server.consoleHost = server.parentServer?.name
            server.consolePort = 2179
            server.sshUsername = cloud.accountCredentialData?.username ?: cloud.getConfigProperty('username') ?: 'dunno'
            if (server.sshUsername.contains('\\')) {
                server.sshUsername = server.sshUsername.tokenize('\\')[1]
            }
            server.consolePassword = cloud.accountCredentialData?.password ?: cloud.getConfigProperty('password')
        }
    }

    private void configureServerCapacity(ComputeServer server) {
        server.capacityInfo = new ComputeCapacityInfo(maxCores: server.maxCores, maxMemory: server.maxMemory, maxStorage: server.maxStorage)
    }

    protected updateMatchedVirtualMachines(List<SyncTask.UpdateItem<ComputeServer, Map>> updateList, availablePlans, fallbackPlan,
                                           List<ComputeServer> hosts, consoleEnabled, ComputeServerType defaultServerType) {
        log.debug("VirtualMachineSync >> updateMatchedVirtualMachines() called")
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
        return context.services.computeServer.list(new DataQuery().withFilter('id', 'in', updateList.collect { up -> up.existingItem.id })
                .withJoins(['account', 'zone', 'computeServerType', 'plan', 'chassis', 'serverOs', 'sourceImage', 'folder', 'createdBy', 'userGroup',
                            'networkDomain', 'interfaces', 'interfaces.addresses', 'controllers', 'snapshots', 'metadata', 'volumes',
                            'volumes.datastore', 'resourcePool', 'parentServer', 'capacityInfo'])).collectEntries { [(it.id): it] }
    }

    private List<ComputeServer> processServerUpdates(List<SyncTask.UpdateItem<ComputeServer, Map>> updateList, Map matchedServers,
                                                   availablePlans, fallbackPlan, List<ComputeServer> hosts, consoleEnabled, ComputeServerType defaultServerType) {
        List<ComputeServer> saves = []

        for (updateMap in updateList) {
            ComputeServer currentServer = matchedServers[updateMap.existingItem.id]
            def masterItem = updateMap.masterItem

            try {
                log.debug("Checking state of matched SCVMM Server ${masterItem.ID} - ${currentServer}")
                if (currentServer.status != 'provisioning') {
                    Boolean save = updateSingleServer(currentServer, masterItem, hosts, consoleEnabled, defaultServerType, availablePlans, fallbackPlan)

                    log.debug ("updateMatchedVirtualMachines: save: ${save}")
                    if (save) {
                        saves << currentServer
                    }
                }
            } catch (Exception e) {
                log.error "Error in updating server ${currentServer?.name}: ${e.message}", e
            }
        }

        return saves
    }

    private Boolean updateSingleServer(ComputeServer currentServer, masterItem, List<ComputeServer> hosts,
                                     consoleEnabled, ComputeServerType defaultServerType, availablePlans, fallbackPlan) {
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

    private Boolean updateBasicServerProperties(ComputeServer currentServer, masterItem, ComputeServerType defaultServerType) {
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

    private Boolean updateNetworkProperties(ComputeServer currentServer, masterItem) {
        Boolean save = false

        if (masterItem.IpAddress && currentServer.externalIp != masterItem.IpAddress) {
            def netInterface = currentServer.interfaces.find {it.publicIpAddress == currentServer.externalIp}
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
            def netInterface = currentServer.interfaces.find {it.ipAddress == currentServer.internalIp}
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

    private Boolean updateResourceProperties(ComputeServer currentServer, masterItem) {
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

        def maxMemory = (masterItem.Memory?.toLong() ?: 0) * 1024l * 1024l
        if (currentServer.maxMemory != maxMemory) {
            currentServer.maxMemory = maxMemory
            save = true
        }

        return save
    }

    private Boolean updateParentServer(ComputeServer currentServer, masterItem, List<ComputeServer> hosts) {
        def parentServer = hosts?.find { host -> host.externalId == masterItem.HostId }
        if (parentServer != null && currentServer.parentServer != parentServer) {
            currentServer.parentServer = parentServer
            return true
        }
        return false
    }

    private Boolean updateConsoleProperties(ComputeServer currentServer, consoleEnabled) {
        Boolean save = false

        def consoleType = consoleEnabled ? 'vmrdp' : null
        def consolePort = consoleEnabled ? 2179 : null
        def consoleHost = consoleEnabled ? currentServer.parentServer?.name : null
        def consoleUsername = cloud.accountCredentialData?.username ?: cloud.getConfigProperty('username') ?: 'dunno'
        if (consoleUsername.contains('\\')) {
            consoleUsername = consoleUsername.tokenize('\\')[1]
        }
        def consolePassword = cloud.accountCredentialData?.password ?: cloud.getConfigProperty('password')

        if (currentServer.consoleType != consoleType) {
            currentServer.consoleType = consoleType
            save = true
        }
        if (currentServer.consoleHost != consoleHost) {
            currentServer.consoleHost = consoleHost
        }
        if (currentServer.consolePort != consolePort) {
            currentServer.consolePort = consolePort
            save = true
        }
        if (consoleEnabled) {
            if (consoleUsername != currentServer.sshUsername) {
                currentServer.sshUsername = consoleUsername
                save = true
            }
            if (consolePassword != currentServer.consolePassword) {
                currentServer.consolePassword = consolePassword
                save = true
            }
        }

        return save
    }

    private Boolean updateOperatingSystem(ComputeServer currentServer, masterItem) {
        def osTypeCode = apiService.getMapScvmmOsType(masterItem.OperatingSystem, true, masterItem.OperatingSystemWindows?.toString() == 'true' ? 'windows' : null)
        def osTypeCodeStr = osTypeCode ?: 'other'
        def osType = context.services.osType.find(new DataQuery().withFilter('code', osTypeCodeStr))

        if (osType && currentServer.serverOs != osType) {
            currentServer.serverOs = osType
            currentServer.osType = currentServer.serverOs?.platform?.toString()?.toLowerCase()
            currentServer.platform = osType?.platform
            return true
        }
        return false
    }

    private Boolean updatePowerState(ComputeServer currentServer, masterItem) {
        def powerState = masterItem.VirtualMachineState == 'Running' ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
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
            return true
        }
        return false
    }

    private Boolean updateServicePlan(ComputeServer currentServer, availablePlans, fallbackPlan) {
        ServicePlan plan = SyncUtils.findServicePlanBySizing(availablePlans, currentServer.maxMemory, currentServer.maxCores,
                null, fallbackPlan, currentServer.plan, currentServer.account, [])
        if (currentServer.plan?.id != plan?.id) {
            currentServer.plan = plan
            return true
        }
        return false
    }

    private void updateVolumes(ComputeServer currentServer, masterItem) {
        if (masterItem.Disks) {
            if (currentServer.status != 'resizing' && currentServer.status != 'provisioning') {
                syncVolumes(currentServer, masterItem.Disks)
            }
        }
    }

    private void updateWorkloadAndInstanceStatuses(ComputeServer server, Workload.Status workloadStatus,
                                                   String instanceStatus, List<String> additionalExcludedStatuses = []) {
        // Update workloads
        context.services.workload.list(new DataQuery().withFilter('server.id', server.id)
                .withFilter('status', '!=', Workload.Status.deploying))?.each { workload ->
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
                'resizing'
        ]
        if (additionalExcludedStatuses) {
            excludedStatuses.addAll(additionalExcludedStatuses)
        }

        // Update instances
        def instanceIds = context.services.workload.list(new DataQuery()
                .withFilters(
                        new DataFilter('status', '!=', Workload.Status.failed),
                        new DataFilter('status', '!=', Workload.Status.deploying),
                        new DataFilter('instance.status', 'notIn', excludedStatuses),
                        new DataFilter('server.id', server.id)
                ))?.collect { it.instance.id }?.unique()

        if(instanceIds) {
            context.services.instance.list(new DataQuery().withFilter('id', 'in', instanceIds))?.each { instance ->
                instance.status = instanceStatus
                context.services.instance.save(instance)
            }
        }
    }

    def removeMissingVirtualMachines(List<ComputeServerIdentityProjection> removeList) {
        log.debug("removeMissingVirtualMachines: ${cloud} ${removeList.size()}")
        def removeItems = context.services.computeServer.listIdentityProjections(
                new DataQuery().withFilter("id", "in", removeList.collect { it.id })
                        .withFilter("computeServerType.code", 'scvmmUnmanaged')
        )
        context.async.computeServer.remove(removeItems).blockingGet()
    }

    private buildVmConfig(Map cloudItem, ComputeServerType defaultServerType) {
        def vmConfig = [
                name             : cloudItem.Name,
                cloud            : cloud,
                status           : 'provisioned',
                apiKey           : java.util.UUID.randomUUID(),
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
                powerState       : cloudItem.VirtualMachineState == 'Running' ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
        ]
        return vmConfig
    }

    def syncVolumes(server, externalVolumes) {
        log.debug "syncVolumes: ${server}, ${groovy.json.JsonOutput.prettyPrint(externalVolumes?.encodeAsJSON()?.toString())}"
        def changes = false
        try {
            def maxStorage = 0

            def existingVolumes = server.volumes
            def masterItems = externalVolumes?.findAll{it != null}

            def existingItems = Observable.fromIterable(existingVolumes)
            def diskNumber = masterItems.size()

            SyncTask<StorageVolumeIdentityProjection, Map, StorageVolume> syncTask = new SyncTask<>(existingItems, masterItems as Collection<Map>)

            syncTask.addMatchFunction { StorageVolumeIdentityProjection storageVolume, Map masterItem ->
                storageVolume.externalId == masterItem.ID
            }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<StorageVolumeIdentityProjection, StorageVolume>> updateItems ->
                context.async.storageVolume.listById(updateItems.collect { it.existingItem.id } as List<Long>)
            }.onAdd { itemsToAdd ->
                addMissingStorageVolumes(itemsToAdd, server, diskNumber, maxStorage, changes)
            }.onUpdate { List<SyncTask.UpdateItem<StorageVolume, Map>> updateItems ->
                updateMatchedStorageVolumes(updateItems, server, maxStorage, changes)
            }.onDelete { removeItems ->
                removeMissingStorageVolumes(removeItems, server, changes)
            }.start()

            if (server instanceof ComputeServer && server.maxStorage != maxStorage) {
                log.debug "max storage changed for ${server} from ${server.maxStorage} to ${maxStorage}"
                server.maxStorage = maxStorage
                context.async.computeServer.save(server).blockingGet()
                changes = true
            }
        } catch (e) {
            log.error("syncVolumes error: ${e}", e)
        }
        return changes
    }

    def addMissingStorageVolumes(itemsToAdd, ComputeServer server, int diskNumber, maxStorage, changes) {
        def provisionProvider = cloudProvider.getProvisionProvider('morpheus-scvmm-plugin.provision')
        def serverVolumeNames = server.volumes.collect{ it.name }
        itemsToAdd?.eachWithIndex { diskData, index ->
            log.debug("adding new volume: ${diskData}")
            def datastore = diskData.datastore ?: loadDatastoreForVolume(diskData.HostVolumeId, diskData.FileShareId, diskData.PartitionUniqueId) ?: null
            def deviceName = diskData.deviceName ?: apiService.getDiskName(diskNumber)
            def volumeName = serverVolumeNames?.getAt(index) ?: getVolumeName(diskData, deviceName, server, index)
            def volumeConfig = [
                    name      : volumeName,
                    size      : diskData.TotalSize?.toLong() ?: 0,
                    rootVolume: diskData.VolumeType == 'BootAndSystem' || !server.volumes?.size(),
                    //deviceName: (diskData.deviceName ?: provisionProvider.getDiskName(diskNumber)),
                    deviceName: deviceName,
                    externalId: diskData.ID,
                    internalId: diskData.Name,
                    storageType: getStorageVolumeType("scvmm-${diskData?.VHDType}-${diskData?.VHDFormat}".toLowerCase()),
            ]
            if (datastore)
                volumeConfig.datastoreId = "${datastore.id}"
            def storageVolume = buildStorageVolume(server.account ?: cloud.account, server, volumeConfig)
            context.services.storageVolume.create(storageVolume)
            server.volumes.add(storageVolume)
            maxStorage += storageVolume.maxStorage ?: 0l
            diskNumber++
            log.debug("added volume: ${storageVolume?.dump()}")
        }
        context.async.computeServer.bulkSave([server]).blockingGet()
    }

    def updateMatchedStorageVolumes(updateItems, server, maxStorage, changes) {
        def savedVolumes = []
        updateItems?.eachWithIndex { updateMap, index ->
            log.debug("updating volume: ${updateMap.masterItem}")
            StorageVolume volume = updateMap.existingItem
            def masterItem = updateMap.masterItem

            def masterDiskSize = masterItem?.TotalSize?.toLong() ?: 0
            def sizeRange = [min: (volume.maxStorage - ComputeUtility.ONE_GIGABYTE), max: (volume.maxStorage + ComputeUtility.ONE_GIGABYTE)]
            def save = false
            if (masterDiskSize && volume.maxStorage != masterDiskSize && (masterDiskSize <= sizeRange.min || masterDiskSize >= sizeRange.max)) {
                volume.maxStorage = masterDiskSize
                save = true
            }
            if (volume.internalId != masterItem.Name) {
                volume.internalId = masterItem.Name
                save = true
            }
            def isRootVolume = (masterItem?.VolumeType == 'BootAndSystem') || (server.volumes.size() == 1)
            if (volume.rootVolume != isRootVolume) {
                volume.rootVolume = isRootVolume
                save = true
            }
            if (volume.name == null) {
                volume.name = getVolumeName(masterItem, volume.deviceName, server, index)
                save = true
            }
            if (save) {
                savedVolumes << volume
                changes = true
            }
            maxStorage += masterDiskSize
        }
        if (savedVolumes.size() > 0) {
            context.async.storageVolume.bulkSave(savedVolumes).blockingGet()
        }
    }

    def removeMissingStorageVolumes(removeItems, ComputeServer server, Boolean changes) {
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

    def buildStorageVolume(account, server, volume) {
        def storageVolume = new StorageVolume()
        storageVolume.name = volume.name
        storageVolume.account = account

        storageVolume.maxStorage = volume?.maxStorage?.toLong() ?: volume?.size?.toLong()
        def storageType
        if(volume?.storageType) {
            storageType = context.async.storageVolume.storageVolumeType.get(volume.storageType?.toLong()).blockingGet()
        }
        else {
            storageType = context.async.storageVolume.storageVolumeType.find(new DataQuery().withFilter('code', 'standard')).blockingGet()
        }
        storageVolume.type = storageType

        storageVolume.rootVolume = volume.rootVolume == true
        if (volume.datastoreId) {
            storageVolume.datastoreOption = volume.datastoreId
            storageVolume.datastore = context.services.cloud.datastore.get(storageVolume.datastoreOption.toLong())
            if(storageVolume.datastore) {
                storageVolume.storageServer = storageVolume.datastore.storageServer
            }
            storageVolume.refType = 'Datastore'
            storageVolume.refId = volume.datastoreId?.toLong()
        }

        if (volume.externalId)
            storageVolume.externalId = volume.externalId
        if (volume.internalId)
            storageVolume.internalId = volume.internalId

        if (server instanceof ComputeServer) {
            storageVolume.cloudId = server.cloud?.id
        } else if (server instanceof VirtualImage && server.refType == 'ComputeZone') {
            storageVolume.cloudId = server.refId?.toLong()
        } else if (server instanceof VirtualImageLocation && server.refType == 'ComputeZone') {
            storageVolume.cloudId = server.refId?.toLong()
        }

        storageVolume.deviceName = volume.deviceName

        storageVolume.removable = storageVolume.rootVolume != true
        storageVolume.displayOrder = volume.displayOrder ?: server?.volumes?.size() ?: 0
        return storageVolume
    }

    def loadDatastoreForVolume(hostVolumeId = null, fileShareId = null, partitionUniqueId = null) {
        log.debug "loadDatastoreForVolume: ${hostVolumeId}, ${fileShareId}"
        if (hostVolumeId) {
            StorageVolume storageVolume = context.services.storageVolume.find(new DataQuery().withFilter('internalId', hostVolumeId)
                    .withFilter('datastore.refType', 'ComputeZone').withFilter('datastore.refId', cloud.id))
            def ds = storageVolume?.datastore
            if (!ds && partitionUniqueId) {
                storageVolume = context.services.storageVolume.find(new DataQuery().withFilter('externalId', partitionUniqueId)
                        .withFilter('datastore.refType', 'ComputeZone').withFilter('datastore.refId', cloud.id))
                ds = storageVolume?.datastore
            }
            return ds
        } else if (fileShareId) {
            Datastore datastore = context.services.cloud.datastore.find(new DataQuery()
                    .withFilter('externalId', fileShareId)
                    .withFilter('refType', 'ComputeZone')
                    .withFilter('refId', cloud.id))
            return datastore
        }
        null
    }

    def getStorageVolumeType(String storageVolumeTypeCode) {
        log.debug("getStorageVolumeTypeId - Looking up volumeTypeCode ${storageVolumeTypeCode}")
        def storageVolumeType = context.async.storageVolume.storageVolumeType.find(new DataQuery().withFilter('code', storageVolumeTypeCode ?: 'standard')).blockingGet()
        return storageVolumeType.id
    }

    def getVolumeName(diskData, String deviceName, ComputeServer server, int index) {
        // Check if root volume
        boolean isRootVolume = diskData.VolumeType == 'BootAndSystem' || !server.volumes?.size()
        if (isRootVolume) {
            return 'root'
        } else {
            // Extract disk letter from device name (e.g., 'sdb' -> 'b')
            /*if (deviceName && deviceName.length() > 0) {
                def diskLetter = deviceName[-1]
                if (diskLetter.matches(/[a-z]/)) {
                    // Convert letter to number (a=0, b=1, etc.) and add 1 to start from 1
                    def diskIndex = diskLetter as char
                    def diskNum = (diskIndex - ('a' as char)) + 1
                    return "data-${diskNum}"
                }
            }*/
            return "data-${index}"
        }
    }
}
