// Copyright 2024-2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.logging

import groovy.transform.CompileDynamic

import java.util.concurrent.TimeUnit

@CompileDynamic
class TimeTracker {
    // Maps to hold start and elapsed times
    private final Map<String, Long> timeStartMap = [:]
    private final Map<String, Long> timeElapsedMap = [:]

    private final Object lock = new Object()
    private final LogWrapper log = LogWrapper.instance

    TimeTracker() {
    }

    TimeTracker(String activityName) {
        start(activityName)
    }

    // Start tracking time for a given activity under a workflow
    TimeTracker start(String activity) {
        synchronized (lock) {
            timeStartMap[activity] = System.nanoTime() // Start time for the activity
            timeElapsedMap.remove(activity)
        }
        return this
    }

    // End tracking time for a given activity under a workflow
    TimeTracker end(String activity) {
        synchronized (lock) {
            if (timeStartMap.containsKey(activity)) {
                timeElapsedMap[activity] = System.nanoTime() - timeStartMap[activity]
            }
        }
        return this
    }

    @Override
    String toString() {
        synchronized (lock) {
            return timeStartMap.collect { key, value ->
                "${key}=${getElapsedTimeLocked(key) / TrackerConstants.SLOW_LOG_SECOND_PRECISION}s"
            }.join(", ")
        }
    }

    // Get elapsed time in milliseconds for the given activity. If the activity has not been ended, calculate elapsed
    // time up to the current moment.
    long getElapsedTime(String activity) {
        synchronized (lock) {
            return getElapsedTimeLocked(activity)
        }
    }

    private long getElapsedTimeLocked(String activity) {
        Long startTime = timeStartMap[activity]
        if (startTime == null) {
            // If start time is not recorded, log a warning and return 0
            log.warn("Attempted to get elapsed time for activity '${activity}' that was never started")
            return 0
        }
        Long elapsedTime = timeElapsedMap[activity]
        long elapsedNanos = (elapsedTime == null ? (System.nanoTime() - startTime) : elapsedTime)
        return TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
    }
}
