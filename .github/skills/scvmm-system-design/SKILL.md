---
name: scvmm-system-design
description: Explain, review, or evolve the design of the Morpheus SCVMM plugin — components, data flow, how it plugs into morpheus-plugin-core/morpheus-ui, SCVMM/WinRM/PowerShell interaction, networking/IP pools, storage/datastores, console, refactoring proposals, or updating the design doc. Use for "how does X work", architecture questions, design reviews, and ADR writing.
---

# SCVMM system design

## Load first
1. `docs/knowledge/architecture.md` — full component, data-flow and model notes (read all of it for design work).
2. `docs/knowledge/decisions/README.md` and the ADRs relevant to the area — settled decisions and rejected alternatives.
3. For deeper diagrams: draft PR #195 (`docs/design/scvmm-plugin-design.md`) if present on the branch; otherwise the code (`ScvmmPlugin`, `ScvmmCloudProvider.refresh`, `ScvmmProvisionProvider.runWorkload`, `ScvmmApiService.buildCreateServerCommands`).

## Method
1. **Verify claims against code** (`grep`/`view` the class) before stating them; several design-doc statements were wrong until checked. Note what is *(session)*-only.
2. **Trace the core side** when the question involves dispatch (`CloudPluginProvisioningService`, `PluginNetworkPoolService`, `DatastoreService.selectableDatastores`, `GuacamoleTunnelBuilder`, `MonitorCheckService`). Unit tests passing does not prove core can reach the provider.
3. **Compare with embedded SCVMM and sibling plugins** (VMware, HVM/KVM, Nutanix) when proposing behaviour; state parity vs improvement explicitly (ADR-0014).
4. **Respect known constraints**: legacy `ResizeFacet`, whole-model saves, `provided` plugin-api vs appliance runtime, plugin upload destroying plugin-owned state, PowerShell/JSON quirks, Gen1 vs Gen2 limits.
5. **Record the outcome**: new or superseded ADR (`docs/knowledge/decisions/NNNN-slug.md`, add to the index), and update `architecture.md` if a model/flow changed. Use Mermaid for diagrams; avoid `<…>` and backslashes in node labels.

## Known improvement backlog (from the architecture session)
Error handling consolidation (PR #198), command-executor interface + fake for tests, unified controller resolver, splitting `ScvmmApiService` by domain, PowerShell templates instead of string concatenation, a common sync-worker base with per-worker reports, typed accessors for `configMap` state, centralising magic strings in `ScvmmConstants`.
