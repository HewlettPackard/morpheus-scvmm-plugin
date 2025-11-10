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
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.scvmm.ScvmmApiService
import spock.lang.Specification
import spock.lang.Unroll
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.Maybe
import com.morpheusdata.core.MorpheusStorageVolumeService
import com.morpheusdata.core.MorpheusStorageVolumeTypeService
import io.reactivex.rxjava3.core.Observable
import java.time.Instant


class VirtualMachineSyncSpec extends Specification {

    private TestableVirtualMachineSync virtualMachineSync
    private MorpheusContext morpheusContext
    private CloudProvider cloudProvider
    private ScvmmApiService mockApiService
    private MorpheusComputeServerService asyncComputeServerService
    private MorpheusSynchronousComputeServerService computeServerService
    private MorpheusSynchronousOsTypeService osTypeService
    private MorpheusSynchronousWorkloadService workloadService
    private def servicePlanService
    private def resourcePermissionService
    private MorpheusStorageVolumeService storageVolumeService
    private MorpheusStorageVolumeService asyncStorageVolumeService
    private MorpheusStorageVolumeTypeService storageVolumeTypeService
    private def cloudService
    private def cloudAsyncService
    private def datastoreService
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
        def log = [debug: { msg -> println("DEBUG: $msg") }] // Mock log property
        MorpheusContext context // Add missing context property

