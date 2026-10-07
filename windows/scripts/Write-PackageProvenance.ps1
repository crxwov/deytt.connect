[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $PackageDirectory,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string] $ExpectedVersion,

    [switch] $RequireCleanSource
)

$ErrorActionPreference = 'Stop'

function Resolve-PackageFile {
    param(
        [Parameter(Mandatory = $true)][string[]] $RelativePaths,
        [Parameter(Mandatory = $true)][string] $Description
    )

    foreach ($relativePath in $RelativePaths) {
        $candidate = Join-Path $script:PackageRoot $relativePath
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Get-Item -LiteralPath $candidate).FullName
        }
    }
    throw "Package is missing $Description. Checked: $($RelativePaths -join ', ')"
}

function Get-AssemblyMetadata {
    param([Parameter(Mandatory = $true)][string] $Path)

    $assemblyVersion = [Reflection.AssemblyName]::GetAssemblyName($Path).Version.ToString()
    $fileInfo = [Diagnostics.FileVersionInfo]::GetVersionInfo($Path)
    [pscustomobject]@{
        Path            = Get-PackageRelativePath $Path
        AssemblyVersion = $assemblyVersion
        FileVersion     = $fileInfo.FileVersion
        ProductVersion  = $fileInfo.ProductVersion
        SHA256          = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

function Get-PackageRelativePath {
    param([Parameter(Mandatory = $true)][string] $Path)

    $prefix = $script:PackageRoot.TrimEnd([IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $Path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Package file is outside the package directory: $Path"
    }
    return $Path.Substring($prefix.Length).Replace('\', '/')
}

$script:PackageRoot = (Resolve-Path -LiteralPath $PackageDirectory).Path
if (-not (Test-Path -LiteralPath $script:PackageRoot -PathType Container)) {
    throw "PackageDirectory must be an existing directory: $PackageDirectory"
}

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$sourceCommit = (& git -C $repoRoot rev-parse HEAD 2>$null | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceCommit -notmatch '^[0-9a-fA-F]{40}$') {
    throw 'Could not determine the checked-out source commit.'
}

$trackedChanges = @(& git -C $repoRoot status --porcelain --untracked-files=no 2>$null)
if ($LASTEXITCODE -ne 0) {
    throw 'Could not determine whether tracked source files are modified.'
}
$trackedDirty = $trackedChanges.Count -gt 0
if ($RequireCleanSource -and $trackedDirty) {
    throw "Release provenance requires a clean tracked source tree; found $($trackedChanges.Count) tracked change(s)."
}

$expectedAssemblyVersion = "$ExpectedVersion.0"
$uiDll = Resolve-PackageFile @('DeyttConnect.Windows.dll', 'app/DeyttConnect.Windows.dll') 'the UI assembly'
$serviceDll = Resolve-PackageFile @(
    'service/DeyttConnect.Windows.Service.dll',
    'DeyttConnect.Windows.Service.dll'
) 'the VPN service assembly'
$uiMetadata = Get-AssemblyMetadata $uiDll
$serviceMetadata = Get-AssemblyMetadata $serviceDll

foreach ($item in @(
        [pscustomobject]@{ Name = 'UI'; Metadata = $uiMetadata },
        [pscustomobject]@{ Name = 'VPN service'; Metadata = $serviceMetadata })) {
    if ($item.Metadata.AssemblyVersion -ne $expectedAssemblyVersion -or
        $item.Metadata.FileVersion -ne $expectedAssemblyVersion -or
        $item.Metadata.ProductVersion -notmatch "^$([regex]::Escape($ExpectedVersion))(?:\+[0-9A-Za-z.-]+)?$") {
        throw "$($item.Name) version mismatch. Expected AssemblyVersion/FileVersion $expectedAssemblyVersion and ProductVersion $ExpectedVersion; got $($item.Metadata.AssemblyVersion)/$($item.Metadata.FileVersion)/$($item.Metadata.ProductVersion)."
    }
}

$enginePath = Resolve-PackageFile @('DeyttVpnEngine.exe', 'vpn/DeyttVpnEngine.exe') 'the VPN engine'
$mapDirectory = Resolve-PackageFile @('Assets/route-map/index.html', 'app/Assets/route-map/index.html') 'the offline route map'
$mapRoot = Split-Path -Parent $mapDirectory
$mapAssets = [ordered]@{}
foreach ($assetName in @('index.html', 'atlas-init.js', 'network-atlas.css', 'network-atlas.js', 'world-land.json')) {
    $assetPath = Join-Path $mapRoot $assetName
    if (-not (Test-Path -LiteralPath $assetPath -PathType Leaf)) {
        throw "Package route map is missing required asset: $assetName"
    }
    $mapAssets[$assetName] = [pscustomobject]@{
        Path   = Get-PackageRelativePath $assetPath
        SHA256 = (Get-FileHash -LiteralPath $assetPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

$iconPath = $null
foreach ($relativePath in @('Assets/deyttconnect.ico', 'app/Assets/deyttconnect.ico')) {
    $candidate = Join-Path $script:PackageRoot $relativePath
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        $iconPath = (Get-Item -LiteralPath $candidate).FullName
        break
    }
}
$icon = if ($null -ne $iconPath) {
    [pscustomobject]@{
        Path   = Get-PackageRelativePath $iconPath
        SHA256 = (Get-FileHash -LiteralPath $iconPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
} else { $null }

$dotnetCommand = $null
if (-not [string]::IsNullOrWhiteSpace($env:DOTNET) -and
    (Test-Path -LiteralPath $env:DOTNET -PathType Leaf)) {
    $dotnetCommand = $env:DOTNET
} else {
    $bundledDotnet = Join-Path $repoRoot '.toolchain/dotnet/dotnet.exe'
    if (Test-Path -LiteralPath $bundledDotnet -PathType Leaf) {
        $dotnetCommand = $bundledDotnet
    } else {
        $dotnet = Get-Command dotnet -ErrorAction SilentlyContinue
        if ($null -ne $dotnet) {
            $dotnetCommand = $dotnet.Source
        }
    }
}
$dotnetSdkVersion = $null
if ($null -ne $dotnetCommand) {
    $sdkOutput = @(& $dotnetCommand --version 2>$null)
    if ($LASTEXITCODE -eq 0 -and $sdkOutput.Count -gt 0) {
        $dotnetSdkVersion = ($sdkOutput -join '').Trim()
    }
}

$provenance = [ordered]@{
    schema_version          = 1
    expected_version        = $ExpectedVersion
    source_commit           = $sourceCommit.ToLowerInvariant()
    source_tracked_dirty    = $trackedDirty
    source_tracked_changes  = $trackedChanges.Count
    require_clean_source    = [bool]$RequireCleanSource
    dotnet_sdk_version      = $dotnetSdkVersion
    ui_assembly             = $uiMetadata
    service_assembly        = $serviceMetadata
    vpn_engine              = [pscustomobject]@{
        Path   = Get-PackageRelativePath $enginePath
        SHA256 = (Get-FileHash -LiteralPath $enginePath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    route_map_assets        = $mapAssets
    application_icon        = $icon
}

$json = $provenance | ConvertTo-Json -Depth 8
$outputPath = Join-Path $script:PackageRoot 'build-info.json'
[IO.File]::WriteAllText($outputPath, $json + [Environment]::NewLine,
    (New-Object System.Text.UTF8Encoding($false)))
Write-Output "Package provenance written: $outputPath"
