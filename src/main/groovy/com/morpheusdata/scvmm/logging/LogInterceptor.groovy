// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.logging

import com.morpheusdata.response.ServiceResponse
import groovy.transform.CompileStatic

import java.lang.reflect.Method

@CompileStatic
class LogInterceptor extends DelegatingMetaClass {
    private final LogInterface logger

    private LogInterceptor(final Class cls, LogInterface logger) {
        super(cls)
        initialize()
        this.logger = logger
    }

    Object invokeMethod(Object obj, String method, Object[] args) {
        TimeTracker tracker = new TimeTracker()
        String cls = obj.class.simpleName
        tracker.start(method)
        logger.debug("Entering: ${cls}.${method},args:${args.each { it -> it?.dump() }}")
        def val
        try {
            val = super.invokeMethod(obj, method, args)
        } catch (e) {
            // If the method is not expected to return a ServiceResponse, log the exception and rethrow it.
            if (!returnsServiceResponse(obj, method)) {
                tracker.end(method)
                logger.debug("Exiting: ${cls}.${method}, With Exception: ${e}, Time taken: " +
                        "${tracker.getElapsedTime(method)}s", e)
                throw e
            }

            // If the method is expected to return a ServiceResponse, log the exception and return an error response
            // instead of rethrowing the exception. The Morpheus code, in many cases, is not designed to receive
            // unhandled exceptions from providers, and doing so can lead to requests never completing from Morpheus'
            // perspective. It is better to return an error response, with the exception details, than to have the
            // request hang indefinitely.
//            val = ExceptionUtil.buildErrorResponse("${cls}.${method} failed: %s", e as Exception)
            logger.debug("Returning error ServiceResponse instead of throwing: ${e}", e)
            throw e
        }
        tracker.end(method)
        logger.debug("Exiting: ${cls}.${method}, With Return Value: ${val}, Time taken: " +
                "${tracker.getElapsedTime(method)}s")
        return val
    }

    static void injectIn(Class cls, LogInterface logger, boolean force = false) {
        if (force || logger.debugEnabled) {
            cls.metaClass = new LogInterceptor(cls, logger)
        }
    }

    /**
     * Checks if any declared method with the given name on the object's class returns ServiceResponse.
     */
    private static boolean returnsServiceResponse(Object obj, String methodName) {
        try {
            Method[] methods = obj.getClass().methods
            for (Method m : methods) {
                if (m.name == methodName && m.returnType.isAssignableFrom(ServiceResponse)) {
                    return true
                }
            }
        } catch (ignored) {
            // If reflection fails, fall back to throwing the exception
        }
        return false
    }
}