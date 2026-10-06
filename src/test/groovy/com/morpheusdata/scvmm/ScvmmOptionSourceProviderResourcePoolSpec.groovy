// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
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

    @Subject
    ScvmmOptionSourceProvider provider = new ScvmmOptionSourceProvider(plugin, context)

    Cloud cloud = new Cloud(id: 1L)

    def setup() {
        context.services >> services
        services.cloud >> cloudService
        cloudService.pool >> poolService
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

    private static Object filterValue(DataQuery query, String name) {
        query.filters?.find { it.name == name }?.value
    }
}
