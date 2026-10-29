// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.BulkCreateResult
import com.morpheusdata.core.MorpheusResourcePermissionService
import com.morpheusdata.core.cloud.MorpheusCloudPoolService
import com.morpheusdata.core.cloud.MorpheusDatastoreService
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.network.MorpheusNetworkPoolRangeService
import com.morpheusdata.core.network.MorpheusNetworkPoolService
import com.morpheusdata.core.network.MorpheusNetworkSubnetService
import com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkSubnetService
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.NetworkPoolRange
import com.morpheusdata.model.NetworkPoolServer
import com.morpheusdata.model.NetworkSubnet
import com.morpheusdata.model.NetworkSubnetType
import com.morpheusdata.model.ResourcePermission
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.morpheusdata.model.projection.NetworkIdentityProjection
import com.morpheusdata.model.projection.NetworkPoolIdentityProjection
import com.morpheusdata.model.projection.NetworkSubnetIdentityProjection
import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.scvmm.support.Domain
import com.morpheusdata.scvmm.support.ScvmmSpec
import com.morpheusdata.scvmm.support.VmmPayloads
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single

/**
 * The network, pool and cluster syncs that had no spec — NetworkSync, IsolationNetworkSync,
 * ClustersSync, CloudCapabilityProfilesSync and IpPoolsSync (execute + linkNetworkToPool / updateNetworkForPool).
 * Grouped in one class to limit per-class fork overhead. Each sync's private {@code apiService} is
 * replaced with a Mock via {@code .@apiService}; SyncTask runs for real over Observables.
 */
class NetworkAndPoolSyncsSpec extends ScvmmSpec {

    ScvmmApiService apiService = Mock()
    MorpheusNetworkSubnetService asyncSubnetService = Mock()
    MorpheusSynchronousNetworkSubnetService subnetService = Mock()
    MorpheusCloudPoolService asyncPoolService = Mock()
    MorpheusSynchronousCloudPoolService poolService = Mock()
    MorpheusDatastoreService asyncDatastoreService = Mock()
    MorpheusSynchronousDatastoreService datastoreService = Mock()
    MorpheusResourcePermissionService asyncPermissionService = Mock()
    MorpheusSynchronousResourcePermissionService permissionService = Mock()
    MorpheusNetworkPoolService asyncNetworkPoolService = Mock()
    MorpheusNetworkPoolRangeService asyncPoolRangeService = Mock()

    Account owner = new Account(id: 3L, masterAccount: false)
    Cloud cloud = Domain.cloud(id: 1L, owner: owner, account: owner)
    ComputeServer controller = Domain.controller(id: 2L)

    def setup() {
        asyncCloudService.getNetwork() >> asyncNetworkService
        cloudService.getNetwork() >> networkService
        asyncServices.getNetworkSubnet() >> asyncSubnetService
        services.getNetworkSubnet() >> subnetService
        asyncCloudService.getPool() >> asyncPoolService
        cloudService.getPool() >> poolService
        asyncCloudService.getDatastore() >> asyncDatastoreService
        cloudService.getDatastore() >> datastoreService
        asyncServices.getResourcePermission() >> asyncPermissionService
        services.getResourcePermission() >> permissionService
        asyncNetworkService.getPool() >> asyncNetworkPoolService
        asyncNetworkPoolService.getPoolRange() >> asyncPoolRangeService
        computeServerService.find(_ as DataQuery) >> controller
        apiService.getScvmmZoneAndHypervisorOpts(*_) >> [sshHost: 'scvmm.local']
    }

    private NetworkSync networkSync() {
        def s = new NetworkSync(context, cloud)
        s.@apiService = apiService
        s
    }

    private static NetworkIdentityProjection netProj(Long id, String externalId) {
        new NetworkIdentityProjection(id: id, externalId: externalId)
    }

    // ================================================================ NetworkSync

