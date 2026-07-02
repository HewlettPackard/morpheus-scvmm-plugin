// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.logging

import org.slf4j.LoggerFactory
import org.slf4j.spi.LocationAwareLogger
import spock.lang.Specification

class PrefixedLoggerFactorySpec extends Specification {
    def "getLogger returns a LogInterface"() {
        when:
        def wrapper = PrefixedLoggerFactory.getLogger(PrefixedLoggerFactorySpec)

        then:
        wrapper instanceof LogInterface
    }

    def "getLogger returns PrefixedLocationAwareLogWrapper when underlying logger is LocationAwareLogger"() {
        given: "Logback is on the classpath and returns a LocationAwareLogger"
        assert (LoggerFactory.getLogger(PrefixedLoggerFactorySpec) instanceof LocationAwareLogger)

        when:
        def wrapper = PrefixedLoggerFactory.getLogger(PrefixedLoggerFactorySpec)

        then:
        wrapper instanceof PrefixedLocationAwareLogWrapper
    }

    def "getLogger for different classes returns distinct LogInterface instances"() {
        when:
        def wrapper1 = PrefixedLoggerFactory.getLogger(PrefixedLoggerFactorySpec)
        def wrapper2 = PrefixedLoggerFactory.getLogger(String)

        then:
        !wrapper1.is(wrapper2)
    }

    def "returned wrapper delegates isDebugEnabled to the underlying logger"() {
        given:
        def underlyingLogger = LoggerFactory.getLogger(PrefixedLoggerFactorySpec)
        def wrapper = PrefixedLoggerFactory.getLogger(PrefixedLoggerFactorySpec)

        expect:
        wrapper.isDebugEnabled() == underlyingLogger.isDebugEnabled()
    }
}
