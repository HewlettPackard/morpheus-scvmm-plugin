$ErrorActionPreference = 'Stop'
$report = @()
$shares = Get-SCLibraryShare -VMMServer localhost
foreach ($share in $shares) {
  $data = New-Object PSObject -property @{
    ID = $share.ID
    Name = $share.Name
    Path = $share.Path
  }
  $report += $data
}
$report