// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import spock.lang.Specification
import spock.lang.Subject

class ScvmmApiServiceListTemplatesSpec extends Specification {

    @Subject
    ScvmmApiService service = Spy(ScvmmApiService, constructorArgs: [Mock(MorpheusContext)])

    def "library disk metadata uses the current disk rather than a disk from the template loop"() {
        given:
        String capturedCommand
        service.wrapExecuteCommand(_ as String, _ as Map) >> { String command, Map opts ->
            capturedCommand = command
            [success: true, data: []]
        }

        when:
        service.listTemplates([:])

        then:
        def libraryLoopStart = capturedCommand.indexOf('foreach ($VHDconf in $Disks)')
        libraryLoopStart >= 0
        def libraryLoop = capturedCommand.substring(libraryLoopStart)
        libraryLoop.contains('VHDType=$VHDconf.VHDType.ToString()')
        libraryLoop.contains('VHDFormat=$VHDconf.VHDFormatType.ToString()')
        !libraryLoop.contains('$VHD.')
    }
}
