package com.morpheusdata.scvmm.sync

import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.OsType
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.projection.StorageVolumeIdentityProjection
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import com.morpheusdata.model.projection.VirtualImageLocationIdentityProjection
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper
import io.reactivex.rxjava3.core.Observable

/**
 * @author rahul.ray
 */

class TemplatesSync {
    // Constants for duplicate string literals
    private static final String REF_TYPE = 'refType'
    private static final String REF_ID = 'refId'
    private static final String COMPUTE_ZONE = 'ComputeZone'
    private static final String ID = 'id'
    private static final String IN = 'in'
    private static final String VHD = 'vhd'
    private static final String VHDX = 'vhdx'
    private static final String VIRTUAL_IMAGE = 'virtualImage'
    private static final String VIRTUAL_IMAGE_TYPE = 'virtualImage.imageType'
    private static final String CODE = 'code'
    private static final String EXTERNAL_ID = 'externalId'
    private static final String STANDARD = 'standard'
    private static final String DATASTORE_REF_TYPE = 'datastore.refType'
    private static final String DATASTORE_REF_ID = 'datastore.refId'
    private static final String BOOT_AND_SYSTEM = 'BootAndSystem'
    private static final String GENERATION = 'generation'
    private static final String ONE = '1'
    private static final String GENERATION1 = 'generation1'
    private static final String GENERATION2 = 'generation2'

    private final Cloud cloud
    private final ComputeServer node
    private final MorpheusContext context
    private final ScvmmApiService apiService
    private final CloudProvider cloudProvider
    private final LogInterface log = LogWrapper.instance

    TemplatesSync(Cloud cloud, ComputeServer node, MorpheusContext context, CloudProvider cloudProvider) {
        this.cloud = cloud
        this.node = node
        this.context = context
        this.apiService = new ScvmmApiService(context)
        this.@cloudProvider = cloudProvider

    }

    def execute() {
        log.debug "TemplatesSync"
        def scvmmOpts = apiService.getScvmmZoneAndHypervisorOpts(context, cloud, node)
        def listResults = apiService.listTemplates(scvmmOpts)
        if (listResults.success && listResults.templates) {
            processTemplateSync(listResults.templates)
        }
    }

    private void processTemplateSync(Collection templates) {
        def existingLocations = getExistingImageLocations()
        def cleanedLocations = removeDuplicateImageLocations(existingLocations)
        def domainRecords = buildDomainRecords(cleanedLocations)
        executeTemplateSync(domainRecords, templates)
    }

    private Collection getExistingImageLocations() {
        def query = new DataQuery().withFilter(REF_TYPE, COMPUTE_ZONE).withFilter(REF_ID, cloud.id)
                .withFilter(VIRTUAL_IMAGE_TYPE, IN, [VHD, VHDX]).withJoin(VIRTUAL_IMAGE)
        return context.services.virtualImage.location.listIdentityProjections(query)
    }

    private Collection removeDuplicateImageLocations(Collection existingLocations) {
        def groupedLocations = existingLocations.groupBy({ row -> row.externalId })
        def dupedLocations = groupedLocations.findAll { key, value -> value.size() > 1 }
        def dupeCleanup = identifyDuplicatesForCleanup(dupedLocations)
        return cleanupDuplicateLocations(dupeCleanup, existingLocations)
    }

    private List identifyDuplicatesForCleanup(Map dupedLocations) {
        def dupeCleanup = []
        if (dupedLocations?.size() > 0) {
            log.warn("removing duplicate image locations: {}", dupedLocations*.key)
        }
        dupedLocations?.each { key, value ->
            value.eachWithIndex { row, index ->
                if (index > 0) {
                    dupeCleanup << row
                }
            }
        }
        return dupeCleanup
    }

    private Collection cleanupDuplicateLocations(List dupeCleanup, Collection existingLocations) {
        dupeCleanup?.each { row ->
            def dupeResults = context.async.virtualImage.location.remove([row.id]).blockingGet()
            if (dupeResults == true) {
                existingLocations.remove(row)
            }
        }
        return existingLocations
    }

    private buildDomainRecords(Collection existingLocations) {
        def query = new DataQuery().withFilter(REF_TYPE, COMPUTE_ZONE).withFilter(REF_ID, cloud.id)
                .withFilter(VIRTUAL_IMAGE_TYPE, IN, [VHD, VHDX]).withJoin(VIRTUAL_IMAGE)
                .withFilter(ID, IN, existingLocations*.id)
        return context.async.virtualImage.location.listIdentityProjections(query)
    }

