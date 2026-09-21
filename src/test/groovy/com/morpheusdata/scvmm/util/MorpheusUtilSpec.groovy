// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.util

import com.morpheusdata.model.ComputeServer
import spock.lang.Specification
import spock.lang.Unroll

class MorpheusUtilSpec extends Specification {

    @Unroll
    def "getConsoleHost prefers FQDN hostname and falls back to name (hostname=#hostname, name=#name)"() {
        given:
        def host = new ComputeServer(hostname: hostname, name: name)

        expect:
        MorpheusUtil.getConsoleHost(host) == expected

        where:
        hostname                 | name         || expected
        'hv-node-01.corp.local'  | 'hv-node-01' || 'hv-node-01.corp.local'
        null                     | 'hv-node-01' || 'hv-node-01'
        ''                       | 'hv-node-01' || 'hv-node-01'
        'hv-node-01.corp.local'  | null         || 'hv-node-01.corp.local'
        null                     | null         || null
    }

    def "getConsoleHost returns null for a null parent server"() {
        expect:
        MorpheusUtil.getConsoleHost(null) == null
    }
}
