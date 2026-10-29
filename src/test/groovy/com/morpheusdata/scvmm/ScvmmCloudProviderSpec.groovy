// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusHypervisorService
import com.morpheusdata.core.MorpheusOsTypeService
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.NetworkPoolServer
import com.morpheusdata.model.OsType
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.scvmm.error.ScvmmCommandException
import com.morpheusdata.scvmm.helper.NetworkPoolServerHelper
import com.morpheusdata.scvmm.support.Domain
import com.morpheusdata.scvmm.support.ScvmmSpec
import com.morpheusdata.scvmm.sync.CloudCapabilityProfilesSync
import com.morpheusdata.scvmm.sync.ClustersSync
import com.morpheusdata.scvmm.sync.DatastoresSync
import com.morpheusdata.scvmm.sync.HostSync
import com.morpheusdata.scvmm.sync.IpPoolsSync
import com.morpheusdata.scvmm.sync.IsolationNetworkSync
import com.morpheusdata.scvmm.sync.NetworkSync
import com.morpheusdata.scvmm.sync.RegisteredStorageFileSharesSync
import com.morpheusdata.scvmm.sync.TemplatesSync
import com.morpheusdata.scvmm.sync.VirtualMachineSync
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import spock.lang.Subject

/**
 * ScvmmCloudProvider lifecycle: refresh / validate / initializeCloud / getScvmmController.
 * <p>
 * {@code refresh} constructs every sync class inline and calls the static {@code ConnectionUtils.testHostConnectivity};
 * the refresh features therefore use <b>global</b> GroovyMocks for the ten sync classes, {@code ConnectionUtils} and
 * {@code NetworkPoolServerHelper} (only in this spec; forkEvery=1 keeps them from leaking). The provider itself is
 * real; only {@code apiService} is replaced with a Mock.
 */
class ScvmmCloudProviderSpec extends ScvmmSpec {

    ScvmmPlugin plugin = Mock()
    ScvmmApiService apiService = Mock()
    MorpheusOsTypeService asyncOsTypeService = Mock()
    MorpheusHypervisorService hypervisorService = Mock()

    @Subject
    ScvmmCloudProvider provider

    Account account = new Account(id: 7L)
    Cloud cloud = Domain.cloud(account: account, enabled: true,
            config: [host: 'scvmm.local', workingPath: 'C:\\work', diskPath: 'C:\\disks', libraryShare: '\\\\lib\\share'])
    ComputeServer controller = Domain.controller(status: 'provisioned', powerState: 'on')
    Map scvmmOpts = [sshHost: 'scvmm.local']

    def setup() {
        asyncServices.getOsType() >> asyncOsTypeService
        asyncServices.getHypervisorService() >> hypervisorService
        provider = new ScvmmCloudProvider(plugin, context)
        provider.apiService = apiService
    }

    def "SCVMM advertises costing support for embedded parity"() {
        expect:
        (provider as CloudProvider).hasCosting() == true
    }

    // ---------------------------------------------------------------- refresh

    /** Global mocks intercept {@code new X(..)}; each constructor is stubbed to hand back the global mock handle. */
    private Map globalSyncMocks() {
        def m = [
            network     : GroovyMock(NetworkSync, global: true),
            clusters    : GroovyMock(ClustersSync, global: true),
            isolation   : GroovyMock(IsolationNetworkSync, global: true),
            host        : GroovyMock(HostSync, global: true),
            datastores  : GroovyMock(DatastoresSync, global: true),
            fileShares  : GroovyMock(RegisteredStorageFileSharesSync, global: true),
            capabilities: GroovyMock(CloudCapabilityProfilesSync, global: true),
            templates   : GroovyMock(TemplatesSync, global: true),
            ipPools     : GroovyMock(IpPoolsSync, global: true),
            vms         : GroovyMock(VirtualMachineSync, global: true),
        ]
        interaction {
            new NetworkSync(*_) >> m.network
            new ClustersSync(*_) >> m.clusters
            new IsolationNetworkSync(*_) >> m.isolation
            new HostSync(*_) >> m.host
            new DatastoresSync(*_) >> m.datastores
            new RegisteredStorageFileSharesSync(*_) >> m.fileShares
            new CloudCapabilityProfilesSync(*_) >> m.capabilities
            new TemplatesSync(*_) >> m.templates
            new VirtualMachineSync(*_) >> m.vms
        }
        return m
    }

