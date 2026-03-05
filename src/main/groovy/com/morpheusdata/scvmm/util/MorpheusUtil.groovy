package com.morpheusdata.scvmm.util

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper

import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

class MorpheusUtil {
    private static LogInterface log = LogWrapper.instance

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

    static ServiceResponse copyToServer(
            MorpheusContext morpheusContext,
            ComputeServer server,
            String fileName,
            String filePath,
            InputStream sourceStream,
            Long contentLength
    ) {
        log.debug("Initiating file copy to server, server=${server.name} (${server.id})" +
                ", fileName=${fileName}, filePath=${filePath}, contentLength=${contentLength}")

        // Use AtomicReference for thread-safe communication between threads
        AtomicReference<ServiceResponse> fileResultsRef = new AtomicReference<>()
        AtomicReference<Exception> backgroundExceptionRef = new AtomicReference<>()

        long startTime = System.currentTimeMillis()

        Thread copyThread = new Thread({
            try {
                fileResultsRef.set(
                        morpheusContext.services.fileCopy.copyToServer(
                                server,
                                fileName,
                                filePath,
                                sourceStream,
                                contentLength
                        )
                )
            } catch (Exception ex) {
                backgroundExceptionRef.set(ex)
            } finally {
                // Ensure InputStream is closed after use to prevent resource leak
                try {
                    sourceStream?.close()
                } catch (Exception closeEx) {
                    log.warn("Error closing InputStream after file copy: ${closeEx.message}", closeEx)
                }
            }
        } as Runnable)

        // Start the copyToServer operation in the background thread
        copyThread.start()

        long elapsedMs
        while (copyThread.isAlive()) {
            try {
                // Wait up to 10 seconds for the copy thread to complete before logging progress
                copyThread.join(10000)
            } catch (InterruptedException ie) {
                log.warn("copyToServer wait interrupted: " + ie.message)
            }
            elapsedMs = System.currentTimeMillis() - startTime
            if (copyThread.isAlive()) {
                log.info("copyToServer still active after ${formatElapsedTime(elapsedMs)}, filePath=${filePath}, " +
                        "contentLength=${contentLength}")
            }
        }

        // Final elapsed time calculation after thread completion
        elapsedMs = System.currentTimeMillis() - startTime

        // Handle any exception that occurred in the background thread
        Exception backgroundException = backgroundExceptionRef.get()
        if (backgroundException) {
            log.error("Exception during file copy to server: ${backgroundException.message}", backgroundException)
            throw backgroundException
        }

        log.info("copyToServer completed in ${formatElapsedTime(elapsedMs)}, filePath=${filePath}")

        // Return the result to the caller
        return fileResultsRef.get()
    }


    /**
     * Helper method to format elapsed time in a human-readable format (e.g., "1m 30s 500ms"). This improves log
     * readability compared to raw milliseconds, especially for longer operations.
     * @param elapsedMs Elapsed time in milliseconds
     * @return Formatted string representing the elapsed time in minutes, seconds, and milliseconds
     */
    static String formatElapsedTime(long elapsedMs) {
        Duration d = Duration.ofMillis(elapsedMs)
        long m = d.toMinutes()
        long s = d.toSecondsPart()
        long ms = d.toMillisPart()
        return "${m}m ${s}s ${ms}ms"
    }
}
