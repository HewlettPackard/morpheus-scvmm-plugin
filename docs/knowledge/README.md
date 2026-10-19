# SCVMM plugin knowledge base

Distilled, maintained context for working on `morpheus-scvmm-plugin`: how the plugin is built, what has gone wrong before and why, which design decisions are settled, and what to check before shipping a change. It was seeded on 2026-10-19 from ~35 Copilot working sessions (Sep–Oct 2026) plus the repository history, and is meant to be updated with every non-trivial bug or feature.

| File | Use it when |
|---|---|
| [architecture.md](architecture.md) | You need to understand components, data flow, the SCVMM/WinRM/PowerShell interaction model, networking/IP-pool/storage/console models, or appliance-side debugging references. |
| [triage-playbook.md](triage-playbook.md) | A bug report or log arrives. Symptom → likely root cause → where to look → known fix/Jira/PR, grouped by area, plus a "first five minutes" checklist and an SCVMM error-code table. |
| [decisions/](decisions/README.md) | You are about to change behaviour and want to know whether it was already decided, why, and what was rejected (ADR-0001 … ADR-0020). |
| [feature-planning-checklist.md](feature-planning-checklist.md) | Writing a story, reviewing a design, implementing, testing on a real SCVMM, or opening a PR. |
| [session-log.md](session-log.md) | You want the provenance of a statement (which session, date, Jira, PR) or the list of gaps the mining could not resolve. |

Always-loaded summary for Copilot: [`.github/copilot-instructions.md`](../../.github/copilot-instructions.md). Task-specific entry points: `.github/skills/scvmm-bug-triage`, `scvmm-system-design`, `scvmm-feature-planning`.

## Conventions

* **Status words are load-bearing.** "Fixed (#n)" means merged to `main`; "Open PR #n" and "Unfixed" mean the code on `main` still has the problem. Re-check PR state before trusting an "open" entry — several were verified only up to 2026-10-19.
* **Mark uncertainty.** Anything inferred from a session but not re-verified against code is marked *(session)* or "uncertain"; do not upgrade it without evidence.
* **No secrets or lab identifiers.** Hostnames, IPs, domains, credentials and tokens from logs are replaced by placeholders. Keep it that way.
* **Cite.** New entries should name the Jira key, PR number, class/method, and the log line a reader can grep for.

## How to maintain it

1. **After a triage or fix**: add or update a row in `triage-playbook.md` (keep the symptom phrased as a user or log would see it); add a row to `session-log.md`; if the fix changed a documented behaviour, update `architecture.md`.
2. **After a design decision or a rejected approach**: add `decisions/NNNN-<slug>.md` using the template in `decisions/README.md`, link it from the index table, and date it. Supersede rather than rewrite old records.
3. **When a PR listed as open merges or closes**: flip the status in the playbook, the ADR header, and the session log in the same PR.
4. **Before a release**: walk `feature-planning-checklist.md` §D and the "Known parity gaps" in ADR-0014.
5. Keep `.github/copilot-instructions.md` short; it links here instead of duplicating.
