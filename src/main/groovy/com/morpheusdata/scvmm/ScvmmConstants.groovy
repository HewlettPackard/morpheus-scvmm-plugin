// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import groovy.transform.CompileStatic

import java.util.regex.Pattern

@CompileStatic
class ScvmmConstants {
    /**
     * SCVMM can create temporary VM templates with names like "Temporary Template{UUID}" while the SCVMM plugin can
     * create temporary VM templates with names like "Temporary Morpheus Template{UUID}". These occur during
     * provisioning or template-based deployment operations. These templates are intended to be short-lived and are
     * normally removed automatically when the operation completes. However, if an operation fails or is interrupted,
     * temporary templates may remain in SCVMM and clutter template listings. This regex/pattern allows the plugin to
     * identify and ignore those leftovers.
     */
    static final String TEMPORARY_TEMPLATE_UUID_REGEX =
            '^Temporary (?:Morpheus )?Template\\s*' +
                    '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
    static final Pattern TEMPORARY_TEMPLATE_UUID_PATTERN = Pattern.compile(TEMPORARY_TEMPLATE_UUID_REGEX)

    // >>> MORPH-9119
    /** Default resource pool values */
    static final String DEFAULT_RESOURCE_POOL_NAME = 'Default'
    static final String DEFAULT_RESOURCE_POOL_EXTERNAL_ID = 'DefaultResourcePoolExternalID'

    /** Reference keys; duplicated from {@link com.morpheusdata.model.AccountInvoice} */
    static final String REF_CLOUD = 'ComputeZone'

    /** Data filter keys */
    static final String KEY_DEFAULT_POOL = 'defaultPool'
    static final String KEY_EXTERNAL_ID = 'externalId'
    static final String KEY_NAME = 'name'
    static final String KEY_REF_ID = 'refId'
    static final String KEY_REF_TYPE = 'refType'

    // <<< MORPH-9119
}
