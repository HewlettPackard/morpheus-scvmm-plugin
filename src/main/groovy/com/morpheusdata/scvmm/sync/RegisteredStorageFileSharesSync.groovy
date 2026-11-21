package com.morpheusdata.scvmm.sync

import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.projection.DatastoreIdentity
import com.morpheusdata.model.projection.StorageVolumeIdentityProjection
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.PrefixedLoggerFactory
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

/**
 * @author rahul.ray
 */

class RegisteredStorageFileSharesSync {

    /** A share registered to at least one cluster: every node can place on it, so HA VMs may use it. */
    static final String EXTERNAL_TYPE_CLUSTER_FILE_SHARE = 'clusterFileShare'
    /** A share registered to individual hosts only. */
    static final String EXTERNAL_TYPE_HOST_FILE_SHARE = 'hostFileShare'

    private Cloud cloud
    private ComputeServer node
    private MorpheusContext context
    private ScvmmApiService apiService
    private LogInterface log = PrefixedLoggerFactory.getLogger(RegisteredStorageFileSharesSync)

    RegisteredStorageFileSharesSync(Cloud cloud, ComputeServer node, MorpheusContext context) {
        this.cloud = cloud
        this.node = node
        this.context = context
        this.apiService = new ScvmmApiService(context)
    }

    def execute() {
        log.debug "RegisteredStorageFileSharesSync"
        try {
            def clusters = context.services.cloud.pool.list(new DataQuery()
                    .withFilter('refType', 'ComputeZone')
                    .withFilter('refId', cloud.id)
                    .withFilter('type', 'Cluster')
                    .withFilter('internalId', '!=', null))
            def scvmmOpts = apiService.getScvmmZoneAndHypervisorOpts(context, cloud, node)
            def listResults = apiService.listRegisteredFileShares(scvmmOpts)
            log.debug("listResults: ${listResults}")
            if (listResults.success == true && listResults.datastores) {
                def objList = listResults?.datastores
                def serverType = context.async.cloud.findComputeServerTypeByCode('scvmmHypervisor').blockingGet()
                List<ComputeServer> existingHosts = context.services.computeServer.list(new DataQuery().withFilter('zone.id', cloud.id)
                        .withFilter('computeServerType.code', 'scvmmHypervisor'))

                def domainRecords = context.async.cloud.datastore.listIdentityProjections(new DataQuery()
                        .withFilter('category', '=~', 'scvmm.registered.file.share.datastore.%')
                        .withFilter('refType', 'ComputeZone').withFilter('refId', cloud.id)
                        .withFilter('type', 'generic'))

                SyncTask<DatastoreIdentity, Map, Datastore> syncTask = new SyncTask<>(domainRecords, objList as Collection<Map>)

                syncTask.addMatchFunction { DatastoreIdentity morpheusItem, Map cloudItem ->
                    morpheusItem?.externalId == cloudItem?.ID
                }.onDelete { removeItems ->
                    log.debug("removing datastore: ${removeItems?.size()}")
                    context.async.cloud.datastore.remove(removeItems).blockingGet()
                }.onUpdate {  updateItems ->
                    updateMatchedFileShares(updateItems, clusters, existingHosts)
                }.onAdd { itemsToAdd ->
                    addMissingFileShares(itemsToAdd, clusters, existingHosts)
                }.withLoadObjectDetailsFromFinder {  updateItems ->
                    return context.async.cloud.datastore.listById(updateItems.collect { it.existingItem.id } as List<Long>)
                }.start()

                // Host volumes are synced once, after every add/update batch, against the full set of reported shares.
                // Syncing per batch would remove the volumes of shares handled by another batch.
                syncVolumeForEachHosts(buildHostToShareMap(objList), objList)
            } else {
                log.info("Not getting the RegisteredStorageFileShares data")
            }
        } catch (e) {
            log.error("RegisteredStorageFileSharesSync error: ${e}", e.getMessage())
        }
    }

