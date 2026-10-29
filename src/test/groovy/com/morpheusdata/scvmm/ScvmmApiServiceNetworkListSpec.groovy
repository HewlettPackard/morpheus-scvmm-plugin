// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.TaskResult
import com.morpheusdata.scvmm.error.ScvmmConnectionException
import com.morpheusdata.scvmm.support.Domain
import com.morpheusdata.scvmm.support.PsStub
import com.morpheusdata.scvmm.support.TaskResults
import com.morpheusdata.scvmm.support.VmmPayloads
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Timeout

/**
 * listNetworks / listNoIsolationVLans page through VM networks 50 at a time, re-reading the logical networks on
 * every page, until a page returns empty stdout. The paging closures log through the private {@code log} field,
 * which breaks on a Spy, so this spec uses a real service and stubs {@code context.executeWindowsCommand} with
 * ConvertTo-Json stdout (exercising the real wrapExecuteCommand parsing too).
 */
@Timeout(10)
class ScvmmApiServiceNetworkListSpec extends Specification {

    static final String LOGICAL = 'Get-SCLogicalNetwork'
    static final String VMNETS = 'Get-SCVMNetwork'
    static final String DEFINITIONS = 'Get-SCLogicalNetworkDefinition'

    List<String> scripts = []

    MorpheusContext context = Mock()

    @Subject
    ScvmmApiService svc = new ScvmmApiService(context)

    Map opts = [zone: Domain.cloud(), sshHost: 'scvmm.local']
    Map regionOpts = [zone: Domain.cloud(regionCode: 'cloud-region-1'), sshHost: 'scvmm.local']

    private void respond(Map byCommand) {
        Closure ps = PsStub.capture(scripts, byCommand, TaskResults.raw(''))
        context.executeWindowsCommand(*_) >> { args -> Single.just(ps(args[4] as String, [:])) }
    }

    private static TaskResult empty() {
        TaskResults.raw('')
    }

    private static List logicals(String... names) {
        (names as List<String>).withIndex().collect { String n, int i -> VmmPayloads.logicalNetwork(ID: "ln-${i + 1}".toString(), Name: n) }
    }

    // ---------------------------------------------------------------- listNetworks

    def "listNetworks scopes the logical networks to the SCVMM cloud when the zone has a regionCode"() {
        given:
        respond([(VMNETS): empty(), (LOGICAL): TaskResults.json(logicals('logical-1'))])

        when:
        svc.listNetworks(regionOpts)

        then:
        scripts[0].contains("\$cloud = Get-SCCloud -VMMServer localhost | where { \$_.ID -eq 'cloud-region-1' }")
        scripts[0].contains('Get-SCLogicalNetwork -VMMServer localhost -Cloud $cloud | Select ID,Name')
    }

    def "listNetworks reads all logical networks when the zone has no regionCode"() {
        given:
        respond([(VMNETS): empty(), (LOGICAL): TaskResults.json(logicals('logical-1'))])

        when:
        svc.listNetworks(opts)

        then:
        scripts[0].contains('Get-SCLogicalNetwork -VMMServer localhost | Select ID,Name')
        !scripts[0].contains('Get-SCCloud')
    }

    def "listNetworks requests isolated VM networks 50 at a time"() {
        given:
        respond([(VMNETS): empty(), (LOGICAL): TaskResults.json(logicals('logical-1'))])

        when:
        svc.listNetworks(opts)

        then:
        scripts[1].contains('where {$_.IsolationType -ne "NoIsolation"}')
        scripts[1].contains('Select-Object -Skip 0 -First 50')
    }

    def "listNetworks returns the VM networks of known logical networks with their subnets nested"() {
        // plan expected a flattened subnet list; the code returns network maps with Subnets as reported
        given:
        def net1 = VmmPayloads.network(ID: 'n1', LogicalNetwork: 'logical-1',
            Subnets: [VmmPayloads.vlan(VLanID: 100), VmmPayloads.vlan(ID: 'subnet-1-200', VLanID: 200, Subnet: '10.0.200.0/24')])
        def net2 = VmmPayloads.network(ID: 'n2', LogicalNetwork: 'logical-2', Subnets: [VmmPayloads.vlan(VLanID: 300)])
        def orphan = VmmPayloads.network(ID: 'n3', LogicalNetwork: 'not-in-cloud')
        respond([(VMNETS): [TaskResults.json([net1, net2, orphan]), empty()], (LOGICAL): TaskResults.json(logicals('logical-1', 'logical-2'))])

        when:
        def rtn = svc.listNetworks(opts)

        then:
        rtn.success
        rtn.networks*.ID == ['n1', 'n2']
        rtn.networks[0].Subnets*.VLanID == [100, 200]
        rtn.networks[0].Subnets[1].Subnet == '10.0.200.0/24'
    }

