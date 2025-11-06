package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.synchronous.MorpheusSynchronousOsTypeService
import com.morpheusdata.core.synchronous.MorpheusSynchronousWorkloadService
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.*
import com.morpheusdata.scvmm.ScvmmApiService
import spock.lang.Specification
import spock.lang.Unroll
import io.reactivex.rxjava3.core.Single

class VirtualMachineSyncSpec extends Specification {

    private TestableVirtualMachineSync virtualMachineSync
    private MorpheusContext morpheusContext
    private CloudProvider cloudProvider
    private ScvmmApiService mockApiService
    private MorpheusComputeServerService asyncComputeServerService
    private MorpheusSynchronousComputeServerService computeServerService
    private MorpheusSynchronousOsTypeService osTypeService
    private MorpheusSynchronousWorkloadService workloadService
    private Cloud cloud
    private ComputeServer node
    private ComputeServer existingServer
    private ComputeServer parentHost
    private ServicePlan mockServicePlan
    private ServicePlan fallbackPlan
    private OsType mockOsType
    private ComputeServerType defaultServerType

    // Test class that allows us to inject mock apiService
    private static class TestableVirtualMachineSync extends VirtualMachineSync {

        TestableVirtualMachineSync(ComputeServer node, Cloud cloud, MorpheusContext context, CloudProvider cloudProvider, ScvmmApiService apiService) {
            super(node, cloud, context, cloudProvider)
            // Use reflection to set the private apiService field
            def field = VirtualMachineSync.class.getDeclaredField('apiService')
            field.setAccessible(true)
            field.set(this, apiService)
        }
    }

    def setup() {
        // Setup mock context and services
        morpheusContext = Mock(MorpheusContext)
        cloudProvider = Mock(CloudProvider)
        mockApiService = Mock(ScvmmApiService)

        // Mock services
        computeServerService = Mock(MorpheusSynchronousComputeServerService)
        asyncComputeServerService = Mock(MorpheusComputeServerService)
        osTypeService = Mock(MorpheusSynchronousOsTypeService)
        workloadService = Mock(MorpheusSynchronousWorkloadService)

        def morpheusServices = Mock(MorpheusServices) {
            getComputeServer() >> computeServerService
            getOsType() >> osTypeService
            getWorkload() >> workloadService
        }

        def morpheusAsyncServices = Mock(MorpheusAsyncServices) {
            getComputeServer() >> asyncComputeServerService
        }

        // Configure context mocks
        morpheusContext.getAsync() >> morpheusAsyncServices
        morpheusContext.getServices() >> morpheusServices

        // Create test objects
        cloud = new Cloud(
            id: 1L,
            name: "test-scvmm-cloud",
            accountCredentialData: [username: "domain\\user", password: "password"]
        )
        cloud.setConfigProperty('enableVnc', 'true')
        cloud.setConfigProperty('username', 'domain\\user')
        cloud.setConfigProperty('password', 'password')

        node = new ComputeServer(
            id: 2L,
            name: "scvmm-controller",
            externalId: "controller-123"
        )

        parentHost = new ComputeServer(
            id: 3L,
            name: "host-01",
            externalId: "host-123"
        )

        existingServer = new ComputeServer(
            id: 4L,
            name: "old-vm-name",
            externalId: "vm-123",
            internalId: "old-vm-id",
            externalIp: "192.168.1.10",
            internalIp: "10.0.0.10",
            sshHost: "10.0.0.10",
            maxCores: 1L,
            maxMemory: 2147483648L,
            powerState: ComputeServer.PowerState.off,
            status: "running",
            interfaces: [],
            computeServerType: new ComputeServerType(guestVm: true),
            capacityInfo: new ComputeCapacityInfo(maxCores: 1L, maxMemory: 2147483648L)
        )

        mockServicePlan = new ServicePlan(id: 1L, name: "test-plan")
        fallbackPlan = new ServicePlan(id: 2L, name: "fallback-plan")
        mockOsType = new OsType(id: 1L, code: "ubuntu", platform: "linux")
        defaultServerType = new ComputeServerType(id: 1L, code: "scvmmUnmanaged")

        // Create testable VirtualMachineSync instance
        virtualMachineSync = new TestableVirtualMachineSync(node, cloud, morpheusContext, cloudProvider, mockApiService)
    }