    def "NetworkSync adds a network per VM network with a subnet built from its first site"() {
        given:
        def item = VmmPayloads.network(ID: 'vmnet-1', Name: 'vmnet-1', Subnets: [VmmPayloads.vlan(Subnet: '10.0.100.0/24', VLanID: 100)])
        apiService.listNetworks(_) >> [success: true, networks: [item]]
        asyncNetworkService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
        List<Network> created
        NetworkSubnet subnet

        when:
        networkSync().execute()

        then:
        1 * asyncNetworkService.bulkCreate(_ as List) >> { args -> List l = args[0]; created = l; Single.just(new BulkCreateResult(null, null, l, [])) }
        1 * asyncSubnetService.create(_ as List, { it.is(created[0]) }) >> { List l, Network n -> subnet = l[0]; Single.just(true) }
        created[0].code == 'scvmm.network.1.2.vmnet-1'
        created[0].category == 'scvmm.network.1.2'
        created[0].externalId == 'vmnet-1'
        created[0].owner.is(owner)
        created[0].cidr == null
        subnet.cidr == '10.0.100.0/24'
        subnet.netmask == '255.255.255.0'
        subnet.dhcpStart == '10.0.100.1'
        subnet.dhcpEnd == '10.0.100.254'
        subnet.externalId == 'vmnet-1'
    }

    def "NetworkSync takes the new subnet's VLAN from the network item, not from its site"() {
        // Pinned: subnetConfig.vlanId = cloudItem.VLanID (the VM network map has no VLanID), so it is always null.
        given:
        apiService.listNetworks(_) >> [success: true, networks: [VmmPayloads.network(Subnets: [VmmPayloads.vlan(VLanID: 100)])]]
        asyncNetworkService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
        asyncNetworkService.bulkCreate(_ as List) >> { args -> List l = args[0]; Single.just(new BulkCreateResult(null, null, l, [])) }
        NetworkSubnet subnet

        when:
        networkSync().execute()

        then:
        1 * asyncSubnetService.create(_ as List, _ as Network) >> { List l, Network n -> subnet = l[0]; Single.just(true) }
        subnet.vlanId == null
    }

    def "NetworkSync removes networks that VMM no longer reports"() {
        given:
        apiService.listNetworks(_) >> [success: true, networks: [VmmPayloads.network(ID: 'vmnet-1')]]
        def keep = netProj(1L, 'vmnet-1'), gone = netProj(2L, 'vmnet-gone')
        asyncNetworkService.listIdentityProjections(_ as DataQuery) >> Observable.just(keep, gone)
        asyncNetworkService.listById([1L]) >> Observable.just(new Network(id: 1L, externalId: 'vmnet-1', subnets: []))
        asyncSubnetService.list(_ as DataQuery) >> Observable.empty()

        when:
        networkSync().execute()

        then:
        1 * asyncNetworkService.remove([gone]) >> Single.just(true)
    }

    def "NetworkSync removes networks missing from a partial listNetworks page (partial listing is reported as success)"() {
        // listNetworks returns success:true with only the pages read before a failing page, so every network on the
        // later pages is treated as deleted in VMM.
        given:
        apiService.listNetworks(_) >> [success: true, networks: [VmmPayloads.network(ID: 'page-1-net')]]
        def pageOne = netProj(1L, 'page-1-net'), pageTwo = netProj(2L, 'page-2-net')
        asyncNetworkService.listIdentityProjections(_ as DataQuery) >> Observable.just(pageOne, pageTwo)
        asyncNetworkService.listById(_) >> Observable.just(new Network(id: 1L, externalId: 'page-1-net', subnets: []))
        asyncSubnetService.list(_ as DataQuery) >> Observable.empty()

        when:
        networkSync().execute()

        then:
        1 * asyncNetworkService.remove([pageTwo]) >> Single.just(true)
    }

    def "NetworkSync leaves existing networks alone when listNetworks #why"() {
        given:
        apiService.listNetworks(_) >> result

        when:
        networkSync().execute()

        then:
        0 * asyncNetworkService.listIdentityProjections(_)
        0 * asyncNetworkService.remove(_)

        where:
        why            | result
        'fails'        | [success: false, networks: []]
        'is empty'     | [success: true, networks: []]
    }

