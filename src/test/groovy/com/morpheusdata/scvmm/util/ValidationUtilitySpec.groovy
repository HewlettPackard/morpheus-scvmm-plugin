// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.util

import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

class ValidationUtilitySpec extends Specification {
    def "test validateImage #description"() {
        when:
        ServiceResponse response = ValidationUtility.validateImage(opts)

        then:
        if (expectedError) {
            assert !response.success
            assert response.msg == ValidationUtility.VALIDATION_ERROR
            assert response.errors == [(ValidationUtility.FIELD_NAME_TEMPLATE): expectedError] as Map<String, String>
        } else {
            assert response.success
            assert !response.msg
            assert !response.errors
        }

        where:
        [description, opts, expectedError] << [
                [
                        'Null config map',
                        null,
                        'A virtual machine image must be selected',
                ],
                [
                        'Empty config map',
                        [:],
                        'A virtual machine image must be selected',
                ],
                [
                        'Null template',
                        [config: [template: null]],
                        'A virtual machine image must be selected',
                ],
                [
                        'Empty template',
                        [config: [template: '']],
                        'A virtual machine image must be selected',
                ],
                [
                        'Valid template as string',
                        [config: [template: 'ubuntu-template']],
                        null,
                ],
                [
                        'Valid template as numeric id',
                        [config: [template: 12345]],
                        null,
                ],
        ]
    }
}
