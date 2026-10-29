// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.TaskResult
import com.morpheusdata.scvmm.support.Domain
import com.morpheusdata.scvmm.support.PsStub
import com.morpheusdata.scvmm.support.TaskResults
import com.morpheusdata.scvmm.support.VmmPayloads
import com.morpheusdata.scvmm.error.ScvmmCommandException
import com.morpheusdata.scvmm.error.ScvmmResponseParseException
import io.reactivex.rxjava3.core.Single
import spock.lang.Subject
import spock.lang.Timeout
import spock.lang.Specification

/**
 * The PowerShell boundary: executeCommand / wrapExecuteCommand / getServerDetails / server info) and
 * the list* parsers. Uses a real {@link ScvmmApiService} with {@code context.executeWindowsCommand} stubbed,
 * because the paging closures in the list* methods touch the private {@code log} field and break on a Spy.
 * Pages end with empty stdout ({@link #empty()}); a parsed {@code []} page would loop forever.
 */
@Timeout(10)
class ScvmmApiServiceListingsSpec extends Specification {

    MorpheusContext context = Mock()

    @Subject
    ScvmmApiService svc = new ScvmmApiService(context)

    List<String> scripts = []
    List<Integer> ports = []

    Map opts = [zone: Domain.cloud(), sshHost: 'scvmm.local', sshUsername: 'u', sshPassword: 'p']

    private void respond(Map byCommand, TaskResult fallback = empty()) {
        Closure ps = PsStub.capture(scripts, byCommand, fallback)
        context.executeWindowsCommand(*_) >> { args -> ports << (args[1] as Integer); Single.just(ps(args[4] as String, [:])) }
    }

    private void respondAll(Object value) {
        respond([(~/.*/): value])
    }

    private static TaskResult empty() {
        TaskResults.raw('')
    }

    private static List page(int n, int from = 0) {
        (from..<(from + n)).collect { VmmPayloads.vm(ID: "vm-${it}".toString(), Name: "vm-${it}".toString()) }
    }

    // ================================================================ executeCommand / wrapExecuteCommand

    def "executeCommand connects on WinRM port #expected for sshPort=#sshPort"() {
        given:
        respondAll(empty())

        when:
        svc.executeCommand('hostname', opts + [sshPort: sshPort])

        then:
        ports == [expected]

        where:
        sshPort || expected
        null    || 5985
        22      || 5985
        5986    || 5986
        '5986'  || 5986
    }

    def "executeCommand passes host, credentials and script through unchanged"() {
        when:
        def out = svc.executeCommand('hostname', opts)

        then:
        1 * context.executeWindowsCommand('scvmm.local', 5985, 'u', 'p', 'hostname', null, false) >> Single.just(TaskResults.raw('SCVMM01'))
        out.data == 'SCVMM01'
    }

    def "wrapExecuteCommand parses #shape stdout into a list"() {
        given:
        respondAll({ String cmd -> TaskResults.raw(stdout) })

        expect:
        svc.wrapExecuteCommand('x', opts).data == [[a: 1]]

        where:
        shape          | stdout
        'object'       | '{"a":1}'
        'array'        | '[{"a":1}]'
    }

    def "wrapExecuteCommand leaves empty or null stdout untouched (#stdout) and keeps success from the exit code (failOnError=#failOnError)"() {
        given:
        respondAll({ String cmd -> new TaskResult(success: exit == 0, exitCode: "$exit", data: stdout) })

        when:
        def out = svc.wrapExecuteCommand('x', opts, failOnError)

        then: 'the raw value is kept (not normalised to [])'
        out.data == stdout
        out.success == (exit == 0)

        where: 'a failing command is only returned (not thrown) when the caller tolerates failure'
        stdout | exit | failOnError
        ''     | 0    | true
        null   | 0    | true
        ''     | 1    | false
    }

    def "wrapExecuteCommand throws ScvmmCommandException for a non-zero exit code when failOnError is left at its default"() {
        given:
        respondAll({ String cmd -> new TaskResult(success: false, exitCode: '1', data: '') })

        when:
        svc.wrapExecuteCommand('x', opts)

        then:
        def e = thrown(ScvmmCommandException)
        e.exitCode == '1'
    }