    def "NetworkSync rewrites a changed subnet's CIDR, adds new sites and removes vanished ones without touching the network"() {
        given:
        def oldSubnet = new NetworkSubnet(id: 10L, externalId: 'subnet-1-100', name: 'subnet-1', cidr: '10.0.0.0/24', vlanId: 100)
        def network = new Network(id: 1L, externalId: 'vmnet-1', cidr: '192.168.0.0/16', subnets: [oldSubnet, new NetworkSubnet(id: 11L)])
        def item = VmmPayloads.network(ID: 'vmnet-1', Subnets: [VmmPayloads.vlan(ID: 'subnet-1-100', Subnet: '10.0.100.0/24'),
                                                               VmmPayloads.vlan(ID: 'subnet-2-0', Name: 'subnet-2', Subnet: '10.0.200.0/24', VLanID: 0)])
        apiService.listNetworks(_) >> [success: true, networks: [item]]
        asyncNetworkService.listIdentityProjections(_ as DataQuery) >> Observable.just(netProj(1L, 'vmnet-1'))
        asyncNetworkService.listById([1L]) >> Observable.just(network)
        def goneProj = new NetworkSubnetIdentityProjection(id: 11L, externalId: 'subnet-gone')
        asyncSubnetService.list(_ as DataQuery) >> Observable.just(new NetworkSubnetIdentityProjection(id: 10L, externalId: 'subnet-1-100'), goneProj)
        asyncSubnetService.listById([10L]) >> Observable.just(oldSubnet)
        List<NetworkSubnet> added

        when:
        networkSync().execute()

        then:
        1 * asyncSubnetService.save([oldSubnet]) >> Single.just(true)
        1 * asyncSubnetService.remove([goneProj]) >> Single.just(true)
        1 * asyncSubnetService.create(_ as List, network) >> { List l, Network n -> added = l; Single.just(true) }
        oldSubnet.cidr == '10.0.100.0/24'
        oldSubnet.getConfigProperty('subnetCidr') == '10.0.100.0/24'
        oldSubnet.netmask == '255.255.255.0'
        added*.cidr == ['10.0.200.0/24']
        added[0].status == NetworkSubnet.Status.AVAILABLE
        network.cidr == '192.168.0.0/16'
        0 * asyncNetworkService.save(_)
    }

    def "NetworkSync does not save an unchanged subnet"() {
        given:
        def subnet = new NetworkSubnet(id: 10L, externalId: 's1', name: 'subnet-1', cidr: '10.0.100.0/24', subnetAddress: '10.0.100.0/24',
                netmask: '255.255.255.0', dhcpStart: '10.0.100.1', dhcpEnd: '10.0.100.254', vlanId: 100)
        subnet.setConfigProperty('subnetName', 'subnet-1')
        subnet.setConfigProperty('subnetCidr', '10.0.100.0/24')
        apiService.listNetworks(_) >> [success: true, networks: [VmmPayloads.network(Subnets: [VmmPayloads.vlan(ID: 's1')])]]
        asyncNetworkService.listIdentityProjections(_ as DataQuery) >> Observable.just(netProj(1L, 'vmnet-1'))
        asyncNetworkService.listById(_) >> Observable.just(new Network(id: 1L, externalId: 'vmnet-1', subnets: [subnet]))
        asyncSubnetService.list(_ as DataQuery) >> Observable.just(new NetworkSubnetIdentityProjection(id: 10L, externalId: 's1'))
        asyncSubnetService.listById(_) >> Observable.just(subnet)

        when:
        networkSync().execute()

        then:
        0 * asyncSubnetService.save(_)
    }

    // ================================================================ IsolationNetworkSync

    private IsolationNetworkSync isolationSync() {
        new IsolationNetworkSync(context, cloud, apiService)
    }

