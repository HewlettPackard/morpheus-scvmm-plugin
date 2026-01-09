package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.network.MorpheusNetworkService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.NetworkIdentityProjection
import com.morpheusdata.scvmm.ScvmmApiService
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Unroll

class IsolationNetworkSyncSpec extends Specification {

    IsolationNetworkSync sync
    MorpheusContext mockContext
    ScvmmApiService apiService
    Cloud cloud
    Account account
    ComputeServer server

    // Mock nested service structure
    MorpheusServices mockServices
    MorpheusAsyncServices mockAsyncServices
    MorpheusCloudService mockCloudService
    MorpheusNetworkService mockNetworkService
    MorpheusSynchronousComputeServerService mockComputeServerService

    def setup() {
        account = new Account(id: 42L, name: 'acct')
        cloud = new Cloud(id: 77L, name: 'scvmm-cloud', account: account, owner: account)
        server = new ComputeServer(id: 99L, name: 'hyperv-1', externalId: 'srv-99', cloud: cloud)

        mockContext = Mock(MorpheusContext)
        apiService = Mock(ScvmmApiService)
        mockServices = Mock(MorpheusServices)
        mockAsyncServices = Mock(MorpheusAsyncServices)
        mockCloudService = Mock(MorpheusCloudService)
        mockNetworkService = Mock(MorpheusNetworkService)
        mockComputeServerService = Mock(MorpheusSynchronousComputeServerService)

        // Wiring
        mockContext.getServices() >> mockServices
        mockContext.getAsync() >> mockAsyncServices
        mockServices.getComputeServer() >> mockComputeServerService
        mockAsyncServices.getCloud() >> mockCloudService
        mockCloudService.getNetwork() >> mockNetworkService

        sync = new IsolationNetworkSync(mockContext, cloud, apiService)
    }

    def "constructor initializes fields"() {
        expect:
        sync != null
    }

    def "execute performs add, update, delete operations correctly"() {
        given: 'API returns three networks: one new, one changed, one unchanged; DB has changed + unchanged + stale'
        def scvmmOpts = [auth: 'x']
        def apiNetworks = [
                [ID:'net-new', Name:'New Net', Subnet:'10.10.10.0/24', VLanID:500],
                [ID:'net-update', Name:'Update Me', Subnet:'172.16.0.0/20', VLanID:300],
                [ID:'net-same', Name:'Same Net', Subnet:'192.168.5.0/24', VLanID:100]
        ]
        def listResults = [success:true, networks: apiNetworks]

        // Existing projections
        def projUpdate = Mock(NetworkIdentityProjection) { getExternalId() >> 'net-update'; getId() >> 1L }
        def projSame   = Mock(NetworkIdentityProjection) { getExternalId() >> 'net-same'; getId() >> 2L }
        def projDelete = Mock(NetworkIdentityProjection) { getExternalId() >> 'net-delete'; getId() >> 3L }

        // Full objects for update load
        def existingUpdate = new Network(id:1L, externalId:'net-update', cidr:'172.16.1.0/24', vlanId:111)
        def existingSame   = new Network(id:2L, externalId:'net-same', cidr:'192.168.5.0/24', vlanId:100)

        when: 'execute invoked'
        sync.execute()

        then: 'server resolved'
        1 * mockComputeServerService.find(_ as DataQuery) >> server
        and: 'api options fetched'
        1 * apiService.getScvmmZoneAndHypervisorOpts(mockContext, cloud, server) >> scvmmOpts
        and: 'networks listed'
        1 * apiService.listNoIsolationVLans(scvmmOpts) >> listResults
        and: 'existing identity projections loaded'
        1 * mockNetworkService.listIdentityProjections(_ as DataQuery) >> Observable.fromIterable([projUpdate, projSame, projDelete])
        and: 'details for update loaded'
        1 * mockNetworkService.listById([1L,2L]) >> Observable.fromIterable([existingUpdate, existingSame])
        and: 'stale network deleted'
        1 * mockNetworkService.remove({ it*.externalId == ['net-delete'] }) >> Single.just(true)
        and: 'updated network saved (only one) with new cidr & vlanId'
        1 * mockNetworkService.save({ List<Network> nets ->
            assert nets.size() == 1
            assert nets[0].id == 1L
            assert nets[0].cidr == '172.16.0.0/20'
            assert nets[0].vlanId == 300
            true
        }) >> Single.just(true)
        and: 'new network created'
        1 * mockNetworkService.create({ List<Network> nets ->
            assert nets.size() == 1
            def n = nets[0]
            assert n.externalId == 'net-new'
            assert n.cidr == '10.10.10.0/24'
            assert n.vlanId == 500
            assert n.code == "scvmm.vlan.network.${cloud.id}.${server.id}.net-new"
            assert n.category == "scvmm.vlan.network.${cloud.id}.${server.id}"
            assert n.refType == 'ComputeZone'
            assert n.refId == cloud.id
            assert n.owner == cloud.owner
            true
        }) >> Single.just(true)
        and:
        notThrown(Exception)
    }

