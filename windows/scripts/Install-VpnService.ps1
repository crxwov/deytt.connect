param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^S-1-')]
    [string] $AllowedUserSid,

    [string] $BundleRoot = $PSScriptRoot
)

$ErrorActionPreference = 'Stop'
$serviceName = 'DEYTTConnectVpn'
$displayName = 'deytt./connect VPN'
$vendorDirectory = Join-Path $env:ProgramFiles 'DEYTT'
$installDirectory = Join-Path $env:ProgramFiles 'DEYTT\Connect'

$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [System.Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'run the vpn service installer as an administrator.'
}

$sid = [System.Security.Principal.SecurityIdentifier]::new($AllowedUserSid)
$bundlePath = (Resolve-Path -LiteralPath $BundleRoot).Path
$bundleFullPath = [System.IO.Path]::GetFullPath($bundlePath).TrimEnd([char[]]@('\', '/'))
$installFullPath = [System.IO.Path]::GetFullPath($installDirectory).TrimEnd([char[]]@('\', '/'))
$inPlaceMsiPayload = [string]::Equals($bundleFullPath, $installFullPath, [System.StringComparison]::OrdinalIgnoreCase) -and
    (Test-Path -LiteralPath (Join-Path $bundlePath 'DeyttConnect.Windows.Service.exe') -PathType Leaf) -and
    (Test-Path -LiteralPath (Join-Path $bundlePath 'DeyttVpnEngine.exe') -PathType Leaf)
$portableEngineSource = Join-Path $bundlePath 'vpn\DeyttVpnEngine.exe'
$engineSource = if (Test-Path -LiteralPath $portableEngineSource -PathType Leaf) {
    $portableEngineSource
} else {
    Join-Path $bundlePath 'DeyttVpnEngine.exe'
}
$serviceSource = if ($inPlaceMsiPayload) { $bundlePath } else { Join-Path $bundlePath 'service' }
$serviceExecutable = Join-Path $serviceSource 'DeyttConnect.Windows.Service.exe'
if (-not $inPlaceMsiPayload) {
    $msiRegistration = Get-ChildItem -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall' -ErrorAction SilentlyContinue |
        Get-ItemProperty | Where-Object { $_.DisplayName -in @('deytt./connect', 'deytt connect', 'DEYTT Connect') -and $_.WindowsInstaller -eq 1 } | Select-Object -First 1
    if ($null -ne $msiRegistration) {
        throw 'the MSI manages this installation. Use its installer to repair it; portable setup cannot replace MSI files.'
    }
    $installPrefix = $installFullPath + [System.IO.Path]::DirectorySeparatorChar
    $sourcePaths = @($bundleFullPath, [System.IO.Path]::GetFullPath($engineSource),
        [System.IO.Path]::GetFullPath($serviceExecutable))
    foreach ($sourcePath in $sourcePaths) {
        $canonicalSourcePath = [System.IO.Path]::GetFullPath($sourcePath)
        if ($canonicalSourcePath.StartsWith($installPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw 'the vpn package source cannot be inside the installation directory.'
        }
    }
}
if (-not (Test-Path -LiteralPath $engineSource -PathType Leaf) -or
    -not (Test-Path -LiteralPath $serviceExecutable -PathType Leaf)) {
    throw 'the complete windows vpn package is required.'
}

foreach ($directoryPath in @($vendorDirectory, $installDirectory)) {
    if (Test-Path -LiteralPath $directoryPath) {
        $existing = Get-Item -LiteralPath $directoryPath -Force
        if (($existing.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'the vpn installation path cannot contain a reparse point.'
        }
    }
}

$service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($null -ne $service -and $service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
    Stop-Service -Name $serviceName -Force
    (Get-Service -Name $serviceName).WaitForStatus(
        [System.ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds(20))
}

New-Item -ItemType Directory -Path $installDirectory -Force | Out-Null
$adminsSid = [System.Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
$systemSid = [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18')
$usersSid = [System.Security.Principal.SecurityIdentifier]::new('S-1-5-32-545')
$directorySecurity = [System.Security.AccessControl.DirectorySecurity]::new()
$directorySecurity.SetAccessRuleProtection($true, $false)
$directorySecurity.SetOwner($adminsSid)
$directorySecurity.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new(
    $systemSid, [System.Security.AccessControl.FileSystemRights]::FullControl,
    [System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit,
    [System.Security.AccessControl.PropagationFlags]::None, [System.Security.AccessControl.AccessControlType]::Allow))
$directorySecurity.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new(
    $adminsSid, [System.Security.AccessControl.FileSystemRights]::FullControl,
    [System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit,
    [System.Security.AccessControl.PropagationFlags]::None, [System.Security.AccessControl.AccessControlType]::Allow))
$directorySecurity.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new(
    $usersSid, [System.Security.AccessControl.FileSystemRights]::ReadAndExecute,
    [System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit,
    [System.Security.AccessControl.PropagationFlags]::None, [System.Security.AccessControl.AccessControlType]::Allow))
Set-Acl -LiteralPath $installDirectory -AclObject $directorySecurity
if (-not $inPlaceMsiPayload) {
    Copy-Item -Path (Join-Path $serviceSource '*') -Destination $installDirectory -Recurse -Force
    Copy-Item -LiteralPath $engineSource -Destination (Join-Path $installDirectory 'DeyttVpnEngine.exe') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Uninstall-VpnService.ps1') -Destination $installDirectory -Force
    $managedFiles = @(Get-ChildItem -LiteralPath $serviceSource -Recurse -File | ForEach-Object {
        $_.FullName.Substring($serviceSource.TrimEnd('\').Length + 1)
    }) + @('DeyttVpnEngine.exe', 'Uninstall-VpnService.ps1')
    $manifest = @{ files = @($managedFiles | Sort-Object -Unique); owner_sid = $sid.Value }
    $manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $installDirectory '.deytt-portable-service.json') -Encoding UTF8
    $uninstallKey = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\DEYTTConnectVpn.Portable'
    New-Item -Path $uninstallKey -Force | Out-Null
    $powershellPath = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $uninstallCommand = '"' + $powershellPath + '" -NoProfile -ExecutionPolicy Bypass -File "' +
        (Join-Path $installDirectory 'Uninstall-VpnService.ps1') + '" -RemoveAppData'
    foreach ($property in @{
        DisplayName = 'deytt./connect VPN (portable)'; Publisher = 'deytt.';
        InstallLocation = $installDirectory; DisplayIcon = (Join-Path $installDirectory 'DeyttConnect.Windows.Service.exe');
        UninstallString = $uninstallCommand
    }.GetEnumerator()) {
        New-ItemProperty -Path $uninstallKey -Name $property.Key -Value $property.Value -PropertyType String -Force | Out-Null
    }
    foreach ($name in @('NoModify', 'NoRepair')) {
        New-ItemProperty -Path $uninstallKey -Name $name -Value 1 -PropertyType DWord -Force | Out-Null
    }
}

$registryPath = 'HKLM:\SOFTWARE\DEYTT\Connect'
New-Item -Path $registryPath -Force | Out-Null
New-ItemProperty -Path $registryPath -Name 'AllowedUserSid' -Value $sid.Value -PropertyType String -Force | Out-Null

$servicePath = Join-Path $installDirectory 'DeyttConnect.Windows.Service.exe'
$binaryPath = '"' + $servicePath + '" --service'
if ($null -eq $service) {
    New-Service -Name $serviceName -DisplayName $displayName -Description 'VPN tunnel service for deytt./connect.' `
        -BinaryPathName $binaryPath -StartupType Automatic | Out-Null
} else {
    & sc.exe config $serviceName "binPath= $binaryPath" 'start= auto' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'windows could not update the vpn service configuration.'
    }
}

Start-Service -Name $serviceName
(Get-Service -Name $serviceName).WaitForStatus(
    [System.ServiceProcess.ServiceControllerStatus]::Running, [TimeSpan]::FromSeconds(20))
exit 0

