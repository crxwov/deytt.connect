[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $PackageDirectory,

    [string] $OutputDirectory = (Join-Path $PSScriptRoot '..\artifacts\DEYTTConnect-Windows-Portable'),

    [string] $ArchivePath = '',

    [string] $CompilerPath = (Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe')
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$sourcePath = (Resolve-Path -LiteralPath $PackageDirectory).Path
$packageInfoPath = Join-Path $sourcePath 'build-info.json'
if (-not (Test-Path -LiteralPath $packageInfoPath -PathType Leaf)) {
    throw 'the package provenance file is required to name the portable archive.'
}
$packageInfo = Get-Content -LiteralPath $packageInfoPath -Raw | ConvertFrom-Json
$productVersion = [string]$packageInfo.expected_version
if ($productVersion -notmatch '^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$') {
    throw 'the package provenance file has an invalid product version.'
}
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
$effectiveArchivePath = if ([string]::IsNullOrWhiteSpace($ArchivePath)) {
    Join-Path $PSScriptRoot "..\artifacts\deytt-connect-$productVersion-portable.zip"
} else {
    $ArchivePath
}
$archiveFullPath = [IO.Path]::GetFullPath($effectiveArchivePath)

foreach ($path in @($outputPath, $archiveFullPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "refusing to overwrite an existing portable artifact: $path"
    }
}
$repoRootPrefix = $repoRoot.TrimEnd('\') + '\'
if ($outputPath -eq $repoRoot -or -not $outputPath.StartsWith($repoRootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    -not $archiveFullPath.StartsWith($repoRootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'portable output paths must remain inside this repository, and the output directory cannot be its root.'
}
$sourcePrefix = $sourcePath.TrimEnd('\') + '\'
$outputPrefix = $outputPath.TrimEnd('\') + '\'
if ($outputPath.Equals($sourcePath, [StringComparison]::OrdinalIgnoreCase) -or
    $archiveFullPath.Equals($sourcePath, [StringComparison]::OrdinalIgnoreCase) -or
    $archiveFullPath.Equals($outputPath, [StringComparison]::OrdinalIgnoreCase) -or
    $outputPath.StartsWith($sourcePrefix, [StringComparison]::OrdinalIgnoreCase) -or
    $sourcePath.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    $archiveFullPath.StartsWith($sourcePrefix, [StringComparison]::OrdinalIgnoreCase) -or
    $archiveFullPath.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'portable outputs cannot overlap the source package or the portable folder.'
}
$archiveSidecar = $archiveFullPath + '.sha256'
$archiveTempPath = $archiveFullPath + '.partial'
if ((Test-Path -LiteralPath $archiveSidecar) -or (Test-Path -LiteralPath $archiveTempPath)) {
    throw 'refusing to overwrite an existing portable archive sidecar or temporary archive.'
}
foreach ($required in @(
        'DeyttConnect.Windows.exe',
        'DeyttVpnEngine.exe',
        'service\DeyttConnect.Windows.Service.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $sourcePath $required) -PathType Leaf)) {
        throw "the package source is incomplete: $required is missing."
    }
}
if (-not (Test-Path -LiteralPath $CompilerPath -PathType Leaf)) {
    throw "the windows .net framework c# compiler was not found: $CompilerPath"
}

$launcherSource = Join-Path $PSScriptRoot '..\launcher\portable_launcher.cs'
$applicationIcon = Join-Path $repoRoot 'windows\DeyttConnect.Windows\Assets\deyttconnect.ico'
if (-not (Test-Path -LiteralPath $launcherSource -PathType Leaf)) {
    throw 'the portable launcher source is missing.'
}
if (-not (Test-Path -LiteralPath $applicationIcon -PathType Leaf)) {
    throw 'the deytt./connect application icon is missing.'
}

$artifactParent = Split-Path -Parent $outputPath
$tempRoot = Join-Path $artifactParent ('.portable-stage-' + [Guid]::NewGuid().ToString('N'))
$stagePath = Join-Path $tempRoot 'package'
$launcherPath = Join-Path $tempRoot 'deyttconnect.exe'
$launcherMetadataPath = Join-Path $tempRoot 'launcher-metadata.cs'

try {
    New-Item -ItemType Directory -Path (Join-Path $stagePath 'app'),
        (Join-Path $stagePath 'service'), (Join-Path $stagePath 'vpn'),
        (Join-Path $stagePath 'docs') -Force | Out-Null

    foreach ($item in Get-ChildItem -LiteralPath $sourcePath -Force) {
        if ($item.Name -in @('service', 'DeyttConnect.Windows.Service.exe', 'DeyttConnect.Windows.exe.WebView2',
                'DeyttVpnEngine.exe', 'service-before-ui-refresh.dll',
                'apply-live-service.ps1', 'apply-stage15-service.ps1')) {
            continue
        }
        if ($item.PSIsContainer) {
            if ($item.Name -eq 'source') {
                Copy-Item -LiteralPath $item.FullName -Destination (Join-Path $stagePath 'docs') -Recurse
            } elseif ($item.Name -eq 'Assets' -or $item.Name -eq 'runtimes') {
                Copy-Item -LiteralPath $item.FullName -Destination (Join-Path $stagePath 'app') -Recurse
            }
            continue
        }
        if ($item.Extension -eq '.pdb' -or $item.Name -eq 'DeyttVpnEngine.sha256') {
            continue
        }
        if ($item.Name -eq 'build-info.json') {
            Copy-Item -LiteralPath $item.FullName -Destination (Join-Path $stagePath 'docs')
        } else {
            Copy-Item -LiteralPath $item.FullName -Destination (Join-Path $stagePath 'app')
        }
    }
    foreach ($serviceFile in Get-ChildItem -LiteralPath (Join-Path $sourcePath 'service') -File) {
        if ($serviceFile.Extension -ne '.pdb') {
            Copy-Item -LiteralPath $serviceFile.FullName -Destination (Join-Path $stagePath 'service')
        }
    }
    if (Get-ChildItem -LiteralPath (Join-Path $sourcePath 'service') -Directory | Select-Object -First 1) {
        throw 'unexpected nested service payload; refusing an incomplete portable layout.'
    }
    Copy-Item -LiteralPath (Join-Path $sourcePath 'DeyttVpnEngine.exe') -Destination (Join-Path $stagePath 'vpn')
    Copy-Item -LiteralPath (Join-Path $repoRoot 'windows\THIRD-PARTY-NOTICES.md') -Destination (Join-Path $stagePath 'docs')
    Copy-Item -LiteralPath (Join-Path $repoRoot 'LICENSE') -Destination (Join-Path $stagePath 'docs\DEYTT-LICENSE.txt')

    $launcherMetadata = @"
using System.Reflection;
[assembly: AssemblyTitle("deytt./connect portable launcher")]
[assembly: AssemblyCompany("deytt.")]
[assembly: AssemblyProduct("deytt./connect")]
[assembly: AssemblyDescription("Portable launcher for deytt./connect")]
[assembly: AssemblyVersion("$productVersion.0")]
[assembly: AssemblyFileVersion("$productVersion.0")]
[assembly: AssemblyInformationalVersion("$productVersion")]
"@
    Set-Content -LiteralPath $launcherMetadataPath -Value $launcherMetadata -Encoding UTF8
    & $CompilerPath /nologo /target:winexe /platform:x64 /optimize+ "/win32icon:$applicationIcon" "/out:$launcherPath" $launcherSource $launcherMetadataPath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $launcherPath -PathType Leaf)) {
        throw 'failed to build the portable launcher.'
    }
    Copy-Item -LiteralPath $launcherPath -Destination (Join-Path $stagePath 'deyttconnect.exe')

    $readme = @"
deytt./connect for Windows — portable build $productVersion

Run deyttconnect.exe from this folder. Keep app/, service/, vpn/, and docs/
beside it. The launcher starts the bundled UI. The VPN service is installed
only after Windows asks for administrator permission from the app.

The app requires Microsoft Edge WebView2 Runtime. The bundled UI and VPN engine
are self-contained. docs/ contains the license and third-party notices.
"@
    Set-Content -LiteralPath (Join-Path $stagePath 'docs\README.txt') -Value $readme -Encoding UTF8

    $manifest = foreach ($file in Get-ChildItem -LiteralPath $stagePath -Recurse -File | Sort-Object FullName) {
        $relative = $file.FullName.Substring($stagePath.Length + 1)
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $relative"
    }
    Set-Content -LiteralPath (Join-Path $stagePath 'docs\SHA256SUMS.txt') -Value $manifest -Encoding ASCII

    $rootFiles = @(Get-ChildItem -LiteralPath $stagePath -File)
    $rootDirs = @(Get-ChildItem -LiteralPath $stagePath -Directory | Select-Object -ExpandProperty Name | Sort-Object)
    if ($rootFiles.Count -ne 1 -or $rootFiles[0].Name -ne 'deyttconnect.exe' -or
        ($rootDirs -join ',') -ne 'app,docs,service,vpn') {
        throw 'portable layout check failed: expected one launcher and app/docs/service/vpn folders.'
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    New-Item -ItemType Directory -Path (Split-Path -Parent $archiveFullPath) -Force | Out-Null
    [IO.Compression.ZipFile]::CreateFromDirectory(
        $stagePath, $archiveTempPath, [IO.Compression.CompressionLevel]::Optimal, $false)
    Move-Item -LiteralPath $stagePath -Destination $outputPath
    Move-Item -LiteralPath $archiveTempPath -Destination $archiveFullPath

    $zipHash = (Get-FileHash -LiteralPath $archiveFullPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath $archiveSidecar -Value "$zipHash  $(Split-Path -Leaf $archiveFullPath)" -Encoding ASCII
    Write-Output "portable directory: $outputPath"
    Write-Output "portable archive: $archiveFullPath"
    Write-Output "root launcher sha-256: $((Get-FileHash -LiteralPath (Join-Path $outputPath 'deyttconnect.exe') -Algorithm SHA256).Hash)"
    Write-Output "archive sha-256: $zipHash"
}
finally {
    $tempFullPath = [IO.Path]::GetFullPath($tempRoot)
    $allowedTempRoot = [IO.Path]::GetFullPath($artifactParent).TrimEnd('\') + '\'
    if ($tempFullPath.StartsWith($allowedTempRoot, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $tempFullPath)) {
        Remove-Item -LiteralPath $tempFullPath -Recurse -Force
    }
    if (Test-Path -LiteralPath $archiveTempPath) {
        Remove-Item -LiteralPath $archiveTempPath -Force
    }
}
