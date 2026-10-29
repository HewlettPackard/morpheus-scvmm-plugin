// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService
import com.morpheusdata.model.Account
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.Network
import com.morpheusdata.model.OsType
import com.morpheusdata.scvmm.helper.morpheus.types.StorageVolumeTypeHelper
import com.morpheusdata.scvmm.support.Domain
import com.morpheusdata.scvmm.support.ScvmmSpec
import spock.lang.Subject

/**
 * Provisioning option types, server-config validation, controller resolution and opts assembly
 * ({@code getScvmmServerOpts}/{@code getScvmmContainerOpts}), plus ScvmmCloudProvider's
 * {@code getMaxStorage}/{@code getMaxMemory}/{@code createSshOptions}; and the static type catalogs
 * {@link StorageVolumeTypeHelper} and {@link ScvmmApiService#getMapScvmmOsType}.
 * Validation runs against a real {@link ScvmmApiService#validateServerConfig} (it executes no PowerShell).
 */
class ScvmmProvisionProviderOptionsSpec extends ScvmmSpec {

    static final long GB = 1024L * 1024L * 1024L

    ScvmmPlugin plugin = Mock()
    ScvmmApiService typeMapApi = new ScvmmApiService(Stub(com.morpheusdata.core.MorpheusContext))
    MorpheusSynchronousNetworkService cloudNetworkService = Mock()

    @Subject
    ScvmmProvisionProvider provider

    def setup() {
        cloudService.getNetwork() >> cloudNetworkService
        provider = new ScvmmProvisionProvider(plugin, context)
    }

    // --- option types ---

    def "getOptionTypes exposes noAgent, capability profile and host with unique codes"() {
        when:
        def options = provider.optionTypes

        then:
        options*.code == ['provisionType.scvmm.noAgent', 'provisionType.scvmm.capabilityProfile', 'provisionType.scvmm.host']
        options*.fieldName == ['noAgent', 'scvmmCapabilityProfile', 'hostId']
        options.every { it.fieldContext == 'config' && it.category == 'provisionType.scvmm' }
        options.findAll { it.required }*.code == ['provisionType.scvmm.capabilityProfile']
    }

    def "getOptionTypes and getNodeOptionTypes only reference option sources the SCVMM option source provides"() {
        given:
        def scvmmMethods = new ScvmmOptionSourceProvider(plugin, context).methodNames

        expect:
        (provider.optionTypes + provider.nodeOptionTypes).findAll { it.optionSourceType == 'scvmm' }*.optionSource
                .every { it in scvmmMethods }
    }

    def "getNodeOptionTypes declares 11 unique, optional node options"() {
        when:
        def options = provider.nodeOptionTypes

        then:
        options.size() == 11
        options*.code.toSet().size() == 11
        options.every { !it.required }
        options.find { it.code == 'scvmm-node-image' }.optionSource == 'scvmmVirtualImages'
        options.find { it.code == 'provisionType.scvmm.custom.instanceType.backupType' }.defaultValue == 'scvmmSnapshot'
        options.findAll { it.inputType == com.morpheusdata.model.OptionType.InputType.HIDDEN }*.defaultValue ==
                ['vm', 'vm', 'vm', 'This will provision a single vm container', 'scvmmSnapshot']
    }

    def "getNodeOptionTypes leaves the config folder option without a category"() {
        // pinned: every other node option is in 'provisionType.scvmm.custom'
        expect:
        provider.nodeOptionTypes.findAll { it.category != 'provisionType.scvmm.custom' }*.code == ['scvmm-node-config-folder']
    }

    // --- getValidateServerConfigOptions ---

    def "getValidateServerConfigOptions resolves the network id from #source"() {
        expect:
        ScvmmProvisionProvider.getValidateServerConfigOptions(opts).networkId == 'net-1'

        where:
        source                               | opts
        'networkInterface'                   | [networkInterface: [network: [id: 'net-1']], config: [scvmmNetworkId: 'other']]
        'config.networkInterface'            | [config: [networkInterface: [network: [id: 'net-1']], scvmmNetworkId: 'other']]
        'networkInterfaces[0]'               | [networkInterfaces: [[network: [id: 'net-1']]], config: [scvmmNetworkId: 'other']]
        'config.scvmmNetworkId'              | [config: [scvmmNetworkId: 'net-1']]
    }

