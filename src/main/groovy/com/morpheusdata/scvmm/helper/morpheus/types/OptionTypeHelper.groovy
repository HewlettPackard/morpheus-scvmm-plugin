package com.morpheusdata.scvmm.helper.morpheus.types

import com.morpheusdata.model.OptionType

@SuppressWarnings('CompileStatic')
class OptionTypeHelper {
    // Constants for duplicate string literals
    private static final String ZONE_TYPE_SCVMM = 'zoneType.scvmm'
    private static final String CONFIG_CONTEXT = 'config'
    private static final String SCVMM_SOURCE_TYPE = 'scvmm'
    private static final String SCVMM_HOST_FIELD = 'SCVMM Host'
    private static final String CREDENTIALS_FIELD = 'Credentials'
    private static final String USERNAME_FIELD = 'Username'
    private static final String PASSWORD_FIELD = 'Password'
    private static final String CLOUD_FIELD = 'Cloud'
    private static final String HOST_GROUP_FIELD = 'Host Group'
    private static final String CLUSTER_FIELD = 'Cluster'
    private static final String HOST_CONFIG_PROPERTY = 'host'
    private static final String LIBRARY_SHARE_FIELD = 'Library Share'
    private static final String SHARED_CONTROLLER_FIELD = 'Shared Controller'
    private static final String WORKING_PATH_FIELD = 'Working Path'
    private static final String DISK_PATH_FIELD = 'Disk Path'
    private static final String HIDE_HOST_SELECTION_FIELD = 'Hide Host Selection From Users'
    private static final String INVENTORY_EXISTING_FIELD = 'Inventory Existing Instances'
    private static final String ENABLE_HYPERVISOR_CONSOLE_FIELD = 'Enable Hypervisor Console'
    private static final String INSTALL_AGENT_CODE = 'gomorpheus.label.installAgent'
    private static final String INSTALL_AGENT_FIELD = 'Install Agent'
    private static final String CREDENTIAL_DEPENDS_ON =
            'config.host, config.username, config.password, credential.type, credential.username, credential.password'
    private static final int DISPLAY_ORDER_INCREMENT = 10
    private static final String USERNAME_OPTION_TYPE = 'gomorpheus.optiontype.Username'
    private static final String PASSWORD_OPTION_TYPE = 'gomorpheus.optiontype.Password'
    private static final String WORKING_PATH_CONFIG = 'workingPath'
    private static final String DISK_PATH_CONFIG = 'diskPath'
    private static final String LIBRARY_SHARE_CONFIG = 'libraryShare'
    private static final String INSTALL_AGENT_CONFIG = 'installAgent'
    private static final String IMPORT_EXISTING_CONFIG = 'importExisting'
    private static final String SHARED_CONTROLLER_ERROR_KEY = 'sharedController'

