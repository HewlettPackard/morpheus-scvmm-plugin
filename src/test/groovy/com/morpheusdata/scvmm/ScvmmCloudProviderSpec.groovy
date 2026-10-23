// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.providers.CloudProvider
import spock.lang.Specification
import spock.lang.Subject

class ScvmmCloudProviderSpec extends Specification {

    MorpheusContext context = Mock()
    ScvmmPlugin plugin = Mock()

    @Subject
    CloudProvider provider = new ScvmmCloudProvider(plugin, context)

    def "SCVMM advertises costing support for embedded parity"() {
        expect:
        provider.hasCosting() == true
    }
}