    private void executeTemplateSync(def domainRecords, Collection templates) {
        SyncTask<VirtualImageLocationIdentityProjection, Map, VirtualImageLocation> syncTask =
                new SyncTask<>(domainRecords, templates as Collection<Map>)
        syncTask.addMatchFunction { VirtualImageLocationIdentityProjection domainObject, Map cloudItem ->
            domainObject.externalId == cloudItem.ID.toString()
        }.withLoadObjectDetailsFromFinder {
            List<SyncTask.UpdateItemDto<VirtualImageLocationIdentityProjection, Map>> updateItems ->
            context.async.virtualImage.location.listById(updateItems.collect { it.existingItem.id } as List<Long>)
        }.onAdd { itemsToAdd ->
            log.debug("TemplatesSync, onAdd: ${itemsToAdd}")
            addMissingVirtualImageLocations(itemsToAdd)
        }.onUpdate { List<SyncTask.UpdateItem<VirtualImageLocation, Map>> updateItems ->
            log.debug("TemplatesSync, onUpdate: ${updateItems}")
            updateMatchedVirtualImageLocations(updateItems)
        }.onDelete { List<VirtualImageLocationIdentityProjection> removeItems ->
            log.debug("TemplatesSync, onDelete: ${removeItems}")
            removeMissingVirtualImages(removeItems)
        }.start()
    }

    protected removeMissingVirtualImages(List<VirtualImageLocationIdentityProjection> removeList) {
        try {
            log.debug("removeMissingVirtualImageLocations: ${cloud} ${removeList.size()}")
            context.async.virtualImage.location.remove(removeList).blockingGet()
        } catch (e) {
            log.error("error deleting synced virtual image: ${e}", e)
        }
    }

    private updateMatchedVirtualImageLocations(List<SyncTask.UpdateItem<VirtualImageLocation, Map>> updateItems) {
        log.debug "updateMatchedVirtualImages: ${updateItems.size()}"
        try {
            def existingData = prepareExistingVirtualImageData(updateItems)
            def updateLists = processVirtualImageLocationUpdates(updateItems, existingData)
            saveVirtualImageLocationResults(updateLists)
        } catch (e) {
            log.error("Error in updateMatchedVirtualImageLocations: ${e}", e)
        }
    }

    private Map prepareExistingVirtualImageData(List<SyncTask.UpdateItem<VirtualImageLocation, Map>> updateItems) {
        def locationIds = updateItems.findAll { it.existingItem.id }?.collect { it.existingItem.id }
        def existingLocations = getExistingVirtualImageLocations(locationIds)
        def imageIds = updateItems?.findAll {
            it.existingItem.virtualImage?.id
        }?.collect { it.existingItem.virtualImage?.id }
        def externalIds = updateItems?.findAll { it.existingItem.externalId }?.collect { it.existingItem.externalId }
        def existingImages = getExistingVirtualImages(imageIds, externalIds)

        return [
                existingLocations: existingLocations,
                existingImages: existingImages,
        ]
    }

    private List getExistingVirtualImageLocations(def locationIds) {
        return locationIds ? context.services.virtualImage.location.list(new DataQuery()
                .withFilter(ID, IN, locationIds)
                .withFilter(REF_TYPE, COMPUTE_ZONE)
                .withFilter(REF_ID, cloud.id)) : []
    }

    private List<VirtualImage> getExistingVirtualImages(def imageIds, def externalIds) {
        List<VirtualImage> existingItems = []
        if (imageIds && externalIds) {
            def tmpImgProjs = context.async.virtualImage.listIdentityProjections(cloud.id).filter { img ->
                img.id in imageIds || (!img.systemImage && img.externalId != null && img.externalId in externalIds)
            }.toList().blockingGet()
            if (tmpImgProjs) {
                existingItems = context.async.virtualImage.listById(tmpImgProjs*.id).filter { img ->
                    img.id in imageIds || img.imageLocations.size() == 0
                }.toList().blockingGet()
            }
        } else if (imageIds) {
            existingItems = context.async.virtualImage.listById(imageIds).toList().blockingGet()
        }
        return existingItems
    }

    private Map processVirtualImageLocationUpdates(List<SyncTask.UpdateItem<VirtualImageLocation, Map>> updateItems,
                                                    Map existingData) {
        List<VirtualImageLocation> locationsToCreate = []
        List<VirtualImageLocation> locationsToUpdate = []
        List<VirtualImage> imagesToUpdate = []

        updateItems?.each { update ->
            def matchedTemplate = update.masterItem
            def imageLocation = existingData.existingLocations?.find { it.id == update.existingItem.id }

            if (imageLocation) {
                processExistingVirtualImageLocation(imageLocation, matchedTemplate, existingData.existingImages,
                        locationsToUpdate, imagesToUpdate)
            } else {
                processNewVirtualImageLocation(matchedTemplate, existingData.existingImages,
                        locationsToCreate, imagesToUpdate)
            }
        }

        return [
                locationsToCreate: locationsToCreate,
                locationsToUpdate: locationsToUpdate,
                imagesToUpdate: imagesToUpdate,
        ]
    }

