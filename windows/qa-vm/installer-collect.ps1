Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Runs non-elevated after the local elevated runner; returns only fixed safe fields.
$runId = '__RUN_ID__'
if ($runId -notmatch '^[0-9TZ-]+$') { return }
$localTemp = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)) 'Temp'
$localRoot = Join-Path $localTemp ('deytt-qa-installer-source-' + $runId)
$localReport = Join-Path $localRoot 'installer-report.json'
$completePath = Join-Path $localRoot 'installer-complete.txt'
$outputRoot = "\\VBOXSVR\deytt-installer-output-$runId"
$outputReport = Join-Path $outputRoot 'installer-report.json'
$tempOutputReport = $outputReport + '.tmp'
$deadline = [DateTime]::UtcNow.AddMinutes(32)
$complete = $false
while ([DateTime]::UtcNow -lt $deadline) {
    if ([IO.File]::Exists($completePath)) {
        $complete = ([IO.File]::ReadAllText($completePath) -eq 'complete')
        break
    }
    Start-Sleep -Seconds 3
}
$allowedBlockers = @('unsupported_guest_os', 'elevated_session_required', 'preexisting_installation',
    'dotnet_sdk_missing', 'dotnet10_sdk_missing', 'dotnet_sdk_query_timeout', 'wix_build_timeout',
    'wix_build_failed', 'msi_output_invalid', 'msi_hash_invalid', 'msi_install_timeout',
    'msi_install_failed', 'msi_uninstall_timeout', 'msi_uninstall_failed', 'cleanup_not_verified',
    'installed_state_not_verified', 'runner_error', 'temp_cleanup_failed', 'child_process_cleanup_failed')
try {
    if (-not $complete -or -not [IO.File]::Exists($localReport) -or
        -not [IO.Directory]::Exists($outputRoot) -or [IO.File]::Exists($outputReport) -or
        [IO.File]::Exists($tempOutputReport)) { return }
    $report = Get-Content -LiteralPath $localReport -Raw | ConvertFrom-Json
    $requiredBooleans = @('windows10_x64','elevated_session','preexisting_install_clear','dotnet10_sdk',
        'wix6_build_succeeded','msi_built','msi_sha256_valid','install_attempted','installed_state_verified',
        'uninstall_attempted','post_uninstall_clean','cleanup_safe','no_child_processes')
    foreach ($name in $requiredBooleans) {
        $property = $report.PSObject.Properties[$name]
        if ($null -eq $property -or $property.Value -isnot [bool]) { return }
    }
    $schemaProperty = $report.PSObject.Properties['schema']
    $resultProperty = $report.PSObject.Properties['result']
    $elapsedProperty = $report.PSObject.Properties['elapsed_seconds']
    if ($null -eq $schemaProperty -or
        ($schemaProperty.Value -isnot [int] -and $schemaProperty.Value -isnot [long]) -or
        $null -eq $resultProperty -or $resultProperty.Value -isnot [string] -or
        $null -eq $elapsedProperty -or $elapsedProperty.Value -is [bool] -or
        $elapsedProperty.Value -isnot [ValueType] -or
        [double]::IsNaN([double]$elapsedProperty.Value) -or
        [double]::IsInfinity([double]$elapsedProperty.Value) -or
        [double]$elapsedProperty.Value -lt 0 -or [double]$elapsedProperty.Value -gt 3600 -or
        $report.schema -ne 1 -or $report.result -cnotin @('passed','failed') -or
        ($null -ne $report.blocker -and $report.blocker -cnotin $allowedBlockers) -or
        ($null -ne $report.msi_sha256 -and [string]$report.msi_sha256 -notmatch '^[0-9a-f]{64}$') -or
        ($report.msi_sha256_valid -and $null -eq $report.msi_sha256) -or
        ($null -ne $report.install_exit_code -and
            $report.install_exit_code -isnot [int] -and $report.install_exit_code -isnot [long]) -or
        ($null -ne $report.uninstall_exit_code -and
            $report.uninstall_exit_code -isnot [int] -and $report.uninstall_exit_code -isnot [long]) -or
        ($null -ne $report.blocker -and $report.blocker -isnot [string])) { return }
    $passed = ($report.result -eq 'passed' -and $null -eq $report.blocker -and
        $report.windows10_x64 -and $report.elevated_session -and $report.preexisting_install_clear -and
        $report.dotnet10_sdk -and $report.wix6_build_succeeded -and $report.msi_built -and
        $report.msi_sha256_valid -and $report.install_attempted -and $report.install_exit_code -eq 0 -and
        $report.installed_state_verified -and $report.uninstall_attempted -and
        $report.uninstall_exit_code -eq 0 -and $report.post_uninstall_clean -and
        $report.cleanup_safe -and $report.no_child_processes)
    if ($report.result -eq 'passed' -and -not $passed) { return }
    $safe = [ordered]@{
        schema = 1; result = [string]$report.result; blocker = $report.blocker
        windows10_x64 = [bool]$report.windows10_x64; elevated_session = [bool]$report.elevated_session
        preexisting_install_clear = [bool]$report.preexisting_install_clear; dotnet10_sdk = [bool]$report.dotnet10_sdk
        wix6_build_succeeded = [bool]$report.wix6_build_succeeded; msi_built = [bool]$report.msi_built
        msi_sha256_valid = [bool]$report.msi_sha256_valid; msi_sha256 = [string]$report.msi_sha256
        install_attempted = [bool]$report.install_attempted
        install_exit_code = $report.install_exit_code; installed_state_verified = [bool]$report.installed_state_verified
        uninstall_attempted = [bool]$report.uninstall_attempted; uninstall_exit_code = $report.uninstall_exit_code
        post_uninstall_clean = [bool]$report.post_uninstall_clean; cleanup_safe = [bool]$report.cleanup_safe
        no_child_processes = [bool]$report.no_child_processes
        elapsed_seconds = [double]$report.elapsed_seconds
    }
    [IO.File]::WriteAllText($tempOutputReport, ($safe | ConvertTo-Json -Depth 3), [Text.UTF8Encoding]::new($false))
    [IO.File]::Move($tempOutputReport, $outputReport)
}
catch { try { Remove-Item -LiteralPath $tempOutputReport -Force -ErrorAction SilentlyContinue } catch { } }
