// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Cloud
import com.morpheusdata.scvmm.error.ScvmmConnectionException
import spock.lang.Specification

class ScvmmCloudProviderSyncSpec extends Specification {

    MorpheusContext context = Mock()
    ScvmmCloudProvider provider = new ScvmmCloudProvider(new ScvmmPlugin(), context)
    Cloud cloud = new Cloud(name: 'scvmm-lab')

    def "runSyncWorkers keeps going after a data failure and records per-worker outcome"() {
        given:
        List<String> ran = []
        List<Map> workers = [
                [name: 'A', run: { ran << 'A'; [success: true] }],
                [name: 'B', run: { ran << 'B'; throw new IllegalStateException('bad data\nsecond line') }],
                [name: 'C', run: { ran << 'C'; [success: false, msg: 'SCVMM returned no logical networks'] }],
                [name: 'D', run: { ran << 'D'; null }],
        ]

        when:
        List<Map> results = provider.runSyncWorkers(cloud, workers)

        then:
        ran == ['A', 'B', 'C', 'D']
        results*.name == ['A', 'B', 'C', 'D']
        results*.success == [true, false, false, true]
        results[1].error == 'bad data'
        results[2].error == 'SCVMM returned no logical networks'
        results.every { it.durationMs != null && it.durationMs >= 0 }
        results.every { !it.connectionFailure }
    }

    def "runSyncWorkers stops after a connection failure and marks the remaining workers skipped"() {
        given:
        List<String> ran = []
        List<Map> workers = [
                [name: 'A', run: { ran << 'A'; [success: true] }],
                [name: 'B', run: { ran << 'B'; throw new ScvmmConnectionException('WinRM died', 'host', 5985, new java.net.ConnectException('Connection refused')) }],
                [name: 'C', run: { ran << 'C'; [success: true] }],
        ]

        when:
        List<Map> results = provider.runSyncWorkers(cloud, workers)

        then:
        ran == ['A', 'B']
        results[1].connectionFailure
        results[1].error.contains('WinRM')
        results[2].skipped
        !results[2].success
        results[2].error.contains('skipped')
    }

    def "runSyncWorker does not propagate any Throwable from the worker"() {
        when:
        Map result = provider.runSyncWorker(cloud, 'Boom', { throw new StackOverflowError('deep') })

        then:
        notThrown(Throwable)
        !result.success
        result.error == 'deep'
    }

    def "summarizeSyncFailures lists only real failures with their causes"() {
        given:
        List<Map> results = [
                [name: 'NetworkSync', success: true],
                [name: 'IpPoolsSync', success: false, error: 'Grant-SCIPAddress failed'],
                [name: 'HostSync', success: false, error: 'no hosts'],
                [name: 'VirtualMachineSync', success: false, skipped: true, error: 'skipped'],
        ]

        when:
        String summary = ScvmmCloudProvider.summarizeSyncFailures(results)

        then:
        summary.startsWith('Sync completed with 2 of 4 worker(s) failing')
        summary.contains('IpPoolsSync: Grant-SCIPAddress failed')
        summary.contains('HostSync: no hosts')
        !summary.contains('VirtualMachineSync')
    }

    def "truncateStatus keeps cloud status messages within the column limit"() {
        expect:
        ScvmmCloudProvider.truncateStatus('x' * 1000).length() == 250
        ScvmmCloudProvider.truncateStatus('short') == 'short'
    }
}
