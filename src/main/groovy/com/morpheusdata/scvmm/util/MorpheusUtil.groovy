package com.morpheusdata.scvmm.util

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.PrefixedLoggerFactory

class MorpheusUtil {
    private static LogInterface log = PrefixedLoggerFactory.getLogger(MorpheusUtil)

    static ComputeServer saveAndGetMorpheusServer(MorpheusContext context, ComputeServer server, Boolean fullReload = false) {
        def saveResult = context.async.computeServer.bulkSave([server]).blockingGet()
        def updatedServer
        if (saveResult.success == true) {
            if (fullReload) {
                updatedServer = getMorpheusServer(context, server.id)
            } else {
                updatedServer = saveResult.persistedItems.find { it.id == server.id }
            }
        } else {
            updatedServer = saveResult.failedItems.find { it.id == server.id }
            log.warn("Error saving server: ${server?.id}")
        }
        return updatedServer ?: server
    }

    static ComputeServer getMorpheusServer(MorpheusContext context, Long id) {
        return context.services.computeServer.find(
                new DataQuery().withFilter("id", id).withJoin("interfaces.network")
        )
    }

    /**
     * Resolves the VMConnect (vmrdp) console target for a VM's parent Hyper-V host.
     * Prefers the host's resolvable FQDN ({@code hostname}) and falls back to its
     * SCVMM display name only when no FQDN is available, since guacd must be able to
     * resolve the value via DNS.
     */
    static String getConsoleHost(ComputeServer host) {
        host?.hostname ?: host?.name
    }
}
