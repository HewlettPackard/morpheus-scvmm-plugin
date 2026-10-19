# ADR-0010: `getMaxNetworks()` = 8 as a documented constant
- Date: 2026-10-02 · Status: accepted · Code: merged #184 (`ScvmmConstants.MAX_NETWORKS`) · Source: session "Fix multi-nic PR", MORPH-15233 / MORPH-596

## Context
`getMaxNetworks()` was 1, which hid the Add-Network UI for SCVMM. The multi-NIC PR raised it to 8 without justification. The SDK allows `0`/`null` for unlimited; most sibling plugins (AWS, OLVM, VMware, MAAS) use 0. Hyper-V Gen1 supports 8 synthetic (+4 legacy) adapters; Gen2 on WS2025 up to 64. The plugin creates only `-Synthetic` adapters and cannot know the image generation when the provisioning form renders.

## Decision
Keep 8, expressed as `ScvmmConstants.MAX_NETWORKS` with Javadoc linking the Hyper-V scalability doc: the Gen1 ceiling is the safe upper bound for every supported generation.

## Alternatives considered
0 (unlimited) like siblings; a dynamic per-image check (not possible at form time).

## Consequences
Gen2/WS2025 cannot exceed 8 NICs at provision. The limit is **not** enforced on day-2 add (UI buttons are permission-gated), noted as an open gap.
