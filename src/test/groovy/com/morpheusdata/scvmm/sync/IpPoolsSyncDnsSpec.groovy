// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.sync

import spock.lang.Specification

/**
 * MORPH-953: pool attributes carried from SCVMM onto the synced NetworkPool (parity with embedded).
 */
class IpPoolsSyncDnsSpec extends Specification {

    def "DNS servers from SCVMM are normalised to a clean list"() {
        expect:
        IpPoolsSync.normalizeDnsServers(input) == expected

        where:
        input                                   || expected
        null                                    || []
        ''                                      || []
        '10.157.232.245'                        || ['10.157.232.245']
        ['10.157.232.245', ' 10.157.232.246 ']  || ['10.157.232.245', '10.157.232.246']
        ['10.157.232.245', null, '']            || ['10.157.232.245']
    }
}
