# ADR-0004: Grant pool IPs to the adapter and bind with `-IPv4Addresses` only
- Date: 2026-10-14/15 · Status: accepted · Code: merged #199 (`reserveIPAddress(opts, poolId, ipAddress, grant)`, `buildReserveIPAddressCommand`, `ReconfigurePoolIpHelper.grantTarget`) · Source: session "Combined network plugin", MORPH-953

## Context
Live testing of reconfigure pool binding showed the adapter ending with **two** IPv4 addresses and the pool losing two addresses. A PowerShell A/B/C experiment on the VMM server proved: (A) grant + `-IPv4Addresses` + `-IPv4AddressPools` → 2 IPs; (B) `-IPv4AddressPools` only → 1 VMM-picked IP; (C) grant to the adapter + `-IPv4Addresses` only → exactly 1 IP, `Assigned`. `-IPv4AddressPools` on `Set-SCVirtualNetworkAdapter` means "allocate from this pool for this adapter". Separately, provisioning grants with `-GrantToObjectType VirtualMachine` and no object id leave addresses `UnAssigned`, which VMM never auto-returns → leaks.

## Decision
Variant C: `Grant-SCIPAddress -GrantToObjectType VirtualNetworkAdapter -GrantToObjectID <adapterId> -Description morpheus:<vm> [-IPAddress …]`, then `Set-SCVirtualNetworkAdapter … -IPv4AddressType Static -IPv4Addresses "<ip>"` with the pool only looked up for fail-fast. Result logging prints `success/exitCode/data/error`, never `${out}`.

## Alternatives considered
Grant-to-adapter alone (verified not to fix the double allocation); variant B (loses the grant-id-first flow needed for the `NetworkPoolIp` row and user-chosen IP).

## Consequences
One address per pool NIC; grants are attributable in VMM by description. The provision-time path (`Set-SCVirtualNetworkAdapterConfiguration -IPv4Address -IPv4AddressPool` on the VM configuration) does not have this problem and was left unchanged; its grants are still anonymous.
