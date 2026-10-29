// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.backup.response.BackupExecutionResponse
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.scvmm.support.Domain
import com.morpheusdata.scvmm.support.GrailsCodecs
import com.morpheusdata.scvmm.support.ScvmmSpec
import spock.lang.Subject
import spock.lang.Unroll

class ScvmmBackupExecutionProviderSpec extends ScvmmSpec {

    ScvmmPlugin plugin = Mock()
    ScvmmApiService apiService = Mock()
    ScvmmProvisionProvider provisionProvider = Mock()

    @Subject
    ScvmmBackupExecutionProvider provider

    Cloud cloud = Domain.cloud(id: 1L)
    ComputeServer controller = Domain.controller()
    ComputeServer server = Domain.server(id: 10L, externalId: 'vm-ext-1', cloud: cloud)
    Backup backup = Domain.backup()
    Map executionConfig = [containerId: 20L, workingPath: 'C:\\morpheus\\backups', ipAddress: '10.0.0.9', backupResultId: 'set-from-config']
    Map opts = [backupSetId: 'set-1']

    def setup() {
        GrailsCodecs.install()
        provider = new ScvmmBackupExecutionProvider(plugin, context)
        provider.apiService = apiService
        provider.provisionProvider = provisionProvider
        provisionProvider.pickScvmmController(cloud) >> controller
    }

    def cleanup() {
        GrailsCodecs.uninstall()
    }

    // ---------------------------------------------------------------- construction

    def "constructor wires a real provision provider and api service around the given context"() {
        when:
        def fresh = new ScvmmBackupExecutionProvider(plugin, context)

        then:
        fresh.provisionProvider instanceof ScvmmProvisionProvider
        fresh.apiService instanceof ScvmmApiService
        fresh.apiService.morpheusContext.is(context)
        fresh.morpheus.is(context)
        fresh.plugin.is(plugin)
    }

    // ---------------------------------------------------------------- executeBackup

    def "executeBackup snapshots the VM and records the snapshot on a SUCCEEDED result"() {
        given:
        BackupResult result = Domain.backupResult(startDate: new Date(System.currentTimeMillis() - 5000))
        Map snapOpts = [zoneId: 1L]
        String statusDuringSnapshot

        when:
        ServiceResponse<BackupExecutionResponse> rtn = provider.executeBackup(backup, result, executionConfig, cloud, server, opts)

        then:
        1 * apiService.getScvmmZoneAndHypervisorOpts(context, cloud, controller) >> snapOpts
        1 * apiService.snapshotServer(_ as Map, 'vm-ext-1') >> { Map o, String vmId ->
            statusDuringSnapshot = result.status
            [success: true, snapshotId: o.snapshotId]
        }
        snapOpts.snapshotId ==~ /vm-ext-1\.\d{13}/
        statusDuringSnapshot == BackupResult.Status.IN_PROGRESS.toString()

        rtn.success
        rtn.data.updates
        rtn.data.backupResult.is(result)
        result.status == BackupResult.Status.SUCCEEDED.toString()
        result.backupSetId == 'set-1'
        result.executorIpAddress == '10.0.0.9'
        result.resultBase == 'scvmm'
        result.resultBucket == snapOpts.snapshotId
        result.resultArchive == snapOpts.snapshotId
        result.resultPath == 'C:\\morpheus\\backups'
        result.snapshotId == snapOpts.snapshotId
        result.sizeInMb == 0L
        result.getConfigProperty('snapshotId') == snapOpts.snapshotId
        result.getConfigProperty('vmId') == 'vm-ext-1'
        result.endDate != null
        result.durationMillis >= 5000L
    }