    def "wrapExecuteCommand throws ScvmmResponseParseException on #shape stdout instead of returning success=false"() {
        // Callers without try/catch propagate it; the raw parser exception is replaced by a sanitized cause.
        given:
        respondAll({ String cmd -> TaskResults.raw(stdout) })

        when:
        svc.wrapExecuteCommand('x', opts)

        then:
        def e = thrown(ScvmmResponseParseException)
        e.cause instanceof IllegalArgumentException

        where:
        shape    | stdout
        'text'   | 'Get-SCVMHost : You cannot call a method on a null-valued expression.'
        'CLIXML' | '#< CLIXML\n<Objs Version="1.1.0.1"><S S="Error">boom</S></Objs>'
    }

    // ================================================================ getServerDetails

    def "getServerDetails returns the first VM record"() {
        given:
        respondAll(TaskResults.json([VmmPayloads.serverDetail(ID: 'vm-9')]))

        when:
        def rtn = svc.getServerDetails(opts, 'vm-9')

        then:
        rtn.success
        rtn.server.ID == 'vm-9'
        rtn.error == null
        scripts[0].contains('Get-SCVirtualMachine -VMMServer localhost -ID "vm-9"')
    }

    def "getServerDetails surfaces the VM_NOT_FOUND marker as error"() {
        given:
        respondAll(TaskResults.json([Error: 'VM_NOT_FOUND']))

        when:
        def rtn = svc.getServerDetails(opts, 'gone')

        then:
        !rtn.success
        rtn.server == null
        rtn.error == 'VM_NOT_FOUND'
    }

    def "getServerDetails keeps a ConvertTo-Json collapsed single NetworkAdapters map as-is (not normalised)"() {
        given:
        respondAll(TaskResults.json(VmmPayloads.serverDetail(NetworkAdapters: VmmPayloads.vmAdapter())))

        when:
        def rtn = svc.getServerDetails(opts, 'vm-1')

        then:
        rtn.success
        rtn.server.NetworkAdapters instanceof Map
    }

    def "getServerDetails reports success with a null server for an empty payload"() {
        given:
        respondAll(TaskResults.raw('[]'))

        when:
        def rtn = svc.getServerDetails(opts, 'vm-1')

        then:
        rtn.success
        rtn.server == null
    }

    def "getServerDetails returns success=false with a translated error when #why"() {
        given:
        respondAll(result)

        when:
        def rtn = svc.getServerDetails(opts, 'vm-1')

        then:
        !rtn.success
        rtn.server == null
        rtn.errorType == errorType
        rtn.msg
        rtn.error
        rtn.error != 'VM_NOT_FOUND'
        !rtn.connectionFailure

        where:
        why                              | result                                              || errorType
        'the command fails'              | TaskResults.raw('', 1)                              || 'ScvmmCommandException'
        'stdout is not JSON (caught)'    | { String c -> TaskResults.raw('Access is denied') } || 'ScvmmResponseParseException'
    }

    // ================================================================ extractWindowsServerVersion / getScvmmServerInfo

    def "extractWindowsServerVersion maps '#osName' to #code"() {
        expect:
        svc.extractWindowsServerVersion(osName) == code

        where:
        osName                                                || code
        'Microsoft Windows Server 2012 R2 Datacenter'         || 'windows.server.2012'
        'Microsoft Windows Server 2016 Standard'              || 'windows.server.2016'
        'Microsoft Windows Server 2019 Datacenter'            || 'windows.server.2019'
        'Microsoft Windows Server 2022 Standard'              || 'windows.server.2022.std.core'
        'Microsoft Windows Server 2022 Datacenter'            || 'windows.server.2022.dc.core'
        'Microsoft Windows Server 2022 Standard (Desktop Experience)' || 'windows.server.2022.std.core'
        'Windows Server 2022 Essentials'                      || 'windows.server.2022'
        'Microsoft Windows Server 2025 Datacenter'            || 'windows.server.2025'
        'Windows Server vNext'                                || 'windows.server.2012'
        ''                                                    || 'windows.server.2012'
    }

    def "extractWindowsServerVersion throws on a null OS name"() {
        when:
        svc.extractWindowsServerVersion(null)

        then:
        thrown(NullPointerException)
    }

