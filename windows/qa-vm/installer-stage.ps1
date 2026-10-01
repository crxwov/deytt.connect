Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Runs non-elevated from the desktop session; copies only the fixed hashed payload.
$runId = '__RUN_ID__'
if ($runId -notmatch '^[0-9TZ-]+$') { return }
$inputRoot = "\\VBOXSVR\deytt-installer-input-$runId"
$outputRoot = "\\VBOXSVR\deytt-installer-output-$runId"
$localTemp = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)) 'Temp'
$localRoot = Join-Path $localTemp ('deytt-qa-installer-source-' + $runId)
$script:ownsLocalRoot = $false
$reportPath = Join-Path $outputRoot 'installer-stage.json'
$tempReportPath = $reportPath + '.tmp'
$allowed = @{
    'installer/Package.wxs' = $true
    'installer/DEYTTConnect.Windows.Installer.wixproj' = $true
    'installer/installer-runner.ps1' = $true
    'installer/installer-collect.ps1' = $true
    'installer/installer-stage.ps1' = $true
}

function Get-Hash([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    try {
        $sha = [Security.Cryptography.SHA256]::Create()
        try { return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose() }
    }
    finally { $stream.Dispose() }
}

function New-OwnedDirectory([string]$Path) {
    if (-not ('DeyttQa.NativeDirectory' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace DeyttQa {
    public static class NativeDirectory {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        public static extern bool CreateDirectory(string path, IntPtr securityAttributes);
    }
}
'@ -ErrorAction Stop
    }
    if (-not [DeyttQa.NativeDirectory]::CreateDirectory($Path, [IntPtr]::Zero)) {
        throw [ComponentModel.Win32Exception]::new([Runtime.InteropServices.Marshal]::GetLastWin32Error())
    }
    $script:ownsLocalRoot = $true
}

function Test-ManifestPath([string]$Relative) {
    if ([string]::IsNullOrWhiteSpace($Relative) -or $Relative.Contains('\') -or
        [IO.Path]::IsPathRooted($Relative) -or $Relative.Contains(':')) { return $false }
    foreach ($segment in $Relative.Split('/')) {
        if ($segment -in @('', '.', '..') -or $segment -match '[<>:"|?*\x00-\x1f]' -or
            $segment.EndsWith('.') -or $segment.EndsWith(' ') -or
            ($segment -split '\.', 2)[0] -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$') { return $false }
    }
    return $true
}

function Test-JsonInteger($Value) {
    return ($Value -is [int] -or $Value -is [long])
}

try {
    if (-not [IO.Directory]::Exists($inputRoot) -or -not [IO.Directory]::Exists($outputRoot) -or
        [IO.Directory]::Exists($localRoot) -or [IO.File]::Exists($reportPath) -or
        [IO.File]::Exists($tempReportPath)) { return }
    $manifest = Get-Content -LiteralPath (Join-Path $inputRoot 'manifest.json') -Raw | ConvertFrom-Json
    if (-not (Test-JsonInteger $manifest.schema) -or $manifest.schema -ne 1 -or
        $manifest.files -isnot [Array] -or $manifest.files.Count -lt 10 -or $manifest.files.Count -gt 10000) { return }
    $seenPaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $manifest.files) {
        $relative = [string]$entry.path
        if (-not (Test-ManifestPath $relative) -or
            [string]$entry.sha256 -notmatch '^[0-9a-f]{64}$' -or -not $seenPaths.Add($relative) -or
            (($relative -match '^installer/') -and $allowed.Keys -cnotcontains $relative) -or
            (($relative -match '^(?i:package)/') -and -not $relative.StartsWith('package/', [StringComparison]::Ordinal)) -or
            ($relative -notmatch '^package/.+' -and -not $allowed.ContainsKey($relative))) { return }
        if ($allowed.ContainsKey($relative)) { $allowed.Remove($relative) }
        $source = [IO.Path]::GetFullPath((Join-Path $inputRoot ($relative -replace '/', '\')))
        $prefix = [IO.Path]::GetFullPath($inputRoot).TrimEnd('\') + '\'
        if (-not $source.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.File]::Exists($source) -or (Get-Hash $source) -ne [string]$entry.sha256) { return }
    }
    if ($allowed.Count -ne 0 -or -not ($manifest.files | Where-Object { $_.path -ceq 'package/DeyttConnect.Windows.exe' }) -or
        -not ($manifest.files | Where-Object { $_.path -ceq 'package/DeyttConnect.Windows.Service.exe' })) { return }
    New-OwnedDirectory $localRoot
    foreach ($entry in $manifest.files) {
        $relative = [string]$entry.path
        $source = Join-Path $inputRoot ($relative -replace '/', '\')
        $destination = Join-Path $localRoot ($relative -replace '/', '\')
        $parent = [IO.Path]::GetDirectoryName($destination)
        [void][IO.Directory]::CreateDirectory($parent)
        Copy-Item -LiteralPath $source -Destination $destination -ErrorAction Stop
        if ((Get-Hash $destination) -ne [string]$entry.sha256) { throw [IO.InvalidDataException]::new('Copied stage hash mismatch.') }
    }
    Copy-Item -LiteralPath (Join-Path $inputRoot 'manifest.json') -Destination (Join-Path $localRoot 'manifest.json') -ErrorAction Stop
    $report = [ordered]@{ schema = 1; staged = $true; file_count = [int]$manifest.files.Count }
    [IO.File]::WriteAllText($tempReportPath, ($report | ConvertTo-Json -Depth 3), [Text.UTF8Encoding]::new($false))
    [IO.File]::Move($tempReportPath, $reportPath)
}
catch {
    # Do not send exception text, paths, or identity to the host.
    try {
        if ($script:ownsLocalRoot -and [IO.Directory]::Exists($localRoot)) {
            Remove-Item -LiteralPath $localRoot -Recurse -Force -ErrorAction Stop
        }
    } catch { }
    try {
        [IO.File]::WriteAllText($tempReportPath, '{"schema":1,"staged":false,"file_count":0}', [Text.UTF8Encoding]::new($false))
        [IO.File]::Move($tempReportPath, $reportPath)
    } catch { try { Remove-Item -LiteralPath $tempReportPath -Force -ErrorAction SilentlyContinue } catch { } }
}
