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
import com.morpheusdata.model.projection.DatastoreIdentityProjection
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import io.reactivex.rxjava3.core.Observable

/**
 * @author rahul.ray
 */

class DatastoresSync {

    // Constants to avoid duplicate string literals
    private static final String REF_TYPE = 'refType'
    private static final String COMPUTE_ZONE = 'ComputeZone'
    private static final String REF_ID = 'refId'
    private static final String TYPE = 'type'
    private static final String GENERIC = 'generic'
    private static final String SHARED_VOLUMES = 'sharedVolumes'

    ComputeServer node
    private final Cloud cloud
    private final MorpheusContext context
    private final ScvmmApiService apiService
    private final LogInterface log = LogWrapper.instance

    DatastoresSync(ComputeServer node, Cloud cloud, MorpheusContext context) {
        this(node, cloud, context, null, null)
    }

    DatastoresSync(ComputeServer node, Cloud cloud, MorpheusContext context, ScvmmApiService apiService) {
        this(node, cloud, context, apiService, null)
    }

    DatastoresSync(ComputeServer node, Cloud cloud, MorpheusContext context, ScvmmApiService apiService, LogInterface log) {
        this.node = node
        this.cloud = cloud
        this.context = context
        this.apiService = apiService ?: new ScvmmApiService(context)
        this.log = log ?: LogWrapper.instance
    }

    def execute() {
        log.debug 'DatastoresSync'
        try {
            def syncContext = buildSyncContext()
            def listResults = apiService.listDatastores(syncContext.scvmmOpts)
            log.debug("DatastoresSync: listResults: ${listResults}")

            if (listResults.success == true && listResults.datastores) {
                def objList = filterUniqueDatastores(listResults.datastores)
                log.debug("DatastoresSync: objList: ${objList}")

                performDatastoreSync(objList, syncContext)
            }
        } catch (ex) {
            log.error('DatastoresSync error: {}', ex.getMessage())
        }
    }

    private buildSyncContext() {
        List<CloudPool> clusters = context.services.cloud.pool.list(new DataQuery()
                .withFilter(REF_TYPE, COMPUTE_ZONE).withFilter(REF_ID, cloud.id)
                .withFilter(TYPE, 'Cluster').withFilter('internalId', '!=', null))
        StorageVolumeType volumeType = context.services.storageVolume.storageVolumeType.find(new DataQuery().withFilter('code', 'scvmm-datastore'))
        def scvmmOpts = apiService.getScvmmZoneAndHypervisorOpts(context, cloud, node)
        List<ComputeServer> existingHosts = context.services.computeServer.list(new DataQuery().withFilter('zone.id', cloud.id)
                .withFilter('computeServerType.code', 'scvmmHypervisor'))

        [
                clusters: clusters,
                volumeType: volumeType,
                scvmmOpts: scvmmOpts,
                existingHosts: existingHosts,
        ]
    }

    private List<Map> filterUniqueDatastores(List<Map> datastores) {
        def objList = []
        def partitionUniqueIds = []
        datastores?.each { data ->
            if (!partitionUniqueIds?.contains(data.partitionUniqueID)) {
                objList << data
            }
            partitionUniqueIds << data.partitionUniqueID
        }
        return objList
    }

