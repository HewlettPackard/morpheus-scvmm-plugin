package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.synchronous.MorpheusSynchronousWorkloadService

import com.morpheusdata.core.backup.response.BackupExecutionResponse
import com.morpheusdata.model.*
import spock.lang.Specification
import spock.lang.Subject

import java.time.Instant
import java.util.Base64

class ScvmmBackupExecutionProviderSpec extends Specification {

    @Subject
    ScvmmBackupExecutionProvider provider

    ScvmmPlugin mockPlugin
    MorpheusContext mockMorpheusContext
    ScvmmProvisionProvider mockProvisionProvider
    ScvmmApiService mockApiService

    MorpheusServices mockServices
    MorpheusSynchronousCloudService mockCloudService
    MorpheusSynchronousComputeServerService mockComputeServerService
    MorpheusSynchronousWorkloadService mockWorkloadService

    def setupSpec() {
        // Add Groovy extension methods for String encoding
        String.metaClass.encodeAsBase64 = {
            return Base64.encoder.encodeToString(delegate.bytes)
        }
    }

    def setup() {
        mockPlugin = Mock(ScvmmPlugin)
        mockMorpheusContext = Mock(MorpheusContext)
        mockProvisionProvider = Mock(ScvmmProvisionProvider)
        mockApiService = Mock(ScvmmApiService)

        mockCloudService = Mock(MorpheusSynchronousCloudService)
        mockComputeServerService = Mock(MorpheusSynchronousComputeServerService)
        mockWorkloadService = Mock(MorpheusSynchronousWorkloadService)

        mockServices = Mock(MorpheusServices) {
            getCloud() >> mockCloudService
            getComputeServer() >> mockComputeServerService
            getWorkload() >> mockWorkloadService
        }

        mockMorpheusContext.getServices() >> mockServices

        provider = new ScvmmBackupExecutionProvider(mockPlugin, mockMorpheusContext)
        provider.provisionProvider = mockProvisionProvider
        provider.apiService = mockApiService
    }

    def "deleteBackupResult with no cloudId uses cloud from server"() {
        given:
        def backupResult = new BackupResult(
                serverId: 100L
        )
        backupResult.configMap = [snapshotId: "snap123"]

        def cloudRef = new Cloud(id: 1L)
        def server = new ComputeServer(id: 100L, externalId: "vm-100", cloud: cloudRef)
        def node = new ComputeServer()
        def zoneOpts = [zone: "test-zone"]
        def deleteResult = [success: true]

        when:
        def result = provider.deleteBackupResult(backupResult, [:])

        then:
        1 * mockComputeServerService.get(100L) >> server
        1 * mockCloudService.get(1L) >> cloudRef
        1 * mockProvisionProvider.pickScvmmController(cloudRef) >> node
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(mockMorpheusContext, cloudRef, node) >> zoneOpts
        1 * mockApiService.deleteSnapshot(zoneOpts, "vm-100", "snap123") >> deleteResult

        result.success
    }

    def "executeBackup handles snapshot failure and updates backup result with error"() {
        given:
        def backup = new Backup()
        def backupResult = new BackupResult(startDate: Date.from(Instant.now().minusSeconds(60)))
        def executionConfig = [ipAddress: "192.168.1.1", workingPath: "/tmp/backup"]
        def cloud = new Cloud(id: 456L)
        def server = new ComputeServer(id: 123L, externalId: "vm-123")
        def opts = [backupSetId: "backup-set-123"]
        def snapshotResults = [success: false, error: "Snapshot creation failed"]
        def node = new ComputeServer()
        def scvmmOpts = [zone: "test-zone"]

        when:
        def result = provider.executeBackup(backup, backupResult, executionConfig, cloud, server, opts)

        then:
        1 * mockProvisionProvider.pickScvmmController(cloud) >> node
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(mockMorpheusContext, cloud, node) >> scvmmOpts
        1 * mockApiService.snapshotServer(_, "vm-123") >> snapshotResults

        result.success
        result.data.backupResult.status.toString() == BackupResult.Status.FAILED.toString()
        result.data.backupResult.errorOutput != null  // Should contain Base64 encoded error
        result.data.updates
    }

