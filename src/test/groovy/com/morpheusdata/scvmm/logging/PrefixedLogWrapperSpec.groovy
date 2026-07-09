// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.logging

import org.slf4j.Logger
import spock.lang.Specification
import spock.lang.Subject

class PrefixedLogWrapperSpec extends Specification {
    private Logger logger

    @Subject
    private PrefixedLogWrapper wrapper

    void setup() {
        this.logger = Mock(Logger)
        this.wrapper = new PrefixedLogWrapper(logger)
    }

    def "info logs with prefix when info is enabled"() {
        given:
        logger.isInfoEnabled() >> true

        when:
        wrapper.info("message {}", "arg")

        then:
        1 * logger.info(LogConstants.MESSAGE_PREFIX + "message {}", ["arg"] as Object[])
    }

    def "info does not log when info is disabled"() {
        given:
        logger.isInfoEnabled() >> false

        when:
        wrapper.info("message {}", "arg")

        then:
        0 * logger.info(*_)
    }

    def "warn logs with prefix when warn is enabled"() {
        given:
        logger.isWarnEnabled() >> true

        when:
        wrapper.warn("warning {}", "arg")

        then:
        1 * logger.warn(LogConstants.MESSAGE_PREFIX + "warning {}", ["arg"] as Object[])
    }

    def "warn does not log when warn is disabled"() {
        given:
        logger.isWarnEnabled() >> false

        when:
        wrapper.warn("warning {}", "arg")

        then:
        0 * logger.warn(*_)
    }

    def "error logs with prefix when error is enabled"() {
        given:
        logger.isErrorEnabled() >> true

        when:
        wrapper.error("error {}", "arg")

        then:
        1 * logger.error(LogConstants.MESSAGE_PREFIX + "error {}", ["arg"] as Object[])
    }

    def "error does not log when error is disabled"() {
        given:
        logger.isErrorEnabled() >> false

        when:
        wrapper.error("error {}", "arg")

        then:
        0 * logger.error(*_)
    }

    def "debug logs with prefix when debug is enabled"() {
        given:
        logger.isDebugEnabled() >> true

        when:
        wrapper.debug("debug {}", "arg")

        then:
        1 * logger.debug(LogConstants.MESSAGE_PREFIX + "debug {}", ["arg"] as Object[])
    }

    def "debug does not log when debug is disabled"() {
        given:
        logger.isDebugEnabled() >> false

        when:
        wrapper.debug("debug {}", "arg")

        then:
        0 * logger.debug(*_)
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
        1 * logger.info(LogConstants.MESSAGE_PREFIX + "a {} b {} c {}", ["x", "y", "z"] as Object[])
    }
}