    private void controllerFound() {
        computeServerService.find(_ as DataQuery) >> controller
        apiService.getScvmmZoneAndHypervisorOpts(context, cloud, controller) >> scvmmOpts
    }

    def "refresh runs every sync in the fixed order and marks the cloud syncing then ok when the controller answers"() {
        given:
        cloud.setConfigProperty('importExisting', 'on')
        controllerFound()
        GroovySpy(ConnectionUtils, global: true)
        ConnectionUtils.testHostConnectivity('scvmm.local', 5985, false, true, null) >> true
        apiService.listAllNetworks(scvmmOpts) >> [success: true, networks: [[ID: 'vmnet-1']]]
        def helper = GroovyMock(NetworkPoolServerHelper, global: true)
        def poolServer = new NetworkPoolServer(id: 5L)
        def syncs = globalSyncMocks()

        when:
        def response = provider.refresh(cloud)

        then:
        1 * asyncCloudService.updateCloudStatus(cloud, Cloud.Status.syncing, null, _ as Date)
        then:
        1 * syncs.network.execute()
        then:
        1 * syncs.clusters.execute()
        then:
        1 * syncs.isolation.execute()
        then:
        1 * syncs.host.execute()
        then:
        1 * syncs.datastores.execute()
        then:
        1 * syncs.fileShares.execute()
        then:
        1 * syncs.capabilities.execute()
        then:
        1 * syncs.templates.execute()
        then:
        1 * new NetworkPoolServerHelper(context) >> helper
        1 * helper.ensurePoolServer(cloud) >> poolServer
        then:
        1 * new IpPoolsSync(context, cloud, poolServer) >> syncs.ipPools
        1 * syncs.ipPools.execute()
        then:
        1 * syncs.vms.execute(true)
        then:
        1 * asyncCloudService.updateCloudStatus(cloud, Cloud.Status.ok, null, _ as Date)
        response.success
    }

    def "refresh passes createNew=#createNew to VirtualMachineSync for importExisting=#importExisting"() {
        given:
        cloud.setConfigProperty('importExisting', importExisting)
        controllerFound()
        GroovySpy(ConnectionUtils, global: true)
        ConnectionUtils.testHostConnectivity(*_) >> true
        apiService.listAllNetworks(_) >> [success: true, networks: [[ID: 'vmnet-1']]]
        GroovyMock(NetworkPoolServerHelper, global: true)
        def syncs = globalSyncMocks()
        new IpPoolsSync(*_) >> syncs.ipPools

        when:
        provider.refresh(cloud)

        then:
        1 * syncs.vms.execute(createNew)

        where:
        importExisting || createNew
        'on'           || true
        'true'         || true
        true           || true
        'off'          || false
        null           || false
    }

    def "refresh reports controller not found and runs no sync when the cloud has no controller"() {
        given:
        computeServerService.find(_ as DataQuery) >> null
        def syncs = globalSyncMocks()

        when:
        def response = provider.refresh(cloud)

        then:
        1 * asyncCloudService.updateCloudStatus(cloud, Cloud.Status.error, 'SCVMM controller server not found for this cloud', _ as Date)
        0 * syncs.network.execute()
        0 * syncs.vms.execute(_)
        !response.success
        response.msg == 'SCVMM controller server not found for this cloud'
    }

    def "refresh marks the cloud and controller in error with the cause when checkCommunication fails"() {
        given:
        controllerFound()
        GroovySpy(ConnectionUtils, global: true)
        ConnectionUtils.testHostConnectivity(*_) >> true
        apiService.listAllNetworks(scvmmOpts) >> [success: false, msg: 'SCVMM returned no logical networks (Get-SCLogicalNetwork)']
        def syncs = globalSyncMocks()
        def msg = 'Error connecting to SCVMM controller: SCVMM returned no logical networks (Get-SCLogicalNetwork)'

        when:
        def response = provider.refresh(cloud)

        then:
        1 * asyncCloudService.updateCloudStatus(cloud, Cloud.Status.error, msg, _ as Date)
        1 * computeServerService.save(controller)
        controller.status == 'error'
        controller.powerState == ComputeServer.PowerState.unknown
        controller.statusMessage == msg
        0 * syncs.network.execute()
        !response.success
        response.msg == msg
    }

