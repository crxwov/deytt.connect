param(
    [Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$ProductVersion,
    [Parameter(Mandatory)][string]$PackageStageDir,
    [Parameter(Mandatory)][string]$BuildRoot,
    [string]$Dotnet = 'dotnet'
)
$ErrorActionPreference = 'Stop'
$version = [Version]$ProductVersion
if ($version.Major -gt 255 -or $version.Minor -gt 255 -or $version.Build -gt 65535) { throw 'MSI version exceeds 255.255.65535.' }
$PackageStageDir = (Resolve-Path -LiteralPath $PackageStageDir).Path
$BuildRoot = [IO.Path]::GetFullPath($BuildRoot)
foreach ($file in @('DeyttConnect.Windows.exe', 'DeyttConnect.Windows.Service.exe', 'DeyttVpnEngine.exe', 'Assets/route-map/index.html')) {
    if (!(Test-Path -LiteralPath (Join-Path $PackageStageDir $file) -PathType Leaf)) { throw "Incomplete payload: $file" }
}
foreach ($name in @('DeyttConnect.Windows.dll', 'DeyttConnect.Windows.Service.dll')) {
    $path = Join-Path $PackageStageDir $name
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "missing payload assembly: $name" }
    $metadata = [Diagnostics.FileVersionInfo]::GetVersionInfo($path)
    $expected = "$ProductVersion.0"
    if ([Reflection.AssemblyName]::GetAssemblyName($path).Version.ToString() -ne $expected -or
        $metadata.FileVersion -ne $expected -or
        $metadata.ProductVersion -notmatch "^$([regex]::Escape($ProductVersion))(?:\+[0-9A-Za-z.-]+)?$") {
        throw "payload version does not match msi version $ProductVersion : $name"
    }
}
& "$PSScriptRoot/Get-WebView2Bootstrapper.ps1" -Destination (Join-Path $PackageStageDir 'Prerequisites/MicrosoftEdgeWebview2Setup.exe')
& $Dotnet build "$PSScriptRoot/DEYTTConnect.Windows.Installer.wixproj" -c Release "-p:ProductVersion=$ProductVersion" "-p:PackageStageDir=$PackageStageDir" "-p:InstallerBuildRoot=$BuildRoot" --nologo
if ($LASTEXITCODE -ne 0) { throw 'MSI build failed.' }
$msi = Join-Path $BuildRoot 'output/DEYTTConnect.Windows.Installer.msi'
& "$PSScriptRoot/Verify-InstallerRecovery.ps1" -MsiPath $msi
Get-FileHash -LiteralPath $msi -Algorithm SHA256