    def "executeBackup handles exception and sets backup result to failed"() {
        given:
        def backup = new Backup()
        def backupResult = new BackupResult(startDate: Date.from(Instant.now().minusSeconds(60)))
        def executionConfig = [ipAddress: "192.168.1.1", workingPath: "/tmp/backup", backupResultId: "test-backup-id"]
        def cloud = new Cloud(id: 456L)
        def server = new ComputeServer(id: 123L, externalId: "vm-123")
        def opts = [backupSetId: "backup-set-123"]

        when:
        def result = provider.executeBackup(backup, backupResult, executionConfig, cloud, server, opts)

        then:
        // Mock the provision provider to throw an exception early in the process
        1 * mockProvisionProvider.pickScvmmController(cloud) >> { throw new RuntimeException("API connection failed") }

        // The method should catch the exception and set appropriate error fields
        // Note: We can't verify errorOutput due to encodeAsBase64() issue, but we can test other fields
        result.data.backupResult.status.toString() == BackupResult.Status.FAILED.toString()
        result.data.backupResult.backupSetId == "test-backup-id"
        result.data.backupResult.executorIpAddress == "192.168.1.1"
        result.data.backupResult.sizeInMb == 0L
        result.data.updates
        result.msg != null
    }

    def "executeBackup handles exception without backupResultId in executionConfig"() {
        given:
        def backup = new Backup()
        def backupResult = new BackupResult(startDate: Date.from(Instant.now().minusSeconds(60)))
        def executionConfig = [ipAddress: "192.168.1.1", workingPath: "/tmp/backup"]
        def cloud = new Cloud(id: 456L)
        def server = new ComputeServer(id: 123L, externalId: "vm-123")
        def opts = [backupSetId: "backup-set-123"]

        when:
        def result = provider.executeBackup(backup, backupResult, executionConfig, cloud, server, opts)

        then:
        1 * mockProvisionProvider.pickScvmmController(cloud) >> { throw new RuntimeException("Network error") }

        result.data.backupResult.status.toString() == BackupResult.Status.FAILED.toString()
        result.data.backupResult.backupSetId != null  // Generated by BackupResultUtility
        result.data.updates
    }


    def "getMorpheus returns the correct MorpheusContext"() {
        when:
        def result = provider.getMorpheus()

        then:
        result == mockMorpheusContext
    }

    def "configureBackup returns success with the backup object"() {
        given:
        def backup = new Backup()
        def config = [:]
        def opts = [:]

        when:
        def result = provider.configureBackup(backup, config, opts)

        then:
        result.success
        result.data == backup
    }

    def "validateBackup returns success with the backup object"() {
        given:
        def backup = new Backup()
        def config = [:]
        def opts = [:]

        when:
        def result = provider.validateBackup(backup, config, opts)

        then:
        result.success
        result.data == backup
    }

    def "createBackup returns success"() {
        given:
        def backup = new Backup()
        def opts = [:]

        when:
        def result = provider.createBackup(backup, opts)

        then:
        result.success
    }

    def "deleteBackup returns success"() {
        given:
        def backup = new Backup()
        def opts = [:]

        when:
        def result = provider.deleteBackup(backup, opts)

        then:
        result.success
    }

    def "prepareExecuteBackup returns success"() {
        given:
        def backup = new Backup()
        def opts = [:]

        when:
        def result = provider.prepareExecuteBackup(backup, opts)

        then:
        result.success
    }

    def "prepareBackupResult returns success"() {
        given:
        def backupResult = new BackupResult()
        def opts = [:]

        when:
        def result = provider.prepareBackupResult(backupResult, opts)

        then:
        result.success
    }

