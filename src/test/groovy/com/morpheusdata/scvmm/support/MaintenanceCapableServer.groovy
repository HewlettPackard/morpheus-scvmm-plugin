package com.morpheusdata.scvmm.support

import com.morpheusdata.model.ComputeServer

/**
 * ComputeServer as shipped by morpheus-plugin-api 1.6+, which adds maintenanceMode. The build targets 1.5.1, where the
 * field is missing, so maintenance-mode specs use this to behave the same against either API.
 */
class MaintenanceCapableServer extends ComputeServer {
    Boolean maintenanceMode
}
