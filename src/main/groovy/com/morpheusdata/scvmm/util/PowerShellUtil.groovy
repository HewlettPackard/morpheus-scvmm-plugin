// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.util

import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import groovy.transform.CompileDynamic

@SuppressWarnings('CatchException')
@CompileDynamic
class PowerShellUtil {
    @SuppressWarnings('FieldName')
    private static final LogInterface log = LogWrapper.instance

    /**
     * Load a PowerShell script from resources directory
     * @param scriptName Name of script file
     * @return Script content as String
     */
    static String loadPowerShellScript(String scriptName) {
        log.debug("Load PowerShell script: ${scriptName}")
        try {
            String fullPath = "/scripts/powershell/${scriptName}"
            InputStream stream = PowerShellUtil.getResourceAsStream(fullPath)
            if (!stream) {
                String exceptionMsg = "PowerShell script not found: ${fullPath}"
                log.error(exceptionMsg)
                throw new IOException(exceptionMsg)
            }
            // Retrieve the script before returning it (i.e. no "return stream.text") to ensure the stream is closed
            // after reading. This prevents potential "stream closed" exceptions.
            String powerShellScript = stream.text
            return powerShellScript
        } catch (Exception e) {
            log.error("Failed to load PowerShell script ${scriptName}: ${e.message}", e)
            throw e
        }
    }

    /**
     * Load a PowerShell script and replace placeholder tokens
     * @param scriptName Name of script file
     * @param replacements Map of tokens to replace (e.g., ['<%vmid%>': actualVmId])
     * @return Processed script content
     */
    static String loadPowerShellScriptWithTokens(String scriptName, Map<String, String> replacements) {
        String script = loadPowerShellScript(scriptName)
        replacements.each { token, value ->
            script = script.replace(token, value ?: '')
        }
        return script
    }
}
