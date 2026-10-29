// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.BulkSaveResult
import com.morpheusdata.core.synchronous.provisioning.MorpheusSynchronousProvisionService
import com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageLocationService
import com.morpheusdata.core.synchronous.library.MorpheusSynchronousWorkloadTypeService
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.Instance
import com.morpheusdata.model.OsType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.Workload
import com.morpheusdata.model.WorkloadType
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.request.UpdateModel
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.scvmm.logging.PrefixedLoggerFactory
import com.morpheusdata.scvmm.support.Domain
import com.morpheusdata.scvmm.support.ScvmmSpec
import io.reactivex.rxjava3.core.Single

/**
 * Provisioning lifecycle of {@link ScvmmProvisionProvider}: {@code runWorkload}, power/remove/destroy and
 * {@code resizeWorkloadAndServer} plan/volume changes. One class to keep {@code forkEvery = 1} cost down.
 */
class ScvmmProvisionProviderWorkloadSpec extends ScvmmSpec {

    ScvmmPlugin plugin = Mock()
    ScvmmApiService apiService = Mock()
    MorpheusSynchronousWorkloadTypeService workloadTypeService = Mock()
    MorpheusSynchronousProvisionService provisionService = Mock()
    MorpheusSynchronousVirtualImageLocationService imageLocationService = Mock()
    ScvmmProvisionProvider provider

    Cloud cloud = Domain.cloud(owner: new Account(id: 1L))
    ComputeServer controller = Domain.controller()
    ComputeServer node = Domain.server(id: 5L, name: 'hyperv-1', externalId: 'host-1')
    Datastore datastore = new Datastore(id: 6L, name: 'CSV1', externalId: 'ds-1')
    ServicePlan plan = Domain.plan()
    VirtualImage image
    StorageVolume rootVolume
    ComputeServer server
    Workload workload
    WorkloadRequest request
    Map createOpts
    Map createResults
    Closure placement = { args -> [node, datastore, 'C:\\ClusterStorage\\Volume1', false] }
    Closure createAnswer = { -> createResults }

    def setup() {
        services.getWorkloadType() >> workloadTypeService
        services.getProvision() >> provisionService
        virtualImageService.getLocation() >> imageLocationService
        context.getProcess() >> asyncProcessService
        asyncProcessService.startProcessStep(*_) >> Single.just(true)
        asyncWorkloadService.save(_) >> { args -> Single.just(args[0]) }
        asyncComputeServerService.save(_) >> { args -> Single.just(args[0]) }
        asyncComputeServerService.bulkSave(_) >> { args -> Single.just(new BulkSaveResult<ComputeServer>(null, null, args[0], [])) }

        image = Domain.image(refType: 'ComputeZone', isCloudInit: false, osType: new OsType(code: 'ubuntu.64', platform: PlatformType.linux),
                config: [generation: 'generation2'])
        rootVolume = Domain.volume(rootVolume: true, maxStorage: 42949672960L)
        server = Domain.server(cloud: cloud, volumes: [rootVolume], sourceImage: image, externalId: null)
        workload = Domain.workload(server: server, instance: Domain.instance(plan: plan), workloadType: new WorkloadType(id: 3L), config: [:])
        request = new WorkloadRequest(cloudConfigOpts: [:])
        createResults = [success: true, server: [id: 'vm-new', VMId: 'hv-guid',
                disks: [osDisk: [externalId: 'dd-0'], dataDisks: [], diskMetaData: ['dd-0': [VhdID: 'vhd-0', HostVolumeId: 'hv-0']]]]]
        computeServerService.get(_) >> { args -> [(10L): server, (5L): node][args[0]] }

        provider = GroovySpy(ScvmmProvisionProvider, constructorArgs: [plugin, context])
        // production closures resolve the context/log fields dynamically; on a GroovySpy that becomes getContext()/getLog()
        provider.getContext() >> context
        provider.getLog() >> PrefixedLoggerFactory.getLogger(ScvmmProvisionProvider)
        provider.apiService = apiService
        provider.pickScvmmController(_) >> controller
        provider.getHostAndDatastore(*_) >> { args -> placement(args) }
        provider.getDiskExternalIds(_, _) >> [[rootVolume: true, externalId: 'vhd-tpl', idx: 0]]
        provider.findVmNodeServerTypeForCloud(*_) >> null
        provider.saveAndGetMorpheusServer(_, _) >> { args -> args[0] }
        provider.loadDatastoreForVolume(*_) >> null
        provider.getScvmmContainerOpts(_) >> { args -> [vmId: args[0].server.externalId, externalId: args[0].server.externalId, platform: 'linux',
                serverFolder: 'morpheus\\morpheus_server_10', maxCores: 2L, maxMemory: 4294967296L, maxTotalStorage: 42949672960L,
                containerConfig: [networkId: 'net-c']] }
        provider.getScvmmServerOpts(_) >> { args -> [vmId: args[0].externalId, externalId: args[0].externalId] }
        apiService.getScvmmCloudOpts(*_) >> [zoneId: 1L]
        apiService.getScvmmZoneOpts(*_) >> [zoneId: 1L, diskRoot: 'C:\\disks']
        apiService.getScvmmControllerOpts(*_) >> [sshHost: 'scvmm.local']
        apiService.createServer(_) >> { args -> createOpts = new LinkedHashMap(args[0]); createAnswer() }
    }

