// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.util

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import groovy.transform.CompileStatic

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

@CompileStatic
class MorpheusUtil {
    public static final String MORPHEUS_CONTEXT = "Morpheus context"
    public static final String COMPUTE_SERVER = "computeServer"
    public static final String COMPUTE_SERVER_ID = "computeServer.id"
    public static final String OPERATION = "operation"

    private static LogInterface log = LogWrapper.instance

    // Serialize operations per computeServer.id instead of globally.
    private static final ConcurrentHashMap<Long, ReentrantLock> COMPUTE_SERVER_LOCKS = new ConcurrentHashMap<>()

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

    // TODO
    static <T> T withComputeServerAccessLock(
            MorpheusContext morpheusContext,
            ComputeServer computeServer,
            String operationName,
            Closure<T> operation
    ) {
        checkNotNull(morpheusContext, MORPHEUS_CONTEXT)
        checkNotNull(computeServer, COMPUTE_SERVER)
        checkNotNull(computeServer.id, COMPUTE_SERVER_ID)
        checkNotNull(operation, OPERATION)

        long lockAcquiredNs = 0
        boolean lockAcquired = false
        ReentrantLock lock = COMPUTE_SERVER_LOCKS.computeIfAbsent(computeServer.id) { new ReentrantLock() }
        try {
            lockAcquiredNs = System.nanoTime()
            lockAcquired = true // MIKEMIKE lock.tryLock()
            log.debug("Lock ${lockAcquired ? 'acquired' : 'not acquired'} for computeServer.id=${computeServer.id} " +
                    "during ${operationName}")
            if (!lockAcquired) {
                long waitStartNs = System.nanoTime()
                lock.lock()
                long waitedNs = System.nanoTime() - waitStartNs
                double waitedSeconds = waitedNs / 1_000_000_000d
                log.debug("Lock acquired for computeServer.id=${computeServer.id} during ${operationName} " +
                        "after waiting ${String.format('%.1f', waitedSeconds)} seconds")
                lockAcquiredNs = System.nanoTime()
                lockAcquired = true
            }

            return operation.call()
        } finally {
            long operationNs = System.nanoTime() - lockAcquiredNs
            double operationSeconds = operationNs / 1_000_000_000d
            log.debug("Releasing lock for computeServer.id=${computeServer.id} after ${operationName} " +
                    "took ${String.format('%.1f', operationSeconds)} seconds to execute")
//            if (lockAcquired) {
//                lock.unlock()
//            }
        }
    }

    /**
     * Checks if the input value is valid otherwise raises an exception.
     */
    static void checkNotNull(Object value, String variableName) throws IllegalArgumentException {
        if (value == null) {
            log.error("${variableName} cannot be null")
            throw new IllegalArgumentException(String.format("%s cannot be null", variableName))
        }

        if (value instanceof String && !value?.trim()) {
            log.error("${variableName} cannot be empty")
            throw new IllegalArgumentException(String.format("%s cannot be empty", variableName))
        }
    }
}
