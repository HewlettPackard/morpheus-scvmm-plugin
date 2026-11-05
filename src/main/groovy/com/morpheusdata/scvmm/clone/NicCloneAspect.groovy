// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.clone

import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ProcessEvent
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.PrefixedLoggerFactory

/**
 * MORPH-20363: makes the clone's network adapters match the NICs selected in the clone wizard.
 * <ul>
 *   <li>plan: reads the source's adapters before it is stopped and builds a {@link CloneNicPlanner} plan.</li>
 *   <li>clone job: sets each copied adapter to its request entry (network, subnet, VLAN, IP mode) and disconnects
 *       copies the request dropped, so placement never depends on a network that is being removed.</li>
 *   <li>before start: removes the dropped copies and creates the NICs added in the wizard (the VM is off, so this
 *       also works on Generation 1).</li>
 *   <li>records: binds each Morpheus interface to its adapter; a NIC SCVMM refused is dropped from Morpheus with
 *       its lease released and a History warning, instead of failing the clone.</li>
 * </ul>
 */
class NicCloneAspect extends CloneWizardAspect {

    private LogInterface log = PrefixedLoggerFactory.getLogger(NicCloneAspect)

    @Override
    String getName() {
        return 'network'
    }

    @Override
    ProcessEvent.ProcessType getHistoryType() {
        return ProcessEvent.ProcessType.provisionNetwork
    }

    @Override
    String getHistoryStatus() {
        return 'configuring network interfaces'
    }

    @Override
    Map plan(CloneContext ctx) {
        def details = ctx.apiService.getServerDetails(ctx.scvmmOpts, ctx.sourceVmId)
        if (details?.success != true) {
            return [success: false, error: "Unable to read the network adapters of the clone source VM ${ctx.sourceVmId}${details?.error ? ': ' + details.error : ''}".toString()]
        }
        Map plan = CloneNicPlanner.build(adapterList(details.server?.NetworkAdapters), ctx.networkConfig)
        if (plan.success) {
            log.info("clone: network adapters ${plan.updates.size()} reconfigured, ${plan.adds.size()} added, ${plan.removes.size()} removed (source ${ctx.sourceVmId})")
        }
        return plan
    }

    @Override
    List<String> cloneJobCommands(Map plan, String jobGroup) {
        List<String> commands = []
        (plan?.updates ?: []).each { Map update ->
            def index = update.index
            def nic = update.nic as Map
            def adapterVar = "\$CloneVNA${index}"
            def networkVar = "\$CloneVMNetwork${index}"
            def subnetVar = "\$CloneVMSubnet${index}"
            commands << "${adapterVar} = Get-SCVirtualNetworkAdapter -VMMServer localhost -VM \$VM | where { \$_.ID -eq \"${update.adapterId}\" }".toString()
            commands << "if (-not ${adapterVar}) { Write-Error \"Network adapter ${update.adapterId} of the clone source not found\"; Exit 24 }".toString()
            commands << "${networkVar} = Get-SCVMNetwork -VMMServer localhost -ID \"${nic.networkExternalId}\"".toString()
            commands << "if (-not ${networkVar}) { Write-Error \"VM network ${nic.networkExternalId} not found\"; Exit 25 }".toString()
            def vlanArgs = nic.vlanEnabled ? "-VLanEnabled \$true -VLanID ${nic.vlanId}" : "-VLanEnabled \$false"
            def ipArgs = nic.poolBound ? "-IPv4AddressType Static -IPv4Addresses \"${nic.ipAddress}\"" : '-IPv4AddressType Dynamic'
            def setAdapter = { String subnetArg ->
                "\$ignore = Set-SCVirtualNetworkAdapter -VirtualNetworkAdapter ${adapterVar} -VMNetwork ${networkVar} ${subnetArg}${vlanArgs} " +
                        "-MACAddressType Dynamic ${ipArgs} -IPv6AddressType Dynamic -NoPortClassification -EnableVMNetworkOptimization \$false " +
                        "-EnableMACAddressSpoofing \$false -JobGroup ${jobGroup}"
            }
            if (nic.subnetExternalId) {
                commands << "${subnetVar} = Get-SCVMSubnet -VMMServer localhost -ID \"${nic.subnetExternalId}\"".toString()
                commands << "if (-not ${subnetVar}) { Write-Error \"VM subnet ${nic.subnetExternalId} not found\"; Exit 26 }".toString()
                commands << setAdapter("-VMSubnet ${subnetVar} ").toString()
            } else {
                // Same default as before: a network with VM subnets uses its first one
                commands << "${subnetVar} = \$null; if (${networkVar}.VMSubnet) { ${subnetVar} = @(${networkVar}.VMSubnet)[0] }".toString()
                commands << "if (${subnetVar}) { ${setAdapter("-VMSubnet ${subnetVar} ")} } else { ${setAdapter('')} }".toString()
            }
            commands << "if (-not \$?) { Write-Error \"Unable to configure network adapter ${update.adapterId} for the clone\"; Exit 29 }".toString()
        }
        (plan?.removes ?: []).eachWithIndex { Map remove, int index ->
            def adapterVar = "\$CloneVNARemove${index}"
            commands << "${adapterVar} = Get-SCVirtualNetworkAdapter -VMMServer localhost -VM \$VM | where { \$_.ID -eq \"${remove.adapterId}\" }".toString()
            commands << "if (-not ${adapterVar}) { Write-Error \"Network adapter ${remove.adapterId} of the clone source not found\"; Exit 24 }".toString()
            commands << "\$ignore = Set-SCVirtualNetworkAdapter -VirtualNetworkAdapter ${adapterVar} -NoConnection -JobGroup ${jobGroup}".toString()
            commands << "if (-not \$?) { Write-Error \"Unable to disconnect network adapter ${remove.adapterId} for the clone\"; Exit 29 }".toString()
        }
        return commands
    }