    // ------------------------------------------------------------------ runWorkload

    def "runWorkload passes host, datastore, template and generation from a synced template to createServer"() {
        when:
        def rtn = provider.runWorkload(workload, request, [:])

        then:
        rtn.success
        createOpts.imageId == 'vhd-tpl'
        createOpts.templateId == 'tpl-1'
        createOpts.isSyncdImage == true
        createOpts.isTemplate == true
        createOpts.scvmmGeneration == 'generation2'
        createOpts.hostExternalId == 'host-1'
        createOpts.datastoreId == 'ds-1'
        createOpts.volumePath == 'C:\\ClusterStorage\\Volume1'
        createOpts.volumePaths == ['C:\\ClusterStorage\\Volume1']
        createOpts.highlyAvailable == false
        createOpts.controllerServerId == 2L
        createOpts.sshHost == 'scvmm.local'
        createOpts.networkId == 'net-c'
        createOpts.additionalTemplateDisks == []
        createOpts.name == 'vm-1'
    }

    def "runWorkload binds the created VM to the server, starts it and moves the process to the network step"() {
        when:
        def rtn = provider.runWorkload(workload, request, [:])

        then:
        1 * apiService.startServer(_, _) >> [success: true]
        1 * asyncProcessService.startProcessStep(_, _, 'waiting for network') >> Single.just(true)
        rtn.success
        server.externalId == 'vm-new'
        server.internalId == 'hv-guid'
        server.parentServer.is(node)
        server.status == 'provisioned'
        server.managed
        rootVolume.externalId == 'vhd-0'
        rootVolume.datastore.is(datastore)
        workload.getConfigMap().containsKey('deleteDvdOnComplete')
    }

    def "runWorkload leaves agent install to cloud-init for #platform servers (installAgent=#expected)"() {
        given:
        server.platform = platform

        when:
        def rtn = provider.runWorkload(workload, request, [:])

        then:
        rtn.data.installAgent == expected

        where:
        platform  || expected
        'linux'   || false
        'windows' || true
    }

    def "runWorkload sets vmrdp console fields when the cloud has enableVnc"() {
        given:
        cloud.setConfigProperty('enableVnc', 'on')

        when:
        provider.runWorkload(workload, request, [:])

        then:
        server.consoleType == 'vmrdp'
        server.consolePort == 2179
    }

    def "runWorkload prefers clone-request selections in opts over the stale container config"() {
        when:
        provider.runWorkload(workload, request, [config: [scvmmCapabilityProfile: 'Hyper-V', scvmmNetworkId: 'net-o', scvmmGeneration: 'generation1', scvmmHostId: 'host-9']])

        then:
        createOpts.scvmmCapabilityProfile == 'Hyper-V'
        createOpts.networkId == 'net-o'
        createOpts.scvmmGeneration == 'generation1'
        createOpts.hostId == 'host-9'
    }

