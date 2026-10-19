# ADR-0002: Stay on legacy `ResizeFacet`; plugin leases/releases SCVMM pool IPs itself on reconfigure
- Date: 2026-10-07 · Status: accepted · Code: merged #199 (`helper/ReconfigurePoolIpHelper`, `updateResizedInterfaces`, `addResizedInterfaces`, `removeResizedInterfaces`) · Source: sessions "Combined network plugin", "PR review and fix", MORPH-953 AC 12 / MORPH-18437

## Context
`ScvmmProvisionProvider` implements `WorkloadProvisionProvider.ResizeFacet` / `HostProvisionProvider.ResizeFacet`. On that legacy path core (`CloudPluginProvisioningService.resizeContainer`) hands the raw `ResizeRequest` to the plugin and does nothing else: no `ComputeServerInterface` rows are created or deleted and no IP-pool leases are granted or released (that only happens in `performSupplementedInstanceResize` for `ResizeV2Facet`). Core only emits an `interfacesUpdate` when network/subnet changes (never for an ipMode-only change) and drops primary-NIC deletes before they reach the plugin. Embedded SCVMM never bound pool IPs on reconfigure either.

## Decision
* Keep the legacy facet. Implement `reconfigureResizedInterfaces` = **remove → update → add**, refreshing the server between phases and stopping at the first failure; each phase returns a `ServiceResponse` so failures surface.
* The plugin creates/removes Morpheus NIC rows (`context.async.computeServer.computeServerInterface.create/remove(list, server)`), names them `eth<n>`, refuses to remove the primary NIC (stricter than HVM).
* `ReconfigurePoolIpHelper` leases from the SCVMM pool whenever the target network has an `scvmm` pool and `ipMode != 'dhcp'` (even without a user-chosen IP), writes a `NetworkPoolIp` exactly as core does (`refType=ComputeServer`, `refId=server.id`, `subRefId=iface.id`, gateway/netmask/dns from pool else network) so standard teardown (`releaseNetworkReservations` → `deleteHostRecord`) still works, releases old leases on success, rolls back on failure. Added NICs are created Dynamic first (`New-SCVirtualNetworkAdapter` has no `-IPv4Addresses`) then bound.
* Target network without an SCVMM pool → adapter set to `-IPv4AddressType Dynamic`, `iface.dhcp = true` (avoids SCVMM Error 15046). Never force Static.
* The helper is self-contained (own `SCVMM_POOL_TYPE_CODE`) so it compiles on `main` without PR #187; leasing is inert until the IPAM provider exists.

## Alternatives considered
* Migrate to `ResizeV2Facet` so core handles leases — rejected: large signature change, core would also take over volume handling.
* Interim fail-fast when `interfacesAdd/Delete` is non-empty — superseded by the full implementation.
* Fail the whole resize on NIC errors — initially kept as unconditional `success=true` per user, later changed (ADR-0009).

## Consequences
Pool leases work on reconfigure; ipMode-only changes remain unreachable (core limitation, documented in #199). Primary-NIC deletion guard is unit-test-only coverage. `MAX_NETWORKS` is not enforced on day-2 add.
