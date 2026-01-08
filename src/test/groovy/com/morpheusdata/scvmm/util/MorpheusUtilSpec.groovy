package com.morpheusdata.scvmm.util

import com.morpheusdata.core.BulkSaveResult
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

class MorpheusUtilSpec extends Specification {

    private MorpheusContext morpheusContext
    private MorpheusServices morpheusServices
    private MorpheusAsyncServices morpheusAsyncServices
    private MorpheusComputeServerService asyncComputeServerService
    private MorpheusSynchronousComputeServerService computeServerService
    private LogInterface mockLog

    def setup() {
        // Setup mocks
        morpheusContext = Mock(MorpheusContext)
        morpheusServices = Mock(MorpheusServices)
        morpheusAsyncServices = Mock(MorpheusAsyncServices)
        asyncComputeServerService = Mock(MorpheusComputeServerService)
        computeServerService = Mock(MorpheusSynchronousComputeServerService)
        mockLog = Mock(LogInterface)

        // Configure context services
        morpheusContext.services >> morpheusServices
        morpheusContext.async >> morpheusAsyncServices
        morpheusServices.computeServer >> computeServerService
        morpheusAsyncServices.computeServer >> asyncComputeServerService

        // Mock the LogWrapper singleton instance property
        LogWrapper.metaClass.static.instance = mockLog
    }

    def "saveAndGetMorpheusServer should save server successfully and return updated server when fullReload is false"() {
        given: "A compute server to save"
        def server = new ComputeServer(id: 123L, name: "test-server")
        def savedServer = new ComputeServer(id: 123L, name: "test-server-saved")
        def saveResult = Mock(BulkSaveResult) {
            getSuccess() >> true
            getPersistedItems() >> [savedServer]
            getFailedItems() >> []
        }

        when: "Saving the server with fullReload false"
        def result = MorpheusUtil.saveAndGetMorpheusServer(morpheusContext, server, false)

        then: "The server is saved via bulk save and returned server is from persisted items"
        1 * asyncComputeServerService.bulkSave([server]) >> Single.just(saveResult)
        0 * computeServerService.find(_) // No additional find call when fullReload is false
        result == savedServer
        result.id == 123L
    }

    def "saveAndGetMorpheusServer should save server successfully and return reloaded server when fullReload is true"() {
        given: "A compute server to save"
        def server = new ComputeServer(id: 456L, name: "test-server")
        def savedServer = new ComputeServer(id: 456L, name: "test-server-saved")
        def reloadedServer = new ComputeServer(id: 456L, name: "test-server-reloaded")
        def saveResult = Mock(BulkSaveResult) {
            getSuccess() >> true
            getPersistedItems() >> [savedServer]
            getFailedItems() >> []
        }

        when: "Saving the server with fullReload true"
        def result = MorpheusUtil.saveAndGetMorpheusServer(morpheusContext, server, true)

        then: "The server is saved and then reloaded from database"
        1 * asyncComputeServerService.bulkSave([server]) >> Single.just(saveResult)
        1 * computeServerService.find(_) >> { DataQuery query ->
            // Verify the query is constructed correctly
            assert query.filters.find { it.name == "id" }?.value == 456L
            assert query.joins.contains("interfaces.network")
            return reloadedServer
        }
        result == reloadedServer
        result.id == 456L
    }

    def "saveAndGetMorpheusServer should handle save failure and return failed server"() {
        given: "A compute server to save that will fail"
        def server = new ComputeServer(id: 789L, name: "test-server")
        def failedServer = new ComputeServer(id: 789L, name: "failed-server")
        def saveResult = Mock(BulkSaveResult) {
            getSuccess() >> false
            getPersistedItems() >> []
            getFailedItems() >> [failedServer]
        }

        when: "Saving the server that fails"
        def result = MorpheusUtil.saveAndGetMorpheusServer(morpheusContext, server, false)

        then: "The failed server is returned and warning is logged"
        1 * asyncComputeServerService.bulkSave([server]) >> Single.just(saveResult)
        0 * computeServerService.find(_) // No find call when save fails
        result == failedServer
        result.id == 789L
    }

    def "saveAndGetMorpheusServer should return original server when save fails and no failed item exists"() {
        given: "A compute server to save that will fail without failed items"
        def server = new ComputeServer(id: 999L, name: "test-server")
        def saveResult = Mock(BulkSaveResult) {
            getSuccess() >> false
            getPersistedItems() >> []
            getFailedItems() >> []
        }

        when: "Saving the server that fails with no failed items returned"
        def result = MorpheusUtil.saveAndGetMorpheusServer(morpheusContext, server, false)

        then: "The original server is returned and warning is logged"
        1 * asyncComputeServerService.bulkSave([server]) >> Single.just(saveResult)
        0 * computeServerService.find(_) // No find call when save fails
        result == server
        result.id == 999L
    }

