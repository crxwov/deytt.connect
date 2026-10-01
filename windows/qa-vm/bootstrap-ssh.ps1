[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^ssh-ed25519 [A-Za-z0-9+/]+={0,2}( [^\r\n]+)?$')]
    [string] $PublicKey,

    [switch] $Rollback
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$qaUser = 'deyttqa'
$root = Join-Path $env:ProgramData 'DEYTT\QA'
$dispatchPath = Join-Path $root 'ssh-dispatcher.ps1'
$statePath = Join-Path $root 'ssh-bootstrap-state.json'
$authorizedKeyPath = Join-Path $env:SystemDrive 'Users\deyttqa\.ssh\authorized_keys'
$configPath = Join-Path $env:ProgramData 'ssh\sshd_config'
$ruleName = 'DEYTT-QA-OpenSSH-Loopback'
$taskName = 'DEYTT_QA_Fixed_MSI_Installer'
$taskPath = Join-Path $root 'fixed-msi-installer.ps1'
$script:mutationStarted = $false

function Assert-Elevated {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Open this PowerShell window with Run as administrator.'
    }
}

function Set-PrivateAcl([string] $Path, [string[]] $Principals) {
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($principal in $Principals) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            $principal, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Get-RandomPassword {
    $bytes = [byte[]]::new(32)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    # The password is assigned only to a standard local QA account; password SSH is disabled.
    return [Convert]::ToBase64String($bytes) + 'aA1!'
}

function Install-Bootstrap {
    Assert-Elevated
    $product = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion'
    $build = 0
    if ($product.ProductName -notlike 'Windows 10*' -or [IntPtr]::Size -ne 8 -or
        -not [int]::TryParse([string]$product.CurrentBuildNumber, [ref]$build) -or $build -lt 17763) {
        throw 'This bootstrap is restricted to the Windows 10 x64 QA VM.'
    }
    if (Test-Path -LiteralPath $root) { throw 'QA bootstrap state already exists; inspect it or use -Rollback.' }
    if (Get-LocalUser -Name $qaUser -ErrorAction SilentlyContinue) { throw 'The reserved QA account already exists.' }
    if (Get-Service -Name sshd -ErrorAction SilentlyContinue) { throw 'OpenSSH already exists; refusing to replace its service or configuration.' }
    if (Get-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue) {
        throw 'The default OpenSSH firewall rule already exists; refusing to change a pre-existing rule.'
    }
    if (Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue) { throw 'The reserved QA firewall rule already exists.' }
    if (Test-Path -LiteralPath $configPath) { throw 'An sshd_config already exists; refusing to replace it.' }
    $keyParts = $PublicKey.Trim().Split(' ', 3, [StringSplitOptions]::RemoveEmptyEntries)
    if ($keyParts.Count -lt 2 -or $keyParts[0] -ne 'ssh-ed25519') { throw 'Only an Ed25519 public key is accepted.' }
    $keyBlob = [Convert]::FromBase64String($keyParts[1])
    if ($keyBlob.Length -ne 51 -or [Text.Encoding]::ASCII.GetString($keyBlob, 4, 11) -ne 'ssh-ed25519' -or
        $keyBlob[15] -ne 0 -or $keyBlob[16] -ne 0 -or $keyBlob[17] -ne 0 -or $keyBlob[18] -ne 32) {
        throw 'The public key does not contain a valid Ed25519 key blob.'
    }

    $capability = Get-WindowsCapability -Online -Name 'OpenSSH.Server~~~~0.0.1.0'
    $wasInstalled = $capability.State -eq 'Installed'
    $script:mutationStarted = $true
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    Set-PrivateAcl -Path $root -Principals @('NT AUTHORITY\SYSTEM', 'BUILTIN\Administrators')
    $state = [ordered]@{
        schema = 1
        qa_user_sid = $null
        openssh_was_installed = $wasInstalled
        created_at_utc = [DateTime]::UtcNow.ToString('o')
    }
    [IO.File]::WriteAllText($statePath, ($state | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
    if (-not $wasInstalled) {
        Add-WindowsCapability -Online -Name 'OpenSSH.Server~~~~0.0.1.0' | Out-Null
        Disable-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $configPath) { throw 'OpenSSH created an unexpected sshd_config; inspect it before continuing.' }
    $accountPassword = ConvertTo-SecureString (Get-RandomPassword) -AsPlainText -Force
    New-LocalUser -Name $qaUser -Password $accountPassword -AccountNeverExpires -PasswordNeverExpires `
        -UserMayNotChangePassword -Description 'DEYTT Windows QA SSH account' | Out-Null

    $userSsh = Join-Path $env:SystemDrive 'Users\deyttqa\.ssh'
    New-Item -ItemType Directory -Path $userSsh -Force | Out-Null
    Set-PrivateAcl -Path $userSsh -Principals @('NT AUTHORITY\SYSTEM', 'BUILTIN\Administrators', $qaUser)
    $keyLine = 'no-pty,no-port-forwarding,no-agent-forwarding,no-X11-forwarding,restrict,command="powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:\ProgramData\DEYTT\QA\ssh-dispatcher.ps1" ' + $PublicKey.Trim()
    [IO.File]::WriteAllText($authorizedKeyPath, $keyLine + "`r`n", [Text.UTF8Encoding]::new($false))
    $keyAcl = Get-Acl -LiteralPath $authorizedKeyPath
    $keyAcl.SetAccessRuleProtection($true, $false)
    foreach ($name in @('NT AUTHORITY\SYSTEM', 'BUILTIN\Administrators', $qaUser)) {
        $keyAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($name, 'FullControl', 'Allow'))
    }
    Set-Acl -LiteralPath $authorizedKeyPath -AclObject $keyAcl

    $dispatcher = @'
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$command = [string]$env:SSH_ORIGINAL_COMMAND
switch ($command) {
    'qa-status' {
        $os = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion'
        [ordered]@{
            schema = 1
            windows10_x64 = ($os.ProductName -like 'Windows 10*' -and [IntPtr]::Size -eq 8)
            ssh_service = [string](Get-Service -Name sshd).Status
            installer_task = [bool](Get-ScheduledTask -TaskName 'DEYTT_QA_Fixed_MSI_Installer' -ErrorAction SilentlyContinue)
        } | ConvertTo-Json -Compress
    }
    'installer-run' {
        $task = Get-ScheduledTask -TaskName 'DEYTT_QA_Fixed_MSI_Installer' -ErrorAction SilentlyContinue
        if ($null -eq $task -or $task.State -eq 'Disabled') { throw 'The fixed MSI task is not provisioned.' }
        Start-ScheduledTask -TaskName 'DEYTT_QA_Fixed_MSI_Installer'
        'started'
    }
    default { throw 'Command is not in the QA allowlist.' }
}
'@

    $fixedRunner = @'
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# Deliberately fail closed until a fixed-path, independently reviewed MSI runner is provisioned.
throw 'Fixed MSI runner is not provisioned.'
'@
    [IO.File]::WriteAllText($dispatchPath, $dispatcher, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText($taskPath, $fixedRunner, [Text.UTF8Encoding]::new($false))
    foreach ($path in @($dispatchPath, $taskPath)) {
        $acl = Get-Acl -LiteralPath $path
        $acl.SetAccessRuleProtection($true, $false)
        foreach ($name in @('NT AUTHORITY\SYSTEM', 'BUILTIN\Administrators')) {
            $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($name, 'FullControl', 'Allow'))
        }
        Set-Acl -LiteralPath $path -AclObject $acl
    }
    Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $root 'bootstrap-ssh.ps1')
    $bootstrapAcl = Get-Acl -LiteralPath (Join-Path $root 'bootstrap-ssh.ps1')
    $bootstrapAcl.SetAccessRuleProtection($true, $false)
    foreach ($name in @('NT AUTHORITY\SYSTEM', 'BUILTIN\Administrators')) {
        $bootstrapAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($name, 'FullControl', 'Allow'))
    }
    Set-Acl -LiteralPath (Join-Path $root 'bootstrap-ssh.ps1') -AclObject $bootstrapAcl

    $sshDirectory = Split-Path -Parent $configPath
    if (-not (Test-Path -LiteralPath $sshDirectory)) { New-Item -ItemType Directory -Path $sshDirectory -Force | Out-Null }
    $config = @'
Port 22
AddressFamily any
ListenAddress 0.0.0.0
ListenAddress ::
PubkeyAuthentication yes
PasswordAuthentication no
AuthenticationMethods publickey
AllowUsers deyttqa
AuthorizedKeysFile .ssh/authorized_keys
PermitTTY no
AllowTcpForwarding no
AllowAgentForwarding no
X11Forwarding no
ForceCommand powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:\ProgramData\DEYTT\QA\ssh-dispatcher.ps1
'@
    [IO.File]::WriteAllText($configPath, $config + "`r`n", [Text.UTF8Encoding]::new($false))
    $sshExe = Join-Path $env:WINDIR 'System32\OpenSSH\sshd.exe'
    & $sshExe -t -f $configPath
    if ($LASTEXITCODE -ne 0) { throw 'sshd rejected its configuration; rollback is required.' }

    New-NetFirewallRule -Name $ruleName -DisplayName 'DEYTT QA SSH from VirtualBox NAT only' `
        -Direction Inbound -Action Allow -Protocol TCP -LocalPort 22 -RemoteAddress '10.0.2.2/32' -Profile Any | Out-Null

    $action = New-ScheduledTaskAction -Execute (Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe') `
        -Argument ('-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $taskPath + '"')
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Minutes 30) -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $taskName -Action $action -Principal $principal -Settings $settings `
        -Description 'Fixed, parameterless DEYTT Windows MSI QA action. The script path is ACL protected.' | Out-Null
    $qaSid = [string](Get-LocalUser -Name $qaUser).SID
    $scheduler = New-Object -ComObject Schedule.Service
    $scheduler.Connect()
    $registeredTask = $scheduler.GetFolder('\').GetTask("\$taskName")
    $taskSddl = 'O:SYG:SYD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;GRGX;;;' + $qaSid + ')'
    $registeredTask.SetSecurityDescriptor($taskSddl, 0)
    if ([string]$registeredTask.GetSecurityDescriptor(5) -notmatch [regex]::Escape($qaSid)) {
        throw 'The fixed task ACL did not retain the QA account read/execute grant.'
    }
    Disable-ScheduledTask -TaskName $taskName | Out-Null

    $state.qa_user_sid = $qaSid
    [IO.File]::WriteAllText($statePath, ($state | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
    Set-Service -Name sshd -StartupType Automatic
    Start-Service -Name sshd
    'Bootstrap installed. Use only qa-status until the fixed MSI runner receives independent review.'
}

function Remove-Bootstrap {
    Assert-Elevated
    Disable-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue
    if (-not (Test-Path -LiteralPath $statePath)) { throw 'Bootstrap state file is absent; refusing broad cleanup.' }
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    Stop-Service -Name sshd -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
    Remove-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue
    Remove-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue
    Remove-LocalUser -Name $qaUser -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $env:SystemDrive 'Users\deyttqa') -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $configPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $root -Recurse -Force
    if ($state.openssh_was_installed -eq $false) {
        Stop-Service -Name sshd -ErrorAction SilentlyContinue
        if ((Get-WindowsCapability -Online -Name 'OpenSSH.Server~~~~0.0.1.0').State -eq 'Installed') {
            Remove-WindowsCapability -Online -Name 'OpenSSH.Server~~~~0.0.1.0' | Out-Null
        }
    }
    'QA SSH bootstrap removed.'
}

if ($Rollback) {
    try { Remove-Bootstrap }
    catch {
        Disable-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue
        throw
    }
    exit 0
}
try { Install-Bootstrap }
catch {
    if ($script:mutationStarted) {
        Disable-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue
    }
    throw
}
