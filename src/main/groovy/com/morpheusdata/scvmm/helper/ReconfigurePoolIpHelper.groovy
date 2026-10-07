// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.helper

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.NetworkPoolIp
import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.PrefixedLoggerFactory

/**
 * Leases and releases SCVMM static IP pool addresses for NICs changed by a reconfigure (resize) operation.
 *
 * On the legacy {@code ResizeFacet} path the appliance hands the raw resize request to the plugin and performs no
 * IP pool bookkeeping of its own, so the plugin has to grant / revoke addresses in SCVMM itself and keep the Morpheus
 * {@link NetworkPoolIp} records in step. Records are written exactly the way the appliance writes them at provision
 * time ({@code refType=ComputeServer, refId=server.id, subRefId=interface.id}) so that instance teardown and the IP
 * pool views keep working unchanged.
 */
class ReconfigurePoolIpHelper {

    static final String REF_TYPE = 'ComputeServer'
    /** Type code of the SCVMM IPAM pool type (kept local so this helper does not depend on the IPAM provider). */
    static final String SCVMM_POOL_TYPE_CODE = 'scvmm'

    private final MorpheusContext context
    private final ScvmmApiService apiService
    private LogInterface log = PrefixedLoggerFactory.getLogger(ReconfigurePoolIpHelper)

    ReconfigurePoolIpHelper(MorpheusContext context, ScvmmApiService apiService) {
        this.context = context
        this.apiService = apiService
    }

    /**
     * Resolves the SCVMM static IP pool backing {@code network}, or null when the network has no pool or its pool is
     * not an SCVMM pool. The pool is reloaded so {@code type} and {@code externalId} are populated even when the
     * marshalled network only carries the pool id.
     */
    NetworkPool resolveScvmmPool(Network network) {
        Long poolId = network?.pool?.id
        if (poolId == null) {
            return null
        }
        NetworkPool pool = network.pool
        if (!pool.type?.code || !pool.externalId) {
            try {
                pool = context.services.network.pool.get(poolId) ?: pool
            } catch (e) {
                log.warn("resolveScvmmPool: unable to load pool ${poolId}: ${e.message}")
            }
        }
        if (pool?.type?.code != SCVMM_POOL_TYPE_CODE) {
            log.debug("resolveScvmmPool: pool ${poolId} on network ${network.id} is type ${pool?.type?.code}, not scvmm")
            return null
        }
        if (!pool.externalId) {
            log.warn("resolveScvmmPool: scvmm pool ${poolId} has no externalId, cannot lease from it")
            return null
        }
        return pool
    }

    /**
     * Decides whether a NIC landing on {@code network} should receive an address from its SCVMM pool. Mirrors the
     * appliance: a pool-backed network leases unless the user explicitly picked DHCP for the row.
     */
    static boolean shouldLeaseFromPool(NetworkPool pool, String ipMode) {
        return pool != null && ipMode != 'dhcp'
    }

    /**
     * Grants an address from {@code pool} in SCVMM (a specific one when {@code requestedIp} is given) and builds the
     * matching {@link NetworkPoolIp}. The record is <b>not</b> persisted yet; call {@link #persistLease} once the
     * address has been bound to the adapter, or {@link #revokeLease} to roll the grant back.
     * @return map with success and poolIp, or success=false and error
     */
    Map leasePoolIp(Map scvmmOpts, NetworkPool pool, Network network, ComputeServer server, ComputeServerInterface iface, String requestedIp = null) {
        Map rtn = [success: false]
        try {
            def results = apiService.reserveIPAddress(scvmmOpts, pool.externalId, requestedIp ?: null)
            def granted = results?.ipAddress
            if (!results?.success || !granted?.Address) {
                rtn.error = results?.msg ?: (requestedIp ?
                        "Unable to reserve IP address ${requestedIp} from SCVMM pool ${pool.name ?: pool.externalId}" :
                        "Unable to reserve an IP address from SCVMM pool ${pool.name ?: pool.externalId}")
                return rtn
            }
            rtn.poolIp = buildPoolIp(pool, network, server, iface, granted.Address.toString(), granted.ID?.toString())
            rtn.success = true
            log.info("leasePoolIp: granted ${rtn.poolIp.ipAddress} (scvmm id ${rtn.poolIp.externalId}) from pool ${pool.externalId} for NIC ${iface?.id ?: iface?.externalId}")
        } catch (e) {
            log.error("leasePoolIp error: ${e}", e)
            rtn.error = "Error reserving an IP address from SCVMM: ${e.message}"
        }
        return rtn
    }