    def "runWorkload places each data volume with a second getHostAndDatastore call pinned to the root's host"() {
        given:
        def data = Domain.volume(id: 61L, name: 'data-1', rootVolume: false, externalId: null)
        server.volumes << data

        when:
        provider.runWorkload(workload, request, [:])

        then:
        1 * provider.getHostAndDatastore(_, _, _, null, _, _, _, _, _) >> [node, datastore, 'C:\\root', false]
        1 * provider.getHostAndDatastore(_, _, _, 5L, _, _, _, _, _) >> [node, datastore, 'C:\\data', false]
        createOpts.volumePaths == ['C:\\root', 'C:\\data']
        data.volumePath == 'C:\\data'
        data.datastore.is(datastore)
    }

    def "runWorkload uploads a user image when the image has no externalId and records its location"() {
        given:
        image.refType = null
        image.externalId = null
        image.remotePath = 'vhd/ubuntu.vhdx'
        asyncVirtualImageService.getVirtualImageFiles(image) >> Single.just([Mock(com.bertramlabs.plugins.karman.CloudFile)])

        when:
        def rtn = provider.runWorkload(workload, request, [:])

        then:
        1 * apiService.insertContainerImage({ it.image.virtualImageId == 50L && it.image.containerType == 'vhd' }) >> [success: true, imageId: 'vhd-up']
        1 * imageLocationService.create(_ as VirtualImageLocation)
        rtn.success
        createOpts.imageId == 'vhd-up'
        createOpts.isTemplate == false
    }

    def "runWorkload fails with 'Failed to upload image' when the user image upload fails"() {
        given:
        image.refType = null
        image.externalId = null
        asyncVirtualImageService.getVirtualImageFiles(image) >> Single.just([])

        when:
        def rtn = provider.runWorkload(workload, request, [:])

        then: 'the empty-cloud-files message is overwritten; the upload is still attempted'
        1 * apiService.insertContainerImage(_) >> [success: false]
        0 * apiService.createServer(_)
        !rtn.success
        rtn.msg == 'Failed to upload image'
        server.statusMessage == 'Failed to upload image'
    }

    def "runWorkload surfaces createServer's errorMsg and does not start the VM"() {
        given:
        createResults = [success: false, errorMsg: errorMsg]

        when:
        def rtn = provider.runWorkload(workload, request, [:])

        then:
        0 * apiService.startServer(*_)
        0 * asyncProcessService.startProcessStep(*_)
        !rtn.success
        rtn.msg == expected
        rtn.error == expected
        rtn.data.message == expected
        server.statusMessage == expected

        where:
        errorMsg                     || expected
        'Error (13206) generation 2' || 'Error (13206) generation 2'
        null                         || 'Unknown error creating server'
    }

    def "runWorkload reports 'Failed to create server' when createServer succeeds without a server"() {
        given:
        createResults = [success: true]

        when:
        def rtn = provider.runWorkload(workload, request, [:])

        then:
        0 * apiService.startServer(*_)
        !rtn.success
        rtn.msg == 'Failed to create server'
    }

    def "runWorkload returns the exception message when createServer throws"() {
        given:
        createAnswer = { -> throw new IllegalStateException('winrm closed') }

        when:
        def rtn = provider.runWorkload(workload, request, [:])

        then:
        !rtn.success
        rtn.msg == 'winrm closed'
        rtn.data.message == 'winrm closed'
    }

    def "runWorkload stops before createServer when placement fails (#why)"() {
        given:
        placement = { args -> throw new IllegalArgumentException((String) message) }

        when:
        def rtn = provider.runWorkload(workload, request, [:])

        then:
        0 * apiService.createServer(_)
        !rtn.success
        rtn.msg == expected
        server.statusMessage == expected

        where:
        why            | message                                       || expected
        'with message' | 'No suitable datastore found with free space' || 'No suitable datastore found with free space'
        'no message'   | null                                          || 'Error in determining host and datastore'
    }

