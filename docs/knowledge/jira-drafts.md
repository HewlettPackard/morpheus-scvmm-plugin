# Jira drafts — bugs confirmed on `main` with no PR (2026-10-19)

Draft tickets only. **Nothing here has been posted.** Each item was root-caused in a Copilot session and the offending code re-verified on `main` (`f5b8456a`) on 2026-10-19. Copy into Jira when ready; remove the entry (or replace it with the key) once filed. Project: MORPH. Suggested parent: MORPH-9194 "SCVMM Plugin - Final Issues List" where applicable.

---

## 1. Pool-backed NICs are never bound to an SCVMM static IP at provision time

* **Component:** SCVMM plugin — provisioning / networking
* **Summary:** When an instance is provisioned on a network backed by an SCVMM static IP pool, the plugin grants an address from the pool but creates the adapter with `-IPv4AddressType Dynamic` and never emits the `Set-SCVirtualNetworkAdapterConfiguration -IPv4Address -IPv4AddressPool` block. The grant stays `UnAssigned` in VMM, the guest gets the IP only via cloud-init, and the pool leaks one address per aborted provision.
* **Repro / evidence:** Provision a VM with a NIC on a pool-backed network (Static). Appliance log shows `Grant-SCIPAddress` but no `Set-SCVirtualNetworkAdapterConfiguration`; `Get-SCVirtualNetworkAdapter -VM … | ft IPv4AddressType, IPv4Addresses` shows `Dynamic`/empty; `Get-SCIPAddress -StaticIPAddressPool …` shows the grant `State=UnAssigned, AssignedToID=<empty>`. Observed over a full day of provisioning (7 VMs) with zero static blocks emitted. Embedded SCVMM has the same gates.
* **Affected code:** `ScvmmApiService.buildCreateServerCommands` — `doStatic = networkConfig?.doStatic` (deprecated top-level flag, always false for plugin provisioning); `doPool` uses `primaryInterface.poolType`, which `ScvmmProvisionProvider.runWorkload` derives from `network.pool.type.code` although core does not populate `network.pool` on the marshalled `Network`; `extraDoPool` additionally gated on the primary's `doStatic`.
* **Proposed fix:** Derive `poolType` from `interface.networkPool?.type?.code` (reload via `context.services.network.pool.get(id)` when `type` is missing) and `doStatic` from the per-interface flag for primary and extras independently. Implementation exists on PR #187 (`resolveInterfacePoolType`, commits `c0526217`, `73da7e53`, `d6fd1d5b`) — either land #187 or cherry-pick these commits separately. Add INFO diagnostics per NIC (`doStatic`, `poolType`, `doPool`, `poolId`).
* **Related:** MORPH-953, PR #187, ADR-0003.

## 2. `releaseIPAddress` throws `MissingPropertyException: No such property: errorData` on every static-IP VM delete

