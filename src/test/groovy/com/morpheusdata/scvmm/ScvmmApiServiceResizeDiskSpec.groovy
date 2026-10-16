// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.ComputeUtility
import spock.lang.Specification
import spock.lang.Subject

/**
 * MORPH-946: resizeDisk must skip no-op resizes, refuse differencing disks / VMs with
 * checkpoints with a clear message, and only wait on an SCVMM job when a real expand was issued.
 */
class ScvmmApiServiceResizeDiskSpec extends Specification {

    static final String VM_ID = 'vm-1111-2222'
    static final String DISK_ID = 'vhd-aaaa-bbbb'
    static final Long FORTY_GB = 40L * ComputeUtility.ONE_GIGABYTE

    MorpheusContext context = Mock()
    Map opts = [externalId: VM_ID, sshHost: 'scvmm.local', sshUsername: 'u', sshPassword: 'p']

    @Subject
    ScvmmApiService service

    String capturedCommand

    def setup() {
        service = Spy(ScvmmApiService, constructorArgs: [context])
        service.generateCommandString(_ as String) >> { String cmd -> cmd }
    }

    private void stubExecute(List data) {
        service.wrapExecuteCommand(_ as String, _ as Map) >> { String cmd, Map o ->
            capturedCommand = cmd
            return [success: true, data: data]
        }
    }

    def "generated script guards against differencing disks, checkpoints and no-op resizes"() {
        given:
        stubExecute([[success: true, jobId: 'job-1']])
        service.waitForJobToComplete(_, _) >> [success: true]

        when:
        service.resizeDisk(opts, DISK_ID, FORTY_GB)

        then:
        capturedCommand.contains('$newSize = 40')
        capturedCommand.contains("\$diskId = \"${DISK_ID}\"")
        capturedCommand.contains('Get-SCVMCheckpoint -VM $VM')
        capturedCommand.contains('$newSize -le $currentSizeGB')
        capturedCommand.contains('$vhd.ParentDisk')
        capturedCommand.contains('Expand-SCVirtualDiskDrive')
    }

    def "waits on the SCVMM job when an expand was issued"() {
        given:
        stubExecute([[success: true, skipped: false, jobId: 'job-1']])

        when:
        def rtn = service.resizeDisk(opts, DISK_ID, FORTY_GB)

        then:
        1 * service.waitForJobToComplete(opts, 'job-1') >> [success: true]
        rtn.success
    }

    def "returns success without waiting on a job when the resize is skipped as a no-op"() {
        given:
        stubExecute([[success: true, skipped: true, errOut: 'Requested size 40GB is not larger than current size 40GB - resize not required']])

        when:
        def rtn = service.resizeDisk(opts, DISK_ID, FORTY_GB)

        then:
        0 * service.waitForJobToComplete(*_)
        rtn.success
        rtn.skipped
        rtn.msg.contains('resize not required')
    }

    def "propagates the SCVMM error message when the disk cannot be resized"() {
        given:
        stubExecute([[success: false, skipped: false, errOut: errOut]])

        when:
        def rtn = service.resizeDisk(opts, DISK_ID, FORTY_GB)

        then:
        0 * service.waitForJobToComplete(*_)
        !rtn.success
        rtn.errOut == errOut

        where:
        errOut << [
            'Cannot Resize a Differencing Disk (parent: base.vhdx)',
            'Cannot Resize a Disk on a VM with 2 Checkpoint(s)'
        ]
    }

    def "returns a failure when SCVMM returns no usable payload"() {
        given:
        stubExecute([])

        when:
        def rtn = service.resizeDisk(opts, DISK_ID, FORTY_GB)

        then:
        !rtn.success
        rtn.errOut.contains('did not receive expected response')
    }
}
