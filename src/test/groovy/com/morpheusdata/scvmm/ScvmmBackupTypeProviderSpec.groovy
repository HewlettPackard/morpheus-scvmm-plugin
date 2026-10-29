// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.BackupProvider as BackupProviderModel
import spock.lang.Specification
import spock.lang.Subject

class ScvmmBackupTypeProviderSpec extends Specification {

    ScvmmPlugin plugin = Mock()
    MorpheusContext context = Mock()

    @Subject
    ScvmmBackupTypeProvider provider = new ScvmmBackupTypeProvider(plugin, context)

    def "backup type contract property #property is #expected"() {
        expect:
        provider."$property" == expected

        where:
        property                 | expected
        'code'                   | 'scvmmSnapshot'
        'name'                   | 'SCVMM VM Snapshot'
        'containerType'          | 'single'
        'copyToStore'            | false
        'downloadEnabled'        | false
        'restoreExistingEnabled' | true
        'restoreNewEnabled'      | false
        'restoreType'            | 'offline'
        'restoreNewMode'         | null
        'hasCopyToStore'         | false
    }

    def "getOptionTypes returns an empty, mutable list"() {
        when:
        def optionTypes = provider.optionTypes

        then:
        optionTypes != null
        optionTypes.isEmpty()
        optionTypes instanceof ArrayList
    }

    def "constructor keeps the plugin and context for the lazily built providers"() {
        expect:
        provider.plugin.is(plugin)
        provider.morpheusContext.is(context)
        provider.morpheus.is(context)
    }

    def "getExecutionProvider lazily builds one ScvmmBackupExecutionProvider and reuses it"() {
        when:
        def first = provider.executionProvider
        def second = provider.executionProvider

        then:
        first instanceof ScvmmBackupExecutionProvider
        first.is(second)
        first.morpheusContext.is(context)
        first.plugin.is(plugin)
    }

    def "getRestoreProvider lazily builds one ScvmmBackupRestoreProvider and reuses it"() {
        when:
        def first = provider.restoreProvider
        def second = provider.restoreProvider

        then:
        first instanceof ScvmmBackupRestoreProvider
        first.is(second)
        first.morpheusContext.is(context)
        first.plugin.is(plugin)
    }

    def "an injected execution provider is returned instead of building one"() {
        given:
        ScvmmBackupExecutionProvider injected = Mock()
        provider.executionProvider = injected

        expect:
        provider.executionProvider.is(injected)
    }

    def "refresh and clean are no-op successes"() {
        expect:
        provider.refresh([:], new BackupProviderModel()).success
        provider.clean(new BackupProviderModel(), [:]).success
    }
}
