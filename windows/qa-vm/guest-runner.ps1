Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# This runner has one fixed action: launch the synthetic signed-out QA fixture,
# capture that fixture's window only, request a graceful close, and report status.
$script:Phase = 'initialize'
$script:FailureType = $null
$script:ProcessExitCode = $null
$script:WindowFound = $false
$script:ScreenshotCaptured = $false
$script:CloseRequested = $false
$script:ProcessExited = $false
$script:FixtureProcessId = $null
$script:FixtureExceptionType = $null
$script:FixtureStackMethods = @()
$script:ForceStopUsed = $false
$script:TempRoot = $null
$process = $null
$runnerMutex = $null
$ownsRunnerMutex = $false
$startedAt = [DateTime]::UtcNow
$runId = '__RUN_ID__'
if ($runId -notmatch '^[0-9TZ-]+$') { throw [System.ArgumentException]::new('Invalid QA run identifier.') }
$shareRoot = "\\VBOXSVR\deytt-qa-readonly-$runId"
$outputRoot = "\\VBOXSVR\deytt-qa-output-$runId"
$exeName = 'DeyttConnect.Windows.QA.exe'
$reportPath = Join-Path $outputRoot 'run-report.json'
$reportTempPath = Join-Path $outputRoot 'run-report.json.tmp'
$startedPath = Join-Path $outputRoot 'runner-started.txt'
$windowReadyPath = Join-Path $outputRoot 'window-ready.json'
$hostCaptureAckPath = Join-Path $outputRoot 'host-capture-result.json'
$fixtureCrashPath = Join-Path $outputRoot 'fixture-crash.json'
$logPath = Join-Path $outputRoot 'run.log'
$screenshotPath = Join-Path $outputRoot 'fixture-window.png'
$logLines = [System.Collections.Generic.List[string]]::new()

function Add-RunLog([string]$Message) {
    $logLines.Add(([DateTime]::UtcNow.ToString('o') + ' ' + $Message))
}

function Get-Sha256([string]$Path) {
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant()
        }
        finally { $sha.Dispose() }
    }
    finally { $stream.Dispose() }
}

function Get-FixtureWindowBounds([IntPtr]$Handle) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class DeYttQaWindowCapture {
    [StructLayout(LayoutKind.Sequential)]
    public struct Rect { public int Left; public int Top; public int Right; public int Bottom; }
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool GetWindowRect(IntPtr hWnd, out Rect rect);
    [DllImport("user32.dll")]
    public static extern int GetSystemMetrics(int index);
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool PostMessage(IntPtr hWnd, uint message, IntPtr wParam, IntPtr lParam);
}
'@
    $rect = [DeYttQaWindowCapture+Rect]::new()
    if (-not [DeYttQaWindowCapture]::GetWindowRect($Handle, [ref]$rect)) {
        throw [System.ComponentModel.Win32Exception]::new([Runtime.InteropServices.Marshal]::GetLastWin32Error())
    }
    $width = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top
    if ($width -lt 320 -or $height -lt 240 -or $width -gt 4096 -or $height -gt 4096) {
        throw [InvalidOperationException]::new('QA window bounds outside the capture policy.')
    }
    # GetWindowRect and GetSystemMetrics run in the same PowerShell DPI context;
    # the host maps this coordinate space to the VBox PNG only after ratio checks.
    $desktop = [ordered]@{
        monitor_count = [DeYttQaWindowCapture]::GetSystemMetrics(80) # SM_CMONITORS
        left = [DeYttQaWindowCapture]::GetSystemMetrics(76) # SM_XVIRTUALSCREEN
        top = [DeYttQaWindowCapture]::GetSystemMetrics(77) # SM_YVIRTUALSCREEN
        width = [DeYttQaWindowCapture]::GetSystemMetrics(78) # SM_CXVIRTUALSCREEN
        height = [DeYttQaWindowCapture]::GetSystemMetrics(79) # SM_CYVIRTUALSCREEN
    }
    return [ordered]@{
        left = $rect.Left; top = $rect.Top; right = $rect.Right; bottom = $rect.Bottom
        width = $width; height = $height; desktop = $desktop
    }
}