    private void processExistingVirtualImageLocation(def imageLocation, def matchedTemplate,
                                                     List<VirtualImage> existingImages,
                                                     List<VirtualImageLocation> locationsToUpdate,
                                                     List<VirtualImage> imagesToUpdate) {
        def updateResults = updateVirtualImageLocationProperties(imageLocation, matchedTemplate, existingImages)

        if (updateResults.saveLocation) {
            locationsToUpdate << imageLocation
        }
        if (updateResults.saveImage && updateResults.virtualImage) {
            imagesToUpdate << updateResults.virtualImage
        }
    }

    private Map updateVirtualImageLocationProperties(def imageLocation, def matchedTemplate,
                                                     List<VirtualImage> existingImages) {
        def save = false
        def saveImage = false
        def virtualImage = existingImages.find { it.id == imageLocation.virtualImage.id }

        if (virtualImage) {
            def nameUpdateResult = updateVirtualImageNames(imageLocation, virtualImage, matchedTemplate)
            save = nameUpdateResult.saveLocation || save
            saveImage = nameUpdateResult.saveImage || saveImage

            def publicityUpdateResult = updateVirtualImagePublicity(imageLocation, virtualImage)
            save = publicityUpdateResult.saveLocation || save
            saveImage = publicityUpdateResult.saveImage || saveImage
        }

        save = updateVirtualImageLocationCode(imageLocation, matchedTemplate) || save
        save = updateVirtualImageLocationExternalId(imageLocation, matchedTemplate) || save
        save = updateVirtualImageLocationVolumes(imageLocation, matchedTemplate) || save

        return [
                saveLocation: save,
                saveImage: saveImage,
                virtualImage: virtualImage,
        ]
    }

    private Map updateVirtualImageNames(def imageLocation, def virtualImage, def matchedTemplate) {
        def save = false
        def saveImage = false

        if (imageLocation.imageName != matchedTemplate.Name) {
            imageLocation.imageName = matchedTemplate.Name
            if (virtualImage.refId == imageLocation.refId.toString()) {
                virtualImage.name = matchedTemplate.Name
                saveImage = true
            }
            save = true
        }

        return [saveLocation: save, saveImage: saveImage]
    }

    private Map updateVirtualImagePublicity(def imageLocation, def virtualImage) {
        def save = false
        def saveImage = false

        if (virtualImage?.isPublic != false) {
            virtualImage.isPublic = false
            imageLocation.isPublic = false
            save = true
            saveImage = true
        }

        return [saveLocation: save, saveImage: saveImage]
    }

    private boolean updateVirtualImageLocationCode(def imageLocation, def matchedTemplate) {
        if (imageLocation.code == null) {
            imageLocation.code = "scvmm.image.${cloud.id}.${matchedTemplate.ID}"
            return true
        }
        return false
    }

    private boolean updateVirtualImageLocationExternalId(def imageLocation, def matchedTemplate) {
        if (imageLocation.externalId != matchedTemplate.ID) {
            imageLocation.externalId = matchedTemplate.ID
            return true
        }
        return false
    }

    private boolean updateVirtualImageLocationVolumes(def imageLocation, def matchedTemplate) {
        if (matchedTemplate.Disks) {
            def changed = syncVolumes(imageLocation, matchedTemplate.Disks)
            return changed == true
        }
        return false
    }

    private void processNewVirtualImageLocation(def matchedTemplate, List<VirtualImage> existingImages,
                                                List<VirtualImageLocation> locationsToCreate,
                                                List<VirtualImage> imagesToUpdate) {
        def image = existingImages?.find { it.externalId == matchedTemplate.ID || it.name == matchedTemplate.Name }
        if (image) {
            def locationConfig = createVirtualImageLocationConfig(matchedTemplate, image)
            def addLocation = new VirtualImageLocation(locationConfig)
            log.debug("save VirtualImageLocation: ${addLocation}")
            locationsToCreate << addLocation

            prepareVirtualImageForUpdate(image)
            imagesToUpdate << image
        }
    }

    private Map createVirtualImageLocationConfig(def matchedTemplate, def image) {
        return [
                code        : "scvmm.image.${cloud.id}.${matchedTemplate.ID}",
                externalId  : matchedTemplate.ID,
                virtualImage: image,
                refType     : COMPUTE_ZONE,
                refId       : cloud.id,
                imageName   : matchedTemplate.Name,
                imageRegion : cloud.regionCode,
                isPublic    : false,
        ]
    }

    private void prepareVirtualImageForUpdate(def image) {
        if (!image.owner && !image.systemImage) {
            image.owner = cloud.owner
        }
        image.deleted = false
        image.isPublic = false
    }

