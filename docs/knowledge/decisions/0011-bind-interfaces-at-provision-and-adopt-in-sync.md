# ADR-0011: Stamp adapter identity at provision time; sync adopts unclaimed adapters; VM JSON depth 5
- Date: 2026-09-14 (two branches 2026-09-15; adoption hardened 2026-10-07) · Status: accepted · Code: merged #190 (MORPH-4114) and #189 (MORPH-16995) · Source: sessions "Morph-16995 root cause", "Scvmm log failure analysis", "Combined network plugin"

## Context
Provision-created `ComputeServerInterface` rows had no `externalId`/MAC because `getServerDetails` returned no adapter data. `VirtualMachineSync.syncInterfaces` matched only by `externalId`, so it tried to delete those rows with the single-arg `remove(List)` (which does not unlink the `compute_server_compute_server_interface` join table → FK error every ~7.5 min, silently logged) and created duplicates. Independently, `ConvertTo-Json -Depth 3` collapsed per-NIC address arrays into strings that Groovy iterated per character, producing garbage `NetAddress` rows (the "fake IPv6" Network tab). plugin-api's deprecated `setIpv6Address` writes `this.ipAddress` into the IPv6 entry.

## Decision
* `getServerDetails` returns `NetworkAdapters[]` (ID, MacAddress, SlotId, IPv4/IPv6 addresses, VLanID, VirtualNetworkId, Enabled) and `HostId`; `saveAndGetNetworkInterface` picks the primary adapter (`findPrimaryNetworkAdapter`: IP → SlotId 0 → first) and sets `externalId`, `macAddress`, `vlanId`.
* `syncInterfaces` adopts unclaimed adapters by externalId → IP → MAC → primary/slot 0 → position (`matchUnclaimedAdapters`), names new rows `eth<n>` (`nextInterfaceName`), back-fills externalId/MAC, removes with `remove(list, server)` and checks the boolean.
* `VM_REPORT_JSON_DEPTH = 5` for VM reports; `normalizeAddresses` tolerates legacy collapsed strings; `buildNetAddresses` builds the `addresses` list directly (no deprecated setters).
* Delivered as two independent branches off `main` (user request), each self-contained; the second to merge removed the duplicated address helper in favour of `normalizeAddresses` (reviewer feedback).

## Alternatives considered
Only fix sync adoption (MAC would still be missing until the first sync); keep deprecated setters.

## Consequences
MAC visible immediately; no more FK churn or duplicate NICs on new builds; existing VMs self-heal via adoption. Both PRs added per-adapter objects to the same PowerShell hashtable as #187 — the combination needs a superset object (see ADR-0016).
