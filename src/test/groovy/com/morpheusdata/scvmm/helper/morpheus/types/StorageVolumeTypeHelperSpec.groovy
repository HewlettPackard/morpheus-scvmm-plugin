package com.morpheusdata.scvmm.helper.morpheus.types

import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.StorageVolumeType
import spock.lang.Specification

class StorageVolumeTypeHelperSpec extends Specification {

    def "getDatastoreStorageTypes should return two datastore volume types"() {
        when:
        Collection<StorageVolumeType> result = StorageVolumeTypeHelper.getDatastoreStorageTypes()

        then:
        result.size() == 2
        result.every { it.volumeType == 'datastore' }
        result.every { it.volumeCategory == 'datastore' }
        result.every { it.enabled == true }
        result.every { it.autoDelete == true }
        result.every { it.customLabel == false }
        result.every { it.customSize == false }
        result.every { it.defaultType == false }
        result.every { it.allowSearch == false }
        result.every { it.minStorage == (1L * ComputeUtility.ONE_GIGABYTE) }

        and: "first type is SCVMM Datastore"
        def datastoreType = result.find { it.code == 'scvmm-datastore' }
        datastoreType.displayName == 'SCVMM Datastore'
        datastoreType.name == 'SCVMM Datastore'
        datastoreType.displayOrder == 1

        and: "second type is SCVMM File Share"
        def fileshareType = result.find { it.code == 'scvmm-registered-file-share-datastore' }
        fileshareType.displayName == 'SCVMM Registered File Share'
        fileshareType.name == 'SCVMM Registered File Share'
        fileshareType.displayOrder == 2
    }

    def "getVhdDiskStorageTypes should return three VHD disk types"() {
        when:
        Collection<StorageVolumeType> result = StorageVolumeTypeHelper.getVhdDiskStorageTypes()

        then:
        result.size() == 3
        result.every { it.volumeType == 'disk' }
        result.every { it.volumeCategory == 'disk' }
        result.every { it.enabled == true }
        result.every { it.customLabel == true }
        result.every { it.customSize == true }
        result.every { it.defaultType == true }
        result.every { it.autoDelete == true }
        result.every { it.hasDatastore == true }
        result.every { it.allowSearch == true }

        and: "fixed VHD type is configured correctly"
        def fixedVhd = result.find { it.code == 'scvmm-fixedsize-vhd' }
        fixedVhd.externalId == 'fixed-vhd'
        fixedVhd.displayOrder == 1

        and: "dynamic VHD type is configured correctly"
        def dynamicVhd = result.find { it.code == 'scvmm-dynamicallyexpanding-vhd' }
        dynamicVhd.externalId == 'dynamic-vhd'
        dynamicVhd.displayOrder == 2

        and: "differencing VHD type is configured correctly"
        def differencingVhd = result.find { it.code == 'scvmm-differencing-vhd' }
        differencingVhd.externalId == 'differencing'
        differencingVhd.displayOrder == 3
    }

    def "getVhdxDiskStorageTypes should return three VHDX disk types"() {
        when:
        Collection<StorageVolumeType> result = StorageVolumeTypeHelper.getVhdxDiskStorageTypes()

        then:
        result.size() == 3
        result.every { it.volumeType == 'disk' }
        result.every { it.volumeCategory == 'disk' }
        result.every { it.enabled == true }
        result.every { it.customLabel == true }
        result.every { it.customSize == true }
        result.every { it.defaultType == true }
        result.every { it.autoDelete == true }
        result.every { it.hasDatastore == true }
        result.every { it.allowSearch == true }

        and: "fixed VHDX type is configured correctly"
        def fixedVhdx = result.find { it.code == 'scvmm-fixedsize-vhdx' }
        fixedVhdx.externalId == 'fixed-vhdx'
        fixedVhdx.displayOrder == 4

        and: "dynamic VHDX type is configured correctly"
        def dynamicVhdx = result.find { it.code == 'scvmm-dynamicallyexpanding-vhdx' }
        dynamicVhdx.externalId == 'dynamic-vhdx'
        dynamicVhdx.displayOrder == 5

        and: "differencing VHDX type is configured correctly"
        def differencingVhdx = result.find { it.code == 'scvmm-differencing-vhdx' }
        differencingVhdx.externalId == 'differencing'
        differencingVhdx.displayOrder == 6
    }