    def "runWorkload fails fast when the clone source workload cannot be found"() {
        given:
        workloadService.get(99L) >> null

        when:
        def rtn = provider.runWorkload(workload, request, [cloneContainerId: 99L])

        then:
        0 * provider.getHostAndDatastore(*_)
        0 * apiService.createServer(_)
        !rtn.success
        rtn.msg == 'Unable to locate source workload 99 for clone'
    }

    def "runWorkload clone stops a running source, clones from its VM and restarts it afterwards"() {
        given:
        def parent = cloneParent(false)
        server.platform = 'linux'

        when:
        def rtn = provider.runWorkload(workload, request, [cloneContainerId: 99L])

        then:
        1 * provider.stopWorkload(parent) >> ServiceResponse.success()
        1 * apiService.startServer({ it.async == true }, 'src-vm') >> [success: true]
        1 * workloadService.save({ it.status == Workload.Status.running })
        rtn.success
        createOpts.cloneVMId == 'src-vm'
        createOpts.cloneContainerId == 99L
        createOpts.startClonedVM == true
        createOpts.cloneBaseOpts == [cloudInitIsoNeeded: false]
        rtn.data.installAgent == true
    }

    def "runWorkload clone rebuilds the source's cloud-init ISO from the source's own stored cloud-config"() {
        given:
        def parent = cloneParent(true)
        byte[] iso = [7, 7] as byte[]
        createResults.cloneBaseResults = [cloudInitIsoPath: 'C:\\disks\\src\\config.iso']

        when:
        provider.runWorkload(workload, request, [cloneContainerId: 99L])

        then:
        1 * provider.stopWorkload(parent) >> ServiceResponse.success()
        1 * provisionService.buildCloudConfigOptions(*_) >> [:]
        1 * provisionService.buildIsoOutputStream(false, PlatformType.linux, 'src-meta', 'src-user', null) >> iso
        createOpts.cloneBaseOpts.cloudInitIsoNeeded == true
        createOpts.cloneBaseOpts.cloudConfigBytes == iso
        createOpts.cloneBaseOpts.diskFolder == 'C:\\disks\\morpheus\\morpheus_server_10'
        workload.getConfigMap().cloneParentDvdCleanup == [externalId: 'src-vm', deleteIso: 'C:\\disks\\src\\config.iso']
    }

    def "runWorkload builds the cloud-init ISO from the request for a cloud-init image"() {
        given:
        image.isCloudInit = true
        request.cloudConfigUser = 'user-data'
        request.cloudConfigMeta = 'meta-data'
        byte[] iso = [1] as byte[]

        when:
        provider.runWorkload(workload, request, [:])

        then:
        1 * provisionService.buildCloudConfigOptions(cloud, server, true, _) >> [:]
        1 * provisionService.buildIsoOutputStream(false, PlatformType.linux, 'meta-data', 'user-data', null) >> iso
        createOpts.cloudConfigUser == 'user-data'
        createOpts.cloudConfigBytes == iso
    }

    def "runWorkload maps the OS name and licence for a sysprep image"() {
        given:
        image.isSysprep = true
        image.isCloudInit = false
        request.cloudConfigOpts = [licenses: [[fullName: 'Ops', licenseKey: 'KEY-1', orgName: 'HPE']]]
        provisionService.buildCloudConfigOptions(*_) >> [:]
        provisionService.buildIsoOutputStream(*_) >> ([0] as byte[])

        when:
        provider.runWorkload(workload, request, [:])

        then:
        1 * apiService.getMapScvmmOsType('ubuntu.64', false) >> 'Ubuntu Linux (64 bit)'
        createOpts.OSName == 'Ubuntu Linux (64 bit)'
        createOpts.license == [fullName: 'Ops', productKey: 'KEY-1', orgName: 'HPE']
    }

    // ------------------------------------------------------------------ power & lifecycle

    private Object powerTarget(String method, String externalId = 'vm-ext-1') {
        server.externalId = externalId
        method.endsWith('Workload') ? workload : server
    }

    def "#method calls apiService.#apiCall with the VM id and succeeds when SCVMM reports success"() {
        when:
        def rtn = provider."$method"(powerTarget(method))

        then:
        1 * apiService."$apiCall"(_ as Map, 'vm-ext-1') >> [success: true]
        rtn.success

        where:
        method          | apiCall
        'stopWorkload'  | 'stopServer'
        'startWorkload' | 'startServer'
        'stopServer'    | 'stopServer'
        'startServer'   | 'startServer'
    }

