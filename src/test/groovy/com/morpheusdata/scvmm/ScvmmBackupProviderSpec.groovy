package com.morpheusdata.scvmm

import spock.lang.Specification
import com.morpheusdata.core.MorpheusContext

class ScvmmBackupProviderSpec extends Specification {

    def "constructor should initialize and register backup type provider"() {
        given:
        def plugin = Mock(ScvmmPlugin)
        def morpheusContext = Mock(MorpheusContext)

        when:
        def provider = new ScvmmBackupProvider(plugin, morpheusContext)

        then:
        1 * plugin.registerProvider(_ as ScvmmBackupTypeProvider)
        provider instanceof ScvmmBackupProvider
    }
}