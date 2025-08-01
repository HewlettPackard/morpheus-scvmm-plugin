package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.backup.AbstractBackupTypeProvider
import com.morpheusdata.core.backup.BackupExecutionProvider
import com.morpheusdata.core.backup.BackupRestoreProvider
import com.morpheusdata.core.backup.BackupTypeProvider
import com.morpheusdata.model.BackupProvider as BackupProviderModel
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

/**
 * Provides a standard set of methods for a {@link com.morpheusdata.core.backup.BackupProvider}. A backup provider is the primary connection to the
 * external provider services. The backup provider supplies providers for provision types and/or container types via
 * the {@link BackupTypeProvider BackupTypeProviders} implemented within the provider.
 */
class ScvmmBackupTypeProvider extends AbstractBackupTypeProvider {

	BackupExecutionProvider executionProvider;
	BackupRestoreProvider restoreProvider;
	MorpheusContext morpheusContext
	ScvmmPlugin plugin

	ScvmmBackupTypeProvider(ScvmmPlugin plugin, MorpheusContext morpheusContext) {
		super(plugin, morpheusContext)
		this.plugin = plugin
		this.morpheusContext = morpheusContext

	}

	/**
	 * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
	 * that is seeded or generated related to this provider will reference it by this code.
	 * @return short code string that should be unique across all other plugin implementations.
	 */
	@Override
	String getCode() {
		return "scvmmSnapshot"
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		return "SCVMM VM Snapshot"
	}
	
	/**
	 * get the type of container compatible with this backup type
	 * @return the container type code
	 */
	@Override
	String getContainerType() {
		return "single"
	}
	
	/**
	 * Determines if this backup type supports copying the backup to a datastore (export).
	 * @return boolean indicating copy to store is supported
	 */
	@Override
	Boolean getCopyToStore() {
		return false
	}

	/**
	 * Determines if this backup type supports downloading the backups for this backup type.
	 * @return boolean indicating downloading is enabled
	 */
	@Override
	Boolean getDownloadEnabled() {
		return false
	}

	/**
	 * Determines if this backup type supports restoring a backup to the existing workload.
	 * @return boolean indicating restore to existing is enabled
	 */
	@Override
	Boolean getRestoreExistingEnabled() {
		return true
	}

	/**
	 * Determines if this backup type supports restoring to a new workload rather than replacing the existing workload.
	 * @return boolean indicating restore to new is enabled
	 */
	@Override
	Boolean getRestoreNewEnabled() {
		return false
	}

	/**
	 * Indicates the type of restore supported. Current options include: new, existing, online, offline, migration, failover
	 * @return the supported restore type
	 */
	@Override
	String getRestoreType() {
		return "offline"
	}

	/**
	 * Get the desired method of restoring a backup to a new instance.
	 * <p>
	 * Available options:
	 * 		<ul>
	 * 			<li>
	 * 			 	DEFAULT -- Uses the backup as an input to the instance provision process. Generally this
	 * 			 				involves using a snapshot or image ID as the image used for provisioning, but the precise
	 * 			 				details are left up to the backup provider.
	 * 			</li>
	 * 		 	<li>
	 * 		 	    VM_RESTORE -- The external backup provider restores to a VM and the core system will associate the resulting
	 * 		 	    				VM to the internal resources.
	 * 		 	</li>
	 * 		 	<li>
	 * 		 	  	TEMP_EXTRACT -- determines the visibility of the restore to new option
	 * 		 	</li>
	 * 		</ul>
	 *
	 * @return
	 */
	@Override
	String getRestoreNewMode() {
		return null
	}
	
	/**
	 * Does this backup type provider support copy to store?
	 * @return boolean indicating copy to store support
	 */
	@Override
	Boolean getHasCopyToStore() {
		return false
	}

	/**
	 * A list of {@link OptionType OptionTypes} for use in the backup create and edit forms.
	 * @return a list of option types
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		return new ArrayList<OptionType>()
	}
	
	/**
	 * Get the backup provider which will be responsible for all the operations related to backup executions.
	 * @return a {@link BackupExecutionProvider} providing methods for backup execution.
	 */
	@Override
	ScvmmBackupExecutionProvider getExecutionProvider() {
		if(!this.executionProvider) {
			this.executionProvider = new ScvmmBackupExecutionProvider(plugin, morpheusContext)
		}
		return this.executionProvider
	}

	/**
	 * Get the backup provider which will be responsible for all the operations related to backup restore.
	 * @return a {@link BackupRestoreProvider} providing methods for backup restore operations.
	 */
	@Override
	ScvmmBackupRestoreProvider getRestoreProvider() {
		if(!this.restoreProvider) {
		this.restoreProvider = new ScvmmBackupRestoreProvider(plugin, morpheusContext)
		}
		return this.restoreProvider
	}

	/**
	 * Refresh the provider with the associated data in the external system.
	 * @param authConfig  necessary connection and credentials to connect to the external provider
	 * @param backupProvider an instance of the backup integration provider
	 * @return a {@link ServiceResponse} object. A ServiceResponse with a success value of 'false' will indicate the
	 * refresh process has failed and will halt any further backup creation processes in the core system.
	 */
	@Override
	ServiceResponse refresh(Map authConfig, BackupProviderModel backupProviderModel) {
		return ServiceResponse.success()
	}
	
	/**
	 * Clean up all data created by the backup type provider.
	 * @param backupProvider the provider to be cleaned up
	 * @param opts additional options
	 * @return a {@link ServiceResponse} object
	 */
	@Override
	ServiceResponse clean(BackupProviderModel backupProviderModel, Map opts) {
		return ServiceResponse.success()
	}

}			