    def "getScvmmServerInfo collects hostname, OS, memory and disks from the last meaningful stdout line"() {
        given:
        respond([
            'hostname'           : TaskResults.raw('\r\nSCVMM01\r\n'),
            'OsName'             : TaskResults.raw('Microsoft Windows Server 2019 Standard\n'),
            'PhysicalMemory'     : TaskResults.raw('TotalPhysicalMemory\n17179869184\n'),
            'Win32_DiskDrive'    : TaskResults.raw('Size\n107374182400\n'),
        ])

        when:
        def info = svc.getScvmmServerInfo(opts)

        then:
        info == [success: true, hostname: 'SCVMM01', osName: 'Microsoft Windows Server 2019 Standard',
                 memory: '17179869184', disks: '107374182400']
    }

    def "getScvmmServerInfo reports success with empty values when every command returns nothing"() {
        given:
        respondAll(empty())

        expect:
        svc.getScvmmServerInfo(opts) == [success: true, hostname: '', osName: '', memory: '', disks: '']
    }

    // ================================================================ listVirtualMachines

    def "listVirtualMachines pages 50 at a time until an empty page and concatenates the results"() {
        given:
        respondAll([TaskResults.json(page(50)), TaskResults.json(page(50, 50)), TaskResults.json(page(10, 100)), empty()])

        when:
        def rtn = svc.listVirtualMachines(opts + [zoneConfig: [:]])

        then:
        rtn.success
        rtn.virtualMachines.size() == 110
        rtn.virtualMachines*.ID.unique().size() == 110
        scripts.size() == 4
        scripts.collect { (it =~ /Select-Object -Skip (\d+) -First 50/)[0][1] as int } == [0, 50, 100, 150]
    }

    def "listVirtualMachines fails the whole listing when a later page fails instead of returning a partial list"() {
        // A partial list reported as success would make VirtualMachineSync delete the VMs on the unread pages.
        given:
        respondAll([TaskResults.json(page(50)), TaskResults.raw('', 1)])

        when:
        svc.listVirtualMachines(opts + [zoneConfig: [:]])

        then:
        thrown(ScvmmCommandException)
        scripts.size() == 2
    }

    def "listVirtualMachines propagates ScvmmResponseParseException when a page returns non-JSON stdout"() {
        given:
        respondAll([TaskResults.json(page(50)), { String c -> TaskResults.raw('WinRM error') }])

        when:
        svc.listVirtualMachines(opts + [zoneConfig: [:]])

        then:
        thrown(ScvmmResponseParseException)
    }

    def "listVirtualMachines propagates ScvmmCommandException when the first page fails"() {
        given:
        respondAll(TaskResults.raw('', 1))

        when:
        svc.listVirtualMachines(opts + [zoneConfig: [:]])

        then:
        thrown(ScvmmCommandException)
        scripts.size() == 1
    }

    def "listVirtualMachines reports success with no VMs for an empty first page"() {
        given:
        respondAll(empty())

        expect:
        svc.listVirtualMachines(opts + [zoneConfig: [:]]) == [success: true, virtualMachines: []]
    }

    def "listVirtualMachines builds the #scope selector"() {
        given:
        respondAll(empty())
        def zone = Domain.cloud(regionCode: regionCode)

        when:
        svc.listVirtualMachines(opts + [zone: zone, zoneConfig: [hostGroup: hostGroup]])

        then:
        fragments.every { scripts[0].contains(it) }
        absent.every { !scripts[0].contains(it) }

        where:
        scope                   | regionCode | hostGroup              || fragments                                                                                                                       | absent
        'all VMs'               | null       | null                   || ['Get-SCVirtualMachine -VMMServer localhost -All']                                                                              | ["Status -ne 'Missing'", 'Get-SCCloud']
        'cloud'                 | 'cloud-1'  | null                   || ["\$_.ID -eq 'cloud-1'", "-Cloud \$cloud | where { \$_.Status -ne 'Missing' }"]                                                  | ['HostGroupPath']
        'host group'            | null       | 'All Hosts\\group-1'   || ["\$_.Status -ne 'Missing' -and \$_.HostGroupPath -like 'All Hosts\\group-1*'"]                                                  | ['Get-SCCloud', ' -All ']
        'cloud and host group'  | 'cloud-1'  | 'All Hosts\\group-1'   || ["-Cloud \$cloud | where { \$_.Status -ne 'Missing' -and \$_.HostGroupPath -like 'All Hosts\\group-1*'"]                       | []
    }

