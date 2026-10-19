# Copilot instructions — morpheus-scvmm-plugin

Morpheus cloud plugin for Microsoft SCVMM (Hyper-V), written in Groovy 4 on `morpheus-plugin-api` (1.5.1-SNAPSHOT, `provided`), Java 25, Gradle 9, Spock 2.3. Package `com.morpheusdata.scvmm`. It replaces the embedded SCVMM integration in morpheus-ui, which is being deprecated — **parity with embedded is the baseline, exceeding it is a deliberate decision** (see ADR-0014).

Detailed, maintained context lives in [`docs/knowledge/`](../docs/knowledge/README.md): [architecture](../docs/knowledge/architecture.md) · [triage playbook](../docs/knowledge/triage-playbook.md) · [decisions](../docs/knowledge/decisions/README.md) · [feature checklist](../docs/knowledge/feature-planning-checklist.md) · [session log](../docs/knowledge/session-log.md). Load the relevant file before triaging, designing, or planning; use the skills in `.github/skills/` as entry points.

## Architecture in one screen

* **Layer 1 — providers** registered in `ScvmmPlugin.initialize()`: `ScvmmCloudProvider` (cloud CRUD, `refresh()` sync orchestration, `filterDatastores`), `ScvmmProvisionProvider` (`runWorkload`/`runHost`, clone, `getServerDetails`, resize via the **legacy** `ResizeFacet`, `HypervisorConsoleFacet`), `ScvmmBackupProvider` → `scvmmSnapshot` checkpoints, `ScvmmOptionSourceProvider` (dropdowns). No IPAM provider on `main` yet (PR #187).
* **Layer 2 — sync workers** (`sync/*Sync`, `SyncTask` by `externalId`) run in fixed order: Network → Clusters → IsolationNetwork → Host → Datastores → RegisteredStorageFileShares → CloudCapabilityProfiles → Templates → IpPools → VirtualMachine.
* **Layer 3 — `ScvmmApiService`**: the only place PowerShell is built. `generateCommandString` appends `| ConvertTo-Json -Depth N`; `wrapExecuteCommand` → `MorpheusContext.executeWindowsCommand(host, 5985, …)` over WinRM and parses stdout into `TaskResult.data`. Scripts use `Exit 23..28` codes; errors arrive as CLIXML (`cleanPowerShellError`).
* Helpers: `helper/ReconfigurePoolIpHelper` (pool leases on reconfigure), `util/MorpheusUtil` (console host/creds, reload-before-save), `logging/Prefixed*` (`[SCVMMPlugin]` prefix), `ScvmmConstants`.
* External dependencies: SCVMM server (WinRM 5985, Morpheus Windows agent for `fileCopy` of cloud-init ISOs/VHDs), Hyper-V hosts (KVP is how VMM learns guest IPs; vmrdp console :2179), cloud-init NoCloud ISO built by core, guest Morpheus agent (`morphd`).

## Build / test

```bash
./gradlew test                      # Spock; add --offline when the Morpheus Nexus is down (never disable TLS)
./gradlew shadowJar                 # build/libs/morpheus-scvmm-plugin-1.0.0-<ts>-<n>-<sha>-all.jar — upload the -all jar
./gradlew codenarcMain              # non-failing static analysis; CI also runs gitleaks + JaCoCo gates (line 20 / branch 30 / instr 50)
```
CI publishes `main` to HPE Artifactory (`morpheus-open-source-plugins`); GitHub run artifacts are reports only. `functional_tests/` is a pytest suite against a live appliance.

## Conventions observed in this codebase

* Groovy, dynamic typing, `def` result maps (`[success:false, msg/error/errOut/errorMsg]`) — check the **key name at every caller**; `TaskResult` has `error`, not `errorData`/`errorOutput`; `ServiceResponse.error()` does not set `msg`.
* Spock specs in `src/test/groovy/com/morpheusdata/scvmm/**`, one `*Spec` per concern; pure PowerShell builders are tested by asserting on the generated script (`new ScvmmApiService(Mock(MorpheusContext))`). Spock gotchas: `setup()` stubs beat `given:`, private methods on a `GroovySpy` run for real, `computeServer.get()` returns `Maybe`, `List<String>.contains(GString)` is false.
* Logging via `PrefixedLoggerFactory.getLogger(Class)`; never log credentials or full `opts` at INFO; log `success/exitCode/data/error`, never `${out}`.
* Commit subjects keep the `MORPH-xxxxx:` prefix; no Jira keys in code comments; comments ≤ 2 lines. Published PR branches are updated by **merge**, not rebase (ADR-0016). Repo pushes/commits use the `ckeskar23` identity (SSH alias `github-ckeskar23`); do not edit shared `.git/config`.
* Mark "verified on appliance" vs "unit-tested only" in PR descriptions; the QA lab is a single-node cluster with no CSVs.

## Top recurring pitfalls

1. **`ComputeServer` saves are whole-model binds** — never save an object loaded before a WinRM poll; re-fetch first (`MorpheusUtil.getMorpheusServer`). Wiped `agentInstalled`/`platform` is the signature (ADR-0006).
2. **`$ignore = <cmdlet>` hides failures** — follow placement/config cmdlets with `if (-not $?) { Exit N }`; async cmdlets (`Start-SCVirtualMachine`, `Set-SCVirtualDVDDrive`, `New-SCVMCheckpoint`) are fire-and-forget unless you read the job.
3. **`ConvertTo-Json -Depth`** collapses deeper arrays into strings (use `VM_REPORT_JSON_DEPTH`); PowerShell hashtable keys are case-insensitive; `-not $x` is true for `0` (VLAN 0 bug); Groovy `'\\\\'` emits two backslashes; GStrings stored in config serialise as `{"values":…}` — `.toString()`.
4. **Legacy `ResizeFacet`** — the plugin creates/removes interface rows and leases/releases pool IPs itself; core never sends ipMode-only changes; Gen1 cannot hot-add NICs (Error 640).
5. **Marshalled models are partial** — `Network.pool` is not populated in `runWorkload`; use `interface.networkPool`; top-level `networkConfig.doStatic` is deprecated/always false.
6. **Pool IPs**: grant to the adapter with `-GrantToObjectID`/`-Description`, bind with `-IPv4Addresses` only — `-IPv4AddressPools` double-allocates; anonymous grants leak forever (ADR-0004).
7. **Hypervisor credentials belong only on `console*` fields** (full `DOMAIN\user`, FQDN `consoleHost`); never write them to `sshUsername` (ADR-0007). `runHost` and `runWorkload` drift — add features to both.
8. **Datastores**: `assignedZonePools` + `externalType`, `zonePool` must stay null; HA = cluster **and** every disk on cluster storage (ADR-0001/0005). Wizard sends `pool-<id>`.
9. **Clone**: `cloneContainerId` is the **workload** id; `Remove-SCISO -Force` detaches a mounted ISO; `-UseDiffDiskOptimization` makes `resizeDisk` refuse; the source's cloud-init cache must be reset (PR #193, still open).
10. **Plugin upload = delete + install**: state parented to plugin-registered types (e.g. `NetworkPoolServer`) is destroyed; design it to be rebuildable (ADR-0015). `provided` API ≠ runtime API — guard new model fields (ADR-0018).
11. **Environment first**: before blaming the plugin check appliance clock (`timedatectl`), DNS for Hyper-V FQDNs (guacd), guest `hyperv-daemons`/KVP, per-host vSwitch/DHCP, VMM `UpdateFailed` state, and which plugin sha is actually deployed.

## Working with a user on this repo

* Do not push, open PRs, or post Jira/PR comments unless asked; draft them. Never paste tokens; sanitize hostnames/IPs in anything written to the repo.
* When asked to fix a bug, locate the stack line (not the enclosing message), check the playbook for a known cause, compare with embedded SCVMM, and state plugin vs environment with evidence before proposing code.
* Add/refresh the knowledge base (playbook row, ADR, session-log row) as part of any non-trivial change.
