package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.OsType
import com.morpheusdata.scvmm.support.VmmPayloads
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.MorpheusSynchronousOsTypeService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.scvmm.ScvmmApiService
import io.reactivex.rxjava3.core.Observable

class HostSyncSpec extends Specification {

	MorpheusContext context = Mock()
	MorpheusAsyncServices asyncServices = Mock()
	MorpheusComputeServerService computeServerService = Mock()
	MorpheusCloudService cloudService = Mock()
	HostSync hostSync

	def setup() {
		context.async >> asyncServices
		asyncServices.computeServer >> computeServerService
		asyncServices.cloud >> cloudService
		hostSync = new HostSync(new Cloud(id: 1L), null, context)
	}

	def "addMissingHosts sets maxSockets from the physical CPU count"() {
		given:
		HostSync sync = Spy(hostSync) {
			getHypervisorOs(_) >> new OsType(code: 'windows')
		}
		ComputeServerType serverType = new ComputeServerType(id: 10L, code: 'scvmmHypervisor')
		cloudService.findComputeServerTypeByCode('scvmmHypervisor') >> Maybe.just(serverType)
		ComputeServer createdServer
		Map cloudItem = VmmPayloads.host(
			computerName: 'scvmm-host',
			name: 'scvmm-host.example.test',
			cpuCount: '4',
			coresPerCpu: '8'
		)
		def addMissingHosts = HostSync.declaredMethods.find { it.name == 'addMissingHosts' }
		addMissingHosts.accessible = true

		when:
		addMissingHosts.invoke(sync, [cloudItem], [])

		then:
		1 * computeServerService.create(_ as ComputeServer) >> { ComputeServer server ->
			createdServer = server
			Single.just(server)
		}
		1 * computeServerService.save(_ as ComputeServer) >> { ComputeServer server -> Single.just(server) }
		createdServer.maxSockets == 4L
		createdServer.maxCpu == 4L
		createdServer.maxCores == 32L
	}

	def "updateMatchedHosts updates maxSockets when metadata is unchanged"() {
		given:
		HostSync sync = Spy(hostSync) {
			getHypervisorOs(_) >> new OsType(code: 'windows')
		}
		ComputeServer server = serverWithCapacity(maxSockets: null, serverOs: new OsType(code: 'windows'))
		def updateMatchedHosts = HostSync.declaredMethods.find { it.name == 'updateMatchedHosts' }
		updateMatchedHosts.accessible = true
		def updateItem = new SyncTask.UpdateItem(existingItem: server, masterItem: VmmPayloads.host(cpuCount: '2'))

		when:
		updateMatchedHosts.invoke(sync, [updateItem], [])

		then:
		server.maxSockets == 2L
		1 * computeServerService.save(server) >> Single.just(server)
	}

	def "updateHostStats does not rewrite maxSockets when the socket count is unchanged"() {
		given:
		ComputeServer server = Spy(serverWithCapacity(maxSockets: 2L))

		when:
		hostSync.updateHostStats(server, VmmPayloads.host(cpuCount: '2'))

		then:
		server.maxSockets == 2L
		0 * server.setMaxSockets(_)
		1 * computeServerService.save(server) >> Single.just(server)
	}

	def "host persistence failures propagate out of the sync callback"() {
		given:
		ComputeServer server = serverWithCapacity(maxSockets: 2L)
		computeServerService.save(server) >> Single.error(new IllegalStateException('host save failed'))

		when:
		hostSync.updateHostStats(server, hostStats(cpuCount: '2'))

		then:
		def error = thrown(IllegalStateException)
		error.message == 'host save failed'
	}

	def "SyncTask propagates callback failures to the worker boundary"() {
		given:
		def task = new SyncTask(io.reactivex.rxjava3.core.Observable.empty(), [[id: 'host1']])
		task.addMatchFunction { a, b -> false }.onAdd {
			throw new IllegalStateException('host callback failed')
		}

		when:
		task.start()

		then:
		def error = thrown(Exception)
		com.morpheusdata.scvmm.error.ScvmmErrorTranslator.rootCause(error).message == 'host callback failed'
	}

