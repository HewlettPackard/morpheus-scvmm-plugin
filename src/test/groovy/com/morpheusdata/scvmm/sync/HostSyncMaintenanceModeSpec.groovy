// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.MorpheusSynchronousOsTypeService
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.OsType
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Unroll

/**
 * SCVMM reports a drained host through its OverallStateString ("In Maintenance Mode"). Host sync has to carry that
 * onto ComputeServer.maintenanceMode on every sync so placement can skip the host.
 */
class HostSyncMaintenanceModeSpec extends Specification {

    MorpheusContext context = Mock()
    MorpheusServices services = Mock()
    MorpheusAsyncServices asyncServices = Mock()
    MorpheusComputeServerService asyncComputeServerService = Mock()
    MorpheusSynchronousOsTypeService osTypeService = Mock()

    Cloud cloud = new Cloud(id: 1L)
    HostSync hostSync

    def setup() {
        context.services >> services
        context.async >> asyncServices
        asyncServices.computeServer >> asyncComputeServerService
        services.osType >> osTypeService
        hostSync = new HostSync(cloud, null, context)
    }

    @Unroll
    def "updateHostStats: status '#status' with prior #prior -> maintenanceMode #expected"() {
        given:
        def host = host(prior)

        when:
        hostSync.updateHostStats(host, hostMap(status))

        then:
        1 * asyncComputeServerService.save(host) >> Single.just(host)
        host.maintenanceMode == expected

        where:
        status                | prior || expected
        'In Maintenance Mode' | false || true
        'in maintenance mode' | false || true
        ' In Maintenance Mode '| null || true
        'OK'                  | true  || false
        'Needs Attention'     | true  || false
        'OK'                  | null  || false
        null                  | true  || true   // absent status must not clear the flag
        ''                    | true  || true
        null                  | null  || null
    }

    def "a host whose cluster and OS are unchanged still has its maintenance state refreshed"() {
        given:
        def os = new OsType(code: 'windows.server.2022')
        osTypeService.find(_) >> os
        def host = host(false)
        host.serverOs = os
        def update = new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: host, masterItem: hostMap('In Maintenance Mode'))

        when:
        hostSync.updateMatchedHosts([update], [])

        then:
        1 * asyncComputeServerService.save(host) >> Single.just(host)
        host.maintenanceMode == true
    }

    def "a host leaving maintenance mode becomes eligible again on the next sync"() {
        given:
        def os = new OsType(code: 'windows.server.2022')
        osTypeService.find(_) >> os
        def host = host(true)
        host.serverOs = os
        def update = new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: host, masterItem: hostMap('OK'))

        when:
        hostSync.updateMatchedHosts([update], [])

        then:
        1 * asyncComputeServerService.save(host) >> Single.just(host)
        host.maintenanceMode == false
    }

    private static ComputeServer host(Boolean maintenanceMode) {
        def server = new ComputeServer(id: 1L, name: 'HYPERV-A', hostname: 'HYPERV-A', uniqueId: 'host-1', externalId: 'host-1')
        server.capacityInfo = new ComputeCapacityInfo()
        server.maintenanceMode = maintenanceMode
        return server
    }

    private static Map hostMap(String status) {
        [
                id             : 'host-1',
                name           : 'HYPERV-A',
                computerName   : 'HYPERV-A',
                status         : status,
                hyperVState    : 'Running',
                os             : 'Windows Server 2022 Datacenter',
                cpuCount       : 1,
                coresPerCpu    : 32,
                totalMemory    : 68407640064L,
                availableMemory: 53710,
                totalStorage   : 1000L,
                usedStorage    : 100L
        ]
    }
}