    def "getValidateServerConfigOptions prefers config over top-level for #key and omits it when absent"() {
        expect:
        ScvmmProvisionProvider.getValidateServerConfigOptions([config: [(key): 'cfg'], (key): 'top'])[key] == 'cfg'
        ScvmmProvisionProvider.getValidateServerConfigOptions([(key): 'top'])[key] == 'top'
        !ScvmmProvisionProvider.getValidateServerConfigOptions([config: [:]]).containsKey(key)

        where:
        key << ['scvmmCapabilityProfile', 'nodeCount', 'template']
    }

    def "getValidateServerConfigOptions tolerates null opts"() {
        expect:
        ScvmmProvisionProvider.getValidateServerConfigOptions(null) == [networkId: null, networkInterfaces: []]
    }

    // --- validateServerConfigOptions (via validateWorkload / validateInstance / validateHost) ---

    def "#entryPoint succeeds when network, capability profile and template are all present"() {
        given:
        def opts = [config: [scvmmNetworkId: 'net-1', scvmmCapabilityProfile: 'Hyper-V', template: 50, nodeCount: 1]]

        when:
        def result = call(provider, opts)

        then:
        result.success
        !result.errors

        where:
        entryPoint         | call
        'validateWorkload' | { p, o -> p.validateWorkload(o) }
        'validateInstance' | { p, o -> p.validateInstance(null, o) }
        'validateHost'     | { p, o -> p.validateHost(new ComputeServer(), o) }
    }

    def "validateWorkload reports '#msg' on field #field when #reason"() {
        when:
        def result = provider.validateWorkload(opts)

        then:
        !result.success
        result.errors == [(field): msg]

        where:
        reason                                 | opts                                                              | field                    | msg
        'no network is selected'               | [config: [:]]                                                     | 'networkId'              | 'Network is required'
        'opts are empty'                       | [:]                                                               | 'networkId'              | 'Network is required'
        'the capability profile is blank'      | [config: [scvmmNetworkId: 'n', scvmmCapabilityProfile: '']]        | 'scvmmCapabilityProfile' | 'You must select a capability profile'
        'the template is blank'                | [config: [scvmmNetworkId: 'n', template: null]]                    | 'template'               | 'Virtual image is required'
        'the node count is an empty string'    | [config: [scvmmNetworkId: 'n', nodeCount: '']]                     | 'nodeCount'              | 'You must indicate number of hosts'
        'the first interface has no network'   | [networkInterfaces: [[network: [:]], [network: [id: 'n']]]]       | 'networkInterface'       | 'Network is required'
    }

    def "validateWorkload keeps only the last message per field when several errors share it"() {
        when:
        def result = provider.validateWorkload([networkInterfaces: [[network: [:]], [network: [:]]]])

        then:
        !result.success
        result.errors == [networkInterface: 'Network is required']
    }

    def "validateWorkload does not validate further interfaces once the first one has a network"() {
        // pinned: networkInterfaces[0].network.id becomes validationOpts.networkId, which short-circuits the per-NIC loop
        expect:
        provider.validateWorkload([networkInterfaces: [[network: [id: 'n']], [network: [:]]]]).success
    }

    def "validateWorkload rejects an interface whose network is null without reporting a field error"() {
        // nic.network.group NPEs inside validateServerConfig; its catch returns success=false with an empty error list
        when:
        def result = provider.validateWorkload([networkInterfaces: [[network: null]]])

        then:
        !result.success
        !result.errors
    }

    def "validateWorkload returns success when the validator itself throws"() {
        given:
        provider.apiService = Mock(ScvmmApiService) {
            validateServerConfig(_) >> { throw new IllegalStateException('boom') }
        }

        expect:
        provider.validateWorkload([config: [:]]).success
    }

    def "validateHost skips validation for hypervisor server types"() {
        given:
        provider.apiService = Mock(ScvmmApiService)
        def hypervisor = new ComputeServer(computeServerType: new ComputeServerType(vmHypervisor: true))

        when:
        def result = provider.validateHost(hypervisor, [:])

        then:
        result.success
        0 * provider.apiService.validateServerConfig(_)
    }

    // --- pickScvmmController ---

    def "pickScvmmController returns the shared controller without looking up the cloud's own"() {
        given:
        def shared = Domain.controller(id: 77L)

        when:
        def result = provider.pickScvmmController(Domain.cloud(config: [sharedController: '77']))

        then:
        1 * computeServerService.get(77L) >> shared
        0 * computeServerService.find(_)
        result.is(shared)
    }

