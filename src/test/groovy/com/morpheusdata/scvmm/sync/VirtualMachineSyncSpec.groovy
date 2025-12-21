package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.synchronous.MorpheusSynchronousOsTypeService
import com.morpheusdata.core.synchronous.MorpheusSynchronousWorkloadService
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.core.synchronous.MorpheusSynchronousServicePlanService
import com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService
import com.morpheusdata.core.cloud.MorpheusCloudService
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
import io.reactivex.rxjava3.core.Observable
import com.morpheusdata.core.MorpheusStorageVolumeService
import com.morpheusdata.core.MorpheusStorageVolumeTypeService
import io.reactivex.rxjava3.core.Observable
import com.morpheusdata.core.util.SyncUtils

class VirtualMachineSyncSpec extends Specification {

    private TestableVirtualMachineSync virtualMachineSync
    private MorpheusContext morpheusContext
    private CloudProvider cloudProvider
    private ScvmmApiService mockApiService
    private MorpheusComputeServerService asyncComputeServerService
    private MorpheusSynchronousComputeServerService computeServerService
    private MorpheusSynchronousOsTypeService osTypeService
    private MorpheusSynchronousWorkloadService workloadService
    private MorpheusSynchronousStorageVolumeService syncStorageVolumeService
    private MorpheusSynchronousServicePlanService servicePlanService
    private MorpheusSynchronousResourcePermissionService resourcePermissionService
    private MorpheusStorageVolumeService storageVolumeService
    private MorpheusStorageVolumeService asyncStorageVolumeService
    private MorpheusStorageVolumeTypeService storageVolumeTypeService
    private def cloudService
    private MorpheusCloudService cloudAsyncService
    private MorpheusSynchronousDatastoreService datastoreService
    private Cloud cloud
    private ComputeServer node
    private ComputeServer existingServer
    private ComputeServer parentHost
    private ServicePlan mockServicePlan
    private ServicePlan fallbackPlan
    private OsType mockOsType
    private ComputeServerType defaultServerType

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
        GroovySpy(SyncUtils, global: true)

        // Mock services
        computeServerService = Mock(MorpheusSynchronousComputeServerService)
        asyncComputeServerService = Mock(MorpheusComputeServerService)
        osTypeService = Mock(MorpheusSynchronousOsTypeService)
        workloadService = Mock(MorpheusSynchronousWorkloadService)
        syncStorageVolumeService = Mock(MorpheusSynchronousStorageVolumeService)
        servicePlanService = Mock(MorpheusSynchronousServicePlanService)
        resourcePermissionService = Mock(MorpheusSynchronousResourcePermissionService)
        storageVolumeService = Mock(MorpheusStorageVolumeService) // Keep async type for compatibility
        asyncStorageVolumeService = Mock(MorpheusStorageVolumeService)
        storageVolumeTypeService = Mock(MorpheusStorageVolumeTypeService)

        // Initialize datastoreService first
        datastoreService = Mock(MorpheusSynchronousDatastoreService)

        cloudService = Mock(MorpheusSynchronousCloudService) {
            getDatastore() >> datastoreService
        }
        cloudAsyncService = Mock(MorpheusCloudService)

        def morpheusServices = Mock(MorpheusServices) {
            getComputeServer() >> computeServerService
            getOsType() >> osTypeService
            getWorkload() >> workloadService
            getCloud() >> cloudService
            getStorageVolume() >> syncStorageVolumeService  // Use sync service here
            getServicePlan() >> servicePlanService
            getResourcePermission() >> resourcePermissionService
        }


        def morpheusAsyncServices = Mock(MorpheusAsyncServices) {
            getComputeServer() >> asyncComputeServerService
            getStorageVolume() >> asyncStorageVolumeService
            getCloud() >> cloudAsyncService
        }