    private void saveVirtualImageLocationResults(Map updateLists) {
        if (updateLists.locationsToCreate.size() > 0) {
            context.async.virtualImage.location.create(updateLists.locationsToCreate, cloud).blockingGet()
        }
        if (updateLists.locationsToUpdate.size() > 0) {
            context.async.virtualImage.location.save(updateLists.locationsToUpdate, cloud).blockingGet()
        }
        if (updateLists.imagesToUpdate.size() > 0) {
            context.async.virtualImage.save(updateLists.imagesToUpdate, cloud).blockingGet()
        }
    }

    private addMissingVirtualImageLocations(Collection<Map> addList) {
        log.debug "addMissingVirtualImageLocations: ${addList?.size()}"
        try {
            def names = addList*.Name?.unique()
            def uniqueIds = [] as Set
            def existingItems = context.async.virtualImage.listIdentityProjections(new DataQuery().withFilters(
                    new DataFilter('imageType', IN, [VHD, VHDX, 'vmdk']),
                    new DataFilter('name', IN, names),
                    new DataOrFilter(
                            new DataFilter('systemImage', true),
                            new DataOrFilter(
                                    new DataFilter('owner', null),
                                    new DataFilter('owner.id', cloud.owner?.id)
                            )
                    )
            )).filter { proj ->
                def uniqueKey = "${proj.imageType}:${proj.name}".toString()
                if (!uniqueIds.contains(uniqueKey)) {
                    uniqueIds << uniqueKey
                    return true
                }
                return false
            }
            SyncTask<VirtualImageIdentityProjection, Map, VirtualImage> syncTask =
                    new SyncTask<>(existingItems, addList)
            syncTask.addMatchFunction { VirtualImageIdentityProjection domainObject, Map cloudItem ->
                domainObject.name == cloudItem.Name
            }.onAdd { itemsToAdd ->
                addMissingVirtualImages(itemsToAdd)
            }.onUpdate { List<SyncTask.UpdateItem<VirtualImage, Map>> updateItems ->
                updateMatchedVirtualImages(updateItems)
            }.withLoadObjectDetailsFromFinder { updateItems ->
                return context.async.virtualImage.listById(updateItems?.collect { it.existingItem.id } as List<Long>)
            }.start()
        } catch (e) {
            log.error("Error in addMissingVirtualImageLocations: ${e}", e)
        }
    }

    private updateMatchedVirtualImages(List<SyncTask.UpdateItem<VirtualImage, Map>> updateItems) {
        log.debug "updateMatchedVirtualImages: ${updateItems.size()}"
        try {
            def existingData = prepareVirtualImageData(updateItems)
            def updateLists = processVirtualImageUpdates(updateItems, existingData)
            saveVirtualImageResults(updateLists)
        } catch (e) {
            log.error("Error in updateMatchedVirtualImages: ${e}", e)
        }
    }

    private Map prepareVirtualImageData(List<SyncTask.UpdateItem<VirtualImage, Map>> updateItems) {
        def locationIds = extractLocationIds(updateItems)
        def existingLocations = getExistingVirtualImageLocations(locationIds)
        def imageIds = updateItems?.findAll { it.existingItem.id }?.collect { it.existingItem.id }
        def externalIds = updateItems?.findAll { it.existingItem.externalId }?.collect { it.existingItem.externalId }
        def existingImages = getExistingVirtualImages(imageIds, externalIds)

        return [
                existingLocations: existingLocations,
                existingImages: existingImages,
        ]
    }

    private List extractLocationIds(List<SyncTask.UpdateItem<VirtualImage, Map>> updateItems) {
        def locationIds = []
        updateItems?.each {
            def ids = it.existingItem.locations
            locationIds << ids
        }
        return locationIds
    }

    private Map processVirtualImageUpdates(List<SyncTask.UpdateItem<VirtualImage, Map>> updateItems, Map existingData) {
        List<VirtualImageLocation> locationsToCreate = []
        List<VirtualImageLocation> locationsToUpdate = []
        List<VirtualImage> imagesToUpdate = []

        updateItems?.each { update ->
            def matchedTemplate = update.masterItem
            def imageLocation = existingData.existingLocations?.find { it.id == update.existingItem.id }

            if (imageLocation) {
                processExistingImageLocationForVirtualImage(imageLocation, matchedTemplate, existingData.existingImages,
                        locationsToUpdate, imagesToUpdate)
            } else {
                processNewImageLocationForVirtualImage(matchedTemplate, existingData.existingImages,
                        locationsToCreate, imagesToUpdate)
            }
        }

        return [
                locationsToCreate: locationsToCreate,
                locationsToUpdate: locationsToUpdate,
                imagesToUpdate: imagesToUpdate,
        ]
    }

