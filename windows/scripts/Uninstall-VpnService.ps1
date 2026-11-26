[CmdletBinding()]
param([switch]$RemoveAppData)

$ErrorActionPreference = 'Stop'
$serviceName = 'DEYTTConnectVpn'
$installDirectory = [IO.Path]::GetFullPath((Join-Path $env:ProgramFiles 'DEYTT\Connect'))
$manifestPath = Join-Path $installDirectory '.deytt-portable-service.json'
$registryPath = 'HKLM:\SOFTWARE\DEYTT\Connect'
$uninstallKey = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\DEYTTConnectVpn.Portable'

# MSI owns its own lifecycle. Never directly erase registered MSI files.
$msi = Get-ChildItem -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall' -ErrorAction SilentlyContinue |
    Get-ItemProperty | Where-Object { $_.DisplayName -in @('deytt./connect', 'deytt connect', 'DEYTT Connect') -and $_.WindowsInstaller -eq 1 } | Select-Object -First 1
if ($null -ne $msi) {
    if ($msi.PSChildName -notmatch '^\{[0-9A-Fa-f-]{36}\}$') { throw 'the registered MSI identity is invalid.' }
    $removal = Start-Process -FilePath (Join-Path $env:WINDIR 'System32\msiexec.exe') -ArgumentList @('/x', $msi.PSChildName) -Wait -PassThru -WindowStyle Hidden
    exit $removal.ExitCode
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath + '"'
    if ($RemoveAppData) { $arguments += ' -RemoveAppData' }
    $removal = Start-Process -FilePath (Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe') -Verb RunAs -ArgumentList $arguments -Wait -PassThru -WindowStyle Hidden
    exit $removal.ExitCode
}

function Test-PlainPath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path) -or -not [IO.Path]::IsPathRooted($Path)) { return $false }
    $current = [IO.Path]::GetFullPath($Path)
    while ($current) {
        if (Test-Path -LiteralPath $current) {
            if (((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { return $false }
        }
        $current = [IO.Path]::GetDirectoryName($current)
    }
    return $true
}
function Remove-OwnedCache([string]$Path) {
    if (-not (Test-PlainPath $Path) -or -not (Test-Path -LiteralPath $Path -PathType Container)) { return }
    $pending = [Collections.Generic.Stack[string]]::new()
    $pending.Push($Path)
    while ($pending.Count -gt 0) {
        foreach ($child in Get-ChildItem -LiteralPath $pending.Pop() -Force) {
            if (($child.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { return }
            if ($child.PSIsContainer) { $pending.Push($child.FullName) }
        }
    }
    foreach ($child in Get-ChildItem -LiteralPath $Path -Force) {
        if (-not (Test-PlainPath $child.FullName)) { continue }
        if ($child.PSIsContainer) { Remove-OwnedCache $child.FullName }
        else { Remove-Item -LiteralPath $child.FullName -Force }
    }
    if (-not (Get-ChildItem -LiteralPath $Path -Force | Select-Object -First 1)) { Remove-Item -LiteralPath $Path -Force }
}
function Get-ManagedFilePaths([string]$Directory, [string[]]$Files) {
    $directoryFullPath = [IO.Path]::GetFullPath($Directory).TrimEnd('\')
    $prefix = $directoryFullPath + '\'
    foreach ($relative in $Files) {
        if ([string]::IsNullOrWhiteSpace($relative) -or [IO.Path]::IsPathRooted($relative)) { throw 'the portable file manifest contains an unsafe path.' }
        $path = [IO.Path]::GetFullPath((Join-Path $directoryFullPath $relative))
        if (-not $path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -or -not (Test-PlainPath $path)) {
            throw 'the portable file manifest contains an unsafe path.'
        }
        $path
    }
}

if (-not (Test-PlainPath $installDirectory)) { throw 'the installation path contains a reparse point.' }
$ownerSid = (Get-ItemProperty -LiteralPath $registryPath -ErrorAction SilentlyContinue).AllowedUserSid
$manifest = if (Test-Path -LiteralPath $manifestPath -PathType Leaf) { Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json } else { $null }
$serviceRegistry = Get-ItemProperty -LiteralPath "HKLM:\SYSTEM\CurrentControlSet\Services\$serviceName" -ErrorAction SilentlyContinue
if ($null -ne $serviceRegistry) {
    $expectedCommand = '"' + (Join-Path $installDirectory 'DeyttConnect.Windows.Service.exe') + '" --service'
    if ($serviceRegistry.ImagePath -ne $expectedCommand) { throw 'the VPN service points outside the managed portable installation; removal stopped.' }
}
if ($null -eq $manifest -and (Test-Path -LiteralPath $installDirectory)) {
    throw 'this legacy service has no file manifest. Remove it with its installer; automatic file deletion is unsafe.'
}

$installPrefix = $installDirectory.TrimEnd('\') + '\'
$ownedPaths = @()
if ($null -ne $manifest) {
    $ownedPaths = @(Get-ManagedFilePaths $installDirectory @($manifest.files))
}
$service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($null -ne $service) {
    if ($service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
        Stop-Service -Name $serviceName -Force
        (Get-Service -Name $serviceName).WaitForStatus([ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds(20))
    }
    & sc.exe delete $serviceName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'windows could not remove the vpn service.' }
}
foreach ($path in $ownedPaths) {
    if (Test-Path -LiteralPath $path -PathType Leaf) { Remove-Item -LiteralPath $path -Force }
    $parent = [IO.Path]::GetDirectoryName($path)
    while ($parent.StartsWith($installPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        if ((Test-PlainPath $parent) -and (Test-Path -LiteralPath $parent) -and -not (Get-ChildItem -LiteralPath $parent -Force | Select-Object -First 1)) { Remove-Item -LiteralPath $parent -Force }
        $parent = [IO.Path]::GetDirectoryName($parent)
    }
}
if ($null -ne $manifest) { Remove-Item -LiteralPath $manifestPath -Force }

$runtimeDirectory = Join-Path $env:ProgramData 'DEYTT\Connect\runtime'
if ((Test-PlainPath $runtimeDirectory) -and (Test-Path -LiteralPath $runtimeDirectory -PathType Container)) {
    foreach ($file in Get-ChildItem -LiteralPath $runtimeDirectory -Filter '*.json' -File) {
        $profileId = [Guid]::Empty
        if ([Guid]::TryParseExact($file.BaseName, 'N', [ref]$profileId) -and (Test-PlainPath $file.FullName)) { Remove-Item -LiteralPath $file.FullName -Force }
    }
    if (-not (Get-ChildItem -LiteralPath $runtimeDirectory -Force | Select-Object -First 1)) { Remove-Item -LiteralPath $runtimeDirectory -Force }
}
if ($RemoveAppData -and $ownerSid -match '^S-1-') {
    $profile = Get-ItemProperty -LiteralPath "HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\ProfileList\$ownerSid" -ErrorAction SilentlyContinue
    if ($null -ne $profile.ProfileImagePath) {
        $userDirectory = Join-Path ([Environment]::ExpandEnvironmentVariables($profile.ProfileImagePath)) 'AppData\Local\DEYTT\Connect'
        if (Test-PlainPath $userDirectory) {
            foreach ($name in @('telegram-session.dat', 'subscription-profile.dat', 'preferences.json', 'installation-id')) {
                $path = Join-Path $userDirectory $name
                if ((Test-PlainPath $path) -and (Test-Path -LiteralPath $path -PathType Leaf)) { Remove-Item -LiteralPath $path -Force }
                if (Test-Path -LiteralPath $userDirectory -PathType Container) {
                    foreach ($temporary in Get-ChildItem -LiteralPath $userDirectory -Filter "$name.*.tmp" -File) {
                        if ($temporary.Name -match ('^' + [regex]::Escape($name) + '\.[0-9a-f]{32}\.tmp$') -and (Test-PlainPath $temporary.FullName)) { Remove-Item -LiteralPath $temporary.FullName -Force }
                    }
                }
            }
            Remove-OwnedCache (Join-Path $userDirectory 'updates')
            Remove-OwnedCache (Join-Path $userDirectory 'WebView2')
            if ((Test-Path -LiteralPath $userDirectory) -and -not (Get-ChildItem -LiteralPath $userDirectory -Force | Select-Object -First 1)) { Remove-Item -LiteralPath $userDirectory -Force }
        }
    }
}
foreach ($name in @('AllowedUserSid', 'StartMenuShortcut')) { Remove-ItemProperty -LiteralPath $registryPath -Name $name -ErrorAction SilentlyContinue }
if (Test-Path -LiteralPath $registryPath) {
    $remainingKey = Get-Item -LiteralPath $registryPath
    if ($remainingKey.GetValueNames().Count -eq 0 -and $remainingKey.GetSubKeyNames().Count -eq 0) { Remove-Item -LiteralPath $registryPath -Force }
}
Remove-Item -LiteralPath $uninstallKey -Force -ErrorAction SilentlyContinue
if ((Test-Path -LiteralPath $installDirectory) -and -not (Get-ChildItem -LiteralPath $installDirectory -Force | Select-Object -First 1)) { Remove-Item -LiteralPath $installDirectory -Force }
Write-Output 'Portable VPN service removed. You may delete the extracted portable folder. Server device slots can be released in the bot.'
exit 0