function Capture-FixtureWindow([IntPtr]$Handle, [string]$Path) {
    $bounds = Get-FixtureWindowBounds -Handle $Handle
    Add-Type -AssemblyName System.Drawing
    $bitmap = [System.Drawing.Bitmap]::new($bounds.width, $bounds.height)
    try {
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.CopyFromScreen(
                [System.Drawing.Point]::new($bounds.left, $bounds.top),
                [System.Drawing.Point]::Empty,
                [System.Drawing.Size]::new($bounds.width, $bounds.height))
            $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
        }
        finally { $graphics.Dispose() }
    }
    finally { $bitmap.Dispose() }
}

function Read-FixtureCrashDiagnostics {
    if (-not [IO.File]::Exists($fixtureCrashPath)) { return }
    try {
        $crash = Get-Content -LiteralPath $fixtureCrashPath -Raw | ConvertFrom-Json
        $methods = @($crash.stack_methods)
        $validMethods = @($methods | Where-Object { $_ -notmatch '^[A-Za-z0-9_.+<>`]+$' }).Count -eq 0
        if ($crash.schema -eq 1 -and $crash.exception_type -match '^[A-Za-z0-9_]+$' -and
            $methods.Count -le 16 -and $validMethods) {
            $script:FixtureExceptionType = $crash.exception_type
            $script:FixtureStackMethods = $methods
            Add-RunLog ('fixture exception captured: ' + $script:FixtureExceptionType)
        }
    }
    catch { Add-RunLog 'fixture exception report was invalid' }
}