    protected NetworkPoolIp buildPoolIp(NetworkPool pool, Network network, ComputeServer server, ComputeServerInterface iface, String ipAddress, String externalId) {
        String hostname = server.hostname ?: server.name
        def domainName = network?.networkDomain?.name ?: server.networkDomain?.name
        NetworkPoolIp poolIp = new NetworkPoolIp(
                networkPool: pool,
                ipAddress: ipAddress,
                externalId: externalId,
                staticIp: true,
                ipType: 'assigned',
                gatewayAddress: pool.gateway ?: network?.gateway,
                subnetMask: pool.netmask ?: network?.netmask,
                dnsServer: pool.dnsServers ? pool.dnsServers.join(',') : [network?.dnsPrimary, network?.dnsSecondary].findAll { it }.join(',') ?: null,
                interfaceName: iface?.name,
                macAddress: iface?.macAddress,
                hostname: hostname,
                fqdn: (domainName && hostname && !hostname.endsWith(".${domainName}")) ? "${hostname}.${domainName}".toString() : hostname,
                domainName: domainName,
                refType: REF_TYPE,
                refId: server.id,
                subRefId: iface?.id,
                startDate: new Date(),
                createdBy: server.createdBy
        )
        return poolIp
    }

    /** Persists a leased {@link NetworkPoolIp} under its pool. */
    boolean persistLease(NetworkPoolIp poolIp) {
        try {
            return context.async.network.pool.poolIp.create(poolIp.networkPool, [poolIp]).blockingGet() == true
        } catch (e) {
            log.error("persistLease: unable to save pool ip ${poolIp.ipAddress}: ${e}", e)
            return false
        }
    }

    /** Revokes a grant in SCVMM. Used both for rollback of an unbound lease and when removing an existing record. */
    Map revokeLease(Map scvmmOpts, NetworkPool pool, NetworkPoolIp poolIp) {
        Map rtn = [success: true]
        if (!poolIp?.externalId) {
            return rtn
        }
        try {
            def results = apiService.releaseIPAddress(scvmmOpts, pool?.externalId, poolIp.externalId)
            if (!results?.success) {
                rtn.success = false
                rtn.error = results?.msg ?: "Unable to release IP address ${poolIp.ipAddress} back to SCVMM pool ${pool?.externalId}"
            }
        } catch (e) {
            log.error("revokeLease error: ${e}", e)
            rtn.success = false
            rtn.error = "Error releasing an IP address back to SCVMM: ${e.message}"
        }
        return rtn
    }

    /** Finds every pool IP record the appliance or this helper has attached to {@code iface} on {@code server}. */
    List<NetworkPoolIp> findLeases(ComputeServer server, ComputeServerInterface iface) {
        if (server?.id == null || iface?.id == null) {
            return []
        }
        try {
            return context.services.network.pool.poolIp.list(new DataQuery().withFilters(
                    new DataFilter('refType', REF_TYPE),
                    new DataFilter('refId', server.id),
                    new DataFilter('subRefId', iface.id)
            )) ?: []
        } catch (e) {
            log.warn("findLeases: unable to query pool ips for server ${server.id} NIC ${iface.id}: ${e.message}")
            return []
        }
    }

    /**
     * Releases every SCVMM pool address held by {@code iface}: revokes the grant in SCVMM and removes the Morpheus
     * record. A record whose grant cannot be revoked is kept so that instance teardown can retry it later.
     * @return map with success and released (count); success=false and error when any revoke failed
     */
    Map releaseLeases(Map scvmmOpts, ComputeServer server, ComputeServerInterface iface) {
        return releaseLeases(scvmmOpts, findLeases(server, iface))
    }

    /** Same as {@link #releaseLeases(Map, ComputeServer, ComputeServerInterface)} for an already loaded list. */
    Map releaseLeases(Map scvmmOpts, List<NetworkPoolIp> leases) {
        Map rtn = [success: true, released: 0]
        for (NetworkPoolIp lease in (leases ?: [])) {
            NetworkPool pool = loadPool(lease)
            if (pool && pool.type?.code && pool.type.code != SCVMM_POOL_TYPE_CODE) {
                log.debug("releaseLeases: skipping non-scvmm pool ip ${lease.ipAddress} (pool type ${pool.type.code})")
                continue
            }
            Map revoke = revokeLease(scvmmOpts, pool, lease)
            if (!revoke.success) {
                log.warn("releaseLeases: ${revoke.error}; keeping Morpheus record ${lease.id} for a later retry")
                rtn.success = false
                rtn.error = revoke.error
                continue
            }
            try {
                context.async.network.pool.poolIp.remove(pool?.id ?: lease.networkPool?.id, [lease]).blockingGet()
                rtn.released++
                log.info("releaseLeases: released ${lease.ipAddress} from pool ${pool?.externalId} (NIC ${lease.subRefId})")
            } catch (e) {
                log.error("releaseLeases: revoked ${lease.ipAddress} in SCVMM but failed to remove Morpheus record ${lease.id}: ${e}", e)
                rtn.success = false
                rtn.error = "Released ${lease.ipAddress} in SCVMM but failed to remove its Morpheus record"
            }
        }
        return rtn
    }

    protected NetworkPool loadPool(NetworkPoolIp lease) {
        Long poolId = lease?.networkPool?.id
        if (poolId == null) {
            return lease?.networkPool
        }
        try {
            return context.services.network.pool.get(poolId) ?: lease.networkPool
        } catch (e) {
            log.warn("loadPool: unable to load pool ${poolId}: ${e.message}")
            return lease.networkPool
        }
    }
}