    private addMissingFileShares(Collection<Map> addList, clusters, List<ComputeServer> existingHosts) {
        log.debug("RegisteredStorageFileSharesSync: addMissingFileShares: called")
        def dataStoreAdds = []
        try {
            addList?.each { cloudItem ->
                def totalSize = cloudItem.Capacity ? cloudItem.Capacity?.toLong() : 0
                def freeSpace = cloudItem.FreeSpace?.toLong() ?: 0
                def online = cloudItem.IsAvailableForPlacement
                // Create the datastore
                def datastoreConfig = [
                        owner      : cloud.owner,
                        name       : cloudItem.Name,
                        externalId : cloudItem.ID,
                        refType    : 'ComputeZone',
                        refId      : cloud.id,
                        freeSpace  : freeSpace,
                        storageSize: totalSize,
                        cloud      : cloud,
                        category   : "scvmm.registered.file.share.datastore.${cloud.id}",
                        drsEnabled : false,
                        online     : online,
                        type       : 'generic',
                        externalType: externalTypeFor(cloudItem)
                ]
                log.info "Created registered file share for id: ${cloudItem.ID}"
                Datastore datastore = new Datastore(datastoreConfig)
                datastore.assignedZonePools = findOwningPools(cloudItem, clusters, existingHosts)
                dataStoreAdds << datastore
            }
            //create dataStore
            if (dataStoreAdds.size() > 0) {
                context.async.cloud.datastore.bulkCreate(dataStoreAdds).blockingGet()
            }
        } catch (e) {
            log.error "Error in adding RegisteredStorageFileSharesSync ${e}", e
        }
    }

    private updateMatchedFileShares(List<SyncTask.UpdateItem<Datastore, Map>> updateList, clusters, List<ComputeServer> existingHosts) {
        log.debug("RegisteredStorageFileSharesSync >> updateMatchedFileShares >> Entered")
        def itemsToUpdate = []
        try {
            for (update in updateList) {
                Datastore existingItem = update.existingItem
                Map masterItem = update.masterItem
                def save = false
                if (existingItem.online != masterItem.IsAvailableForPlacement) {
                    existingItem.online = masterItem.IsAvailableForPlacement
                    save = true
                }
                if (existingItem.name != masterItem.Name) {
                    existingItem.name = masterItem.Name
                    save = true
                }
                def freeSpace = masterItem.FreeSpace?.toLong() ?: 0
                if (existingItem.freeSpace != freeSpace) {
                    existingItem.freeSpace = freeSpace
                    save = true
                }
                def totalSize = masterItem.Capacity ? masterItem.Capacity?.toLong() : 0
                if (existingItem.storageSize != totalSize) {
                    existingItem.storageSize = totalSize
                    save = true
                }
                List<CloudPool> owningPools = findOwningPools(masterItem, clusters, existingHosts)
                def externalType = externalTypeFor(masterItem)
                if (existingItem.externalType != externalType) {
                    existingItem.externalType = externalType
                    save = true
                }
                if (existingItem.zonePool != null) {
                    existingItem.zonePool = null
                    save = true
                }
                if (poolIds(existingItem.assignedZonePools) != poolIds(owningPools)) {
                    existingItem.assignedZonePools = owningPools
                    save = true
                }
                if (save) {
                    itemsToUpdate << existingItem
                }
            }
            if (itemsToUpdate.size() > 0) {
                context.async.cloud.datastore.bulkSave(itemsToUpdate).blockingGet()
            }
        } catch (e) {
            log.error "Error in update updateMatchedFileShares ${e}", e
        }
    }

    /**
     * Maps each host externalId to the IDs of the registered file shares it can reach, either through a cluster
     * association or a direct host association.
     */
    protected static Map<String, Set<String>> buildHostToShareMap(Collection objList) {
        Map<String, Set<String>> hostToShareMap = [:]
        objList?.each { cloudItem ->
            def shareId = cloudItem?.ID?.toString()
            if (!shareId) {
                return
            }
            ((cloudItem.ClusterAssociations ?: []) + (cloudItem.HostAssociations ?: [])).each { association ->
                def hostId = association?.HostID?.toString()
                if (hostId) {
                    hostToShareMap.get(hostId, [] as Set) << shareId
                }
            }
        }
        return hostToShareMap
    }

