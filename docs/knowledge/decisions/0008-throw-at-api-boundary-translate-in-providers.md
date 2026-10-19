# ADR-0008: Error handling — throw typed exceptions at the WinRM boundary, translate once in providers, isolate sync workers
- Date: 2026-09-29 (rebased 2026-10-12) · Status: proposed · Code: open PR #198 (draft) — **not on `main`** · Source: sessions "Scvmm plugin architecture", "Error handling improvements"

## Context
Metrics on `main` (Sep 2026): 94 `catch (e)` blocks that log and return `[success:false]`, ~50 ad-hoc result maps with inconsistent keys (`error` / `errOut` / `msg` / `errorMsg`), `wrapExecuteCommand` swallowing JSON errors in an empty catch, `refresh()` marking the cloud `error connecting` for any exception in any worker and aborting the rest, `validate()` collapsing every cause to "Error connecting to scvmm", passwords logged at INFO in `runWorkload`, polling loops with bare timeout messages, and a `getServerDetails` that read `.message` instead of `.msg`.

## Decision
* New package `com.morpheusdata.scvmm.error`: `ScvmmException` + `ScvmmConnectionException`, `ScvmmCommandException`, `ScvmmResponseParseException`, `ScvmmJobFailedException`, `ScvmmTimeoutException`; `ScvmmCommandSanitizer` (redacts `-Password`/`-Credential`/`ConvertTo-SecureString`, `redactOpts`); `ScvmmKnownErrors` (single ordered regex table → code/category/userMessage/field); `ScvmmErrorTranslator` (Throwable → `ServiceResponse` or legacy map).
* `executeCommand`/`wrapExecuteCommand(cmd, opts, failOnError=true)` throw; ~35 best-effort cleanup callers (temp template removal, `deleteIso`, `setCdrom`, mkdirs, `removeDisk`, …) pass `failOnError=false`.
* Providers' catch blocks call the translator once; option sources show "Unable to load clouds: <cause>"; `validate()` flags `host`/`password` fields.
* `refresh()` runs the 10 workers via `runSyncWorkers`, records per-worker success/duration/error, sets `Cloud.Status.warning` with a summary on data failures and `offline` on connection loss.
* Polling messages state what was awaited, how long, how many attempts and the last observed state; VMM job `ErrorInfo` surfaced.

## Alternatives considered
Keep maps and enrich `msg` everywhere; split `ScvmmApiService` / typed opts (explicitly out of scope); disable TLS verification to get CI green during the Nexus cert outage (rejected).

## Consequences
Control-flow change for every `ScvmmApiService` caller (list methods now throw). Open risks: whether core honours `data.status = warning` from a non-success `refresh()`; the broad `winrm.auth` regex may misclassify file "Access is denied" errors. Conflicts with #187 in `ScvmmApiService`/`ScvmmCloudProvider` must be resolved by whichever merges second. Until merged, triage must assume the old swallowing behaviour.