    def "refresh treats a reachable controller with an empty network inventory (#listResult) as connected and syncs"() {
        // an empty inventory is not a connection failure
        given:
        controllerFound()
        GroovySpy(ConnectionUtils, global: true)
        ConnectionUtils.testHostConnectivity(*_) >> true
        apiService.listAllNetworks(scvmmOpts) >> listResult
        GroovyMock(NetworkPoolServerHelper, global: true)
        def syncs = globalSyncMocks()
        new IpPoolsSync(*_) >> syncs.ipPools

        when:
        def response = provider.refresh(cloud)

        then:
        1 * syncs.network.execute()
        1 * syncs.vms.execute(false)
        1 * asyncCloudService.updateCloudStatus(cloud, Cloud.Status.ok, null, _ as Date)
        0 * asyncCloudService.updateCloudStatus(cloud, Cloud.Status.error, _, _)
        response.success

        where:
        listResult << [[success: true, networks: []], [success: true, networks: null]]
    }

    def "refresh marks the cloud offline without syncing when the WinRM port is unreachable"() {
        given:
        controllerFound()
        GroovySpy(ConnectionUtils, global: true)
        ConnectionUtils.testHostConnectivity(*_) >> false
        def syncs = globalSyncMocks()
        def msg = 'SCVMM host scvmm.local is not reachable on port 5985 (WinRM)'

        when:
        def response = provider.refresh(cloud)

        then:
        1 * asyncCloudService.updateCloudStatus(cloud, Cloud.Status.offline, msg, _ as Date)
        0 * apiService.listAllNetworks(_)
        0 * syncs.network.execute()
        !response.success
        response.msg == msg
        response.data.status == Cloud.Status.offline
        controller.statusMessage == msg
    }

    def "refresh isolates a sync that throws, runs the remaining syncs and reports a warning"() {
        given:
        controllerFound()
        GroovySpy(ConnectionUtils, global: true)
        ConnectionUtils.testHostConnectivity(*_) >> true
        apiService.listAllNetworks(_) >> [success: true, networks: [[ID: 'vmnet-1']]]
        GroovyMock(NetworkPoolServerHelper, global: true)
        def syncs = globalSyncMocks()
        new IpPoolsSync(*_) >> syncs.ipPools

        when:
        def response = provider.refresh(cloud)

        then: 'the exception is contained to its worker'
        noExceptionThrown()
        1 * syncs.network.execute()
        1 * syncs.clusters.execute() >> { throw new IllegalStateException('boom') }
        1 * syncs.isolation.execute()
        1 * syncs.ipPools.execute()
        1 * syncs.vms.execute(_)
        1 * asyncCloudService.updateCloudStatus(cloud, Cloud.Status.syncing, null, _ as Date)
        1 * asyncCloudService.updateCloudStatus(cloud, Cloud.Status.warning, { it.contains('1 of 10') && it.contains('ClustersSync: boom') }, _ as Date)
        0 * asyncCloudService.updateCloudStatus(cloud, Cloud.Status.ok, _, _)
        0 * asyncCloudService.updateCloudStatus(cloud, Cloud.Status.error, _, _)
        !response.success
        response.data.status == Cloud.Status.warning
        response.data.workers.find { it.name == 'ClustersSync' }.error == 'boom'
    }

    def "updateHypervisorStatus re-saves an already provisioned controller on every successful refresh"() {
        // Discrepancy: the guard compares the PowerState enum with the String 'on', which is never equal,
        // so the controller is saved (and statusDate bumped) on every refresh.
        given:
        controllerFound()
        controller.powerState = ComputeServer.PowerState.on
        GroovySpy(ConnectionUtils, global: true)
        ConnectionUtils.testHostConnectivity(*_) >> true
        apiService.listAllNetworks(_) >> [success: true, networks: [[ID: 'vmnet-1']]]
        GroovyMock(NetworkPoolServerHelper, global: true)
        def syncs = globalSyncMocks()
        new IpPoolsSync(*_) >> syncs.ipPools

        when:
        provider.refresh(cloud)

        then:
        1 * computeServerService.save(controller)
        controller.status == 'provisioned'
        controller.powerState == ComputeServer.PowerState.on
    }

    // ---------------------------------------------------------------- getScvmmController

