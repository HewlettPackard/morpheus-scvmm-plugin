package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.VirtualImageLocationIdentityProjection
import com.morpheusdata.scvmm.ScvmmApiService
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

class TemplatesSyncSpec extends Specification {

    MorpheusContext mockContext
    MorpheusServices mockServices
    MorpheusAsyncServices mockAsync
    Cloud cloud
    ComputeServer node
    CloudProvider mockCloudProvider
    ScvmmApiService mockApiService
    TemplatesSync templatesSync

    def setup() {
        mockContext = Mock(MorpheusContext)
        mockServices = Mock(MorpheusServices)
        mockAsync = Mock(MorpheusAsyncServices)
        mockCloudProvider = Mock(CloudProvider)
        mockApiService = Mock(ScvmmApiService)
        
        cloud = new Cloud(id: 1L, regionCode: 'us-east-1', owner: new Account(id: 1L), account: new Account(id: 1L))
        node = new ComputeServer(id: 2L)
        
        mockContext.services >> mockServices
        mockContext.async >> mockAsync
        
        templatesSync = new TemplatesSync(cloud, node, mockContext, mockCloudProvider)
        templatesSync.@apiService = mockApiService
    }

    def "constructor initializes all fields correctly"() {
        when:
        def sync = new TemplatesSync(cloud, node, mockContext, mockCloudProvider)

        then:
        sync.cloud == cloud
        sync.node == node
        sync.context == mockContext
        sync.@cloudProvider == mockCloudProvider
        sync.apiService != null
    }