    def "#method fails with '#expected' and makes no API call when the server has no externalId"() {
        when:
        def rtn = provider."$method"(powerTarget(method, null))

        then:
        0 * apiService.stopServer(*_)
        0 * apiService.startServer(*_)
        !rtn.success
        rtn.msg == expected

        where:
        method          || expected
        'stopWorkload'  || 'vm not found'
        'startWorkload' || 'vm not found'
        'stopServer'    || 'vm not found'
        'startServer'   || 'externalId not found'
    }

    def "#method returns #expectedMsg when the SCVMM call throws"() {
        when:
        def rtn = provider."$method"(powerTarget(method))

        then:
        1 * apiService."$apiCall"(*_) >> { throw new IllegalStateException('winrm down') }
        !rtn.success
        rtn.msg == expectedMsg

        where:
        method          | apiCall       || expectedMsg
        'stopWorkload'  | 'stopServer'  || 'winrm down'
        'startWorkload' | 'startServer' || 'winrm down'
        'stopServer'    | 'stopServer'  || 'winrm down'
        'startServer'   | 'startServer' || null
    }

    def "restartWorkload stops then starts the VM"() {
        given:
        server.externalId = 'vm-ext-1'

        when:
        def rtn = provider.restartWorkload(workload)

        then:
        1 * apiService.stopServer(_, 'vm-ext-1') >> [success: true]

        then:
        1 * apiService.startServer(_, 'vm-ext-1') >> [success: true]
        rtn.success
    }

    def "restartWorkload does not start the VM when the stop fails and returns the stop response"() {
        given:
        server.externalId = 'vm-ext-1'

        when:
        def rtn = provider.restartWorkload(workload)

        then:
        1 * apiService.stopServer(*_) >> [success: false]
        0 * apiService.startServer(*_)
        !rtn.success
    }

    def "removeWorkload deletes the VM by externalId (#result.success -> #success, msg #msg)"() {
        given:
        server.externalId = 'vm-ext-1'

        when:
        def rtn = provider.removeWorkload(workload, [:])

        then:
        1 * apiService.deleteServer(_, 'vm-ext-1') >> result
        rtn.success == success
        rtn.msg == msg

        where:
        result                                 || success | msg
        [success: true]                        || true    | null
        [success: false, error: 'VM locked']   || false   | 'Failed to remove vm'
    }

    def "removeWorkload fails with 'vm not found' and makes no API call when the server has no externalId"() {
        when:
        def rtn = provider.removeWorkload(workload, [:])

        then:
        0 * apiService.deleteServer(*_)
        !rtn.success
        rtn.msg == 'vm not found'
    }

    def "removeWorkload returns the exception message when deleteServer throws"() {
        given:
        server.externalId = 'vm-ext-1'
        apiService.deleteServer(*_) >> { throw new IllegalStateException('access denied') }

        when:
        def rtn = provider.removeWorkload(workload, [:])

        then:
        !rtn.success
        rtn.msg == 'access denied'
    }

    def "destroyInstance removes each distinct VM once and skips workloads without a VM"() {
        given:
        server.externalId = 'vm-ext-1'
        def sameVm = Domain.workload(id: 21L, server: server)
        def noVm = Domain.workload(id: 22L, server: Domain.server(id: 11L, externalId: null))
        def instance = Domain.instance(containers: [workload, sameVm, noVm])

        when:
        def rtn = provider.destroyInstance(instance, [:])

        then:
        1 * provider.removeWorkload(workload, _) >> ServiceResponse.success()
        0 * provider.removeWorkload(sameVm, _)
        0 * provider.removeWorkload(noVm, _)
        rtn.success
    }

