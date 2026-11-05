// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.clone

import com.morpheusdata.model.ProcessEvent
import spock.lang.Specification

/**
 * MORPH-20363: the clone wizard reconciler runs every aspect through each clone phase, in order.
 */
class CloneWizardReconcilerSpec extends Specification {

    static class Recording extends CloneWizardAspect {
        String name
        List<String> calls
        Map planResult = [success: true]
        List<String> commands = []
        Map applyResult = [success: true]
        List<String> warnings = []
        boolean throwOnApply
        boolean throwOnPlan

        String getName() { name }

        Map plan(CloneContext ctx) {
            calls << "plan:${name}".toString()
            if (throwOnPlan) {
                throw new IllegalStateException('kaput')
            }
            planResult
        }

        List<String> cloneJobCommands(Map plan, String jobGroup) { commands.collect { "${it} -JobGroup ${jobGroup}".toString() } }

        Map applyBeforeStart(CloneContext ctx, Map plan) {
            calls << "apply:${name}".toString()
            if (throwOnApply) {
                throw new IllegalStateException('boom')
            }
            applyResult
        }

        List<String> reconcileRecords(CloneContext ctx, Map plan, Map applyResult) { calls << "records:${name}:${applyResult.success}".toString(); warnings }
    }

    static class NetworkRecording extends Recording {
        ProcessEvent.ProcessType getHistoryType() { ProcessEvent.ProcessType.provisionNetwork }

        String getHistoryStatus() { 'configuring network interfaces' }
    }

    static class NoOp extends CloneWizardAspect {
        String getName() { 'noop' }
    }

    List<String> calls = []
    CloneContext ctx = new CloneContext()

    def "plans every aspect in order and keys each plan by aspect name"() {
        given:
        def reconciler = new CloneWizardReconciler([new Recording(name: 'network', calls: calls, planResult: [success: true, n: 1]),
                                                    new Recording(name: 'storage', calls: calls, planResult: [success: true, s: 2])])

        when:
        def rtn = reconciler.plan(ctx)

        then:
        rtn.success
        rtn.plans == [network: [success: true, n: 1], storage: [success: true, s: 2]]
        calls == ['plan:network', 'plan:storage']
    }

    def "the first aspect that can't plan stops the clone with its reason"() {
        given:
        def reconciler = new CloneWizardReconciler([new Recording(name: 'network', calls: calls, planResult: [success: false, error: 'no adapters']),
                                                    new Recording(name: 'storage', calls: calls)])

        when:
        def rtn = reconciler.plan(ctx)

        then:
        !rtn.success
        rtn.error == 'no adapters'
        calls == ['plan:network']
    }

    def "an aspect that throws while planning fails the plan instead of escaping"() {
        expect:
        new CloneWizardReconciler([new Recording(name: 'network', calls: calls, throwOnPlan: true)]).plan(ctx) == [success: false, error: 'kaput']
    }

    def "clone job commands are concatenated in aspect order, only for planned aspects"() {
        given:
        def reconciler = new CloneWizardReconciler([new Recording(name: 'network', calls: calls, commands: ['Set-A']),
                                                    new Recording(name: 'storage', calls: calls, commands: ['Set-B'])])

        expect:
        reconciler.cloneJobCommands([network: [:], storage: [:]], 'g1') == ['Set-A -JobGroup g1', 'Set-B -JobGroup g1']
        reconciler.cloneJobCommands([storage: [:]], 'g1') == ['Set-B -JobGroup g1']
        reconciler.cloneJobCommands(null, 'g1') == []
    }

    def "apply and records run per planned aspect; a failed apply still reaches records as a failure"() {
        given:
        def reconciler = new CloneWizardReconciler([new Recording(name: 'network', calls: calls, throwOnApply: true),
                                                    new Recording(name: 'storage', calls: calls)])
        def plans = [network: [:], storage: [:]]

        when:
        def results = reconciler.applyBeforeStart(ctx, plans)
        reconciler.reconcileRecords(ctx, plans, results)

        then:
        results.network == [success: false, error: 'boom']
        results.storage == [success: true]
        calls == ['apply:network', 'apply:storage', 'records:network:false', 'records:storage:true']
    }

    def "warnings come back as one History group per aspect, using the aspect's step type and status"() {
        given:
        def network = new NetworkRecording(name: 'network', calls: calls, warnings: ['nic warning'])
        def quiet = new Recording(name: 'storage', calls: calls)
        def reconciler = new CloneWizardReconciler([network, quiet])

        when:
        def groups = reconciler.reconcileRecords(ctx, [network: [:], storage: [:]], [network: [success: true], storage: [success: true]])

        then:
        groups == [[type: ProcessEvent.ProcessType.provisionNetwork, status: 'configuring network interfaces', warnings: ['nic warning']]]
    }

    def "an aspect only overriding its name is a no-op in every phase"() {
        given:
        def reconciler = new CloneWizardReconciler([new NoOp()])

        expect:
        reconciler.plan(ctx) == [success: true, plans: [noop: [success: true]]]
        reconciler.cloneJobCommands([noop: [:]], 'g') == []
        reconciler.applyBeforeStart(ctx, [noop: [:]]) == [noop: [success: true]]
        reconciler.reconcileRecords(ctx, [noop: [:]], [noop: [success: true]]) == []
        new NoOp().historyType == ProcessEvent.ProcessType.provision
        new NoOp().historyStatus == 'configuring noop'
    }

    def "the SCVMM reconciler handles the network today"() {
        expect:
        CloneWizardReconciler.forScvmm().aspects*.name == ['network']
    }
}
