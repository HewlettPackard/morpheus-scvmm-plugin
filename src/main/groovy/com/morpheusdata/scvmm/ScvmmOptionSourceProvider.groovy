package com.morpheusdata.scvmm

import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataAndFilter
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Cloud
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.LogWrapper

@SuppressWarnings('CompileStatic')
class ScvmmOptionSourceProvider extends AbstractOptionSourceProvider {
    // Added constants to remove duplicate string literals
    private static final String CRED_LOCAL = 'local'
    private static final String VALUE_ON = 'on'
    private static final String FILTER_CATEGORY = 'category'
    private static final String REF_TYPE_COMPUTE_ZONE = 'ComputeZone'
    private static final String FIELD_OWNER_ID = 'owner.id'
    private static final String STRING_ENABLED = 'enabled'
    private static final String KEY_NAME = 'name'

    ScvmmPlugin plugin
    MorpheusContext morpheusContext
    ScvmmApiService apiService
    private final LogInterface log = LogWrapper.instance

    ScvmmOptionSourceProvider(ScvmmPlugin plugin, MorpheusContext context) {
        this.plugin = plugin
        this.morpheusContext = context
        this.apiService = new ScvmmApiService(context)
    }

    @Override
    MorpheusContext getMorpheus() {
        return this.morpheusContext
    }

    @Override
    Plugin getPlugin() {
        return this.plugin
    }

    @SuppressWarnings('GetterMethodCouldBeProperty')
    @Override
    String getCode() {
        return 'scvmm-option-source'
    }

    @SuppressWarnings('GetterMethodCouldBeProperty')
    @Override
    String getName() {
        return 'SCVMM Option Source'
    }

    @Override
    List<String> getMethodNames() {
        return new ArrayList<String>([
                'scvmmCloud', 'scvmmHostGroup', 'scvmmCluster', 'scvmmLibraryShares', 'scvmmSharedControllers',
                'scvmmCapabilityProfile', 'scvmmHost', 'scvmmVirtualImages',
        ])
    }

    Map getApiConfig(Cloud cloud) {
        def rtn = apiService.getScvmmZoneOpts(morpheusContext, cloud) + apiService.getScvmmInitializationOpts(cloud)
        return rtn
    }

    @SuppressWarnings('MethodParameterTypeRequired')
    protected Cloud loadCloudFromZoneId(params, config, password) {
        Cloud cloud = morpheusContext.services.cloud.get(params.zoneId.toLong())
        if (params.credential?.type != CRED_LOCAL) {
            if (params.credential) {
                def credentials =
                        morpheusContext.services.accountCredential.loadCredentialConfig(params.credential, config)
                cloud.accountCredentialLoaded = true
                cloud.accountCredentialData = credentials?.data
            } else {
                def credentials = morpheusContext.services.accountCredential.loadCredentials(cloud)
                cloud.accountCredentialData = credentials?.data
                cloud.accountCredentialLoaded = true
            }
        } else if (password != '*' * 12) {
            config.password = password
        }
        return cloud
    }

    @SuppressWarnings('MethodParameterTypeRequired')
    protected Cloud createNewCloud(params, config, password) {
        Cloud cloud = new Cloud()
        if (params.credential && params.credential?.type != CRED_LOCAL) {
            def credentials = morpheusContext.services.accountCredential.loadCredentialConfig(params.credential, config)
            cloud.accountCredentialLoaded = true
            cloud.accountCredentialData = credentials?.data
            log.debug("cloud.accountCredentialData: ${cloud.accountCredentialData}")
        } else {
            config.password = password
        }
        return cloud
    }

    @SuppressWarnings(['MethodParameterTypeRequired', 'ParameterReassignment'])
    protected Map extractConfigAndPassword(params) {
        params = params instanceof Object[] ? params[(0)] : params
        def config = [
                host     : params.config?.host ?: params["config[host]"],
                username : params.config?.username ?: params["config[username]"],
                hostGroup: params.config?.hostGroup ?: params["config[hostGroup]"],
        ]
        def password = params.config?.password ?: params["config[password]"]
        return [config: config, password: password, params: params]
    }

    @SuppressWarnings(['MethodParameterTypeRequired', 'ParameterReassignment', 'UnnecessarySetter'])
    Cloud setupCloudConfig(params) {
        def extracted = extractConfigAndPassword(params)
        def config = extracted.config
        def password = extracted.password
        params = extracted.params

        Cloud cloud = params.zoneId ?
                loadCloudFromZoneId(params, config, password) :
                createNewCloud(params, config, password)

        cloud.setConfigMap(config)
        cloud.regionCode = params.config?.cloud ?: params["config[cloud]"]
        cloud.cloudType = morpheusContext.services.cloud.type.find(new DataQuery().withFilter('code', 'scvmm'))

        def apiProxyId = params.config?.apiProxy ? params.config.apiProxy.toLong() : null
        cloud.apiProxy = apiProxyId ? morpheusContext.services.network.networkProxy.get(apiProxyId) : null

        return cloud
    }

