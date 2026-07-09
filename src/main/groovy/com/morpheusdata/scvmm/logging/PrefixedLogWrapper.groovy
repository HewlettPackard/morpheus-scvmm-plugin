// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.logging

import groovy.transform.CompileStatic
import org.slf4j.Logger

@CompileStatic
class PrefixedLogWrapper implements LogInterface {
    private final Logger logger

    PrefixedLogWrapper(Logger logger) {
        this.logger = logger
    }

    @Override
    void info(String format, Object... args) {
        if (logger.isInfoEnabled()) {
            logger.info(LogConstants.MESSAGE_PREFIX + format, args)
        }
    }

    @Override
    void warn(String format, Object... args) {
        if (logger.isWarnEnabled()) {
            logger.warn(LogConstants.MESSAGE_PREFIX + format, args)
        }
    }

    @Override
    void error(String format, Object... args) {
        if (logger.isErrorEnabled()) {
            logger.error(LogConstants.MESSAGE_PREFIX + format, args)
        }
    }

    @Override
    void debug(String format, Object... args) {
        if (logger.isDebugEnabled()) {
            logger.debug(LogConstants.MESSAGE_PREFIX + format, args)
        }
    }

    @Override
    boolean isDebugEnabled() {
        return logger.isDebugEnabled()
    }
}
