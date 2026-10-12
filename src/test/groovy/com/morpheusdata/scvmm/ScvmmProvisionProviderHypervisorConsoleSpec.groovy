// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.MorpheusSynchronousAccountCredentialService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import spock.lang.Specification
import spock.lang.Subject

/**
 * Covers the ProvisionProvider.HypervisorConsoleFacet hooks core invokes before opening a hypervisor
 * (VMConnect) console: updateServerHost re-resolves the VM's current Hyper-V host from SCVMM and
 * enableConsoleAccess populates console settings for servers that have none.
 */
class ScvmmProvisionProviderHypervisorConsoleSpec extends Specification {

    MorpheusContext context = Mock()
    MorpheusServices services = Mock()
    MorpheusSynchronousComputeServerService computeServerService = Mock()
    MorpheusSynchronousAccountCredentialService accountCredentialService = Mock()
    ScvmmApiService apiService = Mock()
    ScvmmPlugin plugin = Mock()

    Map connectionDetails = [sshHost: 'scvmm.example.com']

    @Subject
    ScvmmProvisionProvider provider = new ScvmmProvisionProvider(plugin, context) {
        @Override
        protected LinkedHashMap<String, Object> fetchScvmmConnectionDetails(ComputeServer server) {
            connectionDetails as LinkedHashMap<String, Object>
        }
    }

    Cloud cloud = new Cloud(id: 1L, name: 'scvmm-cloud')
    ComputeServer hostA = new ComputeServer(id: 10L, externalId: 'host-a-guid', name: 'HV-A', hostname: 'hv-a.corp.local')
    ComputeServer hostB = new ComputeServer(id: 20L, externalId: 'host-b-guid', name: 'HV-B', hostname: 'hv-b.corp.local')
    ComputeServer vm

    def setup() {
        provider.apiService = apiService
        context.services >> services
        services.computeServer >> computeServerService
        services.accountCredential >> accountCredentialService
        computeServerService.list(_) >> [hostA, hostB]

        vm = new ComputeServer(id: 46L, externalId: 'vm-guid', name: 'ubuntu-vm', cloud: cloud, parentServer: hostA,
                consoleType: 'vmrdp', consoleHost: 'hv-a.corp.local', consolePort: 2179)
        computeServerService.get(46L) >> vm
    }

    // ----------------------------------------------------------------------------------------------------------
    // updateServerHost
    // ----------------------------------------------------------------------------------------------------------

    def "updateServerHost moves the VM to the host SCVMM reports and persists the new FQDN console host"() {
        given: 'SCVMM now places the VM on host B'
        apiService.getServerDetails(connectionDetails, 'vm-guid') >> [success: true, server: [HostId: 'host-b-guid']]

        when:
        def response = provider.updateServerHost(new ComputeServer(id: 46L))

        then:
        1 * computeServerService.save({ ComputeServer s ->
            s.id == 46L && s.parentServer.id == 20L && s.consoleHost == 'hv-b.corp.local'
        })
        response.success
        response.data.host == 'hv-b.corp.local'
    }

    def "updateServerHost repairs a stale console host even when the parent host is unchanged"() {
        given: 'the DB still carries the short host name from before the FQDN fix'
        vm.consoleHost = 'HV-A'
        apiService.getServerDetails(connectionDetails, 'vm-guid') >> [success: true, server: [HostId: 'host-a-guid']]

        when:
        def response = provider.updateServerHost(new ComputeServer(id: 46L))

        then:
        1 * computeServerService.save({ ComputeServer s -> s.parentServer.id == 10L && s.consoleHost == 'hv-a.corp.local' })
        response.success
        response.data.host == 'hv-a.corp.local'
    }

    def "updateServerHost does not save when placement and console host are already current"() {
        given:
        apiService.getServerDetails(connectionDetails, 'vm-guid') >> [success: true, server: [HostId: 'host-a-guid']]

        when:
        def response = provider.updateServerHost(new ComputeServer(id: 46L))

        then:
        0 * computeServerService.save(_)
        response.success
        response.data.host == 'hv-a.corp.local'
    }

    def "updateServerHost leaves console host alone for servers that are not vmrdp"() {
        given:
        vm.consoleType = null
        vm.consoleHost = null
        apiService.getServerDetails(connectionDetails, 'vm-guid') >> [success: true, server: [HostId: 'host-b-guid']]

        when:
        provider.updateServerHost(new ComputeServer(id: 46L))

        then:
        1 * computeServerService.save({ ComputeServer s -> s.parentServer.id == 20L && s.consoleHost == null })
    }

