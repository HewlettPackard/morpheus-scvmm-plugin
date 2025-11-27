package com.morpheusdata.scvmm.sync

import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.NetworkPoolRange
import com.morpheusdata.model.NetworkPoolType
import com.morpheusdata.model.NetworkSubnet
import com.morpheusdata.model.ResourcePermission
import com.morpheusdata.model.projection.NetworkPoolIdentityProjection
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import groovy.transform.CompileDynamic
import org.apache.commons.net.util.SubnetUtils

@CompileDynamic
class IpPoolsSync {
    private static final String OWNER_FILTER = 'owner'
    private static final String CATEGORY_FILTER = 'category'
    private static final String REGEX_MATCH = '=~'
    private static final String NETWORK_POOL_TYPE = 'NetworkPool'

    private final MorpheusContext morpheusContext
    private final Cloud cloud
    private final ScvmmApiService apiService
    private final LogInterface log = LogWrapper.instance

    IpPoolsSync(MorpheusContext morpheusContext, Cloud cloud) {
        this.cloud = cloud
        this.morpheusContext = morpheusContext
        this.apiService = new ScvmmApiService(morpheusContext)
    }

    void execute() {
        log.debug 'IpPoolsSync'
        try {
            // @SuppressWarnings('UnnecessaryGetter')
            def networks = getNetworksForSync()
            def syncContext = buildSyncContext()

            if (syncContext.listResults.success == true) {
                executeSyncTask(networks, syncContext)
            }
        } catch (e) {
            log.error("ipPoolsSync error: ${e}", e)
        }
    }

    void updateNetworkForPool(List<Network> networks, NetworkPool pool, String networkId, String subnetId,
                             List networkMapping) {
        log.debug "updateNetworkForPool: ${networks} ${pool} ${networkId} ${subnetId} ${networkMapping}"
        try {
            Network network = findNetworkForPool(networks, networkId, networkMapping)

            if (network) {
                updateNetworkProperties(network, pool)
            }

            if (subnetId && network) {
                updateSubnetForPool(network, pool, subnetId)
            }
        } catch (e) {
            log.error("Error in updateNetworkForPool: ${e}", e)
        }
    }

    protected List<Network> getNetworksForSync() {
        return morpheusContext.services.cloud.network.list(new DataQuery()
                .withFilters(
                        new DataOrFilter(
                                new DataFilter(OWNER_FILTER, cloud.account),
                                new DataFilter(OWNER_FILTER, cloud.owner)
                        ),
                        new DataOrFilter(
                                new DataFilter(CATEGORY_FILTER, REGEX_MATCH, "scvmm.network.${cloud.id}.%"),
                                new DataFilter(CATEGORY_FILTER, REGEX_MATCH, "scvmm.vlan.network.${cloud.id}.%")
                        )
                ))
    }

    protected Map buildSyncContext() {
        def server = morpheusContext.services.computeServer.find(new DataQuery().withFilter('cloud.id', cloud.id))
        def scvmmOpts = apiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server)
        def listResults = apiService.listNetworkIPPools(scvmmOpts)

