// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.logging

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.spi.LocationAwareLogger

@CompileStatic
class PrefixedLoggerFactory {
    @SuppressWarnings('Instanceof')
    static LogInterface getLogger(Class<?> clazz) {
        Logger underlyingLogger = LoggerFactory.getLogger(clazz)

        // If a location-aware logger (such as the native Logback), preserve file name and line number.
        if (underlyingLogger instanceof LocationAwareLogger) {
            return new PrefixedLocationAwareLogWrapper((LocationAwareLogger) underlyingLogger)
        }

        // Otherwise, fall back to standard SLF4J; caller location will be the wrapper.
        return new PrefixedLogWrapper(underlyingLogger)
    }
}
