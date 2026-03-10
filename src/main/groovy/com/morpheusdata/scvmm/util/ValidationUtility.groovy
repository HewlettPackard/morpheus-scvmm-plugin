// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.util

import com.morpheusdata.response.ServiceResponse
import groovy.transform.CompileDynamic

@CompileDynamic
class ValidationUtility {
    /**
     * Validates the network configuration for a VM provisioning request.
     * @param opts The options map containing the network configuration details.
     * @return A ServiceResponse indicating success or failure of the validation, with error details if applicable.
     */
    static ServiceResponse validateNetworkConfig(Map opts) {
        final String errorMessage = 'Network validation error'
        final String fieldName = 'networkInterfaces'

        // Make sure there is a network specified
        boolean networkFound = opts.networkInterfaces.any { networkInterface ->
            networkInterface?.network?.id != null
        }
        if (!networkFound) {
            return ServiceResponse.error(
                    errorMessage,
                    [(fieldName): "At least one network is required"]
            )
        }

        return ServiceResponse.success()
    }
}
