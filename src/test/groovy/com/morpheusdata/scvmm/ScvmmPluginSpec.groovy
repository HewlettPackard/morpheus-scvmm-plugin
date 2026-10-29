// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.MorpheusSynchronousSeedService
import spock.lang.Specification
import spock.lang.Subject

class ScvmmPluginSpec extends Specification {

    static final List<String> SEEDS = [
            'application.ZoneTypesSCVMMSeed',
            'application.ProvisionTypeScvmmSeed',
            'application.ScvmmSeed',
            'application.ComputeServerTypeScvmmSeed',
            'application.ScvmmComputeTypeSeed',
            'application.ServicePlanScvmmSeed',
    ]

    MorpheusContext context = Mock()
    MorpheusServices services = Mock()
    MorpheusSynchronousSeedService seedService = Mock()

    @Subject
    ScvmmPlugin plugin = new ScvmmPlugin()

    def setup() {
        context.getServices() >> services
        services.getSeed() >> seedService
        plugin.morpheus = context
    }

    def "getCode returns the plugin code before initialize"() {
        expect:
        plugin.code == 'morpheus-scvmm-plugin'
    }

    def "initialize sets the plugin name and description"() {
        when:
        plugin.initialize()

        then:
        plugin.name == 'SCVMM'
        plugin.description == 'Plugin for SCVMM Integration'
    }

    def "initialize registers the cloud, provision, backup, backup type, option source and IPAM providers by code"() {
        when:
        plugin.initialize()

        then: 'ScvmmBackupProvider also registers ScvmmBackupTypeProvider, so six providers are registered, not five'
        plugin.providers*.class as Set == [ScvmmCloudProvider, ScvmmProvisionProvider, ScvmmBackupProvider,
                                           ScvmmBackupTypeProvider, ScvmmOptionSourceProvider, ScvmmIPAMProvider] as Set
        plugin.providers.size() == 6
        plugin.providers*.code.toSet().size() == 6
    }

    def "initialize registers each provider with its implementing class and wires plugin and context"() {
        when:
        plugin.initialize()

        then:
        plugin.getProviderByCode(code).class == type
        plugin.getProviderByCode(code).plugin.is(plugin)
        plugin.getProviderByCode(code).morpheus.is(context)

        where:
        code                  | type
        'scvmm'               | ScvmmCloudProvider
        'scvmm.provision'     | ScvmmProvisionProvider
        'scvmmSnapshot'       | ScvmmBackupTypeProvider
        'scvmm-option-source' | ScvmmOptionSourceProvider
        'scvmm-ipam'          | ScvmmIPAMProvider
    }

    def "initialize registers exactly one ScvmmBackupProvider"() {
        when:
        plugin.initialize()

        then:
        plugin.getProvidersByType(ScvmmBackupProvider).size() == 1
        plugin.getProvidersByType(ScvmmBackupProvider).first().plugin.is(plugin)
    }

    def "initialize is idempotent: a second call replaces providers rather than duplicating them"() {
        when:
        plugin.initialize()
        plugin.initialize()

        then:
        plugin.providers.size() == 6
    }

    def "onDestroy reinstalls the six SCVMM seeds once, in order, in a single synchronous call"() {
        when:
        plugin.onDestroy()

        then:
        1 * seedService.reinstallSeedData(SEEDS)
        0 * seedService._
    }

    def "onDestroy propagates a seed service failure (no per-seed isolation)"() {
        when:
        plugin.onDestroy()

        then:
        1 * seedService.reinstallSeedData(_) >> { throw new IllegalStateException('seed failed') }
        def e = thrown(IllegalStateException)
        e.message == 'seed failed'
    }

    def "onDestroy throws when the plugin has no Morpheus context"() {
        given:
        def orphan = new ScvmmPlugin()

        when:
        orphan.onDestroy()

        then:
        thrown(NullPointerException)
    }
}
