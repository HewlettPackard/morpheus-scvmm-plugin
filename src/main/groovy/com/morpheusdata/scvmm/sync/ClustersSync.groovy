package com.morpheusdata.scvmm.sync

import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ResourcePermission
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import io.reactivex.rxjava3.core.Observable
import groovy.transform.CompileDynamic

@CompileDynamic
class ClustersSync {
    // Constants to avoid duplicate string literals
    private static final String COMPUTE_ZONE = 'ComputeZone'
    private static final String REF_TYPE = 'refType'
    private static final String REF_ID = 'refId'
    private static final String OWNER = 'owner'
    private static final String DEFAULT_POOL = 'defaultPool'
    private static final String SHARED_VOLUMES = 'sharedVolumes'
    private static final String NULL_SHARED_VOLUME_SYNC_COUNT = 'nullSharedVolumeSyncCount'
    private final MorpheusContext morpheusContext
    private final Cloud cloud
    private final ScvmmApiService apiService
    private final LogInterface log = LogWrapper.instance

    ClustersSync(MorpheusContext morpheusContext, Cloud cloud) {
        this.cloud = cloud
        this.morpheusContext = morpheusContext
        this.apiService = new ScvmmApiService(morpheusContext)
    }

    void execute() {
        log.debug 'ClustersSync'
        try {
            def server = morpheusContext.services.computeServer.find(new DataQuery().withFilter('cloud.id', cloud.id))

            def scvmmOpts = apiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server)
            def listResults = apiService.listClusters(scvmmOpts)
            log.debug('clusters: {}', listResults)

            if (listResults.success == true && listResults.clusters) {
                def objList = listResults.clusters
                def clusterScope = cloud.getConfigProperty('cluster')
                if (clusterScope) {
                    objList = objList?.findAll { cluster -> cluster.id == clusterScope }
                }

                def masterAccount = cloud.owner.masterAccount

                Observable<CloudPoolIdentity> existingItems = morpheusContext.async.cloud.pool
                    .listIdentityProjections(new DataQuery()
                        .withFilter(REF_TYPE, COMPUTE_ZONE).withFilter(REF_ID, cloud.id))

                SyncTask<CloudPoolIdentity, Map, CloudPool> syncTask = new SyncTask<>(existingItems,
                    objList as Collection<Map>)

                syncTask.addMatchFunction { CloudPoolIdentity existingItem, Map syncItem ->
                    existingItem?.externalId == syncItem?.id
                }.onDelete { removeItems ->
                    removeMissingResourcePools(removeItems)
                }.onUpdate { List<SyncTask.UpdateItem<CloudPool, Map>> updateItems ->
                    updateMatchedResourcePools(updateItems)
                }.onAdd { itemsToAdd ->
                    addMissingResourcePools(itemsToAdd)
                }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<CloudPoolIdentity, Map>> updateItems ->
                    morpheusContext.async.cloud.pool.listById(
                        updateItems.collect { updateItem -> updateItem.existingItem.id } as List<Long>)
                }.start()
                if (masterAccount == false) {
                    chooseOwnerPoolDefaults(cloud.owner)
                }
            } else {
                log.info('Not getting the listClusters')
            }
        } catch (e) {
            log.error("ClustersSync error: ${e}", e)
        }
    }

    void chooseOwnerPoolDefaults(Account currentAccount) {
        // check for default store and set if not
        def pool = morpheusContext.services.cloud.pool.find(new DataQuery()
                .withFilter(OWNER, currentAccount)
                .withFilter(REF_TYPE, COMPUTE_ZONE)
                .withFilter(REF_ID, cloud.id)
                .withFilter(DEFAULT_POOL, true))

        if (pool && pool.readOnly == true) {
            pool.defaultPool = false
            morpheusContext.services.cloud.pool.save(pool)
            pool = null
        }

        if (!pool) {
            pool = morpheusContext.services.cloud.pool.find(new DataQuery()
                    .withFilter(OWNER, currentAccount)
                    .withFilter(REF_TYPE, COMPUTE_ZONE)
                    .withFilter(REF_ID, cloud.id)
                    .withFilter(DEFAULT_POOL, false)
                    .withFilter('readOnly', '!=', true))
            if (pool) {
                pool.defaultPool = true
                morpheusContext.services.cloud.pool.save(pool)
            }
        }
    }

    protected void addMissingResourcePools(Collection<Map> addList) {
        log.debug("addMissingResourcePools: ${addList.size()}")

        List<CloudPool> clusterAdds = []
        List<ResourcePermission> resourcePerms = []
        try {
            addList?.each { Map item ->
                log.debug('add cluster: {}', item)
                def poolConfig = [
                        owner       : cloud.owner,
                        name        : item.name,
                        externalId  : item.id,
                        internalId  : item.name,
                        refType     : COMPUTE_ZONE,
                        refId       : cloud.id,
                        cloud        : cloud,
                        category    : "scvmm.cluster.${cloud.id}",
                        code        : "scvmm.cluster.${cloud.id}.${item.id}",
                        readOnly    : false,
                        type        : 'Cluster',
                        active      : cloud.defaultPoolSyncActive,
                ]
                CloudPool clusterAdd = new CloudPool(poolConfig)
                clusterAdd.setConfigProperty(SHARED_VOLUMES, item.sharedVolumes)
                clusterAdds << clusterAdd
            }

            if (clusterAdds.size() > 0) {
                morpheusContext.async.cloud.pool.bulkCreate(clusterAdds).blockingGet()
                morpheusContext.async.cloud.pool.bulkSave(clusterAdds).blockingGet()
            }

            clusterAdds?.each { cluster ->
                def permissionConfig = [
                        morpheusResourceType    : 'ComputeZonePool',
                        uuid                    : cluster.externalId,
                        morpheusResourceId      : cluster.id,
                        account                 : cloud.account,
                ]
                ResourcePermission resourcePerm = new ResourcePermission(permissionConfig)
                resourcePerms << resourcePerm
            }

            if (resourcePerms.size() > 0) {
                morpheusContext.async.resourcePermission.bulkCreate(resourcePerms).blockingGet()
            }
        } catch (e) {
            log.error "Error in addMissingResourcePools: ${e}", e
        }
    }

    protected void updateMatchedResourcePools(List<SyncTask.UpdateItem<CloudPool, Map>> updateList) {
        log.debug("updateMatchedResourcePools: ${updateList.size()}")

        List<CloudPool> itemsToUpdate = []
        try {
            updateList?.each { updateMap -> // masterItem, existingItem
                def doSave = false

                CloudPool existingItem = updateMap.existingItem
                def masterItem = updateMap.masterItem

                // Sometimes scvmm tells us that the cluster has no shared volumes even when it does! #175290155
                if (existingItem.getConfigProperty(SHARED_VOLUMES) != masterItem.sharedVolumes) {
                    if (!masterItem.sharedVolumes || (masterItem.sharedVolumes?.size() == 1 &&
                            masterItem.sharedVolumes[0] == null)) {
                        // No shared volumes
                        def nullCount = existingItem.getConfigProperty(NULL_SHARED_VOLUME_SYNC_COUNT)?.toLong() ?: 0
                        if (nullCount >= 5) {
                            existingItem.setConfigProperty(SHARED_VOLUMES, masterItem.sharedVolumes)
                        } else {
                            existingItem.setConfigProperty(NULL_SHARED_VOLUME_SYNC_COUNT, ++nullCount)
                        }
                    } else {
                        // Have shared volumes
                        existingItem.setConfigProperty(SHARED_VOLUMES, masterItem.sharedVolumes)
                        existingItem.setConfigProperty(NULL_SHARED_VOLUME_SYNC_COUNT, 0)
                    }
                    doSave = true
                }

                if (doSave) {
                    itemsToUpdate << existingItem
                }
            }

            if (itemsToUpdate.size() > 0) {
                morpheusContext.async.cloud.pool.bulkSave(itemsToUpdate).blockingGet()
            }
        } catch (e) {
            log.error "Error in updateMatchedResourcePools ${e}", e
        }
    }

    protected void removeMissingResourcePools(List<CloudPoolIdentity> removeList) {
        log.debug "removeMissingResourcePools: ${removeList?.size()}"

        def deleteList = []
        try {
            removeList?.each { removeItem ->
                log.debug("removing: ${}", removeItem)
                clearResourcePoolAssociations(removeItem)
                deleteList << removeItem
            }

            if (deleteList.size() > 0) {
                morpheusContext.async.cloud.pool.bulkRemove(deleteList).blockingGet()
            }
        } catch (e) {
            log.error("Error in removeMissingResourcePools: ${e}", e)
        }
    }

    protected void clearResourcePoolAssociations(CloudPoolIdentity removeItem) {
        clearComputeServerAssociations(removeItem)
        clearDatastoreAssociations(removeItem)
        clearNetworkAssociations(removeItem)
        clearCloudPoolAssociations(removeItem)
    }

    protected void clearComputeServerAssociations(CloudPoolIdentity removeItem) {
        def serversToUpdate = morpheusContext.services.computeServer.list(new DataQuery()
                .withFilter('resourcePool.id', removeItem.id))
        if (serversToUpdate) {
            serversToUpdate.each { server -> server.resourcePool = null }
            morpheusContext.async.computeServer.bulkSave(serversToUpdate).blockingGet()
        }
    }

    protected void clearDatastoreAssociations(CloudPoolIdentity removeItem) {
        def datastoresToUpdate = morpheusContext.services.cloud.datastore.list(new DataQuery()
                .withFilter('zonePool.id', removeItem.id))
        if (datastoresToUpdate) {
            datastoresToUpdate.each { datastore -> datastore.zonePool = null }
            morpheusContext.async.cloud.datastore.bulkSave(datastoresToUpdate).blockingGet()
        }
    }

    protected void clearNetworkAssociations(CloudPoolIdentity removeItem) {
        def networksToUpdate = morpheusContext.services.cloud.network.list(new DataQuery()
                .withFilter('cloudPool.id', removeItem.id))
        if (networksToUpdate) {
            networksToUpdate.each { network -> network.cloudPool = null }
            morpheusContext.async.cloud.network.bulkSave(networksToUpdate).blockingGet()
        }
    }

    protected void clearCloudPoolAssociations(CloudPoolIdentity removeItem) {
        def cloudPoolsToUpdate = morpheusContext.services.cloud.pool.list(new DataQuery()
                .withFilter('parent.id', removeItem.id))
        if (cloudPoolsToUpdate) {
            cloudPoolsToUpdate.each { cloudPool -> cloudPool.parent = null }
            morpheusContext.async.cloud.pool.bulkSave(cloudPoolsToUpdate).blockingGet()
        }
    }
}
