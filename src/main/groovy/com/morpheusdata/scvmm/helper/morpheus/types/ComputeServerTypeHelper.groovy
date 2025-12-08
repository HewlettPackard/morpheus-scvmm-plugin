package com.morpheusdata.scvmm.helper.morpheus.types

import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.PlatformType

@SuppressWarnings('CompileStatic')
class ComputeServerTypeHelper {
    // Constants for ComputeServerType
    private static final String CONFIG_CONTEXT = 'config'
    private static final String SCVMM_SOURCE_TYPE = 'scvmm'
    private static final String SCVMM_CONTROLLER_CODE = 'scvmmController'
    private static final String SCVMM_COMPUTE_SERVICE = 'scvmmComputeService'
    private static final String SCVMM_PROVISION_TYPE = 'scvmm'
    private static final String SCVMM_HYPERVISOR_PROVISION_TYPE = 'scvmm-hypervisor'
    private static final String SCVMM_INSTANCE_NAME = 'SCVMM Instance'
    private static final String MORPHEUS_SCVMM_NODE = 'morpheus-scvmm-node'
    private static final String DOCKER_ENGINE = 'docker'
    private static final String EMPTY_DESCRIPTION = ''
    private static final String UNMANAGED_NODE_TYPE = 'unmanaged'
    private static final String MORPHEUS_VM_NODE = 'morpheus-vm-node'
    private static final String MORPHEUS_WINDOWS_VM_NODE = 'morpheus-windows-vm-node'
    private static final String MORPHEUS_NODE = 'morpheus-node'
    private static final String KUBE_MASTER_NODE = 'kube-master'
    private static final String KUBE_WORKER_NODE = 'kube-worker'
    private static final String PROVISION_TYPE_SCVMM = 'provisionType.scvmm'
    private static final String SCVMM_CAPABILITY_PROFILE_FIELD = 'scvmmCapabilityProfile'
    private static final String CAPABILITY_PROFILE_NAME = 'capability profile'
    private static final String SCVMM_VM_CODE = 'scvmmVm'
    private static final int DISPLAY_ORDER_OPTION_TEN = 10

    /**
     * Create the SSH options.
     *
     * @return The SSH options.
     */
    private static Collection<OptionType> createSshOptions() {
        Collection<OptionType> sshOptions = []
        sshOptions << new OptionType(code: 'computeServerType.global.sshHost')
        sshOptions << new OptionType(code: 'computeServerType.global.sshPort')
        sshOptions << new OptionType(code: 'computeServerType.global.sshUsername')
        sshOptions << new OptionType(code: 'computeServerType.global.sshPassword')
        return sshOptions
    }

