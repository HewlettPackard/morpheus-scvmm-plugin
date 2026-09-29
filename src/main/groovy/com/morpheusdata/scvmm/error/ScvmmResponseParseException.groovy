// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.error

/**
 * The host returned output that could not be parsed as the JSON the plugin expects.
 */
class ScvmmResponseParseException extends ScvmmException {
    final String payloadSample
    final String commandSummary

    ScvmmResponseParseException(String message, String payloadSample, String commandSummary = null, Throwable cause = null) {
        super(message,
                'The SCVMM host returned an unexpected (non-JSON) response. Check the host for PowerShell errors or an outdated VMM console.',
                cause)
        this.payloadSample = payloadSample
        this.commandSummary = commandSummary
    }

    static ScvmmResponseParseException fromPayload(String command, String payload, Throwable cause) {
        String sample = ScvmmCommandSanitizer.payloadSample(payload)
        String summary = ScvmmCommandSanitizer.summarize(command)
        String message = "Unable to parse SCVMM response as JSON (${cause?.message ?: 'parse error'}). Payload sample: ${sample} [command: ${summary}]".toString()
        new ScvmmResponseParseException(message, sample, summary, cause)
    }

    @Override
    Map<String, Object> toDetailMap() {
        Map<String, Object> rtn = super.toDetailMap()
        rtn.payloadSample = payloadSample
        rtn.command = commandSummary
        rtn
    }
}
