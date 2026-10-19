# ADR-0005: Datastores use `assignedZonePools` + `externalType`; `zonePool` stays null
- Date: 2026-09-17 · Status: accepted · Code: merged #192 · Source: session "Resource pool datastore filtering", MORPH-14720 / MORPH-7093 / MORPH-8333 / MORPH-11752

## Context
Users wanted the datastore dropdown filtered by the selected resource pool (SCVMM cluster) and restored to the full list when deselected. Core `DatastoreService.selectableDatastores` already does this **if** datastores carry `assignedZonePools` (many-to-many) and `zonePool` is null: no pool → `isNull('zonePool')`; pool → `inList('assignedZonePools.id')`; then the plugin veto `CloudProvider.filterDatastores`. The plugin set `zonePool = cluster` (hiding CSVs when no pool was chosen), offered host-local volumes for every pool, mapped CSV→cluster by substring match, imported volumes from every cluster regardless of `hostGroup`/`cluster`, accepted only numeric pool ids in the host option source, and emitted two backslashes in the host-group PowerShell path.

## Decision
`DatastoresSync` imports only volumes from hosts `HostSync` synced for the cloud (no hosts ⇒ no filtering), computes `assignedZonePools` by exact shared-volume membership disambiguated by the reporting host's cluster, sets `externalType` (`clustered-shared-volume` / `host-volume`) as the CSV marker, clears `zonePool`. `RegisteredStorageFileSharesSync` derives pools from `ClusterAssociations`/`HostAssociations`. `ScvmmCloudProvider.filterDatastores` implemented. `ScvmmOptionSourceProvider.parseResourcePoolId` accepts `pool-<id>`, bare ids, `poolGroup-*`, externalIds, and `pool-` → null. Build on PR #191, supersede #183, one squashed commit.

## Alternatives considered
Keep `zonePool` and patch the UI (UI PR #3441, embedded-only); fix "deselect doesn't restore" in the plugin (impossible — it is a morpheus-ui wizard bug: `value="pool-"`, `multidisk.js` reload only on lookup success, `InstancesController.split('-')[1]`; fixed separately in morpheus-ui PR #5990).

## Consequences
Existing datastores migrate on the next sync. `zonePool` must never again be used as a generic marker. The HA rule that still used `isClone` was exposed by this change (ADR-0001). SCVMM is the only provision type with `hasZonePools && !zonePoolRequired && hasDatastore`, which is why the UI bug only showed there.
