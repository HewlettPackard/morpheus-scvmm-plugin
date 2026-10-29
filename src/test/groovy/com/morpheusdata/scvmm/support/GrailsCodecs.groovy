// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.support

/**
 * Production code calls Grails codec methods ({@code encodeAsBase64}) that the Morpheus runtime adds to
 * {@code String}/{@code byte[]}; they do not exist under plain Groovy. Call {@link #install()} in
 * {@code setup()} and {@link #uninstall()} in {@code cleanup()}.
 */
class GrailsCodecs {

    static void install() {
        String.metaClass.encodeAsBase64 = { -> ((String) delegate).bytes.encodeBase64().toString() }
        byte[].metaClass.encodeAsBase64 = { -> ((byte[]) delegate).encodeBase64().toString() }
    }

    static void uninstall() {
        GroovySystem.metaClassRegistry.removeMetaClass(String)
        GroovySystem.metaClassRegistry.removeMetaClass(byte[])
    }

    static String base64(String value) {
        value.bytes.encodeBase64().toString()
    }
}
