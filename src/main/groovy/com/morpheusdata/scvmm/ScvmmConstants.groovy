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

    /**
     * Code of the {@link com.morpheusdata.model.NetworkPoolType} used for SCVMM static IP address pools. Must stay in
     * sync with the {@code scvmm} entry in the appliance NetworkPoolTypeSeed, which historically owned this record.
     */
    static final String NETWORK_POOL_TYPE_CODE = 'scvmm'

    /** Code of the SCVMM IPAM provider, which becomes the NetworkPoolServerType/AccountIntegrationType code. */
    static final String IPAM_PROVIDER_CODE = 'scvmm-ipam'

    /** ComputeServerType code of the SCVMM controller host that plugin commands are executed against. */
    static final String CONTROLLER_SERVER_TYPE_CODE = 'scvmmController'
}
