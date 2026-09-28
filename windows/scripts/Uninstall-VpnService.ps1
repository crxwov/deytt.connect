$ErrorActionPreference = 'Stop'
$serviceName = 'DEYTTConnectVpn'
$installDirectory = Join-Path $env:ProgramFiles 'DEYTT\Connect'

$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [System.Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run the VPN service uninstaller as an administrator.'
}

$service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($null -ne $service) {
    if ($service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
        Stop-Service -Name $serviceName -Force
        (Get-Service -Name $serviceName).WaitForStatus(
            [System.ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds(20))
    }
    & sc.exe delete $serviceName | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Windows could not remove the VPN service.'
    }
}

Remove-Item -LiteralPath 'HKLM:\SOFTWARE\DEYTT\Connect' -Recurse -Force -ErrorAction SilentlyContinue
if (Test-Path -LiteralPath $installDirectory) {
    $existing = Get-Item -LiteralPath $installDirectory -Force
    if (($existing.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'The VPN installation directory cannot be a reparse point.'
    }
    Remove-Item -LiteralPath $installDirectory -Recurse -Force
}
exit 0
