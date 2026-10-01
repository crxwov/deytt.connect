param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$ExpectedManifestSha256
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Fixed native WiX 6 / one-version MSI install-uninstall acceptance action.
# No parameters, UI launch, VPN account data, profile, or tunnel command is used.
$runId = '__RUN_ID__'
if ($runId -notmatch '^[0-9TZ-]+$') { throw [System.ArgumentException]::new('Invalid QA run identifier.') }
$userTemp = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)) 'Temp'
$inputRoot = Join-Path $userTemp ('deytt-qa-installer-source-' + $runId)
$outputRoot = $inputRoot
$startedPath = Join-Path $outputRoot 'installer-started.txt'
$reportPath = Join-Path $outputRoot 'installer-report.json'
$completePath = Join-Path $outputRoot 'installer-complete.txt'
$programData = [Environment]::GetFolderPath([Environment+SpecialFolder]::CommonApplicationData)
$tempRoot = Join-Path $programData ('DeyttQaInstaller-' + $runId)
$productVersion = '1.0.0'
$testSid = 'S-1-5-18' # SYSTEM: fixed test identity, not a guest account SID.
$result = [ordered]@{
    schema = 1
    result = 'failed'
    blocker = $null
    windows10_x64 = $false
    elevated_session = $false
    preexisting_install_clear = $false
    dotnet10_sdk = $false
    wix6_build_succeeded = $false
    msi_built = $false
    msi_sha256_valid = $false
    msi_sha256 = $null
    install_attempted = $false
    install_exit_code = $null
    wix_build_exit_code = $null
    installed_state_verified = $false
    uninstall_attempted = $false
    uninstall_exit_code = $null
    post_uninstall_clean = $false
    cleanup_safe = $false
    no_child_processes = $false
    elapsed_seconds = 0
    timestamp_utc = [DateTime]::UtcNow.ToString('o')
}
$startedAt = [DateTime]::UtcNow
$processTimedOut = $false
$installedProductCode = $null
$installerTouched = $false
$script:ownsProtectedRoot = $false