    def "listVirtualMachines requests a JSON depth deep enough for per-NIC address arrays"() {
        given:
        respondAll(empty())

        when:
        svc.listVirtualMachines(opts + [zoneConfig: [:]])

        then:
        scripts[0].endsWith('| ConvertTo-Json -Depth 5')
    }

    // ================================================================ listHosts / listDatastores / listRegisteredFileShares

    def "listHosts pages 10 at a time"() {
        given:
        def hosts = { int n, int from -> (from..<(from + n)).collect { VmmPayloads.host(id: "h-$it".toString()) } }
        respondAll([TaskResults.json(hosts(10, 0)), TaskResults.json(hosts(3, 10)), empty()])

        when:
        def rtn = svc.listHosts(opts)

        then:
        rtn.success
        rtn.hosts.size() == 13
        scripts.collect { (it =~ /-Skip (\d+) -First 10/)[0][1] as int } == [0, 10, 20]
    }

    def "listHosts reports #expected for #why"() {
        given:
        respondAll(result)

        expect:
        svc.listHosts(opts) == expected

        where:
        why            | result                  || expected
        'empty page'   | empty()                 || [success: true, hosts: []]
    }

    def "listDatastores scopes volumes to the cloud's clusters when the zone has a regionCode"() {
        given:
        respondAll([TaskResults.json([VmmPayloads.datastore()]), empty()])

        when:
        def rtn = svc.listDatastores(opts + [zone: Domain.cloud(regionCode: 'cloud-1')])

        then:
        rtn.success
        rtn.datastores*.id == ['ds-1']
        scripts[0].contains("Where-Object { \$_.ID -eq  'cloud-1' }")
        scripts[0].contains('Get-SCVMHostCluster')
    }

    def "listDatastores limits volumes to the configured host group and escapes single quotes"() {
        given:
        respondAll(empty())

        when:
        svc.listDatastores(opts + [zone: Domain.cloud(config: [hostGroup: "All Hosts\\O'Brien"])])

        then:
        scripts[0].contains("\$hostGroupPath = 'All Hosts\\O''Brien'")
        !scripts[0].contains('Get-SCCloud')
    }

    def "listDatastores with neither regionCode nor host group passes an empty host group (all hosts)"() {
        given:
        respondAll(empty())

        when:
        def rtn = svc.listDatastores(opts)

        then:
        rtn == [success: true, datastores: []]
        scripts[0].contains("\$hostGroupPath = ''")
    }

    def "listDatastores propagates ScvmmCommandException when the first page fails"() {
        given:
        respondAll(TaskResults.raw('', 1))

        when:
        svc.listDatastores(opts)

        then:
        thrown(ScvmmCommandException)
    }

    def "listRegisteredFileShares pages file shares and returns them under 'datastores'"() {
        given:
        respondAll([TaskResults.json([VmmPayloads.fileShare(), VmmPayloads.fileShare(ID: 'share-2')]), empty()])

        when:
        def rtn = svc.listRegisteredFileShares(opts)

        then:
        rtn.success
        rtn.datastores*.ID == ['share-1', 'share-2']
        scripts[0].contains('Get-SCStorageFileShare -VMMServer localhost | Sort-Object -Property ID | Select-Object -Skip 0 -First 50')
    }

    def "listRegisteredFileShares reports #expected.success for #why"() {
        given:
        respondAll(result)

        expect:
        svc.listRegisteredFileShares(opts) == expected

        where:
        why          | result                 || expected
        'empty page' | empty()                || [success: true, datastores: []]
    }

    // ================================================================ clusters / host groups

    def "listClusters keeps only clusters under the zone's host group"() {
        given:
        respondAll(TaskResults.json([VmmPayloads.cluster(id: 'c1', hostGroup: 'All Hosts\\group-1'),
                                     VmmPayloads.cluster(id: 'c2', hostGroup: 'All Hosts\\group-2'),
                                     VmmPayloads.cluster(id: 'c3', hostGroup: null)]))

        when:
        def rtn = svc.listClusters(opts + [zone: Domain.cloud(config: [hostGroup: 'All Hosts\\group-1'])])

        then:
        rtn.success
        rtn.clusters*.id == ['c1']
    }

