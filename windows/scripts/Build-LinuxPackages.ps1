[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][string]$EngineDirectory,
    [Parameter(Mandatory)][string]$XrayDirectory,
    [string]$Dotnet = 'dotnet',
    [string]$Nfpm = 'nfpm'
)

$ErrorActionPreference = 'Stop'
$windows = Split-Path -Parent $PSScriptRoot
$assets = Join-Path $windows 'linux'
$output = New-Item -ItemType Directory -Force -Path $OutputDirectory
$stage = New-Item -ItemType Directory -Path (Join-Path $output.FullName 'stage')
$dist = New-Item -ItemType Directory -Path (Join-Path $output.FullName 'dist')
$revision = & git -C $windows rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Cannot determine the source revision' }
$workingTreeChanges = & git -C $windows status --porcelain
if ($LASTEXITCODE -ne 0) { throw 'Cannot determine the source status' }
if ($workingTreeChanges) { $revision += '.dirty' }

$app = Join-Path $stage.FullName 'app'
$service = Join-Path $stage.FullName 'service'
$publishArgs = @(
    '-c', 'Release', '-r', 'linux-x64', '--self-contained', 'true',
    '--disable-build-servers', "-p:Version=$Version", "-p:AssemblyVersion=$Version.0",
    "-p:FileVersion=$Version.0", "-p:InformationalVersion=$Version+$revision",
    '-p:IncludeSourceRevisionInInformationalVersion=false'
)

& $Dotnet publish (Join-Path $windows 'DeyttConnect.Windows') @publishArgs -o $app
if ($LASTEXITCODE -ne 0) { throw 'Linux desktop publish failed' }
& $Dotnet publish (Join-Path $windows 'DeyttConnect.Windows.Service') @publishArgs -o $service
if ($LASTEXITCODE -ne 0) { throw 'Linux daemon publish failed' }
Copy-Item -Path (Join-Path $service '*') -Destination $app -Recurse -Force
Copy-Item -LiteralPath (Join-Path $EngineDirectory 'sing-box') -Destination (Join-Path $app 'DeyttVpnEngine')
Copy-Item -LiteralPath (Join-Path $EngineDirectory 'LICENSE') -Destination (Join-Path $app 'sing-box-LICENSE')
Copy-Item -LiteralPath (Join-Path $XrayDirectory 'xray') -Destination $app
Copy-Item -LiteralPath (Join-Path $XrayDirectory 'LICENSE') -Destination (Join-Path $app 'xray-LICENSE')
Get-ChildItem -LiteralPath $app -Filter '*.pdb' -File -Recurse | Remove-Item -Force

foreach ($file in @('deytt-connect.service', 'deytt-connect.socket', 'deytt-connect.desktop',
    'deytt-connect', 'deytt-connect.sysusers', 'postinstall.sh', 'preremove.sh', 'postremove.sh')) {
    $content = [IO.File]::ReadAllText((Join-Path $assets $file)).Replace("`r`n", "`n")
    [IO.File]::WriteAllText((Join-Path $stage.FullName $file), $content)
}

Copy-Item -LiteralPath (Join-Path (Split-Path -Parent $windows) 'app/src/main/res/drawable-nodpi/ic_launcher_png.png') -Destination (Join-Path $stage.FullName 'deytt-connect.png')

$previousStage = $env:DEYTT_PACKAGE_STAGE
$previousVersion = $env:DEYTT_PACKAGE_VERSION
$env:DEYTT_PACKAGE_STAGE = $stage.FullName.Replace('\', '/')
$env:DEYTT_PACKAGE_VERSION = $Version
$config = Join-Path $stage.FullName 'nfpm.yaml'
$configText = [IO.File]::ReadAllText((Join-Path $assets 'nfpm.yaml')).Replace('@STAGE@', $env:DEYTT_PACKAGE_STAGE)
[IO.File]::WriteAllText($config, $configText)
try {
    $packages = [ordered]@{
        deb = "deytt-connect_${Version}_amd64.deb"
        rpm = "deytt-connect-$Version-1.x86_64.rpm"
        archlinux = "deytt-connect-$Version-1-x86_64.pkg.tar.zst"
    }
    foreach ($package in $packages.GetEnumerator()) {
        $target = Join-Path $dist.FullName $package.Value
        & $Nfpm package -f $config -p $package.Key -t $target
        if ($LASTEXITCODE -ne 0) { throw "Linux package failed: $($package.Key)" }
        $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $($package.Value)" | Set-Content -LiteralPath "$target.sha256" -Encoding ascii
    }
}
finally {
    $env:DEYTT_PACKAGE_STAGE = $previousStage
    $env:DEYTT_PACKAGE_VERSION = $previousVersion
}
