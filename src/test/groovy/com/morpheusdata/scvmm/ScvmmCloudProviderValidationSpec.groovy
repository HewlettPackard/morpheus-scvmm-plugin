// Copyright 2026 Hewlett Packard Enterprise Development LP
package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Cloud
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.scvmm.error.ScvmmConnectionException
import spock.lang.Specification

class ScvmmCloudProviderValidationSpec extends Specification {
    MorpheusContext context = Mock()
    ScvmmApiService api = Mock()
    ScvmmCloudProvider provider = Spy(ScvmmCloudProvider, constructorArgs: [new ScvmmPlugin(), context])
    Cloud cloud = new Cloud(id: 1L, name: 'vmm')

    def setup() {
        provider.apiService = api
        provider.validateSharedController(_) >> [success: true]
        api.getScvmmZoneOpts(_, _) >> [:]
        cloud.setConfigMap([host: 'vmm', workingPath: 'C:\\morpheus',
                diskPath: 'C:\\vms', libraryShare: 'C:\\library'])
    }

    def "validation rejects a successful query with no clouds"() {
        given:
        api.listClouds(_) >> [success: true, clouds: clouds]

        when:
        def result = provider.validate(cloud, credentials())

        then:
        !result.success
        result.msg.contains('did not return any clouds')

        where:
        clouds << [[], null]
    }

    def "validation accepts a nonempty cloud list"() {
        given:
        api.listClouds(_) >> [success: true, clouds: [[ID: 'cloud1']]]

        expect:
        provider.validate(cloud, credentials()).success
    }

    def "validation surfaces authentication and listener causes with field errors"() {
        given:
        api.listClouds(_) >> { throw new ScvmmConnectionException(message, 'vmm', 5985) }

        when:
        def result = provider.validate(cloud, credentials())

        then:
        !result.success
        result.msg.contains(expected)
        result.errors[field]
        result.data.errorCode == code

        where:
        message                    | expected         | field      | code
        'WinRM 401 Unauthorized'   | 'Authentication' | 'password' | 'winrm.auth'
        'Connection refused'       | 'WinRM service'  | 'host'     | 'winrm.listener'
    }

    def "an empty network inventory still proves communication succeeded"() {
        given:
        api.getScvmmZoneAndHypervisorOpts(*_) >> [:]
        api.listAllNetworks(_) >> [success: true, networks: []]

        expect:
        provider.checkCommunication(cloud, null).success
    }

    private static ValidateCloudRequest credentials() {
        new ValidateCloudRequest('DOMAIN\\admin', 'secret', null, [:])
    }
}
