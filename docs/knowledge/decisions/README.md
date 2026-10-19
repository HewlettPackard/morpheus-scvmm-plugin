# Decision records

ADR-style notes for design decisions and rejected approaches recovered from the Copilot sessions. Each record states the date it was taken, the source session (see [../session-log.md](../session-log.md)), and whether the resulting code is **merged**, on an **open PR**, or **unmerged/proposed** as of 2026-10-19. Re-check the PR state before relying on an unmerged decision.

| # | Decision | Date | Status |
|---|----------|------|--------|
| [0001](0001-derive-ha-from-storage.md) | HA placement is derived from cluster + every disk's storage, never assumed | 2026-10-16 | merged #201 |
| [0002](0002-plugin-side-pool-ip-leasing-on-reconfigure.md) | Stay on legacy `ResizeFacet`; plugin leases/releases SCVMM pool IPs itself on reconfigure | 2026-10-07 | merged #199 |
| [0003](0003-hidden-per-cloud-network-pool-server.md) | IPAM provider uses a hidden per-cloud `NetworkPoolServer`, pool type code stays `scvmm` | 2026-10-05 | open PR #187 |
| [0004](0004-grant-to-adapter-bind-without-ipv4addresspools.md) | Grant pool IP to the adapter, bind with `-IPv4Addresses` only | 2026-10-14 | merged #199 |
| [0005](0005-datastore-assigned-zone-pools.md) | Datastores use `assignedZonePools` + `externalType`; `zonePool` stays null | 2026-09-17 | merged #192 |
| [0006](0006-reload-before-save-compute-server.md) | Re-fetch `ComputeServer` before any save that follows a long poll | 2026-09-29 | merged #197 |
| [0007](0007-console-credentials-and-hypervisor-console-facet.md) | Hypervisor creds only on `console*` fields, FQDN `consoleHost`, implement `HypervisorConsoleFacet` | 2026-09-16 / 2026-10-12 | merged #194 |
| [0008](0008-throw-at-api-boundary-translate-in-providers.md) | Error handling: throw typed exceptions at the WinRM boundary, translate once in providers, isolate sync workers | 2026-09-29 | open PR #198 |
| [0009](0009-gen1-nic-changes-force-stop-and-honest-resize-result.md) | Gen1 NIC add/remove forces stop/start; resize result reflects errors; CLIXML cleaned | 2026-10-07 | merged #199 |
| [0010](0010-max-networks-eight.md) | `getMaxNetworks()` = 8 as a documented constant | 2026-10-02 | merged #184 |
| [0011](0011-bind-interfaces-at-provision-and-adopt-in-sync.md) | Stamp adapter identity at provision time; sync adopts unclaimed adapters; VM JSON depth 5 | 2026-09-14 | merged #189, #190 |
| [0012](0012-clone-cloud-init-parity.md) | Clone flow: split ISO cleanup, reset source cloud-init cache, source ISO from its own config | 2026-09-15 / 2026-10-18 | open PR #193 |
| [0013](0013-clone-container-id-is-workload-id.md) | `PrepareCloneInstanceResponse.cloneContainerId` is the source workload id; hard-fail when unresolvable | 2026-10-16 | merged #202 |
| [0014](0014-parity-with-embedded-rule.md) | Parity rule with embedded SCVMM: match SCVMM-specific fixes, skip common-code fixes, decide explicitly when to exceed | 2026-10-07 / 2026-10-15 | process |
| [0015](0015-reconcile-pool-leases-from-scvmm.md) | Rebuild `NetworkPoolIp` rows from `Get-SCIPAddress` on every pool sync (plugin upload wipes them) | 2026-10-14 | unmerged branch |
| [0016](0016-merge-not-rebase-and-pr-ordering.md) | Published PR branches are updated by merge, not rebase; dependency-ordered merges; QA integration branch rebuilt from main | 2026-10-16 | process |
| [0017](0017-maintenance-mode-via-plugin-api-field.md) | Exclude maintenance-mode hosts by adding `ComputeServer.maintenanceMode` to plugin-api | 2026-10-19 | unmerged |
| [0018](0018-runtime-guard-for-new-plugin-api-fields.md) | Guard access to plugin-api model fields newer than `minVersion` (`hasProperty`) | 2026-10-19 | proposed |
| [0019](0019-resize-disk-fail-loudly-skip-no-growth.md) | `resizeDisk`: skip when no growth needed, detect checkpoints, fail provisioning loudly | 2026-10-14 | open PR #203 |
| [0020](0020-no-auto-format-of-reconfigure-disks.md) | Reconfigure-added disks are attached raw; the plugin does not format or mount them | 2026-09-29 | behaviour confirmed |

Template for new records:

```
# ADR-NNNN: <title>
- Date: YYYY-MM-DD · Status: proposed | accepted | superseded · Code: merged #n / open PR #n / unmerged · Source: <session / Jira>
## Context
## Decision
## Alternatives considered
## Consequences
```
