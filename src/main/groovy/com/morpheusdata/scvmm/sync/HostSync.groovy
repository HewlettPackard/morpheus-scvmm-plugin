package com.morpheusdata.scvmm.sync

import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.OsType
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import java.time.Instant
import groovy.transform.CompileDynamic

/**
 * @author rahul.ray
 */

@CompileDynamic
class HostSync {
    private static final String SCVMM_HYPERVISOR = 'scvmmHypervisor'
    private static final String COMPUTE_ZONE = 'ComputeZone'
    private static final String WINDOWS_SERVER_2016_CODE = 'windows.server.2016'
    private static final String WINDOWS_SERVER_2012_CODE = 'windows.server.2012'
    private static final String POWER_STATE_ON = 'on'
    private static final String POWER_STATE_OFF = 'off'
    private static final String POWER_STATE_UNKNOWN = 'unknown'
    private static final String HYPERV_RUNNING = 'Running'
    private static final String HYPERV_STOPPED = 'Stopped'

    private final Cloud cloud
    private final ComputeServer node
    private final MorpheusContext context
    private final ScvmmApiService apiService
    private final LogInterface log = LogWrapper.instance

    HostSync(Cloud cloud, ComputeServer node, MorpheusContext context) {
        this.cloud = cloud
        this.node = node
        this.context = context
        this.apiService = new ScvmmApiService(context)
    }

    void execute() {
        log.debug 'HostSync'
        try {
            def scvmmOpts = apiService.getScvmmZoneAndHypervisorOpts(context, cloud, node)
            def listResults = apiService.listHosts(scvmmOpts)
            log.debug("HostSync: listResults: ${listResults}")

            if (listResults.success == true && listResults.hosts) {
                processHostsList(listResults.hosts)
            } else {
                log.error "Error in getting hosts : ${listResults}"
            }
        } catch (e) {
            log.error("HostSync error: ${e}", e)
        }
    }

    void removeMissingHosts(List<ComputeServerIdentityProjection> removeList) {
        log.debug "HostSync: removeMissingHosts: ${removeList.size()}"
        try {
            def parentServers = context.services.computeServer.list(
                    new DataQuery().withFilter('parentServer.id', 'in', removeList*.id)
            )
            def updatedServers = []
            parentServers?.each { server ->
                server.parentServer = null
                updatedServers << server
            }
            if (updatedServers?.size() > 0) {
                context.async.computeServer.bulkSave(updatedServers).blockingGet()
            }
            context.async.computeServer.bulkRemove(removeList).blockingGet()
        } catch (ex) {
            log.error("HostSync: removeMissingHosts error: ${ex}", ex)
        }
    }

    OsType getHypervisorOs(Object name) {
        def rtn
        if (name?.indexOf('2016') > -1) {
            rtn = new OsType(code: WINDOWS_SERVER_2016_CODE)
        } else {
            rtn = new OsType(code: WINDOWS_SERVER_2012_CODE)
        }
        return rtn
    }

    void updateHostStats(ComputeServer server, Map hostMap) {
        log.debug("HostSync: updateHostStats: hostMap: ${hostMap}")
        try {
            def statsData = extractHostStatsData(hostMap)
            def capacityInfo = server.capacityInfo ?: new ComputeCapacityInfo(
                    maxMemory: statsData.maxMemory,
                    maxStorage: statsData.maxStorage
            )

            def updates = updateServerMetrics(server, statsData)
            updates = updateServerCapacity(server, statsData, capacityInfo) || updates
            updates = updateServerState(server, statsData) || updates

            if (updates) {
                server.capacityInfo = capacityInfo
                context.async.computeServer.save(server).blockingGet()
            }
        } catch (e) {
            log.warn("HostSync: error updating host stats: ${e}", e)
        }
    }

    protected void processHostsList(List hosts) {
        // @SuppressWarnings('UnnecessaryGetter')
        def clusters = getClustersForCloud()
        def filteredHosts = filterHostsByScope(hosts, clusters)

        if (filteredHosts?.size() > 0) {
            performSyncOperation(filteredHosts, clusters)
        }
    }

    protected List getClustersForCloud() {
        return context.services.cloud.pool.list(new DataQuery()
                .withFilter('refType', COMPUTE_ZONE).withFilter('refId', cloud.id))
    }