    def "IsolationNetworkSync adds VLAN networks with their CIDR and VLAN id"() {
        given:
        apiService.listNoIsolationVLans(_) >> [success: true, networks: [VmmPayloads.vlan(ID: 'ln-1-100', Name: 'logical-1 100', Subnet: '10.0.100.0/24', VLanID: 100)]]
        asyncNetworkService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
        List<Network> created

        when:
        isolationSync().execute()

        then:
        1 * asyncNetworkService.create(_ as List) >> { args -> List l = args[0]; created = l; Single.just(true) }
        created[0].code.toString() == 'scvmm.vlan.network.1.2.ln-1-100'
        created[0].cidr == '10.0.100.0/24'
        created[0].vlanId == 100
        created[0].type.code == 'scvmmVLANNetwork'
    }

    def "IsolationNetworkSync updates a changed CIDR/VLAN, skips unchanged networks and removes vanished ones"() {
        given:
        def changed = new Network(id: 1L, externalId: 'a', cidr: '10.0.0.0/24', vlanId: 5)
        def same = new Network(id: 2L, externalId: 'b', cidr: '10.0.2.0/24', vlanId: 2)
        apiService.listNoIsolationVLans(_) >> [success: true, networks: [VmmPayloads.vlan(ID: 'a', Subnet: '10.0.1.0/24', VLanID: 6),
                                                                          VmmPayloads.vlan(ID: 'b', Subnet: '10.0.2.0/24', VLanID: 2)]]
        def gone = netProj(3L, 'c')
        asyncNetworkService.listIdentityProjections(_ as DataQuery) >> Observable.just(netProj(1L, 'a'), netProj(2L, 'b'), gone)
        asyncNetworkService.listById(_) >> Observable.just(changed, same)

        when:
        isolationSync().execute()

        then:
        1 * asyncNetworkService.save([changed]) >> Single.just(true)
        1 * asyncNetworkService.remove([gone]) >> Single.just(true)
        changed.cidr == '10.0.1.0/24'
        changed.vlanId == 6
    }

    def "IsolationNetworkSync does nothing when listNoIsolationVLans #why"() {
        given:
        apiService.listNoIsolationVLans(_) >> result

        when:
        isolationSync().execute()

        then:
        0 * asyncNetworkService._

        where:
        why        | result
        'fails'    | [success: false]
        'is empty' | [success: true, networks: []]
    }

    // ================================================================ ClustersSync

    private ClustersSync clustersSync() {
        def s = new ClustersSync(context, cloud)
        s.@apiService = apiService
        s
    }

    def "ClustersSync adds clusters as CloudPools with permissions, honouring the cloud's cluster scope"() {
        given:
        cloud.setConfigProperty('cluster', 'cluster-2')
        apiService.listClusters(_) >> [success: true, clusters: [VmmPayloads.cluster(id: 'cluster-1'),
                                                                  VmmPayloads.cluster(id: 'cluster-2', name: 'c2', sharedVolumes: ['Volume1'])]]
        asyncPoolService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
        List<CloudPool> created
        List<ResourcePermission> perms

        when:
        clustersSync().execute()

        then:
        1 * asyncPoolService.bulkCreate(_ as List) >> { args -> List l = args[0]; created = l; Single.just(new BulkCreateResult(null, null, l, [])) }
        1 * asyncPoolService.bulkSave(_ as List) >> Single.just(true)
        1 * asyncPermissionService.bulkCreate(_ as List) >> { args -> List l = args[0]; perms = l; Single.just(new BulkCreateResult(null, null, l, [])) }
        created*.externalId == ['cluster-2']
        created[0].internalId == 'c2'
        created[0].code == 'scvmm.cluster.1.cluster-2'
        created[0].type == 'Cluster'
        created[0].readOnly == false
        created[0].getConfigProperty('sharedVolumes') == ['Volume1']
        perms*.uuid == ['cluster-2']
        perms[0].morpheusResourceType == 'ComputeZonePool'
    }

