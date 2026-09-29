// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.error

import com.morpheusdata.response.ServiceResponse

/**
 * Single translation point from any {@link Throwable} raised while talking to SCVMM into something a Morpheus
 * operator can act on: a user-facing message, optional field errors, and structured (secret-free) details.
 *
 * Provider {@code catch} blocks should log the exception once (with stack trace and context) and then hand it to
 * {@link #toServiceResponse} or {@link #toResultMap}; this class never logs.
 */
class ScvmmErrorTranslator {

    static final String GENERIC_MESSAGE = 'An unexpected error occurred while communicating with SCVMM.'

    /**
     * Best user-facing message for the throwable: known VMM/WinRM signature first, then the exception's own
     * {@code userMessage}, then its message.
     */
    static String userMessage(Throwable t) {
        if (t == null) {
            return GENERIC_MESSAGE
        }
        // Our own timeout / parse exceptions already carry a precise message; do not let generic patterns
        // ("timed out") re-map them to a connection failure.
        if (t instanceof ScvmmTimeoutException || t instanceof ScvmmResponseParseException) {
            return t.userMessage
        }
        ScvmmKnownErrors.KnownError known = ScvmmKnownErrors.match(t)
        if (known) {
            return known.userMessage
        }
        if (t instanceof ScvmmCommandException) {
            String reason = ScvmmCommandSanitizer.firstLine(t.errorOutput)
            return reason ? "SCVMM reported an error: ${ScvmmCommandSanitizer.redact(reason)}".toString() : t.userMessage
        }
        if (t instanceof ScvmmException) {
            return t.userMessage
        }
        Throwable root = rootCause(t)
        String msg = root?.message ?: t.message
        msg ? ScvmmCommandSanitizer.redact(msg) : "${GENERIC_MESSAGE} (${root?.class?.simpleName ?: t.class.simpleName})".toString()
    }

    /**
     * Builds a failed {@link ServiceResponse} for a provider return value.
     *
     * @param t        the failure
     * @param context  optional short description of what was being attempted (e.g. "Starting VM web-01"); prefixed
     *                 to the message so the UI shows what failed as well as why
     */
    static ServiceResponse toServiceResponse(Throwable t, String context = null) {
        String message = userMessage(t)
        String fullMessage = context ? "${context}: ${message}".toString() : message
        // ServiceResponse.error(msg) only populates errors.error; the UI reads msg, so set both
        ServiceResponse rtn = ServiceResponse.error(fullMessage)
        rtn.msg = fullMessage
        rtn.data = details(t, context)
        ScvmmKnownErrors.KnownError known = ScvmmKnownErrors.match(t)
        if (known) {
            rtn.errorCode = known.code
            if (known.field) {
                rtn.addError(known.field, message)
            }
        } else if (t instanceof ScvmmException) {
            rtn.errorCode = t.errorType
        }
        rtn
    }

    /**
     * Populates a legacy {@code [success:false, msg:..., error:...]} result map from the failure. Existing keys other
     * than {@code success}, {@code msg}, {@code error} and {@code errorType} are preserved.
     */
    static Map toResultMap(Throwable t, Map rtn = [:], String context = null) {
        rtn.success = false
        String message = userMessage(t)
        rtn.msg = context ? "${context}: ${message}".toString() : message
        rtn.error = t?.message ? ScvmmCommandSanitizer.redact(t.message) : rtn.msg
        rtn.errorType = t instanceof ScvmmException ? t.errorType : (t?.class?.simpleName ?: 'Unknown')
        ScvmmKnownErrors.KnownError known = ScvmmKnownErrors.match(t)
        if (known) {
            rtn.errorCode = known.code
        }
        rtn
    }

    /**
     * Structured, secret-free details for {@code ServiceResponse.data}.
     */
    static Map<String, Object> details(Throwable t, String context = null) {
        Map<String, Object> rtn
        if (t instanceof ScvmmException) {
            rtn = t.toDetailMap()
        } else {
            Throwable root = rootCause(t)
            rtn = [errorType: t?.class?.simpleName ?: 'Unknown', message: ScvmmCommandSanitizer.redact(t?.message)]
            if (root != null && !root.is(t)) {
                rtn.cause = "${root.class.simpleName}: ${ScvmmCommandSanitizer.redact(root.message)}".toString()
            }
        }
        ScvmmKnownErrors.KnownError known = ScvmmKnownErrors.match(t)
        if (known) {
            rtn.errorCode = known.code
            rtn.category = known.category.name()
        }
        if (context) {
            rtn.context = context
        }
        rtn
    }

    static boolean isConnectionFailure(Throwable t) {
        if (t == null) {
            return false
        }
        if (t instanceof ScvmmConnectionException) {
            return true
        }
        // our own polling timeouts / parse failures mean the host answered; they are not connection failures
        if (t instanceof ScvmmTimeoutException || t instanceof ScvmmResponseParseException) {
            return false
        }
        ScvmmKnownErrors.match(t)?.category == ScvmmKnownErrors.Category.CONNECTION
    }

    /**
     * One-line summary suitable for status messages (cloud status, server statusMessage).
     */
    static String summary(Throwable t) {
        ScvmmCommandSanitizer.firstLine(userMessage(t))
    }

    static Throwable rootCause(Throwable t) {
        Throwable current = t
        int depth = 0
        while (current?.cause != null && !current.cause.is(current) && depth < 16) {
            current = current.cause
            depth++
        }
        current
    }
}
