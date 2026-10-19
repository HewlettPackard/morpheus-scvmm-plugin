# ADR-0007: Hypervisor credentials only on `console*` fields, FQDN `consoleHost`, implement `HypervisorConsoleFacet`
- Date: 2026-09-16 (port), 2026-09-21 (credential helper), 2026-10-12 (facet) · Status: accepted · Code: merged #194 · Source: sessions "Embedded plugin parity", "SSH credential failure", "Scvmm jira debugging", MORPH-12539 / MORPH-11002 / MORPH-18938

## Context
Embedded SCVMM fixes for the Hypervisor Console (UI #5138 FQDN `consoleHost`, #5251 keep `DOMAIN\` in `consoleUsername`) had not been ported. Worse, the plugin port had three plugin-only divergences: sync wrote the cloud credential into the guest's **`sshUsername`** (embedded wrote `consoleUsername`) every ~7.5 min on console-enabled clouds — breaking Install/Upgrade Agent and Convert-to-Managed for every Linux VM (`Auth fail` as `Administrator`); provision-time and discovered-VM console blocks were commented out (console only worked after an Edit → Save); and the block had been added, removed and re-added three times (`eb4bfc36`, `97faec30`, #173). Core 9.1.2 also calls `ProvisionProvider.HypervisorConsoleFacet.updateServerHost()` on every console connect and logged `does not implement … HypervisorConsoleFacet`.

## Decision
* `MorpheusUtil.getConsoleHost(server)` = `hostname ?: name` (FQDN first); `MorpheusUtil.getConsoleUsername/getConsolePassword(cloud)` return the credential untokenized (full `DOMAIN\user`); used in `VirtualMachineSync` (`onAdd` and `onUpdate`) and `runWorkload` (placed **after** the `server` re-fetch so the fields persist).
* Guard comments + specs: hypervisor creds go only to `consoleType/Host/Port/Username/Password`; `sshUsername/sshPassword` are user-owned.
* Implement `HypervisorConsoleFacet`: `updateServerHost` (WinRM `HostId` → fix `parentServer`/`consoleHost`), `enableConsoleAccess` (brownfield VMs on `enableVnc` clouds), shared `refreshServerPlacement` (also used by the MORPH-16592 placement path). Legacy facet, not V2, because that is what core 9.1.2 casts to.
* The fix deliberately does **not** self-heal already-clobbered `sshUsername` values.

## Alternatives considered
Weaken FQDN back to short names to make a lab work (rejected — the lab failure was appliance DNS); backport MORPH-11002 to 9.1 immediately (deferred — FreeRDP accepts `DOMAIN\user`); self-healing (rejected — cannot know the right guest user).

## Consequences
Guest SSH user no longer clobbered; console fields present from first provision; migrated VMs reconnect without waiting for sync (core still reuses its in-memory `server` on the first click). Existing servers need a one-time credential correction. `runHost` still does not set console fields (MORPH-16994 follow-up).