    def "getScvmmController returns the shared controller of another cloud when configured"() {
        given:
        cloud.setConfigProperty('sharedController', '99')
        def shared = Domain.controller(id: 99L)

        when:
        def result = provider.getScvmmController(cloud)

        then:
        1 * computeServerService.get(99L) >> shared
        0 * computeServerService.find(_)
        result.is(shared)
    }

    def "getScvmmController returns the cloud's scvmmController without re-typing it"() {
        when:
        def result = provider.getScvmmController(cloud)

        then:
        1 * computeServerService.find(_ as DataQuery) >> controller
        0 * asyncComputeServerService.save(_)
        result.is(controller)
    }

    def "getScvmmController falls back to the cloud's hypervisor and re-types it as scvmmController"() {
        given:
        def legacy = Domain.controller(id: 3L)
        def type = new ComputeServerType(code: 'scvmmController')
        List<DataQuery> queries = []

        when:
        def result = provider.getScvmmController(cloud)

        then:
        3 * computeServerService.find(_ as DataQuery) >> { DataQuery q -> queries << q; queries.size() < 3 ? null : legacy }
        1 * asyncCloudService.findComputeServerTypeByCode('scvmmController') >> Maybe.just(type)
        1 * asyncComputeServerService.save(legacy) >> Single.just(legacy)
        result.is(legacy)
        result.computeServerType.is(type)
        queries[2].filters.find { it.name == 'serverType' }?.value == 'hypervisor'
    }

    def "getScvmmController returns null when nothing matches"() {
        given:
        computeServerService.find(_ as DataQuery) >> null

        expect:
        provider.getScvmmController(cloud) == null
    }

    // ---------------------------------------------------------------- validate

    private ValidateCloudRequest creds(String user = 'admin', String pass = 'secret') {
        new ValidateCloudRequest(user, pass, 'local', [:])
    }

    def "validate succeeds when every field is set, no other controller owns the host and listClouds answers"() {
        given:
        apiService.getScvmmZoneOpts(context, cloud) >> [zone: cloud]
        computeServerService.find(_ as DataQuery) >> null

        when:
        def response = provider.validate(cloud, creds())

        then:
        1 * apiService.listClouds({ it.sshHost == 'scvmm.local' && it.sshUsername == 'admin' && it.sshPassword == 'secret' &&
                it.zoneRoot == 'C:\\work' && it.diskRoot == 'C:\\disks' }) >> [success: true, clouds: [[ID: 'c1', Name: 'Cloud 1']]]
        response.success
        response.errors.isEmpty()
        cloud.getConfigProperty('installAgent') == false
    }

    def "validate reports that SCVMM returned no clouds when listClouds answers #listResult"() {
        given:
        apiService.getScvmmZoneOpts(context, cloud) >> [:]
        computeServerService.find(_ as DataQuery) >> null
        apiService.listClouds(_) >> listResult

        when:
        def response = provider.validate(cloud, creds())

        then:
        !response.success
        response.msg == 'SCVMM did not return any clouds (Get-SCCloud). Verify the VMM console is installed on the host and the account is a VMM administrator.'

        where:
        listResult << [[success: false], [success: true, clouds: []], [success: true]]
    }

    def "validate reports the translated cause when listClouds throws"() {
        given:
        apiService.getScvmmZoneOpts(context, cloud) >> [:]
        computeServerService.find(_ as DataQuery) >> null
        apiService.listClouds(_) >> { throw ScvmmCommandException.fromOutput('Get-SCCloud', 'Get-SCCloud : You cannot access VMM management server localhost', '1') }

        when:
        def response = provider.validate(cloud, creds())

        then:
        !response.success
        response.msg == 'The SCVMM host cannot reach the VMM management server (localhost). Verify the VMM service is running on the host.'
        response.data.errorType == 'ScvmmCommandException'
    }

    def "validate reports a missing #field before connecting"() {
        given:
        apiService.getScvmmZoneOpts(context, cloud) >> [:]
        computeServerService.find(_ as DataQuery) >> null

        when:
        def response = provider.validate(cloud, request)

        then:
        0 * apiService.listClouds(_)
        !response.success
        response.msg == message
        response.errors[field] == message

        where:
        field      | request                                      || message
        'username' | new ValidateCloudRequest(null, 'p', 'local', [:]) || 'Enter a username'
        'password' | new ValidateCloudRequest('u', '', 'local', [:])   || 'Enter a password'
        'username' | null                                         || 'Enter a username'
    }

