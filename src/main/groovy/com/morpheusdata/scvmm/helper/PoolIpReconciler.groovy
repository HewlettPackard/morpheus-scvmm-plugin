// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.helper

import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.NetworkPoolIp
import com.morpheusdata.model.projection.NetworkPoolIpIdentityProjection
import groovy.transform.CompileStatic

/**
 * Plans how Morpheus {@link NetworkPoolIp} records for one SCVMM static IP pool should change so that they mirror the
 * grants SCVMM actually holds ({@code Get-SCIPAddress -StaticIPAddressPool}).
 * <p>
 * SCVMM is the source of truth for allocations. Morpheus can lose its records (the appliance deletes the plugin's
 * pool server, its pools and their IPs whenever the plugin is upgraded) and SCVMM can hold grants Morpheus never made
 * (aborted provisions, grants made outside Morpheus). Reconciling on every {@code IpPoolsSync} means an upgrade is a
 * non-event for existing VMs and stray grants become visible in the pool instead of silently consuming addresses.
 * <p>
 * Pure planning; the caller persists the result.
 */
@CompileStatic
class PoolIpReconciler {

    static final String STATE_ASSIGNED = 'Assigned'
    static final String IP_TYPE_ASSIGNED = 'assigned'
    static final String IP_TYPE_RESERVED = 'reserved'
    static final String DESCRIPTION_PREFIX = 'morpheus:'

    /** Where a grant could be attributed to: the Morpheus server/interface that owns the address, if any. */
    @CompileStatic
    static class Owner {
        ComputeServer server
        ComputeServerInterface iface
    }

    @CompileStatic
    static class Plan {
        List<NetworkPoolIp> adds = []
        List<NetworkPoolIp> updates = []
        List<NetworkPoolIpIdentityProjection> removes = []
        /** externalIds of grants matched to existing rows (for logging / tests) */
        Set<String> matched = [] as Set

        boolean isEmpty() { adds.empty && updates.empty && removes.empty }
    }

    /**
     * Index of the cloud's interfaces so grants can be attributed: by SCVMM adapter id and by every address the
     * interface currently reports. Built once per sync.
     */
    @CompileStatic
    static class OwnerIndex {
        final Map<String, Owner> byAdapterId = [:]
        final Map<String, Owner> byIp = [:]

        OwnerIndex(Collection<ComputeServer> servers) {
            servers?.each { ComputeServer server ->
                server.interfaces?.each { ComputeServerInterface iface ->
                    Owner owner = new Owner(server: server, iface: iface)
                    if (iface.externalId) {
                        byAdapterId[iface.externalId.toString().toLowerCase()] = owner
                    }
                    // addresses is the source of truth for an interface's IPs (ipAddress is derived from it)
                    List<String> ips = (iface.addresses?.collect { it.address?.toString() } ?: []) as List<String>
                    if (iface.publicIpAddress) ips << iface.publicIpAddress
                    ips.findAll { it }.each { String ip -> byIp.putIfAbsent(ip.trim(), owner) }
                }
            }
        }

        Owner find(Map grant) {
            def adapterId = grant?.AllocatedToObjectID?.toString()?.toLowerCase()
            Owner owner = adapterId ? byAdapterId[adapterId] : null
            if (!owner && grant?.Address) {
                owner = byIp[grant.Address.toString().trim()]
            }
            return owner
        }
    }

