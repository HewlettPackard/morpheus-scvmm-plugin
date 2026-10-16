// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.error

/**
 * The SCVMM host could not be reached or refused the WinRM session (network, listener, or authentication failure).
 */
class ScvmmConnectionException extends ScvmmException {
    final String host
    final Integer port

    ScvmmConnectionException(String message, String host, Integer port, Throwable cause = null) {
        this(message, null, host, port, cause)
    }

    ScvmmConnectionException(String message, String userMessage, String host, Integer port, Throwable cause = null) {
        super(message, userMessage ?: defaultUserMessage(host, port), cause)
        this.host = host
        this.port = port
    }

    static String defaultUserMessage(String host, Integer port) {
        "Unable to connect to the SCVMM host ${host ?: '(unknown)'}${port ? ':' + port : ''} over WinRM. Verify the host address, that WinRM is enabled, and that the credentials are valid.".toString()
    }

    @Override
    Map<String, Object> toDetailMap() {
        Map<String, Object> rtn = super.toDetailMap()
        rtn.host = host
        rtn.port = port
        rtn
    }
}
