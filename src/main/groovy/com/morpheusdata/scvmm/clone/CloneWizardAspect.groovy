// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.clone

import com.morpheusdata.model.ProcessEvent

/**
 * One kind of setting the clone wizard can change (network interfaces, volumes, ...).
 * <p>
 * SCVMM's clone ({@code New-SCVirtualMachine -VM}) copies the source VM's hardware as is, so every setting the
 * wizard can change has to be reconciled by the plugin in the same four phases:
 * <ol>
 *   <li>{@link #plan}: before the source is stopped, read the source's state from SCVMM and diff it against the
 *       request. A failure stops the clone before anything is touched.</li>
 *   <li>{@link #cloneJobCommands}: PowerShell lines run in the clone's JobGroup, so they apply to the new VM.</li>
 *   <li>{@link #applyBeforeStart}: changes made once the clone exists, while it is still powered off.</li>
 *   <li>{@link #reconcileRecords}: bind or drop Morpheus records to match what was built; returns warnings for
 *       the instance History.</li>
 * </ol>
 * Each phase defaults to a no-op so an aspect implements only what it needs.
 */
abstract class CloneWizardAspect {

    /** Key of this aspect's plan in {@code scvmmOpts.clonePlans}. */
    abstract String getName()

    /** History step type used for this aspect's warnings. */
    ProcessEvent.ProcessType getHistoryType() {
        return ProcessEvent.ProcessType.provision
    }

    /** History step status text used for this aspect's warnings. */
    String getHistoryStatus() {
        return "configuring ${name}".toString()
    }

    /** @return [success: true, ...plan] or [success: false, error: reason] */
    Map plan(CloneContext ctx) {
        return [success: true]
    }

    List<String> cloneJobCommands(Map plan, String jobGroup) {
        return []
    }

    /** @return [success: true/false, ...] describing what was applied; never throws for a per-item failure */
    Map applyBeforeStart(CloneContext ctx, Map plan) {
        return [success: true]
    }

    /** @return warnings for the customer (empty when nothing needs their attention) */
    List<String> reconcileRecords(CloneContext ctx, Map plan, Map applyResult) {
        return []
    }
}
