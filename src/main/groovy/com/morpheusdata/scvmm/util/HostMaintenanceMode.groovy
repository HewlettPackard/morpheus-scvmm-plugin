package com.morpheusdata.scvmm.util

import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer

/**
 * ComputeServer.maintenanceMode only exists from morpheus-plugin-api 1.6 (appliance 9.2). The plugin requires 1.6, so
 * the field is expected; every read, write and query filter still goes through here so a model without it is a no-op
 * rather than a MissingPropertyException that would stop host stats sync and placement.
 */
class HostMaintenanceMode {

    static final String PROPERTY = 'maintenanceMode'
    static final String SCVMM_MAINTENANCE_STATUS = 'In Maintenance Mode'

    // Whether the running plugin API (and therefore the appliance) knows the field; queries can only filter on it then.
    static boolean apiSupported = ComputeServer.metaClass.getMetaProperty(PROPERTY) != null

    static boolean isSupported(ComputeServer server) {
        server?.hasProperty(PROPERTY) != null
    }

    static boolean isInMaintenance(ComputeServer server) {
        isSupported(server) && server."$PROPERTY" == true
    }

    /**
     * Applies SCVMM's OverallStateString. A blank status keeps the stored value so a sparse payload cannot take a
     * drained host out of maintenance.
     * @return true when the stored value changed
     */
    static boolean applyScvmmStatus(ComputeServer server, status) {
        String rawStatus = status?.toString()?.trim()
        if (!rawStatus || !isSupported(server)) {
            return false
        }
        boolean inMaintenance = rawStatus.equalsIgnoreCase(SCVMM_MAINTENANCE_STATUS)
        if (server."$PROPERTY" == inMaintenance) {
            return false
        }
        server."$PROPERTY" = inMaintenance
        return true
    }

    /**
     * Excludes hosts in maintenance mode; NULL is a host not synced since the flag existed and stays eligible.
     */
    static DataQuery excludeHostsInMaintenance(DataQuery query) {
        if (apiSupported) {
            query.withFilters(new DataOrFilter(new DataFilter(PROPERTY, false), new DataFilter(PROPERTY, null)))
        }
        return query
    }
}