function Get-Sha256([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    try {
        $sha = [Security.Cryptography.SHA256]::Create()
        try { return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose() }
    }
    finally { $stream.Dispose() }
}

function Test-NotReparsePoint([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    return (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0)
}

function Test-NoReparseChain([string]$Path) {
    $fullPath = [IO.Path]::GetFullPath($Path)
    $current = [IO.Path]::GetPathRoot($fullPath)
    $tail = $fullPath.Substring($current.Length)
    foreach ($segment in $tail.Split([char]'\', [StringSplitOptions]::RemoveEmptyEntries)) {
        $current = Join-Path $current $segment
        if (-not (Test-Path -LiteralPath $current) -or -not (Test-NotReparsePoint $current)) { return $false }
    }
    return $true
}

function New-ProtectedDirectory([string]$Path) {
    if (Test-Path -LiteralPath $Path) {
        throw [IO.IOException]::new('Protected staging path already exists.')
    }
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $admins = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    $acl.SetOwner($admins)
    foreach ($sid in @($admins, $system)) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            $sid, [Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit',
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow)
        [void]$acl.AddAccessRule($rule)
    }
    [void][IO.Directory]::CreateDirectory($Path, $acl)
    if (-not (Test-NotReparsePoint $Path)) { throw [IO.IOException]::new('Protected staging path is a reparse point.') }
    $actual = Get-Acl -LiteralPath $Path
    if (-not $actual.AreAccessRulesProtected) { throw [Security.SecurityException]::new('Protected staging ACL is inheriting.') }
    $ownerSid = $actual.GetOwner([Security.Principal.SecurityIdentifier]).Value
    if ($ownerSid -notin @('S-1-5-32-544', 'S-1-5-18')) {
        throw [Security.SecurityException]::new('Protected staging directory owner is not trusted.')
    }
    $rules = @($actual.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    if ($rules.Count -ne 2 -or @($rules | Where-Object {
        $_.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
        $_.FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl -or
        $_.IdentityReference.Value -notin @('S-1-5-32-544', 'S-1-5-18')
    }).Count -ne 0) { throw [Security.SecurityException]::new('Protected staging ACL is not restricted to Administrators and SYSTEM.') }
    $script:ownsProtectedRoot = $true
}

function Test-ManifestPath([string]$Relative) {
    if ([string]::IsNullOrWhiteSpace($Relative) -or $Relative.Contains('\') -or
        [IO.Path]::IsPathRooted($Relative) -or $Relative.Contains(':')) { return $false }
    foreach ($segment in $Relative.Split('/')) {
        if ($segment -in @('', '.', '..') -or $segment -match '[<>:"|?*\x00-\x1f]' -or
            $segment.EndsWith('.') -or $segment.EndsWith(' ') -or
            ($segment -split '\.', 2)[0] -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$') { return $false }
    }
    return $true
}

function Test-JsonInteger($Value) {
    return ($Value -is [int] -or $Value -is [long])
}

function Invoke-BoundedProcess([string]$FileName, [string]$Arguments, [int]$TimeoutSeconds) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $FileName
    $info.Arguments = $Arguments
    $info.WorkingDirectory = $tempRoot
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $proc = [Diagnostics.Process]::Start($info)
    $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
    $stderrTask = $proc.StandardError.ReadToEndAsync()
    if (-not $proc.WaitForExit($TimeoutSeconds * 1000)) {
        $script:processTimedOut = $true
        $treeTerminated = $false
        $taskkillProcess = $null
        try {
            $taskkill = [Diagnostics.ProcessStartInfo]::new()
            $taskkill.FileName = Join-Path $env:SystemRoot 'System32\taskkill.exe'
            $taskkill.Arguments = '/PID ' + $proc.Id + ' /T /F'
            $taskkill.UseShellExecute = $false
            $taskkill.CreateNoWindow = $true
            $taskkillProcess = [Diagnostics.Process]::Start($taskkill)
            if ($taskkillProcess.WaitForExit(10000)) {
                $treeTerminated = ($taskkillProcess.ExitCode -eq 0)
            }
            else {
                try { $taskkillProcess.Kill() } catch { }
                try { $null = $taskkillProcess.WaitForExit(5000) } catch { }
            }
        }
        catch { }
        # Reissue the tree kill if the first bounded attempt failed or hung.
        if (-not $treeTerminated) {
            try {
                $retry = [Diagnostics.ProcessStartInfo]::new()
                $retry.FileName = Join-Path $env:SystemRoot 'System32\taskkill.exe'
                $retry.Arguments = '/PID ' + $proc.Id + ' /T /F'
                $retry.UseShellExecute = $false
                $retry.CreateNoWindow = $true
                $retryProcess = [Diagnostics.Process]::Start($retry)
                if ($retryProcess.WaitForExit(10000)) { $treeTerminated = ($retryProcess.ExitCode -eq 0) }
                else {
                    try { $retryProcess.Kill() } catch { }
                    try { $null = $retryProcess.WaitForExit(5000) } catch { }
                }
            }
            catch { }
        }
        try { $null = $proc.WaitForExit(10000) } catch { }
        $proc.Refresh()
        if (-not $proc.HasExited) {
            try { $proc.Kill() } catch { }
            try { $null = $proc.WaitForExit(5000) } catch { }
        }
        $proc.Refresh()
        $rootExited = $proc.HasExited
        return [pscustomobject]@{
            TimedOut = $true
            Terminated = ($treeTerminated -and $rootExited)
            RootExited = $rootExited
            TreeTerminated = $treeTerminated
            ExitCode = $null
            StdOut = ''
            StdErr = ''
        }
    }
    $proc.WaitForExit()
    return [pscustomobject]@{
        TimedOut = $false
        Terminated = $true
        RootExited = $true
        TreeTerminated = $true
        ExitCode = $proc.ExitCode
        StdOut = $stdoutTask.GetAwaiter().GetResult()
        StdErr = $stderrTask.GetAwaiter().GetResult()
    }
}

function Save-PrivateProcessLog([string]$Name, $ProcessResult) {
    [IO.File]::WriteAllText((Join-Path $tempRoot $Name),
        ([string]$ProcessResult.StdOut + [Environment]::NewLine + [string]$ProcessResult.StdErr),
        [Text.UTF8Encoding]::new($false))
}

function Get-ProductEntries {
    $root = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall'
    if (-not (Test-Path -LiteralPath $root)) { return @() }
    $items = @()
    foreach ($key in Get-ChildItem -LiteralPath $root -ErrorAction SilentlyContinue) {
        $item = Get-ItemProperty -LiteralPath $key.PSPath -ErrorAction SilentlyContinue
        if ($item.DisplayName -eq 'DEYTT Connect' -and $item.Publisher -eq 'DEYTT') {
            $items += [pscustomobject]@{
                KeyName = $key.PSChildName
                DisplayVersion = [string]$item.DisplayVersion
            }
        }
    }
    return $items
}

function Get-InstallState {
    $service = Get-Service -Name 'DEYTTConnectVpn' -ErrorAction SilentlyContinue
    $sidPresent = $false
    $registryPath = 'HKLM:\SOFTWARE\DEYTT\Connect'
    if (Test-Path -LiteralPath $registryPath) {
        $registryItem = Get-ItemProperty -LiteralPath $registryPath -ErrorAction SilentlyContinue
        $sidPresent = $null -ne $registryItem.PSObject.Properties['AllowedUserSid']
    }
    $installPath = Join-Path $env:ProgramFiles 'DEYTT\Connect'
    $shortcutPath = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::CommonPrograms)) 'DEYTT Connect\DEYTT Connect.lnk'
    return [pscustomobject]@{
        ProductCount = @(Get-ProductEntries).Count
        ServicePresent = $null -ne $service
        SidPresent = $sidPresent
        InstallPathPresent = Test-Path -LiteralPath $installPath
        ShortcutPresent = Test-Path -LiteralPath $shortcutPath
    }
}

function Test-InstallStateClear($State) {
    return ($State.ProductCount -eq 0 -and -not $State.ServicePresent -and
        -not $State.SidPresent -and -not $State.InstallPathPresent -and -not $State.ShortcutPresent)
}

function Stop-RunChildProcesses {
    # Only processes whose command line contains this unique run directory are candidates.
    $needle = $tempRoot.ToLowerInvariant()
    try {
        $children = @(Get-CimInstance -ClassName Win32_Process -OperationTimeoutSec 15 -ErrorAction Stop | Where-Object {
            $_.ProcessId -ne $PID -and -not [string]::IsNullOrEmpty($_.CommandLine) -and
            $_.CommandLine.ToLowerInvariant().Contains($needle)
        })
        if ($children.Count -gt 32) { return $false }
        foreach ($child in $children) {
            $killer = [Diagnostics.ProcessStartInfo]::new()
            $killer.FileName = Join-Path $env:SystemRoot 'System32\taskkill.exe'
            $killer.Arguments = '/PID ' + [int]$child.ProcessId + ' /T /F'
            $killer.UseShellExecute = $false
            $killer.CreateNoWindow = $true
            $killProcess = [Diagnostics.Process]::Start($killer)
            if (-not $killProcess.WaitForExit(15000)) {
                try { $killProcess.Kill() } catch { }
                try { $null = $killProcess.WaitForExit(5000) } catch { }
                return $false
            }
        }
        Start-Sleep -Milliseconds 500
        $remaining = @(Get-CimInstance -ClassName Win32_Process -OperationTimeoutSec 15 -ErrorAction Stop | Where-Object {
            $_.ProcessId -ne $PID -and -not [string]::IsNullOrEmpty($_.CommandLine) -and
            $_.CommandLine.ToLowerInvariant().Contains($needle)
        })
        return ($remaining.Count -eq 0)
    }
    catch { return $false }
}

function Write-InstallerReport {
    $result.elapsed_seconds = [Math]::Round(([DateTime]::UtcNow - $startedAt).TotalSeconds, 2)
    $tempReport = $reportPath + '.tmp'
    [IO.File]::WriteAllText($tempReport, ($result | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
    [IO.File]::Move($tempReport, $reportPath)
    [IO.File]::WriteAllText($completePath, 'complete', [Text.UTF8Encoding]::new($false))
}

try {
    if (-not [IO.Directory]::Exists($inputRoot) -or -not [IO.Directory]::Exists($outputRoot)) {
        throw [IO.DirectoryNotFoundException]::new('Required installer QA share unavailable.')
    }
    foreach ($path in @($startedPath, $reportPath, ($reportPath + '.tmp'), $completePath)) {
        if ([IO.File]::Exists($path)) { throw [IO.IOException]::new('Refusing to overwrite an installer QA result.') }
    }
    [IO.File]::WriteAllText($startedPath, 'started', [Text.UTF8Encoding]::new($false))

    $osInfo = Get-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion'
    $result.windows10_x64 = ($osInfo.ProductName -like 'Windows 10*' -and [IntPtr]::Size -eq 8)
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    $result.elevated_session = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $result.windows10_x64) { $result.blocker = 'unsupported_guest_os'; return }
    if (-not $result.elevated_session) { $result.blocker = 'elevated_session_required'; return }

    $initialState = Get-InstallState
    $result.preexisting_install_clear = Test-InstallStateClear $initialState
    if (-not $result.preexisting_install_clear) { $result.blocker = 'preexisting_installation'; return }

    $sdkPath = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles)) 'dotnet\dotnet.exe'
    if (-not [IO.File]::Exists($sdkPath)) { $result.blocker = 'dotnet_sdk_missing'; return }
    # Trust the manifest only when its bytes match the hash supplied out-of-band by the host.
    $manifestPath = Join-Path $inputRoot 'manifest.json'
    if (-not (Test-NoReparseChain $inputRoot) -or -not (Test-NotReparsePoint $manifestPath) -or
        (Get-Sha256 $manifestPath) -ne $ExpectedManifestSha256) {
        throw [IO.InvalidDataException]::new('Host-pinned manifest digest mismatch.')
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if (-not (Test-JsonInteger $manifest.schema) -or $manifest.schema -ne 1 -or
        $manifest.files -isnot [Array] -or $manifest.files.Count -lt 1 -or $manifest.files.Count -gt 10000) {
        throw [IO.InvalidDataException]::new('Installer package manifest invalid.')
    }
    $allowedInputs = @{
        'installer/Package.wxs' = $true
        'installer/DEYTTConnect.Windows.Installer.wixproj' = $true
        'installer/installer-runner.ps1' = $true
        'installer/installer-collect.ps1' = $true
        'installer/installer-stage.ps1' = $true
    }
    $seenPaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $manifest.files) {
        $relative = [string]$entry.path
        if (-not (Test-ManifestPath $relative) -or
            [string]$entry.sha256 -notmatch '^[0-9a-f]{64}$' -or -not $seenPaths.Add($relative) -or
            (($relative -match '^installer/') -and $allowedInputs.Keys -cnotcontains $relative) -or
            (($relative -match '^(?i:package)/') -and -not $relative.StartsWith('package/', [StringComparison]::Ordinal))) {
            throw [IO.InvalidDataException]::new('Installer manifest path rejected.')
        }
        if ($relative -notmatch '^package/.+' -and -not $allowedInputs.ContainsKey($relative)) {
            throw [IO.InvalidDataException]::new('Installer manifest path is outside the fixed input set.')
        }
        if ($allowedInputs.ContainsKey($relative)) { $allowedInputs.Remove($relative) }
        $source = [IO.Path]::GetFullPath((Join-Path $inputRoot ($relative -replace '/', '\')))
        $sharePrefix = [IO.Path]::GetFullPath($inputRoot).TrimEnd('\') + '\'
        $relativeSegments = $relative.Split('/')
        $walk = $inputRoot
        foreach ($segment in $relativeSegments) {
            $walk = Join-Path $walk $segment
            if (-not (Test-NotReparsePoint $walk)) { throw [IO.InvalidDataException]::new('Installer input contains a reparse point.') }
        }
        if (-not $source.StartsWith($sharePrefix, [StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.File]::Exists($source) -or (Get-Sha256 $source) -ne [string]$entry.sha256) {
            throw [IO.InvalidDataException]::new('Installer input hash verification failed.')
        }
    }
    if ($allowedInputs.Count -ne 0) { throw [IO.InvalidDataException]::new('Fixed installer inputs are incomplete.') }
    if (-not ($manifest.files | Where-Object { $_.path -ceq 'installer/Package.wxs' }) -or
        -not ($manifest.files | Where-Object { $_.path -ceq 'installer/DEYTTConnect.Windows.Installer.wixproj' }) -or
        -not ($manifest.files | Where-Object { $_.path -ceq 'package/DeyttConnect.Windows.exe' }) -or
        -not ($manifest.files | Where-Object { $_.path -ceq 'package/DeyttConnect.Windows.Service.exe' })) {
        throw [IO.InvalidDataException]::new('Fixed installer input set incomplete.')
    }

    if (-not (Test-NoReparseChain $programData)) { throw [IO.InvalidDataException]::new('ProgramData path contains a reparse point.') }
    New-ProtectedDirectory $tempRoot
    [Environment]::SetEnvironmentVariable('DOTNET_CLI_HOME', (Join-Path $tempRoot 'dotnet-home'), 'Process')
    [Environment]::SetEnvironmentVariable('NUGET_PACKAGES', (Join-Path $tempRoot 'nuget-packages'), 'Process')
    [Environment]::SetEnvironmentVariable('NUGET_HTTP_CACHE_PATH', (Join-Path $tempRoot 'nuget-http-cache'), 'Process')
    foreach ($entry in $manifest.files) {
        $relative = [string]$entry.path
        $source = Join-Path $inputRoot ($relative -replace '/', '\')
        $destination = Join-Path $tempRoot ($relative -replace '/', '\')
        $destinationParent = [IO.Path]::GetDirectoryName($destination)
        [void][IO.Directory]::CreateDirectory($destinationParent)
        Copy-Item -LiteralPath $source -Destination $destination -ErrorAction Stop
        if (-not (Test-NotReparsePoint $destination) -or
            (Get-Sha256 $destination) -ne [string]$entry.sha256) {
            throw [IO.InvalidDataException]::new('Protected installer copy hash verification failed.')
        }
    }

    $sdk = Invoke-BoundedProcess $sdkPath '--list-sdks' 30
    if ($sdk.TimedOut) { $result.blocker = 'dotnet_sdk_query_timeout'; return }
    $result.dotnet10_sdk = ($sdk.ExitCode -eq 0 -and $sdk.StdOut -match '(?m)^10\.\d+\.\d+')
    Save-PrivateProcessLog 'dotnet-sdk.log' $sdk
    if (-not $result.dotnet10_sdk) { $result.blocker = 'dotnet10_sdk_missing'; return }

    $wixProject = [IO.Path]::GetFullPath((Join-Path $tempRoot 'installer\DEYTTConnect.Windows.Installer.wixproj'))
    $wixText = Get-Content -LiteralPath $wixProject -Raw
    if ($wixText -notmatch 'WixToolset\.Sdk/6\.0\.0') { throw [IO.InvalidDataException]::new('WiX SDK version mismatch.') }
    $packageStage = [IO.Path]::GetFullPath((Join-Path $tempRoot 'package')).Replace('\', '/')
    $wixOutput = [IO.Path]::GetFullPath((Join-Path $tempRoot 'wix-output\Release'))
    $wixObjRoot = [IO.Path]::GetFullPath((Join-Path $tempRoot 'wix-obj'))
    $wixIntermediate = [IO.Path]::GetFullPath((Join-Path $wixObjRoot 'Release'))
    $dotnetArgs = 'build "' + $wixProject + '" --configuration Release -m:1 -nodeReuse:false -p:NuGetAudit=false -p:PackageStageDir="' + $packageStage + '" -p:ProductVersion=' + $productVersion + ' -p:OutputPath="' + $wixOutput + '" -p:BaseIntermediateOutputPath="' + $wixObjRoot + '" -p:IntermediateOutputPath="' + $wixIntermediate + '" -p:MSBuildProjectExtensionsPath="' + $wixObjRoot + '" --nologo'
    $build = Invoke-BoundedProcess $sdkPath $dotnetArgs 900
    if ($build.TimedOut) { $result.blocker = 'wix_build_timeout'; $processTimedOut = $true; return }
    $result.wix_build_exit_code = $build.ExitCode
    Save-PrivateProcessLog 'wix-build.log' $build
    if ($build.ExitCode -ne 0) { $result.blocker = 'wix_build_failed'; return }
    $msis = @(Get-ChildItem -LiteralPath (Join-Path $tempRoot 'wix-output') -Filter '*.msi' -File -Recurse)
    if ($msis.Count -ne 1 -or $msis[0].Length -lt 65536) { $result.blocker = 'msi_output_invalid'; return }
    $msiPath = Join-Path $tempRoot 'DEYTT-QA-1.0.0.msi'
    Copy-Item -LiteralPath $msis[0].FullName -Destination $msiPath
    $msiHash = Get-Sha256 $msiPath
    if ($msiHash -notmatch '^[0-9a-f]{64}$') { $result.blocker = 'msi_hash_invalid'; return }
    $result.wix6_build_succeeded = $true
    $result.msi_built = $true
    $result.msi_sha256_valid = $true
    $result.msi_sha256 = $msiHash

    $result.install_attempted = $true
    $installerTouched = $true
    $installLog = Join-Path $tempRoot 'msi-install.log'
    $installArgs = '/i "' + $msiPath + '" ALLOWEDUSERSID=' + $testSid + ' /qn /norestart /L*v "' + $installLog + '"'
    $install = Invoke-BoundedProcess (Join-Path $env:SystemRoot 'System32\msiexec.exe') $installArgs 300
    if ($install.TimedOut) { $result.blocker = 'msi_install_timeout'; $processTimedOut = $true; return }
    $result.install_exit_code = $install.ExitCode
    Save-PrivateProcessLog 'msi-install-output.log' $install

    $products = @(Get-ProductEntries | Where-Object { $_.DisplayVersion -eq $productVersion })
    if ($products.Count -eq 1 -and $products[0].KeyName -match '^\{[0-9A-Fa-f-]{36}\}$') {
        try { $null = [Guid]::Parse($products[0].KeyName.Trim('{}')); $installedProductCode = $products[0].KeyName }
        catch { $installedProductCode = $null }
    }
    if ($install.ExitCode -eq 0 -and $null -ne $installedProductCode) {
        $state = Get-InstallState
        $service = Get-Service -Name 'DEYTTConnectVpn' -ErrorAction SilentlyContinue
        $installDir = Join-Path $env:ProgramFiles 'DEYTT\Connect'
        $shortcut = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::CommonPrograms)) 'DEYTT Connect\DEYTT Connect.lnk'
        $registryPath = 'HKLM:\SOFTWARE\DEYTT\Connect'
        $sidValue = $null
        if (Test-Path -LiteralPath $registryPath) { $sidValue = (Get-ItemProperty -LiteralPath $registryPath -ErrorAction SilentlyContinue).AllowedUserSid }
        $result.installed_state_verified = ($state.ProductCount -eq 1 -and
            $state.ServicePresent -and $service.Status -eq 'Running' -and
            (Test-Path -LiteralPath (Join-Path $installDir 'DeyttConnect.Windows.exe')) -and
            (Test-Path -LiteralPath (Join-Path $installDir 'DeyttConnect.Windows.Service.exe')) -and
            (Test-Path -LiteralPath (Join-Path $installDir 'DeyttVpnEngine.exe')) -and
            (Test-Path -LiteralPath $shortcut) -and $sidValue -eq $testSid)
    }

    if ($null -ne $installedProductCode) {
        $result.uninstall_attempted = $true
        $uninstallLog = Join-Path $tempRoot 'msi-uninstall.log'
        $uninstallArgs = '/x ' + $installedProductCode + ' /qn /norestart /L*v "' + $uninstallLog + '"'
        $uninstall = Invoke-BoundedProcess (Join-Path $env:SystemRoot 'System32\msiexec.exe') $uninstallArgs 300
        if ($uninstall.TimedOut) { $result.blocker = 'msi_uninstall_timeout'; $processTimedOut = $true; return }
        $result.uninstall_exit_code = $uninstall.ExitCode
        Save-PrivateProcessLog 'msi-uninstall-output.log' $uninstall
        if ($uninstall.ExitCode -ne 0) { $result.blocker = 'msi_uninstall_failed'; return }
        $result.post_uninstall_clean = Test-InstallStateClear (Get-InstallState)
    }
    elseif ($install.ExitCode -ne 0) {
        # MSI rollback is accepted only when every fixed machine footprint is absent.
        $result.post_uninstall_clean = Test-InstallStateClear (Get-InstallState)
    }

    if (-not $result.post_uninstall_clean) { $result.blocker = 'cleanup_not_verified'; return }
    if (-not $result.installed_state_verified) { $result.blocker = 'installed_state_not_verified'; return }
    if ($install.ExitCode -ne 0) { $result.blocker = 'msi_install_failed'; return }
    $result.result = 'passed'
    $result.blocker = $null
}
catch {
    if ($null -eq $result.blocker) { $result.blocker = 'runner_error' }
}
finally {
    $result.no_child_processes = Stop-RunChildProcesses
    if ($processTimedOut) {
        $result.cleanup_safe = $false
    }
    else {
        if ($installerTouched) {
            $result.cleanup_safe = $result.post_uninstall_clean
        }
        else {
            $result.cleanup_safe = $true
        }
        $expectedTempRoot = Join-Path $programData ('DeyttQaInstaller-' + $runId)
        if ($script:ownsProtectedRoot -and $result.cleanup_safe -and $result.no_child_processes -and [IO.Directory]::Exists($tempRoot) -and
            [string]::Equals([IO.Path]::GetFullPath($tempRoot), [IO.Path]::GetFullPath($expectedTempRoot), [StringComparison]::OrdinalIgnoreCase) -and
            (Test-NotReparsePoint $tempRoot)) {
            try { Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction Stop }
            catch { $result.cleanup_safe = $false; $result.result = 'failed'; $result.blocker = 'temp_cleanup_failed' }
        }
    }
    if (-not $result.no_child_processes) {
        $result.cleanup_safe = $false
        $result.result = 'failed'
        if ($null -eq $result.blocker) { $result.blocker = 'child_process_cleanup_failed' }
    }
    try { Write-InstallerReport } catch { }
}

if ($result.result -eq 'passed') { exit 0 }
exit 2