    def "ClustersSync waits for #count consecutive empty shared-volume reports before clearing them"() {
        given:
        def pool = new CloudPool(id: 1L, externalId: 'cluster-1')
        pool.setConfigProperty('sharedVolumes', ['Volume1'])
        pool.setConfigProperty('nullSharedVolumeSyncCount', count)
        apiService.listClusters(_) >> [success: true, clusters: [VmmPayloads.cluster(sharedVolumes: [null])]]
        asyncPoolService.listIdentityProjections(_ as DataQuery) >> Observable.just(new CloudPoolIdentity(id: 1L, externalId: 'cluster-1'))
        asyncPoolService.listById(_) >> Observable.just(pool)
        owner.masterAccount = true

        when:
        clustersSync().execute()

        then:
        1 * asyncPoolService.bulkSave([pool]) >> Single.just(true)
        pool.getConfigProperty('sharedVolumes') == expected
        pool.getConfigProperty('nullSharedVolumeSyncCount')?.toLong() == nextCount

        where:
        count || expected      | nextCount
        0     || ['Volume1']   | 1L
        4     || ['Volume1']   | 5L
        5     || [null]        | 5L
    }

    def "ClustersSync takes reported shared volumes immediately and resets the empty counter"() {
        given:
        def pool = new CloudPool(id: 1L, externalId: 'cluster-1')
        pool.setConfigProperty('nullSharedVolumeSyncCount', 3)
        apiService.listClusters(_) >> [success: true, clusters: [VmmPayloads.cluster(sharedVolumes: ['Volume2'])]]
        asyncPoolService.listIdentityProjections(_ as DataQuery) >> Observable.just(new CloudPoolIdentity(id: 1L, externalId: 'cluster-1'))
        asyncPoolService.listById(_) >> Observable.just(pool)
        owner.masterAccount = true

        when:
        clustersSync().execute()

        then:
        1 * asyncPoolService.bulkSave([pool]) >> Single.just(true)
        pool.getConfigProperty('sharedVolumes') == ['Volume2']
        pool.getConfigProperty('nullSharedVolumeSyncCount')?.toLong() == 0L
    }

    def "ClustersSync detaches servers, datastores, networks and child pools before removing a vanished cluster"() {
        given:
        apiService.listClusters(_) >> [success: true, clusters: [VmmPayloads.cluster(id: 'other')]]
        def gone = new CloudPoolIdentity(id: 9L, externalId: 'gone')
        asyncPoolService.listIdentityProjections(_ as DataQuery) >> Observable.just(gone)
        def gonePool = new CloudPool(id: 9L)
        def server = new ComputeServer(id: 1L, resourcePool: gonePool)
        def ds = new Datastore(id: 2L, zonePool: gonePool)
        def net = new Network(id: 3L, cloudPool: gonePool)
        def child = new CloudPool(id: 4L, parent: gonePool)
        computeServerService.list(_ as DataQuery) >> [server]
        datastoreService.list(_ as DataQuery) >> [ds]
        networkService.list(_ as DataQuery) >> [net]
        poolService.list(_ as DataQuery) >> [child]
        owner.masterAccount = true

        when:
        clustersSync().execute()

        then:
        1 * asyncComputeServerService.bulkSave([server]) >> Single.just(true)
        1 * asyncDatastoreService.bulkSave([ds]) >> Single.just(true)
        1 * asyncNetworkService.bulkSave([net]) >> Single.just(true)
        1 * asyncPoolService.bulkSave([child]) >> Single.just(true)
        1 * asyncPoolService.bulkRemove([gone]) >> Single.just(true)
        server.resourcePool == null
        ds.zonePool == null
        net.cloudPool == null
        child.parent == null
    }

    def "ClustersSync chooses owner pool defaults only for a non-master owner"() {
        given:
        owner.masterAccount = master
        apiService.listClusters(_) >> [success: true, clusters: [VmmPayloads.cluster()]]
        asyncPoolService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
        asyncPoolService.bulkCreate(_) >> { args -> List l = args[0]; Single.just(new BulkCreateResult(null, null, l, [])) }
        asyncPoolService.bulkSave(_) >> Single.just(true)
        asyncPermissionService.bulkCreate(_) >> Single.just(true)

        when:
        clustersSync().execute()

        then:
        finds * poolService.find(_ as DataQuery) >> null

        where:
        master || finds
        false  || 2
        true   || 0
    }