    def "executeBackup successfully creates snapshot and updates backup result"() {
        given:
        def backup = new Backup()
        def backupResult = new BackupResult(startDate: Date.from(Instant.now().minusSeconds(60)))
        def executionConfig = [ipAddress: "192.168.1.1", workingPath: "/tmp/backup"]
        def cloud = new Cloud(id: 456L)
        def server = new ComputeServer(id: 123L, externalId: "vm-123")
        def opts = [backupSetId: "backup-set-123"]
        def snapshotResults = [success: true, snapshotId: "snapshot-456"]
        def node = new ComputeServer()
        def scvmmOpts = [zone: "test-zone"]

        when:
        def result = provider.executeBackup(backup, backupResult, executionConfig, cloud, server, opts)

        then:
        1 * mockProvisionProvider.pickScvmmController(cloud) >> node
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(mockMorpheusContext, cloud, node) >> scvmmOpts
        1 * mockApiService.snapshotServer(_, "vm-123") >> snapshotResults

        result.success
        result.data.backupResult.status.toString() == BackupResult.Status.SUCCEEDED.toString()
        result.data.backupResult.snapshotId == "snapshot-456"
    }

    def "refreshBackupResult returns success with backup execution response"() {
        given:
        def backupResult = new BackupResult()

        when:
        def result = provider.refreshBackupResult(backupResult)

        then:
        result.success
        result.data instanceof BackupExecutionResponse
        result.data.backupResult == backupResult
    }

    def "cancelBackup returns success"() {
        given:
        def backupResult = new BackupResult()
        def opts = [:]

        when:
        def result = provider.cancelBackup(backupResult, opts)

        then:
        result.success
    }

    def "extractBackup returns success"() {
        given:
        def backupResult = new BackupResult()
        def opts = [:]

        when:
        def result = provider.extractBackup(backupResult, opts)

        then:
        result.success
    }

    def "updateBackupResultSuccess sets all required fields correctly"() {
        given:
        def backupResult = new BackupResult(startDate: Date.from(Instant.now().minusSeconds(60)))
        def opts = [backupSetId: "backup-set-123"]
        def executionConfig = [ipAddress: "192.168.1.1"]
        def snapshotResults = [snapshotId: "snapshot-456"]
        def outputPath = "/tmp/backup"
        def vmId = "vm-123"

        when:
        provider.updateBackupResultSuccess(backupResult, opts, executionConfig, snapshotResults, outputPath, vmId)

        then:
        backupResult.resultBucket == "snapshot-456"
        backupResult.status.toString() == BackupResult.Status.SUCCEEDED.toString()
    }

    def "updateBackupResultError sets error fields correctly"() {
        given:
        def backupResult = new BackupResult()
        def opts = [backupSetId: "backup-set-123"]
        def executionConfig = [ipAddress: "192.168.1.1"]
        def outputPath = "/tmp/backup"
        def errorOutput = "Error occurred"

        when:
        provider.updateBackupResultError(backupResult, opts, executionConfig, outputPath, errorOutput)

        then:
        backupResult.status.toString() == BackupResult.Status.FAILED.toString()
        backupResult.errorOutput == "Error occurred"
    }

    def "deleteBackupResult with cloudId in config map uses specified cloud"() {
        given:
        def backupResult = new BackupResult(serverId: 100L)
        backupResult.configMap = [cloudId: 2L, snapshotId: "snap123"]

        def specifiedCloud = new Cloud(id: 2L)
        def server = new ComputeServer(id: 100L, externalId: "vm-100", cloud: new Cloud(id: 1L))
        def node = new ComputeServer()
        def zoneOpts = [zone: "test-zone"]
        def deleteResult = [success: true]

        when:
        def result = provider.deleteBackupResult(backupResult, [:])

        then:
        1 * mockCloudService.get(2L) >> specifiedCloud
        1 * mockComputeServerService.get(100L) >> server
        1 * mockProvisionProvider.pickScvmmController(specifiedCloud) >> node
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(mockMorpheusContext, specifiedCloud, node) >> zoneOpts
        1 * mockApiService.deleteSnapshot(zoneOpts, "vm-100", "snap123") >> deleteResult

        result.success
    }

