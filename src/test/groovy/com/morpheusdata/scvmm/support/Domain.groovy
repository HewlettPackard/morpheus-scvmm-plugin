// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.support

import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupRestore
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.Instance
import com.morpheusdata.model.MorpheusModel
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.Workload

/**
 * Factories for Morpheus model objects with stable ids. Overrides are applied through the map constructor;
 * a {@code config: Map} override is written with {@code setConfigMap} so {@code getConfigMap()} sees it.
 */
class Domain {

    static Cloud cloud(Map overrides = [:]) {
        build(Cloud, [id: 1L, name: 'scvmm-cloud', code: 'scvmm-cloud'], overrides)
    }

    static ComputeServer server(Map overrides = [:]) {
        build(ComputeServer, [id: 10L, name: 'vm-1', externalId: 'vm-ext-1', cloud: cloud()], overrides)
    }

    static ComputeServer controller(Map overrides = [:]) {
        build(ComputeServer, [id: 2L, name: 'scvmm-controller', sshHost: 'scvmm.local', sshUsername: 'u', sshPassword: 'p'], overrides)
    }

    static Workload workload(Map overrides = [:]) {
        build(Workload, [id: 20L, serverId: 10L, server: server()], overrides)
    }


    static Instance instance(Map overrides = [:]) {
        build(Instance, [id: 30L, name: 'instance-1'], overrides)
    }

    static ServicePlan plan(Map overrides = [:]) {
        build(ServicePlan, [id: 40L, code: 'scvmm-plan', maxCores: 2L, maxMemory: 4294967296L, maxStorage: 42949672960L], overrides)
    }

    static VirtualImage image(Map overrides = [:]) {
        build(VirtualImage, [id: 50L, name: 'template-1', externalId: 'tpl-1'], overrides)
    }

    static StorageVolume volume(Map overrides = [:]) {
        build(StorageVolume, [id: 60L, name: 'root', externalId: 'disk-1', maxStorage: 42949672960L, rootVolume: true], overrides)
    }

    static ComputeServerInterface nic(Map overrides = [:]) {
        build(ComputeServerInterface, [id: 70L, name: 'eth0', externalId: 'nic-1', primaryInterface: true], overrides)
    }

    static Backup backup(Map overrides = [:]) {
        build(Backup, [id: 80L, name: 'backup-1'], overrides)
    }

    static BackupResult backupResult(Map overrides = [:]) {
        build(BackupResult, [id: 90L, backupName: 'backup-1'], overrides)
    }

    static BackupRestore backupRestore(Map overrides = [:]) {
        build(BackupRestore, [id: 100L], overrides)
    }

    static Network network(Map overrides = [:]) {
        build(Network, [id: 110L, name: 'vmnet-1', externalId: 'vmnet-1'], overrides)
    }

    static NetworkPool networkPool(Map overrides = [:]) {
        build(NetworkPool, [id: 120L, name: 'pool-1', externalId: 'pool-1'], overrides)
    }

    private static <T extends MorpheusModel> T build(Class<T> type, Map defaults, Map overrides) {
        Map values = defaults + (overrides ?: [:])
        def config = values.remove('config')
        T model = type.newInstance(values)
        if (config instanceof Map) {
            model.setConfigMap(config)
        } else if (config != null) {
            model.setConfig(config.toString())
        }
        return model
    }
}