    def "chooseOwnerPoolDefaults keeps a writable default pool"() {
        given:
        def current = new CloudPool(id: 1L, defaultPool: true, readOnly: false)

        when:
        clustersSync().chooseOwnerPoolDefaults(owner)

        then:
        1 * poolService.find(_ as DataQuery) >> current
        0 * poolService.save(_)
        current.defaultPool
    }

    def "chooseOwnerPoolDefaults replaces a read-only default with the first writable pool"() {
        given:
        def readOnly = new CloudPool(id: 1L, defaultPool: true, readOnly: true)
        def writable = new CloudPool(id: 2L, defaultPool: false, readOnly: false)

        when:
        clustersSync().chooseOwnerPoolDefaults(owner)

        then:
        2 * poolService.find(_ as DataQuery) >>> [readOnly, writable]
        1 * poolService.save(readOnly)
        1 * poolService.save(writable)
        !readOnly.defaultPool
        writable.defaultPool
    }

    def "ClustersSync does nothing when listClusters #why"() {
        given:
        apiService.listClusters(_) >> result

        when:
        clustersSync().execute()

        then:
        0 * asyncPoolService._
        0 * poolService._

        where:
        why        | result
        'fails'    | [success: false, clusters: []]
        'is empty' | [success: true, clusters: []]
    }

    // ================================================================ CloudCapabilityProfilesSync

    private CloudCapabilityProfilesSync capabilitySync() {
        def s = new CloudCapabilityProfilesSync(context, cloud)
        s.@apiService = apiService
        s
    }

    def "CloudCapabilityProfilesSync stores the SCVMM cloud's profiles when the cloud has a regionCode"() {
        given:
        def scvmmCloud = Domain.cloud(id: 1L, regionCode: 'cloud-1')
        cloudService.get(1L) >> scvmmCloud
        apiService.getCloud(_) >> [success: true, cloud: [ID: 'cloud-1', CapabilityProfiles: ['Hyper-V', 'Gold']]]

        when:
        capabilitySync().execute()

        then:
        1 * cloudService.save(scvmmCloud)
        0 * apiService.getCapabilityProfiles(_)
        scvmmCloud.getConfigProperty('capabilityProfiles') == ['Hyper-V', 'Gold']
    }

    def "CloudCapabilityProfilesSync stores every profile name when the cloud has no regionCode"() {
        given:
        def scvmmCloud = Domain.cloud(id: 1L)
        cloudService.get(1L) >> scvmmCloud
        apiService.getCapabilityProfiles(_) >> [success: true, capabilityProfiles: [VmmPayloads.capabilityProfile(), VmmPayloads.capabilityProfile(Name: 'Gold')]]

        when:
        capabilitySync().execute()

        then:
        1 * cloudService.save(scvmmCloud)
        scvmmCloud.getConfigProperty('capabilityProfiles') == ['Hyper-V', 'Gold']
    }

    def "CloudCapabilityProfilesSync keeps the stored profiles when VMM returns #why"() {
        given:
        def scvmmCloud = Domain.cloud(id: 1L, regionCode: regionCode)
        cloudService.get(1L) >> scvmmCloud
        apiService.getCloud(_) >> result
        apiService.getCapabilityProfiles(_) >> result

        when:
        capabilitySync().execute()

        then:
        0 * cloudService.save(_)
        noExceptionThrown()

        where:
        why                    | regionCode | result
        'no cloud profiles'    | 'cloud-1'  | [success: true, cloud: [CapabilityProfiles: []]]
        'a failure'            | 'cloud-1'  | [success: false]
        'no profiles'          | null       | [success: true, capabilityProfiles: null]
        'an exception'         | null       | { throw new RuntimeException('winrm') }
    }

    // ================================================================ IpPoolsSync

    NetworkPoolServer poolServer = new NetworkPoolServer(id: 40L)

