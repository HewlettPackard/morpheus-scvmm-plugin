// Copyright 2026 Hewlett Packard Enterprise Development LP
package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.scvmm.error.ScvmmConnectionException
import spock.lang.Specification

class ScvmmCloudProviderRefreshSpec extends Specification {
    MorpheusContext context = Mock()
    MorpheusAsyncServices async = Mock()
    MorpheusServices services = Mock()
    MorpheusCloudService clouds = Mock()
    MorpheusSynchronousComputeServerService servers = Mock()
    ScvmmApiService api = Mock()
    ScvmmCloudProvider provider = Spy(ScvmmCloudProvider, constructorArgs: [new ScvmmPlugin(), context])
    Cloud cloud = new Cloud(id: 1L, name: 'vmm')
    ComputeServer controller = new ComputeServer(id: 2L, status: 'provisioned', powerState: 'on')

    def setup() {
        provider.apiService = api
        context.async >> async
        context.services >> services
        async.cloud >> clouds
        services.computeServer >> servers
        provider.getScvmmController(cloud) >> controller
        provider.checkCommunication(cloud, controller) >> [success: true]
        api.getScvmmZoneAndHypervisorOpts(*_) >> [sshHost: 'vmm']
        GroovyMock(ConnectionUtils, global: true)
        ConnectionUtils.testHostConnectivity(*_) >> true
    }

    def "refresh preserves worker isolation and returns the appropriate cloud status"() {
        given:
        List<String> ran = []
        provider.runSyncWorkers(cloud, { it.size() == 10 }) >> { Cloud c, List<Map> workers ->
            workers.collect { Map worker ->
                provider.runSyncWorker(c, worker.name as String, {
                    ran << worker.name
                    if (worker.name == 'IpPoolsSync') {
                        throw failure
                    }
                    [success: true]
                })
            }
        }

        when:
        def response = provider.refresh(cloud)

        then:
        ran.size() == 10
        ran.last() == 'VirtualMachineSync'
        !response.success
        response.msg.contains('IpPoolsSync')
        response.data.status == status
        response.data.workers.count { !it.success } == 1
        response.data.workers.last().success
        1 * clouds.updateCloudStatus(cloud, Cloud.Status.syncing, null, _)
        1 * clouds.updateCloudStatus(cloud, status, { it.contains('IpPoolsSync') }, _)

        where:
        failure                                                                              | status
        new IllegalStateException('invalid pool data')                                        | Cloud.Status.warning
        new ScvmmConnectionException('WinRM connection lost', 'vmm', 5985)                    | Cloud.Status.offline
    }

    def "refresh marks an unreachable listener offline without running inventory"() {
        when:
        def response = provider.refresh(cloud)

        then:
        !response.success
        response.msg.contains('vmm')
        response.msg.contains('5985')
        response.data.status == Cloud.Status.offline
        1 * ConnectionUtils.testHostConnectivity(*_) >> false
        0 * provider.runSyncWorkers(*_)
        1 * clouds.updateCloudStatus(cloud, Cloud.Status.offline, _, _)
    }
}
