// (c) Copyright 2026 Hewlett Packard Enterprise Development LP
package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.OsType
import com.morpheusdata.model.VirtualImage
import spock.lang.Specification
import spock.lang.Unroll

class ScvmmProvisionProviderImageOptsSpec extends Specification {

    ScvmmProvisionProvider provider = new ScvmmProvisionProvider(Mock(ScvmmPlugin), Mock(MorpheusContext))

    @Unroll
    def "image boot options preserve #generation and secureBoot=#secureBoot independently of uefi=#uefi"() {
        given:
        def image = new VirtualImage(secureBoot: secureBoot, uefi: uefi, osType: new OsType(platform: 'linux'))
        image.setConfigProperty('generation', generation)

        expect:
        provider.getScvmmImageOpts(image) == [
            scvmmGeneration: generation ?: 'generation1',
            secureBoot: secureBoot == true,
            secureBootTemplate: 'MicrosoftUEFICertificateAuthority'
        ]

        where:
        [generation, secureBoot, uefi] << [
            [null, 'generation1', 'generation2'],
            [null, false, true],
            [null, false, true]
        ].combinations()
    }

    @Unroll
    def "Secure Boot template selection uses OS platform #osPlatform before image platform #imagePlatform"() {
        given:
        def image = new VirtualImage(
            secureBoot: true,
            platform: imagePlatform,
            osType: osPlatform == null ? null : new OsType(platform: osPlatform)
        )

        expect:
        provider.getScvmmImageOpts(image).secureBootTemplate == template

        where:
        osPlatform | imagePlatform | template
        'windows'  | 'linux'       | 'MicrosoftWindows'
        'linux'    | 'windows'     | 'MicrosoftUEFICertificateAuthority'
        'other'    | null          | 'MicrosoftUEFICertificateAuthority'
        'osx'      | null          | 'MicrosoftUEFICertificateAuthority'
        'solaris'  | null          | 'MicrosoftUEFICertificateAuthority'
        'unknown'  | null          | 'MicrosoftUEFICertificateAuthority'
        null       | 'WINDOWS'     | 'MicrosoftWindows'
        null       | 'linux'       | 'MicrosoftUEFICertificateAuthority'
        null       | 'freebsd'     | 'MicrosoftUEFICertificateAuthority'
        null       | null          | 'MicrosoftUEFICertificateAuthority'
    }
}
