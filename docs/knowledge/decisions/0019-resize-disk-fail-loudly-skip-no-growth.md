# ADR-0019: `resizeDisk` — skip when no growth is needed, detect checkpoints, fail provisioning loudly
- Date: 2026-10-14 · Status: proposed · Code: open PR #203 (draft, commit `7589749d`, `ScvmmApiServiceResizeDiskSpec`) · Source: sessions "Morph-946 triage", "Fetch logfile (MORPH-19193)", MORPH-946

## Context
`ScvmmApiService.resizeDisk` refuses when `$vhd.ParentDisk` is set ("Cannot Resize a Differencing Disk or a Disk with Checkpoints") but never checks checkpoints; `createServer` calls it for cloned OS/data disks and only `log.debug`s the result, so clones of VMs with checkpoints (which `-UseDiffDiskOptimization` turns into differencing disks) silently provision with the template disk size; the reconfigure caller reads `.error` while the method returns `errOut`. Embedded `dev-9.2` is functionally identical (nothing to port). Microsoft docs do not impose the differencing/checkpoint restriction — it is self-imposed.

## Decision
PowerShell: no-op `skipped=true` when requested size ≤ current `MaximumSize`; specific errors for differencing (`parent: X`) vs checkpoints (`Get-SCVMCheckpoint -VM` count). `createServer`: resize failure sets `rtn.errorMsg` and throws (provisioning fails with the SCVMM message). Reconfigure: fall back to `errOut`. Differencing guard in `getResizeConfig` unchanged.

## Alternatives considered
Drop `-UseDiffDiskOptimization` so SCVMM merges the chain (slower clones; needs SCVMM-side validation); warn and continue.

## Consequences
Clones that do not need growth now succeed; a clone requesting a **larger** disk from a check-pointed source now fails instead of silently keeping the old size (flagged in the MORPH-19193 session — on a QA appliance with 15-minute snapshot backups this is every clone). MORPH-946 itself was cancelled as "same as embedded"; the PR remains a draft.
