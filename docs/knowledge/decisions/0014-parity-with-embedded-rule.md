# ADR-0014: Parity rule with embedded SCVMM
- Date: 2026-10-07 (pool↔network autolink reverted), 2026-10-15 (audit rule), reaffirmed 2026-10-18 · Status: accepted (process) · Source: sessions "Hello (MORPH-953)", "Reconfigure dialog volumes", "Issue triage (MORPH-15705)"

## Context
Embedded SCVMM in morpheus-ui is being deprecated in favour of this plugin; the motto is "plugin code should have parity with embedded (and more)". Several sessions discovered that parity can mean inheriting embedded bugs (embedded never bound pool IPs either; `resizeDisk` guard identical; `checkServerReady` polling storm), that some embedded fixes are in shared code the plugin already benefits from (MORPH-18907 `_multi_disk*.gsp`, console tunnel fixes), and that some plugin "improvements" silently changed behaviour (auto-linking no-isolation pools to networks via `LogicalNetworkID`).

## Decision
1. Any fix landing in **SCVMM-specific** morpheus-ui code (`clouds/scvmm`, `ScvmmComputeUtility`, `ScvmmProvisionService`, `ScvmmComputeService`) must be reproduced in the plugin; fixes in common morpheus-ui files need no port.
2. When the plugin behaves like embedded but embedded is wrong, decide **explicitly** whether to match or exceed, and say so in the PR (e.g. pool-IP binding at provision exceeds embedded; pool↔network autolink was reverted to parity on the user's ruling "if it never happened in embedded we don't need to change that behaviour").
3. Audit status legend for parity tables: ✅ ported · 🔀 on an unmerged branch · ❌ gap · ⚠️ needs decision · ➖ common code / N/A.
4. Before trusting "embedded does X", read the embedded source (`dev-9.2`/`rc-9.1.x`), not the ticket's description — tickets have carried KVM root causes and stale file paths.

## Known parity gaps at the time of writing
MORPH-15807/17203 restart `waitForPowerState` + force-stop; MORPH-15561 network-name fallback in `resolveNetworkForAdapter`; MORPH-18569 maintenance hosts (ADR-0017); MORPH-9219/10356 blank-VM provisioning (product decision); `runHost` console fields (MORPH-16994).

## Consequences
Keeps PR scope honest and reviewable; prevents both regressions and accidental behaviour changes. Requires access to the morpheus-ui checkout during triage.
