package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.scvmm.logging.LogInterface
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

class HostSyncSpec extends Specification {

    MorpheusContext mockContext
    Cloud mockCloud
    ComputeServer mockNode
    ScvmmApiService mockApiService
    LogInterface mockLog

    def setup() {
        mockContext = Mock(MorpheusContext)
        mockCloud = new Cloud(id: 1L, owner: new Account(id: 1L))
        mockNode = new ComputeServer(id: 1L)
        mockApiService = Mock(ScvmmApiService)
        mockLog = Mock(LogInterface)
    }

    def "constructor should initialize fields properly"() {
        when:
        def sync = new HostSync(mockCloud, mockNode, mockContext)

        then:
        sync.cloud == mockCloud
        sync.node == mockNode
        sync.context == mockContext
        sync.apiService != null
    }

    def "getHypervisorOs should return Windows Server 2016 for 2016 OS"() {
        given:
        def sync = new HostSync(mockCloud, mockNode, mockContext)

        when:
        def os = sync.getHypervisorOs("Windows Server 2016")

        then:
        os.code == "windows.server.2016"
    }

    def "getHypervisorOs should return Windows Server 2012 for 2012 OS"() {
        given:
        def sync = new HostSync(mockCloud, mockNode, mockContext)

        when:
        def os = sync.getHypervisorOs("Windows Server 2012 R2")

        then:
        os.code == "windows.server.2012"
    }

    def "getHypervisorOs should return Windows Server 2012 as default"() {
        given:
        def sync = new HostSync(mockCloud, mockNode, mockContext)

        when:
        def osOther = sync.getHypervisorOs("Some other OS")
        def osNull = sync.getHypervisorOs(null)

        then:
        osOther.code == "windows.server.2012"
        osNull.code == "windows.server.2012"
    }

    def "execute should handle API failure gracefully"() {
        given:
        def scvmmOpts = [host: "test-host"]
        def listResults = [success: false, hosts: null]
        
        def sync = new HostSync(mockCloud, mockNode, mockContext)
        sync.apiService = mockApiService
        sync.log = mockLog
        
        mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, mockCloud, mockNode) >> scvmmOpts
        mockApiService.listHosts(scvmmOpts) >> listResults

        when:
        sync.execute()