    def "pickScvmmController finds the cloud's scvmmController when #reason"() {
        given:
        def own = Domain.controller()
        DataQuery captured

        when:
        def result = provider.pickScvmmController(Domain.cloud(id: 5L, config: config))

        then:
        getCalls * computeServerService.get(77L) >> null
        1 * computeServerService.find(_) >> { DataQuery q -> captured = q; own }
        0 * computeServerService.save(_)
        result.is(own)
        captured.filters.find { it.name == 'cloud.id' }.value == 5L
        captured.filters.find { it.name == 'computeServerType.code' }.value == 'scvmmController'

        where:
        reason                               | config                   | getCalls
        'no shared controller is configured' | [:]                      | 0
        'the shared controller is gone'      | [sharedController: '77'] | 1
    }

    def "pickScvmmController retypes and saves a legacy serverType=hypervisor server as the controller"() {
        given:
        def legacyServer = new ComputeServer(id: 9L, computeServerType: new ComputeServerType(code: 'scvmmHypervisor'))
        List<DataQuery> queries = []

        when:
        def result = provider.pickScvmmController(Domain.cloud(id: 5L))

        then:
        3 * computeServerService.find(_) >> { DataQuery q -> queries << q; queries.size() == 3 ? legacyServer : null }
        1 * computeServerService.save(legacyServer)
        result.is(legacyServer)
        result.computeServerType.code == 'scvmmController'
        queries[1].filters.find { it.name == 'computeServerType.code' }.value == 'scvmmHypervisor'
        queries[2].filters.find { it.name == 'serverType' }.value == 'hypervisor'
    }

    def "pickScvmmController retypes a legacy scvmmHypervisor-typed server without the serverType fallback"() {
        given:
        def legacyServer = new ComputeServer(id: 9L, computeServerType: new ComputeServerType(code: 'scvmmHypervisor'))

        when:
        def result = provider.pickScvmmController(Domain.cloud(id: 5L))

        then:
        2 * computeServerService.find(_) >>> [null, legacyServer]
        1 * computeServerService.save(legacyServer)
        result.computeServerType.code == 'scvmmController'
    }

    def "pickScvmmController returns null and saves nothing when the cloud has no controller"() {
        when:
        def result = provider.pickScvmmController(Domain.cloud(id: 5L))

        then:
        3 * computeServerService.find(_) >> null
        0 * computeServerService.save(_)
        result == null
    }

    // --- getScvmmServerOpts ---

    def "getScvmmServerOpts assembles identity, sizing, disks and network from the server"() {
        given:
        def network = new Network(id: 110L)
        def root = Domain.volume(id: 3L, rootVolume: true, maxStorage: 40L * GB)
        def data2 = Domain.volume(id: 5L, rootVolume: false, maxStorage: 20L * GB)
        def data1 = Domain.volume(id: 4L, rootVolume: false, maxStorage: 10L * GB)
        def server = Domain.server(id: 10L, name: 'vm-1', externalId: 'vm-ext-1', hostname: 'vm-1.local',
                maxMemory: 4L * GB, maxCores: 2L, maxCpu: 1L, maxStorage: 30L * GB, account: new Account(id: 7L),
                volumes: [root, data2, data1], config: [networkId: '110', scvmmCapabilityProfile: 'Hyper-V'])

        when:
        def opts = provider.getScvmmServerOpts(server)

        then:
        1 * cloudNetworkService.get(110L) >> network
        opts.name == 'vm-1'
        opts.vmId == 'vm-ext-1'
        opts.externalId == 'vm-ext-1'
        opts.serverId == 10L
        opts.server.is(server)
        opts.memory == 4L * GB
        opts.maxCores == 2L
        opts.maxCpu == 1L
        opts.osDiskSize == 40L * GB
        opts.maxTotalStorage == 70L * GB
        opts.dataDisks*.id == [4L, 5L]
        opts.serverFolder == 'morpheus\\morpheus_server_10'
        opts.network.is(network)
        opts.networkId == 110L
        opts.scvmmCapabilityProfile == 'Hyper-V'
        opts.accountId == 7L
        opts.config.networkId == '110'
    }