function Close-QaFixtureProcess {
    if ($null -eq $process) { return }
    $process.Refresh()
    if (-not $process.HasExited -and $process.MainWindowHandle -ne [IntPtr]::Zero -and
        $process.MainWindowTitle.StartsWith('deytt./connect', [StringComparison]::Ordinal) -and
        $process.MainWindowTitle.Contains('QA fixture')) {
        $script:CloseRequested = $process.CloseMainWindow()
        if (-not $script:CloseRequested) {
            $script:CloseRequested = [DeYttQaWindowCapture]::PostMessage(
                $process.MainWindowHandle, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero)
        }
        if ($script:CloseRequested) { $script:ProcessExited = $process.WaitForExit(15000) }
    }
    $process.Refresh()
    if (-not $process.HasExited) {
        $executable = $process.MainModule.FileName
        $expectedPrefix = [IO.Path]::GetFullPath($script:TempRoot).TrimEnd('\') + '\'
        if ([IO.Path]::GetFileName($executable) -ceq $exeName -and
            $executable.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            $script:ForceStopUsed = $true
            $process.Kill()
            $script:ProcessExited = $process.WaitForExit(5000)
        }
    }
    $process.Refresh()
    if ($process.HasExited) {
        $script:ProcessExited = $true
        $script:ProcessExitCode = $process.ExitCode
        Read-FixtureCrashDiagnostics
    }
}

try {
    $script:Phase = 'validate-shares'
    foreach ($path in @($shareRoot, $outputRoot)) {
        if (-not [System.IO.Directory]::Exists($path)) {
            throw [System.IO.DirectoryNotFoundException]::new('Required QA share is unavailable.')
        }
    }
    foreach ($path in @($startedPath, $windowReadyPath, $hostCaptureAckPath, $reportPath, $reportTempPath, $logPath, $screenshotPath)) {
        if ([System.IO.File]::Exists($path)) {
            throw [System.IO.IOException]::new('Refusing to overwrite an existing QA result.')
        }
    }
    [System.IO.File]::WriteAllText($startedPath, 'started', [Text.UTF8Encoding]::new($false))
    $runnerMutex = [Threading.Mutex]::new($false, 'Global\DeyttConnect.QaVmSmoke')
    $ownsRunnerMutex = $runnerMutex.WaitOne(0)
    if (-not $ownsRunnerMutex) {
        throw [InvalidOperationException]::new('Another QA fixture runner is already active.')
    }

    $script:Phase = 'verify-package'
    $appShareRoot = Join-Path $shareRoot 'app'
    $manifestPath = Join-Path $shareRoot 'manifest.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($manifest.files.Count -lt 1 -or $manifest.files.Count -gt 2500) {
        throw [System.IO.InvalidDataException]::new('QA package manifest count outside policy.')
    }
    foreach ($entry in $manifest.files) {
        $relative = [string]$entry.path
        if ([IO.Path]::IsPathRooted($relative) -or $relative.Contains(':') -or $relative.Contains('..')) {
            throw [System.IO.InvalidDataException]::new('Unsafe relative path in QA package manifest.')
        }
        $sourcePath = [IO.Path]::GetFullPath((Join-Path $appShareRoot $relative))
        $rootPath = [IO.Path]::GetFullPath($appShareRoot).TrimEnd('\') + '\'
        if (-not $sourcePath.StartsWith($rootPath, [StringComparison]::OrdinalIgnoreCase)) {
            throw [System.IO.InvalidDataException]::new('QA manifest path escaped the package directory.')
        }
        if (-not [IO.File]::Exists($sourcePath) -or (Get-Sha256 $sourcePath) -ne [string]$entry.sha256) {
            throw [System.IO.InvalidDataException]::new('QA package hash check failed.')
        }
    }
    $fixtureSharePath = Join-Path $appShareRoot $exeName
    if (-not ($manifest.files | Where-Object { $_.path -eq $exeName })) {
        throw [System.IO.InvalidDataException]::new('Allowlisted QA fixture executable is missing.')
    }
    Add-RunLog 'input package verified'

    $script:Phase = 'stage-local-copy'
    $systemDrive = [IO.Path]::GetPathRoot($env:SystemRoot)
    $driveInfo = [System.IO.DriveInfo]::new($systemDrive)
    if ($driveInfo.AvailableFreeSpace -lt 1073741824) {
        throw [System.IO.IOException]::new('Guest system disk has less than 1 GiB available.')
    }
    $script:TempRoot = Join-Path $env:TEMP ('deytt-qa-smoke-' + [Guid]::NewGuid().ToString('N'))
    [void][IO.Directory]::CreateDirectory($script:TempRoot)
    Get-ChildItem -LiteralPath $appShareRoot -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $script:TempRoot -Recurse -Force
    }
    $fixturePath = Join-Path $script:TempRoot $exeName
    if (-not [IO.File]::Exists($fixturePath) -or (Get-Sha256 $fixturePath) -ne [string](
            ($manifest.files | Where-Object { $_.path -eq $exeName } | Select-Object -First 1).sha256)) {
        throw [System.IO.InvalidDataException]::new('Staged QA fixture hash check failed.')
    }

    $script:Phase = 'launch-fixture'
    $previousCrashPath = [Environment]::GetEnvironmentVariable('DEYTT_QA_CRASH_REPORT', 'Process')
    [Environment]::SetEnvironmentVariable('DEYTT_QA_CRASH_REPORT', $fixtureCrashPath, 'Process')
    try {
        $process = Start-Process -FilePath $fixturePath -WorkingDirectory $script:TempRoot -PassThru
    }
    finally {
        [Environment]::SetEnvironmentVariable('DEYTT_QA_CRASH_REPORT', $previousCrashPath, 'Process')
    }
    Add-RunLog 'allowlisted fixture process started'
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    while ([DateTime]::UtcNow -lt $deadline) {
        $process.Refresh()
        if ($process.HasExited) { break }
        if ($process.MainWindowHandle -ne [IntPtr]::Zero -and
            $process.MainWindowTitle.StartsWith('deytt./connect', [StringComparison]::Ordinal) -and
            $process.MainWindowTitle.Contains('QA fixture')) {
            $script:WindowFound = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $script:WindowFound) {
        if ($process.HasExited) { $script:ProcessExitCode = $process.ExitCode }
        throw [System.TimeoutException]::new('Allowlisted QA fixture window did not appear in 60 seconds.')
    }
    Add-RunLog 'synthetic QA window ready'
    $script:FixtureProcessId = $process.Id
    $windowHandle = $process.MainWindowHandle
    $bounds = Get-FixtureWindowBounds -Handle $windowHandle
    $foregroundRequested = [DeYttQaWindowCapture]::SetForegroundWindow($windowHandle)
    $windowReady = [ordered]@{
        schema = 1
        run_id = $runId
        fixture = 'DeyttConnect.Windows.QA'
        process_id = $script:FixtureProcessId
        bounds = $bounds
        foreground_requested = $foregroundRequested
    }
    $windowReadyTempPath = $windowReadyPath + '.tmp'
    [IO.File]::WriteAllText($windowReadyTempPath, ($windowReady | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
    [IO.File]::Move($windowReadyTempPath, $windowReadyPath)

    $script:Phase = 'capture-window'
    Start-Sleep -Seconds 2
    try {
        Capture-FixtureWindow -Handle $windowHandle -Path $screenshotPath
        $script:ScreenshotCaptured = $true
        Add-RunLog 'fixture window screenshot captured in guest'
    }
    catch {
        Add-RunLog ('guest window capture failed: ' + $_.Exception.GetType().Name)
        $script:Phase = 'host-window-capture'
        $captureDeadline = [DateTime]::UtcNow.AddSeconds(90)
        while ([DateTime]::UtcNow -lt $captureDeadline -and -not [IO.File]::Exists($hostCaptureAckPath)) {
            Start-Sleep -Milliseconds 500
        }
        if (-not [IO.File]::Exists($hostCaptureAckPath)) {
            throw [System.TimeoutException]::new('Host did not acknowledge a fixture-only screenshot within 90 seconds.')
        }
        $ack = Get-Content -LiteralPath $hostCaptureAckPath -Raw | ConvertFrom-Json
        if ($ack.schema -ne 1 -or $ack.run_id -ne $runId -or
            $ack.fixture_window_confirmed -ne $true -or $ack.screenshot_captured -ne $true) {
            throw [System.IO.InvalidDataException]::new('Host screenshot was not confirmed as the synthetic fixture window.')
        }
        $script:ScreenshotCaptured = $true
        Add-RunLog 'host confirmed fixture-only screenshot'
    }

    $script:Phase = 'graceful-close'
    Close-QaFixtureProcess
    if ($script:ProcessExited) {
        $process.Refresh()
        $script:ProcessExitCode = $process.ExitCode
        Add-RunLog 'fixture process exited after window-close request'
    }
    else {
        Add-RunLog 'fixture process did not exit after graceful close request'
    }
}
catch {
    $script:FailureType = $_.Exception.GetType().Name
    Add-RunLog ('phase failed: ' + $script:FailureType)
}
finally {
    if ($null -ne $process) {
        try { Close-QaFixtureProcess }
        catch { if ($null -eq $script:FailureType) { $script:FailureType = $_.Exception.GetType().Name } }
        $process.Dispose()
    }
    if ($null -ne $runnerMutex) {
        if ($ownsRunnerMutex) { try { $runnerMutex.ReleaseMutex() } catch { } }
        $runnerMutex.Dispose()
    }
    if ($null -ne $script:TempRoot -and $script:TempRoot.StartsWith(
            (Join-Path $env:TEMP 'deytt-qa-smoke-'), [StringComparison]::OrdinalIgnoreCase)) {
        try { Remove-Item -LiteralPath $script:TempRoot -Recurse -Force -ErrorAction Stop }
        catch { if ($null -eq $script:FailureType) { $script:FailureType = $_.Exception.GetType().Name } }
    }
    $elapsed = [Math]::Round(([DateTime]::UtcNow - $startedAt).TotalSeconds, 2)
    $passed = ($null -eq $script:FailureType -and $script:WindowFound -and
        $script:ScreenshotCaptured -and $script:CloseRequested -and $script:ProcessExited -and
        $script:ProcessExitCode -eq 0)
    $report = [ordered]@{
        schema = 1
        result = $(if ($passed) { 'passed' } else { 'failed' })
        fixture = 'DeyttConnect.Windows.QA'
        fixture_process_id = $script:FixtureProcessId
        fixture_window_seen = $script:WindowFound
        screenshot_captured = $script:ScreenshotCaptured
        graceful_close_requested = $script:CloseRequested
        process_exited = $script:ProcessExited
        process_exit_code = $script:ProcessExitCode
        process_force_stop_used = $script:ForceStopUsed
        fixture_exception_type = $script:FixtureExceptionType
        fixture_stack_methods = $script:FixtureStackMethods
        failure_phase = $(if ($passed) { $null } else { $script:Phase })
        failure_type = $script:FailureType
        elapsed_seconds = $elapsed
        timestamp_utc = $startedAt.ToString('o')
    }
    try {
        [IO.File]::WriteAllText($logPath, (($logLines -join [Environment]::NewLine) + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText($reportTempPath, ($report | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
        [IO.File]::Move($reportTempPath, $reportPath)
    }
    catch {
        # A missing report is handled as a bounded host-side timeout.
        exit 3
    }
}

if (-not $passed) { exit 2 }
exit 0