    def "execute stops on API failure"() {
        given:
        def scvmmOpts = [:]
        when:
        sync.execute()
        then:
        1 * mockComputeServerService.find(_ as DataQuery) >> server
        1 * apiService.getScvmmZoneAndHypervisorOpts(mockContext, cloud, server) >> scvmmOpts
        1 * apiService.listNoIsolationVLans(scvmmOpts) >> [success:false, error:'fail']
        0 * mockNetworkService.listIdentityProjections(_)
        0 * mockNetworkService.create(_)
        0 * mockNetworkService.save(_)
        0 * mockNetworkService.remove(_)
        notThrown(Exception)
    }

    def "execute handles success true but networks null"() {
        given:
        def scvmmOpts = [:]
        when:
        sync.execute()
        then:
        1 * mockComputeServerService.find(_ as DataQuery) >> server
        1 * apiService.getScvmmZoneAndHypervisorOpts(mockContext, cloud, server) >> scvmmOpts
        1 * apiService.listNoIsolationVLans(scvmmOpts) >> [success:true, networks:null]
        0 * mockNetworkService.listIdentityProjections(_)
        notThrown(Exception)
    }

    def "execute handles missing server"() {
        when:
        sync.execute()
        then:
        1 * mockComputeServerService.find(_ as DataQuery) >> null
        1 * apiService.getScvmmZoneAndHypervisorOpts(mockContext, cloud, null) >> [:]
        1 * apiService.listNoIsolationVLans(_) >> [success:false]
        0 * mockNetworkService.listIdentityProjections(_)
        notThrown(Exception)
    }

    def "execute swallows exception from api"() {
        given:
        def scvmmOpts = [:]
        when:
        sync.execute()
        then:
        1 * mockComputeServerService.find(_ as DataQuery) >> server
        1 * apiService.getScvmmZoneAndHypervisorOpts(mockContext, cloud, server) >> scvmmOpts
        1 * apiService.listNoIsolationVLans(scvmmOpts) >> { throw new RuntimeException('boom') }
        notThrown(RuntimeException)
    }

    @Unroll
    def "updateMatchedNetworks updates correctly (cidrChanged=#cidrChanged vlanChanged=#vlanChanged)"() {
        given:
        def existing = new Network(id:10L, externalId:'x', cidr: origCidr, vlanId: origVlan)
        def master = [Subnet: newCidr, VLanID: newVlan]
        // Correctly construct UpdateItem using named arguments matching other specs
        def updateItem = new SyncTask.UpdateItem<Network, Map>(
                existingItem: existing,
                masterItem: master
        )
        def updateList = [updateItem]
        // mock save when needed using explicit parameter instead of it[0]
        if (cidrChanged || vlanChanged) {
            1 * mockNetworkService.save({ List<Network> nets ->
                assert nets.size() == 1
                assert nets[0].id == 10L
                true
            }) >> Single.just(true)
        } else {
            0 * mockNetworkService.save(_)
        }

        when:
        sync.updateMatchedNetworks(updateList as List<SyncTask.UpdateItem<Network,Map>>)

        then:
        existing.cidr == expectedCidr
        existing.vlanId == expectedVlan

        where:
        origCidr         | newCidr          | origVlan | newVlan || cidrChanged | vlanChanged || expectedCidr       | expectedVlan
        '192.168.1.0/24' | '192.168.1.0/24' | 100      | 100     || false       | false       || '192.168.1.0/24'    | 100
        '192.168.1.0/24' | '10.0.0.0/16'    | 100      | 100     || true        | false       || '10.0.0.0/16'       | 100
        '192.168.1.0/24' | '192.168.1.0/24' | 100      | 200     || false       | true        || '192.168.1.0/24'    | 200
        '192.168.1.0/24' | '10.0.0.0/16'    | 100      | 200     || true        | true        || '10.0.0.0/16'       | 200
    }

    def "addMissingNetworks creates networks properly"() {
        given:
        def networkType = new NetworkType(code:'scvmmVLANNetwork')
        def addList = [
                [ID:'n1', Name:'Net 1', Subnet:'10.1.0.0/24', VLanID:101],
                [ID:'n2', Name:'Net 2', Subnet:'10.2.0.0/24', VLanID:102]
        ]
        1 * mockNetworkService.create({ List<Network> nets ->
            assert nets.size() == 2
            assert nets*.externalId.sort() == ['n1','n2']
            nets.each { n ->
                assert n.code.startsWith("scvmm.vlan.network.${cloud.id}.")
                assert n.category == "scvmm.vlan.network.${cloud.id}.${server.id}" // server id used
            }
            true
        }) >> Single.just(true)

        when:
        sync.addMissingNetworks(addList, networkType, server)

        then:
        notThrown(Exception)
    }

    def "addMissingNetworks allows null fields in items"() {
        given:
        def networkType = new NetworkType(code:'scvmmVLANNetwork')
        def addList = [[ID:'null-net', Name:null, Subnet:null, VLanID:null]]
        1 * mockNetworkService.create({ List<Network> nets ->
            assert nets.size() == 1
            def n = nets[0]
            assert n.externalId == 'null-net'
            assert n.name == null
            assert n.cidr == null
            assert n.vlanId == null
            true
        }) >> Single.just(true)
        when:
        sync.addMissingNetworks(addList, networkType, server)
        then:
        notThrown(Exception)
    }

}
