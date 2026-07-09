// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.logging

import org.slf4j.spi.LocationAwareLogger
import spock.lang.Specification
import spock.lang.Subject

class PrefixedLocationAwareLogWrapperSpec extends Specification {
    private static final String FQCN = PrefixedLocationAwareLogWrapper.name

    private LocationAwareLogger logger

    @Subject
    private PrefixedLocationAwareLogWrapper wrapper

    void setup() {
        this.logger = Mock(LocationAwareLogger)
        this.wrapper = new PrefixedLocationAwareLogWrapper(logger)
    }

    def "info logs with prefix when info is enabled"() {
        given:
        logger.isInfoEnabled() >> true

        when:
        wrapper.info("message {}", "arg")

        then:
        1 * logger.log(
                null,
                FQCN,
                LocationAwareLogger.INFO_INT,
                LogConstants.MESSAGE_PREFIX + "message {}",
                ["arg"] as Object[],
                null
        )
    }

    def "info does not log when info is disabled"() {
        given:
        logger.isInfoEnabled() >> false

        when:
        wrapper.info("message {}", "arg")

        then:
        0 * logger.log(*_)
    }

    def "warn logs with prefix when warn is enabled"() {
        given:
        logger.isWarnEnabled() >> true

        when:
        wrapper.warn("warning {}", "arg")

        then:
        1 * logger.log(
                null,
                FQCN,
                LocationAwareLogger.WARN_INT,
                LogConstants.MESSAGE_PREFIX + "warning {}",
                ["arg"] as Object[],
                null
        )
    }

    def "warn does not log when warn is disabled"() {
        given:
        logger.isWarnEnabled() >> false

        when:
        wrapper.warn("warning {}", "arg")

        then:
        0 * logger.log(*_)
    }

    def "error logs with prefix when error is enabled"() {
        given:
        logger.isErrorEnabled() >> true

        when:
        wrapper.error("error {}", "arg")

        then:
        1 * logger.log(
                null,
                FQCN,
                LocationAwareLogger.ERROR_INT,
                LogConstants.MESSAGE_PREFIX + "error {}",
                ["arg"] as Object[],
                null
        )
    }

    def "error does not log when error is disabled"() {
        given:
        logger.isErrorEnabled() >> false

        when:
        wrapper.error("error {}", "arg")

        then:
        0 * logger.log(*_)
    }

    def "debug logs with prefix when debug is enabled"() {
        given:
        logger.isDebugEnabled() >> true

        when:
        wrapper.debug("debug {}", "arg")

        then:
        1 * logger.log(
                null,
                FQCN,
                LocationAwareLogger.DEBUG_INT,
                LogConstants.MESSAGE_PREFIX + "debug {}",
                ["arg"] as Object[],
                null
        )
    }

    def "debug does not log when debug is disabled"() {
        given:
        logger.isDebugEnabled() >> false

        when:
        wrapper.debug("debug {}", "arg")

        then:
        0 * logger.log(*_)
    }

    def "isDebugEnabled delegates to underlying logger"() {
        given:
        logger.isDebugEnabled() >> enabled

        expect:
        wrapper.isDebugEnabled() == enabled

        where:
        enabled << [true, false]
    }

    def "info passes multiple args to underlying logger"() {
        given:
        logger.isInfoEnabled() >> true

        when:
        wrapper.info("a {} b {} c {}", "x", "y", "z")

        then:
        1 * logger.log(
                null,
                FQCN,
                LocationAwareLogger.INFO_INT,
                LogConstants.MESSAGE_PREFIX + "a {} b {} c {}",
                ["x", "y", "z"] as Object[],
                null
        )
    }

    def "info extracts trailing Throwable into throwable parameter"() {
        given:
        logger.isInfoEnabled() >> true
        def cause = new RuntimeException("boom")

        when:
        wrapper.info("failed", cause)

        then:
        1 * logger.log(
                null,
                FQCN,
                LocationAwareLogger.INFO_INT,
                LogConstants.MESSAGE_PREFIX + "failed",
                [] as Object[],
                cause
        )
    }

    def "info strips trailing Throwable from args while keeping preceding args"() {
        given:
        logger.isInfoEnabled() >> true
        def cause = new RuntimeException("boom")

        when:
        wrapper.info("failed {} {}", "a", "b", cause)

        then:
        1 * logger.log(
                null,
                FQCN,
                LocationAwareLogger.INFO_INT,
                LogConstants.MESSAGE_PREFIX + "failed {} {}",
                ["a", "b"] as Object[],
                cause
        )
    }

    def "warn extracts trailing Throwable into throwable parameter"() {
        given:
        logger.isWarnEnabled() >> true
        def cause = new RuntimeException("boom")

        when:
        wrapper.warn("failed", cause)

        then:
        1 * logger.log(
                null,
                FQCN,
                LocationAwareLogger.WARN_INT,
                LogConstants.MESSAGE_PREFIX + "failed",
                [] as Object[],
                cause
        )
    }

    def "error extracts trailing Throwable into throwable parameter"() {
        given:
        logger.isErrorEnabled() >> true
        def cause = new RuntimeException("boom")

        when:
        wrapper.error("failed", cause)

        then:
        1 * logger.log(
                null,
                FQCN,
                LocationAwareLogger.ERROR_INT,
                LogConstants.MESSAGE_PREFIX + "failed",
                [] as Object[],
                cause
        )
    }

    def "debug extracts trailing Throwable into throwable parameter"() {
        given:
        logger.isDebugEnabled() >> true
        def cause = new RuntimeException("boom")

        when:
        wrapper.debug("failed", cause)

        then:
        1 * logger.log(
                null,
                FQCN,
                LocationAwareLogger.DEBUG_INT,
                LogConstants.MESSAGE_PREFIX + "failed",
                [] as Object[],
                cause
        )
    }
}
