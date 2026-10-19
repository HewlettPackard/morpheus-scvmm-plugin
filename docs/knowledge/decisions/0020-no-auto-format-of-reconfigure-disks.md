# ADR-0020: Reconfigure-added disks are attached raw; the plugin does not format or mount them
- Date: 2026-09-29 · Status: accepted (behaviour confirmed, QA ticket closed as not-a-bug) · Source: session "Scvmm add-node agent install"

## Context
QA: "Reconfigure: add 20 GB storage — disk shows in the Storage tab but `df -h` in the guest shows only the root disk." Evidence: `createAndAttachDisk` runs `New-SCVirtualDiskDrive … -VolumeType None` (first free SCSI bus/LUN, FixedSize default, VHDX on Gen2); `resizeWorkloadAndServer` records the `StorageVolume`; `lsblk` shows `sdb` with no filesystem; `dmesg` shows the attach; core `CloudPluginProvisioningService.resizeContainer` runs no guest command; VMware behaves the same; the only format/mount code in core is for Docker-host LVM volumes.

## Decision
No change. Document that provisioned ≠ formatted ≠ mounted, and that QA must verify with `lsblk -o NAME,SIZE,TYPE,FSTYPE,MOUNTPOINT`, then `mkfs`/`mount`/fstab manually (or via a Morpheus task).

## Alternatives considered
Auto-format/mount in the plugin (not pursued; product question left open).

## Consequences
Expect this ticket to recur; link here. The "Restarted" History entry in the same report was a manual UI restart, not the reconfigure (SCSI add is hot).