    def "listClusters returns every cluster when the zone has no host group"() {
        given:
        respondAll(TaskResults.json([VmmPayloads.cluster(id: 'c1'), VmmPayloads.cluster(id: 'c2')]))

        expect:
        svc.listClusters(opts).clusters*.id == ['c1', 'c2']
    }

    def "listClusters returns #expected for #why"() {
        given:
        respondAll(result)

        expect:
        svc.listClusters(opts) == expected

        where:
        why            | result                  || expected
        'empty stdout' | empty()                 || [success: true, clusters: '']
    }

    def "listHostGroups without a regionCode returns every host group"() {
        given:
        respondAll(TaskResults.json([[id: 'hg-1', path: 'All Hosts'], [id: 'hg-2', path: 'All Hosts\\a']]))

        when:
        def rtn = svc.listHostGroups(opts)

        then:
        rtn.success
        rtn.hostGroups*.id == ['hg-1', 'hg-2']
    }

    def "listHostGroups with a regionCode keeps the cloud's host groups and their descendants"() {
        given:
        respond([
            'Get-SCCloud'     : TaskResults.json([[ID: 'cloud-1', HostGroup: ['All Hosts\\a']], [ID: 'cloud-2', HostGroup: ['All Hosts\\b']]]),
            'Get-SCVMHostGroup': TaskResults.json([[id: 'root', path: 'All Hosts'], [id: 'a', path: 'All Hosts\\a'],
                                                   [id: 'a1', path: 'All Hosts\\a\\1'], [id: 'ab', path: 'All Hosts\\ab'],
                                                   [id: 'b', path: 'All Hosts\\b']]),
        ])

        when:
        def rtn = svc.listHostGroups(opts + [zone: Domain.cloud(regionCode: 'cloud-1')])

        then:
        rtn.success
        rtn.hostGroups*.id == ['a', 'a1']
    }

    def "listHostGroups with a regionCode propagates ScvmmCommandException when the cloud lookup fails"() {
        given:
        respondAll(TaskResults.raw('', 1))

        when:
        svc.listHostGroups(opts + [zone: Domain.cloud(regionCode: 'cloud-1')])

        then:
        thrown(ScvmmCommandException)
        scripts.size() == 1
    }

    // ================================================================ templates / clouds / capability profiles / shares

    def "listTemplates drops leftover temporary templates and keeps library disks"() {
        given:
        respondAll(TaskResults.json([
            VmmPayloads.template(ID: 't1', Name: 'Win2022'),
            VmmPayloads.template(ID: 't2', Name: 'Temporary Morpheus Template 0f8fad5b-d9cb-469f-a165-70867728950e'),
            VmmPayloads.template(ID: 't3', Name: 'Temporary Template 0f8fad5b-d9cb-469f-a165-70867728950e'),
            [ID: 'vhd-1', Name: 'ubuntu.vhdx', VHDFormatType: 'VHDX', Disks: [VmmPayloads.templateDisk()]],
        ]))

        when:
        def rtn = svc.listTemplates(opts)

        then:
        rtn.success
        rtn.templates*.ID == ['t1', 'vhd-1']
    }

    def "listTemplates passes a template whose Disks contain null through unfiltered (filtering is TemplatesSync's job)"() {
        given:
        respondAll(TaskResults.json([VmmPayloads.template(Disks: [VmmPayloads.templateDisk(), null])]))

        expect:
        svc.listTemplates(opts).templates[0].Disks.size() == 2
    }

    def "listTemplates returns #expected for #why"() {
        given:
        respondAll(result)

        expect:
        svc.listTemplates(opts) == expected

        where:
        why            | result                 || expected
        'empty stdout' | empty()                || [success: true, templates: []]
    }

    def "listClouds returns clouds"() {
        given:
        respondAll(TaskResults.json([[ID: 'c1', Name: 'Cloud 1']]))

        expect:
        svc.listClouds(opts) == [success: true, clouds: [[ID: 'c1', Name: 'Cloud 1']]]
    }

    def "getCapabilityProfiles returns the profiles, or null profiles with success for an empty payload"() {
        given:
        respondAll(result)

        expect:
        svc.getCapabilityProfiles(opts) == expected

        where:
        result                                                || expected
        TaskResults.json([VmmPayloads.capabilityProfile()])  || [success: true, capabilityProfiles: [[ID: 'cap-1', Name: 'Hyper-V']]]
        empty()                                               || [success: true, capabilityProfiles: null]
    }

