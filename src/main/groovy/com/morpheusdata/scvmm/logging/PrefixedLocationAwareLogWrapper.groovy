// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.logging

import groovy.transform.CompileStatic
import org.slf4j.spi.LocationAwareLogger

@CompileStatic
class PrefixedLocationAwareLogWrapper implements LogInterface {
    private final LocationAwareLogger logger

    PrefixedLocationAwareLogWrapper(LocationAwareLogger logger) {
        this.logger = logger
    }

    @Override
    void info(String format, Object... args) {
        if (logger.isInfoEnabled()) {
            logger.log(
                    null,
                    PrefixedLocationAwareLogWrapper.name,
                    LocationAwareLogger.INFO_INT,
                    LogConstants.MESSAGE_PREFIX + format,
                    stripThrowable(args),
                    extractThrowable(args)
            )
        }
    }

    @Override
    void warn(String format, Object... args) {
        if (logger.isWarnEnabled()) {
            logger.log(
                    null,
                    PrefixedLocationAwareLogWrapper.name,
                    LocationAwareLogger.WARN_INT,
                    LogConstants.MESSAGE_PREFIX + format,
                    stripThrowable(args),
                    extractThrowable(args)
            )
        }
    }

    @Override
    void error(String format, Object... args) {
        if (logger.isErrorEnabled()) {
            logger.log(
                    null,
                    PrefixedLocationAwareLogWrapper.name,
                    LocationAwareLogger.ERROR_INT,
                    LogConstants.MESSAGE_PREFIX + format,
                    stripThrowable(args),
                    extractThrowable(args)
            )
        }
    }

    @Override
    void debug(String format, Object... args) {
        if (logger.isDebugEnabled()) {
            logger.log(
                    null,
                    PrefixedLocationAwareLogWrapper.name,
                    LocationAwareLogger.DEBUG_INT,
                    LogConstants.MESSAGE_PREFIX + format,
                    stripThrowable(args),
                    extractThrowable(args)
            )
        }
    }

    @SuppressWarnings('Instanceof')
    private static Throwable extractThrowable(Object[] args) {
        if (args != null && args.length > 0 && args.last() instanceof Throwable) {
            return (Throwable) args.last()
        }
        return null
    }

    @SuppressWarnings('Instanceof')
    private static Object[] stripThrowable(Object[] args) {
        if (args != null && args.length > 0 && args.last() instanceof Throwable) {
            return Arrays.copyOf(args, args.length - 1)
        }
        return args
    }

    @Override
    boolean isDebugEnabled() {
        return logger.isDebugEnabled()
    }
}
