// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService
import com.morpheusdata.core.synchronous.MorpheusSynchronousAccountCredentialService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudTypeService
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.CloudType
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.core.data.DataAndFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * The Host dropdown on the provisioning form is narrowed to the selected resource pool. The wizard sends the
 * pool as {@code pool-<id>}; older callers send the bare morpheus id or the SCVMM cluster id. All of them have
 * to resolve to the same morpheus pool id, and anything that is not a single pool must not narrow the list.
 */
class ScvmmOptionSourceProviderResourcePoolSpec extends Specification {

    MorpheusContext context = Mock()
    MorpheusServices services = Mock()
    MorpheusSynchronousCloudService cloudService = Mock()
    MorpheusSynchronousCloudPoolService poolService = Mock()
    ScvmmPlugin plugin = Mock()
    MorpheusSynchronousComputeServerService computeServerService = Mock()
    MorpheusSynchronousVirtualImageService virtualImageService = Mock()

    @Subject
    ScvmmOptionSourceProvider provider = new ScvmmOptionSourceProvider(plugin, context)

    Cloud cloud = new Cloud(id: 1L)

    def setup() {
        context.services >> services
        services.cloud >> cloudService
        cloudService.pool >> poolService
        services.computeServer >> computeServerService
        services.virtualImage >> virtualImageService
    }

    @Unroll
    def "parseResourcePoolId('#raw') -> #expected without a lookup"() {
        when:
        def result = provider.parseResourcePoolId(cloud, raw)

        then:
        result == expected

        and: 'no externalId lookup is needed for these shapes'
        0 * poolService.find(_)

        where:
        raw               || expected
        'pool-42'         || 42L
        '42'              || 42L
        42L               || 42L
        ' pool-7 '        || 7L
        null              || null
        ''                || null
        '   '             || null
        'null'            || null
        'pool-'           || null
        'poolGroup-5'     || null
    }

    def "an SCVMM cluster id is resolved to the morpheus pool scoped to the cloud"() {
        when:
        def result = provider.parseResourcePoolId(cloud, 'b7f5dc5a-61f6-40c1-b782-927b0f516d54')

        then:
        1 * poolService.find({ DataQuery q ->
            filterValue(q, 'externalId') == 'b7f5dc5a-61f6-40c1-b782-927b0f516d54' &&
                    filterValue(q, 'refType') == 'ComputeZone' &&
                    filterValue(q, 'refId') == cloud.id
        }) >> new CloudPool(id: 99L)
        result == 99L
    }

    def "an unknown cluster id resolves to no pool so the host list is not narrowed"() {
        given:
        poolService.find(_) >> null

        expect:
        provider.parseResourcePoolId(cloud, 'does-not-exist') == null
    }

    // --- scvmmHost / scvmmVirtualImages / scvmmCapabilityProfile ---

    private static Cloud zone(Map config = [:]) {
        def c = new Cloud(id: 1L, regionCode: 'region-1', account: new Account(id: 5L))
        config.each { k, v -> c.setConfigProperty(k as String, v) }
        c
    }

    def "scvmmHost returns 'Auto' without querying hosts when host selection is hidden"() {
        given:
        cloudService.get(1L) >> zone(hideHostSelection: 'on')

        when:
        def result = provider.scvmmHost([[zoneId: '1']] as Object[])

        then:
        result == [[name: 'Auto', value: '']]
        0 * computeServerService.list(_)
    }

    def "scvmmHost lists enabled powered-on, non-maintenance hosts of the cloud sorted by name"() {
        given:
        cloudService.get(1L) >> zone()
        DataQuery captured

        when:
        def result = provider.scvmmHost([zoneId: 1L])

        then:
        1 * computeServerService.list(_) >> { DataQuery q -> captured = q; [
            new ComputeServer(id: 11L, name: 'host-a'),
            new ComputeServer(id: 12L, name: 'host-b', maintenanceMode: null),
            new ComputeServer(id: 13L, name: 'host-c', maintenanceMode: true)
        ] }
        result == [[name: 'host-a', value: 11L], [name: 'host-b', value: 12L]]
        filterValue(captured, 'account.id') == 5L
        filterValue(captured, 'enabled') == true
        filterValue(captured, 'powerState') == 'on'
        filterValue(captured, 'category') == 'scvmm.host.1'
        captured.sort == 'name'
        captured.order == DataQuery.SortOrder.asc
        !captured.joins.contains('resourcePool')
        // maintenance hosts are dropped in memory (null treated as eligible), not via a query filter
        !captured.filters*.name.any { it?.toString()?.toLowerCase()?.contains('maintenance') }
    }

