[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $PackageDirectory,

    [string] $OutputDirectory = (Join-Path $PSScriptRoot '..\artifacts\DEYTTConnect-Windows-Portable'),

    [string] $ArchivePath = (Join-Path $PSScriptRoot '..\artifacts\DEYTTConnect-Windows-Portable-x64.zip'),

    [string] $CompilerPath = (Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe')
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$sourcePath = (Resolve-Path -LiteralPath $PackageDirectory).Path
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
$archiveFullPath = [IO.Path]::GetFullPath($ArchivePath)

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
    throw 'the deytt connect application icon is missing.'
}

$artifactParent = Split-Path -Parent $outputPath
$tempRoot = Join-Path $artifactParent ('.portable-stage-' + [Guid]::NewGuid().ToString('N'))
$stagePath = Join-Path $tempRoot 'package'
$launcherPath = Join-Path $tempRoot 'deyttconnect.exe'

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

    & $CompilerPath /nologo /target:winexe /platform:x64 /optimize+ "/win32icon:$applicationIcon" "/out:$launcherPath" $launcherSource
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $launcherPath -PathType Leaf)) {
        throw 'failed to build the portable launcher.'
    }
    Copy-Item -LiteralPath $launcherPath -Destination (Join-Path $stagePath 'deyttconnect.exe')

    $readme = @'
deytt connect for windows — portable build

run deyttconnect.exe from this folder. keep app/, service/, vpn/, and docs/
beside it. the launcher starts the bundled ui; the vpn service is installed
only through the app's explicit windows administrator prompt.

the app requires microsoft edge webview2 runtime. the bundled ui and vpn engine
are self-contained. docs/ contains the license and third-party notices.
'@
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