    def "getScvmmServerOpts falls back to the plan for sizing and to 1 core when neither sets it"() {
        given:
        def server = Domain.server(plan: Domain.plan(maxMemory: 2L * GB, maxStorage: 20L * GB, maxCores: null, maxCpu: null),
                maxMemory: null, maxStorage: null, maxCores: null, volumes: [])

        when:
        def opts = provider.getScvmmServerOpts(server)

        then:
        opts.memory == 2L * GB
        opts.osDiskSize == 20L * GB
        opts.maxTotalStorage == 20L * GB
        opts.maxCores == 1
        opts.maxCpu == 1
        opts.dataDisks == []
        opts.network == null
        opts.networkId == null
        opts.accountId == null
    }

    def "getScvmmServerOpts maps capability profile '#profile' to #expected"() {
        given:
        def server = Domain.server(maxMemory: 1L, maxCores: 1L, maxStorage: 1L, config: profile == null ? [:] : [scvmmCapabilityProfile: profile])

        expect:
        provider.getScvmmServerOpts(server).scvmmCapabilityProfile == expected

        where:
        profile   || expected
        'Hyper-V' || 'Hyper-V'
        '-1'      || null
        -1        || null
        null      || null
    }

    // --- getScvmmContainerOpts ---

    def "getScvmmContainerOpts assembles opts from the workload, its server and container config"() {
        given:
        def network = new Network(id: 110L)
        def server = Domain.server(id: 10L, externalId: 'vm-ext-1', resourcePool: new CloudPool(externalId: 'cluster-1'),
                volumes: [Domain.volume(maxStorage: 40L * GB), Domain.volume(id: 61L, rootVolume: false, maxStorage: 10L * GB)],
                serverOs: new OsType(platform: platform), osType: osType)
        def workload = Domain.workload(server: server, hostname: 'web-1', maxMemory: 4L * GB, maxCores: 2L, maxStorage: 30L * GB,
                instance: new Instance(plan: Domain.plan()), account: new Account(id: 7L),
                config: [networkId: '110', networkType: 'vlan', hostId: 12, scvmmCapabilityProfile: '-1'])

        when:
        def opts = provider.getScvmmContainerOpts(workload)

        then:
        1 * cloudNetworkService.get(110L) >> network
        opts.vmId == 'vm-ext-1'
        opts.name == 'vm-ext-1'
        opts.externalId == 'vm-ext-1'
        opts.serverId == 10L
        opts.hostname == 'web-1'
        opts.memory == 4L * GB
        opts.maxCores == 2L
        opts.osDiskSize == 40L * GB
        opts.maxTotalStorage == 50L * GB
        opts.dataDisks*.id == [61L]
        opts.network.is(network)
        opts.networkType == 'vlan'
        opts.resourcePool == 'cluster-1'
        opts.hostId == 12
        opts.scvmmCapabilityProfile == null
        opts.platform == expectedPlatform
        opts.accountId == 7L
        opts.serverFolder == 'morpheus\\morpheus_server_10'

        where:
        platform  | osType    || expectedPlatform
        null      | 'windows' || 'windows'
        'linux'   | 'linux'   || 'linux'
        'windows' | 'windows' || 'windows'
    }

    def "getScvmmContainerOpts falls back to the instance plan and has no network or pool when none is set"() {
        given:
        def workload = Domain.workload(server: Domain.server(volumes: []), maxMemory: null, maxCores: null, maxStorage: null,
                instance: new Instance(plan: Domain.plan(maxMemory: 2L * GB, maxStorage: 20L * GB, maxCores: 3L)))

        when:
        def opts = provider.getScvmmContainerOpts(workload)

        then:
        opts.memory == 2L * GB
        opts.maxCores == 3L
        opts.osDiskSize == 20L * GB
        opts.maxTotalStorage == 20L * GB
        opts.network == null
        opts.resourcePool == null
        opts.platform == 'linux'
    }

    // --- ScvmmCloudProvider.getMaxStorage / getMaxMemory / createSshOptions ---

    def "ScvmmCloudProvider.getMaxStorage parses disks=#disks to #expected"() {
        given:
        def cloudProvider = new ScvmmCloudProvider(plugin, context)

        expect:
        cloudProvider.getMaxStorage(serverInfo) == expected

        where:
        serverInfo                || expected
        [disks: '1099511627776'] || 1099511627776L
        [disks: 2048L]           || 2048L
        [disks: ' 512 ']         || 512L
        [disks: 'abc']           || 0L
        [disks: '']              || 0L
        [disks: '   ']           || 0L
        [disks: null]            || 0L
        [:]                      || 0L
        null                     || 0L
        disks = serverInfo?.disks
    }