    def "scvmmHost narrows the list to the selected resource pool (#source)"() {
        given:
        cloudService.get(1L) >> zone()
        DataQuery captured

        when:
        provider.scvmmHost([zoneId: 1L] + params)

        then:
        1 * computeServerService.list(_) >> { DataQuery q -> captured = q; [] }
        captured.joins.contains('resourcePool')
        filterValue(captured, 'resourcePool.id') == 42L

        where:
        source                  | params
        'resourcePoolId'        | [resourcePoolId: 'pool-42']
        'poolId'                | [poolId: '42']
        'config.resourcePoolId' | [config: [resourcePoolId: 42L]]
        'config.resourcePool'   | [config: [resourcePool: 'pool-42']]
    }

    def "scvmmHost returns an empty list when the cloud has no matching hosts"() {
        given:
        cloudService.get(1L) >> zone()
        computeServerService.list(_) >> []

        expect:
        provider.scvmmHost([zoneId: 1L]) == []
    }

    def "scvmmHost throws NullPointerException when no zoneId is supplied"() {
        // current behaviour: tmpZone is null and the account filter dereferences it unguarded
        when:
        provider.scvmmHost([:])

        then:
        thrown(NullPointerException)
        0 * computeServerService.list(_)
    }

    def "scvmmVirtualImages maps images to name/value and drops leftover temporary templates"() {
        given:
        cloudService.get(1L) >> zone()
        virtualImageService.listIdentityProjections(_) >> [
                [id: 1L, name: 'Alpha'],
                [id: 2L, name: 'Temporary Template 016c811a-3c00-4c56-86ea-5b39897079b9'],
                [id: 3L, name: 'Temporary Morpheus Template016c811a-3c00-4c56-86ea-5b39897079b9'],
                [id: 4L, name: 'Temporary Template (not a uuid)'],
                [id: 5L, name: null],
        ]

        expect:
        provider.scvmmVirtualImages([zoneId: '1', accountId: '5']) ==
                [[name: 'Alpha', value: 1L], [name: 'Temporary Template (not a uuid)', value: 4L], [name: null, value: 5L]]
    }

    def "scvmmVirtualImages queries vhd/vhdx/vmdk images visible to the account, scoped to the cloud and region, sorted by name"() {
        given:
        cloudService.get(1L) >> zone()
        DataQuery captured

        when:
        provider.scvmmVirtualImages([[zoneId: '1', accountId: '5']] as Object[])

        then:
        1 * virtualImageService.listIdentityProjections(_) >> { DataQuery q -> captured = q; [] }
        captured.sort == 'name'
        captured.order == DataQuery.SortOrder.asc
        captured.joins.containsAll(['accounts', 'owner', 'locations'])
        filterValue(captured, 'deleted') == false
        filterValue(captured, 'imageType') == ['vhd', 'vhdx', 'vmdk']

        and: 'visibility OR filter carries the account id'
        def visibility = captured.filters.findAll { it instanceof DataOrFilter }[0]
        visibility.value*.name == ['visibility', 'accounts.id', 'owner.id', 'owner.id']
        visibility.value*.value == ['public', 5L, 5L, null]

        and: 'zone/region OR filter: category, ref, location, region (3), system images'
        def scope = captured.filters.findAll { it instanceof DataOrFilter }[1]
        scope.value.size() == 7
        scope.value[0].value == 'scvmm.image.1'
        scope.value.findAll { it instanceof DataAndFilter }.size() == 3
        scope.value.findAll { !(it instanceof DataAndFilter) }*.name == ['category', 'imageRegion', 'locations.imageRegion', 'userUploaded']
    }

    def "scvmmVirtualImages without a zoneId still queries, limited to SCVMM system images"() {
        given:
        DataQuery captured

        when:
        def result = provider.scvmmVirtualImages([accountId: '5'])

        then:
        0 * cloudService.get(_)
        1 * virtualImageService.listIdentityProjections(_) >> { DataQuery q -> captured = q; [[id: 9L, name: 'sys']] }
        result == [[name: 'sys', value: 9L]]
        def scope = captured.filters.findAll { it instanceof DataOrFilter }[1]
        scope.value.size() == 1
        scope.value[0].value*.name == ['systemImage', 'zoneType']
    }

    def "scvmmVirtualImages returns an empty list when the image query fails"() {
        given:
        cloudService.get(1L) >> zone()
        virtualImageService.listIdentityProjections(_) >> { throw new RuntimeException('db down') }

        expect:
        provider.scvmmVirtualImages([zoneId: '1']) == []
    }

