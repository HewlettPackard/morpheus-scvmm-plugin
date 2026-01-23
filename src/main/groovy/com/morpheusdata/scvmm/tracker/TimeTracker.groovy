package com.morpheusdata.scvmm.tracker

import com.morpheusdata.scvmm.common.Constants
import groovy.transform.CompileStatic

/**
 * Custom class to track time for activities and workflows.
 */
@CompileStatic
class TimeTracker {
    // Map to hold start and end times for both activities and workflows
    private final Map<String, Long> timeMap

    /**
     * Constructor for TimeTracker.
     */
    TimeTracker() {
        timeMap = [:]
    }

    /**
     * Constructor for TimeTracker.
     *
     * @param activityName The name of the activity to track
     */
    TimeTracker(String activityName) {
        timeMap = [:]
        start(activityName)
    }

    /**
     * Start tracking time for a given activity under a workflow.
     *
     * @param activity The name of the activity to track
     * @return The TimeTracker object
     */
    TimeTracker start(String activity) {
        timeMap[activity] = System.currentTimeMillis() // Start time for the activity
        return this
    }

    /**
     * End tracking time for a given activity under a workflow.
     *
     * @param activity The name of the activity to track
     * @return The TimeTracker object
     */
    TimeTracker end(String activity) {
        if (timeMap.containsKey(activity)) {
            timeMap[activity] = System.currentTimeMillis() - timeMap[activity]
        }
        return this
    }

    /**
     * Get the overall time taken for a given activity.
     *
     * @param activity The name of the activity to track
     * @return The overall time taken for the activity
     */
    Long getOverallTime(String activity) {
        return timeMap[activity]
    }

    /**
     * Get the overall time taken for all activities.
     *
     * @return The overall time taken for all activities
     */
    @Override
    String toString() {
        return timeMap.collect { key, value -> "${key}=${value / Constants.SECOND}s" }.join(", ")
    }

    /**
     * Get the elapsed time for a given activity.
     *
     * @param activity The name of the activity to track
     * @return The elapsed time for the activity
     */
    Long getElapsedTime(String activity) {
        return timeMap[activity] / Constants.SECOND as Long
    }
}
