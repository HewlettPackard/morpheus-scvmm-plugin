// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.clone

import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.PrefixedLoggerFactory

/**
 * Runs the {@link CloneWizardAspect}s, in order, through each phase of an SCVMM clone so the new VM matches
 * what was selected in the clone wizard. Add an aspect to {@link #forScvmm} to reconcile another setting.
 */
class CloneWizardReconciler {

    private LogInterface log = PrefixedLoggerFactory.getLogger(CloneWizardReconciler)

    final List<CloneWizardAspect> aspects

    CloneWizardReconciler(List<CloneWizardAspect> aspects) {
        this.aspects = aspects ?: []
    }

    /** The aspects the SCVMM plugin reconciles today. Volumes (MORPH-980) still run in createServer/runWorkload. */
    static CloneWizardReconciler forScvmm() {
        return new CloneWizardReconciler([new NicCloneAspect()])
    }

    /**
     * Plans every aspect before the source is stopped. Stops at the first failure.
     * @return [success: true, plans: [aspectName: plan]] or [success: false, error: reason]
     */
    Map plan(CloneContext ctx) {
        Map plans = [:]
        for (CloneWizardAspect aspect in aspects) {
            Map plan
            try {
                plan = aspect.plan(ctx)
            } catch (e) {
                log.error("clone: unable to plan ${aspect.name}: ${e.message}", e)
                plan = [success: false, error: e.message]
            }
            if (plan?.success != true) {
                return [success: false, error: plan?.error ?: "Unable to plan the clone's ${aspect.name}".toString()]
            }
            plans[aspect.name] = plan
        }
        return [success: true, plans: plans]
    }

    /** PowerShell lines for the clone's JobGroup, in aspect order. Pure: used by the clone script builder. */
    List<String> cloneJobCommands(Map plans, String jobGroup) {
        List<String> commands = []
        aspects.each { aspect ->
            if (plans?.containsKey(aspect.name)) {
                commands.addAll(aspect.cloneJobCommands(plans[aspect.name] as Map, jobGroup) ?: [])
            }
        }
        return commands
    }

    /** Applies each aspect's changes to the powered-off clone. @return [aspectName: applyResult] */
    Map applyBeforeStart(CloneContext ctx, Map plans) {
        Map results = [:]
        aspects.each { aspect ->
            if (!plans?.containsKey(aspect.name)) {
                return
            }
            try {
                results[aspect.name] = aspect.applyBeforeStart(ctx, plans[aspect.name] as Map)
            } catch (e) {
                log.error("clone: unable to apply ${aspect.name} to the clone: ${e.message}", e)
                results[aspect.name] = [success: false, error: e.message]
            }
        }
        return results
    }

    /**
     * Brings Morpheus records in line with the clone.
     * @return one entry per aspect with warnings: [type: ProcessEvent.ProcessType, status: String, warnings: [String]]
     */
    List<Map> reconcileRecords(CloneContext ctx, Map plans, Map applyResults) {
        List<Map> groups = []
        aspects.each { aspect ->
            if (!plans?.containsKey(aspect.name)) {
                return
            }
            List<String> warnings
            try {
                warnings = aspect.reconcileRecords(ctx, plans[aspect.name] as Map, (applyResults?.get(aspect.name) ?: [success: false]) as Map)
            } catch (e) {
                log.error("clone: unable to update Morpheus records for ${aspect.name}: ${e.message}", e)
                warnings = ["The clone's ${aspect.name} could not be fully recorded in Morpheus: ${e.message}".toString()]
            }
            if (warnings) {
                groups << [type: aspect.historyType, status: aspect.historyStatus, warnings: warnings]
            }
        }
        return groups
    }
}
