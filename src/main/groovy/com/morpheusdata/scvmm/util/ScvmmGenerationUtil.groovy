package com.morpheusdata.scvmm.util

import com.morpheusdata.model.ComputeServer

class ScvmmGenerationUtil {

    static String toGenerationConfig(Object generation) {
        if (generation == null) {
            return null
        }
        def gen = generation.toString()
        if (gen == '1' || gen.equalsIgnoreCase('generation1')) {
            return 'generation1'
        }
        if (gen == '2' || gen.equalsIgnoreCase('generation2')) {
            return 'generation2'
        }
        return null
    }

    static boolean isGeneration2(String generationConfig) {
        return generationConfig == 'generation2'
    }

    static boolean hotResizeFromCloudItem(Map cloudItem) {
        return isGeneration2(toGenerationConfig(cloudItem?.Generation))
    }

    static boolean supportsHotDiskResize(ComputeServer server) {
        if (!server) {
            return false
        }
        if (server.hotResize == true) {
            return true
        }
        return isGeneration2(server.getConfigProperty('generation'))
    }

    static void applyGenerationFromCloudItem(ComputeServer server, Map cloudItem) {
        def generation = toGenerationConfig(cloudItem?.Generation)
        if (generation) {
            server.setConfigProperty('generation', generation)
            server.hotResize = isGeneration2(generation)
        }
    }

    static void applyGenerationFromScvmmGeneration(ComputeServer server, String scvmmGeneration) {
        if (!scvmmGeneration) {
            return
        }
        server.setConfigProperty('generation', scvmmGeneration)
        server.hotResize = isGeneration2(scvmmGeneration)
    }
}
