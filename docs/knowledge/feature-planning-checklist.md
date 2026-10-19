# SCVMM plugin — feature planning & execution checklist

Everything here is something that actually tripped up past work (see [session-log.md](session-log.md)). Use it when writing a story, reviewing a design, or opening a PR. Pair with [architecture.md](architecture.md) and the [decisions](decisions/README.md).

## A. Before you start

- [ ] **Read the embedded implementation first** (`morpheus-ui` `clouds/scvmm`: `ScvmmProvisionService`, `ScvmmComputeService`, `ScvmmComputeUtility`) and decide explicitly: match, exceed, or diverge — and write that in the story (ADR-0014). Tickets have carried KVM root causes and legacy file paths; verify against code.
- [ ] **Check whether the fix is in common core code** (GSP templates, `GuacamoleTunnelBuilder`, `AbstractBoxProvisionService`, …). If so, nothing to port — note it and move on.
- [ ] **Find existing PRs / branches** touching the same methods. `getServerDetails`, `buildCreateServerCommands`, `syncInterfaces`, `reserveIPAddress`, `getHostAndDatastore`, `resizeWorkloadAndServer` are hot spots where PRs collide semantically without textual conflicts. Check open PRs: #187 (IPAM), #193 (clone cloud-init), #198 (error handling), #203 (resize disk), #204 (secure boot) as of 2026-10-19.
- [ ] **Check which plugin-api version introduces any model field you need.** `morpheus-plugin-api` is `provided`; runtime classes come from the appliance. New fields need a `minVersion` bump or `hasProperty` guards (ADR-0018). Unknown appliance ↔ API mapping is a blocker to resolve early.
- [ ] **Write acceptance criteria that are observable on an appliance** (log line, DB column, VMM object state), not "works". Capture Jira ACs in your notes early — Atlassian MCP tokens expire.
- [ ] **Decide the PR split up front** (one concern per PR; epic ACs vs parity extras as separate commits) and whether the branch must compile standalone (inline constants for cherry-picked helpers).

## B. Design review questions

### Core contract
- [ ] Which SPI facet does this ride on? SCVMM is on the **legacy** `ResizeFacet`: the plugin owns interface rows and IP-pool bookkeeping on reconfigure; core only sends interface updates on network/subnet change and drops primary-NIC deletes.
- [ ] Does core call a facet you don't implement (`HypervisorConsoleFacet`, `ConvertToManagedFacet`)? Grep the appliance log for `does not implement`.
- [ ] Are ids the right kind? Instance ≠ container/workload ≠ server ids; `cloneContainerId` is a **workload** id. Test on an appliance where they have drifted.
- [ ] Is any state smuggled through `configMap` (`deleteDvdOnComplete`, `cloneParentDvdCleanup`)? Store plain `String`s only — GStrings serialise as `{"values":…}`.
- [ ] Does the marshalled model carry what you read? `Network.pool` is **not** populated on the `Network` handed to `runWorkload`; use `interface.networkPool` (and reload the pool type if missing). Top-level `networkConfig.doStatic` is deprecated/always false; use per-interface flags.
- [ ] **Saves are whole-model binds.** Any `ComputeServer` save after a WinRM poll must re-fetch first (ADR-0006). Does your change move `server.status` to `provisioned` earlier than core expects (sync will then write to it)?
- [ ] Will plugin upload (`plugins#delete` + install) destroy state your feature depends on (anything parented to a plugin-registered type)? Make it rebuildable from the cloud (ADR-0015).

### SCVMM / PowerShell
- [ ] Verified every cmdlet parameter set against Microsoft Learn (`systemcenter-ps-2025`)? Reviewers' comments and your memory are both fallible (`-VirtualNetworkAdapterConfiguration` is single-object; `New-SCVirtualNetworkAdapter -VM` has no `-VMMServer`; `-IPv4AddressPools` double-allocates).
- [ ] Every cmdlet whose output is assigned to `$ignore` is followed by `if (-not $?) { Exit N }` with a distinct exit code and a message that names the object?
- [ ] Script ends with an expression value (not an `if`), stays under the `ConvertTo-Json -Depth` you pass (nested arrays at depth ≥ `-Depth` collapse to strings), avoids case-colliding hashtable keys, and does not rely on `-not $x` for numeric fields that can be 0?
- [ ] Async cmdlets (`-RunAsynchronously`, `Start-SCVirtualMachine`, `Set-SCVirtualDVDDrive`, `New-SCVMCheckpoint`) — do you read the job/error stream, and do you wait for object locks (Error 2606) before the next config change on the same VM?
- [ ] Generation awareness: Gen1 cannot hot-add NICs (Error 640); root disk on IDE 0:0; `-SecureBootEnabled` Gen2 only; `MAX_NETWORKS = 8` is the Gen1 synthetic limit.
- [ ] Per-host objects: `Get-SCVirtualNetwork` is a per-host vSwitch — always scope with `-VMHost`.
- [ ] Will the change spam VMM Jobs (5-second polls, LUN probing)? Failed jobs are visible to customers.
- [ ] Destructive cmdlets (`Remove-SCISO -Force` detaches and deletes; `Revoke-SCIPAddress`) — is the target provably the right object?

