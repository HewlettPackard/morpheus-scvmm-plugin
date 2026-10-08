// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.helper

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.NetworkPoolServer
import com.morpheusdata.model.NetworkPoolServerType
import com.morpheusdata.scvmm.ScvmmConstants
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.PrefixedLoggerFactory

/**
 * Manages the hidden per-cloud {@link NetworkPoolServer} that SCVMM static IP pools hang off.
 *
 * The appliance dispatches IP lease/release calls to a plugin {@code IPAMProvider} by way of
 * {@code pool.poolServer.type.code}, so every SCVMM pool must be parented to a pool server whose type code is the
 * SCVMM IPAM provider code. SCVMM is not a standalone IPAM integration, so the server is created invisibly per cloud
 * and torn down with it.
 */
class NetworkPoolServerHelper {

    private final MorpheusContext morpheusContext
    private LogInterface log = PrefixedLoggerFactory.getLogger(NetworkPoolServerHelper)

    NetworkPoolServerHelper(MorpheusContext morpheusContext) {
        this.morpheusContext = morpheusContext
    }

    static String getInternalId(Cloud cloud) {
        return "${ScvmmConstants.IPAM_PROVIDER_CODE}.${cloud.id}".toString()
    }

    static String getPoolCategory(Cloud cloud) {
        return "scvmm.ipPool.${cloud.id}".toString()
    }

    /** Extracts the cloud id from a pool server created by {@link #ensurePoolServer}, or null for any other server. */
    static Long getCloudId(NetworkPoolServer poolServer) {
        String prefix = "${ScvmmConstants.IPAM_PROVIDER_CODE}."
        String internalId = poolServer?.internalId
        if (!internalId?.startsWith(prefix)) {
            return null
        }
        String id = internalId.substring(prefix.length())
        return id.isLong() ? id.toLong() : null
    }

    NetworkPoolServer findPoolServer(Cloud cloud) {
        return morpheusContext.services.network.poolServer.find(new DataQuery().withFilters(
                new DataFilter('internalId', getInternalId(cloud)),
                new DataFilter('account.id', cloud.account.id)
        ))
    }

    NetworkPoolServer ensurePoolServer(Cloud cloud) {
        NetworkPoolServer poolServer = findPoolServer(cloud)
        if (!poolServer) {
            log.info("Creating hidden SCVMM network pool server for cloud ${cloud.id}")
            poolServer = morpheusContext.services.network.poolServer.create(new NetworkPoolServer(
                    name: "SCVMM IP Pools - ${cloud.name}".toString(),
                    internalId: getInternalId(cloud),
                    type: new NetworkPoolServerType(code: ScvmmConstants.IPAM_PROVIDER_CODE),
                    account: cloud.account,
                    enabled: true,
                    visible: false
            ))
        }
        return poolServer
    }

    /** Binds a pool to its pool server. Returns true when the pool was changed and needs saving. */
    static boolean bindPool(NetworkPool pool, NetworkPoolServer poolServer) {
        if (!poolServer?.id) {
            return false
        }
        String parentId = poolServer.id.toString()
        if (pool.poolServer?.id == poolServer.id && pool.parentType == 'NetworkPoolServer' && pool.parentId == parentId) {
            return false
        }
        pool.poolServer = poolServer
        pool.parentType = 'NetworkPoolServer'
        pool.parentId = parentId
        return true
    }

    /** Removes the cloud's pool server together with the SCVMM pools parented to it. */
    void removePoolServer(Cloud cloud) {
        NetworkPoolServer poolServer = findPoolServer(cloud)
        if (!poolServer) {
            return
        }
        def pools = morpheusContext.async.cloud.network.pool.listIdentityProjections(new DataQuery()
                .withFilter('account.id', cloud.account.id)
                .withFilter('category', getPoolCategory(cloud))).toList().blockingGet()
        if (pools) {
            morpheusContext.async.cloud.network.pool.remove(poolServer.id, pools).blockingGet()
        }
        morpheusContext.async.network.poolServer.remove([poolServer]).blockingGet()
    }
}
