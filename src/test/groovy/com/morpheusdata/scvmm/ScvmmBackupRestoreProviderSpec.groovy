package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusComputeTypeSetService
import com.morpheusdata.core.MorpheusOsTypeService
import com.morpheusdata.core.MorpheusProcessService
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.MorpheusStorageVolumeService
import com.morpheusdata.core.MorpheusVirtualImageService
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.library.MorpheusWorkloadTypeService
import com.morpheusdata.core.network.MorpheusNetworkService
import com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService
import com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService
import com.morpheusdata.core.synchronous.MorpheusSynchronousWorkloadService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.synchronous.library.MorpheusSynchronousWorkloadTypeService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Workload
import com.morpheusdata.response.ServiceResponse
import org.junit.jupiter.api.BeforeEach
import spock.lang.Specification
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Backup
import com.morpheusdata.model.Instance
import com.morpheusdata.model.BackupRestore
import spock.lang.Unroll

class ScvmmBackupRestoreProviderSpec extends Specification {
    private MorpheusContext morpheusContext
    private ScvmmPlugin plugin
    private ScvmmBackupRestoreProvider bkpRestoreProvider
    private ScvmmApiService mockApiService
    private MorpheusSynchronousComputeServerService computeServerService
    private MorpheusComputeServerService asyncComputeServerService
    private MorpheusComputeTypeSetService asyncComputeTypeSetService
    private MorpheusProcessService processService
    private MorpheusSynchronousWorkloadTypeService workloadTypeService
    private MorpheusSynchronousWorkloadService workloadService
    private MorpheusWorkloadTypeService asyncWorkloadTypeService
    private MorpheusCloudService asyncCloudService
    private MorpheusOsTypeService asyncOsTypeService
    private MorpheusSynchronousCloudService cloudService
    private MorpheusSynchronousNetworkService networkService
    private MorpheusSynchronousStorageVolumeService storageVolumeService
    private MorpheusSynchronousResourcePermissionService resourcePermissionService
    private MorpheusStorageVolumeService asyncStorageVolumeService
    private MorpheusSynchronousVirtualImageService virtualImageService
    private MorpheusVirtualImageService asyncVirtualImageService
    private MorpheusAsyncServices morpheusAsyncServices

    @BeforeEach
    void setup() {
        // Setup mock context and services
        morpheusContext = Mock(MorpheusContext)
        plugin = Mock(ScvmmPlugin)

        // Mock services
        computeServerService = Mock(MorpheusSynchronousComputeServerService)
        asyncComputeServerService = Mock(MorpheusComputeServerService)
        asyncComputeTypeSetService = Mock(MorpheusComputeTypeSetService)
        processService = Mock(MorpheusProcessService)
        asyncCloudService = Mock(MorpheusCloudService)
        def asyncNetworkService = Mock(MorpheusNetworkService)
        workloadTypeService = Mock(MorpheusSynchronousWorkloadTypeService)
        workloadService = Mock(MorpheusSynchronousWorkloadService)
        asyncWorkloadTypeService = Mock(MorpheusWorkloadTypeService)
        storageVolumeService = Mock(MorpheusSynchronousStorageVolumeService)
        resourcePermissionService = Mock(MorpheusSynchronousResourcePermissionService)
        cloudService = Mock(MorpheusSynchronousCloudService)
        networkService = Mock(MorpheusSynchronousNetworkService)
        asyncStorageVolumeService = Mock(MorpheusStorageVolumeService)
        virtualImageService = Mock(MorpheusSynchronousVirtualImageService)
        asyncVirtualImageService = Mock(MorpheusVirtualImageService)
        asyncOsTypeService = Mock(MorpheusOsTypeService)

        def morpheusServices = Mock(MorpheusServices) {
            getComputeServer() >> computeServerService
            getCloud() >> cloudService
            getWorkloadType() >> workloadTypeService
            getWorkload() >> workloadService
            getStorageVolume() >> storageVolumeService
            getVirtualImage() >> virtualImageService
            getResourcePermission() >> resourcePermissionService
        }
        morpheusAsyncServices = Mock(MorpheusAsyncServices) {
            getCloud() >> asyncCloudService
            getNetwork() >> asyncNetworkService
            getComputeServer() >> asyncComputeServerService
            getStorageVolume() >> asyncStorageVolumeService
            getVirtualImage() >> asyncVirtualImageService
            getComputeTypeSet() >> asyncComputeTypeSetService
            getWorkloadType() >> asyncWorkloadTypeService
            getOsType() >> asyncOsTypeService
        }
        morpheusContext.getAsync() >> morpheusAsyncServices
        morpheusContext.getServices() >> morpheusServices

        mockApiService = Mock(ScvmmApiService)
        bkpRestoreProvider = Spy(ScvmmBackupRestoreProvider, constructorArgs: [plugin, morpheusContext])
        bkpRestoreProvider.apiService = mockApiService

    }

