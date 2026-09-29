// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.error

import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

class ScvmmErrorTranslatorSpec extends Specification {

    def "known error table maps '#description' to code #code"() {
        when:
        def hit = ScvmmKnownErrors.match((String) text)

        then:
        hit?.code == code
        hit?.category == category

        where:
        description                  | text                                                                                   | code                        | category
        'WinRM 401'                  | 'The remote server returned an error: (401) Unauthorized.'                             | 'winrm.auth'                | ScvmmKnownErrors.Category.CONNECTION
        'access denied'              | 'Connecting to remote server failed: Access is denied.'                                | 'winrm.auth'                | ScvmmKnownErrors.Category.CONNECTION
        'listener unreachable'       | 'WinRM cannot complete the operation. Verify that the specified computer name is valid' | 'winrm.listener'            | ScvmmKnownErrors.Category.CONNECTION
        'connection refused'         | 'java.net.ConnectException: Connection refused'                                        | 'winrm.listener'            | ScvmmKnownErrors.Category.CONNECTION
        'socket timeout'             | 'java.net.SocketTimeoutException: Read timed out'                                      | 'winrm.timeout'             | ScvmmKnownErrors.Category.CONNECTION
        'VMM module missing'         | "The term 'Get-SCCloud' is not recognized as the name of a cmdlet"                    | 'host.vmm-module-missing'   | ScvmmKnownErrors.Category.HOST
        'generation 2 mismatch'      | 'The selected VHD is not compatible with a template which includes generation 2 VMs'   | 'vmm.generation2-mismatch'  | ScvmmKnownErrors.Category.VMM
        'generation 1 mismatch'      | 'The selected VHD is not compatible with a template which includes generation 1 VMs'   | 'vmm.generation1-mismatch'  | ScvmmKnownErrors.Category.VMM
        'placement'                  | 'Unable to find a suitable host for the virtual machine'                               | 'vmm.placement'             | ScvmmKnownErrors.Category.VMM
        'library share'              | 'No library share found'                                                               | 'vmm.library-share'         | ScvmmKnownErrors.Category.VMM
        'VM not found marker'        | 'VM_NOT_FOUND'                                                                         | 'vmm.vm-not-found'          | ScvmmKnownErrors.Category.VMM
        'ip already released'        | 'Unable to find the specified allocated IP address'                                    | 'vmm.ip-already-released'   | ScvmmKnownErrors.Category.VMM
        'differencing disk'          | 'Cannot Resize a Differencing Disk or a Disk with Checkpoints'                         | 'vmm.differencing-disk'     | ScvmmKnownErrors.Category.VMM
        'unknown text'               | 'Something entirely unexpected happened'                                               | null                        | null
        'null text'                  | null                                                                                   | null                        | null
    }

    def "generation mismatch messages match the historical inline strings"() {
        expect:
        ScvmmKnownErrors.match('... which includes generation 2 ...').userMessage == ScvmmKnownErrors.GENERATION_2_MISMATCH_MESSAGE
        ScvmmKnownErrors.match('... which includes generation 1 ...').userMessage == ScvmmKnownErrors.GENERATION_1_MISMATCH_MESSAGE
    }

    def "match(Throwable) inspects command output and the cause chain"() {
        given:
        def cmd = ScvmmCommandException.fromOutput('Get-SCCloud', 'Line 1\nUnable to find a suitable host', null)
        def wrapped = new RuntimeException('outer', new IllegalStateException('inner', new java.net.ConnectException('Connection refused')))

        expect:
        ScvmmKnownErrors.match(cmd).code == 'vmm.placement'
        ScvmmKnownErrors.match(wrapped).code == 'winrm.listener'
        ScvmmKnownErrors.match((Throwable) null) == null
    }

