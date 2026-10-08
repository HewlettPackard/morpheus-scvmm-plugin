// Copyright 2026 Hewlett Packard Enterprise Development LP
package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.compute.MorpheusComputeServerInterfaceService
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.BulkSaveResult
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

/**
 * Covers the stale-save race fixed for MORPH-16592: the server model loaded before a long remote
 * call must not be persisted afterwards, otherwise agent check-in data (agentInstalled, agentVersion,
 * platform, platformVersion) written to the DB in the meantime is overwritten.
 */
class ScvmmProvisionProviderSpec extends Specification {

    MorpheusContext context = Mock()
    MorpheusAsyncServices asyncServices = Mock()
    MorpheusServices services = Mock()
    MorpheusComputeServerService asyncComputeServer = Mock()
    MorpheusComputeServerInterfaceService interfaceService = Mock()
    MorpheusSynchronousComputeServerService syncComputeServer = Mock()
    ScvmmApiService apiService = Mock()
    ScvmmPlugin plugin = Mock()

    ScvmmProvisionProvider provider

    Cloud cloud = new Cloud(id: 5L, name: 'scvmm-cloud')

    def setup() {
        context.getAsync() >> asyncServices
        context.getServices() >> services
        asyncServices.getComputeServer() >> asyncComputeServer
        asyncComputeServer.getComputeServerInterface() >> interfaceService
        services.getComputeServer() >> syncComputeServer

        provider = GroovySpy(ScvmmProvisionProvider, constructorArgs: [plugin, context])
        provider.apiService = apiService
        // fetchScvmmConnectionDetails is private (not interceptable), so stub its public collaborators;
        // the controller lookup / option building is irrelevant to these tests
        provider.pickScvmmController(_) >> new ComputeServer(id: 1L, name: 'scvmm-controller')
        provider.getScvmmServerOpts(_) >> [:]
        apiService.getScvmmCloudOpts(*_) >> [:]
        apiService.getScvmmControllerOpts(*_) >> [:]
    }

    /** Server as it looked before the remote poll, i.e. before the agent checked in. */
    private ComputeServer staleServer() {
        new ComputeServer(
            id: 25L, name: 'ckeskar-rocky', externalId: 'vm-25', cloud: cloud,
            platform: 'linux', platformVersion: null, agentInstalled: false, agentVersion: null
        )
    }

    /** Server as it looks in the DB after the agent checked in during the poll. */
    private ComputeServer freshServer() {
        new ComputeServer(
            id: 25L, name: 'ckeskar-rocky', externalId: 'vm-25', cloud: cloud,
            platform: 'rocky', platformVersion: '6.12.0-211.el10.x86_64', agentInstalled: true, agentVersion: '4.0.1'
        )
    }

    private BulkSaveResult<ComputeServer> successfulSave(ComputeServer server) {
        new BulkSaveResult<ComputeServer>(null, null, [server], [])
    }

    // ---------------------------------------------------------------- getServerDetails

