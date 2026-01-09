package com.morpheusdata.scvmm.sync

import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.NetworkUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkSubnet
import com.morpheusdata.model.NetworkSubnetType
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.projection.NetworkIdentityProjection
import com.morpheusdata.model.projection.NetworkSubnetIdentityProjection
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import groovy.transform.CompileDynamic

@CompileDynamic
class NetworkSync {
    private static final String OWNER = 'owner'
    private static final String COMPUTE_ZONE = 'ComputeZone'
    private static final String SUBNET_NAME = 'subnetName'
    private static final String SUBNET_CIDR = 'subnetCidr'

    private final MorpheusContext morpheusContext
    private final Cloud cloud
    private final ScvmmApiService apiService
    private final LogInterface log = LogWrapper.instance

    NetworkSync(MorpheusContext morpheusContext, Cloud cloud) {
        this.cloud = cloud
        this.morpheusContext = morpheusContext
        this.apiService = new ScvmmApiService(morpheusContext)
    }

    void execute() {
        log.debug 'NetworkSync'
        try {
            def networkType = new NetworkType(code: 'scvmmNetwork')
            NetworkSubnetType subnetType = new NetworkSubnetType(code: 'scvmm')

            def server = morpheusContext.services.computeServer.find(new DataQuery().withFilter('cloud.id', cloud.id))
            def scvmmOpts = apiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, server)
            def listResults = apiService.listNetworks(scvmmOpts)

            if (listResults.success == true && listResults.networks) {
                def objList = listResults?.networks
                log.debug('objList: {}', objList)
                if (!objList) {
                    log.info 'No networks returned!'
                }
                def existingItems = morpheusContext.async.cloud.network.listIdentityProjections(new DataQuery()
                        .withFilters(
                                new DataOrFilter(
                                        new DataFilter(OWNER, cloud.account),
                                        new DataFilter(OWNER, cloud.owner)
                                ),
                                new DataFilter('category', '=~', "scvmm.network.${cloud.id}.%")))

                SyncTask<NetworkIdentityProjection, Map, Network> syncTask =
                        new SyncTask<>(existingItems, objList as Collection<Map>)
                syncTask.addMatchFunction { NetworkIdentityProjection network, Map networkItem ->
                    network?.externalId == networkItem?.ID
                }.onDelete { removeItems ->
                    morpheusContext.async.cloud.network.remove(removeItems).blockingGet()
                }.onUpdate { List<SyncTask.UpdateItem<Network, Map>> updateItems ->
                    updateMatchedNetworks(updateItems, subnetType)
                }.onAdd { itemsToAdd ->
                    addMissingNetworks(itemsToAdd, networkType, subnetType, server)
                }.withLoadObjectDetailsFromFinder {
                    List<SyncTask.UpdateItemDto<NetworkIdentityProjection, Map>> updateItems ->
                    morpheusContext.async.cloud.network.listById(
                            updateItems.collect { updateItem ->
                                updateItem.existingItem.id
                            } as List<Long>)
                }.start()
            } else {
                log.info('Not getting the listNetworks')
            }
        } catch (e) {
            log.error("cacheNetworks error: ${e}", e)
        }
    }

    protected void addMissingNetworks(Collection<Map> addList, NetworkType networkType,
                               NetworkSubnetType subnetType, ComputeServer server) {
        log.debug('NetworkSync >> addMissingNetworks >> called')

        try {
            def networkAdds = createNetworkEntities(addList, networkType, server)
            processNetworksWithSubnets(networkAdds, addList, subnetType)
        } catch (e) {
            log.error("Error in addMissingNetworks: ${e}", e)
        }
    }

    protected List<Network> createNetworkEntities(Collection<Map> addList, NetworkType networkType,
                                                ComputeServer server) {
        def networkAdds = []
        addList?.each { networkItem ->
            def networkConfig = [
                    code      : "scvmm.network.${cloud.id}.${server.id}.${networkItem.ID}",
                    category  : "scvmm.network.${cloud.id}.${server.id}",
                    cloud     : cloud,
                    dhcpServer: true,
                    uniqueId  : networkItem.ID,
                    name      : networkItem.Name,
                    externalId: networkItem.ID,
                    type      : networkType,
                    refType   : COMPUTE_ZONE,
                    refId     : cloud.id,
                    owner     : cloud.owner,
                    active    : cloud.defaultNetworkSyncActive,
            ]
            Network networkAdd = new Network(networkConfig)
            networkAdds << networkAdd
        }
        return networkAdds
    }

    protected void processNetworksWithSubnets(List<Network> networkAdds, Collection<Map> addList,
                                            NetworkSubnetType subnetType) {
        if (networkAdds.size() > 0) {
            def result = morpheusContext.async.cloud.network.bulkCreate(networkAdds).blockingGet()
            processSubnetsForNetworks(result.persistedItems, addList, subnetType)
        }
    }

    protected void processSubnetsForNetworks(List<Network> networks, Collection<Map> addList,
                                           NetworkSubnetType subnetType) {
        networks.each { networkAdd ->
            def cloudItem = addList.find { item -> item.Name == networkAdd.name }
            if (cloudItem) {
                addSubnetToNetwork(networkAdd, cloudItem, subnetType)
            }
        }
    }

    protected void addSubnetToNetwork(Network networkAdd, Map cloudItem, NetworkSubnetType subnetType) {
        def subnet = cloudItem.Subnets?.getAt(0)?.Subnet
        def networkCidr = NetworkUtility.getNetworkCidrConfig(subnet)

        def subnetConfig = [
                dhcpServer         : true,
                account            : cloud.owner,
                externalId         : cloudItem.ID,
                networkSubnetType  : subnetType,
                category           : "scvmm.subnet.${cloud.id}",
                name               : cloudItem.Name,
                vlanId             : cloudItem.VLanID,
                cidr               : subnet,
                netmask            : networkCidr.config?.netmask,
                dhcpStart          : (networkCidr.ranges ? networkCidr.ranges[0].startAddress : null),
                dhcpEnd            : (networkCidr.ranges ? networkCidr.ranges[0].endAddress : null),
                subnetAddress      : subnet,
                refType            : COMPUTE_ZONE,
                refId              : cloud.id,
        ]
        def addSubnet = new NetworkSubnet(subnetConfig)
        morpheusContext.async.networkSubnet.create([addSubnet], networkAdd).blockingGet()
    }

    protected void updateMatchedNetworks(List<SyncTask.UpdateItem<Network, Map>> updateList,
                                        NetworkSubnetType subnetType) {
        log.debug('NetworkSync >> updateMatchedNetworks >> Entered')

        try {
            updateList?.each { updateMap ->
                Network network = updateMap.existingItem
                def matchedNetwork = updateMap.masterItem

                def existingSubnetIds = network.subnets*.id
                def existingSubnets = morpheusContext.async.networkSubnet.list(new DataQuery()
                        .withFilter('id', 'in', existingSubnetIds))

                def masterSubnets = matchedNetwork.Subnets

                SyncTask<NetworkSubnetIdentityProjection, Map, NetworkSubnet> syncTask =
                        new SyncTask<>(existingSubnets, masterSubnets as Collection<Map>)

                syncTask.addMatchFunction { NetworkSubnetIdentityProjection subnet, Map scvmmSubnet ->
                    subnet?.externalId == scvmmSubnet.ID
                }.onDelete { removeItems ->
                    morpheusContext.async.networkSubnet.remove(removeItems).blockingGet()
                }.onUpdate { List<SyncTask.UpdateItem<NetworkSubnet, Map>> updateItems ->
                    updateMatchedNetworkSubnet(updateItems)
                }.onAdd { itemsToAdd ->
                    addMissingNetworkSubnet(itemsToAdd, subnetType, network)
                }.withLoadObjectDetailsFromFinder {
                    List<SyncTask.UpdateItemDto<NetworkSubnetIdentityProjection, Map>> updateItems ->
                    morpheusContext.async.networkSubnet.listById(
                            updateItems.collect { updateItem -> updateItem.existingItem.id } as List<Long>)
                }.start()
            }
        } catch (e) {
            log.error("Error in updateMatchedNetworks: ${e}", e)
        }
    }

    protected void addMissingNetworkSubnet(Collection<Map> addList, NetworkSubnetType subnetType, Network network) {
        log.debug("addMissingNetworkSubnet: ${addList}")

        def subnetAdds = []
        try {
            addList?.each { scvmmSubnet ->
                log.debug("adding new SCVMM subnet: ${scvmmSubnet}")

                def networkCidr = NetworkUtility.getNetworkCidrConfig(scvmmSubnet.Subnet)
                def subnetInfo = [
                        dhcpServer       : true,
                        account          : cloud.owner,
                        externalId       : scvmmSubnet.ID,
                        networkSubnetType: subnetType,
                        category         : "scvmm.subnet.${cloud.id}",
                        name             : scvmmSubnet.Name,
                        cidr             : scvmmSubnet.Subnet,
                        netmask          : networkCidr.config?.netmask,
                        dhcpStart        : (networkCidr.ranges ? networkCidr.ranges[0].startAddress : null),
                        status           : NetworkSubnet.Status.AVAILABLE,
                        dhcpEnd          : (networkCidr.ranges ? networkCidr.ranges[0].endAddress : null),
                        subnetAddress    : scvmmSubnet.Subnet,
                        refType          : COMPUTE_ZONE,
                        refId            : cloud.id,
                ]

                NetworkSubnet subnetAdd = new NetworkSubnet(subnetInfo)
                subnetAdds << subnetAdd
            }

            // create networkSubnets
            morpheusContext.async.networkSubnet.create(subnetAdds, network).blockingGet()
        } catch (e) {
            log.error("Error in addMissingNetworkSubnet: ${e}", e)
        }
    }

    protected void updateMatchedNetworkSubnet(List<SyncTask.UpdateItem<NetworkSubnet, Map>> updateList) {
        log.debug("updateMatchedNetworkSubnet: ${updateList}")

        List<NetworkSubnet> itemsToUpdate = []
        try {
            updateList?.each { subnetUpdateMap ->
                def matchedSubnet = subnetUpdateMap.masterItem
                NetworkSubnet subnet = subnetUpdateMap.existingItem
                log.debug("updating subnet: ${matchedSubnet}")

                if (subnet && updateSubnetProperties(subnet, matchedSubnet)) {
                    itemsToUpdate << subnet
                }
            }

            if (itemsToUpdate.size() > 0) {
                morpheusContext.async.networkSubnet.save(itemsToUpdate).blockingGet()
            }
        } catch (e) {
            log.error "Error in updateMatchedNetworkSubnet ${e}", e
        }
    }

    protected boolean updateSubnetProperties(NetworkSubnet subnet, Map matchedSubnet) {
        def networkCidr = NetworkUtility.getNetworkCidrConfig(matchedSubnet.Subnet)
        def save = false

        save = updateSubnetBasicProperties(subnet, matchedSubnet) || save
        save = updateSubnetNetworkProperties(subnet, matchedSubnet, networkCidr) || save
        save = updateSubnetDhcpProperties(subnet, networkCidr) || save

        if (subnet.vlanId != matchedSubnet.VLanID) {
            subnet.vlanId = matchedSubnet.VLanID
            save = true
        }

        return save
    }

    protected boolean updateSubnetBasicProperties(NetworkSubnet subnet, Map matchedSubnet) {
        def save = false

        if (subnet.name != matchedSubnet.Name) {
            subnet.name = matchedSubnet.Name
            save = true
        }
        if (subnet.getConfigProperty(SUBNET_NAME) != matchedSubnet.Name) {
            subnet.setConfigProperty(SUBNET_NAME, matchedSubnet.Name)
            save = true
        }

        return save
    }

    protected boolean updateSubnetNetworkProperties(NetworkSubnet subnet, Map matchedSubnet, Map networkCidr) {
        def save = false

        if (subnet.cidr != matchedSubnet.Subnet) {
            subnet.cidr = matchedSubnet.Subnet
            save = true
        }
        if (subnet.getConfigProperty(SUBNET_CIDR) != matchedSubnet.Subnet) {
            subnet.setConfigProperty(SUBNET_CIDR, matchedSubnet.Subnet)
            save = true
        }
        if (subnet.subnetAddress != matchedSubnet.Subnet) {
            subnet.subnetAddress = matchedSubnet.Subnet
            save = true
        }
        if (subnet.netmask != networkCidr.config?.netmask) {
            subnet.netmask = networkCidr.config?.netmask
            save = true
        }

        return save
    }

    protected boolean updateSubnetDhcpProperties(NetworkSubnet subnet, Map networkCidr) {
        def save = false

        def dhcpStart = networkCidr.ranges ? networkCidr.ranges[0].startAddress : null
        if (subnet.dhcpStart != dhcpStart) {
            subnet.dhcpStart = dhcpStart
            save = true
        }

        def dhcpEnd = networkCidr.ranges ? networkCidr.ranges[0].endAddress : null
        if (subnet.dhcpEnd != dhcpEnd) {
            subnet.dhcpEnd = dhcpEnd
            save = true
        }

        return save
    }
}