    private void processExistingImageLocationForVirtualImage(def imageLocation, def matchedTemplate,
                                                             List<VirtualImage> existingImages,
                                                             List<VirtualImageLocation> locationsToUpdate,
                                                             List<VirtualImage> imagesToUpdate) {
        def updateResults = updateImageLocationPropertiesForVirtualImage(imageLocation, matchedTemplate, existingImages)

        if (updateResults.saveLocation) {
            locationsToUpdate << imageLocation
        }
        if (updateResults.saveImage && updateResults.virtualImage) {
            imagesToUpdate << updateResults.virtualImage
        }
    }

    private Map updateImageLocationPropertiesForVirtualImage(def imageLocation, def matchedTemplate,
                                                             List<VirtualImage> existingImages) {
        def save = false
        def saveImage = false
        def virtualImage = existingImages.find { it.id == imageLocation.virtualImage.id }

        if (virtualImage) {
            def nameUpdateResult = updateImageNameForVirtualImage(imageLocation, virtualImage, matchedTemplate)
            save = nameUpdateResult.saveLocation || save
            saveImage = nameUpdateResult.saveImage || saveImage

            def publicityUpdateResult = updateImagePublicityForVirtualImage(imageLocation, virtualImage)
            save = publicityUpdateResult.saveLocation || save
            saveImage = publicityUpdateResult.saveImage || saveImage
        }

        save = updateImageLocationCodeForVirtualImage(imageLocation, matchedTemplate) || save
        save = updateImageLocationExternalIdForVirtualImage(imageLocation, matchedTemplate) || save
        save = updateImageLocationVolumesForVirtualImage(imageLocation, matchedTemplate) || save

        return [
                saveLocation: save,
                saveImage: saveImage,
                virtualImage: virtualImage,
        ]
    }

    private Map updateImageNameForVirtualImage(def imageLocation, def virtualImage, def matchedTemplate) {
        def save = false
        def saveImage = false

        if (imageLocation.imageName != matchedTemplate.Name) {
            imageLocation.imageName = matchedTemplate.Name
            if (virtualImage.refId == imageLocation.refId.toString()) {
                virtualImage.name = matchedTemplate.Name
                saveImage = true
            }
            save = true
        }

        return [saveLocation: save, saveImage: saveImage]
    }

    private Map updateImagePublicityForVirtualImage(def imageLocation, def virtualImage) {
        def save = false
        def saveImage = false

        if (virtualImage?.isPublic != false) {
            virtualImage.isPublic = false
            imageLocation.isPublic = false
            save = true
            saveImage = true
        }

        return [saveLocation: save, saveImage: saveImage]
    }

    private boolean updateImageLocationCodeForVirtualImage(def imageLocation, def matchedTemplate) {
        if (imageLocation.code == null) {
            imageLocation.code = "scvmm.image.${cloud.id}.${matchedTemplate.ID}"
            return true
        }
        return false
    }

    private boolean updateImageLocationExternalIdForVirtualImage(def imageLocation, def matchedTemplate) {
        if (imageLocation.externalId != matchedTemplate.ID) {
            imageLocation.externalId = matchedTemplate.ID
            return true
        }
        return false
    }

    private boolean updateImageLocationVolumesForVirtualImage(def imageLocation, def matchedTemplate) {
        if (matchedTemplate.Disks) {
            def changed = syncVolumes(imageLocation, matchedTemplate.Disks)
            return changed == true
        }
        return false
    }

    private void processNewImageLocationForVirtualImage(def matchedTemplate, List<VirtualImage> existingImages,
                                                        List<VirtualImageLocation> locationsToCreate,
                                                        List<VirtualImage> imagesToUpdate) {
        def image = existingImages?.find { it.externalId == matchedTemplate.ID || it.name == matchedTemplate.Name }
        if (image) {
            def locationConfig = createLocationConfigForVirtualImage(matchedTemplate, image)
            def addLocation = new VirtualImageLocation(locationConfig)
            log.debug("save VirtualImageLocation: ${addLocation}")
            locationsToCreate << addLocation

            prepareImageForVirtualImageUpdate(image)
            imagesToUpdate << image
        }
    }

    private Map createLocationConfigForVirtualImage(def matchedTemplate, def image) {
        return [
                code        : "scvmm.image.${cloud.id}.${matchedTemplate.ID}",
                externalId  : matchedTemplate.ID,
                virtualImage: image,
                refType     : COMPUTE_ZONE,
                refId       : cloud.id,
                imageName   : matchedTemplate.Name,
                imageRegion : cloud.regionCode,
                isPublic    : false,
        ]
    }

    private void prepareImageForVirtualImageUpdate(def image) {
        if (!image.owner && !image.systemImage) {
            image.owner = cloud.owner
        }
        image.deleted = false
        image.isPublic = false
    }

