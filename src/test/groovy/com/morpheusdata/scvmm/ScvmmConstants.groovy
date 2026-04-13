// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import spock.lang.Specification

class ScvmmConstantsSpec extends Specification {
    def "TEMPORARY_TEMPLATE_UUID_REGEX #description"() {
        expect:
        (input ==~ ScvmmConstants.TEMPORARY_TEMPLATE_UUID_PATTERN) == shouldMatch

        where:
        [description, input, shouldMatch] << [
                // --- should match ---
                [
                        'Matches "Temporary Template" with no space before UUID',
                        'Temporary Template016c811a-3c00-4c56-86ea-5b39897079b9',
                        true,
                ],
                [
                        'Matches "Temporary Template" with one space before UUID',
                        'Temporary Template 016c811a-3c00-4c56-86ea-5b39897079b9',
                        true,
                ],
                [
                        'Matches "Temporary Template" with multiple spaces before UUID',
                        'Temporary Template   016c811a-3c00-4c56-86ea-5b39897079b9',
                        true,
                ],
                [
                        'Matches "Temporary Morpheus Template" with no space before UUID',
                        'Temporary Morpheus Template016c811a-3c00-4c56-86ea-5b39897079b9',
                        true,
                ],
                [
                        'Matches "Temporary Morpheus Template" with one space before UUID',
                        'Temporary Morpheus Template 016c811a-3c00-4c56-86ea-5b39897079b9',
                        true,
                ],
                [
                        'Matches uppercase hex UUID',
                        'Temporary Template 016C811A-3C00-4C56-86EA-5B39897079B9',
                        true,
                ],
                [
                        'Matches mixed-case hex UUID',
                        'Temporary Template 016c811A-3C00-4c56-86Ea-5b39897079B9',
                        true,
                ],
                // --- should not match ---
                [
                        'Does not match empty string',
                        '',
                        false,
                ],
                [
                        'Does not match prefix only with no UUID',
                        'Temporary Template',
                        false,
                ],
                [
                        'Does not match wrong prefix casing',
                        'temporary template 016c811a-3c00-4c56-86ea-5b39897079b9',
                        false,
                ],
                [
                        'Does not match UUID with missing segment',
                        'Temporary Template 016c811a-3c00-4c56-5b39897079b9',
                        false,
                ],
                [
                        'Does not match UUID without dashes',
                        'Temporary Template 016c811a3c004c5686ea5b39897079b9',
                        false,
                ],
                [
                        'Does not match UUID with non-hex character',
                        'Temporary Template 016c811a-3c00-4c56-86ea-5b39897079bZ',
                        false,
                ],
                [
                        'Does not match UUID with too few hex chars in first segment',
                        'Temporary Template 016c811-3c00-4c56-86ea-5b39897079b9',
                        false,
                ],
                [
                        'Does not match trailing characters after UUID',
                        'Temporary Template 016c811a-3c00-4c56-86ea-5b39897079b9-extra',
                        false,
                ],
                [
                        'Does not match leading characters before prefix',
                        'X Temporary Template 016c811a-3c00-4c56-86ea-5b39897079b9',
                        false,
                ],
                [
                        'Does not match "Temporary Morpheus" without "Template"',
                        'Temporary Morpheus 016c811a-3c00-4c56-86ea-5b39897079b9',
                        false,
                ],
        ]
    }
}
