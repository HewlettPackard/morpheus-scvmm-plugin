// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusStorageVolumeTypeService
import com.morpheusdata.core.MorpheusVirtualImageLocationService
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.synchronous.MorpheusSynchronousOsTypeService
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService
import com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageLocationService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.OsType
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.projection.StorageVolumeIdentityProjection
import com.morpheusdata.model.projection.VirtualImageLocationIdentityProjection
import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.scvmm.support.Domain
import com.morpheusdata.scvmm.support.ScvmmSpec
import com.morpheusdata.scvmm.support.VmmPayloads
import groovy.json.JsonOutput
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Subject

/**
 * TemplatesSync (SCVMM templates + library VHDs -> VirtualImage / VirtualImageLocation / StorageVolume).
 * The private {@code apiService} is replaced via {@code .@apiService}; SyncTask runs for real over Observables.
 * {@code syncVolumes} logs {@code externalVolumes.encodeAsJSON()} eagerly, so ArrayList gets that Grails method here.
 */
class TemplatesSyncSpec extends ScvmmSpec {

    static final long GB = 1024L * 1024L * 1024L

    ScvmmApiService apiService = Mock()
    CloudProvider cloudProvider = Mock()
    MorpheusSynchronousVirtualImageLocationService locationService = Mock()
    MorpheusVirtualImageLocationService asyncLocationService = Mock()
    MorpheusSynchronousOsTypeService osTypeService = Mock()
    MorpheusStorageVolumeTypeService asyncVolumeTypeService = Mock()
    MorpheusSynchronousStorageVolumeTypeService volumeTypeService = Mock()
    MorpheusSynchronousDatastoreService datastoreService = Mock()

    Account owner = new Account(id: 3L)
    Cloud cloud = Domain.cloud(id: 1L, owner: owner, account: owner, regionCode: 'cloud-1')
    StorageVolumeType dynamicVhdx = new StorageVolumeType(id: 501L, code: 'scvmm-dynamicallyexpanding-vhdx')

    @Subject
    TemplatesSync sync

    def setup() {
        ArrayList.metaClass.encodeAsJSON = { -> JsonOutput.toJson(delegate) }
        virtualImageService.getLocation() >> locationService
        asyncVirtualImageService.getLocation() >> asyncLocationService
        services.getOsType() >> osTypeService
        asyncStorageVolumeService.getStorageVolumeType() >> asyncVolumeTypeService
        storageVolumeService.getStorageVolumeType() >> volumeTypeService
        cloudService.getDatastore() >> datastoreService
        asyncVolumeTypeService.find(_ as DataQuery) >> Maybe.just(dynamicVhdx)
        sync = new TemplatesSync(cloud, Domain.controller(), context, cloudProvider)
        sync.@apiService = apiService
        apiService.getScvmmZoneAndHypervisorOpts(*_) >> [sshHost: 'scvmm.local']
    }

    def cleanup() {
        GroovySystem.metaClassRegistry.removeMetaClass(ArrayList)
    }

    private static VirtualImageLocationIdentityProjection locProj(Long id, String externalId) {
        new VirtualImageLocationIdentityProjection(id: id, externalId: externalId)
    }

    // ================================================================ execute

    def "execute does nothing when listTemplates #why"() {
        given:
        apiService.listTemplates(_) >> result

        when:
        sync.execute()

        then:
        0 * locationService._
        0 * asyncLocationService._

        where:
        why                    | result
        'fails'                | [success: false, templates: []]
        'returns no templates' | [success: true, templates: []]
    }

    def "execute adds a VirtualImage with its location for a template not yet known"() {
        given:
        def template = VmmPayloads.template(ID: 'tpl-1', Name: 'Win2022', Memory: 2048, Generation: 1, Disks: [])
        apiService.listTemplates(_) >> [success: true, templates: [template]]
        locationService.listIdentityProjections(_ as DataQuery) >> []
        asyncLocationService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
        asyncVirtualImageService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
        apiService.getMapScvmmOsType('Windows Server 2022 Standard', true, 'Other Linux (64 bit)') >> 'windows.server.2022'
        osTypeService.find(_ as DataQuery) >> new OsType(code: 'windows.server.2022')
        def savedLocation = new VirtualImageLocation(id: 77L, volumes: [])
        asyncLocationService.find(_ as DataQuery) >> Maybe.just(savedLocation)
        VirtualImage created

        when:
        sync.execute()

        then:
        1 * asyncVirtualImageService.create({ it.size() == 1 }, cloud) >> { List l, Cloud c -> created = l[0]; Single.just(true) }
        created.name == 'Win2022'
        created.externalId == 'tpl-1'
        created.code == 'scvmm.image.1.tpl-1'
        created.imageType.toString() == 'vhdx'
        created.minRam == 2048L * 1024L * 1024L
        created.isPublic == false
        created.imageRegion == 'cloud-1'
        created.getConfigProperty('generation') == 'generation1'
        created.imageLocations*.externalId == ['tpl-1']
        created.osType.code == 'windows.server.2022'
        0 * asyncVirtualImageService.save(*_)
    }