    def "executeBackup keeps an existing endDate and duration"() {
        given:
        Date end = new Date(0)
        BackupResult result = Domain.backupResult(startDate: new Date(), endDate: end, durationMillis: 7L)
        apiService.getScvmmZoneAndHypervisorOpts(*_) >> [:]
        apiService.snapshotServer(*_) >> [success: true, snapshotId: 'snap-1']

        when:
        provider.executeBackup(backup, result, executionConfig, cloud, server, opts)

        then:
        result.endDate.is(end)
        result.durationMillis == 7L
    }

    def "executeBackup sets endDate but leaves duration at 0 when there is no startDate"() {
        given:
        BackupResult result = Domain.backupResult()
        apiService.getScvmmZoneAndHypervisorOpts(*_) >> [:]
        apiService.snapshotServer(*_) >> [success: true, snapshotId: 'snap-1']

        when:
        provider.executeBackup(backup, result, executionConfig, cloud, server, opts)

        then:
        result.endDate != null
        result.durationMillis == 0L
    }

    def "executeBackup marks the result FAILED with a generated set id when option building throws"() {
        given:
        BackupResult result = Domain.backupResult()
        apiService.getScvmmZoneAndHypervisorOpts(*_) >> { throw new IllegalStateException('no controller') }

        when:
        def rtn = provider.executeBackup(backup, result, executionConfig - [backupResultId: 'set-from-config'], cloud, server, opts)

        then:
        0 * apiService.snapshotServer(*_)
        rtn.success == false
        rtn.msg == 'no controller'
        rtn.data.updates
        result.status == BackupResult.Status.FAILED.toString()
        result.errorOutput == GrailsCodecs.base64('Failed to execute backup')
        result.backupSetId
        result.backupSetId != 'set-from-config'
        result.executorIpAddress == '10.0.0.9'
    }

    def "executeBackup marks the result FAILED instead of throwing when the server is null"() {
        given:
        BackupResult result = Domain.backupResult()

        when:
        def rtn = provider.executeBackup(backup, result, executionConfig, cloud, null, opts)

        then:
        noExceptionThrown()
        0 * apiService.snapshotServer(*_)
        rtn.success == false
        result.status == BackupResult.Status.FAILED.toString()
    }

    // ---------------------------------------------------------------- deleteBackupResult

    def "deleteBackupResult deletes the snapshot using the cloud from config.cloudId"() {
        given:
        BackupResult result = Domain.backupResult(serverId: 10L, config: [cloudId: 1L, snapshotId: 'snap-1'])
        Map deleteOpts = [zoneId: 1L]

        when:
        def rtn = provider.deleteBackupResult(result, [:])

        then:
        1 * cloudService.get(1L) >> cloud
        1 * computeServerService.get(10L) >> server
        1 * apiService.getScvmmZoneAndHypervisorOpts(context, cloud, controller) >> deleteOpts
        1 * apiService.deleteSnapshot(deleteOpts, 'vm-ext-1', 'snap-1') >> [success: true, snapshotId: 'snap-1']
        rtn.success
    }

    def "deleteBackupResult falls back to the server's cloud when config has no cloudId"() {
        given:
        BackupResult result = Domain.backupResult(serverId: 10L, config: [snapshotId: 'snap-1'])

        when:
        def rtn = provider.deleteBackupResult(result, [:])

        then:
        1 * computeServerService.get(10L) >> server
        1 * cloudService.get(1L) >> cloud
        1 * apiService.getScvmmZoneAndHypervisorOpts(*_) >> [:]
        1 * apiService.deleteSnapshot(_, 'vm-ext-1', 'snap-1') >> [success: true]
        rtn.success
    }

    def "deleteBackupResult resolves the server through the container when the result has no serverId"() {
        given:
        BackupResult result = Domain.backupResult(containerId: 20L, config: [cloudId: 1L, snapshotId: 'snap-1'])
        workloadService.get(20L) >> Domain.workload(id: 20L, serverId: 10L)
        cloudService.get(1L) >> cloud
        computeServerService.get(10L) >> server
        apiService.getScvmmZoneAndHypervisorOpts(*_) >> [:]

        when:
        def rtn = provider.deleteBackupResult(result, [:])

        then:
        1 * apiService.deleteSnapshot(_, 'vm-ext-1', 'snap-1') >> [success: true]
        rtn.success
    }

