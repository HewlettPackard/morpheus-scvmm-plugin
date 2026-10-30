// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.BulkSaveResult
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.MorpheusStorageVolumeTypeService
import com.morpheusdata.core.compute.MorpheusComputeServerInterfaceService
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.synchronous.MorpheusSynchronousInstanceService
import com.morpheusdata.core.synchronous.MorpheusSynchronousOsTypeService
import com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService
import com.morpheusdata.core.synchronous.MorpheusSynchronousServicePlanService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.Instance
import com.morpheusdata.model.NetAddress
import com.morpheusdata.model.Network
import com.morpheusdata.model.OsType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.Workload
import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.scvmm.support.SpecFixtures
import com.morpheusdata.scvmm.support.Domain
import com.morpheusdata.scvmm.support.ScvmmSpec
import com.morpheusdata.scvmm.support.VmmPayloads
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single

/**
 * {@link VirtualMachineSync}: {@code updateMatchedVirtualMachines} + volume sync (incl. MORPH-19779) and
 * {@code execute} / {@code addMissingVirtualMachines} / {@code syncInterfaces}.
 * The sync is a real instance with its {@code apiService} replaced by a mock; private methods are
 * called directly (Groovy ignores visibility).
 */
class VirtualMachineSyncSpec extends ScvmmSpec {

    static final long MB = 1024L * 1024L
    static final long GB = 1024L * MB

    ScvmmApiService apiService = Mock()
    CloudProvider cloudProvider = Mock()
    MorpheusSynchronousOsTypeService osTypeService = Mock()
    MorpheusSynchronousServicePlanService servicePlanService = Mock()
    MorpheusSynchronousResourcePermissionService permissionService = Mock()
    MorpheusSynchronousInstanceService instanceService = Mock()
    MorpheusSynchronousDatastoreService datastoreService = Mock()
    MorpheusComputeServerInterfaceService interfaceService = Mock()
    MorpheusStorageVolumeTypeService volumeTypeService = Mock()

    Cloud cloud = Domain.cloud(config: [username: 'DOMAIN\\svc', password: 'pw'])
    ComputeServer controllerNode = Domain.controller()
    ComputeServer host1 = Domain.server(id: 3L, name: 'HYPERV-1', externalId: 'host-1')
    ComputeServer host2 = Domain.server(id: 4L, name: 'HYPERV-2', externalId: 'host-2')
    ComputeServerType unmanaged = new ComputeServerType(id: 8L, code: 'scvmmUnmanaged', guestVm: true)
    OsType ubuntu = new OsType(id: 12L, code: 'ubuntu.64', platform: PlatformType.linux)
    StorageVolumeType standard = new StorageVolumeType(id: 9L, code: 'standard')
    List<List> bulkSaves = []

    VirtualMachineSync sync

    def setup() {
        SpecFixtures.installJsonCodec()
        services.getOsType() >> osTypeService
        services.getServicePlan() >> servicePlanService
        services.getResourcePermission() >> permissionService
        services.getInstance() >> instanceService
        cloudService.getDatastore() >> datastoreService
        asyncComputeServerService.getComputeServerInterface() >> interfaceService
        asyncStorageVolumeService.getStorageVolumeType() >> volumeTypeService

        osTypeService.find(_) >> ubuntu
        volumeTypeService.find(_) >> Maybe.just(standard)
        volumeTypeService.get(_) >> Maybe.just(standard)
        interfaceService.save(_) >> Single.just(true)
        interfaceService.create(_, _) >> Single.just(true)
        interfaceService.remove(_, _) >> Single.just(true)
        asyncComputeServerService.bulkSave(_) >> { args -> bulkSaves << args[0]; Single.just(new BulkSaveResult(null, null, args[0], [])) }
        asyncComputeServerService.save(_) >> { args -> Single.just(args[0]) }
        asyncStorageVolumeService.bulkSave(_) >> { args -> Single.just(new BulkSaveResult(null, null, args[0], [])) }
        asyncStorageVolumeService.remove(_ as StorageVolume) >> Single.just(true)
        apiService.getMapScvmmOsType(*_) >> 'ubuntu.64'
        apiService.getDiskName(_) >> { int i -> 'sd' + ('a'..'z')[i] }

        sync = new VirtualMachineSync(controllerNode, cloud, context, cloudProvider)
        sync.@apiService = apiService
    }