    def "execute derives the generation and image type of a library VHD from its format"() {
        given:
        def vhd = [ID: 'vhd-1', Name: 'ubuntu.vhd', VHDFormatType: 'VHD', Location: '\\\\lib\\ubuntu.vhd', OperatingSystem: 'Ubuntu', Disks: []]
        apiService.listTemplates(_) >> [success: true, templates: [vhd]]
        locationService.listIdentityProjections(_ as DataQuery) >> []
        asyncLocationService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
        asyncVirtualImageService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
        asyncLocationService.find(_ as DataQuery) >> Maybe.just(new VirtualImageLocation(id: 77L, volumes: []))
        VirtualImage created

        when:
        sync.execute()

        then:
        1 * asyncVirtualImageService.create(_, cloud) >> { List l, Cloud c -> created = l[0]; Single.just(true) }
        created.imageType.toString() == 'vhd'
        created.remotePath == '\\\\lib\\ubuntu.vhd'
        created.getConfigProperty('generation') == 'generation1'
        created.minRam == null
    }

    def "execute removes duplicate locations for the same externalId before syncing"() {
        given:
        apiService.listTemplates(_) >> [success: true, templates: [VmmPayloads.template(ID: 'tpl-1')]]
        def keep = locProj(1L, 'tpl-1'), dupe = locProj(2L, 'tpl-1')
        locationService.listIdentityProjections(_ as DataQuery) >> [keep, dupe]
        asyncLocationService.listIdentityProjections(_ as DataQuery) >> Observable.just(keep)
        asyncLocationService.listById(_) >> Observable.empty()

        when:
        sync.execute()

        then:
        1 * asyncLocationService.remove([2L]) >> Single.just(true)
    }

    def "execute removes locations whose template disappeared from VMM"() {
        given:
        apiService.listTemplates(_) >> [success: true, templates: [VmmPayloads.template(ID: 'tpl-1')]]
        def gone = locProj(9L, 'tpl-gone')
        locationService.listIdentityProjections(_ as DataQuery) >> [gone]
        asyncLocationService.listIdentityProjections(_ as DataQuery) >> Observable.just(gone)
        asyncVirtualImageService.listIdentityProjections(_ as DataQuery) >> Observable.empty()
        asyncLocationService.find(_ as DataQuery) >> Maybe.just(new VirtualImageLocation(id: 77L, volumes: []))
        asyncVirtualImageService.create(*_) >> Single.just(true)

        when:
        sync.execute()

        then: 'removal is not gated on user-uploaded images'
        1 * asyncLocationService.remove([gone]) >> Single.just(true)
    }

    def "removeMissingVirtualImages swallows a removal failure"() {
        when:
        sync.removeMissingVirtualImages([locProj(9L, 'x')])

        then:
        1 * asyncLocationService.remove(_ as List) >> { throw new RuntimeException('db') }
        noExceptionThrown()
    }

    // ================================================================ updateMatchedVirtualImageLocations

    private SyncTask.UpdateItem<VirtualImageLocation, Map> updateItem(VirtualImageLocation loc, Map template) {
        new SyncTask.UpdateItem<VirtualImageLocation, Map>(existingItem: loc, masterItem: template)
    }

