// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.util

import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import groovy.transform.CompileDynamic
import groovy.xml.XmlSlurper
import groovy.xml.XmlUtil

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
    static String loadPowerShellScriptWithTokens(String scriptName, Map<String, Object> replacements) {
        String script = loadPowerShellScript(scriptName)
        replacements.each { token, value ->
            script = script.replace(token, value.toString() ?: '')
        }
        return script
    }

    /**
     * Pretty print PowerShell error output if it is in CLI XML format. If the error string is not in CLI XML format or
     * if parsing fails, the original error string is returned. This method also includes safeguards against XML bomb
     * attacks by limiting the size of the input string and disabling external entity resolution during XML parsing.
     * @param outputError The error string output from PowerShell execution
     * @return Pretty printed XML string if input is CLI XML, otherwise the original error string
     */
    @SuppressWarnings('DuplicateNumberLiteral')
    static String prettyPrintPowerShellScriptError(String outputError) {
        // If the error string is null or empty, return it as is
        if (!outputError) {
            return outputError
        }

        // Prevent billion laughs/XML bomb attacks by limiting XML size (1 MiB max)
        if (outputError.length() > 1 * 1024 * 1024) {
            log.info("XML error output of size ${outputError.length()} exceeds size limit, skipping pretty print")
            return outputError
        }

        // If the error string does not start with a CLI XML prefix, return it as is
        if (!outputError.startsWith('#< CLIXML')) {
            return outputError
        }

        // Preserve original for return on parse failure
        String originalError = outputError

        // Remove the CLI XML prefix to get raw XML content; return original string if no content
        String xmlContent = outputError.replaceFirst(/^#< CLIXML\r?\n/, '')
        if (!xmlContent) {
            return originalError
        }

        // Attempt to parse the error string as XML and pretty print it. If parsing fails, log the error and return the
        // original string.
        try {
            // XmlSlurper with (false, false) disables external entity resolution and namespace awareness
            def parsed = new XmlSlurper(false, false).parseText(xmlContent)
            String prettyXml = XmlUtil.serialize(parsed)
            return prettyXml.replaceAll(/\n\s*\n/, '\n')
        } catch (Exception ex) {
            log.debug("Failed to parse PowerShell error output as XML: ${ex.message}")
            return originalError
        }
    }
}
