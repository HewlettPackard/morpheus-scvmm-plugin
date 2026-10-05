// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import spock.lang.Specification

class ScvmmProvisionProviderNetworkSpec extends Specification {

    def "provision provider advertises multi NIC support up to the Hyper-V synthetic adapter limit"() {
        given:
        def provider = new ScvmmProvisionProvider(Mock(ScvmmPlugin), Mock(MorpheusContext))

        expect:
        provider.getMaxNetworks() == ScvmmConstants.MAX_NETWORKS
        ScvmmConstants.MAX_NETWORKS == 8
    }
}
