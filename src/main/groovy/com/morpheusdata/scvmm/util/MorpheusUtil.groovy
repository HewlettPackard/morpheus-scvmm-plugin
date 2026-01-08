package com.morpheusdata.scvmm.util

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import groovy.transform.CompileStatic

@CompileStatic
class MorpheusUtil {
    private static final LogInterface LOG = LogWrapper.instance

    static ComputeServer saveAndGetMorpheusServer(MorpheusContext context, ComputeServer server,
                                                  Boolean fullReload = false) {
        def saveResult = context.async.computeServer.bulkSave([server]).blockingGet()
        def updatedServer
        if (saveResult.success == true) {
            if (fullReload) {
                updatedServer = getMorpheusServer(context, server.id)
            } else {
                updatedServer = saveResult.persistedItems.find { item -> item.id == server.id }
            }
        } else {
            updatedServer = saveResult.failedItems.find { item -> item.id == server.id }
            LOG.warn("Error saving server: ${server?.id}")
        }
        return updatedServer ?: server
    }

    static ComputeServer getMorpheusServer(MorpheusContext context, Long id) {
        return context.services.computeServer.find(
                new DataQuery().withFilter("id", id).withJoin("interfaces.network")
        )
    }
}
