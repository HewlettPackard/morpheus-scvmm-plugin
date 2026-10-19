# ADR-0016: Published PR branches are updated by merge, not rebase; dependency-ordered merges; QA integration branch rebuilt from main
- Date: 2026-10-16 · Status: accepted (process) · Source: sessions "Combined network plugin", "PR dependency workflow", "Fix multi-nic PR"

## Context
Four networking PRs (#187 IPAM, #189 MAC/sync, #199 reconfigure NICs, #190 IP sync) plus #194/#201/#202 were open at once, touched the same methods (`getServerDetails` hashtable, `reserveIPAddress`, `syncInterfaces`) and had cherry-picked commits across them. One branch was rebased + force-pushed, after which the user forbade history rewrites on published branches. QA needed one build containing all fixes before reviewers finished.

## Decision
* Keep PR branches current with `git merge origin/main`; resolve conflicts inside the merge commit; remove duplicates introduced by earlier merges as plain follow-on commits; fast-forward pushes only.
* Make cherry-picked helpers self-contained (inline constants) so a PR compiles standalone regardless of merge order.
* Merge in dependency order (#189 → #199 → #187 → reconcile follow-up); whichever of two overlapping PRs merges second takes the semantic fix (e.g. collapse duplicate `NetworkAdapters` entries into one superset object).
* Freeze an out-of-draft PR's scope; put enhancements on a single follow-up branch instead of slicing.
* For QA builds, recreate an integration branch (`qa/ckeskar-main`) from `main` on every run, merge listed PRs in order, replay textual resolutions with `git rerere`, apply saved fixup patches for semantic overlaps, skip drafts, emit a manifest with branch names and commits. Never ship a combined build without compiling, running the full suite and diffing against a known-good combination.

## Alternatives considered
Stacking #187 on #189 (overlap was 3 lines); a long-lived integration branch with incremental merges (force-pushes and merges to main make it drift).

## Consequences
Linear-looking PRs are traded for stable published history. `git rerere` can auto-stage a stale "both sides" resolution — inspect every replay. Branch names: the app prefixes/truncates session branch names; repo branches must be named explicitly.