    def cleanup() {
        SpecFixtures.uninstallJsonCodec()
    }

    /** Server already in step with {@code vm()}, so any save is caused by the override under test. */
    private ComputeServer matched(Map overrides = [:]) {
        Domain.server([id: 10L, name: 'vm-1', externalId: 'vm-1', internalId: 'hv-vm-1', cloud: cloud, computeServerType: unmanaged,
                       maxCores: 2L, maxMemory: 4096L * MB, hotResize: true, cpuHotResize: true, parentServer: host1,
                       serverOs: ubuntu, powerState: ComputeServer.PowerState.on, interfaces: [], volumes: [],
                       status: 'provisioned', config: [generation: 'generation2']] + overrides)
    }

    /**
     * VM payload with dynamic memory on, matching the {@code hotResize: true} of {@link #matched}, so the in-step
     * fixture produces no save of its own.
     */
    private static Map vm(Map overrides = [:]) {
        VmmPayloads.vm([DynamicMemoryEnabled: true] + overrides)
    }

    private void update(List<ComputeServer> servers, List<Map> vms, boolean consoleEnabled = false) {
        computeServerService.list(_) >> servers
        def items = [servers, vms].transpose().collect { s, vm -> new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: s, masterItem: vm) }
        sync.updateMatchedVirtualMachines(items, [], null, [host1, host2], consoleEnabled, unmanaged, [:])
    }

    // ------------------------------------------------------------------ updateMatchedVirtualMachines

    def "updateMatchedVirtualMachines does not save a VM that is already in step with SCVMM"() {
        when:
        update([matched()], [vm()])

        then:
        bulkSaves.isEmpty()
    }

    def "updateMatchedVirtualMachines follows a rename and the Hyper-V VMId"() {
        given:
        def server = matched()

        when:
        update([server], [vm(Name: 'renamed', VMId: 'hv-new')])

        then:
        server.name == 'renamed'
        server.internalId == 'hv-new'
        bulkSaves == [[server]]
    }

    def "updateMatchedVirtualMachines keeps the known IPs when SCVMM reports #why (MORPH-19779)"() {
        given:
        def server = matched(externalIp: '10.0.0.5', internalIp: '10.0.0.5', sshHost: '10.0.0.5')

        when:
        update([server], [vm(payload)])

        then:
        server.externalIp == '10.0.0.5'
        server.internalIp == '10.0.0.5'
        server.sshHost == '10.0.0.5'
        bulkSaves.isEmpty()

        where:
        why            | payload
        'empty IPs'    | [IpAddress: '', InternalIp: '']
        'missing keys' | [IpAddress: null, InternalIp: null]
    }

    def "updateMatchedVirtualMachines moves externalIp, sshHost and the matching interface to a new IP"() {
        given:
        def nic = Domain.nic(publicIpAddress: '10.0.0.5')
        def server = matched(externalIp: '10.0.0.5', sshHost: '10.0.0.5', interfaces: [nic])

        when:
        update([server], [vm(IpAddress: '10.0.0.9', NetworkAdapters: null)])

        then:
        1 * interfaceService.save([nic]) >> Single.just(true)
        nic.publicIpAddress == '10.0.0.9'
        server.externalIp == '10.0.0.9'
        server.sshHost == '10.0.0.9'
        bulkSaves == [[server]]
    }

    def "updateMatchedVirtualMachines follows a new internal IP but leaves a user-set sshHost alone"() {
        given:
        def server = matched(internalIp: '192.168.0.5', sshHost: 'jump.example.com')

        when:
        update([server], [vm(InternalIp: '192.168.0.9', NetworkAdapters: null)])

        then:
        server.internalIp == '192.168.0.9'
        server.sshHost == 'jump.example.com'
    }

    def "updateMatchedVirtualMachines updates cores, memory and capacityInfo"() {
        given:
        def server = matched(capacityInfo: new com.morpheusdata.model.ComputeCapacityInfo(maxCores: 2L))

        when:
        update([server], [vm(CPUCount: 4, Memory: 8192)])

        then:
        server.maxCores == 4L
        server.maxMemory == 8L * GB
        server.capacityInfo.maxCores == 4L
        bulkSaves == [[server]]
    }

    def "updateMatchedVirtualMachines maps Generation #gen and DynamicMemoryEnabled #dyn to hot-resize flags"() {
        given:
        def server = matched()

        when:
        update([server], [vm(Generation: gen, DynamicMemoryEnabled: dyn)])

        then:
        server.cpuHotResize == cpuHot
        server.hotResize == memHot
        server.getConfigProperty('generation') == generation

        where:
        gen  | dyn     || cpuHot | memHot | generation
        1    | true    || false  | true   | 'generation1'
        2    | 'false' || true   | false  | 'generation2'
        '2'  | 'true'  || true   | true   | 'generation2'
        null | false   || true   | true   | 'generation2'
    }

    def "updateMatchedVirtualMachines re-points the parent host only to a known host"() {
        given:
        def moved = matched(id: 10L)
        def unknown = matched(id: 11L, externalId: 'vm-2')

        when:
        update([moved, unknown], [vm(HostId: 'host-2'), vm(ID: 'vm-2', HostId: 'host-x')])

        then:
        moved.parentServer.is(host2)
        unknown.parentServer.is(host1)
        bulkSaves == [[moved]]
    }

    def "updateMatchedVirtualMachines sets the vmrdp console from the cloud credentials without touching ssh credentials"() {
        given:
        def server = matched(sshUsername: 'guest', sshPassword: 'guest-pw')

        when:
        update([server], [vm()], true)

        then:
        server.consoleType == 'vmrdp'
        server.consolePort == 2179
        server.consoleHost == 'HYPERV-1'
        server.consoleUsername == 'DOMAIN\\svc'
        server.consolePassword == 'pw'
        server.sshUsername == 'guest'
        server.sshPassword == 'guest-pw'
        bulkSaves == [[server]]
    }

    def "updateMatchedVirtualMachines assigns the default server type to a VM without one"() {
        given:
        def server = matched(computeServerType: null)

        when:
        update([server], [vm()])

        then:
        server.computeServerType.is(unmanaged)
    }

    def "updateMatchedVirtualMachines follows an OS change reported by SCVMM"() {
        given:
        def windows = new OsType(id: 13L, code: 'windows.server.2022', platform: PlatformType.windows)
        def server = matched()

        when:
        update([server], [vm(OperatingSystem: 'Windows Server 2022 Datacenter', OperatingSystemWindows: true)])

        then:
        1 * apiService.getMapScvmmOsType('Windows Server 2022 Datacenter', true, 'windows') >> 'windows.server.2022'
        1 * osTypeService.find(_) >> windows
        server.serverOs.is(windows)
        server.osType == 'windows'
    }

    def "updateMatchedVirtualMachines turns VMM state #state into power #power and updates workload and instance statuses"() {
        given:
        def server = matched(powerState: null)
        def wl = new Workload(id: 20L, status: Workload.Status.running, instance: new Instance(id: 30L))
        def inst = new Instance(id: 30L, status: 'running')

        when:
        update([server], [vm(VirtualMachineState: state)])

        then:
        2 * workloadService.list(_) >> [wl]
        1 * instanceService.list(_) >> [inst]
        server.powerState == power
        wl.status == workloadStatus
        inst.status == instanceStatus

        where:
        state      || power                          | workloadStatus           | instanceStatus
        'Running'  || ComputeServer.PowerState.on    | Workload.Status.running  | 'running'
        'PowerOff' || ComputeServer.PowerState.off   | Workload.Status.stopped  | 'stopped'
        'Paused'   || ComputeServer.PowerState.off   | Workload.Status.stopped  | 'stopped'
    }

    def "updateMatchedVirtualMachines skips servers that are still provisioning"() {
        given:
        def server = matched(status: 'provisioning')

        when:
        update([server], [vm(Name: 'renamed')])

        then:
        server.name == 'vm-1'
        bulkSaves.isEmpty()
    }

    def "updateMatchedVirtualMachines syncs disks only when the server is not #status"() {
        given:
        def server = matched(status: status)
        asyncStorageVolumeService.listById(_) >> Observable.empty()

        when:
        update([server], [vm(Disks: [VmmPayloads.vmDisk()])])

        then:
        calls * storageVolumeService.create(_)

        where:
        status        || calls
        'provisioned' || 1
        'resizing'    || 0
    }

    def "updateMatchedVirtualMachines skips interface sync when SCVMM reports NetworkAdapters null"() {
        given:
        def server = matched(interfaces: [Domain.nic()])

        when:
        update([server], [vm(NetworkAdapters: null)])

        then:
        0 * interfaceService.remove(*_)
        0 * interfaceService.create(*_)
        bulkSaves.isEmpty()
    }

    def "updateMatchedVirtualMachines keeps going after one VM fails and saves the rest"() {
        given:
        def broken = matched(id: 10L)
        def fine = matched(id: 11L, externalId: 'vm-2')

        when:
        update([broken, fine], [vm(CPUCount: null), vm(ID: 'vm-2', Name: 'renamed')])

        then:
        bulkSaves == [[fine]]
    }

    // ------------------------------------------------------------------ updateMatchedVirtualMachines: NIC changes vs bulkSave

    List<DataQuery> serverQueries = []

    /** First list() is the matched-server load; any later list() is the interface reload before bulkSave. */
    private void updateWithReload(List<ComputeServer> servers, List<Map> vms, List<ComputeServer> reloaded, Map systemNetworks = [:]) {
        computeServerService.list(_) >> { DataQuery q -> serverQueries << q; serverQueries.size() == 1 ? servers : reloaded }
        def items = [servers, vms].transpose().collect { s, vm -> new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: s, masterItem: vm) }
        sync.updateMatchedVirtualMachines(items, [], null, [host1, host2], false, unmanaged, systemNetworks)
    }

    private ComputeServerInterface eth0() {
        Domain.nic(id: 70L, name: 'eth0', externalId: 'nic-1', macAddress: '00:15:5D:00:00:01', vlanId: '0', network: vnet,
                   dhcp: true, primaryInterface: true, addresses: [])
    }

    private static Map secondAdapter() {
        VmmPayloads.vmAdapter(ID: 'nic-2', Name: 'nic-2', SlotId: 1, MacAddress: '00:15:5D:00:00:02', IPv4Addresses: ['10.0.0.20'])
    }

    def "updateMatchedVirtualMachines saves a changed VM with the re-read interface list after an out-of-band NIC is added"() {
        given:
        def nic0 = eth0()
        def server = matched(interfaces: [nic0])
        def createdRow = Domain.nic(id: 72L, name: 'eth1', externalId: 'nic-2', macAddress: '00:15:5D:00:00:02', primaryInterface: false)
        def reloaded = matched(interfaces: [eth0(), createdRow])
        List<ComputeServerInterface> created

        when:
        updateWithReload([server], [vm(Name: 'renamed', NetworkAdapters: [VmmPayloads.vmAdapter(), secondAdapter()])], [reloaded], ['vmnet-1': vnet])

        then:
        1 * interfaceService.create(_, server) >> { args -> created = args[0]; Single.just(true) }
        0 * interfaceService.remove(*_)
        created*.externalId == ['nic-2']
        created[0].name == 'eth1'
        bulkSaves == [[server]]
        server.name == 'renamed'
        server.interfaces*.externalId == ['nic-1', 'nic-2']
        server.interfaces[1].is(createdRow)
        serverQueries.size() == 2
        serverQueries[1].joins.containsAll(['interfaces', 'interfaces.network', 'interfaces.addresses'])
    }

    def "updateMatchedVirtualMachines saves a changed VM without an interface SCVMM no longer reports"() {
        given:
        def nic0 = eth0()
        def gone = Domain.nic(id: 71L, name: 'eth1', externalId: 'nic-gone', primaryInterface: false)
        def server = matched(interfaces: [nic0, gone])
        def reloaded = matched(interfaces: [eth0()])

        when:
        updateWithReload([server], [vm(Name: 'renamed', NetworkAdapters: [VmmPayloads.vmAdapter()])], [reloaded], ['vmnet-1': vnet])

        then:
        1 * interfaceService.remove([gone], server) >> Single.just(true)
        0 * interfaceService.create(*_)
        bulkSaves == [[server]]
        server.interfaces*.externalId == ['nic-1']
    }

    def "updateMatchedVirtualMachines does not save a VM whose only change is a #why NIC"() {
        given:
        def gone = Domain.nic(id: 71L, externalId: 'nic-gone', primaryInterface: false)
        def server = matched(interfaces: why == 'new' ? [eth0()] : [eth0(), gone])
        def adapters = why == 'new' ? [VmmPayloads.vmAdapter(), secondAdapter()] : [VmmPayloads.vmAdapter()]

        when:
        updateWithReload([server], [vm(NetworkAdapters: adapters)], [], ['vmnet-1': vnet])

        then:
        creates * interfaceService.create(_, server) >> Single.just(true)
        removes * interfaceService.remove(_, server) >> Single.just(true)
        bulkSaves.isEmpty()
        serverQueries.size() == 1

        where:
        why       || creates | removes
        'new'     || 1       | 0
        'removed' || 0       | 1
    }

    def "updateMatchedVirtualMachines neither touches interfaces nor saves when the NICs match SCVMM"() {
        given:
        def server = matched(interfaces: [eth0()])

        when:
        updateWithReload([server], [vm(NetworkAdapters: [VmmPayloads.vmAdapter()])], [], ['vmnet-1': vnet])

        then:
        0 * interfaceService.create(*_)
        0 * interfaceService.remove(*_)
        0 * interfaceService.save(*_)
        bulkSaves.isEmpty()
        serverQueries.size() == 1
    }

    def "updateMatchedVirtualMachines leaves out of the save a VM whose interfaces cannot be re-read and saves the rest"() {
        given:
        def nicChanged = matched(id: 10L, interfaces: [eth0()])
        def other = matched(id: 11L, externalId: 'vm-2')

        when:
        updateWithReload([nicChanged, other],
                [vm(Name: 'renamed', NetworkAdapters: [VmmPayloads.vmAdapter(), secondAdapter()]), vm(ID: 'vm-2', Name: 'renamed-2')],
                [], ['vmnet-1': vnet])

        then:
        1 * interfaceService.create(_, nicChanged) >> Single.just(true)
        bulkSaves == [[other]]
    }

    // ------------------------------------------------------------------ syncVolumes

    def "syncVolumes updates a matched disk that grew by more than 1 GB and fixes its root flag and name"() {
        given:
        def vol = Domain.volume(id: 60L, externalId: 'disk-1', maxStorage: 40 * GB, rootVolume: false, internalId: 'old.vhdx', name: null)
        def server = matched(volumes: [vol, Domain.volume(id: 61L, externalId: 'disk-2', rootVolume: false)])
        asyncStorageVolumeService.listById(_) >> Observable.fromIterable([vol])

        when:
        sync.syncVolumes(server, [VmmPayloads.vmDisk(ID: 'disk-1', Name: 'disk-1.vhdx', TotalSize: 50 * GB)])

        then:
        1 * asyncStorageVolumeService.bulkSave([vol]) >> Single.just(new BulkSaveResult(null, null, [vol], []))
        vol.maxStorage == 50 * GB
        vol.internalId == 'disk-1.vhdx'
        vol.rootVolume
        !vol.removable
        vol.name == 'root'
    }

    def "syncVolumes ignores a size drift within 1 GB"() {
        given:
        def vol = Domain.volume(id: 60L, externalId: 'disk-1', maxStorage: 40 * GB, rootVolume: true, internalId: 'disk-1.vhdx', removable: false)
        def server = matched(volumes: [vol])
        asyncStorageVolumeService.listById(_) >> Observable.fromIterable([vol])

        when:
        sync.syncVolumes(server, [VmmPayloads.vmDisk(TotalSize: 40 * GB + 512 * MB)])

        then:
        0 * asyncStorageVolumeService.bulkSave(_)
        vol.maxStorage == 40 * GB
    }

    def "syncVolumes removes a volume whose disk is gone from SCVMM"() {
        given:
        def vol = Domain.volume(id: 61L, externalId: 'disk-gone', datastore: new Datastore(id: 6L))
        def server = matched(volumes: [vol])

        when:
        sync.syncVolumes(server, [])

        then:
        1 * asyncStorageVolumeService.remove(vol) >> Single.just(true)
        server.volumes.isEmpty()
        vol.datastore == null
    }

    def "syncVolumes adds a new disk with its datastore resolved by #how"() {
        given:
        def ds = new Datastore(id: 6L, name: 'CSV1')
        def server = matched(volumes: [])
        StorageVolume created
        storageVolumeService.find(_) >>> volumeLookups.collect { it ? new StorageVolume(datastore: ds) : null }
        datastoreService.find(_) >> (how == 'fileShareId' ? ds : null)
        datastoreService.get(6L) >> ds

        when:
        sync.syncVolumes(server, [VmmPayloads.vmDisk(disk)])

        then:
        1 * storageVolumeService.create(_) >> { args -> created = args[0] }
        created.name == 'root'
        created.rootVolume
        created.externalId == 'disk-1'
        created.internalId == 'disk-1.vhdx'
        // pinned as-is: the device index starts at the number of SCVMM disks, so the only (root) disk is 'sdb'
        created.deviceName == 'sdb'
        created.type.is(standard)
        created.datastore.is(ds)
        created.refType == 'Datastore'
        server.volumes == [created]

        where:
        how                 | disk                                                           | volumeLookups
        'hostVolumeId'      | [:]                                                            | [true]
        'partitionUniqueId' | [:]                                                            | [false, true]
        'fileShareId'       | [HostVolumeId: null, FileShareId: 'share-1']                   | []
    }

    def "syncVolumes adds a disk without a datastore when none can be resolved"() {
        given:
        def server = matched(volumes: [Domain.volume(id: 60L, externalId: 'disk-1')])
        asyncStorageVolumeService.listById(_) >> Observable.fromIterable(server.volumes)
        StorageVolume created

        when:
        sync.syncVolumes(server, [VmmPayloads.vmDisk(), VmmPayloads.vmDisk(ID: 'disk-2', Name: 'disk-2.vhdx', VolumeType: 'None', HostVolumeId: 'unknown')])

        then:
        1 * storageVolumeService.create(_) >> { args -> created = args[0] }
        created.datastore == null
        created.rootVolume == false
        created.removable
        // pinned as-is: new disks take the name of the existing volume at the same index, so this one is also 'root'
        created.name == 'root'
    }

    def "syncVolumes ignores null entries in the SCVMM disk list"() {
        given:
        def server = matched(volumes: [])

        when:
        sync.syncVolumes(server, [null])

        then:
        0 * storageVolumeService.create(_)
        noExceptionThrown()
    }

    def "getVolumeName names the boot disk (or the first disk) root and others data-<index>"() {
        expect:
        sync.getVolumeName([VolumeType: volumeType], 'sdb', matched(volumes: volumes), 2) == expected

        where:
        volumeType      | volumes           || expected
        'BootAndSystem' | [Domain.volume()] || 'root'
        'None'          | []                || 'root'
        'None'          | [Domain.volume()] || 'data-2'
    }

    def "getStorageVolumeType looks up the scvmm VHD type code and falls back to standard"() {
        when:
        def id = sync.getStorageVolumeType(code)

        then:
        1 * volumeTypeService.find({ it.filters.find { f -> f.name == 'code' }.value == expected }) >> Maybe.just(standard)
        id == 9L

        where:
        code                               || expected
        'scvmm-dynamicallyexpanding-vhdx'  || 'scvmm-dynamicallyexpanding-vhdx'
        null                               || 'standard'
    }

    // ------------------------------------------------------------------ execute

    private void executeFixture(ComputeServer existing, ComputeServer gone, List<Map> vms) {
        apiService.getScvmmZoneAndHypervisorOpts(*_) >> [zoneId: 1L]
        apiService.listVirtualMachines(_) >> [success: true, virtualMachines: vms]
        computeServerService.list(_) >> { args -> args[0].filters.any { it.name == 'id' } ? [existing] : [host1] }
        servicePlanService.list(_) >> []
        asyncCloudService.findComputeServerTypeByCode('scvmmUnmanaged') >> Maybe.just(unmanaged)
        cloudService.getNetwork() >> networkService
        networkService.list(_) >> []
        asyncComputeServerService.listIdentityProjections(_) >> Observable.fromIterable([existing, gone])
        asyncComputeServerService.listById([existing.id]) >> Observable.just(existing)
        computeServerService.listIdentityProjections(_) >> [gone]
    }

    def "execute creates new VMs, updates matched ones and removes missing unmanaged ones"() {
        given:
        def existing = matched()
        def gone = matched(id: 11L, externalId: 'vm-gone')
        executeFixture(existing, gone, [vm(Name: 'renamed'), vm(ID: 'vm-new', Name: 'brand-new')])
        DataQuery removeQuery

        when:
        sync.execute(true)

        then:
        1 * asyncComputeServerService.create({ it.externalId == 'vm-new' && it.name == 'brand-new' }) >> { args -> Single.just(args[0]) }
        1 * computeServerService.listIdentityProjections(_) >> { args -> removeQuery = args[0]; [gone] }
        1 * asyncComputeServerService.remove([gone]) >> Single.just(true)
        existing.name == 'renamed'
        bulkSaves.contains([existing])
        removeQuery.filters.find { it.name == 'computeServerType.code' }.value == 'scvmmUnmanaged'
    }

    def "execute with createNew=false does not import unknown VMs"() {
        given:
        def existing = matched()
        executeFixture(existing, matched(id: 11L, externalId: 'vm-gone'), [vm(), vm(ID: 'vm-new')])
        asyncComputeServerService.remove(_) >> Single.just(true)

        when:
        sync.execute(false)

        then:
        0 * asyncComputeServerService.create(_)
    }

    def "execute does nothing when SCVMM #why"() {
        given:
        apiService.getScvmmZoneAndHypervisorOpts(*_) >> [:]

        when:
        sync.execute(true)

        then:
        1 * apiService.listVirtualMachines(_) >> response
        0 * asyncComputeServerService.listIdentityProjections(_)
        0 * asyncComputeServerService.create(_)
        noExceptionThrown()

        where:
        why                    | response
        'returns success=false' | { [success: false, error: 'access denied'] }
        'throws'               | { throw new IllegalStateException('winrm') }
    }

    // ------------------------------------------------------------------ addMissingVirtualMachines

    private List<ComputeServer> addMissing(List<Map> vms, boolean consoleEnabled = false) {
        List<ComputeServer> created = []
        asyncComputeServerService.create(_) >> { args -> created << args[0]; Single.just(args[0]) }
        sync.addMissingVirtualMachines(vms, [], fallback, [], [host1, host2], consoleEnabled, unmanaged, [:])
        created
    }

    com.morpheusdata.model.ServicePlan fallback = new com.morpheusdata.model.ServicePlan(id: 99L, code: 'internal-custom-scvmm')

    def "addMissingVirtualMachines builds an unmanaged discovered server from the SCVMM payload"() {
        when:
        def created = addMissing([vm(ID: 'vm-9', Name: 'imported', VMId: 'hv-9', CPUCount: 4, Memory: 2048, HostId: 'host-2',
                IpAddress: '10.0.0.9', InternalIp: '192.168.0.9', Generation: 1, VirtualMachineState: 'PowerOff', TotalSize: 100, UsedSize: 40)])

        then:
        created.size() == 1
        with(created[0]) {
            name == 'imported'
            externalId == 'vm-9'
            uniqueId == 'vm-9'
            internalId == 'hv-9'
            !managed
            discovered
            computeServerType.is(unmanaged)
            maxCores == 4L
            maxMemory == 2048L * MB
            usedStorage == 40L
            parentServer.is(host2)
            externalIp == '10.0.0.9'
            internalIp == '192.168.0.9'
            sshHost == '192.168.0.9'
            serverOs.is(ubuntu)
            osType == 'linux'
            powerState == ComputeServer.PowerState.off
            getConfigProperty('generation') == 'generation1'
            cpuHotResize == false
            plan.is(fallback)
            consoleType == null
        }
    }

    def "addMissingVirtualMachines leaves IPs unset when SCVMM reports none"() {
        when:
        def created = addMissing([vm(IpAddress: '', InternalIp: null)])

        then:
        created[0].externalIp == null
        created[0].internalIp == null
        created[0].sshHost == null
    }

    def "addMissingVirtualMachines sets the vmrdp console when the cloud enables it"() {
        when:
        def created = addMissing([vm()], true)

        then:
        created[0].consoleType == 'vmrdp'
        created[0].consolePort == 2179
        created[0].consoleHost == 'HYPERV-1'
        created[0].consoleUsername == 'DOMAIN\\svc'
        created[0].sshUsername == null
    }

    def "addMissingVirtualMachines syncs the new VM's disks and adapters"() {
        when:
        addMissing([vm(Disks: [VmmPayloads.vmDisk()], NetworkAdapters: [VmmPayloads.vmAdapter()])])

        then:
        1 * storageVolumeService.create(_)
        1 * interfaceService.create({ it.size() == 1 && it[0].externalId == 'nic-1' }, _) >> Single.just(true)
    }

    // ------------------------------------------------------------------ syncInterfaces

    Network vnet = Domain.network(id: 110L, externalId: 'vmnet-1')
    Network vlan5 = Domain.network(id: 111L, externalId: 'vmnet-1-5')

    def "syncInterfaces creates an interface for a new adapter with network, primary flag, dhcp and addresses"() {
        given:
        def server = matched(interfaces: [], sourceImage: Domain.image(interfaceName: 'ens192'))
        List<ComputeServerInterface> created

        when:
        def changed = sync.syncInterfaces(server, [VmmPayloads.vmAdapter(VLanID: 5, IPv4Addresses: ['10.0.0.9'])], ['vmnet-1-5': vlan5])

        then:
        1 * interfaceService.create(_, server) >> { args -> created = args[0]; Single.just(true) }
        changed
        with(created[0]) {
            externalId == 'nic-1'
            name == 'ens192'
            macAddress == '00:15:5D:00:00:01'
            vlanId == '5'
            network.is(vlan5)
            primaryInterface
            dhcp
            addresses*.address == ['10.0.0.9']
        }
    }

    def "syncInterfaces updates a matched interface and saves it only when something changed"() {
        given:
        def nic = Domain.nic(externalId: 'nic-1', macAddress: 'old', vlanId: '0', network: vnet, dhcp: true, primaryInterface: true)
        def server = matched(interfaces: [nic])

        when:
        def changed = sync.syncInterfaces(server, [VmmPayloads.vmAdapter(MacAddress: mac)], ['vmnet-1': vnet])

        then:
        saves * interfaceService.save([nic]) >> Single.just(true)
        changed == (saves == 1)
        nic.macAddress == mac

        where:
        mac                 || saves
        '00:15:5D:00:00:02' || 1
        'old'               || 0
    }

    def "syncInterfaces removes interfaces SCVMM no longer reports"() {
        given:
        def stale = Domain.nic(id: 71L, externalId: 'nic-gone', primaryInterface: false)
        def server = matched(interfaces: [stale])

        when:
        def changed = sync.syncInterfaces(server, [], [:])

        then:
        1 * interfaceService.remove([stale], server) >> Single.just(true)
        changed
    }

    def "syncInterfaces does nothing for #why adapters on a server without interfaces"() {
        when:
        def changed = sync.syncInterfaces(matched(interfaces: []), adapters, [:])

        then:
        0 * interfaceService._
        !changed

        where:
        why     | adapters
        'empty' | []
        'null'  | null
    }

    def "syncInterfaces resolves the network by adapter ID plus VLAN when the VM network id does not match"() {
        given:
        def byAdapter = Domain.network(id: 112L, externalId: 'nic-1-7')
        List<ComputeServerInterface> created

        when:
        sync.syncInterfaces(matched(interfaces: []), [VmmPayloads.vmAdapter(VLanID: 7)], ['nic-1-7': byAdapter])

        then:
        1 * interfaceService.create(_, _) >> { args -> created = args[0]; Single.just(true) }
        created[0].network.is(byAdapter)
    }

    def "syncInterfaces only marks slot 0 primary when no interface is primary yet"() {
        given:
        List<ComputeServerInterface> created

        when:
        sync.syncInterfaces(matched(interfaces: []), [VmmPayloads.vmAdapter(ID: 'nic-2', SlotId: 1, MacAddress: '00:15:5D:00:00:09')], [:])

        then:
        1 * interfaceService.create(_, _) >> { args -> created = args[0]; Single.just(true) }
        !created[0].primaryInterface
    }
}
