// Copyright 2024-2026 Hewlett Packard Enterprise Development LP
package com.morpheusdata.scvmm.logging

import groovy.transform.CompileDynamic

@CompileDynamic
class TimeTracker {
    // Map to hold start and end times for both activities and workflows
    private final Map<String, Long> timeMap

    // Constructor to initialize the time map
    TimeTracker() {
        timeMap = [:]
    }

    TimeTracker(String activityName) {
        timeMap = [:]
        start(activityName)
    }

    // Start tracking time for a given activity under a workflow
    TimeTracker start(String activity) {
        timeMap[activity] = System.currentTimeMillis() // Start time for the activity
        return this
    }

    // End tracking time for a given activity under a workflow
    TimeTracker end(String activity) {
        if (timeMap.containsKey(activity)) {
            timeMap[activity] = System.currentTimeMillis() - timeMap[activity]
        }
        return this
    }

    Long getOverallTime(String activity) {
        return timeMap[activity]
    }

    @Override
    String toString() {
        return timeMap.collect { key, value ->
            "${key}=${value / TrackerConstants.SLOW_LOG_SECOND_PRECISION}s"
        }.join(", ")
    }

    Long getElapsedTime(String activity) {
        return timeMap[activity] / 1000
    }
}