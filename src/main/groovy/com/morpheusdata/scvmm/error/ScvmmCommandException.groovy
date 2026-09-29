// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.error

/**
 * A PowerShell command executed on the SCVMM host reported failure (non-zero exit code or {@code success == false}).
 *
 * {@code commandSummary} is a truncated, secret-free rendering of the command (see
 * {@link ScvmmCommandSanitizer#summarize}). {@code errorOutput} is the stderr / error text returned by the host.
 */
class ScvmmCommandException extends ScvmmException {
    final String commandSummary
    final String errorOutput
    final String exitCode

    ScvmmCommandException(String message, String commandSummary, String errorOutput, String exitCode = null, Throwable cause = null) {
        this(message, null, commandSummary, errorOutput, exitCode, cause)
    }

    ScvmmCommandException(String message, String userMessage, String commandSummary, String errorOutput, String exitCode, Throwable cause) {
        super(message, userMessage, cause)
        this.commandSummary = commandSummary
        this.errorOutput = errorOutput
        this.exitCode = exitCode
    }

    /**
     * Builds an exception from the raw pieces returned by the host. The command is sanitized here so that callers
     * never have to remember to do it.
     */
    static ScvmmCommandException fromOutput(String command, String errorOutput, String exitCode) {
        String summary = ScvmmCommandSanitizer.summarize(command)
        String firstLine = ScvmmCommandSanitizer.firstLine(errorOutput)
        String message = "SCVMM command failed${exitCode ? " (exit code ${exitCode})" : ''}: ${firstLine ?: 'no error output'} [command: ${summary}]".toString()
        new ScvmmCommandException(message, summary, errorOutput, exitCode, null)
    }

    @Override
    Map<String, Object> toDetailMap() {
        Map<String, Object> rtn = super.toDetailMap()
        rtn.command = commandSummary
        rtn.exitCode = exitCode
        rtn.errorOutput = ScvmmCommandSanitizer.truncate(errorOutput, ScvmmCommandSanitizer.MAX_ERROR_LENGTH)
        rtn
    }
}