        return [
                listResults: listResults,
                poolType: new NetworkPoolType(code: 'scvmm'),
                objList: listResults.ipPools,
                networkMapping: listResults.networkMapping,
        ]
    }

    protected void executeSyncTask(List networks, Map syncContext) {
        def existingItems = morpheusContext.async.cloud.network.pool.listIdentityProjections(new DataQuery()
                .withFilter('account.id', cloud.account.id)
                .withFilter(CATEGORY_FILTER, "scvmm.ipPool.${cloud.id}"))

        SyncTask<NetworkPoolIdentityProjection, Map, NetworkPool> syncTask =
                new SyncTask<>(existingItems, syncContext.objList as Collection<Map>)
        syncTask.addMatchFunction { NetworkPoolIdentityProjection networkPool, poolItem ->
            networkPool?.externalId == poolItem?.ID
        }.onDelete { removeItems ->
            morpheusContext.async.cloud.network.pool.remove(removeItems).blockingGet()
        }.onUpdate { List<SyncTask.UpdateItem<NetworkPool, Map>> updateItems ->
            updateMatchedIpPools(updateItems, networks, syncContext.networkMapping)
        }.onAdd { itemsToAdd ->
            addMissingIpPools(itemsToAdd, networks, syncContext.poolType, syncContext.networkMapping)
        }.withLoadObjectDetailsFromFinder {
            List<SyncTask.UpdateItemDto<NetworkPoolIdentityProjection, Map>> updateItems ->
            morpheusContext.async.cloud.network.pool.listById(
                    updateItems.collect { updateItem -> updateItem.existingItem.id } as List<Long>)
        }.start()
    }

    protected void addMissingIpPools(Collection<Map> addList, List<Network> networks, NetworkPoolType poolType,
                              List networkMapping) {
        log.debug("addMissingIpPools: ${addList.size()}")

        List<NetworkPool> networkPoolAdds = []
        List<NetworkPoolRange> poolRangeAdds = []

        try {
            // Create network pools and ranges
            buildNetworkPoolsAndRanges(addList, poolType, networkPoolAdds, poolRangeAdds)

            // Save pools and ranges
            savePools(networkPoolAdds, poolRangeAdds)

            // Create resource permissions
            createResourcePermissions(networkPoolAdds)

            // Update network associations
            updateNetworkAssociations(addList, networks, networkPoolAdds, networkMapping)
        } catch (e) {
            log.error("Error in addMissingIpPools: ${e}", e)
        }
    }

    protected void buildNetworkPoolsAndRanges(Collection<Map> addList, NetworkPoolType poolType,
                                       List<NetworkPool> networkPoolAdds, List<NetworkPoolRange> poolRangeAdds) {
        addList?.each { poolData ->
            NetworkPool pool = createNetworkPool(poolData, poolType)
            networkPoolAdds << pool

            if (poolData.IPAddressRangeStart && poolData.IPAddressRangeEnd) {
                NetworkPoolRange range = createNetworkPoolRange(poolData, pool)
                poolRangeAdds << range
                pool.addToIpRanges(range)
            }
        }
    }

    protected NetworkPool createNetworkPool(Map poolData, NetworkPoolType poolType) {
        // @SuppressWarnings('UnnecessaryGetter')
        def info = new SubnetUtils(poolData.Subnet as String).getInfo()
        def gateway = poolData.DefaultGateways ? poolData.DefaultGateways.first() : null

        def addConfig = [
                account      : cloud.account,
                typeCode     : "scvmm.ipPool.${cloud.id}.${poolData.ID}",
                category     : "scvmm.ipPool.${cloud.id}",
                name         : poolData.Name,
                displayName  : "${poolData.Name} (${poolData.Subnet})",
                externalId   : poolData.ID,
                ipCount      : poolData.TotalAddresses ?: 0,
                ipFreeCount  : poolData.AvailableAddresses ?: 0,
                dhcpServer   : true,
                gateway      : gateway,
                poolEnabled  : true,
                netmask      : info.netmask,
                subnetAddress: info.networkAddress,
                type         : poolType,
                refType      : 'ComputeZone',
                refId        : "${cloud.id}",
        ]
        return new NetworkPool(addConfig)
    }

    protected NetworkPoolRange createNetworkPoolRange(Map poolData, NetworkPool pool) {
        def rangeConfig = [
                networkPool: pool,
                startAddress: poolData.IPAddressRangeStart,
                endAddress: poolData.IPAddressRangeEnd,
                addressCount: (poolData.TotalAddresses ?: 0).toInteger(),
                externalId: poolData.ID,
        ]
        return new NetworkPoolRange(rangeConfig)
    }

    protected void savePools(List<NetworkPool> networkPoolAdds, List<NetworkPoolRange> poolRangeAdds) {
        if (networkPoolAdds.size() > 0) {
            morpheusContext.async.cloud.network.pool.bulkCreate(networkPoolAdds).blockingGet()
        }

        if (poolRangeAdds.size() > 0) {
            morpheusContext.async.cloud.network.pool.poolRange.bulkCreate(poolRangeAdds).blockingGet()
            morpheusContext.async.cloud.network.pool.bulkSave(networkPoolAdds).blockingGet()
        }
    }

    protected void createResourcePermissions(List<NetworkPool> networkPoolAdds) {
        List<ResourcePermission> resourcePerms = []

        networkPoolAdds?.each { pool ->
            def permissionConfig = [
                    morpheusResourceType    : NETWORK_POOL_TYPE,
                    uuid                    : pool.externalId,
                    morpheusResourceId      : pool.id,
                    account                 : cloud.account,
            ]
            ResourcePermission resourcePerm = new ResourcePermission(permissionConfig)
            resourcePerms << resourcePerm
        }

        if (resourcePerms.size() > 0) {
            morpheusContext.async.resourcePermission.bulkCreate(resourcePerms).blockingGet()
        }
    }

    protected void updateNetworkAssociations(Collection<Map> addList, List<Network> networks,
                                      List<NetworkPool> networkPoolAdds, List networkMapping) {
        networkPoolAdds.each { pool ->
            def mapping = addList.find { item -> item.ID == pool.externalId }
            updateNetworkForPool(networks, pool, mapping?.NetworkID, mapping?.SubnetID, networkMapping)
        }
    }

    protected Network findNetworkForPool(List<Network> networks, String networkId, List networkMapping) {
        def networkExternalId = networkMapping?.find { mapping -> mapping.ID == networkId }?.ID
        return networks?.find { network -> network.externalId == networkExternalId }
    }

    protected void updateNetworkProperties(Network network, NetworkPool pool) {
        def doSave = false

        if (network.pool != pool) {
            network.pool = pool
            doSave = true
        }

        doSave = updateNetworkGateway(network, pool) || doSave
        doSave = updateNetworkDnsServers(network, pool) || doSave
        doSave = updateNetworkNetmask(network, pool) || doSave
        doSave = updateNetworkStaticOverride(network) || doSave

        if (doSave) {
            morpheusContext.async.cloud.network.save(network).blockingGet()
        }
    }

    protected boolean updateNetworkGateway(Network network, NetworkPool pool) {
        if (pool.gateway && network.gateway != pool.gateway) {
            network.gateway = pool.gateway
            true
        } else {
            false
        }
    }

    protected boolean updateNetworkDnsServers(Network network, NetworkPool pool) {
        boolean updated = false

        if (pool.dnsServers?.size() && pool.dnsServers[0] && network.dnsPrimary != pool.dnsServers[0]) {
            network.dnsPrimary = pool.dnsServers[0] ?: null
            updated = true
        }

        if (pool.dnsServers?.size() > 1 && pool.dnsServers[1] && network.dnsSecondary != pool.dnsServers[1]) {
            network.dnsSecondary = pool.dnsServers[1] ?: null
            updated = true
        }

        return updated
    }

    protected boolean updateNetworkNetmask(Network network, NetworkPool pool) {
        if (pool.netmask && network.netmask != pool.netmask) {
            network.netmask = pool.netmask
            true
        } else {
            false
        }
    }

    protected boolean updateNetworkStaticOverride(Network network) {
        if (network.allowStaticOverride != true) {
            network.allowStaticOverride = true
            true
        } else {
            false
        }
    }

    protected void updateSubnetForPool(Network network, NetworkPool pool, String subnetId) {
        def subnetObj = network.subnets.find { subnet -> subnet.externalId?.startsWith(subnetId) }

        if (subnetObj) {
            def subnet = morpheusContext.services.networkSubnet.get(subnetObj.id)
            if (subnet) {
                updateSubnetProperties(subnet, pool)
            }
        }
    }

    protected void updateSubnetProperties(NetworkSubnet subnet, NetworkPool pool) {
        def doSave = false

        if (subnet.pool != pool) {
            subnet.pool = pool
            doSave = true
        }

        doSave = updateSubnetGateway(subnet, pool) || doSave
        doSave = updateSubnetDnsServers(subnet, pool) || doSave
        doSave = updateSubnetNetmask(subnet, pool) || doSave

        if (doSave) {
            morpheusContext.async.networkSubnet.save(subnet).blockingGet()
        }
    }

    protected boolean updateSubnetGateway(NetworkSubnet subnet, NetworkPool pool) {
        if (pool.gateway && subnet.gateway != pool.gateway) {
            subnet.gateway = pool.gateway
            true
        } else {
            false
        }
    }

    protected boolean updateSubnetDnsServers(NetworkSubnet subnet, NetworkPool pool) {
        boolean updated = false

        if (pool.dnsServers?.size() && pool.dnsServers[0] && subnet.dnsPrimary != pool.dnsServers[0]) {
            subnet.dnsPrimary = pool.dnsServers[0] ?: null
            updated = true
        }

        if (pool.dnsServers?.size() > 1 && pool.dnsServers[1] && subnet.dnsSecondary != pool.dnsServers[1]) {
            subnet.dnsSecondary = pool.dnsServers[1] ?: null
            updated = true
        }

        return updated
    }

    protected boolean updateSubnetNetmask(NetworkSubnet subnet, NetworkPool pool) {
        if (pool.netmask && subnet.netmask != pool.netmask) {
            subnet.netmask = pool.netmask
            true
        } else {
            false
        }
    }

    protected void updateMatchedIpPools(List<SyncTask.UpdateItem<NetworkPool, Map>> updateList, List networks,
                                  List networkMapping) {
        log.debug("updateMatchedIpPools : ${updateList.size()}")

        try {
            updateList?.each { updateMap ->
                NetworkPool existingItem = updateMap.existingItem
                def masterItem = updateMap.masterItem
                if (existingItem) {
                    updateIpPoolRange(existingItem, masterItem)
                    updatePoolProperties(existingItem, masterItem)
                    ensureResourcePermission(existingItem)
                    updateNetworkForPool(networks, existingItem, masterItem.NetworkID, masterItem.SubnetID,
                                         networkMapping)
                }
            }
        } catch (e) {
            log.error("Error in updateMatchedIpPools: ${e}", e)
        }
    }

    protected void updateIpPoolRange(NetworkPool existingItem, Map masterItem) {
        if (masterItem.IPAddressRangeStart && masterItem.IPAddressRangeEnd) {
            if (existingItem.ipRanges) {
                updateExistingIpRange(existingItem, masterItem)
            } else {
                createNewIpRange(existingItem, masterItem)
            }
        }
    }

    protected void createNewIpRange(NetworkPool existingItem, Map masterItem) {
        def range = new NetworkPoolRange(
                networkPool: existingItem,
                startAddress: masterItem.IPAddressRangeStart,
                endAddress: masterItem.IPAddressRangeEnd,
                addressCount: (masterItem.TotalAddresses ?: 0).toInteger(),
                externalId: masterItem.ID
        )
        existingItem.addToIpRanges(range)
        morpheusContext.async.cloud.network.pool.poolRange.create(range).blockingGet()
        morpheusContext.async.cloud.network.pool.save(existingItem).blockingGet()
    }

    protected void updateExistingIpRange(NetworkPool existingItem, Map masterItem) {
        NetworkPoolRange range = existingItem.ipRanges.first()
        boolean needsUpdate = (range.startAddress != masterItem.IPAddressRangeStart ||
                range.endAddress != masterItem.IPAddressRangeEnd ||
                range.addressCount != (masterItem.TotalAddresses ?: 0).toInteger() ||
                range.externalId != masterItem.ID)

        if (needsUpdate) {
            range.startAddress = masterItem.IPAddressRangeStart
            range.endAddress = masterItem.IPAddressRangeEnd
            range.addressCount = (masterItem.TotalAddresses ?: 0).toInteger()
            range.externalId = masterItem.ID
            morpheusContext.async.cloud.network.pool.poolRange.save(range).blockingGet()
        }
    }

    protected void updatePoolProperties(NetworkPool existingItem, Map masterItem) {
        def doSave = false

        doSave = updatePoolName(existingItem, masterItem) || doSave
        doSave = updatePoolDisplayName(existingItem, masterItem) || doSave
        doSave = updatePoolAddressCounts(existingItem, masterItem) || doSave
        doSave = updatePoolGateway(existingItem, masterItem) || doSave
        doSave = updatePoolSubnetInfo(existingItem, masterItem) || doSave

        if (doSave) {
            morpheusContext.async.cloud.network.pool.save(existingItem).blockingGet()
        }
    }

    protected boolean updatePoolName(NetworkPool existingItem, Map masterItem) {
        if (existingItem.name != masterItem.Name) {
            existingItem.name = masterItem.Name
            true
        } else {
            false
        }
    }

    protected boolean updatePoolDisplayName(NetworkPool existingItem, Map masterItem) {
        def displayName = "${masterItem.Name} (${masterItem.Subnet})"
        if (existingItem.displayName != displayName) {
            existingItem.displayName = displayName
            true
        } else {
            false
        }
    }

    protected boolean updatePoolAddressCounts(NetworkPool existingItem, Map masterItem) {
        boolean updated = false

        if (existingItem.ipCount != (masterItem.TotalAddresses ?: 0).toInteger()) {
            existingItem.ipCount = (masterItem.TotalAddresses ?: 0).toInteger()
            updated = true
        }

        if (existingItem.ipFreeCount != (masterItem.AvailableAddresses ?: 0).toInteger()) {
            existingItem.ipFreeCount = (masterItem.AvailableAddresses ?: 0).toInteger()
            updated = true
        }

        return updated
    }

    protected boolean updatePoolGateway(NetworkPool existingItem, Map masterItem) {
        def gateway = masterItem.DefaultGateways ? masterItem.DefaultGateways.first() : null
        if (existingItem.gateway != gateway) {
            existingItem.gateway = gateway
            true
        } else {
            false
        }
    }

    protected boolean updatePoolSubnetInfo(NetworkPool existingItem, Map masterItem) {
        def info = new SubnetUtils(masterItem.Subnet as String).info
        boolean updated = false

        if (existingItem.netmask != info.netmask) {
            existingItem.netmask = info.netmask
            updated = true
        }

        if (existingItem.subnetAddress != info.networkAddress) {
            existingItem.subnetAddress = info.networkAddress
            updated = true
        }

        return updated
    }

    protected void ensureResourcePermission(NetworkPool existingItem) {
        def existingPermission = morpheusContext.services.resourcePermission.find(new DataQuery()
                .withFilter('morpheusResourceType', NETWORK_POOL_TYPE)
                .withFilter('morpheusResourceId', existingItem.id)
                .withFilter('account', cloud.account))

        if (!existingPermission) {
            def resourcePerm = new ResourcePermission(
                    morpheusResourceType: NETWORK_POOL_TYPE,
                    uuid: existingItem.externalId,
                    morpheusResourceId: existingItem.id,
                    account: cloud.account
            )
            morpheusContext.async.resourcePermission.create(resourcePerm).blockingGet()
        }
    }
}