    def "scvmmCapabilityProfile offers 'Not required' when #reason"() {
        given:
        cloudService.get(1L) >> cloud

        expect:
        provider.scvmmCapabilityProfile(params) == [[name: 'Not required', value: -1]]

        where:
        reason                       | params        | cloud
        'the cloud has no profiles'  | [zoneId: '1'] | new Cloud(id: 1L)
        'the profile list is empty'  | [zoneId: '1'] | zone(capabilityProfiles: [])
        'no zoneId is supplied'      | [:]           | null
        'the cloud is not found'     | [zoneId: '1'] | null
    }

    // --- setupCloudConfig and the SCVMM-backed lookups ---

    MorpheusSynchronousAccountCredentialService credentialService = Mock()
    MorpheusSynchronousCloudTypeService cloudTypeService = Mock()
    ScvmmApiService apiService = Mock()
    static final Map CREDS = [sshUsername: 'admin', sshPassword: 'secret']

    private void wireCloudConfig() {
        services.accountCredential >> credentialService
        cloudService.type >> cloudTypeService
        cloudTypeService.find(_) >> new CloudType(code: 'scvmm')
        provider.@apiService = apiService
    }

    def "setupCloudConfig builds a new cloud from form fields with a local password"() {
        given:
        wireCloudConfig()

        when:
        Cloud cloud = provider.setupCloudConfig([[config: [host: 'vmm.local', username: 'admin', password: 'pw', hostGroup: 'All Hosts', cloud: 'c-1']]] as Object[])

        then:
        0 * credentialService._
        cloud.id == null
        cloud.configMap.host == 'vmm.local'
        cloud.configMap.username == 'admin'
        cloud.configMap.password == 'pw'
        cloud.configMap.hostGroup == 'All Hosts'
        cloud.regionCode == 'c-1'
        cloud.cloudType.code == 'scvmm'
        cloud.apiProxy == null
    }

    def "setupCloudConfig reads bracketed form keys when config is absent"() {
        given:
        wireCloudConfig()

        when:
        Cloud cloud = provider.setupCloudConfig(['config[host]': 'h', 'config[username]': 'u', 'config[password]': 'p',
                                                 'config[hostGroup]': 'g', 'config[cloud]': 'r'])

        then:
        cloud.configMap.subMap(['host', 'username', 'password', 'hostGroup']) == [host: 'h', username: 'u', password: 'p', hostGroup: 'g']
        cloud.regionCode == 'r'
    }

    def "setupCloudConfig loads a stored credential for a new cloud and does not keep the form password"() {
        given:
        wireCloudConfig()
        def credential = [type: 'username-password', id: 3]

        when:
        Cloud cloud = provider.setupCloudConfig([credential: credential, config: [host: 'h', password: 'ignored']])

        then:
        1 * credentialService.loadCredentialConfig(credential, _) >> [data: [username: 'cred-user']]
        cloud.accountCredentialLoaded
        cloud.accountCredentialData == [username: 'cred-user']
        !cloud.configMap.containsKey('password')
    }

    def "setupCloudConfig for an existing cloud with no credential in the form loads the cloud's credentials"() {
        given:
        wireCloudConfig()
        def existing = new Cloud(id: 1L)
        cloudService.get(1L) >> existing

        when:
        Cloud cloud = provider.setupCloudConfig([zoneId: '1', config: [host: 'h']])

        then:
        1 * credentialService.loadCredentials(existing) >> new AccountCredential(data: [username: 'stored'])
        cloud.is(existing)
        cloud.accountCredentialLoaded
        cloud.accountCredentialData == [username: 'stored']
    }

    def "setupCloudConfig for an existing cloud loads a newly chosen credential from the form"() {
        given:
        wireCloudConfig()
        cloudService.get(1L) >> new Cloud(id: 1L)
        def credential = [type: 'username-password', id: 4]

        when:
        Cloud cloud = provider.setupCloudConfig([zoneId: '1', credential: credential, config: [host: 'h']])

        then:
        1 * credentialService.loadCredentialConfig(credential, _) >> [data: [username: 'new']]
        0 * credentialService.loadCredentials(_)
        cloud.accountCredentialData == [username: 'new']
    }

    def "setupCloudConfig for an existing cloud with local credentials keeps the password only when it is not the mask"() {
        given:
        wireCloudConfig()
        cloudService.get(1L) >> new Cloud(id: 1L)

        when:
        Cloud cloud = provider.setupCloudConfig([zoneId: '1', credential: [type: 'local'], config: [host: 'h', password: password]])

        then:
        0 * credentialService._
        cloud.configMap.password == expected

        where:
        password       | expected
        'new-pw'       | 'new-pw'
        '*' * 12       | null
    }

