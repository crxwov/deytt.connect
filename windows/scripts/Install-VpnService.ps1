param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^S-1-')]
    [string] $AllowedUserSid,

    [string] $BundleRoot = $PSScriptRoot
)

$ErrorActionPreference = 'Stop'
$serviceName = 'DEYTTConnectVpn'
$displayName = 'DEYTT Connect VPN'
$vendorDirectory = Join-Path $env:ProgramFiles 'DEYTT'
$installDirectory = Join-Path $env:ProgramFiles 'DEYTT\Connect'

$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [System.Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run the VPN service installer as an administrator.'
}

$sid = [System.Security.Principal.SecurityIdentifier]::new($AllowedUserSid)
$bundlePath = (Resolve-Path -LiteralPath $BundleRoot).Path
$engineSource = Join-Path $bundlePath 'DeyttVpnEngine.exe'
$serviceSource = Join-Path $bundlePath 'service'
$serviceExecutable = Join-Path $serviceSource 'DeyttConnect.Windows.Service.exe'
if (-not (Test-Path -LiteralPath $engineSource -PathType Leaf) -or
    -not (Test-Path -LiteralPath $serviceExecutable -PathType Leaf)) {
    throw 'The complete Windows VPN package is required.'
}

foreach ($directoryPath in @($vendorDirectory, $installDirectory)) {
    if (Test-Path -LiteralPath $directoryPath) {
        $existing = Get-Item -LiteralPath $directoryPath -Force
        if (($existing.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'The VPN installation path cannot contain a reparse point.'
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
Get-ChildItem -LiteralPath $installDirectory -Force | Remove-Item -Recurse -Force
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
Copy-Item -Path (Join-Path $serviceSource '*') -Destination $installDirectory -Recurse -Force
Copy-Item -LiteralPath $engineSource -Destination (Join-Path $installDirectory 'DeyttVpnEngine.exe') -Force

$registryPath = 'HKLM:\SOFTWARE\DEYTT\Connect'
New-Item -Path $registryPath -Force | Out-Null
New-ItemProperty -Path $registryPath -Name 'AllowedUserSid' -Value $sid.Value -PropertyType String -Force | Out-Null

$servicePath = Join-Path $installDirectory 'DeyttConnect.Windows.Service.exe'
$binaryPath = '"' + $servicePath + '" --service'
if ($null -eq $service) {
    New-Service -Name $serviceName -DisplayName $displayName -Description 'Runs the DEYTT Connect Windows VPN tunnel.' `
        -BinaryPathName $binaryPath -StartupType Automatic | Out-Null
} else {
    & sc.exe config $serviceName "binPath= $binaryPath" 'start= auto' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Windows could not update the VPN service configuration.'
    }
}

Start-Service -Name $serviceName
(Get-Service -Name $serviceName).WaitForStatus(
    [System.ServiceProcess.ServiceControllerStatus]::Running, [TimeSpan]::FromSeconds(20))
exit 0
