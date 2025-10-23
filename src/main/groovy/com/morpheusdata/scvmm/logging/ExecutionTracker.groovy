package com.morpheusdata.scvmm.logging

import com.morpheusdata.model.TaskResult
import com.morpheusdata.scvmm.common.Constants
import com.morpheusdata.scvmm.tracker.TimeTracker
import groovy.transform.CompileStatic

import java.time.Duration

/**
 * Error-aware logging utility for SCVMM plugin that tracks execution time
 * and provides error handling capabilities.
 */
@CompileStatic
class ExecutionTracker {
    static final Integer SLOW_LOG_MAX_TIME = 10 * Constants.SECOND

    static void printLog(TimeTracker tracker, String activityName, Integer customMaxTimeout) {
        def timeout = customMaxTimeout != 0 ? customMaxTimeout : SLOW_LOG_MAX_TIME
        if (customMaxTimeout < 0 || tracker.getOverallTime(activityName) > timeout) {
            LogWrapper.instance.info("Activity: { ${activityName} } took " +
                    "${tracker.getOverallTime(activityName) / Constants.SECOND}s to complete")
        } else {
            LogWrapper.instance.debug("Activity: { ${activityName} } execution completed in " +
                    "${tracker.getOverallTime(activityName) / Constants.SECOND}s")
        }
    }

    static <T> T execute(String command, Closure<T> action, Integer slowLoggerTimeout) {
        LogWrapper.instance.debug("Executing ${command}")
        TimeTracker tracker = new TimeTracker(command)
        T result

        try {
            result = action.call()

            // Check if result has error/success properties safely with CompileStatic
            if (result != null) {
                // Use metaClass for property checking in CompileStatic mode
                def metaResult = (TaskResult) result

                // Check for success property that is false
                if (!metaResult?.success || metaResult?.error != null) {
                    String errorMsg = metaResult?.error ? metaResult?.error?.toString() : "Unknown error"
                    LogWrapper.instance.error("Error in ${command}: ${errorMsg}")
                    return result
                }
            }

            return result
        } catch (Exception e) {
            // Log the exception
            LogWrapper.instance.error("Exception in ${command}: ${e.message}", e)
            // Rethrow the exception
            throw e
        } finally {
            printLog(tracker.end(command), command, slowLoggerTimeout)
        }
    }
}