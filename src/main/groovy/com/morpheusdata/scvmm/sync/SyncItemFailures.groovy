// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.sync

import com.morpheusdata.scvmm.error.ScvmmErrorTranslator
import com.morpheusdata.scvmm.logging.LogInterface

/**
 * Records failures while reconciling individual inventory items so one bad item does not stop the rest of a sync
 * worker, while still making the worker report failure instead of success.
 */
class SyncItemFailures {
    private final String syncName
    private final LogInterface log
    private int count = 0
    private String firstFailure

    SyncItemFailures(String syncName, LogInterface log) {
        this.syncName = syncName
        this.log = log
    }

    /**
     * Logs and records an item failure. Connection failures are rethrown because every remaining item would fail too.
     */
    void record(Throwable failure) {
        if (ScvmmErrorTranslator.isConnectionFailure(failure)) {
            throw failure
        }
        count++
        String summary = ScvmmErrorTranslator.summary(failure)
        firstFailure = firstFailure ?: summary
        log.error("${syncName}: failed to reconcile an item: ${summary}", failure)
    }

    Map result() {
        count ? [success: false, msg: "${count} item operation(s) failed; first: ${firstFailure}".toString()] : [success: true]
    }
}