    def "ScvmmCloudProvider.getMaxMemory parses memory=#memory to #expected"() {
        given:
        def cloudProvider = new ScvmmCloudProvider(plugin, context)

        expect:
        cloudProvider.getMaxMemory(serverInfo) == expected

        where:
        serverInfo                 || expected
        [memory: '17179869184']   || 17179869184L
        [memory: '16 GB']         || 0L
        [memory: '']              || 0L
        null                      || 0L
        memory = serverInfo?.memory
    }

    def "ScvmmCloudProvider.createSshOptions returns the four global SSH option codes, used by the unmanaged server type"() {
        given:
        def codes = ['computeServerType.global.sshHost', 'computeServerType.global.sshPort',
                     'computeServerType.global.sshUsername', 'computeServerType.global.sshPassword']

        expect:
        ScvmmCloudProvider.createSshOptions()*.code == codes
        new ScvmmCloudProvider(plugin, context).computeServerTypes.find { it.code == 'unmanaged' }.optionTypes*.code == codes
    }

    // --- StorageVolumeTypeHelper ---

    def "getAllStorageVolumeTypes returns the 10 SCVMM volume types with unique codes in catalog order"() {
        when:
        def all = StorageVolumeTypeHelper.allStorageVolumeTypes

        then:
        all*.code == [
                'scvmm-datastore', 'scvmm-registered-file-share-datastore',
                'scvmm-fixedsize-vhd', 'scvmm-dynamicallyexpanding-vhd', 'scvmm-differencing-vhd',
                'scvmm-fixedsize-vhdx', 'scvmm-dynamicallyexpanding-vhdx', 'scvmm-differencing-vhdx',
                'scvmm-linkedphysical', 'standard'
        ]
        all*.code.toSet().size() == all.size()
        all.every { it.enabled && it.autoDelete }
        all.every { it.name && it.displayName && it.description }
    }

    def "#group returns #codes"() {
        expect:
        StorageVolumeTypeHelper."$group"()*.code == codes

        where:
        group                                | codes
        'getDatastoreStorageTypes'           | ['scvmm-datastore', 'scvmm-registered-file-share-datastore']
        'getVhdDiskStorageTypes'             | ['scvmm-fixedsize-vhd', 'scvmm-dynamicallyexpanding-vhd', 'scvmm-differencing-vhd']
        'getVhdxDiskStorageTypes'            | ['scvmm-fixedsize-vhdx', 'scvmm-dynamicallyexpanding-vhdx', 'scvmm-differencing-vhdx']
        'getLinkedPhysicalDiskStorageTypes'  | ['scvmm-linkedphysical']
        'getStandardDiskStorageTypes'        | ['standard']
    }

    def "datastore types are non-default, fixed-label, fixed-size datastore volumes with a 1 GB minimum"() {
        expect:
        StorageVolumeTypeHelper.datastoreStorageTypes.every {
            it.volumeType == 'datastore' && it.volumeCategory == 'datastore' &&
                    !it.defaultType && !it.customLabel && !it.customSize && !it.allowSearch &&
                    it.minStorage == ComputeUtility.ONE_GIGABYTE && it.externalId == null
        }
        StorageVolumeTypeHelper.datastoreStorageTypes*.displayOrder == [1, 2]
    }

    def "disk type #code has externalId #externalId and displayOrder #order"() {
        given:
        def type = StorageVolumeTypeHelper.allStorageVolumeTypes.find { it.code == code }

        expect:
        type.externalId == externalId
        type.displayOrder == order
        type.volumeType == 'disk'
        type.volumeCategory == 'disk'
        type.defaultType && type.customLabel && type.customSize && type.hasDatastore && type.allowSearch

        where:
        code                               | externalId     | order
        'scvmm-fixedsize-vhd'              | 'fixed-vhd'    | 1
        'scvmm-dynamicallyexpanding-vhd'   | 'dynamic-vhd'  | 2
        'scvmm-differencing-vhd'           | 'differencing' | 3
        'scvmm-fixedsize-vhdx'             | 'fixed-vhdx'   | 4
        'scvmm-dynamicallyexpanding-vhdx'  | 'dynamic-vhdx' | 5
        'scvmm-differencing-vhdx'          | 'differencing' | 6
        'scvmm-linkedphysical'             | 'linked'       | 7
        'standard'                         | 'standard'     | 1
    }

