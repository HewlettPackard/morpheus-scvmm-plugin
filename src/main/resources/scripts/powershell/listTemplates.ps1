foreach ($Template in $VMTemplates) {
        $data = New-Object PSObject -property @{
                ID              = $Template.ID
                ObjectType      = $Template.ObjectType.ToString()
                Name            = $Template.Name
                CPUCount        = $Template.CPUCount
                Memory          = $Template.Memory
                OperatingSystem = $Template.OperatingSystem.Name
                TotalSize       = 0
                UsedSize        = 0
                Generation      = $Template.Generation
                Disks           = @()
        }

        foreach ($VHDconf in $Template.VirtualDiskDrives) {
                $VHD = $VHDconf.VirtualHardDisk
                $disk = New-Object PSObject -property @{
                        ID           = $VHD.ID
                        Name         = $VHD.Name
                        VHDType      = $VHD.VHDType.ToString()
                        VHDFormat    = $VHD.VHDFormatType.ToString()
                        Location     = $VHD.Location
                        TotalSize    = $VHD.MaximumSize
                        UsedSize     = $VHD.Size
                        HostId       = $VHD.HostId
                        HostVolumeId = $VHD.HostVolumeId
                        VolumeType   = ([Microsoft.VirtualManager.Remoting.VolumeType]$VHDconf.VolumeType).toString()
                }
                $data.Disks += $disk
                $data.TotalSize += $VHD.MaximumSize
                $data.UsedSize += $VHD.Size
        }
        $report += $data
}

$Disks = Get-SCVirtualHardDisk -VMMServer localhost
foreach ($VHDconf in $Disks) {
        $data = New-Object PSObject -property @{
                ID              = $VHDconf.ID
                Name            = $VHDconf.Name
                Location        = $VHDconf.Location
                OperatingSystem = $VHDconf.OperatingSystem.Name
                TotalSize       = $VHDconf.MaximumSize
                VHDFormatType   = ([Microsoft.VirtualManager.Remoting.VHDFormatType]$VHDconf.VHDFormatType).toString()
                UsedSize        = 0
                Disks           = @()
        }
        $disk = New-Object PSObject -property @{
                ID           = $VHDconf.ID
                ObjectType   = $VHDConf.ObjectType.ToString()
                Name         = $VHDconf.Name
                VHDType      = $VHDconf.VHDType.ToString()
                VHDFormat    = $VHDconf.VHDFormatType.ToString()
                Location     = $VHDconf.Location
                TotalSize    = $VHDconf.MaximumSize
                UsedSize     = $VHDconf.Size
                HostId       = $VHDconf.HostId
                HostVolumeId = $VHDconf.HostVolumeId
        }
        $data.Disks += $disk
        $report += $data
}
$report