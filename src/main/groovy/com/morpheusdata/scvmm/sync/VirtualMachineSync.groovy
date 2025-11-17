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
            def now = new Date()
            def consoleEnabled = cloud.getConfigProperty('enableVnc') ? true : false
            def scvmmOpts = apiService.getScvmmZoneAndHypervisorOpts(context, cloud, node)

            def listResults = apiService.listVirtualMachines(scvmmOpts)
            log.debug("VM List acquired in ${new Date().time - now.time}ms")

            if (listResults.success == true) {
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

                def existingVms = context.async.computeServer.listIdentityProjections(new DataQuery()
                        .withFilter('zone.id', cloud.id)
                        .withFilter("computeServerType.code", "!=", 'scvmmHypervisor')
                        .withFilter("computeServerType.code", "!=", 'scvmmController'))

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
                        addMissingVirtualMachines(itemsToAdd, availablePlans, fallbackPlan, availablePlanPermissions, hosts, consoleEnabled, serverType)
                    }
                }.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
                    updateMatchedVirtualMachines(updateItems, availablePlans, fallbackPlan, hosts, consoleEnabled, serverType)
                }.onDelete { List<ComputeServerIdentityProjection> removeItems ->
                    removeMissingVirtualMachines(removeItems)
                }.observe().blockingSubscribe()
            }
        } catch (ex) {
            log.error("cacheVirtualMachines error: ${ex}", ex)
        }
    }

    def addMissingVirtualMachines(List addList, Collection<ServicePlan> availablePlans, ServicePlan fallbackPlan, Collection<ResourcePermission> availablePlanPermissions, List hosts, Boolean consoleEnabled, ComputeServerType defaultServerType) {
        try {
            for (cloudItem in addList) {
                log.debug "Adding new virtual machine: ${cloudItem.Name}"
                def vmConfig = buildVmConfig(cloudItem, defaultServerType)
                ComputeServer add = new ComputeServer(vmConfig)
                add.maxStorage = (cloudItem.TotalSize?.toDouble() ?: 0)
                add.usedStorage = (cloudItem.UsedSize?.toDouble() ?: 0)
                add.maxMemory = (cloudItem.Memory?.toLong() ?: 0) * 1024l * 1024l
                add.maxCores = cloudItem.CPUCount.toLong() ?: 1
                add.parentServer = hosts?.find { host -> host.externalId == cloudItem.HostId }
                add.plan = SyncUtils.findServicePlanBySizing(availablePlans, add.maxMemory, add.maxCores, null, fallbackPlan, null, cloud.account, availablePlanPermissions)
                if (cloudItem.IpAddress) {
                    add.externalIp = cloudItem.IpAddress
                }
                if (cloudItem.InternalIp) {
                    add.internalIp = cloudItem.InternalIp
                }
                // Operating System
                def osTypeCode = apiService.getMapScvmmOsType(cloudItem.OperatingSystem, true, "Other Linux (64 bit)")
                def osTypeCodeStr = osTypeCode ?: 'other'
                def osType = context.services.osType.find(new DataQuery().withFilter('code', osTypeCodeStr))
                if (osType) {
                    add.serverOs = osType
                    add.osType = add.serverOs?.platform?.toString()?.toLowerCase()
                    add.platform = osType?.platform
                }
                add.sshHost = add.internalIp ?: add.externalIp
                if (consoleEnabled) {
                    add.consoleType = 'vmrdp'
                    add.consoleHost = add.parentServer?.name
                    add.consolePort = 2179
                    add.sshUsername = cloud.accountCredentialData?.username ?: cloud.getConfigProperty('username') ?: 'dunno'
                    if (add.sshUsername.contains('\\')) {
                        add.sshUsername = add.sshUsername.tokenize('\\')[1]
                    }
                    add.consolePassword = cloud.accountCredentialData?.password ?: cloud.getConfigProperty('password')
                }
                add.capacityInfo = new ComputeCapacityInfo(maxCores: add.maxCores, maxMemory: add.maxMemory, maxStorage: add.maxStorage)
                ComputeServer savedServer = context.async.computeServer.create(add).blockingGet()
                if (!savedServer) {
                    log.error "error adding new virtual machine: ${add}"
                } else {
                    syncVolumes(savedServer, cloudItem.Disks)
                }
            }
        } catch (ex) {
            log.error("Error in adding VM: ${ex.message}", ex)
        }
    }

    protected updateMatchedVirtualMachines(List<SyncTask.UpdateItem<ComputeServer, Map>> updateList, availablePlans, fallbackPlan,
                                           List<ComputeServer> hosts, consoleEnabled, ComputeServerType defaultServerType) {
        log.debug("VirtualMachineSync >> updateMatchedVirtualMachines() called")
        try {

            List<ComputeServer> saves = []
            for (updateMap in updateList) {
                ComputeServer currentServer = context.services.computeServer.get(updateMap.existingItem.id)
                def masterItem = updateMap.masterItem
                try {
                    log.debug("Checking state of matched SCVMM Server ${masterItem.ID} - ${currentServer}")
                    if (currentServer.status != 'provisioning') {
                        try {
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
                            if (masterItem.IpAddress && currentServer.externalIp != masterItem.IpAddress) {
                                if (currentServer.externalIp == currentServer.sshHost) {
                                    currentServer.sshHost = masterItem.IpAddress
                                }
                                currentServer.externalIp = masterItem.IpAddress
                                save = true
                            }
                            if (masterItem.InternalIp && currentServer.internalIp != masterItem.InternalIp) {
                                if (currentServer.internalIp == currentServer.sshHost) {
                                    currentServer.sshHost = masterItem.InternalIp
                                }
                                currentServer.internalIp = masterItem.InternalIp
                                save = true
                            }

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
                            def parentServer = hosts?.find { host -> host.externalId == masterItem.HostId }
                            if (parentServer != null && currentServer.parentServer != parentServer) {
                                currentServer.parentServer = parentServer
                                save = true
                            }
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
                            // Operating System
                            def osTypeCode = apiService.getMapScvmmOsType(masterItem.OperatingSystem, true, masterItem.OperatingSystemWindows?.toString() == 'true' ? 'windows' : null)
                            def osTypeCodeStr = osTypeCode ?: 'other'
                            def osType = context.services.osType.find(new DataQuery().withFilter('code', osTypeCodeStr))
                            if (osType && currentServer.serverOs != osType) {
                                currentServer.serverOs = osType
                                currentServer.osType = currentServer.serverOs?.platform?.toString()?.toLowerCase()
                                currentServer.platform = osType?.platform
                                save = true
                            }

                            def powerState = masterItem.VirtualMachineState == 'Running' ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
                            if(powerState != currentServer.powerState) {
                                currentServer.powerState = powerState
                                if(currentServer.computeServerType?.guestVm) {
                                    if(currentServer.powerState == ComputeServer.PowerState.on) {
                                        context.services.computeServer.list(new DataQuery().withFilter('id', currentServer.id)
                                                .withFilter('status', '!=', 'provisioning'))?.each { compServer ->
                                            compServer.status = 'running'
                                            context.services.computeServer.save(compServer)
                                        }
                                        def instanceIds = context.services.computeServer.list(new DataQuery()
                                                .withFilters(
                                                        new DataFilter('status', '!=', 'failed'),
                                                        new DataFilter('status', '!=', 'provisioning'),
                                                        new DataFilter('status', 'notIn', [
                                                                'pending',
                                                                'pendingRemoval',
                                                                'removing',
                                                                'restarting',
                                                                'finishing',
                                                                'resizing'
                                                        ]),
                                                        new DataFilter('id', currentServer.id)
                                                ))?.collect { it.id }?.unique()
                                        if(instanceIds) {
                                            context.services.computeServer.list(new DataQuery().withFilter('id', 'in', instanceIds))?.each { server ->
                                                server.status = 'running'
                                                context.services.computeServer.save(server)
                                            }
                                        }
                                    } else {
                                        def containerStatus = currentServer.powerState == ComputeServer.PowerState.paused ? 'suspended' : 'stopped'
                                        def instanceStatus = currentServer.powerState == ComputeServer.PowerState.paused ? 'suspended' : 'stopped'
                                        context.services.computeServer.list(new DataQuery().withFilter('id', currentServer.id)
                                                .withFilter('status', '!=', 'provisioning'))?.each { server ->
                                            server.status = containerStatus
                                            context.services.computeServer.save(server)
                                        }
                                        def instanceIds = context.services.computeServer.list(new DataQuery()
                                                .withFilters(
                                                        new DataFilter('status', '!=', 'failed'),
                                                        new DataFilter('status', '!=', 'provisioning'),
                                                        new DataFilter('status', 'notIn', [
                                                                'pending',
                                                                'pendingRemoval',
                                                                'removing',
                                                                'restarting',
                                                                'finishing',
                                                                'resizing'
                                                        ]),
                                                        new DataFilter('id', currentServer.id)
                                                ))?.collect { it.id }?.unique()
                                        if(instanceIds) {
                                            context.services.computeServer.list(new DataQuery().withFilter('id', 'in', instanceIds))?.each { server ->
                                                server.status = instanceStatus
                                                context.services.computeServer.save(server)
                                            }
                                        }
                                    }
                                }
                                save = true
                            }

                            //plan
                            ServicePlan plan = SyncUtils.findServicePlanBySizing(availablePlans, currentServer.maxMemory, currentServer.maxCores,
                                    null, fallbackPlan, currentServer.plan, currentServer.account, [])
                            if (currentServer.plan?.id != plan?.id) {
                                currentServer.plan = plan
                                save = true
                            }
                            if (save) {
                                context.async.computeServer.bulkSave([currentServer]).blockingGet()
                            }
                            if (masterItem.Disks) {
                                if (currentServer.status != 'resizing' && currentServer.status != 'provisioning') {
                                    syncVolumes(currentServer, masterItem.Disks)
                                }
                            }
                            log.debug ("updateMatchedVirtualMachines: save: ${save}")
                            if (save) {
                                saves << currentServer
                            }
                        } catch (ex) {
                            log.error("Error Updating Virtual Machine ${currentServer?.name} - ${currentServer.externalId} - ${ex}", ex)
                        }
                    }
                } catch (Exception e) {
                    log.error "Error in updating stats: ${e.message}", e
                }
            }
        } catch (Exception e) {
            log.error "Error in updating virtual machines: ${e.message}", e
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

    def syncVolumes(serverToSync, externalVolumes) {
        log.debug "syncVolumes: ${serverToSync}, ${groovy.json.JsonOutput.prettyPrint(externalVolumes?.encodeAsJSON()?.toString())}"

        def server = context.async.computeServer.get(serverToSync.id).blockingGet()
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
                server = context.async.computeServer.get(serverToSync.id).blockingGet()
                server.maxStorage = maxStorage
                context.async.computeServer.bulkSave([server]).blockingGet()
                changes = true
            }
        } catch (e) {
            log.error("syncVolumes error: ${e}", e)
        }
        return changes
    }

    def addMissingStorageVolumes(itemsToAdd, ComputeServer serverToSync, int diskNumber, maxStorage, changes) {
        def provisionProvider = cloudProvider.getProvisionProvider('morpheus-scvmm-plugin.provision')
        def serverVolumeNames = serverToSync.volumes.collect{ it.name }
        def createVolumeList = []
        def server = context.async.computeServer.get(serverToSync.id).blockingGet()
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
            maxStorage += storageVolume.maxStorage ?: 0l
            diskNumber++
            createVolumeList<< storageVolume
            log.debug("added volume: ${storageVolume?.dump()}")
        }
        context.async.storageVolume.create(createVolumeList, server).blockingGet()
    }

    def updateMatchedStorageVolumes(updateItems, serverToSync, maxStorage, changes) {
        def server = context.async.computeServer.get(serverToSync.id).blockingGet()
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

    def removeMissingStorageVolumes(removeItems, ComputeServer serverToSync, Boolean changes) {
        def removeList = []
        def server = context.async.computeServer.get(serverToSync.id).blockingGet()
        removeItems?.each { currentVolume ->
            log.debug "removing volume: ${currentVolume}"
            if (!currentVolume){
                log.info "volume skipped due to being null: ${currentVolume}"
                return
            }
            changes = true
            currentVolume.controller = null
            currentVolume.datastore = null
            removeList << currentVolume
        }
        if (!context.async.storageVolume.remove(removeList,server,false).blockingGet()) {
            def msg = "Error dissosciating volumes from server ${server.id}, volumes - ${removeList}"
            log.error(msg)
            // Do not propagate exception further, because that will prevent other items from being synced
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
