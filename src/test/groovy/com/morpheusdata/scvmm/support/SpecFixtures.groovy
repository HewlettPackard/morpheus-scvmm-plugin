// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.support

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.scvmm.ScvmmApiService
import com.morpheusdata.scvmm.logging.LogInterface
import com.morpheusdata.scvmm.logging.PrefixedLoggerFactory

/**
 * Shared spec fixtures for Spy targets and Grails codec shims used by the provisioning and VM sync specs.
 */
class SpecFixtures {

    /**
     * Spy target for {@link ScvmmApiService} methods whose closures reference the private {@code log} field.
     * On a plain {@code Spy(ScvmmApiService)} (a generated subclass) Groovy resolves {@code log} inside a closure
     * as a dynamic property and fails with {@code MissingMethodException: getLog()}; this subclass supplies that
     * getter, so the production closures run unchanged:
     * <pre>def svc = Spy(SpecFixtures.LoggingApiService, constructorArgs: [context])</pre>
     */
    static class LoggingApiService extends ScvmmApiService {
        private static final LogInterface TEST_LOG = PrefixedLoggerFactory.getLogger(ScvmmApiService)

        LoggingApiService(MorpheusContext context) {
            super(context)
        }

        LogInterface getLog() {
            TEST_LOG
        }
    }

    /**
     * Sync code logs {@code list.encodeAsJSON()} (a Grails converter absent under plain Groovy) eagerly inside a
     * debug GString. Install in {@code setup()}, uninstall in {@code cleanup()}. {@link VmmPayloads.JsonMap} already
     * covers single maps; this covers payload lists.
     */
    static void installJsonCodec() {
        ArrayList.metaClass.encodeAsJSON = { -> new groovy.json.JsonBuilder(delegate) }
    }

    static void uninstallJsonCodec() {
        GroovySystem.metaClassRegistry.removeMetaClass(ArrayList)
    }
}
