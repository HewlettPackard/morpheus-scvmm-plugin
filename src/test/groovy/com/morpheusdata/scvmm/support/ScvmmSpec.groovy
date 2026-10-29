// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.support

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusOperationNotificationService
import com.morpheusdata.core.MorpheusProcessService
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.MorpheusStorageVolumeService
import com.morpheusdata.core.MorpheusVirtualImageService
import com.morpheusdata.core.MorpheusWorkloadService
import com.morpheusdata.core.backup.MorpheusBackupService
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.network.MorpheusNetworkService
import com.morpheusdata.core.synchronous.MorpheusSynchronousOperationNotificationService
import com.morpheusdata.core.synchronous.MorpheusSynchronousProcessService
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService
import com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService
import com.morpheusdata.core.synchronous.MorpheusSynchronousWorkloadService
import com.morpheusdata.core.synchronous.backup.MorpheusSynchronousBackupService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService
import spock.lang.Specification

/**
 * Base spec wiring a mocked {@link MorpheusContext} to mocked sync ({@code context.services.X}) and async
 * ({@code context.async.X}) sub-services. Spock runs this {@code setup()} before the subclass's own
 * {@code setup()}; subclasses add interactions on the exposed fields (e.g. {@code computeServerService.get(10L) >> server}).
 */
abstract class ScvmmSpec extends Specification {

    MorpheusContext context = Mock()
    MorpheusServices services = Mock()
    MorpheusAsyncServices asyncServices = Mock()

    MorpheusSynchronousComputeServerService computeServerService = Mock()
    MorpheusSynchronousCloudService cloudService = Mock()
    MorpheusSynchronousStorageVolumeService storageVolumeService = Mock()
    MorpheusSynchronousNetworkService networkService = Mock()
    MorpheusSynchronousVirtualImageService virtualImageService = Mock()
    MorpheusSynchronousBackupService backupService = Mock()
    MorpheusSynchronousOperationNotificationService operationNotificationService = Mock()
    MorpheusSynchronousWorkloadService workloadService = Mock()
    MorpheusSynchronousProcessService processService = Mock()

    MorpheusComputeServerService asyncComputeServerService = Mock()
    MorpheusCloudService asyncCloudService = Mock()
    MorpheusStorageVolumeService asyncStorageVolumeService = Mock()
    MorpheusNetworkService asyncNetworkService = Mock()
    MorpheusVirtualImageService asyncVirtualImageService = Mock()
    MorpheusBackupService asyncBackupService = Mock()
    MorpheusOperationNotificationService asyncOperationNotificationService = Mock()
    MorpheusWorkloadService asyncWorkloadService = Mock()
    MorpheusProcessService asyncProcessService = Mock()

    def setup() {
        context.getServices() >> services
        context.getAsync() >> asyncServices

        services.getComputeServer() >> computeServerService
        services.getCloud() >> cloudService
        services.getStorageVolume() >> storageVolumeService
        services.getNetwork() >> networkService
        services.getVirtualImage() >> virtualImageService
        services.getBackup() >> backupService
        services.getOperationNotification() >> operationNotificationService
        services.getWorkload() >> workloadService
        services.getProcess() >> processService

        asyncServices.getComputeServer() >> asyncComputeServerService
        asyncServices.getCloud() >> asyncCloudService
        asyncServices.getStorageVolume() >> asyncStorageVolumeService
        asyncServices.getNetwork() >> asyncNetworkService
        asyncServices.getVirtualImage() >> asyncVirtualImageService
        asyncServices.getBackup() >> asyncBackupService
        asyncServices.getOperationNotification() >> asyncOperationNotificationService
        asyncServices.getWorkload() >> asyncWorkloadService
        asyncServices.getProcess() >> asyncProcessService
    }
}