    def "updateServerHost fails without saving when SCVMM reports an unknown host"() {
        given:
        apiService.getServerDetails(connectionDetails, 'vm-guid') >> [success: true, server: [HostId: 'not-synced-guid']]

        when:
        def response = provider.updateServerHost(new ComputeServer(id: 46L))

        then:
        0 * computeServerService.save(_)
        !response.success
    }

    def "updateServerHost fails without saving when SCVMM reports no host"() {
        given:
        apiService.getServerDetails(connectionDetails, 'vm-guid') >> [success: false]

        when:
        def response = provider.updateServerHost(new ComputeServer(id: 46L))

        then:
        0 * computeServerService.save(_)
        !response.success
    }

    def "updateServerHost fails for a server that is not bound to an SCVMM VM"() {
        given:
        vm.externalId = null

        when:
        def response = provider.updateServerHost(new ComputeServer(id: 46L))

        then:
        0 * apiService.getServerDetails(*_)
        0 * computeServerService.save(_)
        !response.success
    }

    // ----------------------------------------------------------------------------------------------------------
    // enableConsoleAccess
    // ----------------------------------------------------------------------------------------------------------

    def "enableConsoleAccess populates vmrdp console settings from current placement and cloud credentials"() {
        given: 'a server with no console settings on a cloud with the hypervisor console enabled'
        cloud.setConfigProperty('enableVnc', 'on')
        vm.consoleType = null
        vm.consoleHost = null
        vm.consolePort = null
        apiService.getServerDetails(connectionDetails, 'vm-guid') >> [success: true, server: [HostId: 'host-b-guid']]
        accountCredentialService.loadCredentials(cloud) >> new AccountCredential(data: [username: 'CORP\\svc-scvmm', password: 's3cret'])

        when:
        def response = provider.enableConsoleAccess(new ComputeServer(id: 46L))

        then:
        1 * computeServerService.save({ ComputeServer s -> s.parentServer.id == 20L && s.consoleHost == null })  // placement refresh (not vmrdp yet)
        1 * computeServerService.save({ ComputeServer s ->
            s.consoleType == 'vmrdp' && s.consoleHost == 'hv-b.corp.local' && s.consolePort == 2179 &&
                    s.consoleUsername == 'CORP\\svc-scvmm' && s.consolePassword == 's3cret' && s.parentServer.id == 20L
        })
        response.success
        response.data.host == 'hv-b.corp.local'
    }

    def "enableConsoleAccess keeps the DOMAIN prefix on the console username"() {
        given:
        cloud.setConfigProperty('enableVnc', 'on')
        apiService.getServerDetails(connectionDetails, 'vm-guid') >> [success: true, server: [HostId: 'host-a-guid']]
        accountCredentialService.loadCredentials(cloud) >> new AccountCredential(data: [username: 'PQA-RG\\Administrator', password: 'pw'])

        when:
        def response = provider.enableConsoleAccess(new ComputeServer(id: 46L))

        then:
        response.success
        vm.consoleUsername == 'PQA-RG\\Administrator'
    }

    def "enableConsoleAccess falls back to the cloud config credentials when no account credential is attached"() {
        given:
        cloud.setConfigProperty('enableVnc', 'on')
        cloud.setConfigProperty('username', 'localadmin')
        cloud.setConfigProperty('password', 'localpw')
        apiService.getServerDetails(connectionDetails, 'vm-guid') >> [success: true, server: [HostId: 'host-a-guid']]
        accountCredentialService.loadCredentials(cloud) >> null

        when:
        def response = provider.enableConsoleAccess(new ComputeServer(id: 46L))

        then:
        response.success
        vm.consoleUsername == 'localadmin'
        vm.consolePassword == 'localpw'
    }

    def "enableConsoleAccess does nothing when the hypervisor console is disabled on the cloud"() {
        when:
        def response = provider.enableConsoleAccess(new ComputeServer(id: 46L))

        then:
        0 * apiService.getServerDetails(*_)
        0 * computeServerService.save(_)
        !response.success
    }

    def "enableConsoleAccess still configures the console from the stored parent when SCVMM placement cannot be resolved"() {
        given:
        cloud.setConfigProperty('enableVnc', 'on')
        apiService.getServerDetails(connectionDetails, 'vm-guid') >> [success: false]
        accountCredentialService.loadCredentials(cloud) >> new AccountCredential(data: [username: 'u', password: 'p'])

        when:
        def response = provider.enableConsoleAccess(new ComputeServer(id: 46L))

        then:
        1 * computeServerService.save({ ComputeServer s -> s.consoleHost == 'hv-a.corp.local' && s.consoleType == 'vmrdp' })
        response.success
    }
}
