package com.morpheusdata.scvmm.util

import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.Account
import com.morpheusdata.model.ResourcePermission
import com.morpheusdata.model.ServicePlan

/**
 * Service plan matching for discovered SCVMM VMs. Unlike the generic {@code SyncUtils.findServicePlanBySizing},
 * this accounts for Hyper-V dynamic memory: a VM with dynamic memory is matched on its assigned memory, against
 * plans whose dynamic memory range ({@code ranges.minMemory}/{@code ranges.maxMemory} in the plan config)
 * contains it, preferring a plan whose range equals the VM's configured minimum/maximum.
 */
class ScvmmServicePlanUtil {

    static ServicePlan findServicePlanBySizing(Collection<ServicePlan> allPlans, Long maxMemory, Long maxCores,
                                               ServicePlan fallbackPlan = null, ServicePlan existingPlan = null,
                                               Account account = null, Collection<ResourcePermission> resourcePermissions = null,
                                               dynamicMemoryEnabled = null, memoryAssignedMB = null,
                                               minDynamicMemoryMB = null, maxDynamicMemoryMB = null) {
        boolean usingDynamicMemory = isTrue(dynamicMemoryEnabled)
        Long assignedMB = asLong(memoryAssignedMB)
        if (usingDynamicMemory && assignedMB) {
            maxMemory = assignedMB * ComputeUtility.ONE_MEGABYTE
        }

        Collection<ServicePlan> availablePlans = allPlans ?: []
        if (account) {
            availablePlans = availablePlans.findAll { pl -> isAccessible(pl, account, resourcePermissions) }
            if (existingPlan && !isAccessible(existingPlan, account, resourcePermissions)) {
                existingPlan = null
            }
        }

        if (existingPlan && !existingPlan.is(fallbackPlan) && (existingPlan.id == null || existingPlan.id != fallbackPlan?.id)) {
            boolean coresMatch = (existingPlan.maxCores == 0 && maxCores == 1) || existingPlan.maxCores == maxCores || existingPlan.customCores
            boolean dynamicMatch = planHasDynamicMemory(existingPlan) == usingDynamicMemory
            boolean memoryMatch = existingPlan.maxMemory == maxMemory || existingPlan.customMaxMemory ||
                    dynamicMemoryInRangeOfPlan(existingPlan, usingDynamicMemory, maxMemory)
            if (memoryMatch && coresMatch && dynamicMatch) {
                return existingPlan
            }
        }

        Collection<ServicePlan> matchedPlans = availablePlans.findAll { ServicePlan it ->
            ((it.maxMemory == maxMemory && it.customMaxMemory != true) || dynamicMemoryInRangeOfPlan(it, usingDynamicMemory, maxMemory)) &&
                    it.customCores != true && coresFit(it, maxCores) &&
                    (it.coresPerSocket == null || it.coresPerSocket == 1) &&
                    planHasDynamicMemory(it) == usingDynamicMemory
        }
        if (!matchedPlans) {
            matchedPlans = availablePlans.findAll { it.maxMemory == maxMemory && it.customCores }
        }
        if (!matchedPlans) {
            matchedPlans = availablePlans.findAll { ServicePlan it ->
                it.customMaxMemory && coresFit(it, maxCores) && (it.coresPerSocket == null || it.coresPerSocket == 1)
            }
        }
        if (!matchedPlans) {
            matchedPlans = availablePlans.findAll { it.customMaxMemory && it.customCores }
        }

        if (!matchedPlans) {
            return fallbackPlan
        }
        matchedPlans = matchedPlans.sort { it.sortOrder ?: 0 }
        if (usingDynamicMemory) {
            Long minBytes = (asLong(minDynamicMemoryMB) ?: 0L) * ComputeUtility.ONE_MEGABYTE
            Long maxBytes = (asLong(maxDynamicMemoryMB) ?: 0L) * ComputeUtility.ONE_MEGABYTE
            def dynamicPlan = matchedPlans.find { ServicePlan it ->
                if (!planHasDynamicMemory(it)) {
                    return false
                }
                def ranges = planRanges(it)
                asLong(ranges.minMemory) == minBytes && asLong(ranges.maxMemory) == maxBytes
            }
            if (dynamicPlan) {
                return dynamicPlan
            }
        }
        return matchedPlans.first()
    }

    static boolean planHasDynamicMemory(ServicePlan plan) {
        def ranges = planRanges(plan)
        return asLong(ranges.minMemory) && asLong(ranges.maxMemory)
    }

    static boolean dynamicMemoryInRangeOfPlan(ServicePlan plan, boolean usingDynamicMemory, Long memory) {
        if (!usingDynamicMemory || memory == null || !planHasDynamicMemory(plan)) {
            return false
        }
        def ranges = planRanges(plan)
        return asLong(ranges.maxMemory) >= memory && asLong(ranges.minMemory) <= memory
    }

    private static boolean coresFit(ServicePlan plan, Long maxCores) {
        return (maxCores == 1 && (plan.maxCores == null || plan.maxCores == 0)) || plan.maxCores == maxCores
    }

    private static boolean isAccessible(ServicePlan plan, Account account, Collection<ResourcePermission> resourcePermissions) {
        return plan.visibility == 'public' || plan.account?.id == account.id || plan.owner?.id == account.id ||
                resourcePermissions?.find { rp -> rp.morpheusResourceId == plan.id && rp.account?.id == account.id } != null
    }

    private static Map planRanges(ServicePlan plan) {
        def ranges = plan?.getConfigProperty('ranges')
        return ranges instanceof Map ? ranges : [:]
    }

    private static boolean isTrue(value) {
        return value == true || value?.toString()?.equalsIgnoreCase('true')
    }

    private static Long asLong(value) {
        if (value == null || value == '') {
            return null
        }
        if (value instanceof Number) {
            return ((Number) value).longValue()
        }
        try {
            return new BigDecimal(value.toString().trim()).longValue()
        } catch (NumberFormatException ignored) {
            return null
        }
    }
}
