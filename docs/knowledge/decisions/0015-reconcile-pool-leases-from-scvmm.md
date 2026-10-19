# ADR-0015: Rebuild `NetworkPoolIp` rows from `Get-SCIPAddress` on every pool sync
- Date: 2026-10-14 · Status: proposed · Code: unmerged branch `MORPH-953-pool-lease-reconcile` (stacked on PR #187; `PoolIpReconciler`, `IpPoolsSync.reconcilePoolIps`, `listPoolIPAddresses`, `ensurePoolServer` WARN+retry, `LogicalNetworkID` network fallback) · Source: session "Combined network plugin"

## Context
Uploading a new plugin jar is `plugins#delete` + install. Core deletes everything the plugin registered, including the `NetworkPoolServerType scvmm-ipam`, which cascades app-side to the hidden `NetworkPoolServer` → its `NetworkPool`s → all `network_pool_ip` rows, and nulls `network.pool_id` (FKs are plain RESTRICT). The first refresh after upload fails with `ValidationException NetworkPoolServer.type rejected value [null]`; the second recreates server and pools with new ids and zero leases. Every plugin upgrade therefore orphans every lease while VMM still shows the grants `Assigned`. A core change ("don't delete plugin pool servers on upgrade" / "route IPAM by pool type") was judged unobtainable.

## Decision
Plugin-side reconciliation: after each pool sync, list VMM grants (`Get-SCIPAddress -StaticIPAddressPool`) and plan against Morpheus rows — match by grant id then address; attribute to the server/NIC owning the address (adapter id → interface); restore lost rows; back-fill externalId; drop rows whose grant is gone; surface unattributed grants as `reserved`; never move a lease. `ensurePoolServer` retries with WARN instead of ERROR. Link no-isolation pools to a network when exactly one VM network is bound to the pool's logical network; ambiguous → leave to the user.

## Alternatives considered
Stop parenting pools to the hidden server (rejected — core needs `poolServer` for IPAM routing); back the hidden server with an `AccountIntegration` (unverified); core tickets.

## Open question
Should this branch be folded into PR #187 before it merges, or stay a separate follow-up PR? Unanswered as of 2026-10-19.

## Consequences
Upgrades self-heal within two refreshes; pool/server DB ids still churn (instance configs referencing old pool ids were not verified). Design plugin-owned state to be rebuildable from the cloud.
