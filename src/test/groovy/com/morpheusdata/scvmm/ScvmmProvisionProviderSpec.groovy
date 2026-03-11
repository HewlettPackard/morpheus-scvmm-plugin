// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.scvmm.util.ValidationUtility
import spock.lang.Specification

class ScvmmProvisionProviderSpec extends Specification {
    def "test validateWorkload #description"() {
        given:
        ScvmmProvisionProvider provider = new ScvmmProvisionProvider(Mock(ScvmmPlugin), Mock(MorpheusContext))
        ValidationUtility.metaClass.'static'.validateNetworkConfig = { Map ignored -> networkResponse }
        ValidationUtility.metaClass.'static'.validateImage = { Map ignored -> imageResponse }

        when:
        ServiceResponse response = provider.validateWorkload([:])

        then:
        response.success == (!expectedErrors)
        if (!expectedErrors) {
            assert !response.errors
        } else {
            assert response.msg == 'Validation error'
            assert response.errors == expectedErrors
        }

        cleanup:
        GroovySystem.metaClassRegistry.removeMetaClass(ValidationUtility)

        where:
        [description, networkResponse, imageResponse, expectedErrors] << [
                [
                        'Returns success when all validations pass',
                        ServiceResponse.success(),
                        ServiceResponse.success(),
                        null,
                ],
                [
                        'Returns network validation error when network validation fails',
                        ServiceResponse.error(
                                'Network validation error',
                                [networkInterfaces: 'At least one network is required']
                        ),
                        ServiceResponse.success(),
                        [networkInterfaces: 'At least one network is required'],
                ],
                [
                        'Returns image validation error when image validation fails',
                        ServiceResponse.success(),
                        ServiceResponse.error(
                                'Image validation error',
                                [template: 'A virtual machine image must be selected']
                        ),
                        [template: 'A virtual machine image must be selected'],
                ],
                [
                        'Combines network and image validation errors when both fail',
                        ServiceResponse.error(
                                'Network validation error',
                                [networkInterfaces: 'At least one network is required']
                        ),
                        ServiceResponse.error(
                                'Image validation error',
                                [template: 'A virtual machine image must be selected']
                        ),
                        [
                                networkInterfaces: 'At least one network is required',
                                template         : 'A virtual machine image must be selected',
                        ],
                ],
        ]
    }
}
