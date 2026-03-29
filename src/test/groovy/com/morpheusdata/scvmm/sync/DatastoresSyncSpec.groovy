// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.scvmm.ScvmmConstants
import spock.lang.Specification

// >>> MORPH-9119
class DatastoresSyncSpec extends Specification {
    MorpheusContext morpheusContext = Mock(MorpheusContext)
    MorpheusServices morpheusServices = Mock(MorpheusServices)
    MorpheusSynchronousCloudService morpheusCloud = Mock(MorpheusSynchronousCloudService)
    MorpheusSynchronousCloudPoolService morpheusCloudPool = Mock(MorpheusSynchronousCloudPoolService)

    // Static constant pool for parameterized test data (where clause requires static access)
    static final CloudPool MOCK_DEFAULT_POOL = new CloudPool(id: 123L, name: 'default-pool')

    def setup() {
        morpheusContext.services >> morpheusServices
        morpheusServices.cloud >> morpheusCloud
        morpheusCloud.pool >> morpheusCloudPool
    }

    /**
     * Validates that the DataQuery contains all 5 expected filters with correct keys and values
     * for getDefaultResourcePool() method. Includes defensive null checks to ensure API compatibility.
     *
     * @param dataQuery The DataQuery to validate (must not be null)
     * @param expectedCloudId The expected cloud.id value in the refId filter
     * @throws AssertionError if any filter is missing or has incorrect value
     */
    private static void verifyGetDefaultResourcePoolDataQueryFilters(DataQuery dataQuery, Long expectedCloudId) {
        assert dataQuery != null, "DataQuery must not be null"
        assert dataQuery.filters != null, "DataQuery.filters must not be null"
        assert dataQuery.filters.size() == 5, "Expected exactly 5 filters, got ${dataQuery.filters.size()}"

        def filterMap = dataQuery.filters.collectEntries { filter ->
            assert filter != null, "Filter entry must not be null"
            assert filter.name != null, "Filter.name must not be null: ${filter}"
            [filter.name, filter.value]
        }

        assert filterMap.containsKey(ScvmmConstants.KEY_NAME),
                "Missing ${ScvmmConstants.KEY_NAME} filter"
        assert filterMap[ScvmmConstants.KEY_NAME] == ScvmmConstants.DEFAULT_RESOURCE_POOL_NAME,
                "Incorrect name filter: expected ${ScvmmConstants.DEFAULT_RESOURCE_POOL_NAME}, " +
                        "got ${filterMap[ScvmmConstants.KEY_NAME]}"

        assert filterMap.containsKey(ScvmmConstants.KEY_EXTERNAL_ID),
                "Missing ${ScvmmConstants.KEY_EXTERNAL_ID} filter"
        assert filterMap[ScvmmConstants.KEY_EXTERNAL_ID] == ScvmmConstants.DEFAULT_RESOURCE_POOL_EXTERNAL_ID,
                "Incorrect externalId filter"

        assert filterMap.containsKey(ScvmmConstants.KEY_REF_ID),
                "Missing ${ScvmmConstants.KEY_REF_ID} filter"
        assert filterMap[ScvmmConstants.KEY_REF_ID] == expectedCloudId,
                "Incorrect refId filter: expected ${expectedCloudId}, got ${filterMap[ScvmmConstants.KEY_REF_ID]}"

        assert filterMap.containsKey(ScvmmConstants.KEY_REF_TYPE),
                "Missing ${ScvmmConstants.KEY_REF_TYPE} filter"
        assert filterMap[ScvmmConstants.KEY_REF_TYPE] == ScvmmConstants.REF_CLOUD,
                "Incorrect refType filter"

        assert filterMap.containsKey(ScvmmConstants.KEY_DEFAULT_POOL),
                "Missing ${ScvmmConstants.KEY_DEFAULT_POOL} filter"
        assert filterMap[ScvmmConstants.KEY_DEFAULT_POOL] == true,
                "Incorrect defaultPool filter"
    }

    def "test getDefaultResourcePool #description"() {
        given:
        // Create cloud object based on test scenario (can be null or have various id values)
        Cloud cloud = (testCloud ? new Cloud(name: 'mock-cloud', id: testCloud) : null)
        // Note: First parameter (node) is null in unit tests since it is not exercised
        DatastoresSync sync = new DatastoresSync(null, cloud, morpheusContext)

        when:
        CloudPool defaultPool = sync.getDefaultResourcePool()

        then:
        // For null cloud, exception occurs before reaching mocks; for valid clouds, verify chain
        (testCloud ? 1 : 0) * morpheusContext.services >> morpheusServices
        (testCloud ? 1 : 0) * morpheusServices.cloud >> morpheusCloud
        (testCloud ? 1 : 0) * morpheusCloud.pool >> morpheusCloudPool
        (testCloud ? 1 : 0) * morpheusCloudPool.find(*_) >> { DataQuery dataQuery ->
            verifyGetDefaultResourcePoolDataQueryFilters(dataQuery, testCloud)
            if (mockResponse instanceof Exception) {
                throw mockResponse
            }
            return mockResponse
        }

        // Validate return type and value
        assert defaultPool instanceof CloudPool || defaultPool == null,
                "Expected CloudPool or null, got ${defaultPool?.class?.name}"
        defaultPool == expectedDefaultPool

        // If success case, validate specific properties
        if (expectedDefaultPool) {
            assert defaultPool.id == expectedDefaultPool.id
            assert defaultPool.name == expectedDefaultPool.name
        }

        where:
        [description, testCloud, mockResponse, expectedDefaultPool] << [
                // Normal operation scenarios
                [
                        'handles RuntimeException from pool.find: returns null',
                        1L,
                        new RuntimeException('Connection failed'),
                        null,
                ],
                [
                        'handles null response from pool.find: returns null',
                        1L,
                        null,
                        null,
                ],
                [
                        'successful pool retrieval with valid id: returns CloudPool',
                        1L,
                        MOCK_DEFAULT_POOL,
                        MOCK_DEFAULT_POOL,
                ],
                // Null cloud object - exception thrown before reaching mocks
                [
                        'handles null cloud object gracefully: returns null',
                        (Long) null,
                        null,
                        null,
                ],
        ]
    }
}
// <<< MORPH-9119