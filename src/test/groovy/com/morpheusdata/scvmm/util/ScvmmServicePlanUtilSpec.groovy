// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.util

import com.morpheusdata.model.Account
import com.morpheusdata.model.ResourcePermission
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.scvmm.support.Domain
import spock.lang.Specification

class ScvmmServicePlanUtilSpec extends Specification {

    static final long MB = 1024L * 1024L
    static final long GB = 1024L * MB

    Account tenant = new Account(id: 5L)
    ServicePlan fallback = Domain.plan(id: 99L, code: 'internal-custom-scvmm', customMaxMemory: true, customCores: true, maxCores: null, maxMemory: null)
    ServicePlan static4g = Domain.plan(id: 41L, maxCores: 2L, maxMemory: 4L * GB, sortOrder: 1)
    ServicePlan static8g = Domain.plan(id: 42L, maxCores: 2L, maxMemory: 8L * GB, sortOrder: 2)
    ServicePlan dynamic2to8 = Domain.plan(id: 43L, maxCores: 2L, maxMemory: 4L * GB, sortOrder: 3,
            config: [ranges: [minMemory: 2L * GB, maxMemory: 8L * GB]])
    ServicePlan dynamic1to16 = Domain.plan(id: 44L, maxCores: 2L, maxMemory: 4L * GB, sortOrder: 4,
            config: [ranges: [minMemory: 1L * GB, maxMemory: 16L * GB]])

    private ServicePlan find(Map args) {
        ScvmmServicePlanUtil.findServicePlanBySizing(args.plans ?: [static4g, static8g, dynamic2to8, dynamic1to16], args.memory, args.cores ?: 2L,
                fallback, args.existing, args.account, args.permissions, args.dynamic, args.assignedMB, args.minMB, args.maxMB)
    }

    def "a VM without dynamic memory matches the static plan of its memory and cores"() {
        expect:
        find(memory: 8L * GB).is(static8g)
    }

    def "a VM with dynamic memory matches on assigned memory against plans whose range contains it"() {
        expect:
        find(memory: 16L * GB, dynamic: 'True', assignedMB: 3072, minMB: 0, maxMB: 0).is(dynamic2to8)
    }

    def "a VM with dynamic memory prefers the plan whose range equals its min/max"() {
        expect:
        find(memory: 16L * GB, dynamic: true, assignedMB: 3072, minMB: 1024, maxMB: 16384).is(dynamic1to16)
    }

    def "a dynamic-memory VM does not match a static plan and a static VM does not match a dynamic plan"() {
        expect:
        find(plans: [static4g], memory: 4L * GB, dynamic: true, assignedMB: 4096).is(fallback)
        find(plans: [dynamic2to8], memory: 4L * GB, dynamic: false).is(fallback)
    }

    def "the existing plan is kept while the dynamic memory stays in its range"() {
        expect:
        find(existing: dynamic1to16, memory: 16L * GB, dynamic: true, assignedMB: 6144, minMB: 2048, maxMB: 8192).is(dynamic1to16)
    }

    def "the existing plan is replaced once the VM no longer fits it"() {
        expect:
        find(existing: static4g, memory: 8L * GB).is(static8g)
    }

    def "a private plan is only offered to its account or through a resource permission"() {
        given:
        def privatePlan = Domain.plan(id: 45L, maxCores: 2L, maxMemory: 12L * GB, visibility: 'private', account: new Account(id: 77L))
        def permission = new ResourcePermission(morpheusResourceId: 45L, account: tenant)

        expect:
        find(plans: [privatePlan], memory: 12L * GB, account: tenant).is(fallback)
        find(plans: [privatePlan], memory: 12L * GB, account: tenant, permissions: [permission]).is(privatePlan)
    }

    def "an inaccessible existing plan is dropped"() {
        given:
        def privatePlan = Domain.plan(id: 45L, maxCores: 2L, maxMemory: 4L * GB, visibility: 'private', account: new Account(id: 77L))

        expect:
        find(plans: [static4g], existing: privatePlan, memory: 4L * GB, account: tenant).is(static4g)
    }

    def "a single-core VM matches a plan without a core count"() {
        given:
        def anyCores = Domain.plan(id: 46L, maxCores: 0L, maxMemory: 2L * GB)

        expect:
        find(plans: [anyCores], memory: 2L * GB, cores: 1L).is(anyCores)
    }

    def "custom plans are used when nothing matches exactly, otherwise the fallback"() {
        given:
        def customMemory = Domain.plan(id: 47L, maxCores: 2L, maxMemory: null, customMaxMemory: true)

        expect:
        find(plans: [static4g, customMemory], memory: 6L * GB).is(customMemory)
        find(plans: [static4g], memory: 6L * GB).is(fallback)
    }
}