    def "getServerDetails persists the server re-fetched after checkServerReady, not the pre-poll copy"() {
        given:
        ComputeServer stale = staleServer()
        ComputeServer fresh = freshServer()
        ComputeServer reloadedAfterSave = freshServer()
        reloadedAfterSave.internalIp = '10.0.0.42'
        reloadedAfterSave.externalIp = '10.0.0.42'
        List<ComputeServer> saved = []

        when:
        def response = provider.getServerDetails(new ComputeServer(id: 25L))

        then:
        1 * asyncComputeServer.get(25L) >> Maybe.just(stale)
        1 * apiService.checkServerReady({ it.server.is(stale) && it.waitForIp == true }, 'vm-25') >>
            [success: true, server: [ipAddress: '10.0.0.42', macAddress: '00:15:5D:01:02:03']]
        // the first find() is the re-fetch after the poll, the second is the reload after bulkSave
        2 * syncComputeServer.find(_ as DataQuery) >>> [fresh, reloadedAfterSave]
        1 * interfaceService.create({ List<ComputeServerInterface> ifaces -> ifaces.size() == 1 && ifaces[0].ipAddress == '10.0.0.42' }, fresh) >> Single.just(true)
        1 * asyncComputeServer.bulkSave(_ as List) >> { args ->
            List<ComputeServer> items = args[0]
            saved.addAll(items)
            Single.just(successfulSave(items[0]))
        }
        1 * apiService.getServerDetails(_, 'vm-25') >> [success: true, server: [:]]
        0 * asyncComputeServer.bulkSave(_)

        and: 'the persisted model is the fresh one carrying the agent check-in data'
        saved.size() == 1
        saved[0].is(fresh)
        !saved[0].is(stale)
        saved[0].agentInstalled == true
        saved[0].agentVersion == '4.0.1'
        saved[0].platform == 'rocky'
        saved[0].platformVersion == '6.12.0-211.el10.x86_64'

        and: 'ip and power details were applied to the fresh model'
        saved[0].externalIp == '10.0.0.42'
        saved[0].internalIp == '10.0.0.42'
        saved[0].sshHost == '10.0.0.42'
        saved[0].macAddress == '00:15:5D:01:02:03'
        saved[0].powerState == ComputeServer.PowerState.on

        and: 'the stale copy was left untouched'
        stale.externalIp == null
        stale.internalIp == null
        stale.powerState == null

        and: 'response ips reflect the persisted server'
        response.success
        response.data.privateIp == '10.0.0.42'
        response.data.publicIp == '10.0.0.42'
    }

    def "getServerDetails updates an existing primary interface instead of creating a new one"() {
        given:
        ComputeServer fresh = freshServer()
        ComputeServerInterface existing = new ComputeServerInterface(name: 'eth0', primaryInterface: true, ipAddress: '192.168.0.1')
        fresh.interfaces = [existing]

        when:
        def response = provider.getServerDetails(new ComputeServer(id: 25L))

        then:
        1 * asyncComputeServer.get(25L) >> Maybe.just(staleServer())
        1 * apiService.checkServerReady(_, 'vm-25') >> [success: true, server: [ipAddress: '10.0.0.43', macAddress: 'AA']]
        2 * syncComputeServer.find(_ as DataQuery) >>> [fresh, freshServer()]
        1 * interfaceService.save([existing]) >> Single.just(true)
        0 * interfaceService.create(*_)
        1 * asyncComputeServer.bulkSave([fresh]) >> Single.just(successfulSave(fresh))
        1 * apiService.getServerDetails(_, 'vm-25') >> [success: true, server: [:]]

        and: 'the existing interface was updated in place'
        existing.ipAddress == '10.0.0.43'
        existing.publicIpAddress == '10.0.0.43'
        existing.macAddress == 'AA'
        response.success
    }

    def "getServerDetails does not save anything when checkServerReady fails"() {
        given:
        ComputeServer stale = staleServer()

        when:
        def response = provider.getServerDetails(new ComputeServer(id: 25L))

        then:
        1 * asyncComputeServer.get(25L) >> Maybe.just(stale)
        1 * apiService.checkServerReady(_, 'vm-25') >> [success: false, message: 'timed out waiting for ip']
        0 * syncComputeServer.find(_)
        0 * syncComputeServer.save(_)
        0 * asyncComputeServer.bulkSave(_)
        0 * interfaceService._

        and:
        !response.success
        response.msg == 'timed out waiting for ip'
    }

    def "getServerDetails uses the default message when checkServerReady fails without one"() {
        when:
        def response = provider.getServerDetails(new ComputeServer(id: 25L))

        then:
        1 * asyncComputeServer.get(25L) >> Maybe.just(staleServer())
        1 * apiService.checkServerReady(_, 'vm-25') >> [success: false]
        0 * asyncComputeServer.bulkSave(_)

        and:
        !response.success
        response.msg == 'Failed to get server details'
    }

    // ---------------------------------------------------------------- updateServerHost