    def "a renamed template renames the location and, for images owned by this cloud, the image"() {
        given:
        def image = new VirtualImage(id: 50L, name: 'Old', refId: '1', isPublic: true)
        def loc = new VirtualImageLocation(id: 5L, externalId: 'tpl-1', imageName: 'Old', virtualImage: image, refId: 1L, code: null)
        locationService.list(_ as DataQuery) >> [loc]
        asyncVirtualImageService.listIdentityProjections(1L) >> Observable.just(new com.morpheusdata.model.projection.VirtualImageIdentityProjection(id: 50L, externalId: 'tpl-1'))
        asyncVirtualImageService.listById([50L]) >> Observable.just(image)

        when:
        sync.updateMatchedVirtualImageLocations([updateItem(loc, VmmPayloads.template(ID: 'tpl-1', Name: 'New', Disks: []))])

        then:
        1 * asyncLocationService.save([loc], cloud) >> Single.just(true)
        1 * asyncVirtualImageService.save([image], cloud) >> Single.just(true)
        loc.imageName == 'New'
        image.name == 'New'
        loc.code == 'scvmm.image.1.tpl-1'
        image.isPublic == false
        loc.isPublic == false
    }

    def "an unchanged location is not saved"() {
        given:
        def image = new VirtualImage(id: 50L, name: 'Same', isPublic: false)
        def loc = new VirtualImageLocation(id: 5L, externalId: 'tpl-1', imageName: 'Same', virtualImage: image, refId: 1L, code: 'c')
        locationService.list(_ as DataQuery) >> [loc]
        asyncVirtualImageService.listIdentityProjections(1L) >> Observable.just(new com.morpheusdata.model.projection.VirtualImageIdentityProjection(id: 50L, externalId: 'tpl-1'))
        asyncVirtualImageService.listById([50L]) >> Observable.just(image)

        when:
        sync.updateMatchedVirtualImageLocations([updateItem(loc, VmmPayloads.template(ID: 'tpl-1', Name: 'Same', Disks: []))])

        then:
        0 * asyncLocationService.save(*_)
        0 * asyncVirtualImageService.save(*_)
    }

    def "a template matched by image but without a location record gets a new location"() {
        given:
        def image = new VirtualImage(id: 50L, name: 'Win2022', externalId: 'tpl-1', isPublic: true)
        def loc = new VirtualImageLocation(id: 5L, externalId: 'tpl-1', virtualImage: image)
        locationService.list(_ as DataQuery) >> []
        asyncVirtualImageService.listIdentityProjections(1L) >> Observable.just(new com.morpheusdata.model.projection.VirtualImageIdentityProjection(id: 50L, externalId: 'tpl-1'))
        asyncVirtualImageService.listById([50L]) >> Observable.just(image)
        List created

        when:
        sync.updateMatchedVirtualImageLocations([updateItem(loc, VmmPayloads.template(ID: 'tpl-1', Name: 'Win2022'))])

        then:
        1 * asyncLocationService.create(_ as List, cloud) >> { List l, Cloud c -> created = l; Single.just(true) }
        1 * asyncVirtualImageService.save([image], cloud) >> Single.just(true)
        created*.code == ['scvmm.image.1.tpl-1']
        created[0].imageRegion == 'cloud-1'
        image.owner.is(owner)
        image.deleted == false
    }

    // ================================================================ syncVolumes / storage volumes

    def "syncVolumes drops null disk entries and creates one volume per real disk (MORPH-19614 regression)"() {
        given:
        def loc = new VirtualImageLocation(id: 77L, refType: 'ComputeZone', refId: 1L, volumes: [])
        List<StorageVolume> created

        when:
        def changed = sync.syncVolumes(loc, [VmmPayloads.templateDisk(ID: 'd1', VolumeType: 'BootAndSystem'), null,
                                             VmmPayloads.templateDisk(ID: 'd2', VolumeType: 'None', TotalSize: 10L * GB)])

        then:
        1 * asyncStorageVolumeService.create(_ as List, loc) >> { List l, VirtualImageLocation x -> created = l; Single.just(true) }
        created*.externalId == ['d1', 'd2']
        created*.maxStorage == [42949672960L, 10L * GB]
        created*.cloudId == [1L, 1L]
        created*.displayOrder == [0, 1]
        !changed
    }

    def "addMissingStorageVolumes leaves maxStorage null for a disk without a size and keeps a provided deviceName"() {
        given:
        def loc = new VirtualImageLocation(id: 77L, volumes: [new StorageVolumeIdentityProjection(id: 1L)])
        List<StorageVolume> created

        when:
        sync.addMissingStorageVolumes([[ID: 'd9', VHDType: 'DynamicallyExpanding', VHDFormat: 'VHDX', deviceName: 'xvdb', VolumeType: 'None']], loc, 1, 0, false)

        then:
        1 * asyncStorageVolumeService.create(_ as List, loc) >> { List l, VirtualImageLocation x -> created = l; Single.just(true) }
        created[0].maxStorage == null
        created[0].deviceName == 'xvdb'
        created[0].rootVolume == false
        created[0].removable
    }