    def "getCloud looks the zone's regionCode up and returns the first match"() {
        given:
        respondAll(result)

        when:
        def rtn = svc.getCloud(opts + [zone: Domain.cloud(regionCode: 'cloud-1')])

        then:
        rtn == expected
        scripts[0].contains("\$_.ID -eq 'cloud-1'")

        where:
        result                                                      || expected
        TaskResults.json([ID: 'cloud-1', CapabilityProfiles: []])  || [success: true, cloud: [ID: 'cloud-1', CapabilityProfiles: []]]
        empty()                                                     || [success: true, cloud: null]
    }

    def "listLibraryShares returns the shares"() {
        given:
        respondAll(TaskResults.json([[ID: 's1', Name: 'MSSCVMMLibrary', Path: '\\\\lib\\MSSCVMMLibrary']]))

        expect:
        svc.listLibraryShares(opts) == [success: true, libraryShares: [[ID: 's1', Name: 'MSSCVMMLibrary', Path: '\\\\lib\\MSSCVMMLibrary']]]
    }

    def "#method propagates ScvmmCommandException when the command fails (callers translate it)"() {
        given:
        respondAll(TaskResults.raw('', 1))

        when:
        svc."$method"(opts + extraOpts)

        then:
        def e = thrown(ScvmmCommandException)
        e.exitCode == '1'
        scripts.size() == 1

        where:
        method                     | extraOpts
        'listHosts'                | [:]
        'listRegisteredFileShares' | [:]
        'listClusters'             | [:]
        'listTemplates'            | [:]
        'listClouds'               | [:]
        'getCapabilityProfiles'    | [:]
        'getCloud'                 | [zone: Domain.cloud(regionCode: 'cloud-1')]
        'listLibraryShares'        | [:]
    }

    // ================================================================ listAllNetworks

    def "listAllNetworks keeps the probed VM network whose logical network exists"() {
        given:
        respond([
            'Get-SCLogicalNetwork': TaskResults.json([VmmPayloads.logicalNetwork()]),
            'Get-SCVMNetwork'     : TaskResults.json([[ID: 'vmnet-1', Name: 'vmnet-1', LogicalNetwork: 'logical-1']]),
        ])

        when:
        def rtn = svc.listAllNetworks(opts)

        then:
        rtn.success
        rtn.networks*.ID == ['vmnet-1']
        scripts[1].contains('Select-Object -First 1')
    }

    def "listAllNetworks reports success=false when no logical network is returned (#why)"() {
        given:
        respondAll(result)

        expect:
        !svc.listAllNetworks(opts).success

        where:
        why            | result
        'empty stdout' | empty()
        'failure'      | TaskResults.raw('', 1)
    }

    def "listAllNetworks still succeeds with no networks when the VM network probe fails"() {
        given:
        respond([
            'Get-SCLogicalNetwork': TaskResults.json([VmmPayloads.logicalNetwork()]),
            'Get-SCVMNetwork'     : TaskResults.raw('', 1),
        ])

        expect:
        svc.listAllNetworks(opts) == [success: true, networks: []]
    }

    def "listAllNetworks catches a parse error and reports a message"() {
        given:
        respondAll({ String c -> TaskResults.raw('not json') })

        when:
        def rtn = svc.listAllNetworks(opts)

        then:
        !rtn.success
        rtn.msg.startsWith('Error fetching all networks list from SCVMM Host: ')
        rtn.msg.contains('non-JSON')
        rtn.errorType == 'ScvmmResponseParseException'
    }

    // ================================================================ listNetworkIPPools

    def "listNetworkIPPools returns pools and the VM network mapping, passing DNS/gateway shapes through unnormalised"() {
        given:
        respond([
            'Get-SCStaticIPAddressPool': TaskResults.json([VmmPayloads.ipPool(DNSServers: '10.0.0.53'),
                                                           VmmPayloads.ipPool(ID: 'pool-2', DNSServers: null, DefaultGateways: [])]),
            'Get-SCVMNetwork'          : TaskResults.json([[ID: 'vmnet-1', Name: 'vmnet-1', LogicalNetwork: 'logical-1', LogicalNetworkId: 'ln-1']]),
        ])

        when:
        def rtn = svc.listNetworkIPPools(opts)

        then:
        rtn.success
        rtn.ipPools*.ID == ['pool-1', 'pool-2']
        rtn.ipPools[0].DNSServers == '10.0.0.53'
        rtn.ipPools[1].DNSServers == null
        rtn.networkMapping*.LogicalNetworkId == ['ln-1']
    }