    def "validate flags a blank #field config value"() {
        given:
        cloud.setConfigProperty(field, '')
        apiService.getScvmmZoneOpts(context, cloud) >> [:]
        computeServerService.find(_ as DataQuery) >> null

        when:
        def response = provider.validate(cloud, creds())

        then:
        0 * apiService.listClouds(_)
        response.errors[field] == message

        where:
        field          || message
        'host'         || 'Enter a host'
        'workingPath'  || 'Enter a working path'
        'diskPath'     || 'Enter a disk path'
        'libraryShare' || 'Enter a library share'
    }

    def "validate does not flag a required config field that is absent altogether"() {
        given: 'only empty strings are rejected, absent fields pass'
        def bare = Domain.cloud(account: account, config: [host: 'scvmm.local'])
        apiService.getScvmmZoneOpts(context, bare) >> [:]
        computeServerService.find(_ as DataQuery) >> null
        apiService.listClouds(_) >> [success: true, clouds: [[ID: 'c1', Name: 'Cloud 1']]]

        when:
        def response = provider.validate(bare, creds())

        then:
        response.success
        response.errors.isEmpty()
    }

    def "validate requires a shared controller when another cloud's controller already manages the host"() {
        given:
        def unsaved = Domain.cloud(id: null, account: account, config: [host: 'scvmm.local'])
        apiService.getScvmmZoneOpts(context, unsaved) >> [:]
        computeServerService.find(_ as DataQuery) >> Domain.controller(id: 44L)

        when:
        def response = provider.validate(unsaved, creds())

        then:
        0 * apiService.listClouds(_)
        !response.success
        response.errors.sharedController == 'You must specify a shared controller'
    }

    def "validateSharedController accepts a cloud that already owns its controller"() {
        when:
        def result = provider.validateSharedController(cloud)

        then:
        1 * computeServerService.find(_ as DataQuery) >> controller
        result.success
    }

    def "validateSharedController rejects a shared controller on a different host"() {
        given:
        cloud.setConfigProperty('sharedController', '99')
        computeServerService.get(99L) >> Domain.controller(id: 99L, sshHost: sshHost, externalIp: externalIp)

        expect:
        provider.validateSharedController(cloud) == expected

        where:
        sshHost       | externalIp    || expected
        'other.local' | '10.0.0.9'    || [success: false, msg: 'The selected controller is on a different host than specified for this cloud']
        'scvmm.local' | '10.0.0.9'    || [success: true]
        'other.local' | 'scvmm.local' || [success: true]
    }

    def "validate reports 'No zone found' for a null cloud"() {
        when:
        def response = provider.validate(null, creds())

        then:
        !response.success
        response.msg == 'No zone found'
        0 * apiService._
    }

    def "validate swallows an exception from the API service and returns success=false"() {
        given:
        apiService.getScvmmZoneOpts(context, cloud) >> { throw new RuntimeException('keypair lookup failed') }

        when:
        def response = provider.validate(cloud, creds())

        then:
        noExceptionThrown()
        !response.success
    }

    // ---------------------------------------------------------------- initializeCloud / initializeHypervisor

    private void serverInfo(Map info) {
        apiService.getScvmmInitializationOpts(cloud) >> [sshHost: 'scvmm.local']
        apiService.getScvmmServerInfo(_) >> ([success: true] + info)
        apiService.extractWindowsServerVersion(_ as String) >> { String os -> new ScvmmApiService(context).extractWindowsServerVersion(os) }
    }

