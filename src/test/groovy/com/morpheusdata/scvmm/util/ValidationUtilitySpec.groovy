// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.util

import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

class ValidationUtilitySpec extends Specification {
    def "test validateNetworkConfig #description"() {
        when:
        ServiceResponse response = ValidationUtility.validateNetworkConfig(opts)

        then:
        if (expectedError) {
            assert !response.success
            assert response.msg == ValidationUtility.VALIDATION_ERROR
            assert response.errors == [
                    (ValidationUtility.FIELD_NAME_NETWORK_INTERFACES): expectedError
            ] as Map<String, String>
        } else {
            assert response.success
            assert !response.msg
            assert !response.errors
        }

        where:
        [description, opts, expectedError] << [
                [
                        'Null opts map',
                        null,
                        'At least one network is required',
                ],
                [
                        'Empty opts map',
                        [:],
                        'At least one network is required',
                ],
                [
                        'Null networkInterfaces',
                        [networkInterfaces: null],
                        'At least one network is required',
                ],
                [
                        'Invalid networkInterfaces',
                        [networkInterfaces: 1234],
                        'At least one network is required',
                ],
                [
                        'Empty networkInterfaces',
                        [networkInterfaces: [:]],
                        'At least one network is required',
                ],
                [
                        'Missing network property',
                        [networkInterfaces: [[key1: 'bogus']]],
                        'At least one network is required',
                ],
                [
                        'Null network property',
                        [networkInterfaces: [[network: null]]],
                        'At least one network is required',
                ],
                [
                        'Invalid network property type',
                        [networkInterfaces: [[network: 'bogus']]],
                        'At least one network is required',
                ],
                [
                        'Missing network id',
                        [networkInterfaces: [[network: [bogus: 1234]]]],
                        'At least one network is required',
                ],
                [
                        'Null network id',
                        [networkInterfaces: [[network: [id: null]]]],
                        'At least one network is required',
                ],
                [
                        'Valid network id as string',
                        [networkInterfaces: [[network: [id: '1234']]]],
                        null,
                ],
                [
                        'Valid network id as numeric id',
                        [networkInterfaces: [[network: [id: 1234]]]],
                        null,
                ],
        ]
    }

    def "test validateImage #description"() {
        when:
        ServiceResponse response = ValidationUtility.validateImage(opts)

        then:
        if (expectedError) {
            assert !response.success
            assert response.msg == ValidationUtility.VALIDATION_ERROR
            assert response.errors == [
                    (ValidationUtility.FIELD_NAME_TEMPLATE): expectedError
            ] as Map<String, String>
        } else {
            assert response.success
            assert !response.msg
            assert !response.errors
        }

        where:
        [description, opts, expectedError] << [
                [
                        'Null opts map',
                        null,
                        'A virtual machine image must be selected',
                ],
                [
                        'Empty opts map',
                        [:],
                        'A virtual machine image must be selected',
                ],
                [
                        'Null config template',
                        [config: [template: null]],
                        'A virtual machine image must be selected',
                ],
                [
                        'Empty config template',
                        [config: [template: '']],
                        'A virtual machine image must be selected',
                ],
                [
                        'Valid config template as string',
                        [config: [template: 'ubuntu-template']],
                        null,
                ],
                [
                        'Valid config template as numeric id',
                        [config: [template: 12345]],
                        null,
                ],
        ]
    }
}
