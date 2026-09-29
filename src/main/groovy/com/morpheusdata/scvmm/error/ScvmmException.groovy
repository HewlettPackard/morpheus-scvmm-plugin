// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.error

/**
 * Base runtime exception for all SCVMM plugin failures.
 *
 * Carries an optional {@code userMessage}: a short, actionable sentence that is safe to show in the
 * Morpheus UI. When it is not set, {@link #getUserMessage()} falls back to the technical message.
 */
class ScvmmException extends RuntimeException {
    private final String userMessage

    ScvmmException(String message) {
        this(message, null, null)
    }

    ScvmmException(String message, Throwable cause) {
        this(message, null, cause)
    }

    ScvmmException(String message, String userMessage, Throwable cause) {
        super(message, cause)
        this.userMessage = userMessage
    }

    String getUserMessage() {
        userMessage ?: message
    }

    /**
     * Short machine-friendly type used in {@code ServiceResponse.data.errorType} and log lines.
     */
    String getErrorType() {
        this.class.simpleName
    }

    /**
     * Structured, secret-free details suitable for {@code ServiceResponse.data}.
     */
    Map<String, Object> toDetailMap() {
        Map<String, Object> rtn = [errorType: errorType, message: message]
        if (userMessage) {
            rtn.userMessage = userMessage
        }
        if (cause && !cause.is(this)) {
            rtn.cause = "${cause.class.simpleName}: ${cause.message}".toString()
        }
        rtn
    }
}
