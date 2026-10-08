// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import spock.lang.Specification

/**
 * MORPH-4114 / MORPH-16995: ConvertTo-Json collapses nested address arrays into delimited strings once
 * they sit deeper than -Depth, so address payloads must be normalized before being iterated.
 */
class ScvmmApiServiceNormalizeAddressesSpec extends Specification {

    def "returns empty list for null, empty and blank inputs"() {
        expect:
        ScvmmApiService.normalizeAddresses(null) == []
        ScvmmApiService.normalizeAddresses([]) == []
        ScvmmApiService.normalizeAddresses('') == []
        ScvmmApiService.normalizeAddresses('   ') == []
    }

    def "passes through collections while dropping null and blank entries"() {
        expect:
        ScvmmApiService.normalizeAddresses(['10.0.0.5', null, ' fe80::1 ', '']) == ['10.0.0.5', 'fe80::1']
        ScvmmApiService.normalizeAddresses(['10.0.0.5'] as Object[]) == ['10.0.0.5']
    }

    def "splits delimited strings produced by a shallow ConvertTo-Json"() {
        expect:
        ScvmmApiService.normalizeAddresses(input) == expected

        where:
        input                                    || expected
        '10.0.0.5'                               || ['10.0.0.5']
        '10.0.0.5 10.0.0.6'                      || ['10.0.0.5', '10.0.0.6']
        'fe80::1, 2001:db8::1'                   || ['fe80::1', '2001:db8::1']
        "10.0.0.5;10.0.0.6\n10.0.0.7"            || ['10.0.0.5', '10.0.0.6', '10.0.0.7']
    }

    def "never yields single characters when handed a string"() {
        when:
        def result = ScvmmApiService.normalizeAddresses('10.0.0.5 fe80::1')

        then:
        result.every { it.length() > 1 }
        result == ['10.0.0.5', 'fe80::1']
    }

    def "vm report depth covers per-adapter address arrays"() {
        expect:
        // [VMs](0) -> VM(1) -> NetworkAdapters(2) -> adapter(3) -> IPv4Addresses(4)
        ScvmmApiService.VM_REPORT_JSON_DEPTH >= 4
    }

    def "generateCommandString honors the requested depth and defaults to 3"() {
        given:
        def service = new ScvmmApiService(null)

        expect:
        service.generateCommandString('Get-Foo').endsWith('| ConvertTo-Json -Depth 3')
        service.generateCommandString('Get-Foo', ScvmmApiService.VM_REPORT_JSON_DEPTH).endsWith("| ConvertTo-Json -Depth ${ScvmmApiService.VM_REPORT_JSON_DEPTH}")
    }
}
