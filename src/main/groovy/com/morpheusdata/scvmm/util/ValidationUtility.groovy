// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.util

import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import groovy.transform.CompileStatic

@CompileStatic
class ValidationUtility {
    // Common error messages
    static final String VALIDATION_ERROR = 'Validation error'

    // Field names for error responses
    static final String FIELD_NAME_NETWORK_INTERFACES = 'networkInterfaces'
    static final String FIELD_NAME_TEMPLATE = 'template'

    @SuppressWarnings('FieldName')
    private static final LogInterface log = LogWrapper.instance

    /**
     * Validates the network configuration for a VM provisioning request.
     * @param opts The options map containing the network configuration details.
     * @return A ServiceResponse indicating success or failure of the validation, with error details if applicable.
     */
    static ServiceResponse validateNetworkConfig(Map opts) {
        // Make sure there is a network specified
        boolean networkFound = opts?.networkInterfaces?.any { networkInterface ->
            if (!(networkInterface instanceof Map)) {
                log.warn("networkInterface validation failed: Invalid entry: ${networkInterface}")
                return false
            }
            Map networkInterfaceMap = networkInterface as Map

            if (!(networkInterfaceMap.network instanceof Map)) {
                log.warn("network validation failed: Invalid entry: ${networkInterfaceMap}")
                return false
            }
            Map networkMap = networkInterfaceMap.network as Map

            return networkMap.id != null
        }

        if (!networkFound) {
            log.error('Network validation failed: No valid network interfaces found in the configuration')
            return ServiceResponse.error(
                    VALIDATION_ERROR,
                    [(FIELD_NAME_NETWORK_INTERFACES): 'At least one network is required']
            )
        }

        return ServiceResponse.success()
    }

    /**
     * Validates the virtual image configuration for a VM provisioning request.
     * @param context The MorpheusContext providing access to services and logging.
     * @param opts The options map containing the image configuration details.
     * @return A ServiceResponse indicating success or failure of the validation, with error details if applicable.
     */
    static ServiceResponse validateImage(Map opts) {
        // Make sure there is a virtual image specified
        if (!(opts?.config as Map)?.template) {
            log.error('Image validation failed: No imageId provided in the configuration')
            return ServiceResponse.error(
                    VALIDATION_ERROR,
                    [(FIELD_NAME_TEMPLATE): 'A virtual machine image must be selected']
            )
        }

        return ServiceResponse.success()
    }
}
