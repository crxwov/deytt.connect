Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Parameterless read-only membership probe. It never elevates and emits no identity.
$runId = '__RUN_ID__'
if ($runId -notmatch '^[0-9TZ-]+$') { return }
$outputRoot = "\\VBOXSVR\deytt-installer-admin-output-$runId"
$startedPath = Join-Path $outputRoot 'admin-check-started.txt'
$reportPath = Join-Path $outputRoot 'admin-check.json'
$tempPath = $reportPath + '.tmp'
try {
    if (-not [IO.Directory]::Exists($outputRoot) -or [IO.File]::Exists($reportPath) -or [IO.File]::Exists($tempPath)) { return }
    [IO.File]::WriteAllText($startedPath, 'started', [Text.UTF8Encoding]::new($false))
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $adminSid = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $isMember = @($identity.Groups | Where-Object { $_.Value -eq $adminSid.Value }).Count -gt 0
    $report = [ordered]@{
        schema = 1
        windows10_x64 = ((Get-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion').ProductName -like 'Windows 10*' -and [IntPtr]::Size -eq 8)
        administrator_member = $isMember
        elevated = ([Security.Principal.WindowsPrincipal]::new($identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))
        timestamp_utc = [DateTime]::UtcNow.ToString('o')
    }
    [IO.File]::WriteAllText($tempPath, ($report | ConvertTo-Json -Depth 3), [Text.UTF8Encoding]::new($false))
    [IO.File]::Move($tempPath, $reportPath)
    return
}
catch { return }