    def "userMessage prefers known error, then userMessage, then command error, then root cause"() {
        expect:
        ScvmmErrorTranslator.userMessage(new ScvmmConnectionException('x', 'h', 5985, new java.net.ConnectException('Connection refused'))).contains('WinRM service')
        ScvmmErrorTranslator.userMessage(new ScvmmException('tech', 'friendly', null)) == 'friendly'
        ScvmmErrorTranslator.userMessage(ScvmmCommandException.fromOutput('Get-Foo', 'Totally novel VMM error\nstack', '1')) == 'SCVMM reported an error: Totally novel VMM error'
        ScvmmErrorTranslator.userMessage(new RuntimeException('outer', new IllegalStateException('the real reason'))) == 'the real reason'
        ScvmmErrorTranslator.userMessage(new RuntimeException((String) null)).startsWith(ScvmmErrorTranslator.GENERIC_MESSAGE)
        ScvmmErrorTranslator.userMessage(null) == ScvmmErrorTranslator.GENERIC_MESSAGE
    }

    def "userMessage does not re-map our own timeout exception to a connection timeout"() {
        given:
        def timeout = new ScvmmTimeoutException('VM x to be Running', 10, 50_000L, 'PowerOff')

        expect:
        ScvmmErrorTranslator.userMessage(timeout) == timeout.message
        !ScvmmErrorTranslator.isConnectionFailure(timeout)
    }

    def "userMessage redacts secrets in raw exception messages"() {
        expect:
        !ScvmmErrorTranslator.userMessage(new RuntimeException('login failed for password=Sup3rS3cret!')).contains('Sup3rS3cret!')
    }

    def "toServiceResponse produces a failed response with message, field error, code and structured data"() {
        when:
        ServiceResponse rtn = ScvmmErrorTranslator.toServiceResponse(new ScvmmConnectionException('WinRM 401 Unauthorized', 'scvmm-host', 5985), 'Validating cloud')

        then:
        !rtn.success
        rtn.msg.startsWith('Validating cloud: ')
        rtn.msg.contains('Authentication')
        rtn.errors.password?.contains('Authentication')
        rtn.errorCode == 'winrm.auth'
        rtn.data.errorType == 'ScvmmConnectionException'
        rtn.data.host == 'scvmm-host'
        rtn.data.category == 'CONNECTION'
        rtn.data.context == 'Validating cloud'
    }

    def "toServiceResponse falls back to the exception type for unknown ScvmmExceptions and generic throwables"() {
        expect:
        ScvmmErrorTranslator.toServiceResponse(new ScvmmException('x')).errorCode == 'ScvmmException'
        with(ScvmmErrorTranslator.toServiceResponse(new IllegalArgumentException('bad arg'))) {
            !success
            msg == 'bad arg'
            errorCode == null
            data.errorType == 'IllegalArgumentException'
        }
    }

    def "toResultMap populates the legacy map without dropping existing keys"() {
        given:
        Map rtn = [success: true, clouds: ['a']]

        when:
        ScvmmErrorTranslator.toResultMap(ScvmmCommandException.fromOutput('Get-SCCloud', 'No library share found', '1'), rtn, 'Listing clouds')

        then:
        rtn.success == false
        rtn.clouds == ['a']
        rtn.msg.startsWith('Listing clouds: ')
        rtn.msg.contains('Library Share')
        rtn.error.contains('No library share found')
        rtn.errorType == 'ScvmmCommandException'
        rtn.errorCode == 'vmm.library-share'
    }

    def "isConnectionFailure recognises connection exceptions and connection-like causes"() {
        expect:
        ScvmmErrorTranslator.isConnectionFailure(new ScvmmConnectionException('x', 'h', 1))
        ScvmmErrorTranslator.isConnectionFailure(new RuntimeException('wrapped', new java.net.UnknownHostException('nope')))
        !ScvmmErrorTranslator.isConnectionFailure(new ScvmmException('data problem'))
        !ScvmmErrorTranslator.isConnectionFailure(null)
    }

    def "summary returns a single line"() {
        expect:
        !ScvmmErrorTranslator.summary(new RuntimeException('first line\nsecond line')).contains('\n')
    }
}