    def "getLinkedPhysicalDiskStorageTypes should return one linked physical disk type"() {
        when:
        Collection<StorageVolumeType> result = StorageVolumeTypeHelper.getLinkedPhysicalDiskStorageTypes()

        then:
        result.size() == 1
        def linkedPhysical = result[0]
        linkedPhysical.code == 'scvmm-linkedphysical'
        linkedPhysical.displayName == 'Linked Physical'
        linkedPhysical.name == 'Linked Physical'
        linkedPhysical.externalId == 'linked'
        linkedPhysical.volumeType == 'disk'
        linkedPhysical.volumeCategory == 'disk'
        linkedPhysical.enabled == true
        linkedPhysical.displayOrder == 7
        linkedPhysical.customLabel == true
        linkedPhysical.customSize == true
        linkedPhysical.defaultType == true
        linkedPhysical.autoDelete == true
        linkedPhysical.hasDatastore == true
        linkedPhysical.allowSearch == true
    }

    def "getStandardDiskStorageTypes should return one standard disk type"() {
        when:
        Collection<StorageVolumeType> result = StorageVolumeTypeHelper.getStandardDiskStorageTypes()

        then:
        result.size() == 1
        def standard = result[0]
        standard.code == 'standard'
        standard.displayName == 'Disk'
        standard.name == 'Standard'
        standard.description == 'Standard'
        standard.externalId == 'standard'
        standard.volumeType == 'disk'
        standard.volumeCategory == 'disk'
        standard.enabled == true
        standard.displayOrder == 1
        standard.customLabel == true
        standard.customSize == true
        standard.defaultType == true
        standard.autoDelete == true
        standard.hasDatastore == true
        standard.allowSearch == true
    }

    def "getAllStorageVolumeTypes should return all storage volume types"() {
        when:
        Collection<StorageVolumeType> result = StorageVolumeTypeHelper.getAllStorageVolumeTypes()

        then:
        result.size() == 10 // 2 datastore + 3 VHD + 3 VHDX + 1 linked + 1 standard

        and: "contains all datastore types"
        def datastoreTypes = result.findAll { it.volumeType == 'datastore' }
        datastoreTypes.size() == 2

        and: "contains all disk types"
        def diskTypes = result.findAll { it.volumeType == 'disk' }
        diskTypes.size() == 8

        and: "contains all expected codes"
        def codes = result.collect { it.code }
        codes.containsAll([
                'scvmm-datastore',
                'scvmm-registered-file-share-datastore',
                'scvmm-fixedsize-vhd',
                'scvmm-dynamicallyexpanding-vhd',
                'scvmm-differencing-vhd',
                'scvmm-fixedsize-vhdx',
                'scvmm-dynamicallyexpanding-vhdx',
                'scvmm-differencing-vhdx',
                'scvmm-linkedphysical',
                'standard'
        ])
    }

    def "all volume types should have required fields populated"() {
        when:
        Collection<StorageVolumeType> allTypes = StorageVolumeTypeHelper.getAllStorageVolumeTypes()

        then:
        allTypes.every { type ->
            type.code != null && !type.code.isEmpty()
            type.displayName != null && !type.displayName.isEmpty()
            type.name != null && !type.name.isEmpty()
            type.volumeType != null && !type.volumeType.isEmpty()
            type.volumeCategory != null && !type.volumeCategory.isEmpty()
            type.enabled != null
            type.displayOrder != null
        }
    }
}