# ADR-0009: Gen1 NIC add/remove forces stop/start; resize result reflects errors; CLIXML cleaned
- Date: 2026-10-07 · Status: accepted · Code: merged #199 · Source: session "Combined network plugin", MORPH-15417

## Context
Reconfigure "add NIC" on a Generation 1 VM showed History **Completed** while SCVMM rejected the hot-add with Error 640 ("must be in Poweroff or Stored state"); `getResizeConfig` only considered CPU/memory/disk for `hotResize`, and `resizeWorkloadAndServer` set `rtn.error` and then unconditionally `rtn.success = true` (twice), leaving a phantom NIC row with `id:null` in the instance config. Error text was raw CLIXML. An earlier review (PR #182) had kept the unconditional success on purpose and drafted a reviewer question instead.

## Decision
`getResizeConfig` → `hotResize = false` when NIC add/remove and `generation != 'generation2'` (network reassignment stays hot on both generations). `resizeWorkloadAndServer` → `success = !error`. `ScvmmApiService.cleanPowerShellError` strips CLIXML for `addNetworkInterface`/`removeNetworkInterface`. Combined CPU/memory/volume/NIC changes get a single stop/start.

## Alternatives considered
Keep the pre-existing "always success" pattern (rejected once live evidence showed phantom rows).

## Consequences
History shows Failed with VMM's real message (verified live with Error 25246). Legacy servers lacking generation info force a stop.