    def "listNetworkIPPools succeeds with empty lists for empty payloads"() {
        given:
        respondAll(empty())

        expect:
        svc.listNetworkIPPools(opts) == [success: true, ipPools: [], networkMapping: []]
    }

    def "listNetworkIPPools stops after a failed pool query and does not read the network mapping"() {
        given:
        respondAll(TaskResults.raw('', 1))

        when:
        def rtn = svc.listNetworkIPPools(opts)

        then:
        !rtn.success
        scripts.size() == 1
    }

    def "listNetworkIPPools reports success=false when the network mapping query fails"() {
        given:
        respond([
            'Get-SCStaticIPAddressPool': TaskResults.json([VmmPayloads.ipPool()]),
            'Get-SCVMNetwork'          : TaskResults.raw('', 1),
        ])

        when:
        def rtn = svc.listNetworkIPPools(opts)

        then:
        !rtn.success
        rtn.ipPools.size() == 1
    }

    def "listNetworkIPPools treats a non-zero exit code as failure even with success=true"() {
        given:
        respondAll({ String c -> new TaskResult(success: true, exitCode: '1', data: '') })

        expect:
        !svc.listNetworkIPPools(opts).success
    }

    def "listNetworkIPPools catches a parse error and reports a message"() {
        given:
        respondAll({ String c -> TaskResults.raw('not json') })

        when:
        def rtn = svc.listNetworkIPPools(opts)

        then:
        !rtn.success
        rtn.msg.startsWith('Error syncing ip pools list from SCVMM Host: ')
        rtn.msg.contains('non-JSON')
        rtn.errorType == 'ScvmmResponseParseException'
    }

    // ================================================================ reserveIPAddress parsing

    def "reserveIPAddress returns the whole first grant record as ipAddress"() {
        given:
        respondAll(TaskResults.json([[ID: 'ip-1', Address: '10.0.0.5']]))

        when:
        def rtn = svc.reserveIPAddress(opts, 'pool-1')

        then: 'the caller receives the {ID, Address} map, not the address string'
        rtn.success
        rtn.ipAddress == [ID: 'ip-1', Address: '10.0.0.5']
    }

    def "reserveIPAddress reports failure with a pool-exhausted message for an empty payload"() {
        given:
        respondAll(empty())

        expect: 'an empty grant is not a usable reservation'
        svc.reserveIPAddress(opts, 'pool-1') == [success: false, ipAddress: [],
                msg: 'SCVMM did not grant an IP address from pool pool-1; the pool may be exhausted']
    }

    def "reserveIPAddress reports success=false with a translated message and the VMM error for an 'already allocated' failure"() {
        given:
        respondAll({ String c -> new TaskResult(success: false, exitCode: '1', data: '',
                error: 'Grant-SCIPAddress : The IP address 10.0.0.5 is already allocated. (Error ID: 13668)') })

        when:
        def rtn = svc.reserveIPAddress(opts, 'pool-1', '10.0.0.5')

        then:
        !rtn.success
        rtn.msg.startsWith('Error reserving an IP address from SCVMM: ')
        rtn.error.contains('The IP address 10.0.0.5 is already allocated')
        rtn.errorType == 'ScvmmCommandException'
        rtn.errorCode == 'vmm.ip-already-allocated'
        rtn.msg.contains('already allocated')
        !rtn.msg.contains('no available addresses')
    }

    def "reserveIPAddress catches a parse error and reports a contextual message"() {
        given:
        respondAll({ String c -> TaskResults.raw('Grant-SCIPAddress : error 13668') })

        when:
        def rtn = svc.reserveIPAddress(opts, 'pool-1')

        then:
        !rtn.success
        rtn.msg.startsWith('Error reserving an IP address from SCVMM: ')
        rtn.msg.contains('non-JSON')
        rtn.errorType == 'ScvmmResponseParseException'
    }
}