    def "saveAndGetMorpheusServer should return original server when save result has no matching persisted item"() {
        given: "A compute server to save"
        def server = new ComputeServer(id: 111L, name: "test-server")
        def otherServer = new ComputeServer(id: 222L, name: "other-server")
        def saveResult = Mock(BulkSaveResult) {
            getSuccess() >> true
            getPersistedItems() >> [otherServer]
            getFailedItems() >> []
        }

        when: "Saving the server but persisted items don't contain our server"
        def result = MorpheusUtil.saveAndGetMorpheusServer(morpheusContext, server, false)

        then: "The original server is returned"
        1 * asyncComputeServerService.bulkSave([server]) >> Single.just(saveResult)
        0 * mockLog.warn(_) // No warning logged for successful save
        0 * computeServerService.find(_) // No find call when fullReload is false
        result == server
        result.id == 111L
    }

    def "getMorpheusServer should find server by id with correct DataQuery"() {
        given: "A server id to find"
        def serverId = 555L
        def foundServer = new ComputeServer(id: serverId, name: "found-server")

        when: "Getting the server by id"
        def result = MorpheusUtil.getMorpheusServer(morpheusContext, serverId)

        then: "The server is found with correct query parameters"
        1 * computeServerService.find(_) >> { DataQuery query ->
            // Verify the query is constructed correctly
            assert query.filters.find { it.name == "id" }?.value == serverId
            assert query.joins.contains("interfaces.network")
            return foundServer
        }
        result == foundServer
        result.id == serverId
    }

    def "getMorpheusServer should return null when server not found"() {
        given: "A server id that doesn't exist"
        def serverId = 666L

        when: "Getting the server by id"
        def result = MorpheusUtil.getMorpheusServer(morpheusContext, serverId)

        then: "Null is returned when server is not found"
        1 * computeServerService.find(_) >> { DataQuery query ->
            // Verify the query is constructed correctly
            assert query.filters.find { it.name == "id" }?.value == serverId
            assert query.joins.contains("interfaces.network")
            return null
        }
        result == null
    }

    def "getMorpheusServer should handle different server ids correctly"() {
        given: "Different server ids"
        def serverId1 = 777L
        def serverId2 = 888L
        def server1 = new ComputeServer(id: serverId1, name: "server1")
        def server2 = new ComputeServer(id: serverId2, name: "server2")

        when: "Getting servers by different ids"
        def result1 = MorpheusUtil.getMorpheusServer(morpheusContext, serverId1)
        def result2 = MorpheusUtil.getMorpheusServer(morpheusContext, serverId2)

        then: "Each server is found with their respective id"
        1 * computeServerService.find(_) >> { DataQuery query ->
            if (query.filters.find { it.name == "id" }?.value == serverId1) {
                return server1
            }
            return null
        }
        1 * computeServerService.find(_) >> { DataQuery query ->
            if (query.filters.find { it.name == "id" }?.value == serverId2) {
                return server2
            }
            return null
        }

        result1 == server1
        result1.id == serverId1
        result2 == server2
        result2.id == serverId2
    }

    def "saveAndGetMorpheusServer should handle null server gracefully"() {
        given: "A null server"
        def server = null

        when: "Saving a null server"
        def result = MorpheusUtil.saveAndGetMorpheusServer(morpheusContext, server, false)

        then: "The method handles null gracefully"
        1 * asyncComputeServerService.bulkSave([null]) >> {
            def saveResult = Mock(BulkSaveResult) {
                getSuccess() >> false
                getPersistedItems() >> []
                getFailedItems() >> []
            }
            return Single.just(saveResult)
        }
        result == null
    }

    def "saveAndGetMorpheusServer with fullReload true should return original server when reload returns null"() {
        given: "A compute server to save"
        def server = new ComputeServer(id: 444L, name: "test-server")
        def savedServer = new ComputeServer(id: 444L, name: "test-server-saved")
        def saveResult = Mock(BulkSaveResult) {
            getSuccess() >> true
            getPersistedItems() >> [savedServer]
            getFailedItems() >> []
        }

        when: "Saving the server with fullReload true but reload returns null"
        def result = MorpheusUtil.saveAndGetMorpheusServer(morpheusContext, server, true)

        then: "The original server is returned when reload fails"
        1 * asyncComputeServerService.bulkSave([server]) >> Single.just(saveResult)
        1 * computeServerService.find(_) >> null
        result == server
        result.id == 444L
    }

    def cleanup() {
        // Reset the LogWrapper metaclass to avoid side effects
        LogWrapper.metaClass = null
    }
}
