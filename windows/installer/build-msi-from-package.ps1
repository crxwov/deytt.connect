[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $PackageDirectory,

    [string] $ProductVersion = '0.8.17',

    [string] $OutputPath = (Join-Path $PSScriptRoot '..\artifacts\DEYTTConnect-Windows-0.8.17.msi'),

    [string] $DotnetPath = (Join-Path $PSScriptRoot '..\..\.tmp-dotnet-sdk-10.0.401\dotnet.exe'),

    [switch] $SkipWindowsInstallerValidation
)

$ErrorActionPreference = 'Stop'
if ($ProductVersion -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') {
    throw 'Product version must be numeric major.minor.patch.'
}
$versionParts = $ProductVersion.Split('.') | ForEach-Object { [int]$_ }
if ($versionParts[0] -gt 255 -or $versionParts[1] -gt 255 -or $versionParts[2] -gt 65535) {
    throw 'MSI version fields must be at most 255.255.65535.'
}

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$sourcePath = (Resolve-Path -LiteralPath $PackageDirectory).Path
$outputFullPath = [IO.Path]::GetFullPath($OutputPath)
if (Test-Path -LiteralPath $outputFullPath) {
    throw "Refusing to overwrite an existing MSI: $outputFullPath"
}
$repoRootPrefix = $repoRoot.TrimEnd('\') + '\'
if ($outputFullPath -eq $repoRoot -or
    -not $outputFullPath.StartsWith($repoRootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'MSI output must remain inside this repository and cannot be the repository root.'
}
if (Test-Path -LiteralPath ($outputFullPath + '.sha256')) {
    throw 'Refusing to overwrite an existing MSI checksum sidecar.'
}
$sourcePrefix = $sourcePath.TrimEnd('\') + '\'
if ($outputFullPath.Equals($sourcePath, [StringComparison]::OrdinalIgnoreCase) -or
    $outputFullPath.StartsWith($sourcePrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'MSI output cannot overlap the source package.'
}
if (-not (Test-Path -LiteralPath $DotnetPath -PathType Leaf)) {
    throw "The .NET SDK executable was not found: $DotnetPath"
}
foreach ($required in @('DeyttConnect.Windows.exe', 'DeyttVpnEngine.exe',
        'service\DeyttConnect.Windows.Service.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $sourcePath $required) -PathType Leaf)) {
        throw "The package source is incomplete: $required is missing."
    }
}

$artifactParent = Split-Path -Parent $outputFullPath
$tempRoot = Join-Path $artifactParent ('.msi-stage-' + [Guid]::NewGuid().ToString('N'))
$packageStage = Join-Path $tempRoot 'package'
$wixOutput = Join-Path $tempRoot 'wix-output'
$wixObj = Join-Path $tempRoot 'wix-obj'
$installerProject = Join-Path $PSScriptRoot 'DEYTTConnect.Windows.Installer.wixproj'

try {
    New-Item -ItemType Directory -Path $packageStage, $wixOutput, $wixObj -Force | Out-Null
    foreach ($item in Get-ChildItem -LiteralPath $sourcePath -Force) {
        if ($item.Name -in @('service', 'DeyttConnect.Windows.exe.WebView2',
                'apply-live-service.ps1', 'apply-stage15-service.ps1', 'service-before-ui-refresh.dll')) {
            continue
        }
        if ($item.PSIsContainer) {
            if ($item.Name -eq 'Assets' -or $item.Name -eq 'runtimes') {
                Copy-Item -LiteralPath $item.FullName -Destination $packageStage -Recurse
            }
            continue
        }
        if ($item.Extension -ne '.pdb' -and $item.Name -ne 'DeyttVpnEngine.sha256') {
            Copy-Item -LiteralPath $item.FullName -Destination $packageStage
        }
    }

    foreach ($serviceFile in Get-ChildItem -LiteralPath (Join-Path $sourcePath 'service') -File) {
        if ($serviceFile.Extension -eq '.pdb') {
            continue
        }
        $destination = Join-Path $packageStage $serviceFile.Name
        if (Test-Path -LiteralPath $destination) {
            $existingHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
            $serviceHash = (Get-FileHash -LiteralPath $serviceFile.FullName -Algorithm SHA256).Hash
            if ($existingHash -ne $serviceHash) {
                throw "App and service contain different files named $($serviceFile.Name)."
            }
            continue
        }
        Copy-Item -LiteralPath $serviceFile.FullName -Destination $destination
    }
    if (Get-ChildItem -LiteralPath (Join-Path $sourcePath 'service') -Directory | Select-Object -First 1) {
        throw 'Unexpected nested service payload; refusing an incomplete installer harvest.'
    }

    $manifest = foreach ($file in Get-ChildItem -LiteralPath $packageStage -Recurse -File | Sort-Object FullName) {
        $relative = $file.FullName.Substring($packageStage.Length + 1)
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $relative"
    }
    Set-Content -LiteralPath (Join-Path $packageStage 'SHA256SUMS.txt') -Value $manifest -Encoding ASCII

    $restoreArgs = @('restore', $installerProject, '--ignore-failed-sources', '-p:NuGetAudit=false',
        "-p:BaseIntermediateOutputPath=$wixObj\", "-p:MSBuildProjectExtensionsPath=$wixObj\")
    & $DotnetPath @restoreArgs
    if ($LASTEXITCODE -ne 0) {
        throw "WiX restore failed with exit code $LASTEXITCODE."
    }

    $buildArgs = @('build', $installerProject, '--configuration', 'Release', '--no-restore',
        "-p:PackageStageDir=$packageStage", "-p:ProductVersion=$ProductVersion",
        "-p:OutputPath=$wixOutput\Release\", "-p:BaseIntermediateOutputPath=$wixObj\",
        "-p:IntermediateOutputPath=$wixObj\Release\", "-p:MSBuildProjectExtensionsPath=$wixObj\", '--nologo')
    if ($SkipWindowsInstallerValidation) {
        $buildArgs += '-p:SuppressValidation=true'
        Write-Warning 'Windows Installer ICE validation is disabled for this build.'
    }
    & $DotnetPath @buildArgs
    if ($LASTEXITCODE -ne 0) {
        throw "WiX build failed with exit code $LASTEXITCODE."
    }

    $built = @(Get-ChildItem -LiteralPath $wixOutput -Filter '*.msi' -Recurse -File)
    if ($built.Count -ne 1) {
        throw "Expected one MSI, found $($built.Count)."
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $outputFullPath) -Force | Out-Null
    Copy-Item -LiteralPath $built[0].FullName -Destination $outputFullPath
    $hash = (Get-FileHash -LiteralPath $outputFullPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath ($outputFullPath + '.sha256') -Value "$hash  $(Split-Path -Leaf $outputFullPath)" -Encoding ASCII
    Write-Output "Installer: $outputFullPath"
    Write-Output "SHA-256: $hash"
}
finally {
    $tempFullPath = [IO.Path]::GetFullPath($tempRoot)
    $allowedTempRoot = [IO.Path]::GetFullPath($artifactParent).TrimEnd('\') + '\'
    if ($tempFullPath.StartsWith($allowedTempRoot, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $tempFullPath)) {
        Remove-Item -LiteralPath $tempFullPath -Recurse -Force
    }
}
