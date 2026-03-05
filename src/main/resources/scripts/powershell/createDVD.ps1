$busNumber = <%busNumber%>
$lunNumber = <%lunNumber%>
$VM = Get-SCVirtualMachine -VMMServer localhost -ID "<%externalId%>"
$success = $false
For ($i = 0; $i -le 10 -and -not $success; $i++) {
    $jobGuid = New-Guid
    $shouldRepair = $true
    $null = New-SCVirtualDVDDrive -VMMServer localhost -JobGroup $jobGuid -Bus $busNumber -LUN $lunNumber
    if ($?) {
        $null = Set-SCVirtualMachine -VM $VM -JobGroup $jobGuid
        if ($?) {
            $success = $true
            $shouldRepair = $false
        }
    }

    if ($shouldRepair) {
        $lunNumber++
        $null = Repair-SCVirtualMachine -VM $VM -Dismiss -Force
    }
}

$report = New-Object -Type PSObject -Property @{
    'success' = $success
    'BUS'     = $busNumber
    'LUN'     = $lunNumber
}
$report