    def "deleteBackupResult succeeds without calling SCVMM when #reason"() {
        given:
        BackupResult result = Domain.backupResult(serverId: serverId, containerId: containerId, config: config)
        cloudService.get(_) >> cloud
        computeServerService.get(_) >> foundServer
        workloadService.get(_) >> null

        when:
        def rtn = provider.deleteBackupResult(result, [:])

        then:
        0 * apiService.deleteSnapshot(*_)
        rtn.success

        where:
        reason                          | serverId | containerId | config                                 | foundServer
        'the result has no snapshotId'  | 10L      | null        | [cloudId: 1L]                          | Domain.server()
        'the server is already deleted' | 10L      | null        | [cloudId: 1L, snapshotId: 'snap-1']    | null
        'the container is gone'         | null     | 20L         | [cloudId: 1L, snapshotId: 'snap-1']    | null
        'the config is empty'           | 10L      | null        | null                                   | Domain.server()
    }

    def "deleteBackupResult returns an error when SCVMM fails to delete the snapshot"() {
        given:
        BackupResult result = Domain.backupResult(serverId: 10L, config: [cloudId: 1L, snapshotId: 'snap-1'])
        cloudService.get(1L) >> cloud
        computeServerService.get(10L) >> server
        apiService.getScvmmZoneAndHypervisorOpts(*_) >> [:]
        apiService.deleteSnapshot(*_) >> [success: false, snapshotId: 'snap-1']

        when:
        def rtn = provider.deleteBackupResult(result, [:])

        then:
        rtn.success == false
    }

    def "deleteBackupResult returns an error instead of throwing when a lookup throws"() {
        given:
        BackupResult result = Domain.backupResult(serverId: 10L, config: [cloudId: 1L, snapshotId: 'snap-1'])
        cloudService.get(1L) >> { throw new RuntimeException('db down') }

        when:
        def rtn = provider.deleteBackupResult(result, [:])

        then:
        noExceptionThrown()
        rtn.success == false
    }

    // ---------------------------------------------------------------- pass-throughs

    @Unroll
    def "#method is a pass-through that returns success"() {
        when:
        ServiceResponse rtn = call.call(provider)

        then:
        rtn.success
        !echoesBackup || (rtn.data instanceof Backup && rtn.data.id == 80L)

        where:
        method                 | call                                                                       | echoesBackup
        'configureBackup'      | { p -> p.configureBackup(backupFixture(), [a: 1], [:]) }                   | true
        'validateBackup'       | { p -> p.validateBackup(backupFixture(), [a: 1], [:]) }                    | true
        'createBackup'         | { p -> p.createBackup(backupFixture(), [:]) }                              | false
        'deleteBackup'         | { p -> p.deleteBackup(backupFixture(), [:]) }                              | false
        'prepareExecuteBackup' | { p -> p.prepareExecuteBackup(backupFixture(), [:]) }                      | false
        'prepareBackupResult'  | { p -> p.prepareBackupResult(new BackupResult(), [:]) }                    | false
        'cancelBackup'         | { p -> p.cancelBackup(new BackupResult(), [:]) }                           | false
        'extractBackup'        | { p -> p.extractBackup(new BackupResult(), [:]) }                          | false
    }

    def "refreshBackupResult wraps the given result without changing it"() {
        given:
        BackupResult result = Domain.backupResult(status: 'IN_PROGRESS')

        when:
        ServiceResponse<BackupExecutionResponse> rtn = provider.refreshBackupResult(result)

        then:
        rtn.success
        rtn.data.backupResult.is(result)
        !rtn.data.updates
        result.status == 'IN_PROGRESS'
    }

    private static Backup backupFixture() {
        Domain.backup(id: 80L)
    }
}
