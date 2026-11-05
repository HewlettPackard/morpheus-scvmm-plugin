// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.clone

import com.morpheusdata.scvmm.ScvmmConstants

/**
 * MORPH-20363: reconciles the clone request's NICs against the source VM's adapters by slot order, as VMware does.
 * <p>
 * The clone's adapter in sorted slot i takes request entry i, extra entries are added after the clone exists and
 * surplus copies (always the trailing slots) are removed. A copied NIC carries no state Morpheus keeps (the clone
 * resets MAC and IP; network, VLAN and IP mode come from the request), and core gives no identity linking a
 * request entry to a source NIC, so no identity is needed or used. Request order = slot order also keeps the
 * guest's eth<n> names in line with the cloud-init network config, which core renders as eth<request index>.
 */
class CloneNicPlanner {

    static final String SCVMM_POOL_TYPE = 'scvmm'

    /**
     * @param sourceAdapters the source VM's adapters from SCVMM (ID, SlotId); null when they couldn't be read
     * @param networkConfig core's networkConfig for the clone (primaryInterface + extraInterfaces in request order)
     * @return [success: true, requested, nics, updates, adds, removes] or [success: false, error]
     *         updates: [index, slotId, adapterId, iface, nic]; adds: [index, iface, nic]; removes: [slotId, adapterId]
     */
    static Map build(List<Map> sourceAdapters, Map networkConfig) {
        if (sourceAdapters == null) {
            return [success: false, error: "Unable to read the source VM's network adapters"]
        }
        Map primary = networkConfig?.primaryInterface as Map
        if (!primary?.network?.externalId) {
            return [success: false, error: 'The clone request has no primary network interface']
        }
        boolean primaryStatic = isStatic(primary, networkConfig?.doStatic)
        List<Map> requested = [entry(primary, primaryStatic)]
        (networkConfig.extraInterfaces ?: []).each { extra ->
            if (extra?.network?.externalId) {
                requested << entry(extra as Map, isStatic(extra as Map, primaryStatic))
            }
        }
        if (requested.size() > ScvmmConstants.MAX_NETWORKS) {
            return [success: false, error: "A clone can have at most ${ScvmmConstants.MAX_NETWORKS} network interfaces; ${requested.size()} were requested".toString()]
        }

        List<Map> source = sourceAdapters.findAll { it != null }.sort { a, b -> slotNumber(a.SlotId) <=> slotNumber(b.SlotId) }
        List<Map> updates = []
        List<Map> adds = []
        List<Map> removes = []
        requested.eachWithIndex { Map req, int index ->
            if (index < source.size()) {
                updates << [index: index, slotId: slotNumber(source[index].SlotId), adapterId: source[index].ID?.toString(), iface: req.iface, nic: req.nic]
            } else {
                adds << [index: index, iface: req.iface, nic: req.nic]
            }
        }
        for (int i = requested.size(); i < source.size(); i++) {
            removes << [slotId: slotNumber(source[i].SlotId), adapterId: source[i].ID?.toString()]
        }
        return [success: true, requested: requested*.iface, nics: requested*.nic, updates: updates, adds: adds, removes: removes]
    }

    /** Same rule as provisioning: an explicit per-NIC mode wins, otherwise the fallback (the primary's mode). */
    static boolean isStatic(Map iface, fallback) {
        if (iface?.ipMode?.toString() == 'dhcp') {
            return false
        }
        if (iface?.doStatic != null) {
            return iface.doStatic as boolean
        }
        if (iface?.networkType) {
            return iface.networkType == 'static'
        }
        return fallback as boolean
    }

    private static Map entry(Map iface, boolean isStatic) {
        def ipAddress = iface.ipAddress?.toString()?.trim() ?: null
        def poolExternalId = iface.networkPool?.externalId?.toString()?.take(36) ?: null
        // Only an SCVMM pool address is static in SCVMM; any other static IP is applied in the guest by cloud-init.
        boolean poolBound = isStatic && iface.poolType == SCVMM_POOL_TYPE && ipAddress && poolExternalId
        Integer vlanId = iface.vlanId?.toString()?.isInteger() ? iface.vlanId.toString().toInteger() : null
        return [iface: iface, nic: [
                networkExternalId: iface.network.externalId.toString().take(36),
                subnetExternalId : iface.subnet?.externalId?.toString()?.take(36) ?: null,
                vlanEnabled      : (vlanId ?: 0) > 0,
                vlanId           : (vlanId ?: 0) > 0 ? vlanId : null,
                poolBound        : poolBound,
                ipAddress        : poolBound ? ipAddress : null,
                poolExternalId   : poolBound ? poolExternalId : null,
        ]]
    }

    static int slotNumber(slotId) {
        def text = slotId?.toString()
        return text?.isInteger() ? text.toInteger() : Integer.MAX_VALUE
    }
}