### Networking & IP pools
- [ ] Which IP source applies: SCVMM pool (grant first, bind without `-IPv4AddressPools`, ADR-0004), DHCP (needs guest KVP for VMM to see the IP), Morpheus pool/manual static (cloud-init applies it)? No-isolation networks have no `VMSubnet` — pool/subnet live on the logical network definition.
- [ ] Grants carry `-GrantToObjectID` / `-Description morpheus:<vm>` so leaks are attributable, and every failure path revokes?
- [ ] Reconfigure: covered remove → update → add, hot vs stop, same-network ipMode change (core never sends it — document), DHCP ↔ pool transitions, Error 15046 (Static adapter on pool-less network)?
- [ ] Multi-NIC: indexes into `$VNAConfigs[n]` track created adapters only; clone path intentionally does not add extras.

### Storage & placement
- [ ] Datastore scoping via `assignedZonePools` + `externalType`; `zonePool` stays null (ADR-0005). Wizard pool ids arrive as `pool-<id>` / `poolGroup-<id>` / `pool-`.
- [ ] HA = cluster + every disk on cluster storage (ADR-0001); data disks pinned to the root disk's host; mixed storage is a validation error.
- [ ] Host eligibility: enabled, powered on, has datastore — maintenance mode is **not** yet considered (ADR-0017); hosts are not filtered by network availability.
- [ ] Volume `type` consistency (`StorageVolumeTypeHelper` codes vs `getRootVolumeStorageTypes()` advertising only `standard`).
- [ ] Differencing disks / checkpoints block `resizeDisk` (ADR-0019).

### Console / credentials
- [ ] Hypervisor creds only on `console*` fields with full `DOMAIN\user`; never touch `sshUsername/sshPassword` (ADR-0007). `consoleHost` is the FQDN.
- [ ] Feature added to `runWorkload` — did you add it to `runHost` too (console fields, poolType, …)? The two paths drift.

### Error handling & logging
- [ ] Result-map keys consistent at every caller (`error` vs `errOut` vs `msg` vs `errorMsg`)? `TaskResult` has `error`, not `errorData`/`errorOutput`. `ServiceResponse.error()` does not set `msg`.
- [ ] Failures reach the user: `rtn.success = !rtn.error`, `server.statusMessage`, `provisionResponse.setError`, CLIXML cleaned. `log.debug` of a result map is not error handling.
- [ ] No credentials, tokens, or full `opts` dumps at INFO; log `success/exitCode/data/error`, never `${out}`.
- [ ] Timeout messages say what was awaited, how long, and the last observed state.

## C. Implementation & testing

- [ ] **Spock first for pure script builders** (`buildCreateServerCommands`, `buildReserveIPAddressCommand`, reconcilers): `new ScvmmApiService(Mock(MorpheusContext))`, assert on the generated PowerShell. Spock 2.3-groovy-4.0 gotchas: `setup()` stubs beat `given:`; private methods on a `GroovySpy` run for real; `computeServer.get()` returns `Maybe`; `List<String>.contains(GString)` is false; response closures receive the whole arg list; `ComputeServerInterface.primaryInterface` defaults to true.
- [ ] Unit tests passing does **not** mean the feature works in the appliance (PR #187's provider was unreachable by core dispatch). Trace the core call path (`CloudPluginProvisioningService`, `PluginNetworkPoolService`, `NetworkConfigService`, `DatastoreService`) before trusting a provider design.
- [ ] `./gradlew test` (use `--offline` if the Morpheus Nexus is down — never disable TLS), `./gradlew shadowJar`; upload the **`-all.jar`**, confirm the sha in `/var/opt/morpheus/morpheus-ui/plugins/` and a fresh log marker before testing.
- [ ] **Test on a real SCVMM** with: Gen1 and Gen2 images; an image with and without `hyperv-daemons`; a cluster with a CSV *and* a host-local volume (the QA lab is single-node with no CSVs — say so in the testing notes when a path is unit-tested only); a no-isolation VM network with a static pool; two Hyper-V hosts (switch/DHCP differences per host are common); instance/container/server ids that differ.
- [ ] Plan the live evidence: appliance log markers (`Determining HA`, `createHostRecord: granted`, `checkServerReady … attempt N/300`, `Flushing Pending Commands for Server`), VMM Jobs view in the same window (mind VMM local time), DB rows (`compute_server`, `network_pool_ip`, `datastore`), `GET /api/servers/<id>` for encrypted columns.
- [ ] Verify clocks and DNS on the appliance before attributing failures to your change.
- [ ] After plugin upload, re-run a cloud Refresh twice and check pool/server ids and leases if IPAM is involved.
- [ ] Clean up lab leftovers your tests create: temporary templates/hardware profiles, `morpheus_server_*\config.iso`, orphan `UnAssigned` grants, failed instances with `external_id NULL` (force delete).

## D. PR hygiene

- [ ] Title/subject keeps the `MORPH-xxxxx:` prefix (rebases have dropped it); no Jira numbers in code comments (reviewer preference); comments ≤ 2 lines.
- [ ] PR body: root cause (newcomer-readable), what changed, design decisions reviewers should weigh (with VMware/HVM/embedded comparison where relevant), testing with **actual log lines**, explicit "not in scope" list, and whether paths were unit-tested only. Replace the body, don't append.
- [ ] Commit as the repo persona (`ckeskar23`) via per-command env vars / SSH alias; never change shared `.git/config` in a worktree; the app token cannot push or edit those PRs.
- [ ] Published branches: `git merge origin/main`, no rebase/force-push (ADR-0016). State the merge order if other open PRs overlap and who takes the semantic fix-up.
- [ ] Squash-merged base PR? `git rebase origin/main` on the dependent branch drops the duplicate; verify `gh pr view` state before continuing.
- [ ] Add a row to [session-log.md](session-log.md) / [triage-playbook.md](triage-playbook.md) / a new ADR when the work changes a documented behaviour.