    def "constructor sets fields and creates apiService"() {
        when:
        def provider = new ScvmmBackupRestoreProvider(plugin, morpheusContext)

        then:
        provider.plugin == plugin
        provider.morpheusContext == morpheusContext
        provider.apiService != null
    }

    def "getMorpheus returns morpheusContext"() {
        given:
        def provider = new ScvmmBackupRestoreProvider(plugin, morpheusContext)

        expect:
        provider.getMorpheus() == morpheusContext
    }

    def "configureRestoreBackup returns ServiceResponse.success with config"() {
        given:
        def config = [foo: "bar"]

        when:
        def resp = bkpRestoreProvider.configureRestoreBackup(Mock(BackupResult), config, [:])

        then:
        resp.success
        resp.data == config
    }

    def "getBackupRestoreInstanceConfig returns ServiceResponse.success with restoreConfig"() {
        given:
        def restoreConfig = [baz: "qux"]

        when:
        def resp = bkpRestoreProvider.getBackupRestoreInstanceConfig(Mock(BackupResult), Mock(Instance), restoreConfig, [:])

        then:
        resp.success
        resp.data == restoreConfig
    }

    def "validateRestoreBackup returns ServiceResponse.success"() {
        given:

        when:
        def resp = bkpRestoreProvider.validateRestoreBackup(Mock(BackupResult), [:])

        then:
        resp.success
    }

    def "getRestoreOptions returns ServiceResponse.success"() {
        given:

        when:
        def resp = bkpRestoreProvider.getRestoreOptions(Mock(Backup), [:])

        then:
        resp.success
    }

    def "refreshBackupRestoreResult returns ServiceResponse.success with backupRestore"() {
        given:
        def backupRestore = Mock(BackupRestore)

        when:
        def resp = bkpRestoreProvider.refreshBackupRestoreResult(backupRestore, Mock(BackupResult))

        then:
        resp.success
        resp.data == backupRestore
    }

    @Unroll
    def "restoreBackup returns success and updates backupRestore status when snapshotId is present"() {
        given:
        def backupRestore = new BackupRestore()
        def backupResult = new BackupResult()
        def backup = new Backup()
        def provisionProvider = Mock(ScvmmProvisionProvider)
        bkpRestoreProvider.createProvisionProvider() >> {
            provisionProvider
        }

        def opts = [:]
        def configMap = [snapshotId: "snap-1", vmId: "vm-1"]
        def workload = new Workload()
        workload.serverId = 123
        def computeServer = new ComputeServer(cloud: new Cloud())
        def node = new ComputeServer()
        def restoreOpts = [foo: "bar"]
        def restoreResults = [success: true]

        backupResult.setConfigMap(configMap)
        backupResult.containerId = 456L

        workloadService.get(456) >> workload
        computeServerService.get(123) >> computeServer

        provisionProvider.pickScvmmController(computeServer.cloud) >> node
        def stopWorkloadResp = ServiceResponse.prepare(success: true)
        def startWorkloadResp = ServiceResponse.prepare(success: true)
        provisionProvider.stopWorkload(workload) >> stopWorkloadResp
        provisionProvider.startWorkload(workload) >> startWorkloadResp

        mockApiService.getScvmmZoneAndHypervisorOpts(morpheusContext, computeServer.cloud, node) >> restoreOpts
        mockApiService.restoreServer(restoreOpts, "vm-1", "snap-1") >> restoreResults

        when:
        def resp = bkpRestoreProvider.restoreBackup(backupRestore, backupResult, backup, opts)

        then:
        resp.success
        resp.data.backupRestore.status == BackupResult.Status.SUCCEEDED.toString()
        resp.data.updates == true
    }

    @Unroll
    def "createProvisionProvider returns new ScvmmProvisionProvider with correct args"() {
        given:
        def provider = new ScvmmBackupRestoreProvider(plugin, morpheusContext)

        when:
        def result = provider.createProvisionProvider()

        then:
        result instanceof ScvmmProvisionProvider
        result.plugin == plugin
        result.getMorpheus() == morpheusContext
    }

    def "restoreBackup handles exceptions and sets error status"() {
        given:
        def backupRestore = new BackupRestore()
        def backupResult = new BackupResult()
        def backup = new Backup()
        def opts = [:]
        def errorMessage = "Test exception message"
        def configMap = [snapshotId: "snap-1", vmId: "vm-1"]
        backupResult.setConfigMap(configMap)
        backupResult.containerId = 456L

        // Mock the workloadService to throw an exception
        workloadService.get(456) >> { throw new RuntimeException(errorMessage) }

        when:
        def resp = bkpRestoreProvider.restoreBackup(backupRestore, backupResult, backup, opts)

        then:
        !resp.success
        resp.msg == errorMessage
        resp.data.backupRestore == backupRestore
    }

}