    def "destroyInstance collects per-workload failures"() {
        given:
        server.externalId = 'vm-ext-1'
        def instance = Domain.instance(containers: [workload])

        when:
        def rtn = provider.destroyInstance(instance, [:])

        then:
        1 * provider.removeWorkload(workload, _) >> new ServiceResponse(success: false, msg: 'Failed to remove vm')
        !rtn.success
        rtn.msg == 'Failed to destroy one or more SCVMM workloads'
        rtn.errors == ['20': 'Failed to remove vm']
    }

    def "destroyInstance succeeds without calls for an instance with no workloads"() {
        expect:
        provider.destroyInstance(Domain.instance(containers: []), [:]).success
        provider.destroyInstance(null, [:]).success
    }

    // ------------------------------------------------------------------ resizeWorkloadAndServer volumes / plan

    static final long GB = 1024L * 1024L * 1024L

    private ComputeServer resizable() {
        server.externalId = 'vm-ext-1'
        server.maxMemory = 4 * GB
        server.maxCores = 2L
        server.hotResize = true
        server.cpuHotResize = true
        server.setConfigProperty('generation', 'generation2')
        provider.getMorpheusServer(_) >> server
        provider.saveAndGet(_) >> { args -> args[0] }
        return server
    }

    private ResizeRequest resizeRequest(Map props = [:]) {
        new ResizeRequest([maxMemory: 4 * GB, maxCores: 2L] + props)
    }

    def "resizeServer applies a plan change hot through updateServer and records the new size"() {
        given:
        resizable()

        when:
        def rtn = provider.resizeServer(server, resizeRequest(maxMemory: 8 * GB, maxCores: 4L), [:])

        then:
        1 * apiService.updateServer(_, 'vm-ext-1', [maxMemory: 8 * GB, maxCores: 4L, minDynamicMemory: null, maxDynamicMemory: null]) >> [success: true]
        0 * apiService.stopServer(*_)
        0 * apiService.resizeDisk(*_)
        0 * apiService.createAndAttachDisk(*_)
        rtn.success
        server.maxMemory == 8 * GB
        server.maxCores == 4L
        server.status == 'provisioned'
    }

    def "resizeWorkload also writes the new plan size onto the workload"() {
        given:
        resizable()

        when:
        def rtn = provider.resizeWorkload(workload.instance, workload, resizeRequest(maxMemory: 8 * GB, maxCores: 4L), [:])

        then:
        1 * apiService.updateServer(*_) >> [success: true]
        1 * workloadService.save({ it.maxMemory == 8 * GB && it.maxCores == 4L }) >> workload
        rtn.success
    }

    def "a failed plan update fails the resize with #expected and skips the volume changes"() {
        given:
        resizable()

        when:
        def rtn = provider.resizeServer(server, resizeRequest(maxMemory: 8 * GB, volumesAdd: [[name: 'data-1', size: 10]]), [volumes: true])

        then:
        1 * apiService.updateServer(*_) >> result
        0 * apiService.createAndAttachDisk(*_)
        !rtn.success
        rtn.msg == expected
        server.status == 'provisioned'

        where:
        result                             || expected
        [success: false, error: 'locked']  || 'locked'
        [success: false]                   || 'Failed to resize container'
    }

    def "growing a data volume resizes its VHD and saves the new size"() {
        given:
        resizable()
        def data = Domain.volume(id: 61L, name: 'data-1', externalId: 'vhd-1', rootVolume: false, maxStorage: 20 * GB)
        def update = new UpdateModel<StorageVolume>(data, [maxStorage: 50 * GB, size: 50])

        when:
        def rtn = provider.resizeServer(server, resizeRequest(volumesUpdate: [update]), [volumes: true])

        then:
        0 * apiService.updateServer(*_)
        0 * apiService.stopServer(*_)
        1 * apiService.resizeDisk(_, 'vhd-1', 50 * GB) >> [success: true]
        1 * storageVolumeService.get(61L) >> data
        1 * storageVolumeService.save({ it.maxStorage == 50 * GB })
        rtn.success
    }

    def "a shrink request is ignored without an API call or an error"() {
        given:
        resizable()
        def data = Domain.volume(id: 61L, externalId: 'vhd-1', rootVolume: false, maxStorage: 50 * GB)

        when:
        def rtn = provider.resizeServer(server, resizeRequest(volumesUpdate: [new UpdateModel<StorageVolume>(data, [maxStorage: 20 * GB, size: 20])]), [volumes: true])

        then:
        0 * apiService.resizeDisk(*_)
        rtn.success
    }

