// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.Plugin

class ScvmmPlugin extends Plugin {
    private static final String PLUGIN_CODE = 'morpheus-scvmm-plugin'
    private static final String PLUGIN_NAME = 'SCVMM'
    private static final String PLUGIN_DESCRIPTION = 'Plugin for SCVMM Integration'

    @Override
    String getCode() {
        return PLUGIN_CODE
    }

    @Override
    void initialize() {
        this.setName(PLUGIN_NAME)
        this.setDescription(PLUGIN_DESCRIPTION)
        this.registerProviders(
                new ScvmmCloudProvider(this,this.morpheus),
                new ScvmmProvisionProvider(this,this.morpheus),
                new ScvmmBackupProvider(this,this.morpheus),
                new ScvmmOptionSourceProvider(this, this.morpheus)
        )
    }

	/**
	 * Called when a plugin is being removed from the plugin manager (aka Uninstalled)
	 */
	@Override
	void onDestroy() {
		List<String> seedsToRun = [
			"application.ZoneTypesSCVMMSeed",
			"application.ProvisionTypeScvmmSeed",
            "application.ScvmmSeed",
            "application.ComputeServerTypeScvmmSeed",
            "application.ScvmmComputeTypeSeed",
            "application.ServicePlanScvmmSeed",
		]
		this.morpheus.services.seed.reinstallSeedData(seedsToRun)
		// needs to be synchronous to prevent seeds from running during plugin install
	}
}