    private IpPoolsSync ipPoolsSync() {
        def s = new IpPoolsSync(context, cloud, poolServer)
        s.@apiService = apiService
        s
    }

    def "IpPoolsSync adds a pool with range and permission and links its VM network and subnet"() {
        given:
        def subnet = new NetworkSubnet(id: 7L, externalId: 'subnet-1')
        def network = new Network(id: 5L, externalId: 'vmnet-1', name: 'vmnet-1', subnets: [subnet])
        networkService.list(_ as DataQuery) >> [network]
        apiService.listNetworkIPPools(_) >> [success: true, ipPools: [VmmPayloads.ipPool(DNSServers: ['10.0.0.53', '10.0.0.54'])],
                                             networkMapping: [[ID: 'vmnet-1']]]
        asyncNetworkPoolService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
        subnetService.get(7L) >> subnet
        List<NetworkPool> created
        List<NetworkPoolRange> ranges

        when:
        ipPoolsSync().execute()

        then:
        1 * asyncNetworkPoolService.create(40L, _ as List) >> { Long id, List l -> created = l; l.eachWithIndex { p, i -> p.id = 100L + i }; Single.just(true) }
        1 * asyncPoolRangeService.bulkCreate(_ as List) >> { args -> List l = args[0]; ranges = l; Single.just(true) }
        1 * asyncNetworkPoolService.bulkSave(_ as List) >> Single.just(true)
        1 * asyncPermissionService.bulkCreate({ it*.morpheusResourceType == ['NetworkPool'] }) >> Single.just(true)
        1 * asyncNetworkService.save(network) >> Single.just(network)
        1 * asyncSubnetService.save(subnet) >> Single.just(subnet)
        with(created[0]) {
            externalId == 'pool-1'
            displayName == 'pool-1 (10.0.100.0/24)'
            gateway == '10.0.100.1'
            netmask == '255.255.255.0'
            subnetAddress == '10.0.100.0'
            dnsServers == ['10.0.0.53', '10.0.0.54']
            ipCount == 100
            ipFreeCount == 90
        }
        ranges[0].startAddress == '10.0.100.10'
        ranges[0].addressCount == 100
        network.pool.is(created[0])
        network.gateway == '10.0.100.1'
        network.dnsPrimary == '10.0.0.53'
        network.dnsSecondary == '10.0.0.54'
        network.netmask == '255.255.255.0'
        network.allowStaticOverride
        subnet.pool.is(created[0])
        subnet.gateway == '10.0.100.1'
    }

    def "IpPoolsSync does nothing when listNetworkIPPools fails"() {
        given:
        networkService.list(_ as DataQuery) >> []
        apiService.listNetworkIPPools(_) >> [success: false, ipPools: [], networkMapping: []]

        when:
        ipPoolsSync().execute()

        then:
        0 * asyncNetworkPoolService._
    }

    def "IpPoolsSync removes pools VMM no longer reports"() {
        given:
        networkService.list(_ as DataQuery) >> []
        apiService.listNetworkIPPools(_) >> [success: true, ipPools: [], networkMapping: []]
        def gone = new NetworkPoolIdentityProjection(id: 9L, externalId: 'pool-gone')
        asyncNetworkPoolService.listIdentityProjections(_ as DataQuery) >> Observable.just(gone)

        when:
        ipPoolsSync().execute()

        then: 'an empty pool list removes every pool (no mass-delete guard)'
        1 * asyncNetworkPoolService.remove([gone]) >> Single.just(true)
    }

