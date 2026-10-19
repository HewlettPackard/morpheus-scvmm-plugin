# ADR-0012: Clone flow — split ISO cleanup, reset the source's cloud-init cache, build the source ISO from its own config
- Date: 2026-09-15 (ISO cleanup), 2026-10-18 (cache reset, parity pass) · Status: accepted by user · Code: open PR #193 (commits `8354bab4`, `ee7de736`, `d2cc77c0`; lab-verified; not pushed at last session) — **not on `main`** · Source: session "Issue triage (MORPH-15705)", also "Fetch logfile (MORPH-19193)"

## Context
Cloned instances never showed the **Cloud-Init** History step and later turned out to connect with the parent's apiKey. Three stacked plugin defects: (1) `cloneParentCleanup` (in `runWorkload`'s `finally`) deleted `deleteDvdOnComplete.deleteIso` — the **clone's** ISO — ~46 s after power-on (`Remove-SCISO -Force` detaches it) while the parent's re-created ISO (`cloneBaseResults.cloudInitIsoPath`) was never consumed; (2) embedded resets the source guest's cloud-init cache before cloning (`rm -fr /etc/cloud/cloud.cfg.d/99-manual-cache.cfg /var/lib/cloud/sem/* /var/lib/cloud/instance /var/lib/cloud/instances/*; sync`) because core writes `manual_cache_clean: True` — the plugin never ported it, so clones never re-ran cloud-init; (3) `importPhysicalResource` returned a GString that Jackson persisted as `{"values":…}` so finalize's `Remove-SCISO` matched nothing on **every** provision (library ISO leak). Embedded also ejects the source's DVD never, while the plugin ejected it 16 s after restart and forced an SSH agent install on clones.

## Decision
* Parent ISO → `cloneParentCleanup` via `cloneBaseResults`; clone ISO → `finalizeWorkload` (after network wait), matching embedded timing.
* `prepareCloneInstance` runs embedded's exact cache-reset command in the source guest (`context.executeCommandOnServer(server, cmd, rpc=false, …)`), best-effort, only for Linux + cloud-init + powered-on sources.
* The source's re-mounted ISO is built from the source's **own** stored `cloudConfigUser/Meta/Network` (fallback: workloadRequest) — otherwise the source would adopt the clone's identity after its cache reset.
* Source DVD eject/ISO delete deferred to `finalizeWorkload` on success (`workloadConfig.cloneParentDvdCleanup`), immediate only on failure.
* Clones follow agent mode: `installAgent = false` when cloud-init will install it (avoid double install).
* `importPhysicalResource` returns `.toString()`; finalize logs eject/delete failures.

## Alternatives considered
SDK `CloudInitUtils.disableCloudInitCache` (has an inverted `equals("windows")` check and different command — rejected); remove parent-side cleanup entirely for strict parity (recommended in the MORPH-19193 session as the "words not code" option); keep forced SSH agent install on clones.

## Consequences
Clone and parent each phone home with their own apiKey (verified on lab); the source gets a second Cloud-Init History event (expected, same as embedded). Fresh provisions now really delete their ISO; stale `morpheus_server_*\config.iso` need a one-time manual cleanup. Cold or Windows sources skip the cache reset by design.