    private void performDatastoreSync(List<Map> objList, Map syncContext) {
        Observable<DatastoreIdentityProjection> existingItems = context.async.cloud.datastore.listIdentityProjections(new DataQuery()
                .withFilter('category', '=~', 'scvmm.datastore.%').withFilter(REF_TYPE, COMPUTE_ZONE)
                .withFilter(REF_ID, cloud.id).withFilter(TYPE, GENERIC))

        SyncTask<DatastoreIdentityProjection, Map, Datastore> syncTask = new SyncTask<>(existingItems, objList as Collection<Map>)
        syncTask.addMatchFunction { existingItem, cloudItem ->
            existingItem.externalId?.toString() == cloudItem.partitionUniqueID?.toString()
        }.onDelete { removeItems ->
            removeMissingDatastores(removeItems)
        }.onUpdate { updateItems ->
            updateMatchedDatastores(updateItems, syncContext.clusters, syncContext.existingHosts, syncContext.volumeType)
        }.onAdd { itemsToAdd ->
            addMissingDatastores(itemsToAdd, syncContext.clusters, syncContext.existingHosts, syncContext.volumeType)
        }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<DatastoreIdentityProjection, Map>> updateItems ->
            context.async.cloud.datastore.listById(updateItems.collect { updateItem -> updateItem.existingItem.id } as List<Long>)
        }.start()
    }

    private removeMissingDatastores(List<DatastoreIdentityProjection> removeList) {
        log.debug("removeMissingDatastores: ${cloud} ${removeList.size()}")
        try {
            context.services.cloud.datastore.bulkRemove(removeList)
        } catch (ex) {
            log.error('DatastoresSync: removeMissingDatastores error: {}', ex.getMessage())
        }
    }

    private void updateMatchedDatastores(List<SyncTask.UpdateItem<Datastore, Map>> updateList, List<CloudPool> clusters, List<ComputeServer> existingHosts, StorageVolumeType volumeType) {
        try {
            log.debug("updateMatchedDatastores: ${updateList?.size()}")
            updateList.each { item ->
                updateSingleDatastore(item, clusters, existingHosts, volumeType)
            }
        } catch (e) {
            log.error("Error in updateMatchedDatastores method: ${e}", e)
        }
    }

    private void updateSingleDatastore(SyncTask.UpdateItem<Datastore, Map> item, List<CloudPool> clusters, List<ComputeServer> existingHosts, StorageVolumeType volumeType) {
        def masterItem = item.masterItem
        def existingItem = item.existingItem
        def host = existingHosts.find { hostItem -> hostItem.hostname == masterItem.vmHost }
        def cluster = findClusterForDatastore(masterItem, clusters, host)
        def name = getName(masterItem, cluster, host)

        if (updateDatastoreProperties(existingItem, masterItem, name, cluster)) {
            def savedDataStore = context.async.cloud.datastore.save(existingItem).blockingGet()
            if (savedDataStore && host) {
                syncVolume(masterItem, host, savedDataStore, volumeType, getDataStoreExternalId(masterItem))
            }
        }
    }

    private CloudPool findClusterForDatastore(Map masterItem, List<CloudPool> clusters, ComputeServer host) {
        if (masterItem.isClusteredSharedVolume) {
            return clusters.find { c ->
                c.getConfigProperty(SHARED_VOLUMES)?.toString().contains(masterItem.name) && (!host || host.resourcePool.id == c.id)
            }
        }
        return null
    }

    private boolean updateDatastoreProperties(Datastore existingItem, Map masterItem, String name, CloudPool cluster) {
        boolean doSave = false

        if (existingItem.online != masterItem.isAvailableForPlacement) {
            existingItem.online = masterItem.isAvailableForPlacement
            doSave = true
        }
        if (existingItem.name != name) {
            existingItem.name = name
            doSave = true
        }

        def freeSpace = masterItem.freeSpace?.toLong() ?: 0
        if (existingItem.freeSpace != freeSpace) {
            existingItem.freeSpace = freeSpace
            doSave = true
        }

        def totalSize = calculateTotalSize(masterItem)
        if (existingItem.storageSize != totalSize) {
            existingItem.storageSize = totalSize
            doSave = true
        }

        if (existingItem.zonePool?.id != cluster?.id) {
            existingItem.zonePool = cluster
            doSave = true
        }

        return doSave
    }

    private long calculateTotalSize(Map masterItem) {
        return masterItem.size ? masterItem.size?.toLong() : masterItem.capacity ? masterItem.capacity?.toLong() : 0
    }

    private void addMissingDatastores(Collection<Map> addList, List<CloudPool> clusters, List<ComputeServer> existingHosts, StorageVolumeType volumeType) {
        log.debug("addMissingDatastores: addList?.size(): ${addList?.size()}")
        try {
            addList?.each { Map item ->
                addSingleDatastore(item, clusters, existingHosts, volumeType)
            }
        } catch (e) {
            log.error "Error in adding Datastores sync ${e}", e
        }
    }

    private void addSingleDatastore(Map item, List<CloudPool> clusters, List<ComputeServer> existingHosts, StorageVolumeType volumeType) {
        def externalId = getDataStoreExternalId(item)
        ComputeServer host = existingHosts.find { hostItem -> hostItem.hostname == item.vmHost }
        def cluster = findClusterForDatastore(item, clusters, host)

        def datastoreConfig = buildDatastoreConfig(item, cluster, host, externalId)
        log.debug("datastoreConfig: ${datastoreConfig}")

        Datastore datastore = new Datastore(datastoreConfig)
        def savedDataStore = context.async.cloud.datastore.create(datastore).blockingGet()
        log.debug("savedDataStore?.id: ${savedDataStore?.id}")

        if (savedDataStore && host) {
            syncVolume(item, host, savedDataStore, volumeType, externalId)
        }
    }

    private Map buildDatastoreConfig(Map item, CloudPool cluster, ComputeServer host, String externalId) {
        return [
                cloud      : cloud,
                drsEnabled : false,
                zonePool   : cluster,
                refId      : cloud.id,
                type       : GENERIC,
                owner      : cloud.owner,
                refType    : COMPUTE_ZONE,
                name       : getName(item, cluster, host),
                externalId : externalId,
                online     : item.isAvailableForPlacement,
                category   : "scvmm.datastore.${cloud.id}",
                freeSpace  : item.freeSpace?.toLong() ?: 0,
                active     : cloud.defaultDatastoreSyncActive,
                storageSize: calculateTotalSize(item),
        ]
    }

    private void syncVolume(Map item, ComputeServer host, Datastore savedDataStore, StorageVolumeType volumeType, String externalId) {
        try {
            def volumeMetrics = calculateVolumeMetrics(item)
            StorageVolume existingVolume = host.volumes.find { volumeItem -> volumeItem.externalId == externalId }

            if (existingVolume) {
                updateExistingVolume(existingVolume, item, savedDataStore, volumeMetrics)
            } else {
                def volumeContext = [
                        item: item,
                        savedDataStore: savedDataStore,
                        volumeType: volumeType,
                        externalId: externalId,
                        host: host,
                        volumeMetrics: volumeMetrics,
                ]
                addNewVolume(volumeContext)
            }
        } catch (ex) {
            log.error "Error in volumeSync Datastores ${ex}", ex
        }
    }

    private Map calculateVolumeMetrics(Map item) {
        def totalSize = calculateTotalSize(item)
        def freeSpace = item.freeSpace?.toLong() ?: 0
        def usedSpace = totalSize - freeSpace
        def mountPoint = item.mountPoints?.size() > 0 ? item.mountPoints[0] : null

        return [
                totalSize: totalSize,
                freeSpace: freeSpace,
                usedSpace: usedSpace,
                mountPoint: mountPoint,
        ]
    }

    private void updateExistingVolume(StorageVolume existingVolume, Map item, Datastore savedDataStore, Map volumeMetrics) {
        boolean save = false

        if (existingVolume.internalId != item.id) {
            existingVolume.internalId = item.id
            save = true
        }
        if (existingVolume.maxStorage != volumeMetrics.totalSize) {
            existingVolume.maxStorage = volumeMetrics.totalSize
            save = true
        }
        if (existingVolume.usedStorage != volumeMetrics.usedSpace) {
            existingVolume.usedStorage = volumeMetrics.usedSpace
            save = true
        }
        if (existingVolume.name != item.name) {
            existingVolume.name = item.name
            save = true
        }
        if (existingVolume.volumePath != volumeMetrics.mountPoint) {
            existingVolume.volumePath = volumeMetrics.mountPoint
            save = true
        }
        if (existingVolume.datastore?.id != savedDataStore.id) {
            existingVolume.datastore = savedDataStore
            save = true
        }

        if (save) {
            context.async.storageVolume.save(existingVolume).blockingGet()
        }
    }

    private void addNewVolume(Map volumeContext) {
        def newVolume = new StorageVolume(
                type: volumeContext.volumeType,
                name: volumeContext.item.name,
                cloudId: cloud?.id,
                maxStorage: volumeContext.volumeMetrics.totalSize,
                usedStorage: volumeContext.volumeMetrics.usedSpace,
                externalId: volumeContext.externalId,
                internalId: volumeContext.item.id,
                volumePath: volumeContext.volumeMetrics.mountPoint
        )
        newVolume.datastore = volumeContext.savedDataStore
        context.async.storageVolume.create([newVolume], volumeContext.host).blockingGet()
        log.debug('syncVolume: newVolume created')
    }

    private String getDataStoreExternalId(Map cloudItem) {
        if (cloudItem.partitionUniqueID) {
            return cloudItem.partitionUniqueID
        } else if (cloudItem.isClusteredSharedVolume && cloudItem.storageVolumeID) {
            return cloudItem.storageVolumeID
        }
        return "${cloudItem.name}|${cloudItem.vmHost}"
    }

    private String getName(Map ds, CloudPool cluster, ComputeServer host) {
        def name = ds.name
        if (ds.isClusteredSharedVolume && cluster?.name) {
            name = "${cluster.name} : ${ds.name}"
        } else if (host?.name) {
            name = "${host.name} : ${ds.name}"
        }
        return name
    }
}