    def "listNetworks pages until a page returns no data and concatenates the pages"() {
        given:
        def page1 = [VmmPayloads.network(ID: 'n1')]
        def page2 = [VmmPayloads.network(ID: 'n2')]
        respond([(VMNETS): [TaskResults.json(page1), TaskResults.json(page2), empty()], (LOGICAL): TaskResults.json(logicals('logical-1'))])

        when:
        def rtn = svc.listNetworks(opts)

        then:
        rtn.success
        rtn.networks*.ID == ['n1', 'n2']
        scripts.size() == 6
        scripts.findAll { it.contains(VMNETS) }.collect { (it =~ /-Skip (\d+) -First 50/)[0][1] } == ['0', '50', '100']
    }

    def "listNetworks passes through networks whose Subnets are null or lack a VLanID"() {
        given:
        def noSubnets = VmmPayloads.network(ID: 'n1', Subnets: null)
        def noVlan = VmmPayloads.network(ID: 'n2', Subnets: [VmmPayloads.vlan(VLanID: null)])
        respond([(VMNETS): [TaskResults.json([noSubnets, noVlan]), empty()], (LOGICAL): TaskResults.json(logicals('logical-1'))])

        when:
        def rtn = svc.listNetworks(opts)

        then:
        noExceptionThrown()
        rtn.success
        rtn.networks*.ID == ['n1', 'n2']
        rtn.networks[0].Subnets == null
    }

    def "listNetworks returns success with no networks when the first page is empty"() {
        given:
        respond([(VMNETS): empty(), (LOGICAL): TaskResults.json(logicals('logical-1'))])

        expect:
        svc.listNetworks(opts) == [success: true, networks: []]
    }

    def "listNetworks fails without listing VM networks when the logical network lookup returns #label"() {
        given:
        respond([(LOGICAL): result])

        when:
        def rtn = svc.listNetworks(opts)

        then:
        !rtn.success
        rtn.networks == []
        rtn.msg.startsWith(msg)
        scripts.size() == 1

        where:
        label           | result                                      || msg
        'an error'      | TaskResults.fail('Access denied')           || 'Error syncing networks list from SCVMM Host: SCVMM denied access'
        'no data'       | TaskResults.json([])                        || 'SCVMM returned no logical networks for the cloud (Get-SCLogicalNetwork)'
        'empty output'  | TaskResults.raw('')                         || 'SCVMM returned no logical networks for the cloud (Get-SCLogicalNetwork)'
        'exit code 1'   | TaskResults.json(logicals('logical-1'), 1)  || 'Error syncing networks list from SCVMM Host: SCVMM reported an error'
    }

    def "listNetworks rethrows ScvmmConnectionException when the WinRM call throws (so the refresh can mark the cloud offline)"() {
        given:
        context.executeWindowsCommand(*_) >> { throw new RuntimeException('winrm timeout') }

        when:
        svc.listNetworks(opts)

        then:
        def e = thrown(ScvmmConnectionException)
        e.message.contains('winrm timeout')
    }

    // ---------------------------------------------------------------- listNoIsolationVLans

    def "listNoIsolationVLans pages non-isolated logical network definitions 50 at a time"() {
        given:
        respond([(DEFINITIONS): empty(), (LOGICAL): TaskResults.json(logicals('logical-1'))])

        when:
        svc.listNoIsolationVLans(regionOpts)

        then:
        scripts[0].contains("where { \$_.ID -eq 'cloud-region-1' }")
        scripts[1].contains('Get-SCLogicalNetworkDefinition -VMMServer localhost | where {$_.IsolationType -eq "None"}')
        scripts[1].contains('Select-Object -Skip 0 -First 50')
        scripts[1].contains('where { $_.IsVlanEnabled -eq $true }')
    }

    def "listNoIsolationVLans keeps VLANs whose LogicalNetworkID belongs to a known logical network"() {
        given:
        def vlans = [
            VmmPayloads.vlan(ID: 'net-1-100', LogicalNetworkID: 'ln-1', VLanID: 100),
            VmmPayloads.vlan(ID: 'net-2-200', LogicalNetworkID: 'ln-2', VLanID: 200),
            VmmPayloads.vlan(ID: 'net-9-900', LogicalNetworkID: 'ln-9', VLanID: 900)
        ]
        respond([(DEFINITIONS): [TaskResults.json(vlans), empty()], (LOGICAL): TaskResults.json(logicals('logical-1', 'logical-2'))])

        when:
        def rtn = svc.listNoIsolationVLans(opts)

        then:
        rtn.success
        rtn.networks*.ID == ['net-1-100', 'net-2-200']
        rtn.networks[0].Subnet == '10.0.100.0/24'
    }