    @Unroll
    def "updateMatchedVirtualMachines should update server properties and save when changes detected"() {
        given: "A list of update items with server changes"
        def masterItem = [
            ID: "vm-123",
            Name: "new-vm-name",
            VMId: "new-vm-id",
            IpAddress: "192.168.1.20",
            InternalIp: "10.0.0.20",
            CPUCount: "4",
            Memory: "8192",
            HostId: "host-123",
            OperatingSystem: "Ubuntu Linux (64-bit)",
            OperatingSystemWindows: "false",
            VirtualMachineState: "Running",
            Disks: []
        ]

        def updateItem = new SyncTask.UpdateItem<ComputeServer, Map>(
            existingItem: existingServer,
            masterItem: masterItem
        )

        def updateList = [updateItem]
        def hosts = [parentHost]
        def availablePlans = [mockServicePlan]

        when: "updateMatchedVirtualMachines is called"
        virtualMachineSync.updateMatchedVirtualMachines(
            updateList,
            availablePlans,
            fallbackPlan,
            hosts,
            true, // console enabled
            defaultServerType
        )

        then: "services are called to load and update servers"
        1 * computeServerService.list(_ as DataQuery) >> [existingServer]
        1 * mockApiService.getMapScvmmOsType(_, _, _) >> "ubuntu"
        1 * osTypeService.find(_ as DataQuery) >> mockOsType
        (2.._) * workloadService.list(_ as DataQuery) >> []  // Multiple calls due to power state change
        1 * asyncComputeServerService.bulkSave(_) >> Single.just([existingServer])

        and: "server properties are updated"
        existingServer.name == "new-vm-name"
        existingServer.internalId == "new-vm-id"
        existingServer.externalIp == "192.168.1.20"
        existingServer.internalIp == "10.0.0.20"
        existingServer.sshHost == "10.0.0.20"  // sshHost follows internalIp since original sshHost matched original internalIp
        existingServer.maxCores == 4L
        existingServer.maxMemory == 8589934592L
        existingServer.parentServer == parentHost
        existingServer.powerState == ComputeServer.PowerState.on
        existingServer.consoleType == "vmrdp"
        existingServer.consoleHost == "host-01"
        existingServer.consolePort == 2179
        existingServer.sshUsername == "user"
        existingServer.consolePassword == "password"
    }

    @Unroll
    def "updateMatchedVirtualMachines should handle console disabled scenario"() {
        given: "A server update with console disabled"
        def masterItem = [
            ID: "vm-123",
            Name: "test-vm",
            VMId: "vm-id-123",
            CPUCount: "2",
            Memory: "4096",
            VirtualMachineState: "Stopped",
            Disks: []
        ]

        def updateItem = new SyncTask.UpdateItem<ComputeServer, Map>(
            existingItem: existingServer,
            masterItem: masterItem
        )

        def updateList = [updateItem]

        when: "updateMatchedVirtualMachines is called with console disabled"
        virtualMachineSync.updateMatchedVirtualMachines(
            updateList,
            [],
            fallbackPlan,
            [],
            false, // console disabled
            defaultServerType
        )

        then: "services are called appropriately"
        1 * computeServerService.list(_ as DataQuery) >> [existingServer]
        1 * mockApiService.getMapScvmmOsType(_, _, _) >> "other"
        1 * osTypeService.find(_ as DataQuery) >> mockOsType
        0 * workloadService.list(_ as DataQuery)  // No workload calls expected when power state doesn't change
        1 * asyncComputeServerService.bulkSave(_) >> Single.just([existingServer])

        and: "console properties are cleared"
        existingServer.consoleType == null
        existingServer.consoleHost == null
        existingServer.consolePort == null
        existingServer.powerState == ComputeServer.PowerState.off
    }

    @Unroll
    def "updateMatchedVirtualMachines should handle server in provisioning status"() {
        given: "A server in provisioning status"
        existingServer.status = "provisioning"

        def masterItem = [
            ID: "vm-123",
            Name: "test-vm",
            VMId: "vm-id-123"
        ]

        def updateItem = new SyncTask.UpdateItem<ComputeServer, Map>(
            existingItem: existingServer,
            masterItem: masterItem
        )

        def updateList = [updateItem]

        when: "updateMatchedVirtualMachines is called"
        virtualMachineSync.updateMatchedVirtualMachines(
            updateList,
            [],
            fallbackPlan,
            [],
            false,
            defaultServerType
        )

        then: "server is loaded but not updated due to provisioning status"
        1 * computeServerService.list(_ as DataQuery) >> [existingServer]
        0 * asyncComputeServerService.bulkSave(_)
    }

    @Unroll
    def "updateMatchedVirtualMachines should handle errors gracefully"() {
        given: "A server update that will cause an error"
        def masterItem = [
            ID: "vm-123",
            Name: "test-vm",
            VMId: "vm-id-123"
        ]

        def updateItem = new SyncTask.UpdateItem<ComputeServer, Map>(
            existingItem: existingServer,
            masterItem: masterItem
        )

        def updateList = [updateItem]

        when: "updateMatchedVirtualMachines is called and an error occurs"
        virtualMachineSync.updateMatchedVirtualMachines(
            updateList,
            [],
            fallbackPlan,
            [],
            false,
            defaultServerType
        )

        then: "error is handled gracefully"
        1 * computeServerService.list(_ as DataQuery) >> { throw new RuntimeException("Database error") }
        0 * asyncComputeServerService.bulkSave(_)

        and: "no exception is thrown from the method"
        noExceptionThrown()
    }
}
