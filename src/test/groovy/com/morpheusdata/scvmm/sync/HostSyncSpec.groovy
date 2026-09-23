package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.OsType
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

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
		Map cloudItem = new JsonHostMap(hostStats(
			computerName: 'scvmm-host',
			name: 'scvmm-host.example.test',
			cpuCount: '4',
			coresPerCpu: '8'
		))
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

	def "updateHostStats sets maxSockets when the socket count was missing"() {
		given:
		ComputeServer server = serverWithCapacity(maxSockets: null)

		when:
		hostSync.updateHostStats(server, hostStats(cpuCount: '2'))

		then:
		server.maxSockets == 2L
		1 * computeServerService.save(server) >> Single.just(server)
	}

	def "updateHostStats updates maxSockets when the socket count changes"() {
		given:
		ComputeServer server = serverWithCapacity(maxSockets: 1L)

		when:
		hostSync.updateHostStats(server, hostStats(cpuCount: '2'))

		then:
		server.maxSockets == 2L
		1 * computeServerService.save(server) >> Single.just(server)
	}

	def "updateHostStats does not rewrite maxSockets when the socket count is unchanged"() {
		given:
		ComputeServer server = Spy(serverWithCapacity(maxSockets: 2L))

		when:
		hostSync.updateHostStats(server, hostStats(cpuCount: '2'))

		then:
		server.maxSockets == 2L
		0 * server.setMaxSockets(_)
		1 * computeServerService.save(server) >> Single.just(server)
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

	private static Map hostStats(Map overrides) {
		[
			id             : 'host-id',
			cpuCount       : '2',
			coresPerCpu    : '4',
			totalMemory    : '0',
			availableMemory: '0',
			totalStorage   : '0',
			usedStorage    : '0',
			hyperVState    : 'Unknown'
		] + overrides
	}

	private static class JsonHostMap extends LinkedHashMap {

		JsonHostMap(Map values) {
			super(values)
		}

		Object encodeAsJSON() {
			return this
		}
	}
}