    protected List filterHostsByScope(List hosts, List clusters) {
        def hostGroupScope = cloud.getConfigProperty('hostGroup')
        def matchAllHostGroups = !hostGroupScope
        def clusterScope = cloud.getConfigProperty('cluster')
        def matchAllClusters = !clusterScope

        log.debug("HostSync: clusters?.size(): ${clusters?.size()}")

        def objList = []
        hosts?.each { item ->
            def hostGroupMatch = matchAllHostGroups || apiService.isHostInHostGroup(item.hostGroup, hostGroupScope)
            def cluster = clusters.find { clusterItem -> clusterItem.internalId == item.cluster }
            def clusterMatch = matchAllClusters || cluster
            if (hostGroupMatch && clusterMatch) {
                objList << item
            }
        }

        log.debug("HostSync: objList?.size(): ${objList?.size()}")
        return objList
    }

    protected void performSyncOperation(List filteredHosts, List clusters) {
        def existingItems = context.async.computeServer.listIdentityProjections(
                new DataQuery().withFilter('zone.id', cloud.id).withFilter('computeServerType.code', SCVMM_HYPERVISOR)
        )

        SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask =
                new SyncTask<>(existingItems, filteredHosts as Collection<Map>)
        syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
            domainObject.externalId == cloudItem?.id
        }.withLoadObjectDetailsFromFinder {
                List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
            context.async.computeServer.listById(
                    updateItems.collect { updateItem -> updateItem.existingItem.id } as List<Long>)
        }.onAdd { itemsToAdd ->
            log.debug("HostSync, onAdd: ${itemsToAdd}")
            addMissingHosts(itemsToAdd, clusters)
        }.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
            log.debug("HostSync, onUpdate: ${updateItems}")
            updateMatchedHosts(updateItems, clusters)
        }.onDelete { List<ComputeServerIdentityProjection> removeItems ->
            log.debug("HostSync, onDelete: ${removeItems}")
            removeMissingHosts(removeItems)
        }.start()
    }

    protected void updateMatchedHosts(List<SyncTask.UpdateItem<ComputeServer, Map>> updateList, List clusters) {
        log.debug "HostSync: updateMatchedHosts: ${cloud.id} ${updateList.size()}"
        try {
            for (updateItem in updateList) {
                def existingItem = updateItem.existingItem
                def masterItem = updateItem.masterItem
                def cluster = clusters.find { clusterItem -> clusterItem.internalId == masterItem.cluster }
                // May be null if Host not in a cluster
                if (existingItem.resourcePool != cluster) {
                    existingItem.resourcePool = cluster
                    def savedServer = context.async.computeServer.save(existingItem).blockingGet()
                    log.debug("savedServer?.id: ${savedServer?.id}")
                    if (savedServer) {
                        updateHostStats(savedServer, masterItem)
                    }
                }
            }
        } catch (e) {
            log.error("HostSync: updateMatchedHosts error: ${e}", e)
        }
    }

    protected void addMissingHosts(Collection<Map> addList, List clusters) {
        log.debug "HostSync: addMissingHosts: ${cloud} ${addList.size()}"
        try {
            def serverType = context.async.cloud.findComputeServerTypeByCode(SCVMM_HYPERVISOR).blockingGet()
            for (cloudItem in addList) {
                processNewHostServer(cloudItem, clusters, serverType)
            }
        } catch (e) {
            log.error("HostSync: addMissingHosts error: ${e}", e)
        }
    }

    protected void processNewHostServer(Map cloudItem, List clusters, Object serverType) {
        def serverOs = getHypervisorOs(cloudItem.os)
        def cluster = clusters.find { clusterItem -> clusterItem.internalId == cloudItem.cluster }

        def serverConfig = buildServerConfig(cloudItem, cluster, serverType, serverOs)
        log.debug("serverConfig: ${serverConfig}")

        def newServer = new ComputeServer(serverConfig)
        setServerCapacityInfo(newServer, cloudItem)
        newServer.setConfigProperty('rawData', cloudItem.encodeAsJSON().toString())

        def savedServer = context.async.computeServer.create(newServer).blockingGet()
        log.debug("savedServer?.id: ${savedServer?.id}")

        if (savedServer) {
            updateHostStats(savedServer, cloudItem)
        }
    }

    protected Map buildServerConfig(Map cloudItem, Object cluster, Object serverType, Object serverOs) {
        return [
                account          : cloud.owner,
                category         : "scvmm.host.${cloud.id}",
                name             : cloudItem.computerName,
                resourcePool     : cluster,
                externalId       : cloudItem.id,
                cloud            : cloud,
                sshUsername      : 'Admnistrator',
                apiKey           : UUID.randomUUID(),
                status           : 'provisioned',
                provision        : false,
                singleTenant     : false,
                serverType       : 'hypervisor',
                computeServerType: serverType,
                statusDate       : Date.from(Instant.now()),
                serverOs         : serverOs,
                osType           : 'windows',
                hostname         : cloudItem.name,
        ]
    }

    protected void setServerCapacityInfo(ComputeServer newServer, Map cloudItem) {
        def maxMemory = cloudItem.totalMemory?.toLong() ?: 0
        def maxStorage = cloudItem.totalStorage?.toLong() ?: 0
        def cpuCount = cloudItem.cpuCount?.toLong() ?: 1
        def coresPerCpu = cloudItem.coresPerCpu?.toLong() ?: 1

        newServer.maxMemory = maxMemory
        newServer.maxStorage = maxStorage
        newServer.maxCpu = cpuCount
        newServer.maxCores = cpuCount * coresPerCpu
        newServer.capacityInfo = new ComputeCapacityInfo(
                maxMemory: maxMemory,
                maxStorage: maxStorage,
                maxCores: newServer.maxCores
        )
    }

    protected Map extractHostStatsData(Map hostMap) {
        def storageStats = extractStorageStats(hostMap)
        def cpuStats = extractCpuStats(hostMap)
        def memoryStats = extractMemoryStats(hostMap)

        return [
                maxStorage: storageStats.maxStorage,
                maxUsedStorage: storageStats.maxUsedStorage,
                maxCores: cpuStats.maxCores,
                maxCpu: cpuStats.maxCpu,
                cpuPercent: cpuStats.cpuPercent,
                maxMemory: memoryStats.maxMemory,
                maxUsedMemory: memoryStats.maxUsedMemory,
                powerState: determinePowerState(hostMap.hyperVState),
                hostname: hostMap.name,
        ]
    }

    protected Map extractStorageStats(Map hostMap) {
        return [
                maxStorage: hostMap.totalStorage?.toLong() ?: 0,
                maxUsedStorage: hostMap.usedStorage?.toLong() ?: 0,
        ]
    }

    protected Map extractCpuStats(Map hostMap) {
        def cpuCount = hostMap.cpuCount?.toLong() ?: 1
        def coresPerCpu = hostMap.coresPerCpu?.toLong() ?: 1

        return [
                maxCores: cpuCount * coresPerCpu,
                maxCpu: cpuCount,
                cpuPercent: hostMap.cpuUtilization?.toLong(),
        ]
    }

    protected Map extractMemoryStats(Map hostMap) {
        def totalMemory = hostMap.totalMemory?.toLong() ?: 0
        def availableMemory = hostMap.availableMemory?.toLong() ?: 0

        return [
                maxMemory: totalMemory,
                maxUsedMemory: totalMemory - (availableMemory * ComputeUtility.ONE_MEGABYTE),
        ]
    }

    protected String determinePowerState(String hyperVState) {
        return (hyperVState == HYPERV_RUNNING) ? POWER_STATE_ON :
                (hyperVState == HYPERV_STOPPED) ? POWER_STATE_OFF : POWER_STATE_UNKNOWN
    }

    protected boolean updateServerMetrics(ComputeServer server, Map statsData) {
        boolean updates = false

        if (statsData.powerState == POWER_STATE_ON) {
            updates = true
        }

        if (statsData.maxCpu != server.maxCpu) {
            server.maxCpu = statsData.maxCpu
            updates = true
        }

        if (statsData.maxCores != server.maxCores) {
            server.maxCores = statsData.maxCores
            updates = true
        }

        if (statsData.cpuPercent) {
            updates = true
        }

        return updates
    }

    protected boolean updateServerCapacity(ComputeServer server, Map statsData, ComputeCapacityInfo capacityInfo) {
        boolean updates = false

        if (statsData.maxMemory > server.maxMemory) {
            server.maxMemory = statsData.maxMemory
            capacityInfo?.maxMemory = statsData.maxMemory
            updates = true
        }

        if (statsData.maxUsedMemory != capacityInfo.usedMemory) {
            server.usedMemory = statsData.maxUsedMemory
            capacityInfo.usedMemory = statsData.maxUsedMemory
            updates = true
        }

        if (statsData.maxStorage != server.maxStorage) {
            server.maxStorage = statsData.maxStorage
            capacityInfo?.maxStorage = statsData.maxStorage
            updates = true
        }

        if (statsData.maxUsedStorage != capacityInfo.usedStorage) {
            server.usedStorage = statsData.maxUsedStorage
            capacityInfo.usedStorage = statsData.maxUsedStorage
            updates = true
        }

        return updates
    }

    protected boolean updateServerState(ComputeServer server, Map statsData) {
        boolean updates = false

        if (server.powerState != statsData.powerState) {
            server.powerState = statsData.powerState
            updates = true
        }

        if (statsData.hostname && statsData.hostname != server.hostname) {
            server.hostname = statsData.hostname
            updates = true
        }

        return updates
    }
}
