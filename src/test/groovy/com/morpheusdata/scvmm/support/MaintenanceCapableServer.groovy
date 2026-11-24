package com.morpheusdata.scvmm.support

import com.morpheusdata.model.ComputeServer

/**
 * ComputeServer with an explicit maintenanceMode, so maintenance-mode specs do not depend on which plugin-api jar
 * resolves (a stale local 1.5.1 build lacks the field).
 */
class MaintenanceCapableServer extends ComputeServer {
    Boolean maintenanceMode
}