    def "initializeHypervisor creates the controller with the osType derived from the OS name when none exists"() {
        given:
        serverInfo(hostname: 'SCVMM01', osName: 'Microsoft Windows Server 2022 Datacenter', memory: '17179869184', disks: '107374182400')
        def type = new ComputeServerType(code: 'scvmmController')
        def os = new OsType(code: 'windows.server.2022.dc.core')
        ComputeServer created

        when:
        def result = provider.initializeHypervisor(cloud)

        then:
        1 * computeServerService.find(_ as DataQuery) >> null
        1 * asyncCloudService.findComputeServerTypeByCode('scvmmController') >> Maybe.just(type)
        1 * computeServerService.create(_ as ComputeServer) >> { ComputeServer s -> created = s; s }
        1 * asyncOsTypeService.find({ DataQuery q -> q.filters[0].value == 'windows.server.2022.dc.core' }) >> Maybe.just(os)
        1 * computeServerService.save({ it.is(created) })
        1 * hypervisorService.initialize({ it.is(created) }) >> Single.just(true)
        result.success
        created.name == 'SCVMM01'
        created.computeServerType.is(type)
        created.serverOs.is(os)
        created.sshHost == 'scvmm.local'
        created.getConfigProperty('workingPath') == 'C:\\work'
        created.platformVersion == '2022'
        created.maxMemory == 17179869184L
        created.maxStorage == 107374182400L
        created.serverType == 'hypervisor'
        created.status == 'provisioning'
    }

    def "initializeHypervisor reuses an existing controller and falls back to the generic windows.server.<year> osType"() {
        given:
        serverInfo(hostname: 'SCVMM01', osName: 'Microsoft Windows Server 2019 Standard', memory: 'n/a', disks: '')
        def existing = Domain.controller(id: 3L)
        def generic = new OsType(code: 'windows.server.2019')

        when:
        def result = provider.initializeHypervisor(cloud)

        then:
        1 * computeServerService.find(_ as DataQuery) >> existing
        0 * computeServerService.create(_)
        1 * asyncOsTypeService.find({ DataQuery q -> q.filters[0].value == 'windows.server.2019' }) >> Maybe.empty()
        then: 'tokens[2] of "windows.server.2019" is the year, so the fallback looks up the same code again'
        1 * asyncOsTypeService.find({ DataQuery q -> q.filters[0].value == 'windows.server.2019' }) >> Maybe.just(generic)
        1 * computeServerService.save(existing)
        1 * hypervisorService.initialize(existing) >> Single.just(true)
        result.success
        existing.hostname == 'SCVMM01'
        existing.serverOs.is(generic)
        existing.maxMemory == 0L
        existing.maxStorage == 0L
    }

    def "initializeHypervisor skips controller creation for a cloud sharing another cloud's controller"() {
        given:
        cloud.setConfigProperty('sharedController', '99')

        when:
        def result = provider.initializeHypervisor(cloud)

        then:
        result == [success: true]
        0 * apiService.getScvmmServerInfo(_)
        0 * computeServerService._
    }

    def "initializeCloud refreshes after a successful hypervisor initialisation"() {
        given:
        ScvmmCloudProvider spy = Spy(provider)

        when:
        def response = spy.initializeCloud(cloud)

        then:
        1 * spy.initializeHypervisor(cloud) >> [success: true]
        1 * spy.refresh(cloud) >> null
        response.success
    }

    def "initializeCloud does not refresh when hypervisor initialisation fails but still reports success"() {
        given:
        ScvmmCloudProvider spy = Spy(provider)

        when:
        def response = spy.initializeCloud(cloud)

        then:
        1 * spy.initializeHypervisor(cloud) >> [success: false]
        0 * spy.refresh(_)
        response.success
    }

    def "initializeCloud does nothing for a disabled cloud"() {
        given:
        cloud.enabled = false
        ScvmmCloudProvider spy = Spy(provider)

        when:
        def response = spy.initializeCloud(cloud)

        then:
        0 * spy.initializeHypervisor(_)
        !response.success
    }

    def "initializeCloud swallows an initialisation exception and returns success=false"() {
        given:
        ScvmmCloudProvider spy = Spy(provider)

        when:
        def response = spy.initializeCloud(cloud)

        then:
        1 * spy.initializeHypervisor(cloud) >> { throw new NullPointerException('no hostname') }
        noExceptionThrown()
        !response.success
    }

    // ---------------------------------------------------------------- refreshDaily / deleteServer

    def "refreshDaily removes orphaned library items only when the controller answers"() {
        given:
        controllerFound()
        GroovySpy(ConnectionUtils, global: true)
        ConnectionUtils.testHostConnectivity(*_) >> true
        apiService.listAllNetworks(_) >> listResult

        when:
        provider.refreshDaily(cloud)

        then:
        calls * apiService.removeOrphanedResourceLibraryItems(scvmmOpts)

        where:
        listResult                                    || calls
        [success: true, networks: [[ID: 'vmnet-1']]] || 1
        [success: false]                             || 0
    }
}
