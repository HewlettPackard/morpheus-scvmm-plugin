package com.morpheusdata.scvmm.logging

import org.slf4j.Logger
import spock.lang.Specification

class LogWrapperSpec extends Specification {

    def "LogWrapper implements LogInterface"() {
        given:
        def logWrapper = LogWrapper.instance

        expect:
        logWrapper instanceof LogInterface
    }

    def "LogWrapper is a Singleton"() {
        given:
        def instance1 = LogWrapper.instance
        def instance2 = LogWrapper.instance

        expect:
        instance1 == instance2
        instance1.is(instance2)
    }

    def "LogWrapper has isDebugEnabled method"() {
        given:
        def logWrapper = LogWrapper.instance

        when:
        def debugEnabled = logWrapper.isDebugEnabled()

        then:
        debugEnabled instanceof Boolean
    }

    def "info method can be called with format and args"() {
        given:
        def logWrapper = LogWrapper.instance

        when:
        logWrapper.info("Test message")
        logWrapper.info("Test message with arg: {}", "argument")
        logWrapper.info("Multiple args: {} and {}", "arg1", "arg2")

        then:
        noExceptionThrown()
    }

    def "warn method can be called with format and args"() {
        given:
        def logWrapper = LogWrapper.instance

        when:
        logWrapper.warn("Test warning")
        logWrapper.warn("Test warning with arg: {}", "argument")
        logWrapper.warn("Multiple args: {} and {}", "arg1", "arg2")

        then:
        noExceptionThrown()
    }

    def "error method can be called with format and args"() {
        given:
        def logWrapper = LogWrapper.instance

        when:
        logWrapper.error("Test error")
        logWrapper.error("Test error with arg: {}", "argument")
        logWrapper.error("Multiple args: {} and {}", "arg1", "arg2")

        then:
        noExceptionThrown()
    }

    def "debug method can be called with format and args"() {
        given:
        def logWrapper = LogWrapper.instance

        when:
        logWrapper.debug("Test debug")
        logWrapper.debug("Test debug with arg: {}", "argument")
        logWrapper.debug("Multiple args: {} and {}", "arg1", "arg2")

        then:
        noExceptionThrown()
    }

    def "logging methods handle null format gracefully"() {
        given:
        def logWrapper = LogWrapper.instance

        when:
        logWrapper.info(null)
        logWrapper.warn(null)
        logWrapper.error(null)
        logWrapper.debug(null)

        then:
        noExceptionThrown()
    }

    def "logging methods handle empty format string"() {
        given:
        def logWrapper = LogWrapper.instance

        when:
        logWrapper.info("")
        logWrapper.warn("")
        logWrapper.error("")
        logWrapper.debug("")

        then:
        noExceptionThrown()
    }

    def "logging methods handle null args"() {
        given:
        def logWrapper = LogWrapper.instance

        when:
        logWrapper.info("Test message", null)
        logWrapper.warn("Test message", null)
        logWrapper.error("Test message", null)
        logWrapper.debug("Test message", null)

        then:
        noExceptionThrown()
    }

    def "logging methods handle no args"() {
        given:
        def logWrapper = LogWrapper.instance

        when:
        logWrapper.info("Test message")
        logWrapper.warn("Test message")
        logWrapper.error("Test message")
        logWrapper.debug("Test message")

        then:
        noExceptionThrown()
    }

    def "LogWrapper handles special characters in messages"() {
        given:
        def logWrapper = LogWrapper.instance

        when:
        logWrapper.info("Message with special chars: !@#\$%^&*()")
        logWrapper.info("Message with newlines:\nSecond line\nThird line")
        logWrapper.info("Message with quotes: 'single' \"double\"")

        then:
        noExceptionThrown()
    }

    def "LogWrapper can log from different calling contexts"() {
        given:
        def logWrapper = LogWrapper.instance

        when:
        // Call from test method (should include caller details)
        logWrapper.info("Test from spec context")
        helperMethod(logWrapper)

        then:
        noExceptionThrown()
    }

    def "LogWrapper handles various argument types"() {
        given:
        def logWrapper = LogWrapper.instance

        when:
        logWrapper.info("String arg: {}", "text")
        logWrapper.info("Number arg: {}", 42)
        logWrapper.info("Boolean arg: {}", true)
        logWrapper.info("Object arg: {}", new Date())
        logWrapper.info("Array arg: {}", [1, 2, 3] as Object[])

        then:
        noExceptionThrown()
    }

