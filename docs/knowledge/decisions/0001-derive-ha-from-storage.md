# ADR-0001: HA placement is derived from cluster + every disk's storage, never assumed
- Date: 2026-10-16 · Status: accepted · Code: merged #201 (`resolveHighlyAvailable`, `isClusterStorage`, `Exit 28`) · Source: sessions "MORPH-19186", "Greeting (VMLocation/HA)", MORPH-19186, MORPH-909

## Context
`runWorkload` passed a hard-coded `isClone = true` into `getHostAndDatastore` (introduced by PR #135 for MORPH-909 with no documented rationale; at the time CSVs often had a null `zonePool`, so clones were forced HA). PR #192 widened datastore selection to host-local volumes under a cluster and rewrote the HA rule to `isClone || isClusteredSharedVolume(datastore)` but did not audit callers, so every cluster provision got `-HighlyAvailable $true` even with the boot disk on `C:\`. SCVMM cannot place an HA VM on non-cluster storage → placement silently failed (`$ignore = Update-SCVMConfiguration`) → `New-SCVirtualMachine` Error 10730. VMware derives HA from cluster `dasConfig`; HVM uses an explicit `PlacementStrategy`; neither has a per-VM "force HA" flag.

## Decision
* Remove `isClone`; `highlyAvailable = clusterId && every volume (root + data) isClusterStorage(datastore)` where cluster storage = `externalType` `clustered-shared-volume` or `clusterFileShare` (legacy `zonePool != null` fallback kept).
* Clones use the same rule (cloned volumes carry the source datastores).
* Mixed cluster + local disks under a pool, or local disks resolving to different hosts, is a **validation error** (stricter than VMware/HVM, which degrade silently — SCVMM would fail later with an opaque error).
* Data disks are pinned to the root disk's host when local.
* `if (-not $?) { Write-Error "VM placement failed on host … (HighlyAvailable=…, VolumePath=…)"; Exit 28 }` after both `Update-SCVMConfiguration` lines.
* `RegisteredStorageFileSharesSync` tags shares `clusterFileShare` / `hostFileShare` so cluster SMB shares stay HA-capable.
* Keep #192's auto-datastore ordering (most free space, type-agnostic) and require an explicit resource pool for HA (no cluster inference from a chosen host).
* `runWorkload` surfaces `e.message` into `server.statusMessage` / `provisionResponse`.

## Alternatives considered
* Minimal fix `isClone = opts.cloneContainerId ? true : false` (written, uncommitted, in the earlier session) — superseded.
* Derive clone HA from the source VM's `IsHighlyAvailable`.
* Prefer CSVs in auto selection; restrict selection to CSVs under a cluster — rejected (diverges from peers).
* Mixed storage → non-HA + warning — rejected by user; left as reviewer option.
* Always pass `-VMLocation/-PinVMLocation` — proposed in the earlier session, not adopted.

## Consequences
Local volumes under a pool yield valid non-HA VMs (the ticket case). The #192 spec "clones in a cluster are always HA" was inverted. Pool + CSV → HA and the mixed-storage rejection were unit-tested only (QA lab has a single-node cluster and no CSVs). A user-facing HA toggle (`canManagePlacement`) remains a possible future feature.
