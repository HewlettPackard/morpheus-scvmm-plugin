// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.clone

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.scvmm.helper.ReconfigurePoolIpHelper

/**
 * Inputs shared by every {@link CloneWizardAspect}. {@code cloneVmId} is known only after the clone exists.
 */
class CloneContext {
    MorpheusContext morpheusContext
    ScvmmApiService apiService
    ReconfigurePoolIpHelper poolIpHelper
    Map scvmmOpts
    /** The clone's Morpheus server (its interface and volume rows were created by core from the request). */
    ComputeServer server
    String sourceVmId
    String cloneVmId
    /** Core's network configuration for the clone: primaryInterface plus extraInterfaces, in request order. */
    Map networkConfig
}