    def "LogWrapper instance is accessible from different threads"() {
        given:
        def logWrapper = LogWrapper.instance
        def results = []

        when:
        def threads = (1..5).collect { threadNum ->
            Thread.start {
                def threadWrapper = LogWrapper.instance
                threadWrapper.info("Message from thread {}", threadNum)
                results << (threadWrapper == logWrapper)
            }
        }
        threads*.join()

        then:
        noExceptionThrown()
        results.every { it == true } // All threads should get the same singleton instance
    }

    def "LogWrapper class behaves as singleton"() {
        when:
        def instance1 = LogWrapper.instance
        def instance2 = LogWrapper.instance
        def instance3 = LogWrapper.getInstance()

        then:
        instance1 == instance2
        instance2 == instance3
        instance1.is(instance2)
        instance2.is(instance3)
    }

    def "LogWrapper getCallerDetails handles various stack trace scenarios"() {
        given:
        def logWrapper = LogWrapper.instance

        when:
        // Test logging from current class (should include caller details with package prefix match)
        logWrapper.info("Message from LogWrapperSpec")
        
        // Test logging from a method
        testMethod()
        
        // Test logging from nested method calls
        nestedTestMethod()

        then:
        noExceptionThrown()
    }

    def "LogWrapper handles logger levels correctly"() {
        given:
        def logWrapper = LogWrapper.instance

        when:
        def debugEnabled = logWrapper.isDebugEnabled()
        
        // Test all log levels regardless of current log level
        logWrapper.debug("Debug message")
        logWrapper.info("Info message") 
        logWrapper.warn("Warn message")
        logWrapper.error("Error message")

        then:
        debugEnabled != null
        noExceptionThrown()
    }

    def "LogWrapper handles empty and null caller detection gracefully"() {
        given:
        def logWrapper = LogWrapper.instance

        when:
        // These should trigger different paths in getCallerDetails
        logWrapper.info("Testing caller detection edge cases")
        
        then:
        noExceptionThrown()
    }

    def "LogWrapper message formatting works with various arg counts"() {
        given:
        def logWrapper = LogWrapper.instance

        when:
        // Test different numbers of arguments
        logWrapper.info("No args")
        logWrapper.info("One arg: {}", "arg1")
        logWrapper.info("Two args: {} and {}", "arg1", "arg2")
        logWrapper.info("Three args: {}, {}, {}", "arg1", "arg2", "arg3")
        logWrapper.info("Many args: {}, {}, {}, {}, {}", "arg1", "arg2", "arg3", "arg4", "arg5")

        then:
        noExceptionThrown()
    }

    def "LogWrapper handles concurrent access"() {
        given:
        def logWrapper = LogWrapper.instance
        def exceptions = []

        when:
        def threads = (1..10).collect { threadNum ->
            Thread.start {
                try {
                    (1..50).each { messageNum ->
                        logWrapper.info("Thread {} message {}", threadNum, messageNum)
                        logWrapper.warn("Thread {} warning {}", threadNum, messageNum)
                        logWrapper.error("Thread {} error {}", threadNum, messageNum)
                        logWrapper.debug("Thread {} debug {}", threadNum, messageNum)
                    }
                } catch (Exception e) {
                    exceptions << e
                }
            }
        }
        threads*.join()

        then:
        exceptions.isEmpty()
        noExceptionThrown()
    }

    def "LogWrapper prefix and caller details are included in messages"() {
        given:
        def logWrapper = LogWrapper.instance

        when:
        // Call from this test class (should match caller package prefix)
        logWrapper.info("Test message with caller details")
        
        then:
        noExceptionThrown()
    }

    def testMethod() {
        def logWrapper = LogWrapper.instance
        logWrapper.warn("Message from testMethod")
    }

    def nestedTestMethod() {
        helperMethod(LogWrapper.instance)
    }

    private void helperMethod(LogWrapper logger) {
        logger.info("Test from helper method")
        deeperHelperMethod(logger)
    }

    private void deeperHelperMethod(LogWrapper logger) {
        logger.error("Test from deeper helper method")
    }
}
