// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.util

import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import groovy.transform.CompileDynamic
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

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

    /**
     * Parse PowerShell CLI XML output to extract warning and error messages.
     * @param cliXml The raw CLI XML error output from PowerShell
     * @return List of extracted warning and error messages, prefixed with stream type (e.g., "[WARNING] Message text")
     */
    @SuppressWarnings('CatchException')
    @SuppressWarnings('DuplicateNumberLiteral')
    @SuppressWarnings('MethodSize')
    static List<String> parseCliXml(String cliXml) {
        final int oneKB = 1024
        final int maxInputLimit = 1 * oneKB * oneKB // 1 MiB
        final int maxMessages = 1000
        final String xmlCliNodeName = 'S'

        // Parse PowerShell CLI XML format to extract readable error messages
        if (!cliXml?.startsWith('#< CLIXML')) {
            String sanitizedForLog = sanitizeForLogging(cliXml)
            log.warn("Output does not appear to be in CLI XML format: ${sanitizedForLog}")
            return null
        }

        // Limit input size to prevent DoS attacks (1 MB reasonable limit)
        if (cliXml.length() > maxInputLimit) {
            String sanitizedForLog = sanitizeForLogging(cliXml)
            log.warn("CLI XML input exceeds maximum size limit (${maxInputLimit} bytes): ${cliXml.length()} bytes",
                    sanitizedForLog)
            return null
        }

        List<String> warningAndErrorMessages = []
        try {
            // Remove the CLI XML header
            String xmlContent = cliXml.replaceFirst(/^#< CLIXML\r?\n/, '')

            // Create secure DocumentBuilder with XXE and DoS protection
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance()
            dbf.with {
                setFeature('http://apache.org/xml/features/nonvalidating/load-external-dtd', false)
                setFeature('http://xml.org/sax/features/external-general-entities', false)
                setFeature('http://xml.org/sax/features/external-parameter-entities', false)
                setFeature('http://apache.org/xml/features/nonvalidating/load-dtd-grammar', false)
                XIncludeAware = false
                expandEntityReferences = false
            }

            // Disable DOCTYPE to prevent XXE/Billion Laughs attacks
            try {
                dbf.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
            } catch (Exception ignored) {
                // Feature not supported in this parser, continue with other protections
            }

            // Protect against Billion Laughs and Quadratic Blowup attacks
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, '')
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, '')

            DocumentBuilder documentBuilder = dbf.newDocumentBuilder()
            Document document = documentBuilder.parse(new ByteArrayInputStream(xmlContent.getBytes('UTF-8')))

            // Extract all <S> elements
            NodeList nodeList = document.getElementsByTagName(xmlCliNodeName)
            for (int i = 0; i < nodeList.length && warningAndErrorMessages.size() < maxMessages; i++) {
                Element node = (Element) nodeList.item(i)
                String streamType = node.getAttribute(xmlCliNodeName)
                String content = node.textContent
                        .replaceAll(/(?i)_x000D__x000A_/, '\n')  // Decode line breaks (case-insensitive)
                        .replaceAll(/_x([0-9A-Fa-f]{4})_?/) { all, hexCode ->
                            try {
                                int charCode = Integer.parseInt(hexCode as String, 16)
                                // Validate: allow printable ASCII (32-126), tab (9), newline (10), carriage return (13)
                                if ((charCode >= 32 && charCode <= 126) || charCode in [9, 10, 13]) {
                                    String.valueOf((char) charCode)
                                } else {
                                    // Replace invalid control characters with placeholder
                                    log.debug("Skipping invalid control character: 0x${hexCode}")
                                    '?'
                                }
                            } catch (Exception ex) {
                                log.warn("Failed to decode hex value: ${hexCode}", ex)
                                '?'
                            }
                        }
                        .trim()

                // Only add non-empty messages with valid stream type
                if (content) {
                    String typePrefix = streamType?.toUpperCase() ?: 'UNKNOWN'
                    String message = "[${typePrefix}] ${content}"
                    warningAndErrorMessages.add(message)
                }
            }

            if (nodeList.length >= maxMessages) {
                log.warn("Number of messages exceeds limit (${maxMessages}), results may be incomplete")
            }

            return warningAndErrorMessages
        } catch (Exception e) {
            String sanitizedForLog = sanitizeForLogging(cliXml)
            log.warn("Failed to parse CLI XML, cliXml=${sanitizedForLog}, exception=${e.message}", e)
            return null
        }
    }

    /**
     * Sanitize long strings for logging to prevent excessively long log entries
     * @param input String to sanitize
     * @param maxLength Maximum length before truncation
     * @return Sanitized string with ellipsis appended if truncated
     */
    private static String sanitizeForLogging(String input, int maxLength = 200) {
        if (!input) {
            return 'null'
        }
        if (input.length() <= maxLength) {
            return input
        }
        return input.take(maxLength) + '...'
    }
}
