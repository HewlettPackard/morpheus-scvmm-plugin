package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.IPAMProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkDomain
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.NetworkPoolIp
import com.morpheusdata.model.NetworkPoolServer
import com.morpheusdata.model.NetworkPoolType
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper

class ScvmmNetworkPoolProvider implements IPAMProvider {
    public static final String NETWORK_POOL_PROVIDER_CODE = 'scvmm-plugin-ipam'
    public static final String NETWORK_POOL_PROVIDER_NAME = 'SCVMM IPAM Plugin'
    protected MorpheusContext context
    protected ScvmmPlugin plugin
    static poolMutex = new Object()
    ScvmmApiService apiService
    private LogInterface log = LogWrapper.instance


    ScvmmNetworkPoolProvider(ScvmmPlugin plugin, MorpheusContext context) {
        super()
        this.@plugin = plugin
        this.@context = context
        this.apiService = new ScvmmApiService(context)
    }

    @Override
    ServiceResponse verifyNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
        ServiceResponse.success(poolServer)
    }

    @Override
    ServiceResponse createNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
        ServiceResponse.success(poolServer)
    }

    @Override
    ServiceResponse updateNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
        ServiceResponse.success()
    }

    @Override
    void refresh(NetworkPoolServer poolServer) {
    }

    @Override
    ServiceResponse initializeNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
        ServiceResponse.success(poolServer)
    }

    @Override
    ServiceResponse createHostRecord(NetworkPoolServer poolServer, NetworkPool networkPool, NetworkPoolIp networkPoolIp, NetworkDomain domain, Boolean createARecord, Boolean createPtrRecord) {
        synchronized (poolMutex) {
            try {
                Cloud cloud = context.async.cloud.find(new DataQuery()
                        .withFilter('id',networkPool.refId)
                        .withFilter('type', networkPool.refType)
                ).blockingGet()
                def controller = apiService.getScvmmController(cloud)
                // Get SCVMM options
                def scvmmOpts = apiService.getScvmmZoneAndHypervisorOpts(context, cloud, controller)
                def reserveResults = apiService.reserveIPAddress(scvmmOpts, networkPool.externalId)

                if (reserveResults.success == true && reserveResults.ipAddress) {
                    def ipAddress = reserveResults.ipAddress
                    def newIp = ipAddress.Address

                    // Populate NetworkPoolIp fields
                    networkPoolIp.ipAddress = newIp
                    networkPoolIp.staticIp = true
                    networkPoolIp.externalId = "${ipAddress.ID}"
                    def ipRange = networkPool.ipRanges?.size() > 0 ? networkPool.ipRanges.first() : null
                    networkPoolIp.networkPoolRange = ipRange
                    networkPoolIp.gatewayAddress = networkPool.gateway
                    networkPoolIp.subnetMask = networkPool.netmask // check it
                    networkPoolIp.dnsServer = networkPool.dnsServers?.size() > 0 ? networkPool.dnsServers.first() : null
                    networkPoolIp.interfaceName = 'eth0' // check it
                    networkPoolIp.startDate = new Date()

                    context.async.network.pool.poolIp.save(networkPoolIp)
                    context.async.network.pool.poolIp.create(networkPool, [networkPoolIp])
                    context.async.network.pool.save(networkPool)

                    return ServiceResponse.success(['poolIp': networkPoolIp, 'ipAddress': newIp, 'poolType': 'static'])
                } else {
                    return ServiceResponse.error("Failed to reserve IP address")
                }
            } catch (e) {
                log.error("createHostRecord error: ${e}", e)
                return ServiceResponse.error(e.message)
            }
        }
    }

    @Override
    ServiceResponse updateHostRecord(NetworkPoolServer poolServer, NetworkPool networkPool, NetworkPoolIp networkPoolIp) {
        ServiceResponse.success()
    }

    @Override
    ServiceResponse deleteHostRecord(NetworkPool networkPool, NetworkPoolIp poolIp, Boolean deleteAssociatedRecords) {
        synchronized(poolMutex) {
            try {
                def results = [:]
                if(poolIp.domain && poolIp.externalId) {
                    Cloud cloud = context.async.cloud.find(new DataQuery()
                            .withFilter('id',networkPool.refId)
                            .withFilter('type', networkPool.refType)
                    ).blockingGet()
                    def controller = apiService.getScvmmController(cloud)
                    def scvmmOpts = apiService.getScvmmZoneAndHypervisorOpts(context, cloud, controller)
                    results = apiService.releaseIPAddress(scvmmOpts, networkPool.id, poolIp.id)
                }
                if(results.success == true) {
                    context.async.network.pool.poolIp.remove(networkPool.id, [poolIp])
                    context.async.network.pool.poolIp.remove(poolIp)
                    context.async.network.pool.save(networkPool)
                    return ServiceResponse.success()
                }
            } catch(e) {
                log.error("leasePoolAddress error: ${e}", e)
                return ServiceResponse.error(e.message)
            }
        }
    }

    @Override
    Collection<NetworkPoolType> getNetworkPoolTypes() {
        Collection<NetworkPoolType> networkPoolType = []
        networkPoolType << new NetworkPoolType(
                code: 'scvmm',
                name: 'SCVMM',
                creatable: false,
                description: 'SCVMM network ip pool',
                rangeSupportsCidr: false,
                hostRecordEditable: false,
        )
        return networkPoolType
    }

    @Override
    List<OptionType> getIntegrationOptionTypes() {
        []
    }

    @Override
    Icon getIcon() {
        return new Icon(path: 'scvmm.svg', darkPath: 'scvmm-dark.svg')
    }

    @Override
    MorpheusContext getMorpheus() {
        this.morpheus
    }

    @Override
    Plugin getPlugin() {
        this.@plugin
    }

    @Override
    String getCode() {
        return NETWORK_POOL_PROVIDER_CODE
    }

    @Override
    String getName() {
        return NETWORK_POOL_PROVIDER_NAME
    }

    /**
     * {@inheritDoc}
     */
    @Override
    Boolean getCreatable() {
        return false
    }
}