// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.error

import java.util.regex.Pattern

/**
 * Single table of known VMM / WinRM / PowerShell error signatures and the user-facing message each maps to.
 *
 * Add new signatures here rather than sprinkling {@code contains(...)} checks through the API service.
 */
class ScvmmKnownErrors {

    enum Category {
        /** Transport / listener / authentication problems: the host is not usable at all. */
        CONNECTION,
        /** The command reached VMM but VMM rejected the request. */
        VMM,
        /** The PowerShell environment on the host is broken (module missing, etc). */
        HOST
    }

    static class KnownError {
        final String code
        final Category category
        final Pattern pattern
        final String userMessage
        /** Optional field name for ServiceResponse.errors (e.g. 'password', 'host'). */
        final String field

        KnownError(String code, Category category, String regex, String userMessage, String field = null) {
            this.code = code
            this.category = category
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
            this.userMessage = userMessage
            this.field = field
        }

        boolean matches(String text) {
            text != null && pattern.matcher(text).find()
        }

        @Override
        String toString() {
            "KnownError(${code})"
        }
    }

    static final String GENERATION_2_MISMATCH_MESSAGE = 'The virtual hard disk selected is not compatible with the template which include generation 2 virtual machine functionality.'
    static final String GENERATION_1_MISMATCH_MESSAGE = 'The virtual hard disk selected is not compatible with the template which include generation 1 virtual machine functionality.'