    def "listNoIsolationVLans fails when the logical network lookup fails"() {
        given:
        respond([(LOGICAL): TaskResults.fail('Access denied')])

        when:
        def rtn = svc.listNoIsolationVLans(opts)

        then:
        !rtn.success
        rtn.networks == []
        rtn.msg.startsWith('Error syncing isolation networks list from SCVMM Host: SCVMM denied access')
        rtn.errorType == 'ScvmmCommandException'
        scripts.size() == 1
    }

    def "listNoIsolationVLans returns success with no networks when the first page is empty"() {
        given:
        respond([(DEFINITIONS): empty(), (LOGICAL): TaskResults.json(logicals('logical-1'))])

        expect:
        svc.listNoIsolationVLans(opts) == [success: true, networks: []]
    }

    def "listNoIsolationVLans fails instead of reporting a partial list as success when a page fails"() {
        // a partial list fed to the sync would delete the VLANs on the unread pages
        given:
        def vlans = [VmmPayloads.vlan(ID: 'net-1-100', LogicalNetworkID: 'ln-1', VLanID: 100)]
        respond([(DEFINITIONS): [TaskResults.json(vlans), TaskResults.fail('Get-SCLogicalNetworkDefinition : query failed')],
                 (LOGICAL)    : TaskResults.json(logicals('logical-1'))])

        when:
        def rtn = svc.listNoIsolationVLans(opts)

        then:
        !rtn.success
        rtn.msg.startsWith('Error syncing isolation networks list from SCVMM Host: ')
        rtn.msg.contains('query failed')
        rtn.errorType == 'ScvmmCommandException'
    }

    def "listNoIsolationVLans rethrows ScvmmConnectionException when the WinRM call throws"() {
        given:
        context.executeWindowsCommand(*_) >> { throw new RuntimeException('winrm timeout') }

        when:
        svc.listNoIsolationVLans(opts)

        then:
        def e = thrown(ScvmmConnectionException)
        e.message.contains('winrm timeout')
    }

    // ---------------------------------------------------------------- listAllNetworks

    def "listAllNetworks probes only the first VM network (connectivity check)"() {
        given:
        respond([(VMNETS): TaskResults.json([VmmPayloads.network(ID: 'n1')]), (LOGICAL): TaskResults.json(logicals('logical-1'))])

        when:
        def rtn = svc.listAllNetworks(opts)

        then:
        scripts.size() == 2
        scripts[0].contains('Get-SCLogicalNetwork -VMMServer localhost | Select ID,Name')
        scripts[1].contains('Sort-Object -Property ID | Select-Object -First 1')
        rtn == [success: true, networks: [VmmPayloads.network(ID: 'n1')]]
    }

    def "listAllNetworks drops VM networks whose logical network is unknown"() {
        given:
        respond([(VMNETS): TaskResults.json([VmmPayloads.network(ID: 'n1', LogicalNetwork: 'other')]),
                 (LOGICAL): TaskResults.json(logicals('logical-1'))])

        expect:
        svc.listAllNetworks(opts) == [success: true, networks: []]
    }

    def "listAllNetworks returns success with no networks when the VM network call #label"() {
        given:
        respond([(VMNETS): result, (LOGICAL): TaskResults.json(logicals('logical-1'))])

        expect:
        svc.listAllNetworks(opts) == [success: true, networks: []]

        where:
        label            | result
        'returns null'   | empty()
        'exits non-zero' | TaskResults.fail('Get-SCVMNetwork : query failed')
    }

    def "listAllNetworks fails when the logical network lookup #label"() {
        given:
        respond([(LOGICAL): result])

        when:
        def rtn = svc.listAllNetworks(opts)

        then:
        !rtn.success
        rtn.networks == []
        rtn.msg.startsWith(msg)
        scripts.size() == 1

        where:
        label             | result                            || msg
        'fails'           | TaskResults.fail('Access denied') || 'Error fetching all networks list from SCVMM Host: SCVMM denied access'
        'returns no data' | TaskResults.json([])              || 'SCVMM returned no logical networks (Get-SCLogicalNetwork)'
    }

    def "listAllNetworks rethrows ScvmmConnectionException when the WinRM call throws"() {
        given:
        context.executeWindowsCommand(*_) >> { throw new RuntimeException('winrm timeout') }

        when:
        svc.listAllNetworks(opts)

        then:
        def e = thrown(ScvmmConnectionException)
        e.message.contains('winrm timeout')
    }
}