    def "IpPoolsSync updates a pool whose subnet, counts and DNS changed and creates its missing range"() {
        given:
        def existing = new NetworkPool(id: 9L, externalId: 'pool-1', name: 'pool-1', displayName: 'pool-1 (10.0.50.0/24)', ipCount: 100,
                ipFreeCount: 90, gateway: '10.0.100.1', netmask: '255.255.255.0', subnetAddress: '10.0.50.0', dnsServers: ['10.0.0.53'],
                poolServer: poolServer, ipRanges: [])
        networkService.list(_ as DataQuery) >> []
        apiService.listNetworkIPPools(_) >> [success: true, ipPools: [VmmPayloads.ipPool(Subnet: '10.0.100.0/23', AvailableAddresses: 80, DNSServers: '10.0.0.99')],
                                             networkMapping: []]
        asyncNetworkPoolService.listIdentityProjections(_ as DataQuery) >> Observable.just(new NetworkPoolIdentityProjection(id: 9L, externalId: 'pool-1'))
        asyncNetworkPoolService.listById([9L]) >> Observable.just(existing)
        permissionService.find(_ as DataQuery) >> new ResourcePermission()

        when:
        ipPoolsSync().execute()

        then:
        1 * asyncPoolRangeService.create(_ as NetworkPoolRange) >> Single.just(new NetworkPoolRange())
        (1.._) * asyncNetworkPoolService.save(existing) >> Single.just(existing)
        existing.displayName == 'pool-1 (10.0.100.0/23)'
        existing.netmask == '255.255.254.0'
        existing.subnetAddress == '10.0.100.0'
        existing.ipFreeCount == 80
        existing.dnsServers == ['10.0.0.99']
        0 * asyncPermissionService.create(_)
    }

    def "linkNetworkToPool is idempotent: an already linked network and subnet are not saved again"() {
        given:
        def pool = new NetworkPool(id: 9L, gateway: '10.0.100.1', netmask: '255.255.255.0', dnsServers: ['10.0.0.53'])
        def subnet = new NetworkSubnet(id: 7L, externalId: 'subnet-1', pool: pool, gateway: '10.0.100.1', netmask: '255.255.255.0', dnsPrimary: '10.0.0.53')
        def network = new Network(id: 5L, pool: pool, gateway: '10.0.100.1', netmask: '255.255.255.0', dnsPrimary: '10.0.0.53',
                allowStaticOverride: true, subnets: [subnet])
        subnetService.get(7L) >> subnet

        when:
        ipPoolsSync().linkNetworkToPool(network, pool, 'subnet-1')

        then:
        0 * asyncNetworkService.save(_)
        0 * asyncSubnetService.save(_)
    }

    def "linkNetworkToPool does not link a pool that has no id yet"() {
        // Pinned: the guard is `network.pool?.id != pool.id`, so an unsaved pool (id null) never links to an
        // unlinked network. addMissingIpPools relies on create() assigning ids to the passed objects.
        given:
        def pool = new NetworkPool(gateway: '10.0.100.1')
        def network = new Network(id: 5L, allowStaticOverride: true, subnets: [])

        when:
        ipPoolsSync().linkNetworkToPool(network, pool, null)

        then:
        1 * asyncNetworkService.save(network) >> Single.just(network)
        network.pool == null
        network.gateway == '10.0.100.1'
    }

    def "linkNetworkToPool keeps the network's DNS and gateway when the pool has none"() {
        given:
        def pool = new NetworkPool(id: 9L, dnsServers: [])
        def network = new Network(id: 5L, gateway: '192.168.1.1', dnsPrimary: '1.1.1.1', subnets: [])

        when:
        ipPoolsSync().linkNetworkToPool(network, pool, null)

        then:
        1 * asyncNetworkService.save(network) >> Single.just(network)
        network.pool.is(pool)
        network.gateway == '192.168.1.1'
        network.dnsPrimary == '1.1.1.1'
    }

    def "updateNetworkForPool does not link when #why"() {
        given:
        def network = new Network(id: 5L, externalId: 'vmnet-1', subnets: [])

        when:
        ipPoolsSync().updateNetworkForPool([network], new NetworkPool(id: 9L), networkId, null, mapping)

        then:
        0 * asyncNetworkService.save(_)
        network.pool == null

        where:
        why                               | networkId  | mapping
        'the pool reports no VM network'  | null       | [[ID: 'vmnet-1']]
        'the VM network is not mapped'    | 'vmnet-1'  | []
        'the network is not synced'       | 'vmnet-2'  | [[ID: 'vmnet-2']]
    }
}
