package com.morpheusdata.scvmm.sync

import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.projection.DatastoreIdentity
import com.morpheusdata.model.projection.StorageVolumeIdentityProjection
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import io.reactivex.rxjava3.core.Observable

/**
 * @author rahul.ray
 */

class RegisteredStorageFileSharesSync {

    private static final String REF_TYPE = 'refType'
    private static final String COMPUTE_ZONE = 'ComputeZone'
    private static final String REF_ID = 'refId'
    private static final String TYPE = 'type'
    private static final String GENERIC = 'generic'
    private static final String CATEGORY = 'category'
    private static final String EQUALS_REGEX = '=~'
    private static final String SCVMM_HYPERVISOR = 'scvmmHypervisor'
    private static final String CATEGORY_PATTERN = 'scvmm.registered.file.share.datastore.%'

    private final Cloud cloud
    private final ComputeServer node
    private final MorpheusContext context
    private final ScvmmApiService apiService
    private final LogInterface log = LogWrapper.instance

    RegisteredStorageFileSharesSync(Cloud cloud, ComputeServer node, MorpheusContext context) {
        this.cloud = cloud
        this.node = node
        this.context = context
        this.apiService = new ScvmmApiService(context)
    }

    def execute() {
        log.debug 'RegisteredStorageFileSharesSync'
        try {
            def scvmmOpts = apiService.getScvmmZoneAndHypervisorOpts(context, cloud, node)
            def listResults = apiService.listRegisteredFileShares(scvmmOpts)
            log.debug("listResults: ${listResults}")
            if (listResults.success == true && listResults.datastores) {
                def objList = listResults?.datastores

                def domainRecords = context.async.cloud.datastore.listIdentityProjections(new DataQuery()
                        .withFilter(CATEGORY, EQUALS_REGEX, CATEGORY_PATTERN)
                        .withFilter(REF_TYPE, COMPUTE_ZONE).withFilter(REF_ID, cloud.id)
                        .withFilter(TYPE, GENERIC))

                SyncTask<DatastoreIdentity, Map, Datastore> syncTask = new SyncTask<>(domainRecords, objList as Collection<Map>)

                syncTask.addMatchFunction { DatastoreIdentity morpheusItem, Map cloudItem ->
                    morpheusItem?.externalId == cloudItem?.ID
                }.onDelete { removeItems ->
                    log.debug("removing datastore: ${removeItems?.size()}")
                    context.async.cloud.datastore.remove(removeItems).blockingGet()
                }.onUpdate { updateItems ->
                    updateMatchedFileShares(updateItems, objList)
                }.onAdd { itemsToAdd ->
                    addMissingFileShares(itemsToAdd, objList)
                }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItem> updateItems ->
                    context.async.cloud.datastore.listById(updateItems.collect { it.existingItem.id } as List<Long>)
                }.start()
            } else {
                log.info('Not getting the RegisteredStorageFileShares data')
            }
        } catch (e) {
            log.error("RegisteredStorageFileSharesSync error: ${e}", e.getMessage())
        }
    }

    private addMissingFileShares(Collection<Map> addList, List objList) {
        log.debug('RegisteredStorageFileSharesSync: addMissingFileShares: called')
        def dataStoreAdds = []
        try {
            def hostToShareMap = [:]

            def getOrCreateHostEntry = { hostId ->
                hostToShareMap[hostId] = hostToShareMap[hostId] ?: ([] as Set)
                hostToShareMap[hostId]
            }
            addList?.each { cloudItem ->
                def externalId = cloudItem.ID
                def totalSize = cloudItem.Capacity ? cloudItem.Capacity?.toLong() : 0
                def freeSpace = cloudItem.FreeSpace?.toLong() ?: 0
                def online = cloudItem.IsAvailableForPlacement
                // Create the datastore
                def datastoreConfig = [
                        owner      : cloud.owner,
                        name       : cloudItem.Name,
                        externalId : cloudItem.ID,
                        refType    : COMPUTE_ZONE,
                        refId      : cloud.id,
                        freeSpace  : freeSpace,
                        storageSize: totalSize,
                        cloud      : cloud,
                        category   : "scvmm.registered.file.share.datastore.${cloud.id}",
                        drsEnabled : false,
                        online     : online,
                        type       : GENERIC,
                ]
                log.info "Created registered file share for id: ${cloudItem.ID}"
                Datastore datastore = new Datastore(datastoreConfig)
                dataStoreAdds << datastore
                // buildMap
                //def externalId = ds.ID
                // Build up a mapping of host (externalId) to registered file shares
                cloudItem.ClusterAssociations?.each { ca ->
                    def hostEntry = getOrCreateHostEntry(ca.HostID)
                    hostEntry << externalId
                }
                cloudItem.HostAssociations?.each { ha ->
                    def hostEntry = getOrCreateHostEntry(ha.HostID)
                    hostEntry << externalId
                }
            }
            //create dataStore
            if (dataStoreAdds.size() > 0) {
                context.async.cloud.datastore.bulkCreate(dataStoreAdds).blockingGet()
            }
            syncVolumeForEachHosts(hostToShareMap, objList)
        } catch (e) {
            log.error "Error in adding RegisteredStorageFileSharesSync ${e}", e
        }
    }

    private updateMatchedFileShares(List<SyncTask.UpdateItem<Datastore, Map>> updateList, List objList) {
        log.debug('RegisteredStorageFileSharesSync >> updateMatchedFileShares >> Entered')
        try {
            def hostToShareMap = [:]
            def itemsToUpdate = []

            updateList.each { update ->
                processDatastoreUpdate(update, itemsToUpdate, hostToShareMap)
            }

            saveUpdatedDatastores(itemsToUpdate)
            syncVolumeForEachHosts(hostToShareMap, objList)
        } catch (e) {
            log.error "Error in update updateMatchedFileShares ${e}", e
        }
    }

    private void processDatastoreUpdate(SyncTask.UpdateItem<Datastore, Map> update, List itemsToUpdate, Map hostToShareMap) {
        Datastore existingItem = update.existingItem
        Map masterItem = update.masterItem

        if (updateDatastoreProperties(existingItem, masterItem)) {
            itemsToUpdate << existingItem
        }

        mapHostToShares(masterItem, hostToShareMap)
    }

    private boolean updateDatastoreProperties(Datastore datastore, Map masterItem) {
        boolean save = false

        save |= updateDatastoreOnlineStatus(datastore, masterItem)
        save |= updateDatastoreName(datastore, masterItem)
        save |= updateDatastoreFreeSpace(datastore, masterItem)
        save |= updateDatastoreStorageSize(datastore, masterItem)

        return save
    }

    private boolean updateDatastoreOnlineStatus(Datastore datastore, Map masterItem) {
        if (datastore.online != masterItem.IsAvailableForPlacement) {
            datastore.online = masterItem.IsAvailableForPlacement
            return true
        }
        return false
    }

    private boolean updateDatastoreName(Datastore datastore, Map masterItem) {
        if (datastore.name != masterItem.Name) {
            datastore.name = masterItem.Name
            return true
        }
        return false
    }

    private boolean updateDatastoreFreeSpace(Datastore datastore, Map masterItem) {
        def freeSpace = masterItem.FreeSpace?.toLong() ?: 0
        if (datastore.freeSpace != freeSpace) {
            datastore.freeSpace = freeSpace
            return true
        }
        return false
    }

    private boolean updateDatastoreStorageSize(Datastore datastore, Map masterItem) {
        def totalSize = masterItem.Capacity ? masterItem.Capacity?.toLong() : 0
        if (datastore.storageSize != totalSize) {
            datastore.storageSize = totalSize
            return true
        }
        return false
    }

    private void mapHostToShares(Map masterItem, Map hostToShareMap) {
        def externalId = masterItem.ID
        def getOrCreateHostEntry = createHostEntryGetter(hostToShareMap)

        processClusterAssociations(masterItem, externalId, getOrCreateHostEntry)
        processHostAssociations(masterItem, externalId, getOrCreateHostEntry)
    }

    private Closure createHostEntryGetter(Map hostToShareMap) {
        return { hostId ->
            hostToShareMap[hostId] = hostToShareMap[hostId] ?: ([] as Set)
            hostToShareMap[hostId]
        }
    }

    private void processClusterAssociations(Map masterItem, String externalId, Closure getOrCreateHostEntry) {
        masterItem.ClusterAssociations?.each { ca ->
            def hostEntry = getOrCreateHostEntry(ca.HostID)
            hostEntry << externalId
        }
    }

    private void processHostAssociations(Map masterItem, String externalId, Closure getOrCreateHostEntry) {
        masterItem.HostAssociations?.each { ha ->
            def hostEntry = getOrCreateHostEntry(ha.HostID)
            hostEntry << externalId
        }
    }

    private void saveUpdatedDatastores(List itemsToUpdate) {
        if (itemsToUpdate.size() > 0) {
            context.async.cloud.datastore.bulkSave(itemsToUpdate).blockingGet()
        }
    }

    /// hostToShareMap is a map of host externalId to a list of registered file share IDs
    /// objList is the list of registered file shares
    private syncVolumeForEachHosts(Map hostToShareMap, List objList) {
        try {
            def existingHostsList = getExistingHosts()
            def morphDatastores = getMorphDatastores()
            def findMountPath = createMountPathFinder(objList)

            existingHostsList?.each { host ->
                syncVolumesForSingleHost(host, hostToShareMap, morphDatastores, findMountPath)
            }
        } catch (e) {
            log.error "error in cacheRegisteredStorageFileShares: ${e}", e
        }
    }

    private List<ComputeServer> getExistingHosts() {
        return context.services.computeServer.list(new DataQuery()
                .withFilter('zone.id', cloud.id)
                .withFilter('computeServerType.code', SCVMM_HYPERVISOR))
    }

    private List<Datastore> getMorphDatastores() {
        return context.services.cloud.datastore.list(new DataQuery()
                .withFilter(CATEGORY, EQUALS_REGEX, CATEGORY_PATTERN)
                .withFilter(REF_TYPE, COMPUTE_ZONE)
                .withFilter(REF_ID, cloud.id)
                .withFilter(TYPE, GENERIC))
    }

    private Closure createMountPathFinder(List objList) {
        return { dsID ->
            def obj = objList.find { storageObj -> storageObj.ID == dsID }
            obj?.MountPoints?.size() > 0 ? obj.MountPoints[0] : null
        }
    }

    private void syncVolumesForSingleHost(ComputeServer host, Map hostToShareMap, List morphDatastores, Closure findMountPath) {
        def volumeType = getVolumeType()
        def existingStorageVolumes = getExistingStorageVolumes(host, volumeType)
        def masterVolumeIds = hostToShareMap[host.externalId]

        log.debug "${host.id}:${host.externalId} has ${existingStorageVolumes?.size()} volumes already"

        def domainRecords = Observable.fromIterable(existingStorageVolumes)
        def syncTask = createStorageVolumeSyncTask(domainRecords, masterVolumeIds, host, morphDatastores, findMountPath)
        syncTask.start()
    }

    private getVolumeType() {
        context.services.storageVolume.storageVolumeType.find(new DataQuery()
                .withFilter('code', 'scvmm-registered-file-share-datastore'))
    }

    private List<StorageVolume> getExistingStorageVolumes(ComputeServer host, Object volumeType) {
        return host.volumes?.findAll { volume -> volume.type == volumeType }
    }

    private SyncTask createStorageVolumeSyncTask(Observable domainRecords, Collection masterVolumeIds, ComputeServer host, List morphDatastores, Closure findMountPath) {
        def syncTask = new SyncTask<StorageVolumeIdentityProjection, Map, StorageVolume>(
                domainRecords, masterVolumeIds as Collection<Map>)

        return syncTask
                .addMatchFunction(this::matchStorageVolume)
                .onDelete { removeItems -> deleteStorageVolumes(removeItems, host) }
                .onUpdate { updateItems -> updateStorageVolumes(updateItems, morphDatastores, findMountPath) }
                .onAdd { itemsToAdd -> addStorageVolumes(itemsToAdd, morphDatastores, findMountPath, host) }
                .withLoadObjectDetailsFromFinder { updateItems ->
                    context.async.storageVolume.listById(updateItems.collect { updateItem -> updateItem.existingItem.id } as List<Long>)
                }
    }

    private boolean matchStorageVolume(StorageVolumeIdentityProjection storageVolume, Map cloudItem) {
        return storageVolume?.externalId == cloudItem?.ID
    }

    private void deleteStorageVolumes(Collection removeItems, ComputeServer host) {
        log.debug("${host.id}: removing storageVolume: ${removeItems.size()}")
        removeItems?.each { currentVolume ->
            removeStorageVolume(currentVolume, host)
        }
    }

    private void removeStorageVolume(StorageVolume currentVolume, ComputeServer host) {
        log.debug "removing volume: ${currentVolume}"
        currentVolume.controller = null
        currentVolume.datastore = null

        context.async.storageVolume.save(currentVolume).blockingGet()
        context.async.storageVolume.remove([currentVolume], host, true).blockingGet()
        context.async.storageVolume.remove(currentVolume).blockingGet()
    }

    private void updateStorageVolumes(Collection updateItems, List morphDatastores, Closure findMountPath) {
        updateItems?.each { updateMap ->
            updateSingleStorageVolume(updateMap, morphDatastores, findMountPath)
        }
    }

    private void updateSingleStorageVolume(Object updateMap, List morphDatastores, Closure findMountPath) {
        StorageVolume storageVolume = updateMap.existingItem
        def dsExternalId = updateMap.masterItem
        Datastore match = morphDatastores.find { datastore -> datastore.externalId == dsExternalId }

        if (updateStorageVolumeProperties(storageVolume, match, findMountPath, dsExternalId)) {
            context.async.storageVolume.save(storageVolume).blockingGet()
        }
    }

    private boolean updateStorageVolumeProperties(StorageVolume storageVolume, Datastore match, Closure findMountPath, Object dsExternalId) {
        boolean save = false

        save |= updateProperty(storageVolume, 'maxStorage', match.storageSize)
        save |= updateProperty(storageVolume, 'usedStorage', match.storageSize - match.freeSpace)
        save |= updateProperty(storageVolume, 'name', match.name)
        save |= updateProperty(storageVolume, 'volumePath', findMountPath(dsExternalId))
        save |= updateDatastoreProperty(storageVolume, match)

        return save
    }

    private boolean updateProperty(StorageVolume volume, String property, Object value) {
        if (volume."${property}" != value) {
            volume."${property}" = value
            return true
        }
        return false
    }

    private boolean updateDatastoreProperty(StorageVolume volume, Datastore match) {
        if (volume.datastore?.id != match.id) {
            volume.datastore = match
            return true
        }
        return false
    }

    private void addStorageVolumes(Collection itemsToAdd, List morphDatastores, Closure findMountPath, ComputeServer host) {
        itemsToAdd?.each { dsExternalId ->
            addSingleStorageVolume(dsExternalId, morphDatastores, findMountPath, host)
        }
    }

    private void addSingleStorageVolume(Object dsExternalId, List morphDatastores, Closure findMountPath, ComputeServer host) {
        Datastore match = morphDatastores.find { datastore -> datastore.externalId == dsExternalId }
        if (match) {
            log.debug "${host.id}: Adding new volume ${dsExternalId}"
            def newVolume = createNewStorageVolume(match, dsExternalId, findMountPath)
            saveNewStorageVolume(newVolume, host)
        } else {
            log.error "Matching datastore with id ${dsExternalId} not found!"
        }
    }

    private StorageVolume createNewStorageVolume(Datastore match, def dsExternalId, Closure findMountPath) {
        def volumeType = getVolumeType()
        def newVolume = new StorageVolume(
                type: volumeType,
                maxStorage: match.storageSize,
                usedStorage: match.storageSize - match.freeSpace,
                externalId: dsExternalId,
                internalId: dsExternalId,
                name: match.name,
                volumePath: findMountPath(dsExternalId),
                cloudId: cloud?.id
        )
        newVolume.datastore = match
        newVolume
    }

    private void saveNewStorageVolume(StorageVolume newVolume, ComputeServer host) {
        context.async.storageVolume.create([newVolume], host).blockingGet()
        context.async.computeServer.save(host).blockingGet()
    }
}