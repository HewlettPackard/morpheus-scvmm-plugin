// (c) Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.util

import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import spock.lang.Specification
import spock.lang.Unroll

class MorpheusUtilSpec extends Specification {

    @Unroll
    def "getConsoleUsername prefers credential data over cloud config and keeps DOMAIN\\user intact (cred=#credUser, config=#configUser)"() {
        given:
        def cloud = new Cloud()
        cloud.accountCredentialData = credUser != null ? [username: credUser, password: 'secret'] : null
        if (configUser != null) {
            cloud.setConfigProperty('username', configUser)
        }

        expect:
        MorpheusUtil.getConsoleUsername(cloud) == expected

        where:
        credUser                | configUser              || expected
        'PQA-RG\\Administrator' | 'CFG\\other'            || 'PQA-RG\\Administrator'
        null                    | 'PQA-RG\\Administrator' || 'PQA-RG\\Administrator'
        'Administrator'         | null                    || 'Administrator'
        null                    | null                    || null
    }

    @Unroll
    def "getConsolePassword prefers credential data over cloud config (cred=#credPw, config=#configPw)"() {
        given:
        def cloud = new Cloud()
        cloud.accountCredentialData = credPw != null ? [username: 'u', password: credPw] : null
        if (configPw != null) {
            cloud.setConfigProperty('password', configPw)
        }

        expect:
        MorpheusUtil.getConsolePassword(cloud) == expected

        where:
        credPw   | configPw   || expected
        'credPw' | 'configPw' || 'credPw'
        null     | 'configPw' || 'configPw'
        null     | null       || null
    }

    def "console credential helpers tolerate a null cloud"() {
        expect:
        MorpheusUtil.getConsoleUsername(null) == null
        MorpheusUtil.getConsolePassword(null) == null
    }

    @Unroll
    def "getConsoleHost prefers FQDN hostname and falls back to name (hostname=#hostname, name=#name)"() {
        given:
        def host = new ComputeServer(hostname: hostname, name: name)

        expect:
        MorpheusUtil.getConsoleHost(host) == expected

        where:
        hostname                 | name         || expected
        'hv-node-01.corp.local'  | 'hv-node-01' || 'hv-node-01.corp.local'
        null                     | 'hv-node-01' || 'hv-node-01'
        ''                       | 'hv-node-01' || 'hv-node-01'
        'hv-node-01.corp.local'  | null         || 'hv-node-01.corp.local'
        null                     | null         || null
    }

    def "getConsoleHost returns null for a null parent server"() {
        expect:
        MorpheusUtil.getConsoleHost(null) == null
    }
}