    /**
     * @param pool      the Morpheus pool (must have id, gateway/netmask/dnsServers as synced)
     * @param existing  full NetworkPoolIp rows currently attached to the pool
     * @param grants    SCVMM grants for this pool from {@code listPoolIPAddresses} (maps with ID/Address/State/...)
     * @param owners    attribution index for the cloud
     */
    static Plan plan(NetworkPool pool, Collection<NetworkPoolIp> existing, Collection<Map> grants, OwnerIndex owners) {
        Plan plan = new Plan()
        List<Map> liveGrants = (grants ?: []).findAll { Map g -> g?.ID && g?.Address } as List<Map>
        Map<String, Map> grantsById = [:]
        Map<String, Map> grantsByIp = [:]
        liveGrants.each { Map g ->
            grantsById[g.ID.toString().toLowerCase()] = g
            grantsByIp[g.Address.toString().trim()] = g
        }

        Set<String> claimedGrantIds = [] as Set
        (existing ?: []).each { NetworkPoolIp row ->
            Map grant = row.externalId ? grantsById[row.externalId.toString().toLowerCase()] : null
            if (!grant && row.ipAddress) {
                grant = grantsByIp[row.ipAddress.toString().trim()]
            }
            if (grant) {
                if (claimedGrantIds.contains(grant.ID.toString().toLowerCase())) {
                    // two Morpheus rows for one SCVMM grant: keep the first, drop the duplicate
                    plan.removes << toProjection(row)
                    return
                }
                claimedGrantIds << grant.ID.toString().toLowerCase()
                plan.matched << grant.ID.toString()
                if (refresh(row, grant, owners?.find(grant))) {
                    plan.updates << row
                }
            } else if (row.externalId) {
                // Morpheus still thinks it holds a grant SCVMM no longer has
                plan.removes << toProjection(row)
            }
            // rows without externalId and without a matching grant are left alone: they are either in-flight
            // reservations the appliance has not handed to createHostRecord yet, or records we did not create
        }

        liveGrants.each { Map g ->
            String key = g.ID.toString().toLowerCase()
            if (claimedGrantIds.contains(key)) {
                return
            }
            plan.adds << build(pool, g, owners?.find(g))
        }
        return plan
    }

    /** Builds a NetworkPoolIp for a grant Morpheus has no record of. */
    static NetworkPoolIp build(NetworkPool pool, Map grant, Owner owner) {
        NetworkPoolIp ip = new NetworkPoolIp(
                networkPool: pool,
                ipAddress: grant.Address.toString().trim(),
                externalId: grant.ID.toString(),
                staticIp: true,
                gatewayAddress: pool.gateway,
                subnetMask: pool.netmask,
                dnsServer: pool.dnsServers ? pool.dnsServers.join(',') : null,
                startDate: new Date()
        )
        apply(ip, grant, owner)
        return ip
    }

    /** Re-applies SCVMM state / attribution to an existing row; true when something changed. */
    static boolean refresh(NetworkPoolIp row, Map grant, Owner owner) {
        boolean changed = false
        String address = grant.Address.toString().trim()
        if (row.ipAddress != address) { row.ipAddress = address; changed = true }
        if (row.externalId != grant.ID.toString()) { row.externalId = grant.ID.toString(); changed = true }
        if (row.staticIp != true) { row.staticIp = true; changed = true }
        // never move a lease between servers; only fill in attribution the row is missing
        if (!row.refId && owner?.server?.id) {
            apply(row, grant, owner)
            changed = true
        } else {
            String ipType = ipTypeFor(grant, row.refId ? new Owner(server: new ComputeServer(id: row.refId)) : owner)
            if (row.ipType != ipType) { row.ipType = ipType; changed = true }
        }
        return changed
    }

    private static void apply(NetworkPoolIp ip, Map grant, Owner owner) {
        ip.ipType = ipTypeFor(grant, owner)
        if (owner?.server) {
            ip.refType = 'ComputeServer'
            ip.refId = owner.server.id
            ip.hostname = owner.server.hostname ?: owner.server.name
            ip.fqdn = owner.server.externalFqdn ?: ip.hostname
        }
        if (owner?.iface) {
            ip.subRefId = owner.iface.id
            ip.interfaceName = owner.iface.name
            ip.macAddress = owner.iface.macAddress
        }
        if (!owner?.server) {
            def desc = grant.Description?.toString()
            ip.description = desc ?: 'Allocated in SCVMM; not attached to a Morpheus server'
        }
    }

    /** Assigned-to-a-known-server grants are {@code assigned}; everything else is {@code reserved}. */
    static String ipTypeFor(Map grant, Owner owner) {
        return owner?.server ? IP_TYPE_ASSIGNED : IP_TYPE_RESERVED
    }

    private static NetworkPoolIpIdentityProjection toProjection(NetworkPoolIp row) {
        return new NetworkPoolIpIdentityProjection(row.id, row.externalId, row.ipAddress)
    }
}