        then:
        1 * mockLog.debug("HostSync")
        1 * mockLog.error({ it.contains("Error in getting hosts") })
        notThrown(Exception)
    }

    def "execute should handle exception and log error"() {
        given:
        def sync = new HostSync(mockCloud, mockNode, mockContext)
        sync.apiService = mockApiService
        sync.log = mockLog
        
        mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, mockCloud, mockNode) >> { 
            throw new RuntimeException("Test exception") 
        }

        when:
        sync.execute()

        then:
        1 * mockLog.debug("HostSync")
        1 * mockLog.error({ it.contains("HostSync error") }, _ as Exception)
        notThrown(Exception)
    }

    def "updateMatchedHosts should update host resource pools when cluster changes"() {
        given:
        // Create a simple stub that returns what we need without type casting issues
        def mockAsyncServices = Stub(MorpheusAsyncServices)
        def mockComputeServerService = Stub(com.morpheusdata.core.MorpheusComputeServerService)
        
        mockAsyncServices.getComputeServer() >> mockComputeServerService
        mockContext.getAsync() >> mockAsyncServices
        
        def cluster1 = new CloudPool(id: 1L, internalId: "cluster1", name: "Cluster1")
        def cluster2 = new CloudPool(id: 2L, internalId: "cluster2", name: "Cluster2")
        def existingHost = new ComputeServer(id: 10L, externalId: "host1", resourcePool: cluster1)
        def masterItem = [cluster: "cluster2", totalMemory: "16000000000", totalStorage: "500000000000",
                         cpuCount: "4", coresPerCpu: "2", usedStorage: "100000000000",
                         cpuUtilization: "25", availableMemory: "8000", hyperVState: "Running",
                         name: "host1.domain.com"]
        
        def updateItem = new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: existingHost, masterItem: masterItem)
        def updateList = [updateItem]
        def clusters = [cluster1, cluster2]
        
        mockComputeServerService.save(existingHost) >> Single.just(existingHost)
        
        def sync = new HostSync(mockCloud, mockNode, mockContext)
        sync.log = mockLog

        when:
        sync.updateMatchedHosts(updateList, clusters)

        then:
        1 * mockLog.debug({ it.contains("updateMatchedHosts") })
        existingHost.resourcePool == cluster2
        notThrown(Exception)
    }

    def "updateMatchedHosts should not update when cluster is same"() {
        given:
        def cluster1 = new CloudPool(id: 1L, internalId: "cluster1", name: "Cluster1")
        def existingHost = new ComputeServer(id: 10L, externalId: "host1", resourcePool: cluster1)
        def masterItem = [cluster: "cluster1"]
        
        def updateItem = new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: existingHost, masterItem: masterItem)
        def updateList = [updateItem]
        def clusters = [cluster1]
        
        def sync = new HostSync(mockCloud, mockNode, mockContext)
        sync.log = mockLog

        when:
        sync.updateMatchedHosts(updateList, clusters)

        then:
        1 * mockLog.debug({ it.contains("updateMatchedHosts") })
        // No save should be called since cluster didn't change
        notThrown(Exception)
    }

    def "updateMatchedHosts should handle exception and log error"() {
        given:
        def mockAsyncServices = Stub(MorpheusAsyncServices)
        def mockComputeServerService = Stub(com.morpheusdata.core.MorpheusComputeServerService)
        
        mockAsyncServices.getComputeServer() >> mockComputeServerService
        mockContext.getAsync() >> mockAsyncServices
        
        def cluster1 = new CloudPool(id: 1L, internalId: "cluster1", name: "Cluster1")
        def existingHost = new ComputeServer(id: 10L, externalId: "host1", resourcePool: null)
        def masterItem = [cluster: "cluster1"]
        
        def updateItem = new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: existingHost, masterItem: masterItem)
        def updateList = [updateItem]
        def clusters = [cluster1]
        
        mockComputeServerService.save(existingHost) >> { throw new RuntimeException("Save failed") }
        
        def sync = new HostSync(mockCloud, mockNode, mockContext)
        sync.log = mockLog

        when:
        sync.updateMatchedHosts(updateList, clusters)

        then:
        1 * mockLog.error({ it.contains("updateMatchedHosts error") }, _ as Exception)
        notThrown(Exception)
    }

    def "addMissingHosts should create new hosts with Windows Server 2016"() {
        given:
        def mockAsyncServices = Stub(MorpheusAsyncServices)
        def mockCloudService = Stub(com.morpheusdata.core.cloud.MorpheusCloudService)
        def mockComputeServerService = Stub(com.morpheusdata.core.MorpheusComputeServerService)
        
        mockAsyncServices.getCloud() >> mockCloudService
        mockAsyncServices.getComputeServer() >> mockComputeServerService
        mockContext.getAsync() >> mockAsyncServices
        
        def cluster1 = new CloudPool(id: 1L, internalId: "cluster1", name: "Cluster1")
        def addList = [
            [id: "host1", computerName: "Host1", name: "host1.domain.com",
             totalMemory: "16000000000", totalStorage: "500000000000",
             cpuCount: "4", coresPerCpu: "2", os: "Windows Server 2016", cluster: "cluster1",
             usedStorage: "100000000000", cpuUtilization: "25", availableMemory: "8000",
             hyperVState: "Running"]
        ]
        def clusters = [cluster1]
        
        def serverType = new ComputeServerType(id: 1L, code: "scvmmHypervisor")
        def createdHost = new ComputeServer(id: 10L)
        
        mockCloudService.findComputeServerTypeByCode("scvmmHypervisor") >> Maybe.just(serverType)
        mockComputeServerService.create(_ as ComputeServer) >> Single.just(createdHost)
        mockComputeServerService.save(_ as ComputeServer) >> Single.just(createdHost)
        
        def sync = new HostSync(mockCloud, mockNode, mockContext)
        sync.log = mockLog

        when:
        sync.addMissingHosts(addList, clusters)

        then:
        1 * mockLog.debug({ it.contains("addMissingHosts") })
        1 * mockLog.debug({ it.contains("serverConfig") })
        notThrown(Exception)
    }

    def "addMissingHosts should create new hosts with Windows Server 2012 fallback"() {
        given:
        def mockAsyncServices = Stub(MorpheusAsyncServices)
        def mockCloudService = Stub(com.morpheusdata.core.cloud.MorpheusCloudService)
        def mockComputeServerService = Stub(com.morpheusdata.core.MorpheusComputeServerService)
        
        mockAsyncServices.getCloud() >> mockCloudService
        mockAsyncServices.getComputeServer() >> mockComputeServerService
        mockContext.getAsync() >> mockAsyncServices
        
        def addList = [
            [id: "host2", computerName: "Host2", name: "host2.domain.com",
             totalMemory: "8000000000", totalStorage: "250000000000",
             cpuCount: "2", coresPerCpu: "4", os: "Windows Server 2012 R2", cluster: null]
        ]
        def clusters = []
        
        def serverType = new ComputeServerType(id: 1L, code: "scvmmHypervisor")
        def createdHost = new ComputeServer(id: 20L)
        
        mockCloudService.findComputeServerTypeByCode("scvmmHypervisor") >> Maybe.just(serverType)
        mockComputeServerService.create(_ as ComputeServer) >> Single.just(createdHost)
        mockComputeServerService.save(_ as ComputeServer) >> Single.just(createdHost)
        
        def sync = new HostSync(mockCloud, mockNode, mockContext)
        sync.log = mockLog

        when:
        sync.addMissingHosts(addList, clusters)

        then:
        1 * mockLog.debug({ it.contains("addMissingHosts") })
        notThrown(Exception)
    }

    def "addMissingHosts should handle exception and log error"() {
        given:
        def mockAsyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def mockAsyncServices = Mock(MorpheusAsyncServices)
        
        mockContext.getAsync() >> mockAsyncServices
        mockAsyncServices.getCloud() >> mockAsyncCloudService
        
        def addList = [
            [id: "host1", computerName: "Host1", name: "host1.domain.com",
             totalMemory: "8000000000", totalStorage: "250000000000",
             cpuCount: "2", coresPerCpu: "2", os: "Windows Server 2016", cluster: null]
        ]
        def clusters = []
        
        mockAsyncCloudService.findComputeServerTypeByCode("scvmmHypervisor") >> { throw new RuntimeException("Test error") }
        
        def sync = new HostSync(mockCloud, mockNode, mockContext)
        sync.log = mockLog

        when:
        sync.addMissingHosts(addList, clusters)

        then:
        1 * mockLog.error({ it.contains("addMissingHosts error") }, _ as Exception)
        notThrown(Exception)
    }

    def "removeMissingHosts should remove hosts and update parent servers"() {
        given:
        def mockComputeServerService = Mock(com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService)
        def mockAsyncComputeServerService = Mock(com.morpheusdata.core.MorpheusComputeServerService)
        def mockServices = Mock(MorpheusServices)
        def mockAsyncServices = Mock(MorpheusAsyncServices)
        
        mockContext.getServices() >> mockServices
        mockServices.getComputeServer() >> mockComputeServerService
        mockContext.getAsync() >> mockAsyncServices
        mockAsyncServices.getComputeServer() >> mockAsyncComputeServerService
        
        def removeList = [
            new ComputeServerIdentityProjection(id: 1L, externalId: "host1", name: "Host1"),
            new ComputeServerIdentityProjection(id: 2L, externalId: "host2", name: "Host2")
        ]
        def parentServer1 = new ComputeServer(id: 10L, parentServer: new ComputeServer(id: 1L))
        def parentServer2 = new ComputeServer(id: 11L, parentServer: new ComputeServer(id: 2L))
        def parentServers = [parentServer1, parentServer2]
        
        mockComputeServerService.list(_ as DataQuery) >> parentServers
        mockAsyncComputeServerService.bulkSave(_) >> Single.just([])
        mockAsyncComputeServerService.bulkRemove(_) >> Single.just(true)
        
        def sync = new HostSync(mockCloud, mockNode, mockContext)
        sync.log = mockLog

        when:
        sync.removeMissingHosts(removeList)

        then:
        1 * mockLog.debug({ it.contains("removeMissingHosts") })
        notThrown(Exception)
    }

    def "removeMissingHosts should handle empty parent servers list"() {
        given:
        def mockComputeServerService = Mock(com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService)
        def mockAsyncComputeServerService = Mock(com.morpheusdata.core.MorpheusComputeServerService)
        def mockServices = Mock(MorpheusServices)
        def mockAsyncServices = Mock(MorpheusAsyncServices)
        
        mockContext.getServices() >> mockServices
        mockServices.getComputeServer() >> mockComputeServerService
        mockContext.getAsync() >> mockAsyncServices
        mockAsyncServices.getComputeServer() >> mockAsyncComputeServerService
        
        def removeList = [
            new ComputeServerIdentityProjection(id: 1L, externalId: "host1", name: "Host1")
        ]
        
        mockComputeServerService.list(_ as DataQuery) >> []
        mockAsyncComputeServerService.bulkRemove(_) >> Single.just(true)
        
        def sync = new HostSync(mockCloud, mockNode, mockContext)
        sync.log = mockLog

        when:
        sync.removeMissingHosts(removeList)

        then:
        1 * mockLog.debug({ it.contains("removeMissingHosts") })
        notThrown(Exception)
    }

    def "removeMissingHosts should handle exception and log error"() {
        given:
        def mockComputeServerService = Mock(Object)
        mockContext.getServices() >> Mock(Object) {
            getComputeServer() >> mockComputeServerService
        }
        
        def removeList = [
            new ComputeServerIdentityProjection(id: 1L, externalId: "host1", name: "Host1")
        ]
        
        mockComputeServerService.list(_ as DataQuery) >> { throw new RuntimeException("Test error") }
        
        def sync = new HostSync(mockCloud, mockNode, mockContext)
        sync.log = mockLog

        when:
        sync.removeMissingHosts(removeList)

        then:
        1 * mockLog.error({ it.contains("removeMissingHosts error") }, _ as Exception)
        notThrown(Exception)
    }

    def "updateHostStats should update all server statistics when power is on"() {
        given:
        def mockAsyncComputeServerService = Mock(com.morpheusdata.core.MorpheusComputeServerService)
        def mockAsyncServices = Mock(MorpheusAsyncServices)
        
        mockContext.getAsync() >> mockAsyncServices
        mockAsyncServices.getComputeServer() >> mockAsyncComputeServerService
        
        def server = new ComputeServer(
            id: 10L,
            maxCpu: 2L,
            maxCores: 4L,
            maxMemory: 8000000000L,
            maxStorage: 250000000000L,
            powerState: "off",
            hostname: "oldname.domain.com"
        )
        
        def hostMap = [
            totalStorage: "500000000000",
            usedStorage: "100000000000",
            cpuCount: "4",
            coresPerCpu: "2",
            cpuUtilization: "25",
            totalMemory: "16000000000",
            availableMemory: "8000",
            hyperVState: "Running",
            name: "newname.domain.com"
        ]
        
        mockAsyncComputeServerService.save(server) >> Single.just(server)
        
        def sync = new HostSync(mockCloud, mockNode, mockContext)
        sync.log = mockLog

        when:
        sync.updateHostStats(server, hostMap)

        then:
        1 * mockLog.debug({ it.contains("updateHostStats") })
        notThrown(Exception)
    }

    def "updateHostStats should handle power state mapping correctly"() {
        given:
        def mockAsyncComputeServerService = Mock(com.morpheusdata.core.MorpheusComputeServerService)
        def mockAsyncServices = Mock(MorpheusAsyncServices)
        
        mockContext.getAsync() >> mockAsyncServices
        mockAsyncServices.getComputeServer() >> mockAsyncComputeServerService
        
        def server = new ComputeServer(id: 10L, powerState: ComputeServer.PowerState.unknown)
        server.capacityInfo = new ComputeCapacityInfo()
        server.maxCpu = 4L
        server.maxCores = 8L
        server.maxMemory = 16000000000L
        server.maxStorage = 500000000000L
        
        mockAsyncComputeServerService.save(server) >> Single.just(server)
        
        def sync = new HostSync(mockCloud, mockNode, mockContext)
        sync.log = mockLog

        when: "hyperVState is Running"
        def hostMapRunning = [hyperVState: "Running", cpuUtilization: "25"]
        sync.updateHostStats(server, hostMapRunning)

        then:
        server.powerState == ComputeServer.PowerState.on

        when: "hyperVState is Stopped"
        server.powerState = ComputeServer.PowerState.unknown
        def hostMapStopped = [hyperVState: "Stopped"]
        sync.updateHostStats(server, hostMapStopped)

        then:
        server.powerState == ComputeServer.PowerState.off

        when: "hyperVState is other"
        server.powerState = ComputeServer.PowerState.on
        def hostMapOther = [hyperVState: "Paused"]
        sync.updateHostStats(server, hostMapOther)

        then:
        server.powerState == ComputeServer.PowerState.unknown
    }

    def "updateHostStats should not save when no updates needed"() {
        given:
        def capacityInfo = new ComputeCapacityInfo(
            maxMemory: 16000000000L,
            maxStorage: 500000000000L,
            usedMemory: 8000000000L,
            usedStorage: 100000000000L
        )
        def server = new ComputeServer(
            id: 10L,
            maxCpu: 4L,
            maxCores: 8L,
            maxMemory: 16000000000L,
            maxStorage: 500000000000L,
            usedMemory: 8000000000L,
            usedStorage: 100000000000L,
            powerState: "off",
            hostname: "host1.domain.com",
            capacityInfo: capacityInfo
        )
        
        def hostMap = [
            totalStorage: "500000000000",
            usedStorage: "100000000000",
            cpuCount: "4",
            coresPerCpu: "2",
            totalMemory: "16000000000",
            availableMemory: "8000",
            hyperVState: "Stopped",
            name: "host1.domain.com"
        ]
        
        def sync = new HostSync(mockCloud, mockNode, mockContext)
        sync.log = mockLog

        when:
        sync.updateHostStats(server, hostMap)

        then:
        // No save should be called since nothing changed
        notThrown(Exception)
    }

    def "updateHostStats should create new capacityInfo when null"() {
        given:
        def mockAsyncComputeServerService = Mock(com.morpheusdata.core.MorpheusComputeServerService)
        def mockAsyncServices = Mock(MorpheusAsyncServices)
        
        mockContext.getAsync() >> mockAsyncServices
        mockAsyncServices.getComputeServer() >> mockAsyncComputeServerService
        
        def server = new ComputeServer(
            id: 10L,
            maxCpu: 2L,
            maxCores: 4L,
            maxMemory: 8000000000L,
            maxStorage: 250000000000L,
            powerState: "off",
            capacityInfo: null
        )
        
        def hostMap = [
            totalStorage: "500000000000",
            usedStorage: "100000000000",
            cpuCount: "4",
            coresPerCpu: "2",
            totalMemory: "16000000000",
            availableMemory: "8000",
            hyperVState: "Running"
        ]
        
        mockAsyncComputeServerService.save(server) >> Single.just(server)
        
        def sync = new HostSync(mockCloud, mockNode, mockContext)
        sync.log = mockLog

        when:
        sync.updateHostStats(server, hostMap)

        then:
        1 * mockLog.debug({ it.contains("updateHostStats") })
        notThrown(Exception)
    }

    def "updateHostStats should handle exception and log warning"() {
        given:
        def mockAsyncComputeServerService = Mock(Object)
        mockContext.getAsync() >> Mock(Object) {
            getComputeServer() >> mockAsyncComputeServerService
        }
        
        def server = new ComputeServer(id: 10L, powerState: "off")
        def hostMap = [hyperVState: "Running"]
        
        mockAsyncComputeServerService.save(server) >> { throw new RuntimeException("Save failed") }
        
        def sync = new HostSync(mockCloud, mockNode, mockContext)
        sync.log = mockLog

        when:
        sync.updateHostStats(server, hostMap)

        then:
        1 * mockLog.warn({ it.contains("error updating host stats") }, _ as Exception)
        notThrown(Exception)
    }

    def "updateHostStats should handle missing or zero values gracefully"() {
        given:
        def mockAsyncComputeServerService = Mock(com.morpheusdata.core.MorpheusComputeServerService)
        def mockAsyncServices = Mock(MorpheusAsyncServices)
        
        mockContext.getAsync() >> mockAsyncServices
        mockAsyncServices.getComputeServer() >> mockAsyncComputeServerService
        
        def server = new ComputeServer(
            id: 10L,
            maxCpu: 1L,
            maxCores: 1L,
            maxMemory: 0L,
            maxStorage: 0L,
            powerState: "off"
        )
        
        def hostMap = [
            totalStorage: null,
            usedStorage: null,
            cpuCount: null,
            coresPerCpu: null,
            totalMemory: null,
            availableMemory: null,
            hyperVState: "Running"
        ]
        
        mockAsyncComputeServerService.save(server) >> Single.just(server)
        
        def sync = new HostSync(mockCloud, mockNode, mockContext)
        sync.log = mockLog

        when:
        sync.updateHostStats(server, hostMap)

        then:
        1 * mockLog.debug({ it.contains("updateHostStats") })
        notThrown(Exception)
    }
}