    private void saveVirtualImageResults(Map updateLists) {
        if (updateLists.locationsToCreate.size() > 0) {
            context.async.virtualImage.location.create(updateLists.locationsToCreate, cloud).blockingGet()
        }
        if (updateLists.locationsToUpdate.size() > 0) {
            context.async.virtualImage.location.save(updateLists.locationsToUpdate, cloud).blockingGet()
        }
        if (updateLists.imagesToUpdate.size() > 0) {
            context.async.virtualImage.save(updateLists.imagesToUpdate, cloud).blockingGet()
        }
    }

    private addMissingVirtualImages(Collection<Map> addList) {
        log.debug "addMissingVirtualImages ${addList?.size()}"
        try {
            addList?.each { templateItem ->
                createVirtualImageFromTemplate(templateItem)
            }
        } catch (e) {
            log.error("Error in addMissingVirtualImages: ${e}", e)
        }
    }

    private void createVirtualImageFromTemplate(Map templateItem) {
        def imageConfig = buildImageConfig(templateItem)
        def osType = getOsTypeForTemplate(templateItem)
        updateImageConfigWithOsType(imageConfig, osType)

        def virtualImage = new VirtualImage(imageConfig)
        setImageGeneration(virtualImage, templateItem)

        def locationConfig = buildLocationConfig(templateItem)
        def addLocation = new VirtualImageLocation(locationConfig)
        virtualImage.imageLocations = [addLocation]

        createAndSaveVirtualImage(virtualImage, templateItem)
    }

    private Map buildImageConfig(Map templateItem) {
        def imageConfig = [
                name       : templateItem.Name,
                code       : "scvmm.image.${cloud.id}.${templateItem.ID}",
                refId      : "${cloud.id}",
                owner      : cloud.owner,
                status     : 'Active',
                account    : cloud.account,
                refType    : COMPUTE_ZONE,
                isPublic   : false,
                category   : "scvmm.image.${cloud.id}",
                imageType  : templateItem.VHDFormatType?.toLowerCase() ?: VHDX,
                visibility : 'private',
                externalId : templateItem.ID,
                imageRegion: cloud.regionCode,
        ]

        if (templateItem.Memory) {
            imageConfig.minRam = templateItem.Memory.toLong() * ComputeUtility.ONE_MEGABYTE
        }
        if (templateItem.Location) {
            imageConfig.remotePath = templateItem.Location
        }

        return imageConfig
    }

    private OsType getOsTypeForTemplate(Map templateItem) {
        def osTypeCode = apiService.getMapScvmmOsType(templateItem.OperatingSystem, true, "Other Linux (64 bit)")
        log.info "cacheTemplates osTypeCode: ${osTypeCode}"
        def osType = context.services.osType.find(new DataQuery().withFilter(CODE, osTypeCode ?: 'other'))
        log.info "osType: ${osType}"
        return osType
    }

    private void updateImageConfigWithOsType(Map imageConfig, OsType osType) {
        imageConfig.osType = osType
        imageConfig.platform = osType?.platform
        if (imageConfig.platform == 'windows') {
            imageConfig.isCloudInit = false
        }
    }

    private void setImageGeneration(VirtualImage virtualImage, Map templateItem) {
        if (templateItem.Generation) {
            virtualImage.setConfigProperty(GENERATION,
                templateItem.Generation?.toString() == ONE ? GENERATION1 : GENERATION2)
        } else if (templateItem.VHDFormatType) {
            virtualImage.setConfigProperty(GENERATION,
                templateItem.VHDFormatType.toLowerCase() == VHD ? GENERATION1 : GENERATION2)
        }
    }

    private Map buildLocationConfig(Map templateItem) {
        return [
                code       : "scvmm.image.${cloud.id}.${templateItem.ID}",
                refId      : cloud.id,
                refType    : COMPUTE_ZONE,
                isPublic   : false,
                imageName  : templateItem.Name,
                externalId : templateItem.ID,
                imageRegion: cloud.regionCode,
        ]
    }

    private void createAndSaveVirtualImage(VirtualImage virtualImage, Map templateItem) {
        context.async.virtualImage.create([virtualImage], cloud).blockingGet()
        def savedLocation = findSavedLocation(virtualImage.imageLocations[0])
        def hasVolumeChanges = syncVolumes(savedLocation, templateItem.Disks)
        if (hasVolumeChanges) {
            context.async.virtualImage.save([virtualImage], cloud).blockingGet()
        }
    }

    private VirtualImageLocation findSavedLocation(VirtualImageLocation location) {
        return context.async.virtualImage.location.find(new DataQuery()
                .withFilter(CODE, location.code)
                .withFilter(REF_TYPE, location.refType)
                .withFilter(REF_ID, location.refId)).blockingGet()
    }

