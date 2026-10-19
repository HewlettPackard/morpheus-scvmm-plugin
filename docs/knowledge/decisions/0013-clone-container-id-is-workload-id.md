# ADR-0013: `PrepareCloneInstanceResponse.cloneContainerId` is the source workload id; hard-fail when unresolvable
- Date: 2026-10-16 · Status: accepted · Code: merged #202 · Source: session "Fetch logfile (MORPH-19193)", MORPH-19193 / MORPH-9222

## Context
`prepareCloneInstance` (added by PR #180 for MORPH-9222) returned `opts.cloneInstanceId` — the **instance** id — although it already held `sourceWorkload`. Core passes the value straight into `provisionOpts.cloneContainerId`, which `runWorkload` resolves with `context.services.workload.get()`; embedded passes `firstContainer.id`. On fresh appliances instance and container ids coincide, masking the bug; on QA they had drifted (+2) → `workload.get()` null → NPE reported as "Error in determining host and datastore" (the enclosing catch).

## Decision
Return `sourceWorkload.id`; add an explicit guard in `runWorkload`: `if (!cloneContainer?.server) { setError("Unable to locate source workload <id> for clone"); return }`. Keep the MORPH-9222 `ResourceProvisionProvider` flow intact. Keep the PR strictly scoped; downstream findings (`UpdateFailed` VMs, `cloneParentCleanup`, swallowed `startServer`/`setCdrom`/`snapshotServer` errors) go to separate tickets.

## Alternatives considered
Resolve instance → first container inside `runWorkload` (breaks the core contract for other clouds); skip the datastore copy and continue (later clone steps need the same lookup).

## Consequences
Live-verified on QA. Lesson: test id-based contracts on an appliance where instance ≠ container ≠ server ids.
