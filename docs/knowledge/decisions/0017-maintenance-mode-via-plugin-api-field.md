# ADR-0017: Exclude maintenance-mode hosts by adding `ComputeServer.maintenanceMode` to plugin-api
- Date: 2026-10-19 · Status: proposed · Code: unmerged — plugin-core branch `MORPH-18569-add-maintenancemode-to-computeserver` (`6cf76863`, from `v1.5.x`), plugin branch `…MORPH-18569-exclude-maintenance-hosts…` (`dac7bded`, 25 tests) · Source: session "Morph-18569 analysis", MORPH-18569 / morpheus-ui #6457

## Context
A Hyper-V host in Maintenance Mode is still offered for placement (auto-pick ranks by free memory; dropdown and explicit host are unchecked) → SCVMM Error 10730. Embedded fixed it in morpheus-ui #6457 using the domain field `ComputeServer.maintenanceMode` (added in 2018 for KVM). `morpheus-plugin-api` (1.4.x–1.6.x) has no such field, only `ComputeServerType.hasMaintenanceMode`. Also found: `HostSync.updateMatchedHosts` only refreshes stats when cluster or OS changed, so memory/power ranking was stale forever.

## Decision
Option 1 — add `maintenanceMode` to plugin-core `ComputeServer` (morpheus-ui maps plugin model fields to the domain by name, so no UI change). Plugin: `HostSync` stores the flag from SCVMM status and refreshes every host's stats each sync; `getHostAndDatastore` rejects an explicit maintenance host with a clear error, skips them in auto-pick, and skips a datastore whose hosts are all in maintenance (beyond #6457, addressing its review finding); clones inheriting a maintenance host fail early; `scvmmHost` option source hides them. No Jira numbers in code comments.

## Alternatives considered
Option 2 — store the flag in a host config property and filter in memory (works on current API but not queryable; rejected by user). Checking maintenance only in `updateHostStats` (would never run for existing hosts).

## Consequences
Needs a plugin-core release and an appliance shipping it; CI fails until then. No runtime guard was added for older appliances — contrast ADR-0018.