    def "updateServerHost reloads the server after the remote call and saves the reloaded copy"() {
        given:
        ComputeServer stale = staleServer()
        stale.consoleType = 'vmrdp'
        ComputeServer latest = freshServer()
        latest.consoleType = 'vmrdp'
        ComputeServer host = new ComputeServer(id: 900L, name: 'hyperv-host-01', externalId: 'host-01')

        when:
        provider.updateServerHost(stale, [some: 'opts'])

        then:
        1 * apiService.getServerDetails([some: 'opts'], 'vm-25') >> [success: true, server: [HostId: 'host-01']]
        1 * syncComputeServer.list(_ as DataQuery) >> [new ComputeServer(id: 901L, externalId: 'host-02'), host]
        1 * syncComputeServer.get(25L) >> latest
        1 * syncComputeServer.save({ ComputeServer s -> s.is(latest) }) >> latest
        0 * syncComputeServer.save({ ComputeServer s -> s.is(stale) })

        and: 'host assignment is applied to the reloaded copy only'
        latest.parentServer.is(host)
        latest.consoleHost == 'hyperv-host-01'
        latest.agentInstalled == true
        latest.platform == 'rocky'
        stale.parentServer == null
        stale.consoleHost == null
    }

    def "updateServerHost does not set consoleHost when consoleType is not vmrdp"() {
        given:
        ComputeServer latest = freshServer()
        latest.consoleType = 'vnc'
        ComputeServer host = new ComputeServer(id: 900L, name: 'hyperv-host-01', externalId: 'host-01')

        when:
        provider.updateServerHost(staleServer(), [:])

        then:
        1 * apiService.getServerDetails(_, 'vm-25') >> [success: true, server: [HostId: 'host-01']]
        1 * syncComputeServer.list(_ as DataQuery) >> [host]
        1 * syncComputeServer.get(25L) >> latest
        1 * syncComputeServer.save(latest) >> latest

        and:
        latest.parentServer.is(host)
        latest.consoleHost == null
    }

    def "updateServerHost falls back to the passed server when the reload returns nothing"() {
        given:
        ComputeServer server = staleServer()
        ComputeServer host = new ComputeServer(id: 900L, name: 'hyperv-host-01', externalId: 'host-01')

        when:
        provider.updateServerHost(server, [:])

        then:
        1 * apiService.getServerDetails(_, 'vm-25') >> [success: true, server: [HostId: 'host-01']]
        1 * syncComputeServer.list(_ as DataQuery) >> [host]
        1 * syncComputeServer.get(25L) >> null
        1 * syncComputeServer.save(server) >> server

        and:
        server.parentServer.is(host)
    }

    def "updateServerHost does not save when the parent host is unchanged"() {
        given:
        ComputeServer host = new ComputeServer(id: 900L, name: 'hyperv-host-01', externalId: 'host-01')
        ComputeServer server = staleServer()
        server.parentServer = host

        when:
        provider.updateServerHost(server, [:])

        then:
        1 * apiService.getServerDetails(_, 'vm-25') >> [success: true, server: [HostId: 'host-01']]
        1 * syncComputeServer.list(_ as DataQuery) >> [host]
        0 * syncComputeServer.get(_)
        0 * syncComputeServer.save(_)
    }

    def "updateServerHost does not save when SCVMM reports no host or an unknown host"() {
        when:
        provider.updateServerHost(staleServer(), [:])

        then:
        1 * apiService.getServerDetails(_, 'vm-25') >> details
        listCalls * syncComputeServer.list(_ as DataQuery) >> [new ComputeServer(id: 900L, externalId: 'host-01')]
        0 * syncComputeServer.get(_)
        0 * syncComputeServer.save(_)

        where:
        details                                             | listCalls
        [success: true, server: [:]]                        | 0
        [success: false]                                    | 0
        [success: true, server: [HostId: 'host-unknown']]   | 1
    }

    def "updateServerHost swallows exceptions from the remote call"() {
        when:
        provider.updateServerHost(staleServer(), [:])

        then:
        1 * apiService.getServerDetails(_, 'vm-25') >> { throw new RuntimeException('winrm down') }
        0 * syncComputeServer._
        noExceptionThrown()
    }
}
