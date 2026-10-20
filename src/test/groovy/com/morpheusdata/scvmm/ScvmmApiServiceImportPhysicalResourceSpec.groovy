// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.model.TaskResult
import com.morpheusdata.model.Workload
import com.morpheusdata.core.MorpheusContext
import spock.lang.Specification
import spock.lang.Subject

class ScvmmApiServiceImportPhysicalResourceSpec extends Specification {

    static final String ROOT_SHARE = '\\\\SCVMMAUG.pqa-rg.hpe.com\\MSSCVMMLibrary'
    static final String EXPECTED_PATH = ROOT_SHARE + '\\morpheus\\morpheus_server_110\\config.iso'

    MorpheusContext context = Mock()

    @Subject
    ScvmmApiService service

    def setup() {
        service = Spy(ScvmmApiService, constructorArgs: [context])
        service.executeCommand(_, _) >> { new TaskResult(success: true) }
    }

    def "returns the library share path as a plain String"() {
        when:
        def rtn = service.importPhysicalResource([rootSharePath: ROOT_SHARE], 'c:\\VirtualDisks\\config.iso', 'morpheus\\morpheus_server_110', 'config.iso')

        then:
        rtn.success
        rtn.sharePath == EXPECTED_PATH
        rtn.sharePath.getClass() == String
    }

    def "share path survives a workload config round trip unchanged"() {
        given:
        def rtn = service.importPhysicalResource([rootSharePath: ROOT_SHARE], 'c:\\VirtualDisks\\config.iso', 'morpheus\\morpheus_server_110', 'config.iso')
        def workload = new Workload()

        when:
        workload.setConfigMap([deleteDvdOnComplete: [removeIsoFromDvd: true, deleteIso: rtn.sharePath]])

        then:
        workload.configMap.deleteDvdOnComplete.deleteIso == EXPECTED_PATH
    }
}