    private syncVolumes(addLocation, externalVolumes) {
        log.debug "syncVolumes: ${addLocation}, " +
                  "${groovy.json.JsonOutput.prettyPrint(externalVolumes?.encodeAsJSON()?.toString())}"
        def changes = false // returns if there are changes to be saved
        try {
            def maxStorage = 0

            def existingVolumes = addLocation.volumes
            def masterItems = externalVolumes

            def existingItems = Observable.fromIterable(existingVolumes)
            def diskNumber = masterItems.size()

            SyncTask<StorageVolumeIdentityProjection, Map, StorageVolume> syncTask =
                    new SyncTask<>(existingItems, masterItems as Collection<Map>)

            syncTask.addMatchFunction { StorageVolumeIdentityProjection storageVolume, Map masterItem ->
                storageVolume.externalId == masterItem.ID
            }.withLoadObjectDetailsFromFinder {
                List<SyncTask.UpdateItemDto<StorageVolumeIdentityProjection, StorageVolume>> updateItems ->
                context.async.storageVolume.listById(updateItems.collect { it.existingItem.id } as List<Long>)
            }.onAdd { itemsToAdd ->
                addMissingStorageVolumes(itemsToAdd, addLocation, diskNumber, maxStorage)
            }.onUpdate { List<SyncTask.UpdateItem<StorageVolume, Map>> updateItems ->
                updateMatchedStorageVolumes(updateItems, addLocation, maxStorage, changes)
            }.onDelete { removeItems ->
                removeMissingStorageVolumes(removeItems, addLocation, changes)
            }.start()
        } catch (e) {
            log.error("syncVolumes error: ${e}", e)
        }
        return changes
    }

    private addMissingStorageVolumes(itemsToAdd, VirtualImageLocation addLocation, int diskNumber, maxStorage) {
        List<StorageVolume> volumes = []
        itemsToAdd?.each { diskData ->
            log.debug("adding new volume: ${diskData}")
            def datastore = diskData.datastore ?:
                    loadDatastoreForVolume(diskData.HostVolumeId, diskData.FileShareId,
                                         diskData.PartitionUniqueId) ?: null
            def volumeConfig = [
                    // Dont replace the Morpheus volume name with the one from SCVMM
                    // name      : diskData.Name,
                    size      : diskData.TotalSize?.toLong() ?: 0,
                    rootVolume: diskData.VolumeType == BOOT_AND_SYSTEM || !addLocation.volumes?.size(),
                    // Note there is no property diskData.deviceName??
                    deviceName: (diskData.deviceName ?: apiService.getDiskName(diskNumber)),
                    externalId: diskData.ID,
                    // To ensure unique take the internalId from the Location property on diskData
                    // as this is the fully qualified path
                    internalId: diskData.Location,
                    // StorageVolumeType code eg 'scvmm-dynamicallyexpanding-vhd'
                    storageType: getStorageVolumeType(
                            "scvmm-${diskData?.VHDType}-${diskData?.VHDFormat}".toLowerCase()).getId(),
                    displayOrder: volumes?.size(),
            ]
            if (datastore) {
                volumeConfig.datastoreId = "${datastore.id}"
            }
            def storageVolume = buildStorageVolume(cloud.account, addLocation, volumeConfig)
            // def createdStorageVolume = context.async.storageVolume.create([storageVolume], addLocation).blockingGet()
            maxStorage += storageVolume.maxStorage ?: 0L
            diskNumber++
            volumes << storageVolume
            log.debug("added volume: ${storageVolume?.dump()}")
        }
        context.async.storageVolume.create(volumes, addLocation).blockingGet()
    }

    private updateMatchedStorageVolumes(updateItems, addLocation, maxStorage, changes) {
        updateItems?.each { updateMap ->
            log.debug("updating volume: ${updateMap.masterItem}")
            def updateResult = processStorageVolumeUpdate(updateMap, addLocation)

            if (updateResult.needsSave) {
                context.async.storageVolume.save(updateResult.volume).blockingGet()
                changes = true
            }
            maxStorage += updateResult.masterDiskSize
        }
    }

    private Map processStorageVolumeUpdate(def updateMap, def addLocation) {
        StorageVolume volume = updateMap.existingItem
        def masterItem = updateMap.masterItem
        def masterDiskSize = masterItem?.TotalSize?.toLong() ?: 0
        def save = false

        save = updateVolumeSize(volume, masterDiskSize) || save
        save = updateVolumeInternalId(volume, masterItem) || save
        save = updateVolumeRootStatus(volume, masterItem, addLocation) || save
        save = updateVolumeStorageType(volume, masterItem) || save

        return [
                volume: volume,
                needsSave: save,
                masterDiskSize: masterDiskSize,
        ]
    }

    private boolean updateVolumeSize(StorageVolume volume, long masterDiskSize) {
        if (!masterDiskSize || volume.maxStorage == masterDiskSize) {
            return false
        }

        def sizeRange = [
                min: (volume.maxStorage - ComputeUtility.ONE_GIGABYTE),
                max: (volume.maxStorage + ComputeUtility.ONE_GIGABYTE),
        ]

        if (masterDiskSize <= sizeRange.min || masterDiskSize >= sizeRange.max) {
            volume.maxStorage = masterDiskSize
            return true
        }

        return false
    }