    @SuppressWarnings('MethodParameterTypeRequired')
    List<Map> scvmmCloud(params) {
        def cloud = setupCloudConfig(params)
        def apiConfig = getApiConfig(cloud)
        def results = []
        if (apiConfig.sshUsername && apiConfig.sshPassword) {
            results = apiService.listClouds(apiConfig)
        }
        log.debug("listClouds: ${results}")
        def optionList = []
        if (results.clouds?.size() > 0) {
            optionList << [name: "Select a Cloud", value: ""]
            optionList += results.clouds?.collect { it -> [name: it.Name, value: it.ID] }
        } else {
            optionList = [[name: "No Clouds Found: verify credentials above", value: ""]]
        }
        return optionList
    }

    @SuppressWarnings('MethodParameterTypeRequired')
    List<Map> scvmmHostGroup(params) {
        log.debug("scvmmHostGroup: ${params}")
        def cloud = setupCloudConfig(params)
        def apiConfig = getApiConfig(cloud)
        def results = []
        if (apiConfig.sshUsername && apiConfig.sshPassword) {
            results = apiService.listHostGroups(apiConfig)
        }
        log.debug("listHostGroups: ${results}")
        def optionList = []
        if (results.hostGroups?.size() > 0) {
            optionList << [name: "Select", value: ""]
            optionList += results.hostGroups?.collect { it -> [name: it.path, value: it.path] }
        } else {
            optionList = [[name: "No Host Groups found", value: ""]]
        }
        return optionList
    }

    @SuppressWarnings('MethodParameterTypeRequired')
    List<Map> scvmmCluster(params) {
        log.debug("scvmmCluster: ${params}")
        def cloud = setupCloudConfig(params)
        def apiConfig = getApiConfig(cloud)
        def results = []
        if (apiConfig.sshUsername && apiConfig.sshPassword) {
            results = apiService.listClusters(apiConfig)
        }
        log.debug("listClusters: ${results}")
        def optionList = []
        if (results.clusters?.size() > 0) {
            optionList << [name: "All", value: ""]
            optionList += results.clusters?.collect { it -> [name: it.name, value: it.id] }
        } else {
            optionList = [[name: "No Clusters found: check your config", value: ""]]
        }
        return optionList
    }

    @SuppressWarnings(['MethodParameterTypeRequired', 'UnnecessaryGetter'])
    List<Map> scvmmLibraryShares(params) {
        log.debug("scvmmLibraryShares: ${params}")
        def cloud = setupCloudConfig(params)
        def apiConfig = getApiConfig(cloud)
        def results = []
        if (apiConfig.sshUsername && apiConfig.sshPassword) {
            results = apiService.listLibraryShares(apiConfig)
        }
        log.debug("listLibraryShares: ${results}")

        def shares = results.libraryShares
        if (shares && !shares.isEmpty()) {
            return shares.collect { share -> [name: share.Path, value: share.Path] }
        }
        return [[name: "No Library Shares found", value: ""]]
    }

    @SuppressWarnings(['MethodParameterTypeRequired', 'ParameterReassignment'])
    List<Map> scvmmSharedControllers(params) {
        params = params instanceof Object[] ? params[(0)] : params
        log.debug("scvmmSharedControllers: ${params}")

        def orFilters = []
        if (params.zoneId) {
            orFilters << new DataFilter('zone.id', params.zoneId)
        }
        if (params.config?.host) {
            orFilters << new DataFilter('sshHost', params.config.host)
        }

        def sharedControllers = morpheusContext.services.computeServer.find(new DataQuery().withFilters(
                new DataFilter(STRING_ENABLED, true),
                new DataFilter('computeServerType.code', 'scvmmController'),
                new DataOrFilter(orFilters)
        ))
        return sharedControllers?.collect { controller -> [name: controller.name, value: controller.id] }
    }

    @SuppressWarnings(['MethodParameterTypeRequired', 'ParameterReassignment'])
    List<Map> scvmmCapabilityProfile(params) {
        params = params instanceof Object[] ? params[(0)] : params
        def tmpZone = params.zoneId ? morpheusContext.services.cloud.get(params.zoneId?.toLong()) : null

        def capabilityProfiles = tmpZone?.getConfigProperty('capabilityProfiles')

        def profiles = []
        if (capabilityProfiles) {
            capabilityProfiles.each { it ->
                profiles << [name: it, value: it]
            }
        } else {
            profiles << [name: 'Not required', value: -1]
        }
        return profiles
    }

