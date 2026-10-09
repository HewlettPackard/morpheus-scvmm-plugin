// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.error

import java.util.regex.Pattern

/**
 * Helpers that make command text and host output safe to log and to surface in the UI:
 * strips the JSON wrapper boilerplate, redacts credential-looking arguments, collapses whitespace and truncates.
 */
class ScvmmCommandSanitizer {
    static final int MAX_COMMAND_LENGTH = 300
    static final int MAX_ERROR_LENGTH = 1000
    static final int MAX_PAYLOAD_SAMPLE_LENGTH = 500

    private static final String REDACTED = '***'

    /** Argument-style secrets: -Password xxx, -Credential xxx, -SecurePassword xxx */
    private static final Pattern ARG_SECRET = Pattern.compile(/(?i)(-(?:Password|SecurePassword|Credential|Passphrase|Token|Secret)\s+)("[^"]*"|'[^']*'|\S+)/)
    /** Assignment-style secrets: password=xxx, "password":"xxx", $password = "xxx" */
    private static final Pattern ASSIGN_SECRET = Pattern.compile(/(?i)((?:password|passwd|pwd|secret|token)\s*["']?\s*[:=]\s*)("[^"]*"|'[^']*'|\S+)/)
    private static final Pattern SECURE_STRING = Pattern.compile(/(?i)(ConvertTo-SecureString\s+)("[^"]*"|'[^']*'|\S+)/)

    private static final Pattern JSON_PREFIX = Pattern.compile('^\\s*\\$FormatEnumerationLimit\\s*=\\s*-1;\\s*')
    private static final Pattern JSON_SUFFIX = Pattern.compile(/\s*\|\s*ConvertTo-Json(\s+-Depth\s+\d+)?\s*$/)

    /**
     * Produces a one-line, secret-free summary of a PowerShell command, limited to {@link #MAX_COMMAND_LENGTH} chars.
     */
    static String summarize(String command) {
        if (command == null) {
            return null
        }
        String rtn = JSON_PREFIX.matcher(command).replaceFirst('')
        rtn = JSON_SUFFIX.matcher(rtn).replaceFirst('')
        rtn = redact(rtn)
        rtn = rtn.replaceAll(/\s+/, ' ').trim()
        truncate(rtn, MAX_COMMAND_LENGTH)
    }

    /**
     * Redacts anything that looks like a credential in free text.
     */
    static String redact(String text) {
        if (text == null) {
            return null
        }
        String rtn = ARG_SECRET.matcher(text).replaceAll('$1' + REDACTED)
        rtn = SECURE_STRING.matcher(rtn).replaceAll('$1' + REDACTED)
        rtn = ASSIGN_SECRET.matcher(rtn).replaceAll('$1' + REDACTED)
        rtn
    }

    static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text
        }
        text.substring(0, Math.max(0, max - 3)) + '...'
    }

    /**
     * First non-blank line of a block of text (PowerShell errors are multi-line; the first line carries the reason).
     */
    static String firstLine(String text) {
        if (!text) {
            return null
        }
        def line = text.readLines().find { it?.trim() }
        line?.trim()
    }

    /**
     * Sample of a payload that failed to parse, for diagnostics.
     */
    static String payloadSample(String payload) {
        truncate(redact(payload), MAX_PAYLOAD_SAMPLE_LENGTH)
    }

    private static final Set<String> SENSITIVE_KEYS = ['sshPassword', 'password', 'privateKey', 'credentialPassword', 'secret', 'token', 'cloudConfigBytes', 'cloudConfigUser'] as Set

    /**
     * Shallow copy of an opts map with credential-bearing keys masked, for safe DEBUG logging.
     */
    static Map redactOpts(Map opts) {
        if (opts == null) {
            return null
        }
        Map rtn = [:]
        opts.each { k, v ->
            String key = k?.toString()
            if (SENSITIVE_KEYS.any { it.equalsIgnoreCase(key) } || key?.toLowerCase()?.contains('password')) {
                rtn[k] = v == null ? null : REDACTED
            } else if (v instanceof Map) {
                rtn[k] = redactOpts(v)
            } else {
                rtn[k] = v
            }
        }
        rtn
    }
}
