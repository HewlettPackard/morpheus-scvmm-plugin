---
name: scvmm-feature-planning
description: Plan, scope, implement, or review a feature or fix for the Morpheus SCVMM plugin — writing Jira stories/ACs, splitting PRs, checking embedded parity, assessing impact on provisioning/reconfigure/network/storage paths, test plans on real SCVMM, PR ordering and QA builds. Use when the user says "plan", "implement", "story", "acceptance criteria", "PR description", "test plan", or starts a MORPH-xxxxx feature.
---

# SCVMM feature planning & execution

## Load first
1. `docs/knowledge/feature-planning-checklist.md` — walk §A before scoping, §B during design, §C while implementing/testing, §D before the PR.
2. `docs/knowledge/architecture.md` — the sections your change touches.
3. `docs/knowledge/decisions/README.md` — do not re-decide settled items; supersede with a new ADR if you must.
4. `docs/knowledge/triage-playbook.md` — the area's known open bugs; your feature may fix or collide with them.

## Method
1. **Scope**: quote the user's request, list ACs observable on an appliance (log line / DB column / VMM state), name the files and methods, and state explicitly what is out of scope and which open PRs overlap (#187, #193, #198, #203 as of 2026-10-19 — re-check with `gh pr list`).
2. **Parity check**: read the embedded implementation and sibling plugins; decide match/exceed/diverge and write the reason (ADR-0014).
3. **Dependency check**: plugin-api fields and facets available at the appliance's runtime version; `minVersion`/`hasProperty` guards (ADR-0018); PR merge order and who takes semantic fix-ups (ADR-0016).
4. **Implementation**: Spock first for script builders and planners; keep helpers self-contained if they may be cherry-picked; follow the logging/error-key conventions in `.github/copilot-instructions.md`; add features to both `runWorkload` and `runHost` when applicable.
5. **Test plan**: unit tests + a numbered manual plan for a real SCVMM covering Gen1/Gen2, with/without KVP images, cluster with CSV and local volumes, no-isolation network with a pool, two hosts, drifted instance/container/server ids; include the exact log markers and VMM/DB checks to collect as evidence; state which paths are unit-tested only.
6. **Deliverables**: PR description (root cause / what changed / decisions for reviewers with VMware-HVM-embedded comparison / testing with real log lines / not in scope), optional QA retest steps, Jira AC text — all as drafts unless asked to post. Then update the knowledge base (checklist item, ADR, playbook row, session-log row).

## Guardrails
* Ask before changing behaviour that an ADR documents or that embedded relies on.
* Don't bundle unrelated fixes; don't rebase published branches; don't push or open PRs unprompted.
* Sanitize hostnames/IPs/credentials in anything committed.