    /**
     * Gets all SCVMM Cloud Provider option types
     * @return Collection of OptionType
     */
    @SuppressWarnings('MethodSize')
    static Collection<OptionType> getAllOptionTypes() {
        def displayOrder = 0
        Collection<OptionType> options = []

        options << new OptionType(
                name: SCVMM_HOST_FIELD,
                category: ZONE_TYPE_SCVMM,
                code: 'zoneType.scvmm.host',
                fieldName: HOST_CONFIG_PROPERTY,
                displayOrder: displayOrder,
                fieldCode: 'gomorpheus.scvmm.option.host',
                fieldLabel: SCVMM_HOST_FIELD,
                required: true,
                inputType: OptionType.InputType.TEXT,
                fieldContext: CONFIG_CONTEXT,
        )

        options << new OptionType(
                name: CREDENTIALS_FIELD,
                category: ZONE_TYPE_SCVMM,
                code: 'zoneType.scvmm.credential',
                fieldName: 'type',
                displayOrder: displayOrder += DISPLAY_ORDER_INCREMENT,
                fieldCode: 'gomorpheus.label.credentials',
                fieldLabel: CREDENTIALS_FIELD,
                required: true,
                defaultValue: 'local',
                inputType: OptionType.InputType.CREDENTIAL,
                fieldContext: 'credential',
                optionSource: 'credentials',
                config: '{"credentialTypes":["username-password"]}'
        )

        options << new OptionType(
                name: USERNAME_FIELD,
                category: ZONE_TYPE_SCVMM,
                code: 'zoneType.scvmm.username',
                fieldName: 'username',
                displayOrder: displayOrder += DISPLAY_ORDER_INCREMENT,
                fieldCode: USERNAME_OPTION_TYPE,
                fieldLabel: USERNAME_FIELD,
                required: true,
                inputType: OptionType.InputType.TEXT,
                fieldContext: CONFIG_CONTEXT,
                localCredential: true
        )

        options << new OptionType(
                name: PASSWORD_FIELD,
                category: ZONE_TYPE_SCVMM,
                code: 'zoneType.scvmm.password',
                fieldName: 'password',
                displayOrder: displayOrder += DISPLAY_ORDER_INCREMENT,
                fieldCode: PASSWORD_OPTION_TYPE,
                fieldLabel: PASSWORD_FIELD,
                required: true,
                inputType: OptionType.InputType.PASSWORD,
                fieldContext: CONFIG_CONTEXT,
                localCredential: true
        )

        options << new OptionType(
                name: CLOUD_FIELD,
                category: ZONE_TYPE_SCVMM,
                code: 'zoneType.scvmm.cloud',
                fieldName: 'regionCode',
                optionSourceType: SCVMM_SOURCE_TYPE,
                displayOrder: displayOrder += DISPLAY_ORDER_INCREMENT,
                fieldCode: 'gomorpheus.optiontype.Cloud',
                fieldLabel: CLOUD_FIELD,
                inputType: OptionType.InputType.SELECT,
                optionSource: 'scvmmCloud',
                fieldContext: 'domain',
                noBlank: true,
                dependsOn: CREDENTIAL_DEPENDS_ON
        )

        options << new OptionType(
                name: HOST_GROUP_FIELD,
                category: ZONE_TYPE_SCVMM,
                code: 'zoneType.scvmm.hostGroup',
                fieldName: 'hostGroup',
                optionSourceType: SCVMM_SOURCE_TYPE,
                displayOrder: displayOrder += DISPLAY_ORDER_INCREMENT,
                fieldCode: 'gomorpheus.optiontype.HostGroup',
                fieldLabel: HOST_GROUP_FIELD,
                inputType: OptionType.InputType.SELECT,
                optionSource: 'scvmmHostGroup',
                fieldContext: CONFIG_CONTEXT,
                noBlank: true,
                dependsOn: CREDENTIAL_DEPENDS_ON,
        )

        options << new OptionType(
                name: CLUSTER_FIELD,
                category: ZONE_TYPE_SCVMM,
                code: 'zoneType.scvmm.Cluster',
                fieldName: 'cluster',
                optionSourceType: SCVMM_SOURCE_TYPE,
                displayOrder: displayOrder += DISPLAY_ORDER_INCREMENT,
                fieldCode: 'gomorpheus.optiontype.Cluster',
                fieldLabel: CLUSTER_FIELD,
                inputType: OptionType.InputType.SELECT,
                optionSource: 'scvmmCluster',
                fieldContext: CONFIG_CONTEXT,
                noBlank: true,
                dependsOn: 'config.host, config.username, config.password, config.hostGroup, ' +
                        'credential.type, credential.username, credential.password'
        )

        options << new OptionType(
                name: LIBRARY_SHARE_FIELD,
                category: ZONE_TYPE_SCVMM,
                code: 'zoneType.scvmm.libraryShare',
                fieldName: LIBRARY_SHARE_CONFIG,
                optionSourceType: SCVMM_SOURCE_TYPE,
                displayOrder: displayOrder += DISPLAY_ORDER_INCREMENT,
                fieldCode: 'gomorpheus.optiontype.LibraryShare',
                fieldLabel: LIBRARY_SHARE_FIELD,
                inputType: OptionType.InputType.SELECT,
                optionSource: 'scvmmLibraryShares',
                fieldContext: CONFIG_CONTEXT,
                noBlank: true,
                dependsOn: CREDENTIAL_DEPENDS_ON
        )

        options << new OptionType(
                name: SHARED_CONTROLLER_FIELD,
                category: ZONE_TYPE_SCVMM,
                code: 'zoneType.scvmm.sharedController',
                fieldName: SHARED_CONTROLLER_ERROR_KEY,
                optionSourceType: SCVMM_SOURCE_TYPE,
                displayOrder: displayOrder += DISPLAY_ORDER_INCREMENT,
                fieldCode: 'gomorpheus.optiontype.SharedController',
                fieldLabel: SHARED_CONTROLLER_FIELD,
                required: false,
                inputType: OptionType.InputType.SELECT,
                optionSource: 'scvmmSharedControllers',
                fieldContext: CONFIG_CONTEXT,
                editable: false
        )

        options << new OptionType(
                name: WORKING_PATH_FIELD,
                code: 'zoneType.scvmm.workingPath',
                fieldName: WORKING_PATH_CONFIG,
                displayOrder: displayOrder += DISPLAY_ORDER_INCREMENT,
                fieldCode: 'gomorpheus.optiontype.WorkingPath',
                fieldLabel: WORKING_PATH_FIELD,
                required: true,
                inputType: OptionType.InputType.TEXT,
                defaultValue: 'c:\\Temp'
        )

        options << new OptionType(
                name: DISK_PATH_FIELD,
                code: 'zoneType.scvmm.diskPath',
                fieldName: DISK_PATH_CONFIG,
                displayOrder: displayOrder += DISPLAY_ORDER_INCREMENT,
                fieldCode: 'gomorpheus.optiontype.DiskPath',
                fieldLabel: DISK_PATH_FIELD,
                required: true,
                inputType: OptionType.InputType.TEXT,
                defaultValue: 'c:\\VirtualDisks'
        )

        options << new OptionType(
                name: HIDE_HOST_SELECTION_FIELD,
                code: 'zoneType.scvmm.hideHostSelection',
                fieldName: 'HideHostSelectionFromUsers',
                displayOrder: displayOrder += DISPLAY_ORDER_INCREMENT,
                fieldLabel: HIDE_HOST_SELECTION_FIELD,
                required: false,
                inputType: OptionType.InputType.CHECKBOX,
                fieldContext: CONFIG_CONTEXT,
        )

        options << new OptionType(
                name: INVENTORY_EXISTING_FIELD,
                code: 'zoneType.scvmm.importExisting',
                fieldName: IMPORT_EXISTING_CONFIG,
                displayOrder: displayOrder += DISPLAY_ORDER_INCREMENT,
                fieldLabel: INVENTORY_EXISTING_FIELD,
                required: false,
                inputType: OptionType.InputType.CHECKBOX,
                fieldContext: CONFIG_CONTEXT,
        )

        options << new OptionType(
                name: ENABLE_HYPERVISOR_CONSOLE_FIELD,
                code: 'zoneType.scvmm.enableHypervisorConsole',
                fieldName: 'enableHypervisorConsole',
                displayOrder: displayOrder += DISPLAY_ORDER_INCREMENT,
                fieldLabel: ENABLE_HYPERVISOR_CONSOLE_FIELD,
                required: false,
                inputType: OptionType.InputType.CHECKBOX,
                fieldContext: CONFIG_CONTEXT,
        )

        options << new OptionType(
                name: INSTALL_AGENT_FIELD,
                code: INSTALL_AGENT_CODE,
                inputType: OptionType.InputType.CHECKBOX,
                fieldName: INSTALL_AGENT_CONFIG,
                fieldContext: CONFIG_CONTEXT,
                fieldCode: INSTALL_AGENT_CODE,
                fieldLabel: INSTALL_AGENT_FIELD,
                fieldGroup: 'Advancedend',
                displayOrder: displayOrder += DISPLAY_ORDER_INCREMENT,
                required: false,
                enabled: true,
                editable: false,
                global: false,
                custom: true,
        )

        return options
    }
}