	private static ComputeServer serverWithCapacity(Map overrides) {
		Map values = [
			maxCpu     : 2L,
			maxCores   : 8L,
			maxMemory  : 0L,
			maxStorage : 0L,
			usedMemory : 0L,
			usedStorage: 0L,
			powerState : ComputeServer.PowerState.unknown,
			uniqueId   : 'host-id',
			capacityInfo: new ComputeCapacityInfo(
				maxMemory: 0L,
				maxStorage: 0L,
				usedMemory: 0L,
				usedStorage: 0L
			)
		] + overrides
		new ComputeServer(values)
	}

	// ---------------------------------------------------------------- execute / remove / stats / JD-6

	MorpheusServices services = Mock()
	MorpheusSynchronousCloudService syncCloudService = Mock()
	MorpheusSynchronousCloudPoolService poolService = Mock()
	MorpheusSynchronousComputeServerService syncComputeServerService = Mock()
	MorpheusSynchronousOsTypeService osTypeService = Mock()
	ScvmmApiService apiService = Mock()

	private HostSync wired(Cloud cloud = new Cloud(id: 1L)) {
		context.services >> services
		services.cloud >> syncCloudService
		syncCloudService.pool >> poolService
		services.computeServer >> syncComputeServerService
		services.osType >> osTypeService
		apiService.isHostInHostGroup(_, _) >> { String a, String b -> new ScvmmApiService(context).isHostInHostGroup(a, b) }
		apiService.extractWindowsServerVersion(_ as String) >> { String os -> new ScvmmApiService(context).extractWindowsServerVersion(os) }
		def sync = new HostSync(cloud, null, context)
		sync.@apiService = apiService
		sync
	}

	def "execute adds hosts inside the cloud's host group, skips others and removes vanished hosts"() {
		given:
		def cloud = new Cloud(id: 1L)
		cloud.setConfigProperty('hostGroup', 'All Hosts\\a')
		def sync = wired(cloud)
		apiService.listHosts(_) >> [success: true, hosts: [
			VmmPayloads.host(id: 'h-in', computerName: 'in', hostGroup: 'All Hosts\\a\\rack1'),
			VmmPayloads.host(id: 'h-out', computerName: 'out', hostGroup: 'All Hosts\\b')]]
		poolService.list(_ as DataQuery) >> []
		def gone = new ComputeServerIdentityProjection(id: 9L, externalId: 'h-gone')
		computeServerService.listIdentityProjections(_ as DataQuery) >> Observable.just(gone)
		cloudService.findComputeServerTypeByCode('scvmmHypervisor') >> Maybe.just(new ComputeServerType(code: 'scvmmHypervisor'))
		syncComputeServerService.list(_ as DataQuery) >> []
		List<ComputeServer> created = []

		when:
		sync.execute()

		then:
		1 * computeServerService.create(_ as ComputeServer) >> { ComputeServer s -> created << s; Single.just(s) }
		1 * computeServerService.bulkRemove([gone]) >> Single.just(true)
		created*.externalId == ['h-in']
		created[0].category == 'scvmm.host.1'
	}

	def "execute only syncs hosts of a synced cluster when the cloud is scoped to a cluster"() {
		given:
		def cloud = new Cloud(id: 1L)
		cloud.setConfigProperty('cluster', 'cluster-1')
		def sync = wired(cloud)
		apiService.listHosts(_) >> [success: true, hosts: [VmmPayloads.host(id: 'h1', cluster: 'c1'), VmmPayloads.host(id: 'h2', cluster: 'other')]]
		poolService.list(_ as DataQuery) >> [new CloudPool(id: 5L, internalId: 'c1')]
		computeServerService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
		cloudService.findComputeServerTypeByCode(_) >> Maybe.just(new ComputeServerType())
		List<ComputeServer> created = []

		when:
		sync.execute()

		then:
		1 * computeServerService.create(_ as ComputeServer) >> { ComputeServer s -> created << s; Single.just(s) }
		created*.externalId == ['h1']
		created[0].resourcePool.id == 5L
	}