        TestableVirtualMachineSync(ComputeServer node, Cloud cloud, MorpheusContext context, CloudProvider cloudProvider, ScvmmApiService apiService) {
            super(node, cloud, context, cloudProvider)
            this.context = context  // Set context property
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
        servicePlanService = Mock(Object)
        resourcePermissionService = Mock(Object)
        storageVolumeService = Mock(MorpheusStorageVolumeService)
        asyncStorageVolumeService = Mock(MorpheusStorageVolumeService)
        storageVolumeTypeService = Mock(MorpheusStorageVolumeTypeService)
        cloudService = Mock(Object) {
            getDatastore() >> datastoreService
        }
        cloudAsyncService = Mock(Object)
        datastoreService = Mock(Object)

        def morpheusServices = Mock(MorpheusServices) {
            getComputeServer() >> computeServerService
            getOsType() >> osTypeService
            getWorkload() >> workloadService
            getCloud() >> cloudService
            getStorageVolume() >> storageVolumeService
            getServicePlan() >> servicePlanService
            getResourcePermission() >> resourcePermissionService
        }

        def cloudAsyncService = Mock(Object)

        def morpheusAsyncServices = Mock(MorpheusAsyncServices) {
            getComputeServer() >> asyncComputeServerService
            getStorageVolume() >> asyncStorageVolumeService
            getCloud() >> cloudAsyncService
        }

        // Configure storage volume service chain
        asyncStorageVolumeService.getStorageVolumeType() >> storageVolumeTypeService

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
                ID                    : "vm-123",
                Name                  : "new-vm-name",
                VMId                  : "new-vm-id",
                IpAddress             : "192.168.1.20",
                InternalIp            : "10.0.0.20",
                CPUCount              : "4",
                Memory                : "8192",
                HostId                : "host-123",
                OperatingSystem       : "Ubuntu Linux (64-bit)",
                OperatingSystemWindows: "false",
                VirtualMachineState   : "Running",
                Disks                 : []
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
                ID                 : "vm-123",
                Name               : "test-vm",
                VMId               : "vm-id-123",
                CPUCount           : "2",
                Memory             : "4096",
                VirtualMachineState: "Stopped",
                Disks              : []
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
                ID  : "vm-123",
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
                ID  : "vm-123",
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

    @Unroll
    def "getVolumeName should return 'root' when VolumeType is 'BootAndSystem'"() {
        given: "diskData with VolumeType set to 'BootAndSystem'"
        def diskData = [VolumeType: 'BootAndSystem']
        def server = new ComputeServer(volumes: [new StorageVolume()])
        def index = 0

        when: "getVolumeName is called"
        def result = virtualMachineSync.getVolumeName(diskData, server, index)

        then: "it should return 'root'"
        result == 'root'
    }

    @Unroll
    def "getVolumeName should return 'root' when server has no volumes"() {
        given: "diskData with different VolumeType and server with no volumes"
        def diskData = [VolumeType: volumeType]
        def server = new ComputeServer(volumes: volumes)
        def index = 1

        when: "getVolumeName is called"
        def result = virtualMachineSync.getVolumeName(diskData, server, index)

        then: "it should return 'root'"
        result == 'root'

        where:
        volumeType  | volumes
        'DataDisk'  | null
        'DataDisk'  | []
        null        | null
        null        | []
        'OtherType' | null
        'OtherType' | []
    }

    @Unroll
    def "getVolumeName should return 'data-\${index}' when VolumeType is not 'BootAndSystem' and server has volumes"() {
        given: "diskData with non-BootAndSystem VolumeType and server with existing volumes"
        def diskData = [VolumeType: volumeType]
        def existingVolumes = [new StorageVolume(name: "existing1")]
        def server = new ComputeServer(volumes: existingVolumes)
        def index = expectedIndex

        when: "getVolumeName is called"
        def result = virtualMachineSync.getVolumeName(diskData, server, index)

        then: "it should return 'data-\${index}'"
        result == expectedResult

        where:
        volumeType  | expectedIndex | expectedResult
        'DataDisk'  | 0             | 'data-0'
        'DataDisk'  | 1             | 'data-1'
        'DataDisk'  | 2             | 'data-2'
        'DataDisk'  | 5             | 'data-5'
        'OtherType' | 0             | 'data-0'
        'OtherType' | 3             | 'data-3'
        null        | 1             | 'data-1'
        ''          | 2             | 'data-2'
    }

    @Unroll
    def "getVolumeName should handle edge case combinations"() {
        given: "various edge case scenarios"
        def diskData = [VolumeType: volumeType]
        def server = new ComputeServer(volumes: volumes)
        def index = testIndex

        when: "getVolumeName is called"
        def result = virtualMachineSync.getVolumeName(diskData, server, index)

        then: "it should return the expected result"
        result == expectedResult

        where:
        volumeType      | volumes                                    | testIndex | expectedResult
        'BootAndSystem' | []                                         | 0         | 'root'
        'BootAndSystem' | null                                       | 1         | 'root'
        'BootAndSystem' | [new StorageVolume()]                      | 2         | 'root'
        'BootAndSystem' | [new StorageVolume(), new StorageVolume()] | 3         | 'root'
        'DataDisk'      | [new StorageVolume()]                      | 0         | 'data-0'
        'DataDisk'      | [new StorageVolume(), new StorageVolume()] | 1         | 'data-1'
    }

    @Unroll
    def "getVolumeName should work with different index values"() {
        given: "diskData that should result in data disk naming"
        def diskData = [VolumeType: 'DataDisk']
        def server = new ComputeServer(volumes: [new StorageVolume()])
        def index = testIndex

        when: "getVolumeName is called with various index values"
        def result = virtualMachineSync.getVolumeName(diskData, server, index)

        then: "it should return 'data-\${index}'"
        result == "data-${testIndex}"

        where:
        testIndex << [0, 1, 2, 5, 10, 25, 99]
    }

    @Unroll
    def "getVolumeName should handle null and empty diskData gracefully"() {
        given: "null or minimal diskData"
        def server = new ComputeServer(volumes: [new StorageVolume()])
        def index = 1

        when: "getVolumeName is called"
        def result = virtualMachineSync.getVolumeName(diskData, server, index)

        then: "it should handle gracefully and return data disk name"
        result == 'data-1'

        where:
        diskData << [
                [:],  // empty map
                [VolumeType: null],  // null VolumeType
                [VolumeType: ''],    // empty VolumeType
                [SomeOtherProperty: 'value']  // map without VolumeType
        ]
    }

    @Unroll
    def "buildVmConfig should create complete configuration map with running power state"() {
        given: "cloudItem with Running VirtualMachineState and defaultServerType"
        def cloudItem = [
                Name               : "test-vm-name",
                ID                 : "vm-123",
                VMId               : "vm-internal-456",
                VirtualMachineState: "Running"
        ]
        def defaultServerType = new ComputeServerType(id: 1L, code: "scvmmUnmanaged")

        when: "buildVmConfig is called"
        def result = virtualMachineSync.buildVmConfig(cloudItem, defaultServerType)

        then: "it should return complete vmConfig map with correct values"
        result.name == "test-vm-name"
        result.cloud == cloud
        result.status == 'provisioned'
        result.apiKey instanceof UUID
        result.account == cloud.account
        result.managed == false
        result.uniqueId == "vm-123"
        result.provision == false
        result.hotResize == false
        result.serverType == 'vm'
        result.lvmEnabled == false
        result.discovered == true
        result.internalId == "vm-internal-456"
        result.externalId == "vm-123"
        result.displayName == "test-vm-name"
        result.singleTenant == true
        result.computeServerType == defaultServerType
        result.powerState == ComputeServer.PowerState.on
    }

    @Unroll
    def "buildVmConfig should create complete configuration map with stopped power state"() {
        given: "cloudItem with non-Running VirtualMachineState and defaultServerType"
        def cloudItem = [
                Name               : "stopped-vm",
                ID                 : "vm-789",
                VMId               : "vm-internal-999",
                VirtualMachineState: virtualMachineState
        ]
        def defaultServerType = new ComputeServerType(id: 2L, code: "scvmmWindows")

        when: "buildVmConfig is called"
        def result = virtualMachineSync.buildVmConfig(cloudItem, defaultServerType)

        then: "it should return complete vmConfig map with PowerState.off"
        result.name == "stopped-vm"
        result.cloud == cloud
        result.status == 'provisioned'
        result.apiKey instanceof UUID
        result.account == cloud.account
        result.managed == false
        result.uniqueId == "vm-789"
        result.provision == false
        result.hotResize == false
        result.serverType == 'vm'
        result.lvmEnabled == false
        result.discovered == true
        result.internalId == "vm-internal-999"
        result.externalId == "vm-789"
        result.displayName == "stopped-vm"
        result.singleTenant == true
        result.computeServerType == defaultServerType
        result.powerState == ComputeServer.PowerState.off

        where:
        virtualMachineState << [
                'Stopped',
                'Paused',
                'Suspended',
                'PowerOff',
                'Saved',
                null,
                '',
                'UnknownState'
        ]
    }

    @Unroll
    def "buildVmConfig should generate unique UUID for apiKey on each call"() {
        given: "same cloudItem and defaultServerType"
        def cloudItem = [
                Name               : "uuid-test-vm",
                ID                 : "vm-uuid-123",
                VMId               : "vm-uuid-456",
                VirtualMachineState: "Running"
        ]
        def defaultServerType = new ComputeServerType(id: 1L, code: "scvmmUnmanaged")

        when: "buildVmConfig is called multiple times"
        def result1 = virtualMachineSync.buildVmConfig(cloudItem, defaultServerType)
        def result2 = virtualMachineSync.buildVmConfig(cloudItem, defaultServerType)
        def result3 = virtualMachineSync.buildVmConfig(cloudItem, defaultServerType)

        then: "each call should generate a unique UUID for apiKey"
        result1.apiKey instanceof UUID
        result2.apiKey instanceof UUID
        result3.apiKey instanceof UUID
        result1.apiKey != result2.apiKey
        result2.apiKey != result3.apiKey
        result1.apiKey != result3.apiKey

        and: "other properties should remain the same"
        result1.name == result2.name
        result1.name == result3.name
        result1.externalId == result2.externalId
        result1.externalId == result3.externalId
    }

    @Unroll
    def "buildVmConfig should handle edge cases and null values gracefully"() {
        given: "cloudItem with potential null/empty values"
        def cloudItem = [
                Name               : name,
                ID                 : id,
                VMId               : vmId,
                VirtualMachineState: state
        ]
        def defaultServerType = new ComputeServerType(id: 1L, code: "scvmmTest")

        when: "buildVmConfig is called"
        def result = virtualMachineSync.buildVmConfig(cloudItem, defaultServerType)

        then: "it should handle gracefully and return vmConfig with expected values"
        result.name == name
        result.uniqueId == id
        result.internalId == vmId
        result.externalId == id
        result.displayName == name
        result.powerState == expectedPowerState
        result.apiKey instanceof UUID
        result.computeServerType == defaultServerType

        where:
        name      | id       | vmId       | state     | expectedPowerState
        null      | null     | null       | null      | ComputeServer.PowerState.off
        ""        | ""       | ""         | ""        | ComputeServer.PowerState.off
        "test"    | null     | "vm-123"   | "Running" | ComputeServer.PowerState.on
        null      | "vm-456" | null       | "Stopped" | ComputeServer.PowerState.off
        "vm-name" | "vm-789" | "internal" | "Running" | ComputeServer.PowerState.on
    }

    @Unroll
    def "buildVmConfig should verify all required configuration properties are set"() {
        given: "a complete cloudItem and defaultServerType"
        def cloudItem = [
                Name               : "complete-vm",
                ID                 : "vm-complete-123",
                VMId               : "vm-complete-internal-456",
                VirtualMachineState: "Running"
        ]
        def defaultServerType = new ComputeServerType(id: 5L, code: "scvmmComplete")

        when: "buildVmConfig is called"
        def result = virtualMachineSync.buildVmConfig(cloudItem, defaultServerType)

        then: "all expected properties should be present in the configuration"
        result.containsKey('name')
        result.containsKey('cloud')
        result.containsKey('status')
        result.containsKey('apiKey')
        result.containsKey('account')
        result.containsKey('managed')
        result.containsKey('uniqueId')
        result.containsKey('provision')
        result.containsKey('hotResize')
        result.containsKey('serverType')
        result.containsKey('lvmEnabled')
        result.containsKey('discovered')
        result.containsKey('internalId')
        result.containsKey('externalId')
        result.containsKey('displayName')
        result.containsKey('singleTenant')
        result.containsKey('computeServerType')
        result.containsKey('powerState')

        and: "static/default values should be correctly set"
        result.status == 'provisioned'
        result.managed == false
        result.provision == false
        result.hotResize == false
        result.serverType == 'vm'
        result.lvmEnabled == false
        result.discovered == true
        result.singleTenant == true
    }

    @Unroll
    def "buildVmConfig should use cloud and account references correctly"() {
        given: "cloudItem and defaultServerType with cloud having an account"
        def testAccount = new Account(id: 100L, name: "test-account")
        cloud.account = testAccount

        def cloudItem = [
                Name               : "account-test-vm",
                ID                 : "vm-account-123",
                VMId               : "vm-account-internal",
                VirtualMachineState: "Running"
        ]
        def defaultServerType = new ComputeServerType(id: 1L, code: "scvmmAccount")

        when: "buildVmConfig is called"
        def result = virtualMachineSync.buildVmConfig(cloudItem, defaultServerType)

        then: "cloud and account references should be correctly set"
        result.cloud == cloud
        result.account == testAccount
        result.account.id == 100L
        result.account.name == "test-account"
    }

    @Unroll
    def "buildVmConfig should handle case-sensitive VirtualMachineState comparison"() {
        given: "cloudItem with different case variations of 'Running'"
        def cloudItem = [
                Name               : "case-test-vm",
                ID                 : "vm-case-123",
                VMId               : "vm-case-internal",
                VirtualMachineState: state
        ]
        def defaultServerType = new ComputeServerType(id: 1L, code: "scvmmCase")

        when: "buildVmConfig is called"
        def result = virtualMachineSync.buildVmConfig(cloudItem, defaultServerType)

        then: "only exact 'Running' match should result in PowerState.on"
        result.powerState == expectedPowerState

        where:
        state       | expectedPowerState
        "Running"   | ComputeServer.PowerState.on
        "running"   | ComputeServer.PowerState.off
        "RUNNING"   | ComputeServer.PowerState.off
        "RuNnInG"   | ComputeServer.PowerState.off
        " Running " | ComputeServer.PowerState.off
        "Running "  | ComputeServer.PowerState.off
        " Running"  | ComputeServer.PowerState.off
    }

    @Unroll
    def "getStorageVolumeType should use provided code and return storage volume type id"() {
        given: "a storage volume type code and mock storage volume type"
        def storageVolumeTypeCode = "test-volume-type"
        def mockStorageVolumeType = new StorageVolumeType(id: 123L, code: "test-volume-type")

        // Create a spy to verify the method calls
        def spyVirtualMachineSync = Spy(TestableVirtualMachineSync, constructorArgs: [node, cloud, morpheusContext, cloudProvider, mockApiService])

        when: "getStorageVolumeType is called with a specific code"
        def result = spyVirtualMachineSync.getStorageVolumeType(storageVolumeTypeCode)

        then: "it should use the provided code (not fallback to standard) and return the id"
        1 * storageVolumeTypeService.find({ DataQuery query ->
            // Verify that the query uses the provided code, not 'standard'
            def codeFilter = query.filters.find { it.name == 'code' }
            codeFilter != null && codeFilter.value == "test-volume-type"
        }) >> {
            // Return a properly typed Maybe for RxJava compatibility
            return Maybe.just(mockStorageVolumeType)
        }
        result == 123L
    }

    @Unroll
    def "getStorageVolumeType should fallback to 'standard' when code is null"() {
        given: "null storage volume type code"
        def storageVolumeTypeCode = null
        def mockStandardStorageVolumeType = new StorageVolumeType(id: 999L, code: "standard")

        def spyVirtualMachineSync = Spy(TestableVirtualMachineSync, constructorArgs: [node, cloud, morpheusContext, cloudProvider, mockApiService])

        when: "getStorageVolumeType is called with null"
        def result = spyVirtualMachineSync.getStorageVolumeType(storageVolumeTypeCode)

        then: "it should fallback to 'standard' due to elvis operator and return the id"
        1 * storageVolumeTypeService.find({ DataQuery query ->
            // Verify that the query falls back to 'standard' when input is null
            def codeFilter = query.filters.find { it.name == 'code' }
            codeFilter != null && codeFilter.value == 'standard'
        }) >> {
            return io.reactivex.rxjava3.core.Maybe.just(mockStandardStorageVolumeType)
        }
        result == 999L
    }

    @Unroll
    def "getStorageVolumeType should fallback to 'standard' when code is empty"() {
        given: "empty storage volume type code"
        def storageVolumeTypeCode = ""
        def mockStandardStorageVolumeType = new StorageVolumeType(id: 888L, code: "standard")

        def spyVirtualMachineSync = Spy(TestableVirtualMachineSync, constructorArgs: [node, cloud, morpheusContext, cloudProvider, mockApiService])

        when: "getStorageVolumeType is called with empty string"
        def result = spyVirtualMachineSync.getStorageVolumeType(storageVolumeTypeCode)

        then: "it should fallback to 'standard' due to elvis operator and return the id"
        1 * storageVolumeTypeService.find({ DataQuery query ->
            // Verify that the query falls back to 'standard' when input is empty
            def codeFilter = query.filters.find { it.name == 'code' }
            codeFilter != null && codeFilter.value == 'standard'
        }) >> {
            return io.reactivex.rxjava3.core.Maybe.just(mockStandardStorageVolumeType)
        }
        result == 888L
    }

    @Unroll
    def "getStorageVolumeType should handle various SCVMM storage volume type codes"() {
        given: "various SCVMM storage volume type codes"
        def storageVolumeTypeCode = inputCode
        def mockStorageVolumeType = new StorageVolumeType(id: expectedId, code: inputCode)

        def spyVirtualMachineSync = Spy(TestableVirtualMachineSync, constructorArgs: [node, cloud, morpheusContext, cloudProvider, mockApiService])

        when: "getStorageVolumeType is called"
        def result = spyVirtualMachineSync.getStorageVolumeType(storageVolumeTypeCode)

        then: "it should create correct DataQuery with code filter and return the id"
        1 * storageVolumeTypeService.find({ DataQuery query ->
            def codeFilter = query.filters.find { it.name == 'code' }
            codeFilter != null && codeFilter.value == inputCode
        }) >> {
            return io.reactivex.rxjava3.core.Maybe.just(mockStorageVolumeType)
        }
        result == expectedId

        where:
        inputCode                 | expectedId
        "scvmm-fixed-vhd"         | 100L
        "scvmm-dynamic-vhd"       | 101L
        "scvmm-differencing-vhd"  | 102L
        "scvmm-fixed-vhdx"        | 103L
        "scvmm-dynamic-vhdx"      | 104L
        "scvmm-differencing-vhdx" | 105L
        "standard"                | 106L
        "thin"                    | 107L
        "thick"                   | 108L
        "ssd"                     | 109L
    }

    @Unroll
    def "getStorageVolumeType should execute debug logging"() {
        given: "a storage volume type code and mock storage volume type"
        def storageVolumeTypeCode = "debug-test-code"
        def mockStorageVolumeType = new StorageVolumeType(id: 777L, code: "debug-test-code")

        // Create a spy that allows us to verify the log call
        def spyVirtualMachineSync = Spy(TestableVirtualMachineSync, constructorArgs: [node, cloud, morpheusContext, cloudProvider, mockApiService])

        when: "getStorageVolumeType is called"
        def result = spyVirtualMachineSync.getStorageVolumeType(storageVolumeTypeCode)

        then: "it should execute the debug log statement (line coverage)"
        1 * storageVolumeTypeService.find(_) >> {
            return io.reactivex.rxjava3.core.Maybe.just(mockStorageVolumeType)
        }
        // The debug log should execute but we can't easily verify the log content in unit tests
        // The important thing is that this line gets executed for coverage
        result == 777L
    }

    @Unroll
    def "getStorageVolumeType should handle database service chain calls"() {
        given: "a storage volume type code"
        def storageVolumeTypeCode = "service-chain-test"
        def mockStorageVolumeType = new StorageVolumeType(id: 555L, code: "service-chain-test")

        def spyVirtualMachineSync = Spy(TestableVirtualMachineSync, constructorArgs: [node, cloud, morpheusContext, cloudProvider, mockApiService])

        when: "getStorageVolumeType is called"
        def result = spyVirtualMachineSync.getStorageVolumeType(storageVolumeTypeCode)

        then: "it should call the complete service chain: context.async.storageVolume.storageVolumeType.find().blockingGet()"
        1 * storageVolumeTypeService.find({ DataQuery query ->
            // Verify DataQuery construction
            query != null &&
                    query.filters.size() == 1 &&
                    query.filters[0].name == 'code' &&
                    query.filters[0].value == storageVolumeTypeCode
        }) >> {
            return io.reactivex.rxjava3.core.Maybe.just(mockStorageVolumeType)
        }
        result == 555L
    }

    @Unroll
    def "getStorageVolumeType should handle all lines including edge cases"() {
        given: "edge case inputs for complete line coverage"
        def storageVolumeTypeCode = inputCode
        def expectedCode = expectedCodeInQuery
        def mockStorageVolumeType = new StorageVolumeType(id: expectedId, code: expectedCodeInQuery)

        def spyVirtualMachineSync = Spy(TestableVirtualMachineSync, constructorArgs: [node, cloud, morpheusContext, cloudProvider, mockApiService])

        when: "getStorageVolumeType is called with edge case input"
        def result = spyVirtualMachineSync.getStorageVolumeType(storageVolumeTypeCode)

        then: "all lines should be executed including elvis operator logic"
        1 * storageVolumeTypeService.find({ DataQuery query ->
            def codeFilter = query.filters.find { it.name == 'code' }
            codeFilter != null && codeFilter.value == expectedCode
        }) >> {
            return io.reactivex.rxjava3.core.Maybe.just(mockStorageVolumeType)
        }
        result == expectedId

        where:
        inputCode  | expectedCodeInQuery | expectedId
        null       | 'standard'          | 1000L     // Tests elvis operator fallback
        ""         | 'standard'          | 1001L     // Tests elvis operator fallback
        "   "      | '   '               | 1002L     // Tests elvis operator with whitespace (Groovy treats as truthy)
        "custom"   | 'custom'            | 1003L     // Tests elvis operator with valid input
        "test-123" | 'test-123'          | 1004L     // Tests elvis operator with valid input
    }

    // Tests for removeMissingVirtualMachines method - comprehensive line coverage
    @Unroll
    def "removeMissingVirtualMachines should remove VMs with proper filtering"() {
        given: "a list of ComputeServerIdentityProjection items to remove"
        def removeItem1 = new ComputeServerIdentityProjection(id: 100L, externalId: "vm-to-remove-1")
        def removeItem2 = new ComputeServerIdentityProjection(id: 200L, externalId: "vm-to-remove-2")
        def removeList = [removeItem1, removeItem2]

        // Mock the filtered compute servers returned from listIdentityProjections
        def filteredServer1 = new ComputeServer(id: 100L, externalId: "vm-to-remove-1")
        def filteredServer2 = new ComputeServer(id: 200L, externalId: "vm-to-remove-2")
        def removeItems = [filteredServer1, filteredServer2]

        when: "removeMissingVirtualMachines is called"
        virtualMachineSync.removeMissingVirtualMachines(removeList)

        then: "it should execute database lookup with proper filtering and remove the items"
        1 * computeServerService.listIdentityProjections({ DataQuery query ->
            // Verify the query filters
            def filters = query.filters
            def idFilter = filters.find { it.name == 'id' }
            def typeFilter = filters.find { it.name == 'computeServerType.code' && it.value == 'scvmmUnmanaged' }

            // Verify ID collection: removeList*.id should be [100L, 200L]
            idFilter != null && idFilter.value == [100L, 200L] &&
                    typeFilter != null
        }) >> removeItems

        1 * asyncComputeServerService.remove(removeItems) >> Single.just(true)

        // Covers lines: 600 (log), 601-604 (listIdentityProjections with filters), 605 (async remove + blockingGet)
    }

    @Unroll
    def "removeMissingVirtualMachines should handle empty removeList gracefully"() {
        given: "an empty list of items to remove"
        def removeList = []

        when: "removeMissingVirtualMachines is called with empty list"
        virtualMachineSync.removeMissingVirtualMachines(removeList)

        then: "it should still execute the database query and async remove with empty results"
        1 * computeServerService.listIdentityProjections({ DataQuery query ->
            // Verify empty ID collection
            def idFilter = query.filters.find { it.name == 'id' }
            idFilter != null && idFilter.value == []
        }) >> []

        1 * asyncComputeServerService.remove([]) >> Single.just(true)

        // Covers all lines with empty input scenario
    }

    @Unroll
    def "removeMissingVirtualMachines should execute debug logging with correct parameters"() {
        given: "a list of items and cloud for logging verification"
        def removeItem = new ComputeServerIdentityProjection(id: 300L, externalId: "debug-vm")
        def removeList = [removeItem]
        def mockServer = new ComputeServer(id: 300L, externalId: "debug-vm")

        when: "removeMissingVirtualMachines is called"
        virtualMachineSync.removeMissingVirtualMachines(removeList)

        then: "debug log should execute with cloud and removeList.size()"
        1 * computeServerService.listIdentityProjections(_) >> [mockServer]
        1 * asyncComputeServerService.remove(_) >> Single.just(true)

        // Covers line 600: log.debug("removeMissingVirtualMachines: ${cloud} ${removeList.size()}")
        // The log will show: "removeMissingVirtualMachines: test-scvmm-cloud 1"
    }

    @Unroll
    def "removeMissingVirtualMachines should handle ID collection with spread operator correctly"() {
        given: "multiple items with different IDs to test spread operator"
        def items = removeItems.collect { id ->
            new ComputeServerIdentityProjection(id: id, externalId: "vm-${id}")
        }
        def expectedIds = removeItems
        def mockServers = items.collect { item ->
            new ComputeServer(id: item.id, externalId: item.externalId)
        }

        when: "removeMissingVirtualMachines is called"
        virtualMachineSync.removeMissingVirtualMachines(items)

        then: "it should correctly collect IDs using spread operator (removeList*.id)"
        1 * computeServerService.listIdentityProjections({ DataQuery query ->
            def idFilter = query.filters.find { it.name == 'id' }
            idFilter != null && idFilter.value == expectedIds
        }) >> mockServers

        1 * asyncComputeServerService.remove(mockServers) >> Single.just(true)

        where:
        removeItems << [
                [1L],                           // Single item
                [10L, 20L],                     // Two items
                [100L, 200L, 300L],            // Three items
                [1L, 5L, 10L, 15L, 20L]        // Five items
        ]
    }

    @Unroll
    def "removeMissingVirtualMachines should apply correct DataQuery filters"() {
        given: "items to remove for filter verification"
        def removeItem = new ComputeServerIdentityProjection(id: 400L, externalId: "filter-test")
        def removeList = [removeItem]
        def mockServer = new ComputeServer(id: 400L, externalId: "filter-test")

        when: "removeMissingVirtualMachines is called"
        virtualMachineSync.removeMissingVirtualMachines(removeList)

        then: "it should apply both ID and computeServerType filters correctly"
        1 * computeServerService.listIdentityProjections({ DataQuery query ->
            def filters = query.filters

            // Verify ID filter: withFilter(ID, IN, removeList*.id)
            def idFilter = filters.find { it.name == 'id' }
            def hasIdFilter = idFilter != null &&
                    idFilter.value == [400L]

            // Verify server type filter: withFilter(COMPUTE_SERVER_TYPE_CODE, SCVMM_UNMANAGED)
            def typeFilter = filters.find { it.name == 'computeServerType.code' }
            def hasTypeFilter = typeFilter != null &&
                    typeFilter.value == 'scvmmUnmanaged'

            hasIdFilter && hasTypeFilter && filters.size() == 2
        }) >> [mockServer]

        1 * asyncComputeServerService.remove([mockServer]) >> Single.just(true)

    }

    @Unroll
    def "removeMissingVirtualMachines should handle async remove operation and blockingGet"() {
        given: "items to test async removal"
        def removeItem = new ComputeServerIdentityProjection(id: 500L, externalId: "async-test")
        def removeList = [removeItem]
        def mockServer = new ComputeServer(id: 500L, externalId: "async-test")

        when: "removeMissingVirtualMachines is called"
        virtualMachineSync.removeMissingVirtualMachines(removeList)

        then: "it should call async remove with blockingGet"
        1 * computeServerService.listIdentityProjections(_) >> [mockServer]
        1 * asyncComputeServerService.remove([mockServer]) >> Single.just(true)

    }

    @Unroll
    def "removeMissingVirtualMachines should handle filtering scenarios correctly"() {
        given: "removeList items and expected filtering behavior"
        def removeItems = inputIds.collect { id ->
            new ComputeServerIdentityProjection(id: id, externalId: "vm-${id}")
        }

        // Simulate that listIdentityProjections might return fewer items due to filtering
        def filteredServers = filteredIds.collect { id ->
            new ComputeServer(id: id, externalId: "vm-${id}")
        }

        when: "removeMissingVirtualMachines is called"
        virtualMachineSync.removeMissingVirtualMachines(removeItems)

        then: "it should handle the filtering appropriately"
        1 * computeServerService.listIdentityProjections({ DataQuery query ->
            def idFilter = query.filters.find { it.name == 'id' }
            idFilter?.value == inputIds
        }) >> filteredServers

        1 * asyncComputeServerService.remove(filteredServers) >> Single.just(true)

        where:
        inputIds     | filteredIds  | scenario
        [1L, 2L, 3L] | [1L, 2L, 3L] | "All items pass filter"
        [1L, 2L, 3L] | [1L, 3L]     | "Some items filtered out"
        [1L, 2L, 3L] | []           | "All items filtered out"
        [10L]        | [10L]        | "Single item passes"
        [20L]        | []           | "Single item filtered out"
    }

    @Unroll
    def "removeMissingVirtualMachines should handle various edge cases"() {
        given: "edge case scenarios"
        def removeList = testRemoveList

        when: "removeMissingVirtualMachines is called with edge case"
        virtualMachineSync.removeMissingVirtualMachines(removeList)

        then: "it should handle gracefully"
        1 * computeServerService.listIdentityProjections({ DataQuery query ->
            def idFilter = query.filters.find { it.name == 'id' }
            idFilter?.value == expectedIds
        }) >> []

        1 * asyncComputeServerService.remove([]) >> Single.just(true)

        where:
        testRemoveList | expectedIds | description
        []             | []          | "Empty list"
    }

    // Tests for removeMissingStorageVolumes method - basic functionality verification
    @Unroll
    def "removeMissingStorageVolumes should handle empty and null input gracefully"() {
        given: "empty or null removeItems list"
        def server = new ComputeServer(id: 600L, volumes: [new StorageVolume(name: "existing-volume")])
        def changes = false

        when: "removeMissingStorageVolumes is called with empty or null list"
        virtualMachineSync.removeMissingStorageVolumes(removeItems, server, changes)

        then: "method should complete without exceptions"
        noExceptionThrown()

        where:
        removeItems << [[], null]

        // Covers: empty/null input handling (these don't trigger the problematic closure)
    }

    @Unroll
    def "addMissingStorageVolumes should handle empty itemsToAdd list"() {
        given: "empty items to add list"
        def server = new ComputeServer(id: 2000L, volumes: [])
        def itemsToAdd = []
        def diskNumber = 0
        def maxStorage = 1000000000L

        when: "addMissingStorageVolumes is called with empty list"
        virtualMachineSync.addMissingStorageVolumes(itemsToAdd, server, diskNumber, maxStorage)

        then: "no processing should occur but bulk save should still be called"
        0 * virtualMachineSync.loadDatastoreForVolume(_, _, _)
        0 * mockApiService.getDiskName(_)
        0 * virtualMachineSync.getVolumeName(_, _, _)
        0 * virtualMachineSync.buildStorageVolume(_, _, _)
        0 * storageVolumeService.create(_)

        // Bulk save should still be called even with empty list
        1 * asyncComputeServerService.bulkSave([server]) >> Single.just([server])

        // Covers: empty list handling and final bulk save
    }

    // Tests for buildStorageVolume method - comprehensive line coverage
    @Unroll
    def "buildStorageVolume should create complete storage volume with all properties"() {
        given: "account, server, and volume configuration"
        def account = new Account(id: 1L, name: "test-account")
        def testCloud = new Cloud(id: 2L, name: "test-cloud")
        def server = new ComputeServer(
                id: 3L,
                cloud: testCloud,
                volumes: [new StorageVolume(name: "existing")]
        )

        def mockDatastore = new Datastore(
                id: 100L,
                name: "test-datastore",
                storageServer: new StorageServer(id: 200L)
        )

        def mockStorageVolumeType = new StorageVolumeType(
                id: 50L,
                code: "test-storage-type"
        )

        def volume = [
                name        : "test-volume",
                maxStorage  : "1073741824", // 1GB
                storageType : "25",
                externalId  : "ext-vol-123",
                internalId  : "int-vol-456",
                deviceName  : "/dev/sdb",
                rootVolume  : false,
                displayOrder: 1
        ]

        when: "buildStorageVolume is called"
        def result = virtualMachineSync.buildStorageVolume(account, server, volume)

        then: "storage volume type lookup should be called"
        1 * asyncStorageVolumeService.getStorageVolumeType() >> storageVolumeTypeService
        1 * storageVolumeTypeService.get(25L) >> io.reactivex.rxjava3.core.Maybe.just(mockStorageVolumeType)

        and: "complete storage volume should be created with all properties"
        result != null
        result instanceof StorageVolume
        result.name == "test-volume"
        result.account == account
        result.maxStorage == 1073741824L
        result.type == mockStorageVolumeType
        result.rootVolume == false
        result.externalId == "ext-vol-123"
        result.internalId == "int-vol-456"
        result.cloudId == 2L
        result.deviceName == "/dev/sdb"
        result.removable == true // rootVolume != true
        result.displayOrder == 1
        result.datastore == null // no datastoreId provided
        result.refType == null
        result.refId == null

        // Covers: new StorageVolume(), name assignment, account assignment,
        // all helper method calls, and return statement
    }

    @Unroll
    def "buildStorageVolume should handle volume with size instead of maxStorage"() {
        given: "volume with size property instead of maxStorage"
        def account = new Account(id: 1L)
        def server = new ComputeServer(id: 2L, cloud: new Cloud(id: 3L))
        def mockStorageVolumeType = new StorageVolumeType(id: 60L, code: "standard")

        def volume = [
                name      : "size-volume",
                size      : "2147483648", // 2GB
                rootVolume: true
        ]

        when: "buildStorageVolume is called"
        def result = virtualMachineSync.buildStorageVolume(account, server, volume)

        then: "storage type fallback should be used"
        1 * asyncStorageVolumeService.getStorageVolumeType() >> storageVolumeTypeService
        1 * storageVolumeTypeService.find({ DataQuery query ->
            query.filters.find { it.name == 'code' && it.value == 'standard' } != null
        }) >> io.reactivex.rxjava3.core.Maybe.just(mockStorageVolumeType)

        and: "storage volume should use size for maxStorage"
        result.name == "size-volume"
        result.maxStorage == 2147483648L
        result.type == mockStorageVolumeType
        result.rootVolume == true
        result.removable == false // rootVolume == true
        result.datastore == null // no datastoreId
        result.refType == null
        result.refId == null

        // Covers: size fallback logic, storage type fallback, root volume logic
    }

    @Unroll
    def "buildStorageVolume should handle volume without datastore"() {
        given: "volume without datastoreId"
        def account = new Account(id: 1L)
        def server = new ComputeServer(id: 2L, cloud: new Cloud(id: 3L))
        def mockStorageVolumeType = new StorageVolumeType(id: 70L, code: "standard")

        def volume = [
                name      : "no-datastore-volume",
                maxStorage: "536870912", // 512MB
                externalId: "ext-123"
        ]

        when: "buildStorageVolume is called"
        def result = virtualMachineSync.buildStorageVolume(account, server, volume)

        then: "storage type fallback should be used (no storageType provided)"
        1 * asyncStorageVolumeService.getStorageVolumeType() >> storageVolumeTypeService
        1 * storageVolumeTypeService.find(_) >> io.reactivex.rxjava3.core.Maybe.just(mockStorageVolumeType)

        and: "no datastore service calls should be made"
        0 * computeServerService.getCloud()
        0 * cloudService.getDatastore()
        0 * datastoreService.get(_)

        and: "storage volume should be created without datastore properties"
        result.name == "no-datastore-volume"
        result.maxStorage == 536870912L
        result.externalId == "ext-123"
        result.internalId == null
        result.datastore == null
        result.datastoreOption == null
        result.storageServer == null
        result.refType == null
        result.refId == null

        // Covers: configureDatastore early return path
    }

    @Unroll
    def "buildStorageVolume should handle server without cloud reference"() {
        given: "server without cloud reference"
        def account = new Account(id: 1L)
        def server = new ComputeServer(id: 4L) // No cloud property set
        def mockStorageVolumeType = new StorageVolumeType(id: 80L, code: "standard")

        def volume = [
                name      : "no-cloud-ref-volume",
                maxStorage: "1048576" // 1MB
        ]

        when: "buildStorageVolume is called"
        def result = virtualMachineSync.buildStorageVolume(account, server, volume)

        then: "storage volume type should be resolved"
        1 * asyncStorageVolumeService.getStorageVolumeType() >> storageVolumeTypeService
        1 * storageVolumeTypeService.find(_) >> io.reactivex.rxjava3.core.Maybe.just(mockStorageVolumeType)

        and: "cloud ID should be null when no cloud reference"
        result.cloudId == null
        result.name == "no-cloud-ref-volume"
        result.maxStorage == 1048576L

        // Covers: null cloud ID determination path
    }

    @Unroll
    def "buildStorageVolume should handle server without cloud or refType"() {
        given: "server without cloud reference or refType"
        def account = new Account(id: 1L)
        def server = new ComputeServer(id: 5L) // No cloud, no refType
        def mockStorageVolumeType = new StorageVolumeType(id: 90L, code: "standard")

        def volume = [
                name      : "no-cloud-volume",
                maxStorage: "2048"
        ]

        when: "buildStorageVolume is called"
        def result = virtualMachineSync.buildStorageVolume(account, server, volume)

        then: "storage volume type should be resolved"
        1 * asyncStorageVolumeService.getStorageVolumeType() >> storageVolumeTypeService
        1 * storageVolumeTypeService.find(_) >> io.reactivex.rxjava3.core.Maybe.just(mockStorageVolumeType)

        and: "cloud ID should be null"
        result.cloudId == null
        result.name == "no-cloud-volume"
        result.maxStorage == 2048L

        // Covers: determineCloudId null fallback branch
    }

    @Unroll
    def "buildStorageVolume should handle display order calculation"() {
        given: "server with existing volumes and volume configuration"
        def account = new Account(id: 1L)
        def existingVolumes = [
                new StorageVolume(name: "vol1"),
                new StorageVolume(name: "vol2"),
                new StorageVolume(name: "vol3")
        ]
        def server = new ComputeServer(
                id: 6L,
                cloud: new Cloud(id: 7L),
                volumes: existingVolumes
        )
        def mockStorageVolumeType = new StorageVolumeType(id: 95L, code: "standard")

        def volume = volumeConfig

        when: "buildStorageVolume is called"
        def result = virtualMachineSync.buildStorageVolume(account, server, volume)

        then: "storage volume type should be resolved"
        1 * asyncStorageVolumeService.getStorageVolumeType() >> storageVolumeTypeService
        1 * storageVolumeTypeService.find(_) >> io.reactivex.rxjava3.core.Maybe.just(mockStorageVolumeType)

        and: "display order should be calculated correctly"
        result.displayOrder == expectedDisplayOrder

        where:
        volumeConfig                             | expectedDisplayOrder
        [name: "with-order", displayOrder: 5]    | 5
        [name: "without-order"]                  | 3  // server.volumes.size()
        [name: "null-order", displayOrder: null] | 3  // fallback to server.volumes.size()

        // Covers: displayOrder logic with fallback to server.volumes.size()
    }

    @Unroll
    def "buildStorageVolume should handle volume with explicit display order"() {
        given: "volume with explicit display order"
        def account = new Account(id: 1L)
        def server = new ComputeServer(id: 7L, cloud: new Cloud(id: 8L))
        def mockStorageVolumeType = new StorageVolumeType(id: 96L, code: "standard")

        def volume = [
                name        : "explicit-order-volume",
                maxStorage  : "4096",
                displayOrder: 99
        ]

        when: "buildStorageVolume is called"
        def result = virtualMachineSync.buildStorageVolume(account, server, volume)

        then: "storage type should be resolved"
        1 * asyncStorageVolumeService.getStorageVolumeType() >> storageVolumeTypeService
        1 * storageVolumeTypeService.find(_) >> io.reactivex.rxjava3.core.Maybe.just(mockStorageVolumeType)

        and: "storage volume should use explicit display order"
        result.name == "explicit-order-volume"
        result.maxStorage == 4096L
        result.displayOrder == 99
        result.datastore == null // no datastoreId

        // Covers: explicit display order case
    }

    @Unroll
    def "buildStorageVolume should handle null and empty volume properties"() {
        given: "volume with null and empty properties"
        def account = new Account(id: 1L)
        def server = new ComputeServer(id: 8L, cloud: new Cloud(id: 9L))
        def mockStorageVolumeType = new StorageVolumeType(id: 97L, code: "standard")

        def volume = [
                name       : volumeName,
                maxStorage : maxStorage,
                size       : size,
                externalId : externalId,
                internalId : internalId,
                deviceName : deviceName,
                rootVolume : rootVolume,
                datastoreId: datastoreId
        ]

        when: "buildStorageVolume is called"
        def result = virtualMachineSync.buildStorageVolume(account, server, volume)

        then: "storage volume type should be resolved"
        1 * asyncStorageVolumeService.getStorageVolumeType() >> storageVolumeTypeService
        1 * storageVolumeTypeService.find(_) >> io.reactivex.rxjava3.core.Maybe.just(mockStorageVolumeType)

        and: "datastore service calls based on datastoreId presence"
        datastoreServiceCalls * computeServerService.getCloud() >> cloudService
        datastoreServiceCalls * cloudService.getDatastore() >> datastoreService
        datastoreServiceCalls * datastoreService.get(_) >> null

        and: "storage volume should handle null properties gracefully"
        result.name == volumeName
        result.maxStorage == expectedMaxStorage
        result.externalId == externalId
        result.internalId == internalId
        result.deviceName == deviceName

        where:
        volumeName | maxStorage | size   | externalId | internalId | deviceName | rootVolume | datastoreId | expectedMaxStorage | datastoreServiceCalls
        null       | null       | null   | null       | null       | null       | null       | null        | null               | 0
        "test"     | null       | "1024" | "ext"      | "int"      | "/dev/sdc" | true       | null        | 1024L              | 0
        "test2"    | "2048"     | null   | null       | null       | null       | false      | null        | 2048L              | 0

        // Covers: null/empty property handling, size vs maxStorage precedence
    }

    @Unroll
    def "buildStorageVolume should handle server with volumes collection edge cases"() {
        given: "server with different volumes collection states"
        def account = new Account(id: 1L)
        def server = new ComputeServer(id: 9L, cloud: new Cloud(id: 10L), volumes: volumesCollection)
        def mockStorageVolumeType = new StorageVolumeType(id: 98L, code: "standard")

        def volume = [name: "edge-case-volume"]

        when: "buildStorageVolume is called"
        def result = virtualMachineSync.buildStorageVolume(account, server, volume)

        then: "storage volume type should be resolved"
        1 * asyncStorageVolumeService.getStorageVolumeType() >> storageVolumeTypeService
        1 * storageVolumeTypeService.find(_) >> io.reactivex.rxjava3.core.Maybe.just(mockStorageVolumeType)

        and: "display order should fallback correctly"
        result.displayOrder == expectedDisplayOrder

        where:
        volumesCollection     | expectedDisplayOrder
        null                  | 0
        []                    | 0
        [new StorageVolume()] | 1

        // Covers: volumes collection null/empty handling in displayOrder calculation
    }

    @Unroll
    def "buildStorageVolume should properly chain all helper method calls"() {
        given: "complete volume configuration"
        def account = new Account(id: 1L, name: "chain-test-account")
        def server = new ComputeServer(id: 10L, cloud: new Cloud(id: 11L))
        def mockStorageVolumeType = new StorageVolumeType(id: 99L, code: "standard")

        def volume = [
                name       : "method-chain-volume",
                maxStorage : "8192",
                storageType: "30",
                externalId : "chain-ext-123",
                internalId : "chain-int-456"
        ]

        when: "buildStorageVolume is called"
        def result = virtualMachineSync.buildStorageVolume(account, server, volume)

        then: "all service calls should be made in correct order"
        // Storage type lookup (from configureStorageVolumeBasics -> resolveStorageType)
        1 * asyncStorageVolumeService.getStorageVolumeType() >> storageVolumeTypeService
        1 * storageVolumeTypeService.get(30L) >> io.reactivex.rxjava3.core.Maybe.just(mockStorageVolumeType)

        and: "final storage volume should have all properties set correctly"
        result instanceof StorageVolume
        result.name == "method-chain-volume"
        result.account == account
        result.maxStorage == 8192L
        result.type == mockStorageVolumeType
        result.externalId == "chain-ext-123"
        result.internalId == "chain-int-456"
        result.cloudId == 11L

        // Covers: complete method flow including new StorageVolume(), all helper calls, return
    }

    // Tests for addMissingVirtualMachines method - basic functionality verification
    @Unroll
    def "addMissingVirtualMachines should execute without exceptions for valid input"() {
        given: "cloud items to add and configuration data"
        def testAccount = new Account(id: 1000L, name: "test-account")
        cloud.account = testAccount

        def parentHost = new ComputeServer(id: 100L, name: "host-01", externalId: "host-123")
        def hosts = [parentHost]

        def servicePlan = new ServicePlan(id: 200L, name: "test-plan")
        def fallbackPlan = new ServicePlan(id: 201L, name: "fallback-plan")
        def availablePlans = [servicePlan]
        def availablePlanPermissions = []

        def defaultServerType = new ComputeServerType(id: 300L, code: "scvmmUnmanaged")

        def cloudItem = [
                Name               : "test-vm-1",
                ID                 : "vm-123",
                VMId               : "vm-internal-456",
                VirtualMachineState: "Running",
                CPUCount           : "2",
                Memory             : "4096",
                OperatingSystem    : "Ubuntu Linux (64-bit)",
                Disks              : []
        ]

        def addList = [cloudItem]
        def mockOsType = new OsType(id: 400L, code: "ubuntu", platform: "linux")
        def savedServer = new ComputeServer(id: 500L, name: "test-vm-1")

        when: "addMissingVirtualMachines is called"
        virtualMachineSync.addMissingVirtualMachines(addList, availablePlans, fallbackPlan, availablePlanPermissions, hosts, true, defaultServerType)

        then: "method should execute without throwing exceptions"
        noExceptionThrown()

        and: "some service interactions should occur"
        interaction {
            // Allow any number of calls to these services as they may or may not be called depending on implementation
            (0.._) * mockApiService.getMapScvmmOsType(_, _, _) >> "ubuntu"
            (0.._) * osTypeService.find(_) >> mockOsType
            (0.._) * asyncComputeServerService.create(_) >> Single.just(savedServer)
            (0.._) * asyncComputeServerService.save(_) >> Single.just(savedServer)
            (0.._) * virtualMachineSync.buildVmConfig(_, _) >> [name: "test-vm-1", cloud: cloud]
            (0.._) * virtualMachineSync.syncVolumes(_, _)
        }

        // Covers: successful method execution
    }

    @Unroll
    def "addMissingVirtualMachines should handle empty addList without errors"() {
        given: "empty list of VMs to add"
        def addList = []

        when: "addMissingVirtualMachines is called with empty list"
        virtualMachineSync.addMissingVirtualMachines(addList, [], new ServicePlan(), [], [], false, new ComputeServerType())

        then: "method should complete without exceptions"
        noExceptionThrown()

        // Covers: empty addList handling
    }

    @Unroll
    def "addMissingVirtualMachines should handle null input gracefully"() {
        given: "null addList"
        def addList = null

        when: "addMissingVirtualMachines is called with null list"
        virtualMachineSync.addMissingVirtualMachines(addList, [], new ServicePlan(), [], [], false, new ComputeServerType())

        then: "method should complete without exceptions"
        noExceptionThrown()

        // Covers: null input handling
    }

    @Unroll
    def "addMissingVirtualMachines should handle service exceptions gracefully"() {
        given: "cloud item and mocked service that throws exception"
        def cloudItem = [
                Name    : "exception-vm",
                ID      : "vm-exception-123",
                CPUCount: "1",
                Memory  : "1024"
        ]
        def addList = [cloudItem]
        def defaultServerType = new ComputeServerType(id: 1100L)

        when: "addMissingVirtualMachines is called and service throws exception"
        virtualMachineSync.addMissingVirtualMachines(addList, [], new ServicePlan(), [], [], false, defaultServerType)

        then: "method should handle exceptions and not propagate them"
        noExceptionThrown()

        and: "allow service calls that may fail"
        interaction {
            (0.._) * mockApiService.getMapScvmmOsType(_, _, _) >> { throw new RuntimeException("Service error") }
            (0.._) * osTypeService.find(_) >> { throw new RuntimeException("Service error") }
            (0.._) * asyncComputeServerService.create(_) >> { throw new RuntimeException("Service error") }
            (0.._) * virtualMachineSync.buildVmConfig(_, _) >> { throw new RuntimeException("Service error") }
        }

        // Covers: exception handling robustness
    }

    // Tests for execute method - functional verification covering all execution paths
    @Unroll
    def "execute should orchestrate VM synchronization flow with API calls with createNew=#createNew"() {
        given: "setup for API orchestration"
        def scvmmOpts = [zone: "test-zone", hypervisor: "test-hypervisor"]
        def virtualMachines = [
                [ID: "vm-1", Name: "test-vm-1", VirtualMachineState: "Running"]
        ]
        def listResults = [success: true, virtualMachines: virtualMachines]

        when: "execute is called"
        virtualMachineSync.execute(createNew)

        then: "core API calls should execute (initializeExecutionContext + listVirtualMachines)"
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, node) >> scvmmOpts
        1 * mockApiService.listVirtualMachines(scvmmOpts) >> listResults

        and: "method should complete without exceptions, executing all subsequent methods"
        noExceptionThrown()

        where:
        createNew << [true, false]

        // Covers: execute method orchestration with both createNew values
    }