    private boolean updateVolumeInternalId(StorageVolume volume, def masterItem) {
        if (volume.internalId != masterItem.Location) {
            volume.internalId = masterItem.Location
            return true
        }
        return false
    }

    private boolean updateVolumeRootStatus(StorageVolume volume, def masterItem, def addLocation) {
        def isRootVolume = (masterItem?.VolumeType == BOOT_AND_SYSTEM) || (addLocation.volumes.size() == 1)
        if (volume.rootVolume != isRootVolume) {
            volume.rootVolume = isRootVolume
            return true
        }
        return false
    }

    private boolean updateVolumeStorageType(StorageVolume volume, def masterItem) {
        if (!masterItem?.VHDType || !masterItem?.VHDFormat) {
            return false
        }

        def storageVolumeType = getStorageVolumeType(
                "scvmm-${masterItem?.VHDType}-${masterItem?.VHDFormat}".toLowerCase())
        if (volume.type?.id != storageVolumeType.id) {
            log.debug("Updating StorageVolumeType to ${storageVolumeType}")
            volume.type = storageVolumeType
            return true
        }

        return false
    }

    // todo Move to ScvmmComputeService?
    // Get the SCVMM StorageVolumeType - set a meaningful default if vhdType is null
    private getStorageVolumeType(String storageVolumeTypeCode) {
        log.debug("getStorageVolumeTypeId - Looking up volumeTypeCode ${storageVolumeTypeCode}")
        def code = storageVolumeTypeCode ?: STANDARD
        return context.async.storageVolume.storageVolumeType.find(
                new DataQuery().withFilter(CODE, code)).blockingGet()
    }

    private removeMissingStorageVolumes(removeItems, addLocation, changes) {
        removeItems?.each { currentVolume ->
            log.debug "removing volume: ${currentVolume}"
            changes = true
            currentVolume.controller = null
            currentVolume.datastore = null

            context.async.storageVolume.save(currentVolume).blockingGet()
            context.async.storageVolume.remove([currentVolume], addLocation).blockingGet()
            context.async.storageVolume.remove(currentVolume).blockingGet()
        }
    }

    private buildStorageVolume(account, addLocation, volume) {
        def storageVolume = new StorageVolume()
        storageVolume.name = volume.name
        storageVolume.account = account

        def storageType = context.services.storageVolume.storageVolumeType.find(new DataQuery()
                .withFilter(CODE, STANDARD))
        storageVolume.type = storageType

        storageVolume.maxStorage = volume?.size?.toLong() ?: volume.maxStorage?.toLong()
        storageVolume.rootVolume = volume.rootVolume == true
        if (volume.datastoreId) {
            storageVolume.datastoreOption = volume.datastoreId
            storageVolume.refType = 'Datastore'
            storageVolume.refId = volume.datastoreId
        }

        if (volume.externalId) {
            storageVolume.externalId = volume.externalId
        }
        if (volume.internalId) {
            storageVolume.internalId = volume.internalId
        }

        if (addLocation instanceof VirtualImageLocation && addLocation.refType == COMPUTE_ZONE) {
            storageVolume.cloudId = addLocation.refId?.toLong()
        }

        storageVolume.deviceName = volume.deviceName

        storageVolume.removable = storageVolume.rootVolume != true
        storageVolume.displayOrder = volume.displayOrder ?: addLocation?.volumes?.size() ?: 0
        return storageVolume
    }

    private loadDatastoreForVolume(hostVolumeId = null, fileShareId = null, partitionUniqueId = null) {
        log.debug "loadDatastoreForVolume: ${hostVolumeId}, ${fileShareId}"
        if (hostVolumeId) {
            StorageVolume storageVolume = context.services.storageVolume.find(new DataQuery()
                    .withFilter('internalId', hostVolumeId)
                    .withFilter(DATASTORE_REF_TYPE, COMPUTE_ZONE).withFilter(DATASTORE_REF_ID, cloud.id))
            def ds = storageVolume?.datastore
            if (!ds && partitionUniqueId) {
                storageVolume = context.services.storageVolume.find(new DataQuery()
                        .withFilter(EXTERNAL_ID, partitionUniqueId)
                        .withFilter(DATASTORE_REF_TYPE, COMPUTE_ZONE).withFilter(DATASTORE_REF_ID, cloud.id))
                ds = storageVolume?.datastore
            }
            return ds
        } else if (fileShareId) {
            Datastore datastore = context.services.cloud.datastore.find(new DataQuery()
                    .withFilter(EXTERNAL_ID, fileShareId)
                    .withFilter(REF_TYPE, COMPUTE_ZONE)
                    .withFilter(REF_ID, cloud.id))
            return datastore
        }
        null
    }
}