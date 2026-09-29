// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.error

/**
 * A polling loop gave up waiting for a condition (job completion, VM creation, IP assignment, agent check-in).
 */
class ScvmmTimeoutException extends ScvmmException {
    final String awaited
    final Integer attempts
    final Long durationMs
    final String lastState

    ScvmmTimeoutException(String awaited, Integer attempts, Long durationMs, String lastState = null, Throwable cause = null) {
        super(buildMessage(awaited, attempts, durationMs, lastState), buildMessage(awaited, attempts, durationMs, lastState), cause)
        this.awaited = awaited
        this.attempts = attempts
        this.durationMs = durationMs
        this.lastState = lastState
    }

    static String buildMessage(String awaited, Integer attempts, Long durationMs, String lastState) {
        StringBuilder sb = new StringBuilder("Timed out waiting for ${awaited}")
        if (durationMs != null) {
            sb.append(" after ${formatDuration(durationMs)}")
        }
        if (attempts != null) {
            sb.append(" (${attempts} attempt${attempts == 1 ? '' : 's'})")
        }
        sb.append('.')
        if (lastState) {
            sb.append(" Last observed state: ${lastState}.")
        }
        sb.toString()
    }

    static String formatDuration(Long ms) {
        if (ms == null) {
            return 'unknown'
        }
        long seconds = Math.round(ms / 1000d)
        if (seconds < 60) {
            return "${seconds}s"
        }
        long minutes = seconds.intdiv(60)
        long rem = seconds % 60
        rem ? "${minutes}m ${rem}s" : "${minutes}m"
    }

    @Override
    Map<String, Object> toDetailMap() {
        Map<String, Object> rtn = super.toDetailMap()
        rtn.awaited = awaited
        rtn.attempts = attempts
        rtn.durationMs = durationMs
        rtn.lastState = lastState
        rtn
    }
}
