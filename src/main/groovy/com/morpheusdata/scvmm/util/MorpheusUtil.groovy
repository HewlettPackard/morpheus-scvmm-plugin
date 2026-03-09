// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.util

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import groovy.transform.CompileDynamic

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

@CompileDynamic
class MorpheusUtil {
    @SuppressWarnings('FieldName')
    private static final LogInterface log = LogWrapper.instance

    /**
     * Test hook for observing watchdog thread lifecycle in copyToServer.
     * Only intended for use by unit tests to verify thread creation, start, and termination.
     * Production code should leave this null.
     */
    @SuppressWarnings('PropertyName')
    private static Closure _testWatchdogObserver = null

    // Watchdog thread timing constants
    // Do not declare WATCHDOG_INITIAL_SLEEP_MS as final so that it can be modified by unit tests
    @SuppressWarnings('PrivateFieldCouldBeFinal')
    private static long WATCHDOG_INITIAL_SLEEP_MS = 2000L
    private static final long WATCHDOG_MAX_SLEEP_MS = 30000L
    private static final long WATCHDOG_JOIN_TIMEOUT_MS = 5000L
    private static final long MAX_OPERATION_TIME_MS = 60 * 60000L

    static ComputeServer saveAndGetMorpheusServer(
            MorpheusContext context,
            ComputeServer server,
            Boolean fullReload = false
    ) {
        def saveResult = context.async.computeServer.bulkSave([server]).blockingGet()
        def updatedServer
        if (saveResult.success == true) {
            if (fullReload) {
                updatedServer = getMorpheusServer(context, server.id)
            } else {
                updatedServer = saveResult.persistedItems.find { ComputeServer it -> it.id == server.id }
            }
        } else {
            updatedServer = saveResult.failedItems.find { ComputeServer it -> it.id == server.id }
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
     * Utility method to copy a file to a ComputeServer with enhanced logging and error handling. This method starts a
     * watchdog thread that logs the status of the copy operation every 10 seconds if it is still running. It also
     * measures the elapsed time for the copy operation and logs it upon completion or if an exception occurs. If an
     * exception is thrown during the copy operation, it is logged with the elapsed time and rethrown to ensure the
     * caller is aware of the failure. The watchdog thread is set as a daemon so it won't prevent JVM shutdown if
     * something goes wrong, and it is signaled to stop once the copy operation completes or fails.
     *
     * @param morpheusContext The MorpheusContext instance
     * @param server The target server
     * @param fileName name of the copied file for the file copy request URL.
     * @param filePath path on the server, including the file name (/some/path/file.txt), to place the file copy.
     * @param sourceStream source {@link InputStream} to copy to the server. It is the caller's responsibility to
     *        ensure this stream is valid and to close it after the copy operation completes.
     * @param contentLength size of the file to be copied
     * @return {@link ServiceResponse} containing the success status of the copy operation
     */
    @SuppressWarnings('CatchException')
    @SuppressWarnings('ParameterCount')
    static ServiceResponse copyToServer(
            MorpheusContext morpheusContext,
            ComputeServer server,
            String fileName,
            String filePath,
            InputStream sourceStream,
            Long contentLength
    ) {
        log.debug("Initiating copyToServer, server=${server?.name}(${server?.id}), fileName=${fileName}, " +
                "filePath=${filePath}, contentLength=${contentLength}")

        AtomicBoolean isRunning = new AtomicBoolean(true)
        CountDownLatch watchdogStarted = new CountDownLatch(1)  // Used by tests to verify thread started
        long startTime = System.currentTimeMillis()

        // Create watchdog thread to log status periodically if the copy operation is still running
        Thread watchdog = new Thread({
            watchdogStarted.countDown()
            try {
                // Double the sleep interval each time up to a maximum of 30 seconds
                for (long sleepTime = WATCHDOG_INITIAL_SLEEP_MS; isRunning.get();
                     sleepTime = Math.min(sleepTime * 2, WATCHDOG_MAX_SLEEP_MS)) {
                    if (System.currentTimeMillis() - startTime >= MAX_OPERATION_TIME_MS) {
                        log.debug('copyToServer has been running for over an hour, stopping watchdog to prevent ' +
                                'infinite logging')
                        return
                    }
                    Thread.sleep(sleepTime)
                    log.debug("copyToServer still active after ${getElapsedTime(startTime)}")
                }
            } catch (InterruptedException ignored) {
                // Thread was interrupted, exit gracefully
                log.debug("copyToServer watchdog thread signaled to exit")
            } catch (Exception ex) {
                log.warn("copyToServer unexpected exception in watchdog thread: ${ex.message}", ex)
            }
        } as Runnable)

        // Set as daemon so it won't prevent JVM shutdown if something goes wrong
        watchdog.daemon = true

        try {
            // Start the watchdog thread before initiating the copy operation
            _testWatchdogObserver?.call(watchdog, watchdogStarted)
            watchdog.start()

            ServiceResponse response = morpheusContext.services.fileCopy.copyToServer(
                    server,
                    fileName,
                    filePath,
                    sourceStream,
                    contentLength
            )
            log.debug("copyToServer completed in ${getElapsedTime(startTime)}, " +
                    "response=[success=${response?.success}, msg=${response?.msg}, error=${response?.error}]")
            return response
        } catch (Exception ex) {
            // Log the exception with elapsed time and rethrow it to ensure the caller is aware of the failure
            log.error("Exception occurred while in copyToServer, elapsedTime=${getElapsedTime(startTime)}: " +
                    "${ex.message}", ex)
            throw ex
        } finally {
            // Signal the watchdog thread to stop and wait for it to exit
            isRunning.set(false)
            log.debug("Signaled copyToServer watchdog thread to stop, waiting for it to exit...")
            watchdog.interrupt()
            watchdog.join(WATCHDOG_JOIN_TIMEOUT_MS)
            if (watchdog.alive) {
                log.warn("copyToServer watchdog thread did not exit within timeout")
            } else {
                log.debug("copyToServer watchdog thread exited successfully")
            }
        }
    }

    /**
     * Helper method to calculate and format elapsed time since the provided start time. The format is "MM:SS.mmm"
     * (minutes:seconds.milliseconds).
     * @param startTime Start time in milliseconds (e.g., from System.currentTimeMillis())
     * @return Formatted string representing the elapsed time since startTime in "MM:SS.mmm" format
     */
    @SuppressWarnings('DuplicateNumberLiteral')
    private static String getElapsedTime(long startTime) {
        long elapsedMs = System.currentTimeMillis() - startTime
        long minutes = elapsedMs / 60000 as long
        long seconds = (elapsedMs % 60000) / 1000 as long
        long milliseconds = elapsedMs % 1000
        return String.format('%02d:%02d.%03d', minutes, seconds, milliseconds)
    }
}