	/// hostToShareMap is a map of host externalId to a list of registered file share IDs
	/// objList is the list of registered file shares
    private syncVolumeForEachHosts (hostToShareMap, objList){
        try {
            def existingHostsList = context.services.computeServer.list(new DataQuery().withFilter('zone.id', cloud.id)
                    .withFilter('computeServerType.code', 'scvmmHypervisor'))
            def existingHostIds = []
            existingHostsList?.each {
                existingHostIds << it.id
            }
            def findMountPath = { dsID ->
                def obj = objList.find { it.ID == dsID}
                obj?.MountPoints?.size() > 0 ? obj.MountPoints[0] : null
            }
            def morphDatastores = context.services.cloud.datastore.list(new DataQuery()
                    .withFilter('category', '=~', 'scvmm.registered.file.share.datastore.%')
                    .withFilter('refType', 'ComputeZone').withFilter('refId', cloud.id)
                    .withFilter('type', 'generic'))
            StorageVolumeType volumeType = context.services.storageVolume.storageVolumeType.find(new DataQuery()
                    .withFilter('code', 'scvmm-registered-file-share-datastore'))

            existingHostIds?.each { hostId ->
                // One failing host must not stop the remaining hosts from being synced.
                try {
                    syncHostFileShareVolumes(hostId, hostToShareMap, morphDatastores, volumeType, findMountPath)
                } catch (e) {
                    log.error "error syncing registered file share volumes for host ${hostId}: ${e}", e
                }
            }
        } catch (e) {
            log.error "error in cacheRegisteredStorageFileShares: ${e}", e
        }
    }

