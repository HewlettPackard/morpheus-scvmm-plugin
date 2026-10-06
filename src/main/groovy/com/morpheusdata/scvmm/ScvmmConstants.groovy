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
     * Maximum number of network interfaces a user may attach to an instance at provision time.
     *
     * Hyper-V Generation 1 VMs support at most 8 synthetic network adapters (plus 4 legacy/emulated adapters, which
     * the plugin never creates). Generation 2 VMs allow more, but the plugin cannot know the generation of the
     * selected image when the provisioning form is rendered, so the Gen1 synthetic limit is used as a safe upper
     * bound that applies to every supported VM generation.
     *
     * See https://learn.microsoft.com/windows-server/virtualization/hyper-v/plan/plan-hyper-v-scalability-in-windows-server
     */
    static final Integer MAX_NETWORKS = 8
}