    def "adding a volume attaches a new VHD and creates the storage volume from the SCVMM disk"() {
        given:
        resizable()

        when:
        def rtn = provider.resizeServer(server, resizeRequest(volumesAdd: [[name: 'data-1', size: 10, maxStorage: 10 * GB]]), [volumes: true])

        then:
        1 * apiService.createAndAttachDisk(_, { it.sizeMb == 10240 && it.vhdName.startsWith('data-') }, true) >>
                [success: true, disk: [VhdID: 'vhd-new', VhdLocation: 'C:\\vms\\new.vhdx', HostVolumeId: 'hv-1']]
        1 * asyncStorageVolumeService.create({ List<StorageVolume> v ->
            v.size() == 1 && v[0].externalId == 'vhd-new' && v[0].internalId == 'C:\\vms\\new.vhdx' &&
                    v[0].maxStorage == 10 * GB && v[0].removable && v[0].name == 'data-1' && v[0].displayOrder == 1
        }, server) >> Single.just(true)
        rtn.success
    }

    def "a failed volume add fails the resize but the remaining adds are still attempted"() {
        given:
        resizable()

        when:
        def rtn = provider.resizeServer(server, resizeRequest(volumesAdd: [[name: 'data-1', size: 10], [name: 'data-2', size: 5]]), [volumes: true])

        then:
        1 * apiService.createAndAttachDisk(_, { it.sizeMb == 10240 }, true) >> [success: false]
        1 * apiService.createAndAttachDisk(_, { it.sizeMb == 5120 }, true) >> [success: true, disk: [VhdID: 'vhd-2']]
        1 * asyncStorageVolumeService.create(_, server) >> Single.just(true)
        !rtn.success
        rtn.msg == 'Error in creating the volume'
    }

    def "deleting a volume removes the disk and then the storage volume (#removed)"() {
        given:
        resizable()
        def data = Domain.volume(id: 62L, externalId: 'vhd-2', rootVolume: false)

        when:
        def rtn = provider.resizeServer(server, resizeRequest(volumesDelete: [data]), [volumes: true])

        then:
        1 * apiService.removeDisk(_, 'vhd-2') >> [success: removed]
        (removed ? 1 : 0) * asyncStorageVolumeService.remove([data], server, true) >> Single.just(true)
        rtn.success

        where:
        removed << [true, false]
    }

    def "volume changes are ignored unless opts.volumes is set"() {
        given:
        resizable()

        when:
        def rtn = provider.resizeServer(server, resizeRequest(volumesAdd: [[name: 'data-1', size: 10]], volumesDelete: [Domain.volume()]), [:])

        then:
        0 * apiService.createAndAttachDisk(*_)
        0 * apiService.removeDisk(*_)
        rtn.success
    }

    def "an exception during resize is reported and the server is returned to provisioned"() {
        given:
        resizable()
        apiService.updateServer(*_) >> { throw new IllegalStateException('VMM timeout') }

        when:
        def rtn = provider.resizeServer(server, resizeRequest(maxMemory: 8 * GB), [:])

        then:
        !rtn.success
        server.status == 'provisioned'
        server.statusMessage == 'Unable to resize server: VMM timeout'
    }

    private Workload cloneParent(boolean cloudInit) {
        def parentImage = Domain.image(id: 51L, isCloudInit: cloudInit)
        def parentServer = Domain.server(id: 77L, externalId: 'src-vm', sourceImage: parentImage, cloud: cloud,
                serverOs: new OsType(platform: PlatformType.linux), cloudConfigUser: 'src-user', cloudConfigMeta: 'src-meta',
                volumes: [Domain.volume(id: 62L, datastore: datastore)])
        def parent = Domain.workload(id: 99L, server: parentServer, status: Workload.Status.running)
        workloadService.get(99L) >> parent
        workloadService.find(_) >> parent
        provider.resetCloudInitCacheForClone(_) >> false
        return parent
    }
}
