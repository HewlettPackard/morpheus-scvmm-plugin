# ADR-0003: IPAM provider uses a hidden per-cloud `NetworkPoolServer`; pool type code stays `scvmm`
- Date: 2026-10-05 · Status: accepted · Code: open PR #187 (`ScvmmIPAMProvider`, `helper/NetworkPoolServerHelper`, `IpPoolsSync` binding) — **not on `main`** · Source: session "Hello (MORPH-953 review)", PRs #58/#61/#62/#187

## Context
PR #187 originally ported the embedded `ScvmmNetworkPoolService` as an `IPAMProvider` with **no** `NetworkPoolServer`. The appliance dispatches plugin IPAM through `pool.poolServer`: `PluginNetworkPoolService.createHostRecord/deleteHostRecord` → `resolvePoolServer(pool)` → null → "Unable to locate pool server for network pool". `NetworkPoolService.getService(type)` returns `pluginNetworkPoolService` unconditionally for plugin pool-server types, but `PluginManagerService` only fills `NetworkPoolType.poolService` when empty — upgraded appliances keep the dead embedded bean name for the `scvmm` type. Unit tests passed because they called the provider directly. PR #58 had used a hidden pool server (reverted by #61 without a stated reason) and wrongly used type code `scvmm-plugin-ipam`.

## Decision
* `NetworkPoolServerHelper.ensurePoolServer(cloud)` creates one `NetworkPoolServer` per cloud (`internalId scvmm-ipam.<cloudId>`, name "SCVMM IP Pools - <cloud>", type `scvmm-ipam`, `visible=false`, `creatable=false`) on every `ScvmmCloudProvider.refresh`; `deleteCloud` removes it.
* `IpPoolsSync` binds synced pools (`poolServer`, `parentType='NetworkPoolServer'`, `parentId`) and re-parents embedded-created pools (upgrade path, no migration).
* Pool type code stays **`scvmm`** so `poolType == 'scvmm'` checks and existing pools keep working.
* `ScvmmIPAMProvider` lifecycle methods return success for the hidden server (avoid hourly error noise); `createHostRecord` honours a user-chosen IP via `Grant-SCIPAddress -IPAddress` (an improvement over embedded); cloud resolved from `pool.refType/refId` with internalId fallback (SDK `NetworkPoolServer` has no `refType/refId`).

## Alternatives considered
* Appliance-side dispatch by `NetworkPoolType` — out of the plugin's control; embedded removal timeline.
* Keep #187's design — does not work in a real appliance.

## Consequences
No UI change. Any state parented to the hidden server is destroyed on plugin upload (see ADR-0015). Merge order with #189/#199 mattered because all three touched `getServerDetails` / `reserveIPAddress`.