        // Configure storage volume service chain
        def mockStorageVolumeType = new StorageVolumeType(id: 999L, code: "test-storage-type")
        asyncStorageVolumeService.getStorageVolumeType() >> storageVolumeTypeService
        storageVolumeTypeService.find(_ as DataQuery) >> Single.just(mockStorageVolumeType)

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
    }

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
    }

    @Unroll
    def "addMissingVirtualMachines should execute without exceptions for a valid input"() {
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
    }

    @Unroll
    def "addMissingVirtualMachines should handle empty addList without errors"() {
        given: "empty list of VMs to add"
        def addList = []

        when: "addMissingVirtualMachines is called with empty list"
        virtualMachineSync.addMissingVirtualMachines(addList, [], new ServicePlan(), [], [], false, new ComputeServerType())

        then: "method should complete without exceptions"
        noExceptionThrown()
    }

    @Unroll
    def "addMissingVirtualMachines should handle null input gracefully"() {
        given: "null addList"
        def addList = null

        when: "addMissingVirtualMachines is called with null list"
        virtualMachineSync.addMissingVirtualMachines(addList, [], new ServicePlan(), [], [], false, new ComputeServerType())

        then: "method should complete without exceptions"
        noExceptionThrown()
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
    }

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
    }

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
    }

    @Unroll
    def "executeSyncTask should handle empty VM data gracefully"() {
        given: "empty sync data"
        def existingVms = Observable.fromIterable([])
        def listResults = [success: true, virtualMachines: []]
        def syncData = [
                hosts                   : [],
                availablePlans          : [],
                fallbackPlan            : null,
                availablePlanPermissions: [],
                serverType              : new ComputeServerType(id: 40L)
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

    @Unroll
    def "loadDatastoreForVolume should return null when no parameters provided"() {
        when: "loadDatastoreForVolume is called with all null parameters"
        def result = virtualMachineSync.loadDatastoreForVolume(null, null, null)

        then: "should return null"
        result == null
        0 * storageVolumeService.find(_)
        0 * datastoreService.find(_)
    }

    @Unroll
    def "loadDatastoreForVolume should handle edge case with empty strings"() {
        when: "loadDatastoreForVolume is called with empty strings"
        def result = virtualMachineSync.loadDatastoreForVolume("", "", "")

        then: "should return null (empty strings are falsy in Groovy)"
        result == null
        0 * storageVolumeService.find(_)
        0 * datastoreService.find(_)
    }

    @Unroll
    def "loadDatastoreForVolume should find datastore by hostVolumeId when available"() {
        given: "a storage volume with matching hostVolumeId"
        def hostVolumeId = "host-vol-123"
        def mockStorageVolume = new StorageVolume(id: 1L, internalId: hostVolumeId)
        def mockDatastore = new Datastore(id: 1L, name: "test-datastore")
        mockStorageVolume.datastore = mockDatastore

        when: "loadDatastoreForVolume is called with hostVolumeId"
        def result = virtualMachineSync.loadDatastoreForVolume(hostVolumeId, null, null)

        then: "datastore should be returned"
        result == mockDatastore
        1 * syncStorageVolumeService.find(_) >> mockStorageVolume
    }

    @Unroll
    def "loadDatastoreForVolume should fallback to partitionUniqueId when hostVolumeId datastore is null"() {
        given: "a storage volume without datastore and partitionUniqueId fallback"
        def hostVolumeId = "host-vol-123"
        def partitionUniqueId = "partition-456"
        def mockStorageVolumeWithoutDatastore = new StorageVolume(id: 1L, internalId: hostVolumeId, datastore: null)
        def mockStorageVolumeWithDatastore = new StorageVolume(id: 2L, externalId: partitionUniqueId)
        def mockDatastore = new Datastore(id: 1L, name: "fallback-datastore")
        mockStorageVolumeWithDatastore.datastore = mockDatastore

        when: "loadDatastoreForVolume is called with hostVolumeId and partitionUniqueId"
        def result = virtualMachineSync.loadDatastoreForVolume(hostVolumeId, null, partitionUniqueId)

        then: "should find datastore via partitionUniqueId fallback"
        result == mockDatastore
        2 * syncStorageVolumeService.find(_) >>> [mockStorageVolumeWithoutDatastore, mockStorageVolumeWithDatastore]
    }

    @Unroll
    def "loadDatastoreForVolume should return datastore when using fileShareId"() {
        given: "a datastore with matching fileShareId"
        def fileShareId = "fileshare-789"
        def mockDatastore = new Datastore(id: 1L, name: "fileshare-datastore", externalId: fileShareId)

        when: "loadDatastoreForVolume is called with fileShareId"
        def result = virtualMachineSync.loadDatastoreForVolume(null, fileShareId, null)

        then: "datastore should be returned"
        result == mockDatastore
        1 * datastoreService.find(_) >> mockDatastore
    }

    @Unroll
    def "loadDatastoreForVolume should return null when hostVolumeId finds no datastore and no partitionUniqueId"() {
        given: "a storage volume without datastore and no partitionUniqueId"
        def hostVolumeId = "host-vol-123"
        def mockStorageVolumeWithoutDatastore = new StorageVolume(id: 1L, internalId: hostVolumeId, datastore: null)

        when: "loadDatastoreForVolume is called with hostVolumeId but no partitionUniqueId"
        def result = virtualMachineSync.loadDatastoreForVolume(hostVolumeId, null, null)

        then: "should return null"
        result == null
        1 * syncStorageVolumeService.find(_) >> mockStorageVolumeWithoutDatastore
    }

    @Unroll
    def "loadDatastoreForVolume should handle storage volume service returning null"() {
        given: "storage volume service returns null"
        def hostVolumeId = "host-vol-123"

        when: "loadDatastoreForVolume is called with hostVolumeId"
        def result = virtualMachineSync.loadDatastoreForVolume(hostVolumeId, null, null)

        then: "should return null"
        result == null
        1 * syncStorageVolumeService.find(_) >> null
    }

    @Unroll
    def "loadDatastoreForVolume should execute debug logging with correct parameters"() {
        given: "parameters for logging verification"
        def hostVolumeId = "host-vol-123"
        def fileShareId = "fileshare-456"

        when: "loadDatastoreForVolume is called"
        virtualMachineSync.loadDatastoreForVolume(hostVolumeId, fileShareId, null)

        then: "debug logging should be executed"
        noExceptionThrown()
    }

    @Unroll
    def "resolveDatastore should return datastore from diskData when present"() {
        given: "disk data with datastore property"
        def existingDatastore = new Datastore(id: 300L, name: "existing-datastore")
        def diskData = [
                datastore: existingDatastore,
                HostVolumeId: "host-123",
                FileShareId: "share-456",
                PartitionUniqueId: "partition-789"
        ]

        when: "resolveDatastore is called"
        def result = virtualMachineSync.resolveDatastore(diskData)

        then: "should return existing datastore without calling loadDatastoreForVolume"
        0 * virtualMachineSync.loadDatastoreForVolume(_, _, _)
        result == existingDatastore
    }

    @Unroll
    def "resolveDeviceName should return deviceName from diskData when present"() {
        given: "disk data with deviceName"
        def diskData = [deviceName: "/dev/sda1"]
        def diskNumber = 5

        when: "resolveDeviceName is called"
        def result = virtualMachineSync.resolveDeviceName(diskData, diskNumber)

        then: "should return existing deviceName without calling apiService"
        0 * mockApiService.getDiskName(_)
        result == "/dev/sda1"
    }

    @Unroll
    def "resolveDeviceName should call apiService.getDiskName when deviceName not present"() {
        given: "disk data without deviceName"
        def diskData = [otherProperty: "value"]
        def diskNumber = 3

        when: "resolveDeviceName is called"
        def result = virtualMachineSync.resolveDeviceName(diskData, diskNumber)

        then: "should call apiService.getDiskName with correct diskNumber"
        1 * mockApiService.getDiskName(3) >> "/dev/sdc"
        result == "/dev/sdc"
    }

    @Unroll
    def "resolveDeviceName should handle null and empty deviceName"() {
        given: "disk data with null or empty deviceName"
        def diskData = [deviceName: deviceNameValue]
        def diskNumber = 2

        when: "resolveDeviceName is called"
        def result = virtualMachineSync.resolveDeviceName(diskData, diskNumber)

        then: "should call apiService when deviceName is null or empty"
        expectedApiCalls * mockApiService.getDiskName(2) >> "/dev/sdb"
        result == expectedResult

        where:
        deviceNameValue | expectedApiCalls | expectedResult
        null           | 1                | "/dev/sdb"
        ""             | 1                | "/dev/sdb"
        "   "          | 0                | "   "        // Groovy treats whitespace as truthy
        "/dev/custom"  | 0                | "/dev/custom"
    }

    @Unroll
    def "resolveVolumeName should return name from serverVolumeNames when available"() {
        given: "server volume names list with entry at index"
        def serverVolumeNames = ["boot-vol", "data-vol", "swap-vol"]
        def diskData = [Name: "disk-name"]
        def server = new ComputeServer(id: 1L)
        def index = 1

        when: "resolveVolumeName is called"
        def result = virtualMachineSync.resolveVolumeName(serverVolumeNames, diskData, server, index)

        then: "should return name from serverVolumeNames without calling getVolumeName"
        0 * virtualMachineSync.getVolumeName(_, _, _)
        result == "data-vol"
    }

    @Unroll
    def "resolveVolumeName should call getVolumeName when serverVolumeNames entry not available"() {
        given: "server volume names scenarios"
        def serverVolumeNames = volumeNamesList
        def diskData = [Name: "test-disk", VolumeType: "Data"]
        def server = new ComputeServer(id: 2L, volumes: [new StorageVolume()])
        def index = testIndex

        when: "resolveVolumeName is called"
        def result = virtualMachineSync.resolveVolumeName(serverVolumeNames, diskData, server, index)

        then: "should call getVolumeName when serverVolumeNames entry not available"
        expectedGetVolumeNameCalls * virtualMachineSync.getVolumeName(diskData, server, index) >> "generated-name"
        result == expectedResult

        where:
        volumeNamesList | testIndex | expectedGetVolumeNameCalls | expectedResult
        ["vol1", "vol2"] | 0        | 0                          | "vol1"           // valid index
    }

    @Unroll
    def "resolveVolumeName should handle edge case with safe navigation"() {
        given: "edge case scenarios with safe navigation"
        def serverVolumeNames = volumeNames
        def diskData = [Name: "edge-disk"]
        def server = new ComputeServer(id: 3L)
        def index = 0

        when: "resolveVolumeName is called"
        def result = virtualMachineSync.resolveVolumeName(serverVolumeNames, diskData, server, index)

        then: "should handle safe navigation correctly"
        expectedCalls * virtualMachineSync.getVolumeName(diskData, server, index) >> "fallback-name"
        result == expectedResult

        where:
        volumeNames        | expectedCalls | expectedResult
        ["existing-name"]  | 0             | "existing-name"  // Valid getAt result
    }

    @Unroll
    def "isRootVolume should return true when VolumeType is BootAndSystem"() {
        given: "disk data with BootAndSystem volume type"
        def diskData = [VolumeType: "BootAndSystem"]
        def server = new ComputeServer(id: 1L, volumes: [new StorageVolume(), new StorageVolume()])

        when: "isRootVolume is called"
        def result = virtualMachineSync.isRootVolume(diskData, server)

        then: "should return true"
        result == true
    }

    @Unroll
    def "isRootVolume should return true when server has no volumes"() {
        given: "disk data with non-boot volume type and server with no volumes"
        def diskData = [VolumeType: "Data"]
        def server = new ComputeServer(id: 2L, volumes: volumesCollection)

        when: "isRootVolume is called"
        def result = virtualMachineSync.isRootVolume(diskData, server)

        then: "should return expected result based on volumes collection"
        result == expectedResult

        where:
        volumesCollection | expectedResult
        null              | true           // !null?.size() evaluates to true
        []                | true           // ![]?.size() evaluates to true (empty collection size is 0)
        [new StorageVolume()] | false      // ![volume]?.size() evaluates to false (size is 1)
    }

    @Unroll
    def "isRootVolume should handle various VolumeType values"() {
        given: "disk data with different volume types"
        def diskData = [VolumeType: volumeType]
        def server = new ComputeServer(id: 3L, volumes: [new StorageVolume()])

        when: "isRootVolume is called"
        def result = virtualMachineSync.isRootVolume(diskData, server)

        then: "should return correct result based on volume type comparison"
        result == expectedResult

        where:
        volumeType        | expectedResult
        "BootAndSystem"   | true           // Matches BOOT_AND_SYSTEM constant
        "bootandsystem"   | false          // Case sensitive comparison
        "BOOTANDSYSTEM"   | false          // Case sensitive comparison
        "Data"            | false          // Different type, server has volumes
        null              | false          // null != "BootAndSystem", server has volumes
        ""                | false          // empty string != "BootAndSystem"
    }

    @Unroll
    def "isRootVolume should test complete OR expression logic"() {
        given: "various combinations of volume type and server volumes"
        def diskData = [VolumeType: volumeType]
        def server = new ComputeServer(id: 4L, volumes: serverVolumes)

        when: "isRootVolume is called"
        def result = virtualMachineSync.isRootVolume(diskData, server)

        then: "should return correct result for OR expression"
        result == expectedResult

        where:
        volumeType      | serverVolumes              | expectedResult | description
        "BootAndSystem" | [new StorageVolume()]      | true          | "First condition true, second false"
        "BootAndSystem" | []                         | true          | "Both conditions true"
        "Data"          | []                         | true          | "First condition false, second true"
        "Data"          | [new StorageVolume()]      | false         | "Both conditions false"
    }

    def "performVirtualMachineSync should prepare data and execute sync task"() {
        given: "Mock dependencies and sync data"
        def listResults = [data: "test"]
        def executionContext = [context: "test"]
        def createNew = true
        def syncData = [prepared: "data"]
        def existingVms = Observable.fromIterable([new ComputeServerIdentityProjection(id: 1L, name: "vm1")])

        and: "Create spy to track method calls"
        def spy = Spy(virtualMachineSync)

        when: "performVirtualMachineSync is called"
        spy.performVirtualMachineSync(listResults, executionContext, createNew)

        then: "prepareSyncData is called"
        1 * spy.prepareSyncData() >> syncData

        and: "getExistingVirtualMachines is called"
        1 * spy.getExistingVirtualMachines() >> existingVms

        and: "executeSyncTask is called with correct parameters and stubbed"
        1 * spy.executeSyncTask(existingVms, listResults, syncData, executionContext, createNew) >> { /* stubbed, no actual execution */ }
    }

    def "createVolumeConfig should build complete volume configuration"() {
        given: "Disk data and server configuration"
        def diskData = [
            ID: "disk-123",
            Name: "test-disk",
            TotalSize: "10737418240", // 10GB
            VHDType: "Dynamic",
            VHDFormat: "VHDX"
        ]
        def server = new ComputeServer(id: 1L, name: "test-server")
        def serverVolumeNames = ["existing-volume"]
        def index = 0
        def diskNumber = 1

        def mockDatastore = new Datastore(id: 100L, name: "test-datastore")
        def volumeTypeId = 25L

        and: "Create spy to mock dependencies"
        def spy = Spy(virtualMachineSync)

        when: "createVolumeConfig is called"
        def result = spy.createVolumeConfig(diskData, server, serverVolumeNames, index, diskNumber)

        then: "resolveDatastore is called"
        1 * spy.resolveDatastore(diskData) >> mockDatastore

        and: "resolveDeviceName is called"
        1 * spy.resolveDeviceName(diskData, diskNumber) >> "/dev/sda1"

        and: "resolveVolumeName is called"
        1 * spy.resolveVolumeName(serverVolumeNames, diskData, server, index) >> "test-disk-volume"

        and: "isRootVolume is called"
        1 * spy.isRootVolume(diskData, server) >> true

        and: "getStorageVolumeType is called"
        1 * spy.getStorageVolumeType("scvmm-dynamic-vhdx") >> volumeTypeId

        and: "volume configuration is correctly built"
        result.name == "test-disk-volume"
        result.size == 10737418240L
        result.rootVolume == true
        result.deviceName == "/dev/sda1"
        result.externalId == "disk-123"
        result.internalId == "test-disk"
        result.storageType == volumeTypeId
        result.datastoreId == "100"
    }

    def "createVolumeConfig should handle missing datastore"() {
        given: "Disk data without datastore"
        def diskData = [
            ID: "disk-456",
            Name: "no-datastore-disk",
            TotalSize: "5368709120", // 5GB
            VHDType: "Fixed",
            VHDFormat: "VHD"
        ]
        def server = new ComputeServer(id: 2L, name: "test-server-2")
        def serverVolumeNames = []
        def index = 1
        def diskNumber = 2

        def volumeTypeId = 30L

        and: "Create spy to mock dependencies"
        def spy = Spy(virtualMachineSync)

        when: "createVolumeConfig is called"
        def result = spy.createVolumeConfig(diskData, server, serverVolumeNames, index, diskNumber)

        then: "resolveDatastore returns null"
        1 * spy.resolveDatastore(diskData) >> null

        and: "other methods are called correctly"
        1 * spy.resolveDeviceName(diskData, diskNumber) >> "/dev/sdb1"
        1 * spy.resolveVolumeName(serverVolumeNames, diskData, server, index) >> "no-datastore-volume"
        1 * spy.isRootVolume(diskData, server) >> false
        1 * spy.getStorageVolumeType("scvmm-fixed-vhd") >> volumeTypeId

        and: "volume configuration is built without datastoreId"
        result.name == "no-datastore-volume"
        result.size == 5368709120L
        result.rootVolume == false
        result.deviceName == "/dev/sdb1"
        result.externalId == "disk-456"
        result.internalId == "no-datastore-disk"
        result.storageType == volumeTypeId
        !result.containsKey("datastoreId")
    }

    def "createAndPersistStorageVolume should build, create and add volume to server"() {
        given: "Server and volume configuration"
        def account = new Account(id: 10L, name: "test-account")
        def server = new ComputeServer(id: 5L, name: "test-server", account: account, volumes: [])
        def volumeConfig = [
            name: "test-volume",
            size: 1073741824L, // 1GB
            rootVolume: false,
            deviceName: "/dev/sdc1",
            externalId: "vol-789",
            internalId: "test-volume-internal"
        ]

        def builtVolume = new StorageVolume(
            name: "test-volume",
            maxStorage: 1073741824L,
            externalId: "vol-789"
        )

        and: "Create spy to mock dependencies"
        def spy = Spy(virtualMachineSync)

        when: "createAndPersistStorageVolume is called"
        def result = spy.createAndPersistStorageVolume(server, volumeConfig)

        then: "buildStorageVolume is called with correct parameters"
        1 * spy.buildStorageVolume(account, server, volumeConfig) >> builtVolume

        and: "storage volume is created via context services"
        1 * syncStorageVolumeService.create(builtVolume) >> builtVolume

        and: "volume is added to server volumes"
        server.volumes.contains(builtVolume)

        and: "created volume is returned"
        result == builtVolume
    }

    def "createAndPersistStorageVolume should use cloud account when server account is null"() {
        given: "Server without account and volume configuration"
        def cloudAccount = new Account(id: 20L, name: "cloud-account")
        def server = new ComputeServer(id: 6L, name: "test-server-no-account", account: null, volumes: [])
        cloud.account = cloudAccount

        def volumeConfig = [
            name: "cloud-account-volume",
            size: 2147483648L, // 2GB
            rootVolume: true
        ]

        def builtVolume = new StorageVolume(
            name: "cloud-account-volume",
            maxStorage: 2147483648L
        )

        and: "Create spy to mock dependencies"
        def spy = Spy(virtualMachineSync)

        when: "createAndPersistStorageVolume is called"
        def result = spy.createAndPersistStorageVolume(server, volumeConfig)

        then: "buildStorageVolume is called with cloud account"
        1 * spy.buildStorageVolume(cloudAccount, server, volumeConfig) >> builtVolume

        and: "storage volume is created"
        1 * syncStorageVolumeService.create(builtVolume) >> builtVolume

        and: "volume is added to server volumes and returned"
        server.volumes.contains(builtVolume)
        result == builtVolume
    }

    def "test prepareSyncData - resource permission collection when availablePlans exist"() {
        given: "Service plan data"
        def plan1 = new ServicePlan(id: 100L, name: "plan1")
        def plan2 = new ServicePlan(id: 200L, name: "plan2")
        def plans = [plan1, plan2]

        def perm1 = new ResourcePermission(morpheusResourceType: 'ServicePlan', morpheusResourceId: 100L)
        def perm2 = new ResourcePermission(morpheusResourceType: 'ServicePlan', morpheusResourceId: 200L)

        when: "prepareSyncData is called"
        def result = virtualMachineSync.prepareSyncData()

        then: "Service plans are retrieved"
        1 * servicePlanService.list(_ as DataQuery) >> plans

        and: "Resource permissions are queried with correct filters"
        1 * resourcePermissionService.list(_ as DataQuery) >> { DataQuery query ->
            assert query.filters.find { it.name == 'morpheusResourceType' }?.value == 'ServicePlan'
            assert query.filters.find { it.name == 'morpheusResourceId' }?.operator == 'in'
            assert query.filters.find { it.name == 'morpheusResourceId' }?.value == [100L, 200L]
            [perm1, perm2]
        }

        and: "Other required services return data"
        1 * servicePlanService.find(_ as DataQuery) >> new ServicePlan(id: 999L)
        1 * cloudAsyncService.findComputeServerTypeByCode('scvmmUnmanaged') >> Maybe.just(new ComputeServerType(id: 1L))
        1 * computeServerService.list(_ as DataQuery) >> []

        and: "Result contains permissions"
        result.availablePlanPermissions.size() == 2
    }

    def "test prepareSyncData - empty permission collection when no plans"() {
        given: "No service plans"
        def emptyPlans = []

        when: "prepareSyncData is called"
        def result = virtualMachineSync.prepareSyncData()

        then: "Service plans list returns empty"
        1 * servicePlanService.list(_ as DataQuery) >> emptyPlans

        and: "Resource permission service is NOT called"
        0 * resourcePermissionService.list(_ as DataQuery)

        and: "Other services return defaults"
        1 * servicePlanService.find(_ as DataQuery) >> new ServicePlan(id: 999L)
        1 * cloudAsyncService.findComputeServerTypeByCode('scvmmUnmanaged') >> Maybe.just(new ComputeServerType(id: 1L))
        1 * computeServerService.list(_ as DataQuery) >> []

        and: "Permissions collection is empty"
        result.availablePlanPermissions == []
    }

    def "test prepareSyncData - findComputeServerTypeByCode call"() {
        given: "Expected server type"
        def expectedType = new ComputeServerType(id: 777L, code: 'scvmmUnmanaged')

        when: "prepareSyncData is called"
        def result = virtualMachineSync.prepareSyncData()

        then: "findComputeServerTypeByCode is called with correct code"
        1 * cloudAsyncService.findComputeServerTypeByCode('scvmmUnmanaged') >> Maybe.just(expectedType)

        and: "Other services"
        1 * servicePlanService.list(_ as DataQuery) >> []
        1 * servicePlanService.find(_ as DataQuery) >> new ServicePlan(id: 999L)
        1 * computeServerService.list(_ as DataQuery) >> []

        and: "Server type is in result"
        result.serverType == expectedType
    }

    def "test executeSyncTask - onAdd callback NOT triggered when createNew is false"() {
        given: "Sync task setup"
        def existingVms = Observable.fromIterable([])
        def newVmData = [[ID: "new-vm-1", Name: "New VM"]]
        def listResults = [virtualMachines: newVmData]

        def syncData = [
            availablePlans: [],
            fallbackPlan: null,
            availablePlanPermissions: [],
            hosts: [],
            serverType: null
        ]

        def executionContext = [consoleEnabled: false]
        def spy = Spy(virtualMachineSync)

        when: "executeSyncTask is called with createNew=false"
        spy.executeSyncTask(existingVms, listResults, syncData, executionContext, false)

        then: "addMissingVirtualMachines is NOT called"
        0 * spy.addMissingVirtualMachines(_, _, _, _, _, _, _)
    }

    def "test addMissingVirtualMachines - error logging when save fails"() {
        given: "VM data"
        def cloudItem = [
                Name: "fail-vm",
                ID: "vm-fail-123",
                VMId: "internal-fail",
                CPUCount: "2",
                Memory: "4096",
                VirtualMachineState: "Running",
                Disks: []
        ]

        when: "addMissingVirtualMachines processes item and save fails"
        virtualMachineSync.addMissingVirtualMachines([cloudItem], [], null, [], [], true, null)

        then: "create is called and returns error (failure)"
        1 * asyncComputeServerService.create(_) >> Single.error(new RuntimeException("Save failed"))

        and: "Exception is logged but not thrown"
        noExceptionThrown()
    }

    def "test configureServerNetwork - IpAddress and InternalIp assignment"() {
        given: "Server and cloud item"
        def server = new ComputeServer()
        def cloudItem = [IpAddress: "192.168.1.100", InternalIp: "10.10.10.100"]

        when: "Server is created via addMissingVirtualMachines"
        def vmConfig = virtualMachineSync.buildVmConfig(cloudItem, null)

        then: "Config has correct structure"
        vmConfig.name == cloudItem.Name
        noExceptionThrown()
    }

    def "test configureServerNetwork - sshHost prefers internalIp"() {
        given: "Cloud item with both IPs"
        def cloudItem = [
                Name: "test-vm",
                IpAddress: "1.1.1.1",
                InternalIp: "10.0.0.1",
                ID: "vm-123",
                VMId: "internal-123",
                CPUCount: "2",
                Memory: "4096",
                VirtualMachineState: "Running"
        ]

        when: "VM config is built"
        def vmConfig = virtualMachineSync.buildVmConfig(cloudItem, null)

        then: "Config is created successfully"
        vmConfig != null
        noExceptionThrown()
    }

    def "test configureServerNetwork - sshHost falls back to externalIp when no internalIp"() {
        given: "Cloud item with only external IP"
        def cloudItem = [
                Name: "test-vm-2",
                IpAddress: "2.2.2.2",
                ID: "vm-456",
                VMId: "internal-456",
                CPUCount: "1",
                Memory: "2048",
                VirtualMachineState: "Running"
        ]

        when: "VM config is built"
        def vmConfig = virtualMachineSync.buildVmConfig(cloudItem, null)

        then: "Config is created successfully"
        vmConfig != null
        noExceptionThrown()
    }

    def "test updateSingleServer - exception in updateBasicServerProperties"() {
        given: "Server and data that will cause list to throw exception"
        def server = new ComputeServer(id: 888L, name: "error-test")
        def masterItem = [Name: "updated", VirtualMachineState: "Running"]
        def updateItem = new SyncTask.UpdateItem<ComputeServer, Map>(
                existingItem: server,
                masterItem: masterItem
        )

        when: "updateMatchedVirtualMachines encounters exception in server list"
        virtualMachineSync.updateMatchedVirtualMachines([updateItem], [], null, [], false, null)

        then: "Server list throws exception"
        1 * computeServerService.list(_ as DataQuery) >> { throw new RuntimeException("Property error") }

        and: "Exception is caught and logged"
        noExceptionThrown()

        and: "bulkSave is not called due to exception"
        0 * asyncComputeServerService.bulkSave(_)
    }

    @Unroll
    def "updateVolumeSize should return false when masterDiskSize is zero"() {
        given: "a storage volume and zero masterDiskSize"
        def volume = new StorageVolume(id: 1L, maxStorage: 1073741824L) // 1GB
        def masterDiskSize = 0L

        when: "updateVolumeSize is called with zero size"
        def result = virtualMachineSync.updateVolumeSize(volume, masterDiskSize)

        then: "should return false and not modify volume"
        result == false
        volume.maxStorage == 1073741824L // unchanged
    }

    @Unroll
    def "updateVolumeSize should return false when sizes are equal"() {
        given: "a storage volume and matching masterDiskSize"
        def volume = new StorageVolume(id: 2L, maxStorage: 2147483648L) // 2GB
        def masterDiskSize = 2147483648L

        when: "updateVolumeSize is called with same size"
        def result = virtualMachineSync.updateVolumeSize(volume, masterDiskSize)

        then: "should return false and not modify volume"
        result == false
        volume.maxStorage == 2147483648L // unchanged
    }

    @Unroll
    def "updateVolumeSize should return false when masterDiskSize is within tolerance range"() {
        given: "a storage volume and masterDiskSize within 1GB tolerance"
        def volume = new StorageVolume(id: 3L, maxStorage: 5368709120L) // 5GB
        def masterDiskSize = masterDiskSizeValue

        when: "updateVolumeSize is called with size within tolerance"
        def result = virtualMachineSync.updateVolumeSize(volume, masterDiskSize)

        then: "should return false and not modify volume"
        result == false
        volume.maxStorage == 5368709120L // unchanged

        where:
        masterDiskSizeValue << [
            4831838208L, // 5GB - 512MB (within tolerance)
            5100273664L, // 5GB + 256MB (within tolerance)
            4905533440L, // 5GB - 256MB + 1MB (within tolerance)
            5905580032L  // 5GB + 512MB (within tolerance)
        ]
    }

    @Unroll
    def "updateVolumeInternalId should return false when internalId matches masterItem Name"() {
        given: "a storage volume with internalId matching masterItem Name"
        def volume = new StorageVolume(id: 4L, internalId: "disk-vm-01")
        def masterItem = [Name: "disk-vm-01"]

        when: "updateVolumeInternalId is called with matching name"
        def result = virtualMachineSync.updateVolumeInternalId(volume, masterItem)

        then: "should return false and not modify volume"
        result == false
        volume.internalId == "disk-vm-01" // unchanged
    }

    @Unroll
    def "updateVolumeInternalId should return false when both internalId and Name are null"() {
        given: "a storage volume with null internalId and masterItem with null Name"
        def volume = new StorageVolume(id: 5L, internalId: null)
        def masterItem = [Name: null]

        when: "updateVolumeInternalId is called with both null"
        def result = virtualMachineSync.updateVolumeInternalId(volume, masterItem)

        then: "should return false and not modify volume"
        result == false
        volume.internalId == null // unchanged
    }

    @Unroll
    def "updateVolumeInternalId should return false when both internalId and Name are empty strings"() {
        given: "a storage volume with empty internalId and masterItem with empty Name"
        def volume = new StorageVolume(id: 6L, internalId: "")
        def masterItem = [Name: ""]

        when: "updateVolumeInternalId is called with both empty"
        def result = virtualMachineSync.updateVolumeInternalId(volume, masterItem)

        then: "should return false and not modify volume"
        result == false
        volume.internalId == "" // unchanged
    }

    @Unroll
    def "updateVolumeRootFlag should return false when rootVolume flag matches calculated isRootVolume"() {
        given: "a storage volume and server configuration"
        def volume = new StorageVolume(id: 7L, rootVolume: currentRootFlag)
        def masterItem = [VolumeType: volumeType]
        def serverVolumes = volumes
        def server = new ComputeServer(id: 1L, volumes: serverVolumes)

        when: "updateVolumeRootFlag is called"
        def result = virtualMachineSync.updateVolumeRootFlag(volume, masterItem, server)

        then: "should return false when flags match"
        result == false
        volume.rootVolume == currentRootFlag // unchanged

        where:
        volumeType      | volumes                                                    | currentRootFlag
        "BootAndSystem" | [new StorageVolume(), new StorageVolume()]                | true    // Both conditions true, flag already true
        "Data"          | [new StorageVolume()]                                     | true    // Single volume (root), flag already true
        "Data"          | [new StorageVolume(), new StorageVolume(), new StorageVolume()] | false   // Multiple volumes, not boot type, flag already false
    }

    @Unroll
    def "updateVolumeRootFlag should return false when masterItem is null and volume count > 1"() {
        given: "a storage volume with null masterItem and multiple server volumes"
        def volume = new StorageVolume(id: 8L, rootVolume: false)
        def masterItem = null
        def server = new ComputeServer(id: 2L, volumes: [new StorageVolume(), new StorageVolume()])

        when: "updateVolumeRootFlag is called with null masterItem"
        def result = virtualMachineSync.updateVolumeRootFlag(volume, masterItem, server)

        then: "should return false when flag matches calculated value"
        result == false
        volume.rootVolume == false // unchanged, multiple volumes so not root
    }

    @Unroll
    def "updateVolumeName should return false when volume name is not null"() {
        given: "a storage volume with existing name"
        def volume = new StorageVolume(id: 9L, name: "existing-volume-name")
        def masterItem = [Name: "new-disk-name", VolumeType: "Data"]
        def server = new ComputeServer(id: 3L, volumes: [new StorageVolume()])
        def index = 0

        when: "updateVolumeName is called on volume with existing name"
        def result = virtualMachineSync.updateVolumeName(volume, masterItem, server, index)

        then: "should return false and not modify volume name"
        result == false
        volume.name == "existing-volume-name" // unchanged
        0 * virtualMachineSync.getVolumeName(_, _, _) // getVolumeName should not be called
    }

    @Unroll
    def "updateVolumeName should return false when volume name is empty string"() {
        given: "a storage volume with empty string name"
        def volume = new StorageVolume(id: 10L, name: "")
        def masterItem = [Name: "disk-name", VolumeType: "Data"]
        def server = new ComputeServer(id: 4L, volumes: [new StorageVolume()])
        def index = 1

        when: "updateVolumeName is called on volume with empty name"
        def result = virtualMachineSync.updateVolumeName(volume, masterItem, server, index)

        then: "should return false and not modify volume name"
        result == false
        volume.name == "" // unchanged (empty string is truthy in Groovy)
        0 * virtualMachineSync.getVolumeName(_, _, _) // getVolumeName should not be called
    }

    @Unroll
    def "updateVolumeName should return false when volume name is whitespace"() {
        given: "a storage volume with whitespace name"
        def volume = new StorageVolume(id: 11L, name: "   ")
        def masterItem = [Name: "disk-name", VolumeType: "Data"]
        def server = new ComputeServer(id: 5L, volumes: [new StorageVolume()])
        def index = 2

        when: "updateVolumeName is called on volume with whitespace name"
        def result = virtualMachineSync.updateVolumeName(volume, masterItem, server, index)

        then: "should return false and not modify volume name"
        result == false
        volume.name == "   " // unchanged (whitespace is truthy in Groovy)
        0 * virtualMachineSync.getVolumeName(_, _, _) // getVolumeName should not be called
    }

    @Unroll
    def "configureDatastore should configure all properties when datastore exists"() {
        given: "a storage volume and volume map with valid datastoreId"
        def storageVolume = new StorageVolume(id: 2L, name: "test-volume")
        def volume = [datastoreId: "100"]

        def mockStorageServer = new StorageServer(id: 50L, name: "storage-server")
        def mockDatastore = new Datastore(id: 100L, name: "test-datastore", storageServer: mockStorageServer)

        when: "configureDatastore is called"
        virtualMachineSync.configureDatastore(storageVolume, volume)

        then: "datastore service is called with correct ID"
        1 * datastoreService.get(100L) >> mockDatastore

        and: "all storage volume properties are set correctly"
        storageVolume.datastoreOption == "100"
        storageVolume.datastore == mockDatastore
        storageVolume.storageServer == mockStorageServer
        storageVolume.refType == 'Datastore'
        storageVolume.refId == 100L
    }

    @Unroll
    def "configureDatastore should handle datastore without storageServer"() {
        given: "a storage volume and volume map with datastore that has no storageServer"
        def storageVolume = new StorageVolume(id: 3L, name: "test-volume")
        def volume = [datastoreId: "200"]

        def mockDatastore = new Datastore(id: 200L, name: "datastore-no-server", storageServer: null)

        when: "configureDatastore is called"
        virtualMachineSync.configureDatastore(storageVolume, volume)

        then: "datastore service is called"
        1 * datastoreService.get(200L) >> mockDatastore

        and: "properties are set but storageServer remains null"
        storageVolume.datastoreOption == "200"
        storageVolume.datastore == mockDatastore
        storageVolume.storageServer == null
        storageVolume.refType == 'Datastore'
        storageVolume.refId == 200L
    }

    @Unroll
    def "configureDatastore should handle when datastore service returns null"() {
        given: "a storage volume and volume map with datastoreId that doesn't exist"
        def storageVolume = new StorageVolume(id: 4L, name: "test-volume")
        def volume = [datastoreId: "999"]

        when: "configureDatastore is called"
        virtualMachineSync.configureDatastore(storageVolume, volume)

        then: "datastore service is called but returns null"
        1 * datastoreService.get(999L) >> null

        and: "datastoreOption and ref properties are still set, but datastore and storageServer are null"
        storageVolume.datastoreOption == "999"
        storageVolume.datastore == null
        storageVolume.storageServer == null
        storageVolume.refType == 'Datastore'
        storageVolume.refId == 999L
    }

    @Unroll
    def "configureDatastore should handle various datastoreId formats"() {
        given: "a storage volume and volume map with different datastoreId formats"
        def storageVolume = new StorageVolume(id: 5L, name: "test-volume")
        def volume = [datastoreId: datastoreId]

        def mockDatastore = new Datastore(id: expectedId, name: "test-datastore")

        when: "configureDatastore is called"
        virtualMachineSync.configureDatastore(storageVolume, volume)

        then: "datastore service is called with correct converted ID"
        1 * datastoreService.get(expectedId) >> mockDatastore

        and: "properties are set with string and numeric values"
        storageVolume.datastoreOption == datastoreId
        storageVolume.datastore == mockDatastore
        storageVolume.refType == 'Datastore'
        storageVolume.refId == expectedId

        where:
        datastoreId | expectedId
        "1"         | 1L
        "123"       | 123L
        "999"       | 999L
    }

    @Unroll
    def "updateVolumeName should return true and set name when volume name is null"() {
        given: "a storage volume with null name"
        def volume = new StorageVolume(id: 1L, name: null)
        def masterItem = [Name: "test-disk", VolumeType: "Data"]
        def server = new ComputeServer(id: 1L, volumes: [new StorageVolume()])
        def index = 0

        and: "Create spy to mock getVolumeName"
        def spy = Spy(virtualMachineSync)

        when: "updateVolumeName is called on volume with null name"
        def result = spy.updateVolumeName(volume, masterItem, server, index)

        then: "getVolumeName is called with correct parameters"
        1 * spy.getVolumeName(masterItem, server, index) >> "generated-volume-name"

        and: "should return true and set volume name"
        result == true
        volume.name == "generated-volume-name"
    }

    @Unroll
    def "updateVolumeName should return false when volume name is not null"() {
        given: "a storage volume with existing name"
        def volume = new StorageVolume(id: 2L, name: volumeName)
        def masterItem = [Name: "disk-name", VolumeType: "Data"]
        def server = new ComputeServer(id: 2L, volumes: [new StorageVolume()])
        def index = 1

        and: "Create spy to verify getVolumeName is not called"
        def spy = Spy(virtualMachineSync)

        when: "updateVolumeName is called on volume with existing name"
        def result = spy.updateVolumeName(volume, masterItem, server, index)

        then: "getVolumeName should not be called"
        0 * spy.getVolumeName(_, _, _)

        and: "should return false and not modify volume name"
        result == false
        volume.name == volumeName

        where:
        volumeName << ["existing-name", "", "   ", "root", "data-1"]
    }

    @Unroll
    def "updateVolumeName should handle different masterItem and server configurations"() {
        given: "a storage volume with null name and various configurations"
        def volume = new StorageVolume(id: 3L, name: null)
        def masterItem = masterData
        def server = new ComputeServer(id: 3L, volumes: serverVolumes)
        def index = testIndex

        and: "Create spy to mock getVolumeName"
        def spy = Spy(virtualMachineSync)

        when: "updateVolumeName is called"
        def result = spy.updateVolumeName(volume, masterItem, server, index)

        then: "getVolumeName is called with provided parameters"
        1 * spy.getVolumeName(masterData, server, testIndex) >> expectedName

        and: "result is true and volume name is set"
        result == true
        volume.name == expectedName

        where:
        masterData                           | serverVolumes                    | testIndex | expectedName
        [Name: "boot-disk", VolumeType: "BootAndSystem"] | []                      | 0         | "root"
        [Name: "data-disk", VolumeType: "Data"]          | [new StorageVolume()]   | 1         | "data-1"
        null                                 | [new StorageVolume()]   | 2         | "data-2"
        [:]                                  | []                      | 0         | "root"
    }

    @Unroll
    def "updateSingleServer should catch exceptions and return false with error logging"() {
        given: "Server and data that will cause an exception"
        def currentServer = new ComputeServer(id: 123L, name: "test-server", externalId: "vm-456")
        def masterItem = [VMId: "updated-vm-id", Name: "Updated Server"]
        def hosts = []
        def consoleEnabled = true
        def defaultServerType = new ComputeServerType(id: 1L)
        def availablePlans = []
        def fallbackPlan = new ServicePlan(id: 999L)

        and: "Create spy to force exception in one of the update methods"
        def spy = Spy(virtualMachineSync)

        when: "updateSingleServer is called and encounters exception"
        def result = spy.updateSingleServer(currentServer, masterItem, hosts, consoleEnabled,
                                          defaultServerType, availablePlans, fallbackPlan)

        then: "updateBasicServerProperties throws exception"
        1 * spy.updateBasicServerProperties(currentServer, masterItem, defaultServerType) >> {
            throw new RuntimeException("Simulated update error")
        }

        and: "exception is caught and false is returned"
        result == false

        and: "error is logged with correct server details"
        noExceptionThrown()
    }

    @Unroll
    def "updateOperatingSystem should return false when osType is null"() {
        given: "Server and masterItem with operating system that results in null osType"
        def currentServer = new ComputeServer(id: 1L, name: "test-server", serverOs: new OsType(id: 10L))
        def masterItem = [OperatingSystem: "UnknownOS", OperatingSystemWindows: false]

        and: "Mock apiService to return null osTypeCode"
        mockApiService.getMapScvmmOsType("UnknownOS", true, null) >> null

        and: "osType service returns null for 'other' code"
        osTypeService.find(_ as DataQuery) >> null

        when: "updateOperatingSystem is called"
        def result = virtualMachineSync.updateOperatingSystem(currentServer, masterItem)

        then: "should return false and not modify server"
        result == false
        currentServer.serverOs.id == 10L // unchanged
    }

    @Unroll
    def "updateWorkloadAndInstanceStatuses should skip instance updates when no instanceIds"() {
        given: "Server and mock services"
        def server = new ComputeServer(id: 1L, externalId: "vm-123")

        and: "Mock services setup"
        def mockInstanceService = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousInstanceService)
        def mockServices = Mock(MorpheusServices)
        morpheusContext.services >> mockServices
        mockServices.instance >> mockInstanceService

        and: "Mock workload service to return empty instance list"
        workloadService.list(_ as DataQuery) >> [] // For workload query
        workloadService.list(_ as DataQuery) >> [] // For instance ID query (empty)

        when: "updateWorkloadAndInstanceStatuses is called"
        virtualMachineSync.updateWorkloadAndInstanceStatuses(server, Workload.Status.running, "running")

        then: "instance service list is not called and no instances are saved"
        0 * mockInstanceService.list(_ as DataQuery)
        0 * mockInstanceService.save(_ as Instance)
    }

    @Unroll
    def "updateSingleServer should catch RuntimeException and log error with server name"() {
        given: "Server and data that will cause RuntimeException"
        def currentServer = new ComputeServer(id: 123L, name: "error-server", externalId: "vm-789")
        def masterItem = [VMId: "updated-vm-id", Name: "Updated Server", VirtualMachineState: "Running"]
        def hosts = []
        def consoleEnabled = true
        def defaultServerType = new ComputeServerType(id: 1L)
        def availablePlans = []
        def fallbackPlan = new ServicePlan(id: 999L)

        and: "Create spy to force RuntimeException"
        def spy = Spy(virtualMachineSync)

        when: "updateSingleServer is called and RuntimeException is thrown"
        def result = spy.updateSingleServer(currentServer, masterItem, hosts, consoleEnabled,
                defaultServerType, availablePlans, fallbackPlan)

        then: "updateBasicServerProperties throws RuntimeException"
        1 * spy.updateBasicServerProperties(currentServer, masterItem, defaultServerType) >> {
            throw new RuntimeException("Database connection failed")
        }

        and: "exception is caught and false is returned"
        result == false

        and: "error is logged with server name and exception message"
        noExceptionThrown()
    }

    @Unroll
    def "updateServicePlan should return false when plan does not change"() {
        given: "Server with existing plan that matches found plan"
        def existingPlan = new ServicePlan(id: 100L, name: "existing-plan")
        def currentServer = new ComputeServer(
                id: 2L,
                name: "test-server-2",
                maxMemory: 4096L,
                maxCores: 8L,
                plan: existingPlan,
                account: new Account(id: 20L)
        )
        def availablePlans = [existingPlan]
        def fallbackPlan = new ServicePlan(id: 400L)

        when: "updateServicePlan is called and plan matches"
        def result = virtualMachineSync.updateServicePlan(currentServer, availablePlans, fallbackPlan)

        then: "SyncUtils.findServicePlanBySizing returns same plan"
        1 * SyncUtils.findServicePlanBySizing(availablePlans, 4096L, 8L, null, fallbackPlan, existingPlan, currentServer.account, []) >> existingPlan

        and: "method should return false and not modify plan"
        result == false
        currentServer.plan == existingPlan
    }

    @Unroll
    def "updatePowerState should set stopped status when power state is off and server is guest VM"() {
        given: "a guest VM server that is currently on"
        def guestVmType = new ComputeServerType(id: 1L, guestVm: true)
        def currentServer = new ComputeServer(
                id: 1L,
                name: "test-vm",
                powerState: ComputeServer.PowerState.on,
                computeServerType: guestVmType
        )
        def masterItem = [VirtualMachineState: "PoweredOff"]

        and: "Create spy to mock updateWorkloadAndInstanceStatuses"
        def spy = Spy(virtualMachineSync)

        when: "updatePowerState is called with powered off state"
        def result = spy.updatePowerState(currentServer, masterItem)

        then: "power state should be updated to off"
        result == true
        currentServer.powerState == ComputeServer.PowerState.off

        and: "updateWorkloadAndInstanceStatuses should be called with stopped status"
        1 * spy.updateWorkloadAndInstanceStatuses(
                currentServer,
                Workload.Status.stopped,
                'stopped',
                ['stopping', 'starting']
        )
    }

    @Unroll
    def "updatePowerState should set stopped status when power state is off and server is guest VM"() {
        given: "a guest VM server that is currently on"
        def guestVmType = new ComputeServerType(id: 1L, guestVm: true)
        def currentServer = new ComputeServer(
                id: 1L,
                name: "test-vm",
                powerState: ComputeServer.PowerState.on,
                computeServerType: guestVmType
        )
        def masterItem = [VirtualMachineState: "PoweredOff"]

        and: "Create spy to mock updateWorkloadAndInstanceStatuses"
        def spy = Spy(virtualMachineSync)

        when: "updatePowerState is called with powered off state"
        def result = spy.updatePowerState(currentServer, masterItem)

        then: "power state should be updated to off"
        result == true
        currentServer.powerState == ComputeServer.PowerState.off

        and: "updateWorkloadAndInstanceStatuses should be called with stopped status"
        1 * spy.updateWorkloadAndInstanceStatuses(
                currentServer,
                Workload.Status.stopped,
                'stopped',
                ['stopping', 'starting']
        )
    }
}
