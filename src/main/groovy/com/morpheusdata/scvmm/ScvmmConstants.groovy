// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import groovy.transform.CompileStatic

import java.util.regex.Pattern

@CompileStatic
class ScvmmConstants {
    /**
     * SCVMM can create temporary VM templates with names like "Temporary Template{UUID}" during provisioning or
     * template-based deployment operations. These templates are intended to be short-lived and are normally removed
     * automatically when the operation completes. However, if an operation fails or is interrupted, temporary
     * templates may remain in SCVMM and clutter template listings. This regex/pattern allows the plugin to identify
     * and ignore those leftovers.
     */
    static final String TEMPORARY_TEMPLATE_UUID_REGEX =
            '^Temporary Template\\s*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
    static final Pattern TEMPORARY_TEMPLATE_UUID_PATTERN = Pattern.compile(TEMPORARY_TEMPLATE_UUID_REGEX)
}
