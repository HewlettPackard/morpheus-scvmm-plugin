// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.backup.BackupTypeProvider
import com.morpheusdata.model.BackupProvider as BackupProviderModel
import spock.lang.Specification

class ScvmmBackupProviderSpec extends Specification {

    ScvmmPlugin plugin = Mock()
    MorpheusContext context = Mock()

    def "constructor registers exactly one ScvmmBackupTypeProvider with the plugin"() {
        given:
        BackupTypeProvider registered

        when:
        def provider = new ScvmmBackupProvider(plugin, context)

        then:
        1 * plugin.registerProvider(_ as ScvmmBackupTypeProvider) >> { args -> registered = args[0] }
        0 * plugin.registerProvider(_)
        provider.backupProviders == [registered]
    }

    def "the registered type provider is scoped to the scvmm provision type with no container type"() {
        when:
        def provider = new ScvmmBackupProvider(plugin, context)
        def scoped = provider.scopedProviders

        then:
        scoped.size() == 1
        scoped[0].provisionTypeCode == 'scvmm'
        scoped[0].containerTypeCode == null
        scoped[0].backupTypeProvider instanceof ScvmmBackupTypeProvider
        scoped[0].backupTypeProvider.code == 'scvmmSnapshot'
    }

    def "the registered type provider shares the provider's plugin and context"() {
        when:
        def provider = new ScvmmBackupProvider(plugin, context)
        ScvmmBackupTypeProvider typeProvider = provider.backupProviders.first() as ScvmmBackupTypeProvider

        then:
        typeProvider.plugin.is(plugin)
        typeProvider.morpheusContext.is(context)
    }

    def "inherits the built-in Morpheus backup provider identity and capabilities"() {
        when:
        def provider = new ScvmmBackupProvider(plugin, context)

        then:
        provider.code == 'morpheus'
        provider.name == 'Morpheus'
        provider.enabled
        provider.hasBackups
        !provider.creatable
        provider.backupJobProvider == null
        provider.morpheus.is(context)
    }

    def "refresh and deleteBackupProvider are left to the appliance and return null"() {
        // plan expected ServiceResponse.success(); MorpheusBackupProvider returns null for both
        given:
        def provider = new ScvmmBackupProvider(plugin, context)

        expect:
        provider.refresh(new BackupProviderModel()) == null
        provider.deleteBackupProvider(new BackupProviderModel(), [:]) == null
    }
}
