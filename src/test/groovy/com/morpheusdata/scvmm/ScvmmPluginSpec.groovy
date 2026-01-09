package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.providers.PluginProvider
import com.morpheusdata.core.synchronous.MorpheusSynchronousSeedService
import spock.lang.Specification
import spock.lang.Subject

class ScvmmPluginSpec extends Specification {
    @Subject
    private ScvmmPlugin plugin

    void setup() {
        this.plugin = new ScvmmPlugin()
    }

    def "on construction plugin code should be initialized"() {
        expect:
        this.plugin.code == 'morpheus-scvmm-plugin'
        this.plugin.name == null
        this.plugin.description == null
        this.plugin.author == null
    }

    def "on initialize plugin information should be set"() {
        given:
        final MorpheusContext morpheusContext = Mock()
        final ScvmmPlugin testPlugin = new ScvmmPlugin()
        testPlugin.morpheus = morpheusContext

        when:
        testPlugin.initialize()

        then:
        testPlugin.code == 'morpheus-scvmm-plugin'
        testPlugin.name == 'SCVMM'

        testPlugin.pluginProviders.size() == 5

    }

    def "onDestroy should reinstall seed data"() {
        given:
        final plugin = new ScvmmPlugin()
        final context = Mock(MorpheusContext)
        final services = Mock(MorpheusServices)
        final seedService = Mock(MorpheusSynchronousSeedService)

        plugin.morpheus = context

        when:
        plugin.onDestroy()

        then:
        1 * context.services >> services
        1 * services.seed >> seedService
        1 * seedService.reinstallSeedData([
                "application.ZoneTypesSCVMMSeed",
                "application.ProvisionTypeScvmmSeed",
                "application.ScvmmSeed",
                "application.ComputeServerTypeScvmmSeed",
                "application.ScvmmComputeTypeSeed"
        ])
    }
}