* **Component:** SCVMM plugin — networking / IPAM
* **Summary:** `ScvmmApiService.releaseIPAddress` reads `out.errorData`, a property that does not exist on `com.morpheusdata.model.TaskResult` (fields: `success, exitCode, output, error, data, msg`). Every `deleteHostRecord` for an SCVMM pool address therefore reports an error, and the "already released" case is never recognised.
* **Repro / evidence:** Delete an instance whose NIC had an SCVMM pool address (requires the IPAM provider from #187 to reach the method; on `main` the method is dead code). Log: `No such property: errorData for class: com.morpheusdata.model.TaskResult` at `ScvmmApiService.releaseIPAddress`.
* **Affected code:** `ScvmmApiService.releaseIPAddress` (`if (out.errorData?.contains("Unable to find the specified allocated IP address"))`).
* **Proposed fix:** Script `Get-SCIPAddress -VMMServer localhost -ID "<id>" -ErrorAction SilentlyContinue`; revoke only when present (`Revoke-SCIPAddress … -ReturnToPool $true`); return a `revoked` / `already-released` marker via `ConvertTo-Json`, treating both as success (VMM auto-returns Assigned grants when the VM is removed, before `deleteHostRecord` runs). Read failures from `out.error ?: out.output`. Implementation exists on PR #187 (`331f83ab`) with `ScvmmApiServiceReleaseIPAddressSpec`.
* **Related:** MORPH-953, PR #187, ADR-0004.

## 3. Clone pins the virtual switch of an arbitrary host (`Get-SCVirtualNetwork … | Select-Object -first 1`)

* **Component:** SCVMM plugin — provisioning / clone
* **Summary:** In the clone branch of the create script the plugin resolves the adapter's switch with `Get-SCVirtualNetwork -VMMServer localhost -Name $VirtualNetworkAdapter.VirtualNetwork | Select-Object -first 1`. `VirtualNetwork` objects are per-host vSwitches, so the first match may belong to a different host than the placement target; `New-SCVirtualMachine -VMHost <target>` then fails with "no connection found to selected network". Multi-NIC clone sources also fail because `Get-SCVirtualNetworkAdapter -VM $VM` is unindexed.
* **Repro / evidence:** Two Hyper-V hosts with identically named switches; clone a VM and force placement on the host that is not first in `Get-SCVirtualNetwork` output → clone fails; same clone succeeds on the other host. Introduced with commit `1dd9117d6` (pre-existing on `main`).
* **Affected code:** `ScvmmApiService.buildCreateServerCommands`, clone branch (`$VirtualNetwork = Get-SCVirtualNetwork … | Select-Object -first 1`).
* **Proposed fix:** `Get-SCVirtualNetwork -VMHost $vmHost -Name <switch>` (scope to the target host); index adapters `(Get-SCVirtualNetworkAdapter -VM $VM)[0]` or loop over all adapters; add `Exit 2x` guard when no switch is found; Spock case asserting `-VMHost` in the generated script.
* **Related:** session "Combined network plugin" (user intended to file a Jira; number unknown), ADR-0016.

## 4. Untagged (VLAN 0) network sites are skipped during network sync → synced network has no CIDR

* **Component:** SCVMM plugin — sync / networking
* **Summary:** For no-isolation logical networks, `listNetworks` iterates `Get-SCLogicalNetworkDefinition | where IsolationType -eq "None"` and skips any subnet-VLAN where `-not $vlan.Subnet -or -not $vlan.VLanId`. PowerShell treats VLAN ID `0` (untagged) as falsy, so untagged sites never produce a subnet; the Morpheus `Network` ends up with `cidr = null` and no `NetworkSubnet`, breaking static/manual IP assignment until the user edits the network by hand.
* **Repro / evidence:** SCVMM logical network with one network site `<subnet>/22`, VLAN 0 (`IsVlanEnabled False`). After cloud refresh the Morpheus network shows no CIDR; provisioning payload has `subnet:null`. Manual CIDR/GW/DNS edits on the parent Network survive refresh because `NetworkSync.updateMatchedNetworks` only rewrites subnets.
* **Affected code:** `ScvmmApiService.listNetworks` no-isolation branch (`if (-not $vlan.Subnet -or -not $vlan.VLanId) { continue }`, `IsVlanEnabled -eq $true` filter).
* **Proposed fix:** Use `$null -eq $vlan.VLanId` for the id check and do not require `IsVlanEnabled` for untagged sites; emit the site with `VLanID = 0`; ensure `NetworkSync` writes `cidr` for the resulting subnet/network. Add a Spock case on the generated script and a sync test with an untagged site.
* **Related:** session "Scvmm logical network connectivity"; architecture §2.4.

## 5. `VirtualMachineSync.syncVolumes` zeroes `server.maxStorage` and forces a save of every VM on every sync

* **Component:** SCVMM plugin — sync
* **Summary:** `syncVolumes` initialises `def maxStorage = 0` and passes it by value into `addMissingStorageVolumes` / `updateMatchedStorageVolumes`, so it is never accumulated. The following `if (server.maxStorage != maxStorage)` then sets `maxStorage = 0`, saves the stale server object and marks `changes = true`, which makes `updateMatchedVirtualMachines` `bulkSave` every VM on every refresh — log noise, wrong capacity figures, and a stale-save risk of the same class as MORPH-16592.
* **Repro / evidence:** Any cloud refresh with managed VMs: log `max storage changed for <vm> from 21474836480 to 0` and `updateMatchedVirtualMachines: save: true` for every VM each cycle.
* **Affected code:** `VirtualMachineSync.syncVolumes` and the two helpers it calls.
* **Proposed fix:** Compute `maxStorage` from the synced `server.volumes` after the `SyncTask` completes (sum of `maxStorage`), compare, and only save when it actually differs; reload the server before saving.
* **Related:** session "Instance health error"; ADR-0006.

## 6. `HostSync` breaks on appliances whose plugin API lacks `ComputeServer.maxSockets`

* **Component:** SCVMM plugin — sync / compatibility
* **Summary:** PR #196 (MORPH-17716) writes `ComputeServer.maxSockets`, a field that exists only from `morpheus-plugin-api` 1.5.1 (MORPH-17806). The API is a `provided` dependency, so on an appliance within the allowed range (`Morpheus-Min-Appliance-Version` 9.1.0) that ships an older API the first access throws a missing-property error; because `HostSync` wraps its whole loop in one try/catch, all existing-host updates stop at the first host and new hosts are never added.
* **Repro / evidence:** Install a build containing #196 on an appliance whose `morpheus-plugin-api` predates 1.5.1 and refresh the cloud; hosts stop updating; stack trace names `maxSockets`. Verified on a 9.1.2.110 appliance that the happy path works (`max_sockets` returned to 1 after DB perturbation).
* **Affected code:** `HostSync.updateMatchedHosts`, `HostSync.addMissingHosts`; `build.gradle` `minVersion`.
* **Proposed fix:** Either raise `minVersion` to the first appliance release that ships API 1.5.1 (version to be confirmed with the release owner), or add `protected boolean supportsMaxSockets(server) { server?.hasProperty('maxSockets') != null }` and guard both call sites (local commit `193b1b34` + 3 `HostSyncSpec` cases exist). Apply the same guard pattern to future model fields (e.g. `maintenanceMode`).
* **Related:** MORPH-17716, MORPH-17806, PR #196, ADR-0018.
