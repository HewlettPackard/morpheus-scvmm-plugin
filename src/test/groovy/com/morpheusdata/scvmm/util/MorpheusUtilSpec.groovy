// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.util

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.MorpheusSynchronousFileCopyService
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

import java.util.concurrent.CountDownLatch

class MorpheusUtilSpec extends Specification {
    MorpheusContext morpheusContext = Mock(MorpheusContext)
    MorpheusServices morpheusServices = Mock(MorpheusServices)
    MorpheusSynchronousFileCopyService morpheusFileCopy = Mock(MorpheusSynchronousFileCopyService)

    def "test copyToServer #description"() {
        given:
        ComputeServer server = new ComputeServer(name: 'mock-server', id: 123L)
        String fileName = 'mockFileName.bin'
        String filePath = '/some/path/mockFileName.bin'
        byte[] mockData = 'mock file content'.getBytes()
        InputStream sourceStream = new ByteArrayInputStream(mockData)
        Thread watchdogThread = null
        CountDownLatch watchdogStarted = null

        // Set initial sleep to a short duration to exercise log output
        MorpheusUtil.WATCHDOG_INITIAL_SLEEP_MS = 1

        // Install test hook to observe watchdog thread lifecycle
        MorpheusUtil._testWatchdogObserver = { Thread thread, CountDownLatch latch ->
            watchdogThread = thread
            watchdogStarted = latch
        }

        when:
        Exception exception = null
        ServiceResponse response = null
        try {
            response = MorpheusUtil.copyToServer(
                    morpheusContext,
                    server,
                    fileName,
                    filePath,
                    sourceStream,
                    mockData.size()
            )
        } catch (Exception e) {
            exception = e
        }

        then:
        1 * morpheusContext.services >> morpheusServices
        1 * morpheusServices.fileCopy >> morpheusFileCopy
        1 * morpheusFileCopy.copyToServer(
                server,
                fileName,
                filePath,
                sourceStream,
                mockData.size()
        ) >> { args ->
            // Sleep briefly to allow watchdog to log at least once
            Thread.sleep(10)

            // Mock behavior: throw if response is an exception, otherwise return the response
            if (mockResponse instanceof Exception) {
                throw mockResponse
            }
            return mockResponse
        }
        0 * _

        // Verify response/exception matches expected outcome
        if (expectedResponse instanceof Exception) {
            assert exception
            assert exception.class == expectedResponse.class
            assert exception.message == expectedResponse.message
            assert !response
        } else {
            assert !exception
            assert response
            assert response.success == expectedResponse.success
            assert response.msg == expectedResponse.msg
            assert response.error == expectedResponse.error
        }

        // Watchdog thread should have been created, started (latch counted down), and terminated after copyToServer
        assert watchdogThread
        assert watchdogStarted
        assert !watchdogThread.alive

        cleanup:
        MorpheusUtil._testWatchdogObserver = null

        where:
        [description, mockResponse, expectedResponse] << [
                [
                        'Exception during copy operation terminates watchdog cleanly',
                        new RuntimeException('Simulated copy failure'),
                        new RuntimeException('Simulated copy failure'),
                ],
                [
                        'Error response returns failure status and terminates watchdog',
                        ServiceResponse.error('mock error'),
                        ServiceResponse.error('mock error'),
                ],
                [
                        'Successful copy operation returns success and terminates watchdog',
                        ServiceResponse.success(),
                        ServiceResponse.success(),
                ],
        ]
    }
}