    @Override
    Map applyBeforeStart(CloneContext ctx, Map plan) {
        return ctx.apiService.applyCloneNicPlan(ctx.scvmmOpts, ctx.cloneVmId, plan)
    }

    @Override
    List<String> reconcileRecords(CloneContext ctx, Map plan, Map applyResult) {
        List<String> warnings = []
        if (applyResult?.success != true) {
            warnings << "The clone's network adapters could not be read from SCVMM${applyResult?.error ? ' (' + applyResult.error + ')' : ''}. Its network interfaces will be matched on the next cloud sync.".toString()
            return warnings
        }
        def server = ctx.server
        // Core creates the clone's rows in request order (displayOrder = request index)
        List<ComputeServerInterface> rows = (server?.interfaces ?: []).sort(false) { it.displayOrder ?: 0 }
        Map failedByIndex = (applyResult.failedAdds ?: []).collectEntries { [(it.index as Integer): it.error] }
        Map bound = (applyResult.bound ?: [:]).collectEntries { k, v -> [(k as Integer): v?.toString()] }
        Map adaptersById = (applyResult.adapters ?: []).collectEntries { [(it.ID?.toString()): it] }
        List<ComputeServerInterface> changed = []
        rows.eachWithIndex { ComputeServerInterface row, int index ->
            if (failedByIndex.containsKey(index)) {
                warnings << dropFailedInterface(ctx, row, failedByIndex[index]?.toString())
                return
            }
            def adapterId = bound[index]
            if (!adapterId) {
                log.warn("clone: no SCVMM adapter recorded for network interface ${row.name ?: row.id} on ${ctx.cloneVmId}; it will be matched on the next cloud sync")
                return
            }
            row.externalId = adapterId
            def mac = adaptersById[adapterId]?.MacAddress
            if (mac) {
                row.macAddress = mac.toString()
            }
            changed << row
        }
        if (changed) {
            ctx.morpheusContext.async.computeServer.computeServerInterface.save(changed).blockingGet()
        }
        warnings.addAll((applyResult.warnings ?: []) as List<String>)
        return warnings
    }

    private String dropFailedInterface(CloneContext ctx, ComputeServerInterface row, String error) {
        def reason = error?.replaceAll(/[.\s]+$/, '') ?: 'unknown error'
        def label = "'${row.name ?: row.id}'${row.network?.name ? ' (' + row.network.name + ')' : ''}"
        try {
            def release = ctx.poolIpHelper?.releaseLeases(ctx.scvmmOpts, ctx.server, row)
            if (release != null && release.success != true) {
                log.warn("clone: unable to release the pool address of network interface ${label}: ${release.error}")
            }
            ctx.morpheusContext.async.computeServer.computeServerInterface.remove([row], ctx.server).blockingGet()
            ctx.server.interfaces?.remove(row)
        } catch (e) {
            log.error("clone: unable to remove network interface ${label} after SCVMM refused it: ${e.message}", e)
            return "Network interface ${label} could not be added to the VM: ${reason}. If it is still listed under Network it is not attached to the VM; remove it and add it again with Reconfigure once the issue is resolved.".toString()
        }
        return "Network interface ${label} could not be added to the VM: ${reason}. It has been removed from this instance; add it again with Reconfigure once the issue is resolved.".toString()
    }

    private static List<Map> adapterList(adapters) {
        if (adapters == null) {
            return []
        }
        return (adapters instanceof Map ? [adapters] : adapters as List) as List<Map>
    }
}
