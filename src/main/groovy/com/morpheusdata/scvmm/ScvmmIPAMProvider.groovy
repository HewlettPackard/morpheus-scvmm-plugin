// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.IPAMProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkDomain
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.NetworkPoolIp
import com.morpheusdata.model.NetworkPoolServer
import com.morpheusdata.model.NetworkPoolType
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.PrefixedLoggerFactory

/**
 * Owns the SCVMM {@link NetworkPoolType} and brokers static IP address leases against SCVMM static IP address pools.
 *
 * SCVMM pools are discovered per cloud by {@link com.morpheusdata.scvmm.sync.IpPoolsSync} rather than through a
 * standalone IPAM integration, so the {@code NetworkPoolServer} lifecycle methods are unsupported and the cloud is
 * resolved from the pool's {@code refId} instead.
 */
class ScvmmIPAMProvider implements IPAMProvider {

    private static final String NOT_AN_INTEGRATION =
            'SCVMM IP pools are discovered during cloud sync and cannot be managed as a standalone IPAM integration'

    ScvmmPlugin plugin
    MorpheusContext morpheusContext
    ScvmmApiService apiService
    private LogInterface log = PrefixedLoggerFactory.getLogger(ScvmmIPAMProvider)

    ScvmmIPAMProvider(ScvmmPlugin plugin, MorpheusContext context) {
        this.plugin = plugin
        this.morpheusContext = context
        this.apiService = new ScvmmApiService(context)
    }

    @Override
    MorpheusContext getMorpheus() {
        return this.morpheusContext
    }

    @Override
    Plugin getPlugin() {
        return this.plugin
    }

    @Override
    String getCode() {
        return ScvmmConstants.IPAM_PROVIDER_CODE
    }

    @Override
    String getName() {
        return 'SCVMM IPAM'
    }

    @Override
    Boolean getCreatable() {
        return false
    }

    @Override
    Collection<NetworkPoolType> getNetworkPoolTypes() {
        return [
                new NetworkPoolType(
                        code: ScvmmConstants.NETWORK_POOL_TYPE_CODE,
                        name: 'SCVMM',
                        description: 'SCVMM network ip pool',
                        creatable: false,
                        rangeSupportsCidr: false,
                        hostRecordEditable: false
                )
        ]
    }

    @Override
    List<OptionType> getIntegrationOptionTypes() {
        return []
    }

    @Override
    Icon getIcon() {
        return null
    }

    @Override
    ServiceResponse verifyNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
        return error(NOT_AN_INTEGRATION)
    }

    @Override
    ServiceResponse createNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
        return error(NOT_AN_INTEGRATION)
    }

    @Override
    ServiceResponse updateNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
        return error(NOT_AN_INTEGRATION)
    }

    @Override
    ServiceResponse initializeNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
        return error(NOT_AN_INTEGRATION)
    }

    @Override
    void refresh(NetworkPoolServer poolServer) {
        // pools are refreshed by IpPoolsSync on the cloud sync cycle
    }

    /**
     * Grants an IP address from the SCVMM static IP address pool backing {@code networkPool} and stamps the result onto
     * {@code networkPoolIp}. SCVMM performs no DNS registration, so the A/PTR record flags are ignored.
     */
    @Override
    ServiceResponse createHostRecord(NetworkPoolServer poolServer, NetworkPool networkPool, NetworkPoolIp networkPoolIp,
                                     NetworkDomain domain, Boolean createARecord, Boolean createPtrRecord) {
        log.debug("createHostRecord: pool ${networkPool?.externalId}")
        try {
            def scvmmOpts = getScvmmOpts(networkPool)
            if (!scvmmOpts) {
                return error("Unable to resolve an SCVMM controller for network pool ${networkPool?.id}")
            }
            def results = apiService.reserveIPAddress(scvmmOpts, networkPool.externalId)
            if (!results.success || !results.ipAddress) {
                return error(results.msg ?:
                        "Unable to reserve an IP address from SCVMM pool ${networkPool.externalId}")
            }
            networkPoolIp.ipAddress = results.ipAddress.Address
            networkPoolIp.externalId = results.ipAddress.ID
            networkPoolIp.staticIp = true
            return ServiceResponse.success(networkPoolIp)
        } catch (e) {
            log.error("createHostRecord error: ${e}", e)
            return error("Error reserving an IP address from SCVMM: ${e.message}")
        }
    }

    @Override
    ServiceResponse updateHostRecord(NetworkPoolServer poolServer, NetworkPool networkPool, NetworkPoolIp networkPoolIp) {
        return error('SCVMM IP address reservations cannot be edited in place')
    }

    /**
     * Revokes a previously granted SCVMM IP address. An address with no {@code externalId} was never granted in SCVMM,
     * so releasing it is a no-op rather than an error.
     */
    @Override
    ServiceResponse deleteHostRecord(NetworkPool networkPool, NetworkPoolIp poolIp, Boolean deleteAssociatedRecords) {
        log.debug("deleteHostRecord: pool ${networkPool?.externalId} ip ${poolIp?.externalId}")
        try {
            if (!poolIp?.externalId) {
                return ServiceResponse.success()
            }
            def scvmmOpts = getScvmmOpts(networkPool)
            if (!scvmmOpts) {
                return error("Unable to resolve an SCVMM controller for network pool ${networkPool?.id}")
            }
            def results = apiService.releaseIPAddress(scvmmOpts, networkPool.externalId, poolIp.externalId)
            if (!results.success) {
                return error(results.msg ?:
                        "Unable to release IP address ${poolIp.ipAddress} back to SCVMM pool ${networkPool.externalId}")
            }
            return ServiceResponse.success()
        } catch (e) {
            log.error("deleteHostRecord error: ${e}", e)
            return error("Error releasing an IP address back to SCVMM: ${e.message}")
        }
    }

    // ServiceResponse.error(String) only populates the errors map; callers in the appliance read msg
    private static ServiceResponse error(String msg) {
        return ServiceResponse.error(msg, [:])
    }

    protected Map getScvmmOpts(NetworkPool networkPool) {
        if (networkPool?.refType != 'ComputeZone' || !networkPool.refId) {
            return null
        }
        Cloud cloud = morpheusContext.services.cloud.get(networkPool.refId.toLong())
        ComputeServer controller = cloud ? getScvmmController(cloud) : null
        if (!controller) {
            return null
        }
        return apiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, controller) as Map
    }

    protected ComputeServer getScvmmController(Cloud cloud) {
        def sharedControllerId = cloud.getConfigProperty('sharedController')
        ComputeServer sharedController = sharedControllerId ?
                morpheusContext.services.computeServer.get(sharedControllerId.toLong()) : null
        if (sharedController) {
            return sharedController
        }
        return morpheusContext.services.computeServer.find(new DataQuery()
                .withFilter('zone.id', cloud.id)
                .withFilter('computeServerType.code', ScvmmConstants.CONTROLLER_SERVER_TYPE_CODE)
                .withJoin('computeServerType'))
    }
}
