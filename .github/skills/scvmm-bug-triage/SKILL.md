---
name: scvmm-bug-triage
description: Triage a bug, Jira ticket, QA report, or appliance log for the Morpheus SCVMM plugin. Use whenever the user shares a MORPH-xxxxx ticket, a morpheus-ui log, an SCVMM error (10730, 15046, 640, 2912…), or symptoms like "waiting for network", "agent not installed", "health error", "clone failed", "console fails", "datastore missing", "IP not assigned".
---

# SCVMM bug triage

## Load first
1. `docs/knowledge/triage-playbook.md` — run §0 "first five minutes", then find the symptom row in §1–§9.
2. `docs/knowledge/architecture.md` — the section for the affected area (provisioning §3, reconfigure §4, networking §5, storage §6, console §7, appliance references §9).
3. `docs/knowledge/decisions/README.md` — check whether the behaviour is a documented decision before calling it a bug.

## Method
1. **Establish facts before hypotheses**: deployed plugin sha (`/var/opt/morpheus/morpheus-ui/plugins/`), appliance version, appliance clock, the exact log window, the VMM Jobs view, the stack line (not the wrapping message). Ask for the log/attachment if you only have a ticket summary — Jira attachments cannot be fetched by tooling.
2. **Classify**: plugin regression · inherited from embedded (compare morpheus-ui `clouds/scvmm`) · core (morpheus-ui common code) · environment (DNS, NTP, KVP/`hyperv-daemons`, vSwitch/DHCP per host, VMM `UpdateFailed`, library VHD `State=Missing`, Windows Update on the VMM server) · QA procedure (wrong build line, `df` vs `lsblk`, `ps | grep agent`).
3. **Match against the playbook**. If the row says *Fixed (#n)*, verify the deployed sha contains it before re-investigating. If *Open PR* / *Unfixed*, say so and point at the branch/commit.
4. **Produce evidence-backed output**: timeline (UTC), root cause with class/method, embedded comparison, plugin-vs-environment verdict with confidence, next step (retest instructions, proposed fix scope, or Jira comment draft). Do not propose code until the investigation is complete unless asked.
5. **Keep scope tight**: one root cause per PR; downstream findings become separate items.
6. **Close the loop**: add/update the playbook row and a `session-log.md` entry; if a design question surfaced, draft an ADR.

## Guardrails
* Never paste tokens or lab hostnames/IPs into repo files; sanitize log excerpts.
* Don't post to Jira/GitHub or push unless explicitly asked — draft instead.
* "Behaves the same as embedded" is a classification, not an exoneration.