    /** Ordered: the first match wins, so put the most specific signatures first. */
    static final List<KnownError> KNOWN_ERRORS = Collections.unmodifiableList([
            // --- authentication / authorization ---
            new KnownError('winrm.auth', Category.CONNECTION,
                    /(\b401\b|Unauthorized|Access is denied|access denied|Logon failure|user name or password is incorrect|The WinRM client cannot process the request.*(Kerberos|Negotiate|authentication)|The credentials? (is|are) invalid|WinRM.*cannot process the request.*credential)/,
                    'Authentication to the SCVMM host failed. Verify the username (DOMAIN\\user) and password, and that the account is a VMM administrator.',
                    'password'),
            // --- transport / listener ---
            new KnownError('winrm.listener', Category.CONNECTION,
                    /(WinRM cannot complete the operation|the client cannot connect to the destination specified|WS-Management service|WinRM service.*not running|Connection refused|Connection reset|No route to host|Network is unreachable|UnknownHostException|Name or service not known|nodename nor servname|could not be resolved|HTTP.*(503|502|504))/,
                    'Unable to reach the WinRM service on the SCVMM host. Verify the host address, that WinRM is enabled and listening on the configured port (default 5985), and that firewall rules allow the Morpheus appliance to connect.',
                    'host'),
            new KnownError('winrm.timeout', Category.CONNECTION,
                    /(timed? ?out|SocketTimeout|Read timed out|The operation has timed out|The WinRM client sent a request .* did not receive a response)/,
                    'The connection to the SCVMM host timed out. Verify the host is online and reachable from the Morpheus appliance.',
                    'host'),
            // --- host environment ---
            new KnownError('host.vmm-module-missing', Category.HOST,
                    /((Get|New|Set|Remove)-SC\w+.*is not recognized as (the name of )?a cmdlet|Could not load file or assembly.*VirtualManager|module.*virtualmachinemanager.*(not|could not be) (found|loaded))/,
                    'The VMM PowerShell module is not available on the SCVMM host. Install the VMM console on the host used for this cloud.'),
            new KnownError('host.vmm-server-unreachable', Category.HOST,
                    /(You cannot access VMM management server|Unable to connect to the VMM management server|VMM server .* is not (available|responding)|Virtual Machine Manager cannot process the request because an error occurred while authenticating)/,
                    'The SCVMM host cannot reach the VMM management server (localhost). Verify the VMM service is running on the host.'),
            // --- VMM: provisioning ---
            new KnownError('vmm.generation2-mismatch', Category.VMM, /which includes generation 2/, GENERATION_2_MISMATCH_MESSAGE),
            new KnownError('vmm.generation1-mismatch', Category.VMM, /which includes generation 1/, GENERATION_1_MISMATCH_MESSAGE),
            new KnownError('vmm.placement', Category.VMM,
                    /(Unable to find a suitable host|no (suitable )?host(s)? (is|are|was|were) available|not available for placement|placement (rules|failed)|does not have (enough|sufficient)|Insufficient (memory|storage|disk)|cannot be placed)/,
                    'SCVMM could not place the virtual machine: no suitable host or insufficient host/storage capacity is available. Check host group placement settings and free resources.'),
            new KnownError('vmm.library-share', Category.VMM,
                    /(No library share found|library share.*(not|could not be) (found|located)|Get-SCLibraryShare.*returned no|The specified library share|Unable to find the specified library)/,
                    'The configured SCVMM library share could not be found. Verify the Library Share setting on the cloud matches a share registered in VMM.'),
            new KnownError('vmm.template-create-failed', Category.VMM,
                    /(New-SCVMTemplate.*(failed|error)|exit code 23\b)/,
                    'SCVMM failed to create the temporary VM template used for provisioning. Verify the selected virtual hard disk exists in the library and matches the VM generation.'),
            new KnownError('vmm.vm-not-found', Category.VMM,
                    /(VM_NOT_FOUND|Unable to find the specified virtual machine|Get-SCVirtualMachine.*returned (nothing|null)|Cannot validate argument on parameter 'VM'\. The argument is null)/,
                    'The virtual machine could not be found in SCVMM. It may have been deleted outside of Morpheus.'),
            new KnownError('vmm.ip-already-released', Category.VMM,
                    /Unable to find the specified allocated IP address/,
                    'The IP address is no longer allocated in the SCVMM static IP pool.'),
            new KnownError('vmm.ip-pool-exhausted', Category.VMM,
                    /(no (more )?(IP )?addresses? (are )?available|IP address pool.*(exhausted|full)|Grant-SCIPAddress.*(failed|error))/,
                    'The SCVMM static IP pool has no available addresses.'),
            new KnownError('vmm.differencing-disk', Category.VMM,
                    /Cannot Resize a Differencing Disk/,
                    'The disk cannot be resized because it is a differencing disk or has checkpoints. Remove checkpoints and retry.'),
            new KnownError('vmm.object-in-use', Category.VMM,
                    /(is (currently )?in use|being used by another (job|process|task)|cannot be (modified|deleted) (because|while))/,
                    'SCVMM reports the object is in use by another job. Wait for running VMM jobs to finish and retry.'),
            new KnownError('vmm.path-not-found', Category.VMM,
                    /(Cannot find path|does not exist|Could not find (a )?part of the path|Path .* not found)/,
                    'A path referenced by the operation does not exist on the SCVMM host. Verify the Working Path, Disk Path and Library Share settings.'),
    ])

    static KnownError match(String text) {
        if (!text) {
            return null
        }
        KNOWN_ERRORS.find { it.matches(text) }
    }

    static KnownError match(Throwable t) {
        if (t == null) {
            return null
        }
        Throwable current = t
        int depth = 0
        while (current != null && depth < 8) {
            KnownError hit = match(textOf(current))
            if (hit) {
                return hit
            }
            current = current.cause?.is(current) ? null : current.cause
            depth++
        }
        null
    }

    static boolean isConnectionError(String text) {
        match(text)?.category == Category.CONNECTION
    }

    private static String textOf(Throwable t) {
        StringBuilder sb = new StringBuilder()
        if (t.message) {
            sb.append(t.message)
        }
        if (t instanceof ScvmmCommandException) {
            sb.append('\n').append(t.errorOutput ?: '')
            if (t.exitCode) {
                sb.append("\nexit code ${t.exitCode}")
            }
        } else if (t instanceof ScvmmJobFailedException) {
            sb.append('\n').append(t.errorInfo ?: '')
        }
        sb.append('\n').append(t.class.name)
        sb.toString()
    }
}