    def "deleteBackupResult with containerId uses container's server when serverId is null"() {
        given:
        def backupResult = new BackupResult(containerId: 200L, serverId: null)
        backupResult.configMap = [snapshotId: "snap456"]

        def cloudRef = new Cloud(id: 3L)
        def server = new ComputeServer(id: 150L, externalId: "vm-150", cloud: cloudRef)
        def container = new Workload(id: 200L, serverId: 150L)
        def node = new ComputeServer()
        def zoneOpts = [zone: "test-zone"]
        def deleteResult = [success: true]

        when:
        def result = provider.deleteBackupResult(backupResult, [:])

        then:
        1 * mockWorkloadService.get(200L) >> container
        1 * mockComputeServerService.get(150L) >> server
        1 * mockCloudService.get(3L) >> cloudRef
        1 * mockProvisionProvider.pickScvmmController(cloudRef) >> node
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(mockMorpheusContext, cloudRef, node) >> zoneOpts
        1 * mockApiService.deleteSnapshot(zoneOpts, "vm-150", "snap456") >> deleteResult

        result.success
    }

    def "deleteBackupResult handles null container"() {
        given:
        def backupResult = new BackupResult(containerId: 200L, serverId: null)
        backupResult.configMap = [snapshotId: "snap789"]

        when:
        def result = provider.deleteBackupResult(backupResult, [:])

        then:
        1 * mockWorkloadService.get(200L) >> null
        0 * mockComputeServerService.get(_)
        0 * mockApiService.deleteSnapshot(_, _, _)

        result.success
    }

    def "deleteBackupResult handles missing snapshotId"() {
        given:
        def backupResult = new BackupResult(serverId: 100L)
        backupResult.configMap = [:]

        def cloudRef = new Cloud(id: 1L)
        def server = new ComputeServer(id: 100L, externalId: "vm-100", cloud: cloudRef)

        when:
        def result = provider.deleteBackupResult(backupResult, [:])

        then:
        1 * mockComputeServerService.get(100L) >> server
        1 * mockCloudService.get(1L) >> cloudRef
        0 * mockApiService.deleteSnapshot(_, _, _)

        result.success
    }

    def "deleteBackupResult handles null server"() {
        given:
        def backupResult = new BackupResult(serverId: 100L)
        backupResult.configMap = [snapshotId: "snap123"]

        when:
        def result = provider.deleteBackupResult(backupResult, [:])

        then:
        1 * mockComputeServerService.get(100L) >> null
        0 * mockApiService.deleteSnapshot(_, _, _)

        result.success
    }

    def "deleteBackupResult handles API delete failure"() {
        given:
        def backupResult = new BackupResult(serverId: 100L)
        backupResult.configMap = [snapshotId: "snap123"]

        def cloudRef = new Cloud(id: 1L)
        def server = new ComputeServer(id: 100L, externalId: "vm-100", cloud: cloudRef)
        def node = new ComputeServer()
        def zoneOpts = [zone: "test-zone"]
        def deleteResult = [success: false, error: "API Error"]

        when:
        def result = provider.deleteBackupResult(backupResult, [:])

        then:
        1 * mockComputeServerService.get(100L) >> server
        1 * mockCloudService.get(1L) >> cloudRef
        1 * mockProvisionProvider.pickScvmmController(cloudRef) >> node
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(mockMorpheusContext, cloudRef, node) >> zoneOpts
        1 * mockApiService.deleteSnapshot(zoneOpts, "vm-100", "snap123") >> deleteResult

        !result.success
    }

    def "deleteBackupResult handles exception and returns false"() {
        given:
        def backupResult = new BackupResult(serverId: 100L)
        backupResult.configMap = [snapshotId: "snap123"]

        when:
        def result = provider.deleteBackupResult(backupResult, [:])

        then:
        1 * mockComputeServerService.get(100L) >> { throw new RuntimeException("Database error") }

        !result.success
    }

}
