# ADR-0006: Re-fetch `ComputeServer` before any save that follows a long poll
- Date: 2026-09-29 (tests 2026-10-05) · Status: accepted · Code: merged #197 · Source: sessions "Scvmm add-node agent install", "Platform detection issue", MORPH-16592 / MORPH-18672 / MORPH-18715

## Context
`ScvmmProvisionProvider.getServerDetails` loaded the server, blocked for minutes in `checkServerReady`, then saved that stale object via `MorpheusUtil.saveAndGetMorpheusServer` (`bulkSave`). Plugin saves go through `MorpheusComputeServerImplService.prepareForSave` → `AbstractMarshaller.modelToDomain` → `bindData`, which rebinds **every** field except a short exclude list (account, apiKey, parentServer, zone, computeServerType, plan, serverOs, sourceImage, resourcePool, capacityInfo, stats, …). `agentInstalled`, `agentVersion`, `platform`, `platformVersion` are not excluded, so the agent check-in that happened during the poll was wiped; core's `waitForAgentInstall` (which needs `agentVersion` non-null via `stats.lastAgentUpdate`) then timed out after ~33 min and the UI showed Agent "Not Installed" / Platform `linux`. Embedded GORM (`dynamicUpdate true`) never had this problem, so parity reasoning misled. A prior commit had tried to hide the race with an extra `waitForAgentInstall`.

## Decision
Reload (`MorpheusUtil.getMorpheusServer(context, id)`) right after `checkServerReady`, set `externalIp`/`powerState` on the fresh copy, let `applyNetworkIpAndGetServer`/`saveAndGetNetworkInterface` perform the single save. Same reload in `updateServerHost` after the slow `apiService.getServerDetails` call. Rule for all future code: **never save a `ComputeServer` held across a WinRM poll.**

## Alternatives considered
Ask core to exclude agent fields in `prepareForSave` (out of plugin control); keep the save and add waits (timing-dependent).

## Consequences
Window shrinks from minutes to milliseconds; a complete fix still needs core. `VirtualMachineSync.updateMatchedVirtualMachines` has the same class of risk (loads all servers, `bulkSave` later) and only skips `status == 'provisioning'`; `runWorkload` sets `status = 'provisioned'` early, exposing servers to sync writes (proposed fix: don't set it early, as VMware does — not implemented).