	def "execute does nothing when listHosts #why"() {
		given:
		def sync = wired()
		apiService.listHosts(_) >> result

		when:
		sync.execute()

		then:
		0 * computeServerService._

		where:
		why        | result
		'fails'    | [success: false, hosts: []]
		'is empty' | [success: true, hosts: []]
	}

	def "removeMissingHosts detaches child servers before removing the hosts"() {
		given:
		def sync = wired()
		def child = new ComputeServer(id: 20L, parentServer: new ComputeServer(id: 9L))
		def gone = new ComputeServerIdentityProjection(id: 9L)

		when:
		sync.removeMissingHosts([gone])

		then:
		1 * syncComputeServerService.list(_ as DataQuery) >> [child]
		1 * computeServerService.bulkSave([child]) >> Single.just(true)
		1 * computeServerService.bulkRemove([gone]) >> Single.just(true)
		child.parentServer == null
	}

	def "getHypervisorOs returns the known OsType or a transient one for '#name'"() {
		given:
		def sync = wired()
		osTypeService.find(_ as DataQuery) >> found

		expect:
		sync.getHypervisorOs(name).code == code

		where:
		name                                       | found                                    || code
		'Microsoft Windows Server 2019 Datacenter' | new OsType(code: 'windows.server.2019')  || 'windows.server.2019'
		'Microsoft Windows Server 2016 Standard'   | null                                     || 'windows.server.2016'
		null                                       | null                                     || 'windows.server.2012'
	}

	def "updateMatchedHosts saves and refreshes stats when the host moved cluster"() {
		given:
		def sync = Spy(wired()) { getHypervisorOs(_) >> new OsType(code: 'windows') }
		def server = serverWithCapacity(maxSockets: 2L, serverOs: new OsType(code: 'windows'))
		def cluster = new CloudPool(id: 5L, internalId: 'c1')
		def updateMatchedHosts = HostSync.declaredMethods.find { it.name == 'updateMatchedHosts' }
		updateMatchedHosts.accessible = true

		when:
		updateMatchedHosts.invoke(sync, [new SyncTask.UpdateItem(existingItem: server, masterItem: VmmPayloads.host(cpuCount: '2', cluster: 'c1'))], [cluster])

		then:
		2 * computeServerService.save(server) >> Single.just(server)
		server.resourcePool.is(cluster)
	}

	def "updateMatchedHosts does not save a host whose cluster, OS and sockets are unchanged"() {
		given:
		def sync = Spy(wired()) { getHypervisorOs(_) >> new OsType(code: 'windows') }
		def server = serverWithCapacity(maxSockets: 2L, serverOs: new OsType(code: 'windows'))
		def updateMatchedHosts = HostSync.declaredMethods.find { it.name == 'updateMatchedHosts' }
		updateMatchedHosts.accessible = true

		when:
		updateMatchedHosts.invoke(sync, [new SyncTask.UpdateItem(existingItem: server, masterItem: VmmPayloads.host(cpuCount: '2', totalMemory: '999'))], [])

		then: 'stats (e.g. memory) are only refreshed on a cluster/OS change'
		0 * computeServerService.save(_)
		server.maxMemory == 0L
	}

	def "updateHostStats maps hyperVState #state to powerState #power"() {
		given:
		def server = serverWithCapacity([:])

		when:
		hostSync.updateHostStats(server, VmmPayloads.host(hyperVState: state, totalMemory: '8589934592', availableMemory: '1024'))

		then:
		1 * computeServerService.save(server) >> Single.just(server)
		server.powerState == power
		server.maxMemory == 8589934592L
		server.usedMemory == 8589934592L - 1024L * 1024L * 1024L

		where:
		state     || power
		'Running' || ComputeServer.PowerState.on
		'Stopped' || ComputeServer.PowerState.off
		'Paused'  || ComputeServer.PowerState.unknown
	}

	def "updateHostStats never lowers maxMemory"() {
		given:
		def server = serverWithCapacity(maxMemory: 16L)

		when:
		hostSync.updateHostStats(server, VmmPayloads.host(totalMemory: '8'))

		then:
		server.maxMemory == 16L
	}
}