    private void syncHostFileShareVolumes(hostId, hostToShareMap, morphDatastores, StorageVolumeType volumeType, Closure findMountPath) {
        ComputeServer host = context.services.computeServer.get(hostId)
        // Model classes do not implement equals(), so the volume type is matched on id/code rather than by identity;
        // an identity check never matches in the appliance and the share volumes would be re-created every sync.
        List<StorageVolume> existingStorageVolumes = (host.volumes?.findAll { isFileShareVolume(it, volumeType) } ?: []) as List<StorageVolume>
        // A host without any registered share still has its stale share volumes removed; SyncTask rejects a null list.
        List<String> masterVolumeIds = ((hostToShareMap[host.externalId] ?: []).collect { it?.toString() }.findAll { it }.unique()) as List<String>
        log.debug "${hostId}:${host.externalId} has ${existingStorageVolumes.size()} volumes already"
        def domainRecords = Observable.fromIterable(existingStorageVolumes)

        SyncTask<StorageVolumeIdentityProjection, String, StorageVolume> syncTask = new SyncTask<>(domainRecords, masterVolumeIds)

        syncTask.addMatchFunction { StorageVolumeIdentityProjection storageVolume, String masterVolumeId ->
            storageVolume?.externalId == masterVolumeId
        }.onDelete { removeItems ->
            log.debug("${hostId}: removing storageVolume: ${removeItems.size()}")
            removeItems?.each { currentVolume ->
                log.debug "removing volume: ${currentVolume}"
                currentVolume.controller = null
                currentVolume.datastore = null

                context.async.storageVolume.save(currentVolume).blockingGet()
                context.async.storageVolume.remove([currentVolume], host, true).blockingGet()
            }
        }.onUpdate { List<SyncTask.UpdateItem<StorageVolume, String>> updateItems ->
            updateItems?.each { updateMap ->
                StorageVolume storageVolume = updateMap.existingItem
                def dsExternalId = updateMap.masterItem
                log.debug "${hostId}: Updating existing volumes ${dsExternalId}"

                Datastore match = morphDatastores.find { it.externalId == dsExternalId }
                if (!match) {
                    log.error "Matching datastore with id ${dsExternalId} not found!"
                    return
                }

                def save = false
                if (storageVolume.maxStorage != match.storageSize) {
                    storageVolume.maxStorage = match.storageSize
                    save = true
                }
                def usedSpace = (match.storageSize ?: 0L) - (match.freeSpace ?: 0L)
                if (storageVolume.usedStorage != usedSpace) {
                    storageVolume.usedStorage = usedSpace
                    save = true
                }
                if (storageVolume.name != match.name) {
                    storageVolume.name = match.name
                    save = true
                }
                def mountPoint = findMountPath(dsExternalId)
                if (storageVolume.volumePath != mountPoint) {
                    storageVolume.volumePath = mountPoint
                    save = true
                }
                if (storageVolume.datastore?.id != match.id) {
                    storageVolume.datastore = match
                    save = true
                }

                if (save) {
                    context.async.storageVolume.save(storageVolume).blockingGet()
                }
            }
        }.onAdd { itemsToAdd ->
            itemsToAdd?.each { dsExternalId ->
                Datastore match = morphDatastores.find { it.externalId == dsExternalId }
                if (match) {
                    log.debug "${hostId}: Adding new volume ${dsExternalId}"
                    def newVolume = new StorageVolume(
                            type: volumeType,
                            maxStorage: match.storageSize,
                            usedStorage: (match.storageSize ?: 0L) - (match.freeSpace ?: 0L),
                            externalId: dsExternalId,
                            internalId: dsExternalId,
                            name: match.name,
                            volumePath: findMountPath(dsExternalId),
                            cloudId: cloud?.id
                    )
                    newVolume.datastore = match
                    context.async.storageVolume.create([newVolume], host).blockingGet()
                    context.async.computeServer.save(host).blockingGet()
                } else {
                    log.error "Matching datastore with id ${dsExternalId} not found!"
                }
            }
        }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<StorageVolumeIdentityProjection, String>> updateItems ->
            return context.async.storageVolume.listById(updateItems.collect { it.existingItem.id } as List<Long>)
        }.start()
    }

    protected static boolean isFileShareVolume(StorageVolume volume, StorageVolumeType volumeType) {
        if (volume?.type == null || volumeType == null) {
            return false
        }
        if (volume.type.id != null && volumeType.id != null) {
            return volume.type.id == volumeType.id
        }
        return volume.type.code != null && volume.type.code == volumeType.code
    }

    /**
     * The clusters a registered file share is associated with, either directly or through the hosts it is mounted
     * on. Scoping the datastore to these pools keeps the share out of the datastore dropdown for unrelated
     * resource pools during provisioning.
     */
    private List<CloudPool> findOwningPools(Map cloudItem, clusters, List<ComputeServer> existingHosts) {
        def clusterIds = (cloudItem.ClusterAssociations?.collect { it.ClusterID?.toString() } ?: []).findAll { it }
        List pools = clusterIds.collect { clusterId -> clusters?.find { it.externalId == clusterId } }.findAll { it }

        def hostIds = ((cloudItem.ClusterAssociations?.collect { it.HostID?.toString() } ?: []) +
                (cloudItem.HostAssociations?.collect { it.HostID?.toString() } ?: [])).findAll { it } as Set
        pools = pools + (existingHosts?.findAll { hostIds.contains(it.externalId) }?.collect { it.resourcePool }?.findAll { it } ?: [])

        return pools.unique { it.id }.collect { new CloudPool(id: it.id) }
    }

    private static Set<Long> poolIds(Collection<CloudPool> pools) {
        return (pools?.collect { it.id }?.findAll { it } ?: []) as Set<Long>
    }

    /**
     * Shares registered to a cluster are reachable from every node and therefore valid for Highly Available VMs.
     */
    protected static String externalTypeFor(Map cloudItem) {
        def clusterIds = (cloudItem?.ClusterAssociations?.collect { it.ClusterID?.toString() } ?: []).findAll { it }
        return clusterIds ? EXTERNAL_TYPE_CLUSTER_FILE_SHARE : EXTERNAL_TYPE_HOST_FILE_SHARE
    }
}