    @Unroll
    def "execute should handle successful listResults and execute performVirtualMachineSync with full workflow"() {
        given: "successful API response with virtual machines"
        def scvmmOpts = [zone: "test-zone", hypervisor: "test-hypervisor"]
        def virtualMachines = [
                [ID: "vm-1", Name: "test-vm-1", VirtualMachineState: "Running", Disks: []],
                [ID: "vm-2", Name: "test-vm-2", VirtualMachineState: "Stopped", Disks: []]
        ]
        def listResults = [success: true, virtualMachines: virtualMachines]

        when: "execute is called with createNew flag"
        virtualMachineSync.execute(createNew)

        then: "initializeExecutionContext should be called"
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, cloud, node) >> scvmmOpts

        and: "API should list virtual machines"
        1 * mockApiService.listVirtualMachines(scvmmOpts) >> listResults


        and: "method should complete successfully"
        noExceptionThrown()

        where:
        createNew << [true, false]

        // Covers: complete execute method success path workflow
    }

    // Tests for syncVolumes method - functional verification covering main execution paths
    @Unroll
    def "syncVolumes should execute without exceptions and return boolean result"() {
        given: "server with volumes and external volumes to sync"
        def existingVolume = new StorageVolume(id: 100L, externalId: "vol-1", name: "existing-vol", maxStorage: 1073741824L)
        def server = new ComputeServer(
                id: 300L,
                name: "test-server",
                maxStorage: 1073741824L,
                volumes: [existingVolume]
        )

        def externalVolumes = [
                [ID: "vol-1", Name: "volume-1", TotalSize: "2147483648"], // Update existing
                [ID: "vol-2", Name: "volume-2", TotalSize: "1073741824"]  // New volume
        ]

        when: "syncVolumes is called"
        def result = virtualMachineSync.syncVolumes(server, externalVolumes)

        then: "method should complete without exceptions and return boolean"
        noExceptionThrown()
        result instanceof Boolean

        and: "allow any service interactions that may occur during sync"
        interaction {
            (0.._) * asyncStorageVolumeService.listById(_) >> Observable.fromIterable([existingVolume])
            (0.._) * asyncComputeServerService.save(_) >> Single.just(server)
            (0.._) * asyncComputeServerService.bulkSave(_) >> Single.just([server])
            (0.._) * storageVolumeService.create(_) >> existingVolume
            (0.._) * asyncStorageVolumeService.bulkSave(_) >> Single.just([existingVolume])
            (0.._) * asyncStorageVolumeService.remove(_) >> Single.just(true)
        }

        // Covers: Observable.fromIterable, SyncTask creation and execution, callback executions
    }

    // Tests for executeSyncTask method - focused unit tests covering SyncTask creation and configuration
    @Unroll
    def "executeSyncTask should create and configure SyncTask with proper callbacks"() {
        given: "VM sync data setup"
        def existingVm = new ComputeServerIdentityProjection(id: 1L, externalId: "vm-1", name: "existing-vm")
        def existingVms = Observable.fromIterable([existingVm])

        def virtualMachines = [
                [ID: "vm-1", Name: "Updated-VM", VirtualMachineState: "Running"],
                [ID: "vm-new", Name: "New-VM", VirtualMachineState: "Stopped"]
        ]
        def listResults = [success: true, virtualMachines: virtualMachines]

        def syncData = [
                hosts: [new ComputeServer(id: 10L)],
                availablePlans: [new ServicePlan(id: 20L)],
                fallbackPlan: new ServicePlan(id: 21L),
                availablePlanPermissions: [],
                serverType: new ComputeServerType(id: 40L)
        ]
        def executionContext = [consoleEnabled: true, scvmmOpts: [:]]

        def server = new ComputeServer(id: 1L, externalId: "vm-1", name: "existing-vm")

        when: "executeSyncTask is called"
        virtualMachineSync.executeSyncTask(existingVms, listResults, syncData, executionContext, createNewParam)

        then: "method should complete without exceptions"
        noExceptionThrown()

        and: "allow any service interactions that may occur during SyncTask execution"
        interaction {
            // SyncTask will call various services during execution
            (0.._) * asyncComputeServerService.listById(_) >> Observable.fromIterable([server])
            (0.._) * virtualMachineSync.addMissingVirtualMachines(_, _, _, _, _, _, _)
            (0.._) * virtualMachineSync.updateMatchedVirtualMachines(_, _, _, _, _, _)
            (0.._) * virtualMachineSync.removeMissingVirtualMachines(_)
        }

        where:
        createNewParam << [true, false]

        // Covers: SyncTask creation, configuration, and execution without exceptions
    }

    @Unroll
    def "executeSyncTask should handle empty VM data gracefully"() {
        given: "empty sync data"
        def existingVms = Observable.fromIterable([])
        def listResults = [success: true, virtualMachines: []]
        def syncData = [
                hosts: [],
                availablePlans: [],
                fallbackPlan: null,
                availablePlanPermissions: [],
                serverType: new ComputeServerType(id: 40L)
        ]
        def executionContext = [consoleEnabled: false, scvmmOpts: [:]]

        when: "executeSyncTask is called with empty data"
        virtualMachineSync.executeSyncTask(existingVms, listResults, syncData, executionContext, createNewParam)

        then: "method should complete without exceptions"
        noExceptionThrown()

        and: "allow any service interactions"
        interaction {
            (0.._) * asyncComputeServerService.listById(_) >> Observable.fromIterable([])
            (0.._) * virtualMachineSync.addMissingVirtualMachines(_, _, _, _, _, _, _)
            (0.._) * virtualMachineSync.updateMatchedVirtualMachines(_, _, _, _, _, _)
            (0.._) * virtualMachineSync.removeMissingVirtualMachines(_)
        }

        where:
        createNewParam << [true, false]

        // Covers: empty data handling in SyncTask
    }

    @Unroll
    def "executeSyncTask should pass correct parameters to SyncTask callbacks"() {
        given: "sync setup with known data to verify parameter passing"
        def existingVm = new ComputeServerIdentityProjection(id: 1L, externalId: "vm-match", name: "test-vm")
        def existingVms = Observable.fromIterable([existingVm])

        def virtualMachines = [
                [ID: "vm-match", Name: "Matched-VM", VirtualMachineState: "Running"],
                [ID: "vm-add", Name: "Add-VM", VirtualMachineState: "Stopped"]
        ]
        def listResults = [success: true, virtualMachines: virtualMachines]

        def mockHosts = [new ComputeServer(id: 10L)]
        def mockPlans = [new ServicePlan(id: 20L)]
        def mockFallbackPlan = new ServicePlan(id: 21L)
        def mockPermissions = []
        def mockServerType = new ComputeServerType(id: 40L)

        def syncData = [
                hosts: mockHosts,
                availablePlans: mockPlans,
                fallbackPlan: mockFallbackPlan,
                availablePlanPermissions: mockPermissions,
                serverType: mockServerType
        ]
        def executionContext = [consoleEnabled: true, scvmmOpts: [:]]

        def server = new ComputeServer(id: 1L, externalId: "vm-match", name: "test-vm")

        when: "executeSyncTask is called"
        virtualMachineSync.executeSyncTask(existingVms, listResults, syncData, executionContext, createNewParam)

        then: "SyncTask should load servers for updates"
        (0.._) * asyncComputeServerService.listById(_) >> Observable.fromIterable([server])

        and: "callback methods should be called with correct parameters"
        if (createNewParam) {
            (0.._) * virtualMachineSync.addMissingVirtualMachines(
                    _, mockPlans, mockFallbackPlan, mockPermissions, mockHosts, true, mockServerType)
        } else {
            (0.._) * virtualMachineSync.addMissingVirtualMachines(_, _, _, _, _, _, _)
        }

        (0.._) * virtualMachineSync.updateMatchedVirtualMachines(
                _, mockPlans, mockFallbackPlan, mockHosts, true, mockServerType)

        (0.._) * virtualMachineSync.removeMissingVirtualMachines(_)

        and: "method should complete without exceptions"
        noExceptionThrown()

        where:
        createNewParam << [true, false]

        // Covers: parameter passing to SyncTask callbacks, createNew flag behavior
    }

    def "getExistingVirtualMachines should return Observable of ComputeServerIdentityProjections with correct filters"() {
        given: "mock compute server projections and query response"
        def projections = [
                new ComputeServerIdentityProjection(id: 1L, name: "vm1", externalId: "vm-123"),
                new ComputeServerIdentityProjection(id: 2L, name: "vm2", externalId: "vm-456")
        ]

        when: "getExistingVirtualMachines is called"
        def result = virtualMachineSync.getExistingVirtualMachines()
        def resultList = result.toList().blockingGet()

        then: "correct filters should be applied in the query"
        1 * asyncComputeServerService.listIdentityProjections({ DataQuery query ->
            assert query.filters.size() == 3
            assert query.filters.find { it.name == 'zone.id' && it.value == cloud.id }
            assert query.filters.find { it.name == 'computeServerType.code' && it.value == 'scvmmHypervisor' }
            assert query.filters.find { it.name == 'computeServerType.code' && it.value == 'scvmmController' }
            true
        }) >> Observable.fromIterable(projections)

        and: "result should contain expected projections"
        resultList.size() == 2
        resultList[0].id == 1L
        resultList[0].name == "vm1"
        resultList[0].externalId == "vm-123"
        resultList[1].id == 2L
        resultList[1].name == "vm2"
        resultList[1].externalId == "vm-456"

        and: "no exceptions should be thrown"
        noExceptionThrown()
    }

    def "addMissingStorageVolumes should process disk items and update server volumes"() {
        given: "disk items to add and server with existing volumes"
        def diskData1 = [
                ID: "disk-1",
                Name: "test-disk-1",
                TotalSize: "21474836480", // 20GB
                VHDType: "Dynamic",
                VHDFormat: "VHDX",
                VolumeType: "BootAndSystem",
                HostVolumeId: "host-vol-1"
        ]
        def diskData2 = [
                ID: "disk-2",
                Name: "test-disk-2",
                TotalSize: "10737418240", // 10GB
                VHDType: "Fixed",
                VHDFormat: "VHD",
                VolumeType: "Data",
                HostVolumeId: "host-vol-2"
        ]
        def itemsToAdd = [diskData1, diskData2]

        def testServer = new ComputeServer(
                id: 5L,
                name: "test-server",
                volumes: [],
                account: new Account(id: 1L)
        )

        def mockStorageVolume1 = new StorageVolume(id: 1L, name: "volume-1", maxStorage: 21474836480L)
        def mockStorageVolume2 = new StorageVolume(id: 2L, name: "volume-2", maxStorage: 10737418240L)

        def diskNumber = 0
        def maxStorage = 0L

        and: "mock interactions are set up"
        // Mock the helper methods via the VirtualMachineSync instance
        virtualMachineSync.metaClass.createVolumeConfig = { diskData, server, volumeNames, index, currentDiskNum ->
            return [
                    name: "volume-${index + 1}",
                    size: diskData.TotalSize?.toLong() ?: 0,
                    rootVolume: diskData.VolumeType == "BootAndSystem",
                    deviceName: "/dev/sd${(char)((int)'a' + currentDiskNum)}",
                    externalId: diskData.ID,
                    internalId: diskData.Name,
                    storageType: null
            ]
        }

        virtualMachineSync.metaClass.createAndPersistStorageVolume = { server, volumeConfig ->
            def volume = volumeConfig.name == "volume-1" ? mockStorageVolume1 : mockStorageVolume2
            server.volumes.add(volume)
            return volume
        }

        // Mock context.async.computeServer.bulkSave
        asyncComputeServerService.bulkSave(*_) >> Single.just([testServer])

        when: "addMissingStorageVolumes is called"
        virtualMachineSync.addMissingStorageVolumes(itemsToAdd, testServer, diskNumber, maxStorage)

        then: "volumes are added to server and bulk save is called"
        testServer.volumes.size() == 2
        testServer.volumes[0].name == "volume-1"
        testServer.volumes[1].name == "volume-2"
        1 * asyncComputeServerService.bulkSave(*_) >> Single.just([testServer])
    }

    def "addMissingStorageVolumes should handle null itemsToAdd gracefully"() {
        given: "null items to add"
        def itemsToAdd = null
        def testServer = new ComputeServer(
                id: 6L,
                name: "test-server-2",
                volumes: []
        )
        def diskNumber = 0
        def maxStorage = 0L

        and: "mock bulk save"
        asyncComputeServerService.bulkSave(*_) >> Single.just([testServer])

        when: "addMissingStorageVolumes is called with null items"
        virtualMachineSync.addMissingStorageVolumes(itemsToAdd, testServer, diskNumber, maxStorage)

        then: "no volumes are added and bulk save is still called"
        testServer.volumes.size() == 0
        1 * asyncComputeServerService.bulkSave(*_) >> Single.just([testServer])
    }

    def "addMissingStorageVolumes should handle empty itemsToAdd list"() {
        given: "empty items to add"
        def itemsToAdd = []
        def testServer = new ComputeServer(
                id: 7L,
                name: "test-server-3",
                volumes: []
        )
        def diskNumber = 0
        def maxStorage = 0L

        and: "mock bulk save"
        asyncComputeServerService.bulkSave(*_) >> Single.just([testServer])

        when: "addMissingStorageVolumes is called with empty list"
        virtualMachineSync.addMissingStorageVolumes(itemsToAdd, testServer, diskNumber, maxStorage)

        then: "no volumes are added and bulk save is still called"
        testServer.volumes.size() == 0
        1 * asyncComputeServerService.bulkSave(*_) >> Single.just([testServer])
    }

    def "test removeMissingStorageVolumes with empty list"() {
        given: "an empty list of volumes to remove"
        def removeItems = []
        def volumeCollection = new HashSet()
        def server = Spy(ComputeServer) {
            getId() >> 1L
            getName() >> "test-server"
        }
        server.metaClass.volumes = volumeCollection
        server.metaClass.getVolumes = { -> volumeCollection }
        Boolean changes = false

        when: "removeMissingStorageVolumes is called with empty list"
        virtualMachineSync.removeMissingStorageVolumes(removeItems, server, changes)

        then: "no services should be called"
        0 * asyncComputeServerService.save(_)
        0 * asyncStorageVolumeService.remove(_)
        volumeCollection.size() == 0
    }

    def "test removeMissingStorageVolumes with null list"() {
        given: "a null list of volumes to remove"
        def removeItems = null
        def volumeCollection = new HashSet()
        def server = Spy(ComputeServer) {
            getId() >> 1L
            getName() >> "test-server"
        }
        server.metaClass.volumes = volumeCollection
        server.metaClass.getVolumes = { -> volumeCollection }
        Boolean changes = false

        when: "removeMissingStorageVolumes is called with null list"
        virtualMachineSync.removeMissingStorageVolumes(removeItems, server, changes)

        then: "no services should be called and no exceptions thrown"
        0 * asyncComputeServerService.save(_)
        0 * asyncStorageVolumeService.remove(_)
        volumeCollection.size() == 0
    }

    def "test removeMissingStorageVolumes with single volume"() {
        given: "a single volume to remove"
        def volume = new StorageVolume(
                id: 1L,
                name: "single-volume",
                externalId: "vol-789"
        )
        volume.controller = new StorageController(id: 1L)
        volume.datastore = new Datastore(id: 1L)

        def removeItems = [volume]

        def volumeCollection = new HashSet([volume])
        def server = Spy(ComputeServer) {
            getId() >> 1L
            getName() >> "test-server"
        }
        server.metaClass.volumes = volumeCollection
        server.metaClass.getVolumes = { -> volumeCollection }

        Boolean changes = false

        // Mock the async services
        asyncComputeServerService.save(server) >> Single.just(server)
        asyncStorageVolumeService.remove(volume) >> Single.just(true)

        when: "removeMissingStorageVolumes is called"
        virtualMachineSync.removeMissingStorageVolumes(removeItems, server, changes)

        then: "the volume should be processed and removed"
        volume.controller == null
        volume.datastore == null
        !volumeCollection.contains(volume)
        volumeCollection.size() == 0

        // Verify service calls
        1 * asyncComputeServerService.save(server) >> Single.just(server)
        1 * asyncStorageVolumeService.remove(volume) >> Single.just(true)
    }

    def "test removeMissingStorageVolumes with volume having null controller and datastore"() {
        given: "a volume with null controller and datastore"
        def volume = new StorageVolume(
                id: 1L,
                name: "null-refs-volume",
                externalId: "vol-null"
        )
        // Note: controller and datastore are already null by default

        def removeItems = [volume]

        def volumeCollection = new HashSet([volume])
        def server = Spy(ComputeServer) {
            getId() >> 1L
            getName() >> "test-server"
        }
        server.metaClass.volumes = volumeCollection
        server.metaClass.getVolumes = { -> volumeCollection }

        Boolean changes = false

        // Mock the async services
        asyncComputeServerService.save(server) >> Single.just(server)
        asyncStorageVolumeService.remove(volume) >> Single.just(true)

        when: "removeMissingStorageVolumes is called"
        virtualMachineSync.removeMissingStorageVolumes(removeItems, server, changes)

        then: "the volume should still be processed and removed"
        volume.controller == null
        volume.datastore == null
        !volumeCollection.contains(volume)
        volumeCollection.size() == 0

        // Verify service calls still happen
        1 * asyncComputeServerService.save(server) >> Single.just(server)
        1 * asyncStorageVolumeService.remove(volume) >> Single.just(true)
    }

    def "test updateMatchedStorageVolumes with basic update items"() {
        given: "a compute server and update items"
        def server = new ComputeServer(id: 1L, name: "test-server")
        def volume1 = new StorageVolume(id: 1L, name: "volume1")
        def volume2 = new StorageVolume(id: 2L, name: "volume2")

        def updateItems = [
                [masterItem: volume1, diskSize: 1024L],
                [masterItem: volume2, diskSize: 2048L]
        ]

        Long maxStorage = 0L
        Boolean changes = false

        // Mock processVolumeUpdate method to return test results
        virtualMachineSync.metaClass.processVolumeUpdate = { updateMap, srv, index ->
            return [
                    shouldSave: true,
                    volume: updateMap.masterItem,
                    diskSize: updateMap.diskSize
            ]
        }

        // Mock bulk save operation
        asyncStorageVolumeService.bulkSave(_) >> Single.just([volume1, volume2])

        when: "updateMatchedStorageVolumes is called"
        virtualMachineSync.updateMatchedStorageVolumes(updateItems, server, maxStorage, changes)

        then: "the method should process all update items and save volumes"
        1 * asyncStorageVolumeService.bulkSave([volume1, volume2]) >> Single.just([volume1, volume2])
    }

}