    def "execute with API failure returns gracefully"() {
        given:
        def scvmmOpts = [zone: 1L]
        def listResults = [success: false, templates: null]

        when:
        templatesSync.execute()

        then:
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, cloud, node) >> scvmmOpts
        1 * mockApiService.listTemplates(scvmmOpts) >> listResults
        noExceptionThrown()
    }

    def "execute with null templates returns gracefully"() {
        given:
        def scvmmOpts = [zone: 1L]
        def listResults = [success: true, templates: null]

        when:
        templatesSync.execute()

        then:
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, cloud, node) >> scvmmOpts
        1 * mockApiService.listTemplates(scvmmOpts) >> listResults
        noExceptionThrown()
    }

    def "execute with empty templates list returns gracefully"() {
        given:
        def scvmmOpts = [zone: 1L]
        def listResults = [success: true, templates: []]

        when:
        templatesSync.execute()

        then:
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, cloud, node) >> scvmmOpts
        1 * mockApiService.listTemplates(scvmmOpts) >> listResults
        noExceptionThrown()
    }

    // ========================================
    // removeMissingVirtualImages() tests
    // ========================================

    def "removeMissingVirtualImages with empty list"() {
        given:
        def removeList = []
        def mockVirtualImageService = Mock(com.morpheusdata.core.MorpheusVirtualImageService)
        def mockLocationService = Mock(com.morpheusdata.core.MorpheusVirtualImageLocationService)
        mockAsync.virtualImage >> mockVirtualImageService
        mockVirtualImageService.location >> mockLocationService
        mockLocationService.remove(removeList) >> Single.just(true)

        when:
        templatesSync.removeMissingVirtualImages(removeList)

        then:
        1 * mockLocationService.remove(removeList) >> Single.just(true)
        noExceptionThrown()
    }

    def "removeMissingVirtualImages with single item"() {
        given:
        def projection = new VirtualImageLocationIdentityProjection(id: 123L, externalId: 'ext-123')
        def removeList = [projection]
        def mockVirtualImageService = Mock(com.morpheusdata.core.MorpheusVirtualImageService)
        def mockLocationService = Mock(com.morpheusdata.core.MorpheusVirtualImageLocationService)
        mockAsync.virtualImage >> mockVirtualImageService
        mockVirtualImageService.location >> mockLocationService
        mockLocationService.remove(removeList) >> Single.just(true)

        when:
        templatesSync.removeMissingVirtualImages(removeList)

        then:
        1 * mockLocationService.remove(removeList) >> Single.just(true)
        noExceptionThrown()
    }

    def "removeMissingVirtualImages handles exception gracefully"() {
        given:
        def projection = new VirtualImageLocationIdentityProjection(id: 99L, externalId: 'ext-99')
        def removeList = [projection]
        def mockVirtualImageService = Mock(com.morpheusdata.core.MorpheusVirtualImageService)
        def mockLocationService = Mock(com.morpheusdata.core.MorpheusVirtualImageLocationService)
        mockAsync.virtualImage >> mockVirtualImageService
        mockVirtualImageService.location >> mockLocationService
        mockLocationService.remove(removeList) >> { throw new RuntimeException("Database error") }

        when:
        templatesSync.removeMissingVirtualImages(removeList)

        then:
        noExceptionThrown()
    }

    // ========================================
    // loadDatastoreForVolume() tests
    // ========================================

    def "loadDatastoreForVolume with all null parameters returns null"() {
        when:
        def result = templatesSync.loadDatastoreForVolume(null, null, null)

        then:
        result == null
    }

    def "loadDatastoreForVolume with empty string returns null"() {
        when:
        def result = templatesSync.loadDatastoreForVolume("", null, null)

        then:
        result == null
    }

    def "loadDatastoreForVolume with only partitionUniqueId returns null"() {
        when:
        def result = templatesSync.loadDatastoreForVolume(null, null, "partition123")

        then:
        result == null
    }

    // ========================================
    // getStorageVolumeType() tests
    // ========================================

    def "getStorageVolumeType with valid code returns volume type"() {
        given:
        def mockVolumeTypeService = Mock(com.morpheusdata.core.MorpheusStorageVolumeTypeService)
        def mockStorageVolumeService = Mock(com.morpheusdata.core.MorpheusStorageVolumeService)
        def mockVolumeType = new StorageVolumeType(code: 'scvmm-dynamicallyexpanding-vhd', name: 'Dynamic VHD')
        mockAsync.storageVolume >> mockStorageVolumeService
        mockStorageVolumeService.storageVolumeType >> mockVolumeTypeService
        mockVolumeTypeService.find(_) >> Maybe.just(mockVolumeType)

        when:
        def result = templatesSync.getStorageVolumeType('scvmm-dynamicallyexpanding-vhd')

        then:
        result == mockVolumeType
    }

    def "getStorageVolumeType with null code uses standard type"() {
        given:
        def mockVolumeTypeService = Mock(com.morpheusdata.core.MorpheusStorageVolumeTypeService)
        def mockStorageVolumeService = Mock(com.morpheusdata.core.MorpheusStorageVolumeService)
        def mockVolumeType = new StorageVolumeType(code: 'standard', name: 'Standard')
        mockAsync.storageVolume >> mockStorageVolumeService
        mockStorageVolumeService.storageVolumeType >> mockVolumeTypeService
        mockVolumeTypeService.find(_) >> Maybe.just(mockVolumeType)

        when:
        def result = templatesSync.getStorageVolumeType(null)

        then:
        result == mockVolumeType
    }

    // ========================================
    // buildStorageVolume() tests
    // ========================================

    def "buildStorageVolume creates basic volume correctly"() {
        given:
        def mockSyncStorageVolumeTypeService = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService)
        def mockSyncStorageVolumeService = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService)
        def mockVolumeType = new StorageVolumeType(code: 'standard', name: 'Standard')
        mockServices.storageVolume >> mockSyncStorageVolumeService
        mockSyncStorageVolumeService.storageVolumeType >> mockSyncStorageVolumeTypeService
        mockSyncStorageVolumeTypeService.find(_) >> mockVolumeType

        def account = new Account(id: 1L)
        def addLocation = new VirtualImageLocation(refType: 'ComputeZone', refId: 123L)
        def volumeConfig = [
            name: 'test-volume',
            size: 10737418240L,
            rootVolume: true,
            deviceName: 'sda',
            externalId: 'ext-123',
            internalId: '/path/to/disk.vhd',
            displayOrder: 0
        ]

        when:
        def result = templatesSync.buildStorageVolume(account, addLocation, volumeConfig)

        then:
        result.name == 'test-volume'
        result.account == account
        result.maxStorage == 10737418240L
        result.rootVolume == true
        result.deviceName == 'sda'
        result.externalId == 'ext-123'
        result.internalId == '/path/to/disk.vhd'
        result.cloudId == 123L
        result.removable == false
        result.displayOrder == 0
    }

    def "buildStorageVolume with datastore option sets datastore fields"() {
        given:
        def mockSyncStorageVolumeTypeService = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService)
        def mockSyncStorageVolumeService = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService)
        def mockVolumeType = new StorageVolumeType(code: 'standard', name: 'Standard')
        mockServices.storageVolume >> mockSyncStorageVolumeService
        mockSyncStorageVolumeService.storageVolumeType >> mockSyncStorageVolumeTypeService
        mockSyncStorageVolumeTypeService.find(_) >> mockVolumeType

        def account = new Account(id: 1L)
        def addLocation = new VirtualImageLocation(refType: 'ComputeZone', refId: 123L)
        def volumeConfig = [
            name: 'test-volume',
            size: 10737418240L,
            datastoreId: 456L,
            rootVolume: false
        ]

        when:
        def result = templatesSync.buildStorageVolume(account, addLocation, volumeConfig)

        then:
        result.datastoreOption == '456'
        result.refType == 'Datastore'
        result.refId == 456L
        result.removable == true
    }

    // ========================================
    // execute() tests with template data
    // ========================================

    def "execute with template containing disks processes volumes correctly"() {
        given:
        def scvmmOpts = [zone: 1L]
        def template = [
            ID: 'template-123',
            Name: 'Test Template',
            Disks: [
                [
                    ID: 'disk-1',
                    Name: 'OS Disk',
                    TotalSize: 42949672960,
                    VolumeType: 'BootAndSystem',
                    VHDType: 'DynamicallyExpanding',
                    VHDFormat: 'VHDX',
                    Location: '/vhds/osdisk.vhdx'
                ]
            ]
        ]
        def listResults = [success: true, templates: [template]]

        // Mock all required services
        def mockVirtualImageLocationService = Mock(com.morpheusdata.core.MorpheusVirtualImageLocationService)
        def mockVirtualImageService = Mock(com.morpheusdata.core.MorpheusVirtualImageService)
        def mockSyncVirtualImageService = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService)
        def mockSyncVirtualImageLocationService = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageLocationService)
        def mockAsyncVolumeTypeService = Mock(com.morpheusdata.core.MorpheusStorageVolumeTypeService)
        def mockAsyncStorageVolumeService = Mock(com.morpheusdata.core.MorpheusStorageVolumeService)
        def mockSyncStorageVolumeService = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService)
        
        def existingLocation = new VirtualImageLocation(id: 1L, externalId: 'template-123', refType: 'ComputeZone', refId: cloud.id)
        
        // Set up mock services - Synchronous
        mockContext.services.virtualImage >> mockSyncVirtualImageService
        mockContext.services.virtualImage.location >> mockSyncVirtualImageLocationService
        mockSyncVirtualImageService.location >> mockSyncVirtualImageLocationService
        mockServices.storageVolume >> mockSyncStorageVolumeService

        // Set up mock services - Asynchronous
        mockAsync.virtualImage >> mockVirtualImageService
        mockAsync.storageVolume >> mockAsyncStorageVolumeService
        mockAsyncStorageVolumeService.storageVolumeType >> mockAsyncVolumeTypeService
        mockVirtualImageService.location >> mockVirtualImageLocationService
        
        // Mock method responses
        mockSyncVirtualImageLocationService.find(_) >> existingLocation
        mockSyncVirtualImageLocationService.list(_) >> [existingLocation]
        mockSyncVirtualImageLocationService.listIdentityProjections(_) >> [existingLocation]

        // Mock asynchronous method responses
        mockVirtualImageLocationService.listIdentityProjections(_) >> io.reactivex.rxjava3.core.Observable.just(existingLocation)
        mockVirtualImageLocationService.listById(_) >> io.reactivex.rxjava3.core.Observable.just(existingLocation)
        mockVirtualImageLocationService.save(_) >> io.reactivex.rxjava3.core.Single.just(true)
        mockVirtualImageLocationService.create(_, _) >> io.reactivex.rxjava3.core.Single.just(true)
        mockVirtualImageLocationService.remove(_) >> io.reactivex.rxjava3.core.Single.just(true)

        // Mock service chain responses for synchronous calls
        mockContext.services.virtualImage.location.listIdentityProjections(_) >> [existingLocation]
        
        when:
        templatesSync.execute()

        then:
        1 * mockApiService.getScvmmZoneAndHypervisorOpts(mockContext, cloud, node) >> scvmmOpts
        1 * mockApiService.listTemplates(scvmmOpts) >> listResults
        noExceptionThrown()
    }
}