    /**
     * Grabs all {@link ComputeServerType} objects that this CloudProvider can represent during
     * a sync or during a provision.
     * @return collection of ComputeServerType
     */
    @SuppressWarnings('MethodSize')
    static Collection<ComputeServerType> getAllComputeServerTypes() {
        Collection<ComputeServerType> serverTypes = []
        Collection<OptionType> sshOptions = createSshOptions()

        // Host option type is used by multiple compute server types.
        OptionType hostOptionType = new OptionType(code: 'computeServerType.scvmm.capabilityProfile',
                inputType: OptionType.InputType.SELECT, name: CAPABILITY_PROFILE_NAME, category: PROVISION_TYPE_SCVMM,
                optionSourceType: SCVMM_SOURCE_TYPE, fieldName: SCVMM_CAPABILITY_PROFILE_FIELD,
                fieldCode: 'gomorpheus.optiontype.CapabilityProfile', fieldLabel: CAPABILITY_PROFILE_NAME,
                fieldContext: CONFIG_CONTEXT, fieldGroup: 'Options', required: true, enabled: true,
                optionSource: SCVMM_CAPABILITY_PROFILE_FIELD, editable: true, global: false, placeHolder: null,
                helpBlock: '', defaultValue: null, custom: false, displayOrder: DISPLAY_ORDER_OPTION_TEN,
                fieldClass: null
        )

        serverTypes << new ComputeServerType(code: UNMANAGED_NODE_TYPE, name: 'Linux VM', description: 'vm',
                platform: PlatformType.linux, nodeType: UNMANAGED_NODE_TYPE, enabled: true, selectable: false,
                externalDelete: false, managed: false, controlPower: false, controlSuspend: false, creatable: true,
                computeService: 'unmanagedComputeService', displayOrder: 100, hasAutomation: false,
                containerHypervisor: false, bareMetalHost: false, vmHypervisor: false, agentType: null,
                managedServerType: 'managed', guestVm: true, provisionTypeCode: UNMANAGED_NODE_TYPE,
                optionTypes: sshOptions
        )

        // scvmm
        serverTypes << new ComputeServerType(code: SCVMM_CONTROLLER_CODE, name: 'SCVMM Manager',
                description: EMPTY_DESCRIPTION, platform: PlatformType.windows, nodeType: MORPHEUS_SCVMM_NODE,
                enabled: true, selectable: false, externalDelete: false, managed: false, controlPower: false,
                controlSuspend: false, creatable: false, computeService: SCVMM_COMPUTE_SERVICE, displayOrder: 0,
                hasAutomation: false, containerHypervisor: false, bareMetalHost: false, vmHypervisor: true,
                agentType: ComputeServerType.AgentType.node, provisionTypeCode: SCVMM_HYPERVISOR_PROVISION_TYPE
        )

        // vms
        serverTypes << new ComputeServerType(code: 'scvmmHypervisor', name: 'SCVMM Hypervisor',
                description: EMPTY_DESCRIPTION, platform: PlatformType.windows, nodeType: MORPHEUS_SCVMM_NODE,
                enabled: true, selectable: false, externalDelete: false, managed: false, controlPower: false,
                controlSuspend: false, creatable: false, computeService: SCVMM_COMPUTE_SERVICE, displayOrder: 0,
                hasAutomation: false, containerHypervisor: false, bareMetalHost: false, vmHypervisor: true,
                agentType: ComputeServerType.AgentType.node, provisionTypeCode: SCVMM_HYPERVISOR_PROVISION_TYPE
        )
        serverTypes << new ComputeServerType(code: 'scvmmWindows', name: 'SCVMM Windows Node',
                description: EMPTY_DESCRIPTION, platform: PlatformType.windows, nodeType: 'morpheus-windows-node',
                enabled: true, selectable: false, externalDelete: true, managed: true, controlPower: true,
                controlSuspend: false, creatable: false, computeService: SCVMM_COMPUTE_SERVICE, displayOrder: 7,
                hasAutomation: true, reconfigureSupported: true, containerHypervisor: false, bareMetalHost: false,
                vmHypervisor: false, agentType: ComputeServerType.AgentType.node, guestVm: true,
                provisionTypeCode: SCVMM_PROVISION_TYPE
        )
        serverTypes << new ComputeServerType(code: SCVMM_VM_CODE, name: SCVMM_INSTANCE_NAME,
                description: EMPTY_DESCRIPTION, platform: PlatformType.linux, nodeType: MORPHEUS_VM_NODE,
                enabled: true, selectable: false, externalDelete: true, managed: true, controlPower: true,
                controlSuspend: false, creatable: false, computeService: SCVMM_COMPUTE_SERVICE, displayOrder: 0,
                hasAutomation: true, reconfigureSupported: true, containerHypervisor: false, bareMetalHost: false,
                vmHypervisor: false, agentType: ComputeServerType.AgentType.guest, guestVm: true,
                provisionTypeCode: SCVMM_PROVISION_TYPE
        )
        // windows container host - not used
        serverTypes << new ComputeServerType(code: 'scvmmWindowsVm', name: 'SCVMM Windows Instance',
                description: EMPTY_DESCRIPTION, platform: PlatformType.windows, nodeType: MORPHEUS_WINDOWS_VM_NODE,
                enabled: true, selectable: false, externalDelete: true, managed: true, controlPower: true,
                controlSuspend: false, creatable: false, computeService: SCVMM_COMPUTE_SERVICE, displayOrder: 0,
                hasAutomation: true, reconfigureSupported: true, containerHypervisor: false, bareMetalHost: false,
                vmHypervisor: false, agentType: ComputeServerType.AgentType.guest, guestVm: true,
                provisionTypeCode: SCVMM_PROVISION_TYPE
        )
        serverTypes << new ComputeServerType(code: 'scvmmUnmanaged', name: SCVMM_INSTANCE_NAME,
                description: 'scvmm vm',
                platform: PlatformType.linux, nodeType: UNMANAGED_NODE_TYPE, enabled: true, selectable: false,
                externalDelete: true, managed: false, controlPower: true, controlSuspend: false, creatable: false,
                computeService: SCVMM_COMPUTE_SERVICE, displayOrder: 99, hasAutomation: false,
                containerHypervisor: false,
                bareMetalHost: false, vmHypervisor: false, agentType: ComputeServerType.AgentType.guest,
                managedServerType: SCVMM_VM_CODE, guestVm: true, provisionTypeCode: SCVMM_PROVISION_TYPE
        )

        // docker
        serverTypes << new ComputeServerType(code: 'scvmmLinux', name: 'SCVMM Docker Host',
                description: EMPTY_DESCRIPTION,
                platform: PlatformType.linux, nodeType: MORPHEUS_NODE, enabled: true, selectable: false,
                externalDelete: true, managed: true, controlPower: true, controlSuspend: false,
                creatable: false, computeService: SCVMM_COMPUTE_SERVICE, displayOrder: 6, hasAutomation: true,
                reconfigureSupported: true, containerHypervisor: true, bareMetalHost: false, vmHypervisor: false,
                agentType: ComputeServerType.AgentType.node, containerEngine: DOCKER_ENGINE,
                provisionTypeCode: SCVMM_PROVISION_TYPE, computeTypeCode: 'docker-host', optionTypes: [hostOptionType]
        )

        // kubernetes
        serverTypes << new ComputeServerType(code: 'scvmmKubeMaster', name: 'SCVMM Kubernetes Master',
                description: EMPTY_DESCRIPTION, platform: PlatformType.linux, nodeType: KUBE_MASTER_NODE,
                hasMaintenanceMode: true, reconfigureSupported: true, enabled: true, selectable: false,
                externalDelete: true, managed: true, controlPower: true, controlSuspend: true, creatable: true,
                supportsConsoleKeymap: true, computeService: SCVMM_COMPUTE_SERVICE,
                displayOrder: DISPLAY_ORDER_OPTION_TEN, hasAutomation: true, containerHypervisor: true,
                bareMetalHost: false, vmHypervisor: false, agentType: ComputeServerType.AgentType.host,
                containerEngine: DOCKER_ENGINE, provisionTypeCode: SCVMM_PROVISION_TYPE,
                computeTypeCode: KUBE_MASTER_NODE, optionTypes: [hostOptionType]
        )
        serverTypes << new ComputeServerType(code: 'scvmmKubeWorker', name: 'SCVMM Kubernetes Worker',
                description: EMPTY_DESCRIPTION, platform: PlatformType.linux, nodeType: KUBE_WORKER_NODE,
                hasMaintenanceMode: true, reconfigureSupported: true, enabled: true, selectable: false,
                externalDelete: true, managed: true, controlPower: true, controlSuspend: true, creatable: true,
                supportsConsoleKeymap: true, computeService: SCVMM_COMPUTE_SERVICE,
                displayOrder: DISPLAY_ORDER_OPTION_TEN, hasAutomation: true, containerHypervisor: true,
                bareMetalHost: false, vmHypervisor: false, agentType: ComputeServerType.AgentType.guest,
                containerEngine: DOCKER_ENGINE, provisionTypeCode: SCVMM_PROVISION_TYPE,
                computeTypeCode: KUBE_WORKER_NODE, optionTypes: [hostOptionType]
        )
        return serverTypes
    }
}