    @SuppressWarnings(['MethodParameterTypeRequired', 'ParameterReassignment',
            'UnnecessaryGetter', 'ConfusingTernary'])
    List<Map> scvmmHost(params) {
        params = params instanceof Object[] ? params[(0)] : params
        log.debug("scvmmHost: ${params}")

        def tmpZone = params.zoneId ? morpheusContext.services.cloud.get(params.zoneId?.toLong()) : null

        if (tmpZone?.getConfigProperty('hideHostSelection') == VALUE_ON) {
            return [[name: 'Auto', value: '']]
        }

        def resourcePoolId = params.resourcePoolId?.toString()?.number ? params.resourcePoolId.toLong() :
                (params.config?.resourcePool != 'null' ?
                        (params.config?.resourcePool?.toString()?.number ?
                                params.config.resourcePool?.toLong() : null) : null)

        def query = new DataQuery()

        // Join account
        query.withJoin('account')
                .withFilter('account.id', tmpZone.account.id)
                .withFilter(STRING_ENABLED, true)
                .withFilter('powerState', VALUE_ON)
                .withFilter(FILTER_CATEGORY, "scvmm.host.${tmpZone.id}")

        if (resourcePoolId) {
            query.withJoin('resourcePool')
                    .withFilter('resourcePool.id', resourcePoolId)
        }

        def results = morpheusContext.services.computeServer
                .listIdentityProjections(
                        query.withSort(KEY_NAME, DataQuery.SortOrder.asc)
                )

        return results.collect { host -> [name: host.name, value: host.id] }
    }

    @SuppressWarnings(['MethodParameterTypeRequired', 'ParameterReassignment'])
    List<Map> scvmmVirtualImages(Object params) {
        // note
        params = params instanceof Object[] ? params[(0)] : params
        log.debug("scvmmVirtualImages: ${params}")

        // def account = params.currentUser?.account
        def tmpZone = params.zoneId ? morpheusContext.services.cloud.get(params.zoneId?.toLong()) : null
        def regionCode = tmpZone?.regionCode

        // Construct the query with all the necessary filters
        def query = new DataQuery()

        // Left joins
        query.withJoin("accounts")
                .withJoin("owner")
                .withJoin("locations")

        // OR filters for visibility/ownership
        def orFilters = []
        orFilters << new DataFilter("visibility", "public")
        orFilters << new DataFilter("accounts.id", params.accountId?.toLong())
        orFilters << new DataFilter(FIELD_OWNER_ID, params.accountId?.toLong())
        orFilters << new DataFilter(FIELD_OWNER_ID, null)
        query.withFilter(new DataOrFilter(orFilters))

        // Basic filters
        query.withFilter("deleted", false)
        query.withFilter("imageType", 'in', ['vhd', 'vhdx', 'vmdk'])

        // Zone and region filters
        def zoneRegionOrFilters = []

        if (tmpZone) {
            // Category filter
            zoneRegionOrFilters << new DataFilter(FILTER_CATEGORY, "scvmm.image.${tmpZone?.id}")

            // Reference filters
            def refAndFilters = []
            refAndFilters << new DataFilter("refType", REF_TYPE_COMPUTE_ZONE)
            refAndFilters << new DataFilter("refId", tmpZone.id.toString())
            zoneRegionOrFilters << new DataAndFilter(refAndFilters)

            // Location filters
            def locAndFilters = []
            locAndFilters << new DataFilter("locations.refType", REF_TYPE_COMPUTE_ZONE)
            locAndFilters << new DataFilter("locations.refId", tmpZone.id)
            zoneRegionOrFilters << new DataAndFilter(locAndFilters)
        }

        if (regionCode) {
            zoneRegionOrFilters << new DataFilter("imageRegion", regionCode)
            zoneRegionOrFilters << new DataFilter("locations.imageRegion", regionCode)
            zoneRegionOrFilters << new DataFilter("userUploaded", true)
        }

        query.withFilter(new DataOrFilter(zoneRegionOrFilters))

        try {
            def results = morpheusContext.services.virtualImage.
                    listIdentityProjections(
                            query.withSort(KEY_NAME, DataQuery.SortOrder.asc)
                    )
            return results.collect { vimage -> [name: vimage.name, value: vimage.id] }
        } catch (e) {
            log.error("scvmmVirtualImages error: ${e}", e)
            return []
        }
    }
}