    def "#method maps SCVMM results to options with a leading '#first' entry"() {
        given:
        wireCloudConfig()
        apiService.getScvmmZoneOpts(context, _) >> CREDS
        apiService.getScvmmInitializationOpts(_) >> [:]

        when:
        def result = provider."$method"([config: [host: 'h']])

        then:
        1 * apiService."$apiMethod"(CREDS) >> apiResult
        result == expected

        where:
        method            | apiMethod          | first           | apiResult                                          | expected
        'scvmmCloud'      | 'listClouds'       | 'Select a Cloud' | [clouds: [[Name: 'C1', ID: 'id-1']]]               | [[name: 'Select a Cloud', value: ''], [name: 'C1', value: 'id-1']]
        'scvmmHostGroup'  | 'listHostGroups'   | 'Select'        | [hostGroups: [[path: 'All Hosts\\HG1']]]          | [[name: 'Select', value: ''], [name: 'All Hosts\\HG1', value: 'All Hosts\\HG1']]
        'scvmmCluster'    | 'listClusters'     | 'All'           | [clusters: [[name: 'CL1', id: 'cl-1']]]            | [[name: 'All', value: ''], [name: 'CL1', value: 'cl-1']]
        'scvmmLibraryShares' | 'listLibraryShares' | '(none)'    | [libraryShares: [[Path: '\\\\lib\\share']]]       | [[name: '\\\\lib\\share', value: '\\\\lib\\share']]
    }

    def "#method returns the '#message' placeholder when SCVMM returns nothing"() {
        given:
        wireCloudConfig()
        apiService.getScvmmZoneOpts(context, _) >> CREDS
        apiService.getScvmmInitializationOpts(_) >> [:]
        apiService."$apiMethod"(_) >> apiResult

        expect:
        provider."$method"([config: [host: 'h']]) == [[name: message, value: '']]

        where:
        method               | apiMethod           | apiResult                    | message
        'scvmmCloud'         | 'listClouds'        | [success: false]             | 'No Clouds found: verify credentials above'
        'scvmmHostGroup'     | 'listHostGroups'    | [hostGroups: []]             | 'No Host Groups found'
        'scvmmCluster'       | 'listClusters'      | [clusters: null]             | 'No Clusters found: check your config'
        'scvmmLibraryShares' | 'listLibraryShares' | [libraryShares: []]          | 'No Library Shares found'
    }

    def "#method does not call SCVMM when the credentials are incomplete"() {
        given:
        wireCloudConfig()
        apiService.getScvmmZoneOpts(context, _) >> [sshUsername: 'admin', sshPassword: null]
        apiService.getScvmmInitializationOpts(_) >> [:]

        when:
        def result = provider."$method"([config: [host: 'h']])

        then:
        0 * apiService."$apiMethod"(_)
        result == [[name: message, value: '']]

        where:
        method               | apiMethod           | message
        'scvmmCloud'         | 'listClouds'        | 'No Clouds found: verify credentials above'
        'scvmmHostGroup'     | 'listHostGroups'    | 'No Host Groups found'
        'scvmmCluster'       | 'listClusters'      | 'No Clusters found: check your config'
        'scvmmLibraryShares' | 'listLibraryShares' | 'No Library Shares found'
    }

    def "scvmmSharedControllers offers at most one controller because it uses find, not list"() {
        // pinned: computeServer.find returns a single server and Groovy's collect wraps it, so only the first match
        // is ever offered
        given:
        DataQuery captured

        when:
        def result = provider.scvmmSharedControllers([[zoneId: 1L, config: [host: 'vmm.local']]] as Object[])

        then:
        1 * computeServerService.find(_) >> { DataQuery q -> captured = q; new ComputeServer(id: 2L, name: 'ctl') }
        result == [[name: 'ctl', value: 2L]]
        filterValue(captured, 'enabled') == true
        filterValue(captured, 'computeServerType.code') == 'scvmmController'
        captured.filters.find { it instanceof DataOrFilter }.value*.name == ['zone.id', 'sshHost']
    }

    def "scvmmSharedControllers returns null when the lookup finds nothing"() {
        given:
        computeServerService.find(_) >> null

        expect:
        provider.scvmmSharedControllers([:]) == null
    }

    def "consoleSessionMode offers auto, enhanced and normal"() {
        expect:
        provider.consoleSessionMode([:]) == ['auto', 'enhanced', 'normal'].collect { [name: it, value: it] }
        provider.methodNames.containsAll(['scvmmHost', 'scvmmVirtualImages', 'scvmmCapabilityProfile', 'scvmmSharedControllers'])
        provider.code == 'scvmm-option-source'
        provider.name == 'SCVMM Option Source'
    }

    private static Object filterValue(DataQuery query, String name) {
        query.filters?.find { it.name == name }?.value
    }
}