    def "addMissingStorageVolumes throws when the StorageVolumeType for the disk is unknown"() {
        given:
        def svc = Mock(MorpheusStorageVolumeTypeService) { find(_) >> Maybe.empty() }
        asyncStorageVolumeService.getStorageVolumeType() >> svc
        def fresh = new TemplatesSync(cloud, null, context, cloudProvider)
        fresh.@apiService = apiService

        when:
        fresh.addMissingStorageVolumes([VmmPayloads.templateDisk()], new VirtualImageLocation(volumes: []), 0, 0, false)

        then:
        thrown(NullPointerException)
    }

    def "updateMatchedStorageVolumes resizes only outside the 1 GB tolerance (#why)"() {
        given:
        def volume = new StorageVolume(id: 1L, maxStorage: 40L * GB, internalId: 'path', rootVolume: true, removable: false, type: dynamicVhdx)
        def loc = new VirtualImageLocation(volumes: [new StorageVolumeIdentityProjection(id: 1L)])
        def disk = VmmPayloads.templateDisk(TotalSize: size, Location: 'path')

        when:
        sync.updateMatchedStorageVolumes([new SyncTask.UpdateItem(existingItem: volume, masterItem: disk)], loc, 0, false)

        then:
        saves * asyncStorageVolumeService.save(volume) >> Single.just(volume)
        volume.maxStorage == expected

        where:
        why            | size              || saves | expected
        'within +1GB'  | 40L * GB + 1000L  || 0     | 40L * GB
        'exactly +1GB' | 41L * GB          || 1     | 41L * GB
        'shrunk'       | 20L * GB          || 1     | 20L * GB
        'size 0'       | 0L                || 0     | 40L * GB
    }

    def "updateMatchedStorageVolumes rewrites the VHD path and the root/removable flags"() {
        given:
        def volume = new StorageVolume(id: 1L, maxStorage: 40L * GB, internalId: 'old', rootVolume: false, removable: true, type: dynamicVhdx)
        def loc = new VirtualImageLocation(volumes: [new StorageVolumeIdentityProjection(id: 1L), new StorageVolumeIdentityProjection(id: 2L)])

        when:
        sync.updateMatchedStorageVolumes([new SyncTask.UpdateItem(existingItem: volume,
                masterItem: VmmPayloads.templateDisk(TotalSize: 40L * GB, Location: 'new', VolumeType: 'BootAndSystem'))], loc, 0, false)

        then:
        1 * asyncStorageVolumeService.save(volume) >> Single.just(volume)
        volume.internalId == 'new'
        volume.rootVolume
        !volume.removable
    }

    def "removeMissingStorageVolumes detaches and removes the volume from the location"() {
        given:
        def volume = new StorageVolume(id: 1L, datastore: new Datastore(id: 2L))
        def loc = new VirtualImageLocation(id: 77L)

        when:
        sync.removeMissingStorageVolumes([volume], loc, false)

        then:
        1 * asyncStorageVolumeService.save(volume) >> Single.just(volume)
        1 * asyncStorageVolumeService.remove([volume], loc) >> Single.just(true)
        1 * asyncStorageVolumeService.remove(volume) >> Single.just(true)
        volume.datastore == null
    }

    // ================================================================ loadDatastoreForVolume

    def "loadDatastoreForVolume resolves #why"() {
        when:
        def ds = sync.loadDatastoreForVolume(hostVolumeId, fileShareId, partitionId)

        then:
        storageVolumeService.find(_ as DataQuery) >>> volumes
        datastoreService.find(_ as DataQuery) >> new Datastore(id: 9L)
        ds?.id == expected

        where:
        why                                  | hostVolumeId | fileShareId | partitionId || volumes                                                          | expected
        'by host volume'                     | 'hv-1'       | null        | null        || [new StorageVolume(datastore: new Datastore(id: 1L))]          | 1L
        'by partition when host volume miss' | 'hv-1'       | null        | 'part-1'    || [null, new StorageVolume(datastore: new Datastore(id: 2L))]    | 2L
        'by file share'                      | null         | 'share-1'   | null        || [null]                                                           | 9L
        'to nothing without ids'             | null         | null        | null        || [null]                                                           | null
    }
}
