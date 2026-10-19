# ADR-0018: Guard access to plugin-api model fields newer than `minVersion`
- Date: 2026-10-19 · Status: proposed (local commit `193b1b34`, draft comment for PR #196's author; #196 merged without the guard) · Source: session "Pull PR #196 and resolve conflicts", MORPH-17716 / MORPH-17806

## Context
PR #196 writes `ComputeServer.maxSockets` in `HostSync`. The field exists only from plugin-api 1.5.1 (MORPH-17806). `morpheus-plugin-api` is a `provided` dependency: compiling against 1.5.1-SNAPSHOT says nothing about the appliance's runtime classes; only `Morpheus-Min-Appliance-Version` (`minVersion=9.1.0`) gates loading, and it is unknown which appliance first ships 1.5.1. On an older appliance the first `maxSockets` access throws a missing-property error; because `HostSync` wraps its whole loop in one try/catch, all host updates stop and new hosts are never added.

## Decision (proposed)
`protected boolean supportsMaxSockets(server) { server?.hasProperty('maxSockets') != null }`; compute/set the field only when supported, in both `updateMatchedHosts` and `addMissingHosts`; spec cases for both paths. Longer term, raise `minVersion` to the first appliance shipping the field. Apply the same pattern to any new model field (e.g. `maintenanceMode`, ADR-0017).

## Alternatives considered
Rely on `morpheusApiVersion` bump (insufficient); raise `minVersion` now (target version unknown).

## Consequences
Degrades gracefully on old appliances. Single-socket lab hosts cannot distinguish a correct read from the fallback value 1 — unit tests must cover multi-socket. Test update paths by perturbing DB values (`max_sockets`) and refreshing rather than deleting hosts.
