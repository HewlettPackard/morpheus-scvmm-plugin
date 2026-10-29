// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupRestore
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Instance
import com.morpheusdata.model.Workload
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.scvmm.support.Domain
import com.morpheusdata.scvmm.support.ScvmmSpec
import spock.lang.Subject

/**
 * restoreBackup runs: restoreServer -> pause -> stopWorkload -> pause -> startWorkload, then marks SUCCEEDED.
 * The pauses and the provision provider come through the test seams pause()/buildProvisionProvider().
 */
class ScvmmBackupRestoreProviderSpec extends ScvmmSpec {

    ScvmmPlugin plugin = Mock()
    ScvmmApiService apiService = Mock()
    ScvmmProvisionProvider provisionProvider = Mock()

    @Subject
    ScvmmBackupRestoreProvider provider

    Cloud cloud = Domain.cloud(id: 1L)
    ComputeServer controller = Domain.controller()
    ComputeServer server = Domain.server(id: 10L, externalId: 'vm-ext-1', cloud: cloud)
    Workload workload = Domain.workload(id: 20L, serverId: 10L, server: server)
    Backup backup = Domain.backup()
    BackupRestore restore = Domain.backupRestore()
    Map restoreOpts = [zoneId: 1L]

    def setup() {
        provider = Spy(ScvmmBackupRestoreProvider, constructorArgs: [plugin, context])
        provider.apiService = apiService
        provider.pause(_) >> null
        provider.buildProvisionProvider() >> provisionProvider
        provisionProvider.pickScvmmController(cloud) >> controller
    }

    private static BackupResult resultWith(Map config) {
        Domain.backupResult(containerId: 20L, config: config)
    }

    def "restoreBackup restores the checkpoint, then power-cycles the workload, and marks the restore SUCCEEDED"() {
        given:
        BackupResult result = resultWith(snapshotId: 'snap-1', vmId: 'vm-ext-1')
        workloadService.get(20L) >> workload
        computeServerService.get(10L) >> server
        apiService.getScvmmZoneAndHypervisorOpts(context, cloud, controller) >> restoreOpts

        when:
        ServiceResponse rtn = provider.restoreBackup(restore, result, backup, [:])

        then:
        1 * apiService.restoreServer(restoreOpts, 'vm-ext-1', 'snap-1') >> [success: true]

        then:
        1 * provider.pause(30000) >> null

        then:
        1 * provisionProvider.stopWorkload(workload)

        then:
        1 * provider.pause(30000) >> null

        then:
        1 * provisionProvider.startWorkload(workload)

        and:
        rtn.success
        rtn.data.updates
        rtn.data.backupRestore.is(restore)
        restore.status == BackupResult.Status.SUCCEEDED.toString()
    }

    def "restoreBackup fails without touching SCVMM when the workload no longer exists"() {
        given:
        BackupResult result = resultWith(snapshotId: 'snap-1', vmId: 'vm-ext-1')
        workloadService.get(20L) >> null

        when:
        ServiceResponse rtn = provider.restoreBackup(restore, result, backup, [:])

        then:
        noExceptionThrown()
        0 * apiService.restoreServer(*_)
        0 * provisionProvider.stopWorkload(_)
        rtn.success == false
        rtn.msg.contains('serverId')
        restore.status != BackupResult.Status.SUCCEEDED.toString()
    }

    def "restoreBackup fails after the restore and skips the start when stopWorkload throws"() {
        // plan assumed stop -> restore -> start; the code restores first, so restoreServer has already run
        given:
        BackupResult result = resultWith(snapshotId: 'snap-1', vmId: 'vm-ext-1')
        workloadService.get(20L) >> workload
        computeServerService.get(10L) >> server
        apiService.getScvmmZoneAndHypervisorOpts(*_) >> restoreOpts

        when:
        ServiceResponse rtn = provider.restoreBackup(restore, result, backup, [:])

        then:
        1 * apiService.restoreServer(*_) >> [success: true]
        1 * provisionProvider.stopWorkload(workload) >> { throw new RuntimeException('stop failed') }
        0 * provisionProvider.startWorkload(_)
        rtn.success == false
        rtn.msg == 'stop failed'
        restore.status == null
    }

    def "restoreBackup fails without power-cycling when restoreServer throws"() {
        given:
        BackupResult result = resultWith(snapshotId: 'snap-1', vmId: 'vm-ext-1')
        workloadService.get(20L) >> workload
        computeServerService.get(10L) >> server
        apiService.getScvmmZoneAndHypervisorOpts(*_) >> restoreOpts
        apiService.restoreServer(*_) >> { throw new IllegalStateException('winrm timeout') }

        when:
        ServiceResponse rtn = provider.restoreBackup(restore, result, backup, [:])

        then:
        0 * provider.pause(_)
        0 * provisionProvider.stopWorkload(_)
        0 * provisionProvider.startWorkload(_)
        rtn.success == false
        rtn.msg == 'winrm timeout'
    }

    // ---------------------------------------------------------------- seams and wiring

    def "buildProvisionProvider returns a new provision provider bound to the plugin and context"() {
        given:
        def real = new ScvmmBackupRestoreProvider(plugin, context)

        when:
        def built = real.buildProvisionProvider()

        then:
        built instanceof ScvmmProvisionProvider
        !built.is(real.buildProvisionProvider())
        real.apiService instanceof ScvmmApiService
        real.morpheus.is(context)
    }

    def "pause delegates to sleep and returns for a zero delay"() {
        given:
        def real = new ScvmmBackupRestoreProvider(plugin, context)

        when:
        real.pause(0L)

        then:
        noExceptionThrown()
    }

    // ---------------------------------------------------------------- pass-throughs

    def "configureRestoreBackup echoes the restore config"() {
        given:
        Map config = [restoreTo: 'existing']

        expect:
        provider.configureRestoreBackup(new BackupResult(), config, [:]).with { success && data.is(config) }
    }

    def "getBackupRestoreInstanceConfig echoes the restore config"() {
        given:
        Map config = [name: 'restored']

        expect:
        provider.getBackupRestoreInstanceConfig(new BackupResult(), new Instance(), config, [:]).with { success && data.is(config) }
    }

    def "refreshBackupRestoreResult echoes the restore unchanged"() {
        expect:
        provider.refreshBackupRestoreResult(restore, new BackupResult()).with { success && data.is(restore) }
    }

    def "#method returns success with no data"() {
        expect:
        call.call(provider).with { success && data == null }

        where:
        method                  | call
        'validateRestoreBackup' | { p -> p.validateRestoreBackup(new BackupResult(), [:]) }
        'getRestoreOptions'     | { p -> p.getRestoreOptions(new Backup(), [:]) }
    }
}