    def "VHD and VHDX differencing types share the 'differencing' externalId"() {
        // pinned: a lookup by externalId alone cannot tell the two apart
        expect:
        StorageVolumeTypeHelper.allStorageVolumeTypes.findAll { it.externalId == 'differencing' }*.code ==
                ['scvmm-differencing-vhd', 'scvmm-differencing-vhdx']
    }

    def "each call returns fresh instances so callers cannot mutate the catalog"() {
        when:
        def first = StorageVolumeTypeHelper.standardDiskStorageTypes
        first[0].name = 'mutated'

        then:
        StorageVolumeTypeHelper.standardDiskStorageTypes[0].name == 'Standard'
        StorageVolumeTypeHelper.standardDiskStorageTypes[0].displayName == 'Disk'
    }

    // --- ScvmmApiService.getMapScvmmOsType ---

    def "getMapScvmmOsType maps VMM OS '#vmmName' to '#code'"() {
        expect:
        typeMapApi.getMapScvmmOsType(vmmName) == code

        where:
        vmmName                                         || code
        '64-bit edition of Windows 10'                  || 'windows.10.64'
        '64-bit edition of Windows Server 2012 Standard' || 'windows.server.2012'
        'Windows Server 2016 Datacenter'                || 'windows.server.2016'
        'Windows Server 2019 Standard'                  || 'windows.server.2019'
        'Windows Server 2022 Datacenter'                || 'windows.server.2022.dc.core'
        'Windows Server 2022 Standard'                  || 'windows.server.2022.std.core'
        'Windows Server 2025 Essentials'                || 'windows.server.2025'
        'CentOS Linux 7 (64 bit)'                       || 'cent.7.64'
        'Red Hat Enterprise Linux 7 (64 bit)'           || 'redhat.7.64'
        'Ubuntu Linux 20.04 (64 bit)'                   || 'ubuntu.20.04.64'
        'Ubuntu Linux 24.04 (64 bit)'                   || 'ubuntu.24.04.64'
        'Suse Linux Enterprise Server 12 (64 bit)'      || 'suse.12.64'
        'Oracle Linux 7 (64 bit)'                       || 'oracle.linux.64'
        'Other Linux (64 bit)'                          || 'linux.64'
        'None'                                          || 'other'
        ''                                              || 'other'
    }

    def "getMapScvmmOsType pins questionable table entries as-is: '#vmmName' -> '#code'"() {
        // Pinned current table data as-is; not fixed here.
        expect:
        typeMapApi.getMapScvmmOsType(vmmName) == code

        where:
        vmmName                             || code
        'Debian GNU/Linux 7 (64 bit)'       || 'debian.6.64'
        'Windows Web Server 2008'           || 'windows.8'
        'Windows Server 2012 R2 Datacenter' || 'windows.server.2012'
        'Ubuntu Linux 16.04 (64 bit)'       || 'ubuntu.64'
    }

    def "getMapScvmmOsType falls back to 'other' for an unknown or null name with no default"() {
        expect:
        typeMapApi.getMapScvmmOsType(name) == 'other'

        where:
        name << ['Windows Server 2030', 'windows server 2019 standard', null]
    }

    def "getMapScvmmOsType uses the default when it is itself a VMM name"() {
        expect:
        typeMapApi.getMapScvmmOsType('Plan 9', true, 'Other Linux (64 bit)') == 'linux.64'
    }

    def "getMapScvmmOsType treats the default as a VMM name, so an OsType code default falls through to 'other'"() {
        // VirtualMachineSync passes 'windows' as the default for Windows guests expecting a code; it is looked up as
        // a map key and misses, so the result is 'other'
        expect:
        typeMapApi.getMapScvmmOsType('Plan 9', true, 'windows') == 'other'
    }

    def "getMapScvmmOsType reverse lookup returns the first VMM name for code '#code'"() {
        expect:
        typeMapApi.getMapScvmmOsType(code, false) == vmmName

        where:
        code                          || vmmName
        'windows.server.2016'         || 'Windows Server 2016 Datacenter'
        'windows.8.64'                || '64-bit edition of Windows 8'
        'ubuntu.24.04.64'             || 'Ubuntu Linux 24.04 (64 bit)'
        'other'                       || 'None'
        'not-a-code'                  || null
        null                          || null
    }
}
