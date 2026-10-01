#!/usr/bin/env python3
"""Static guardrails for the Windows QA SSH bootstrap; does not run PowerShell or touch a VM."""

from pathlib import Path
import math
import re


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = (ROOT / "windows/qa-vm/bootstrap-ssh.ps1").read_text(encoding="utf-8")
HOST = (ROOT / "windows/qa-vm/bootstrap-ssh-host.sh").read_text(encoding="utf-8")
STAGE = (ROOT / "windows/qa-vm/installer-stage.ps1").read_text(encoding="utf-8")
RUNNER = (ROOT / "windows/qa-vm/installer-runner.ps1").read_text(encoding="utf-8")
COLLECTOR = (ROOT / "windows/qa-vm/installer-collect.ps1").read_text(encoding="utf-8")
INSTALLER_HOST = (ROOT / "windows/qa-vm/installer-smoke-host.sh").read_text(encoding="utf-8")
QA_VM = (ROOT / "windows/scripts/qa-vm.sh").read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"FAIL: {message}")


def safe_windows_manifest_path(value: str) -> bool:
    """Edge-case model for the PowerShell path contract; source guards bind it to implementation."""
    if not value or "\\" in value or ":" in value or value.startswith(("/", "\\")):
        return False
    for segment in value.split("/"):
        if (segment in {"", ".", ".."} or re.search(r'[<>:"|?*\x00-\x1f]', segment)
                or segment.endswith((".", " "))
                or re.match(r"(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", segment.split(".", 1)[0])):
            return False
    return True


def is_json_integer(value: object) -> bool:
    return type(value) is int


def fixed_package_root_case_ok(value: str) -> bool:
    return not value.lower().startswith("package/") or value.startswith("package/")


def is_finite_nonnegative_duration(value: object) -> bool:
    return (type(value) in {int, float} and math.isfinite(float(value)) and 0 <= value <= 3600)


install = SCRIPT.split("function Install-Bootstrap {", 1)[1].split("function Remove-Bootstrap {", 1)[0]
build_gate = install.index("$build -lt 17763")
root_create = install.index("New-Item -ItemType Directory -Path $root")
capability_read = install.index("Get-WindowsCapability -Online")
require(build_gate < capability_read < root_create, "Windows 10 build 17763 must be checked before capability access and protected-root creation")
require("$product.ProductName -notlike 'Windows 10*'" in install and "[IntPtr]::Size -ne 8" in install,
        "bootstrap must reject non-Windows-10 or non-x64 guests")

add_capability = re.search(
    r"Add-WindowsCapability -Online -Name 'OpenSSH\.Server~~~~0\.0\.1\.0' \| Out-Null\s*\n\s*Disable-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue",
    install,
)
require(add_capability is not None, "broad OpenSSH firewall rule must be disabled immediately after capability installation")
require("$script:mutationStarted" in install, "mutation marker must gate catch cleanup after all read-only preflight checks")
require(re.search(r"catch \{\s*if \(\$script:mutationStarted\) \{\s*Disable-NetFirewallRule -Name 'OpenSSH-Server-In-TCP'", SCRIPT) is not None,
        "install catch must disable the broad OpenSSH firewall rule after mutation begins")

rollback = SCRIPT.split("function Remove-Bootstrap {", 1)[1].split("if ($Rollback)", 1)[0]
require(rollback.index("Disable-NetFirewallRule -Name 'OpenSSH-Server-In-TCP'") < rollback.index("Test-Path -LiteralPath $statePath"),
        "rollback must disable the broad firewall rule even when state is incomplete")
require("127.0.0.1,$HOST_PORT" in HOST and "0.0.0.0" not in HOST,
        "host forwarding must remain loopback-only")
prepare = HOST.split("  --prepare)", 1)[1].split("  --print-guest-command)", 1)[0]
require(prepare.index("realpath -m") < prepare.index('mkdir -p -- "$ARTIFACT_ROOT"') < prepare.index('mkdir -m 700 -- "$MATERIAL_ROOT"'),
        "private artifact location must be rejected inside the repository before creating staging paths")
require(prepare.index('[[ ! -e "$MATERIAL_ROOT"') < prepare.index('mkdir -p -- "$ARTIFACT_ROOT"') and
        '[[ ! -L "$ARTIFACT_ROOT" ]]' in prepare,
        "existing key material and artifact-root symlinks must be rejected before creating host paths")
require("prepare_complete=false" in prepare and "rm -rf -- \"$MATERIAL_ROOT\"" in prepare and
        "prepare_complete=true" in prepare,
        "partial key/bootstrap staging must be removed only when this invocation created the directory")
remove = HOST.split("  --remove)", 1)[1].split("  --connect)", 1)[0]
require("modifyvm \"$VM_NAME\" --natpf1 delete \"$FORWARD_NAME\"" in remove and
        "reserved QA NAT forward remains configured." in remove and "|| true" not in remove,
        "NAT forward removal must fail closed and verify the resulting VM configuration")

# Installer contracts: catch unsafe schema/path/hash, timeout, cleanup, and report regressions offline.
require("$manifest.schema -ne 1" in STAGE and "$manifest.files.Count -lt 10" in STAGE and
        "$manifest.files.Count -gt 10000" in STAGE,
        "non-elevated stager must enforce a bounded schema-1 manifest")
require("[Environment+SpecialFolder]::LocalApplicationData" in STAGE and
        "[Environment+SpecialFolder]::LocalApplicationData" in RUNNER and
        "[Environment+SpecialFolder]::LocalApplicationData" in COLLECTOR,
        "stager, runner, and collector must use the same fixed user-local temp base")
require("$manifest.files -isnot [Array]" in STAGE and "$manifest.files -isnot [Array]" in RUNNER and
        "$Value -is [int] -or $Value -is [long]" in STAGE and "$Value -is [int] -or $Value -is [long]" in RUNNER,
        "stager and runner must reject bool/object schema fields and non-array file lists")
require("Test-ManifestPath $relative" in STAGE and "Test-ManifestPath $relative" in RUNNER and
        "[<>:\"|?*\\x00-\\x1f]" in STAGE and "COM[1-9]|LPT[1-9]" in RUNNER and
        "StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)" in STAGE,
        "stager and runner must reject Windows-invalid, traversal, rooted, reserved-device, and prefix-escape paths")
require("HashSet[string]" in STAGE and "$seenPaths.Add($relative)" in STAGE and
        "^[0-9a-f]{64}$" in STAGE,
        "stager must reject duplicate/case-colliding paths and malformed hashes")
require("$allowed.Keys -cnotcontains $relative" in STAGE and "$allowedInputs.Keys -cnotcontains $relative" in RUNNER and
        "StartsWith('package/', [StringComparison]::Ordinal)" in STAGE and "StartsWith('package/', [StringComparison]::Ordinal)" in RUNNER and
        "-ceq 'package/DeyttConnect.Windows.exe'" in STAGE and "-ceq 'installer/Package.wxs'" in RUNNER,
        "fixed payload paths must preserve exact case on case-insensitive Windows filesystems")
require("CreateDirectory($Path, [IntPtr]::Zero)" in STAGE and "$script:ownsLocalRoot = $true" in STAGE and
        "if ($script:ownsLocalRoot -and [IO.Directory]::Exists($localRoot))" in STAGE,
        "stager partial cleanup must be limited to a directory atomically created by this invocation")
require("[IO.File]::Move($tempReportPath, $reportPath)" in STAGE and
        "[IO.File]::Move($tempOutputReport, $outputReport)" in COLLECTOR,
        "host-visible stage and collector reports must appear atomically after complete serialization")
require("(Get-Hash $source) -ne [string]$entry.sha256" in STAGE,
        "stager must verify every source file hash before copying")
require("(Get-Sha256 $source) -ne [string]$entry.sha256" in RUNNER and
        "(Get-Sha256 $destination) -ne [string]$entry.sha256" in RUNNER,
        "elevated runner must verify manifest source and protected copied payload hashes")
require("[string]$ExpectedManifestSha256" in RUNNER and "ValidatePattern('^[0-9a-f]{64}$')" in RUNNER and
        "(Get-Sha256 $manifestPath) -ne $ExpectedManifestSha256" in RUNNER and
        "deytt-qa-installer-source-' + $runId" in RUNNER,
        "elevated runner must pin the local manifest digest supplied out-of-band by the host")
require("New-ProtectedDirectory $tempRoot" in RUNNER and "SetAccessRuleProtection($true, $false)" in RUNNER and
        "S-1-5-32-544" in RUNNER and "S-1-5-18" in RUNNER and "$actual.GetOwner" in RUNNER and
        "Test-NotReparsePoint $destination" in RUNNER and "CommonApplicationData" in RUNNER and
        "$script:ownsProtectedRoot = $true" in RUNNER and "if ($script:ownsProtectedRoot -and $result.cleanup_safe" in RUNNER,
        "build staging must have a protected Administrators/SYSTEM ACL, trusted owner, and reparse checks")
protected_root_creation = RUNNER.index("New-ProtectedDirectory $tempRoot")
require("[IO.Directory]::CreateDirectory($tempRoot)" not in RUNNER and
        protected_root_creation < RUNNER.index("SetEnvironmentVariable('DOTNET_CLI_HOME'") <
        RUNNER.index("$sdk = Invoke-BoundedProcess"),
        "the protected root must be created exactly once with its ACL before SDK activity and cleaned only when owned")
require("[Environment+SpecialFolder]::CommonApplicationData" in RUNNER and
        "[Environment+SpecialFolder]::ProgramFiles" in RUNNER and "Get-Command 'dotnet.exe'" not in RUNNER,
        "protected staging and SDK executable must use fixed Windows known folders, not caller-controlled paths")
require("$walk = Join-Path $walk $segment" in RUNNER and "Test-NotReparsePoint $walk" in RUNNER and
        "function Test-NoReparseChain" in RUNNER and "Test-NoReparseChain $inputRoot" in RUNNER and
        "Copy-Item -LiteralPath $source -Destination $destination" in RUNNER and
        "New-ProtectedDirectory $tempRoot" in RUNNER,
        "source path components must reject reparse links before data is copied into protected staging")
require("$manifest.schema -ne 1" in RUNNER and "$manifest.files.Count -gt 10000" in RUNNER and
        "$allowedInputs.Count -ne 0" in RUNNER and "$seenPaths.Add($relative)" in RUNNER and
        "^[0-9a-f]{64}$" in RUNNER,
        "elevated runner must reject unsupported, oversized, duplicate, malformed, or incomplete manifests")
require("WaitForExit($TimeoutSeconds * 1000)" in RUNNER and "taskkill.exe" in RUNNER and
        "'/PID ' + $proc.Id + ' /T /F'" in RUNNER and "$script:processTimedOut = $true" in RUNNER,
        "bounded child process timeout must mark failure and terminate the process tree")
require(RUNNER.index("$result.no_child_processes = Stop-RunChildProcesses") <
        RUNNER.index("Remove-Item -LiteralPath $tempRoot") and
        "if ($script:ownsProtectedRoot -and $result.cleanup_safe -and $result.no_child_processes" in RUNNER,
        "private temp cleanup must happen only after the child-process cleanup check")
require("$result.cleanup_safe = $false" in RUNNER and "$processTimedOut" in RUNNER and
        "if (-not $result.post_uninstall_clean)" in RUNNER,
        "timeouts and an unverified machine footprint must keep cleanup unsafe")
require("$report.result -cnotin @('passed','failed')" in COLLECTOR and
        "^[0-9a-f]{64}$" in COLLECTOR and "requiredBooleans" in COLLECTOR and
        "$report.cleanup_safe -and $report.no_child_processes" in COLLECTOR and
        "$schemaProperty.Value -isnot [int]" in COLLECTOR and
        "[double]::IsNaN" in COLLECTOR and "[double]::IsInfinity" in COLLECTOR,
        "collector must validate report schema, typed fields, finite duration, digest, and success gates")
require("$schemaProperty.Value -isnot [long]" in COLLECTOR and
        "$report.install_exit_code -isnot [long]" in COLLECTOR and "$report.result -cnotin" in COLLECTOR,
        "collector must accept integral JSON widths while rejecting boolean schema/exit codes and case-folded enums")
require("Remove-Item -LiteralPath $localRoot -Recurse -Force" in STAGE and
        "Do not send exception text, paths, or identity to the host." in STAGE,
        "stager failures must discard partial payloads and keep exception detail private")
require("$allowedBlockers" in COLLECTOR and "$_" not in COLLECTOR and
        "Exception" not in COLLECTOR and "password" not in COLLECTOR.lower() and
        "token" not in COLLECTOR.lower() and "subscription" not in COLLECTOR.lower(),
        "collector may export only the fixed sanitized schema and blocker allowlist")
require('readonly INSTALLER_REVIEW_GATE="pending"' in INSTALLER_HOST and
        'INSTALLER_REVIEW_GATE" != approved' in INSTALLER_HOST and
        'No guest action or package build was started.' in INSTALLER_HOST,
        "native MSI execution must remain blocked pending independent review")
require('sha256sum -- "$stage/manifest.json"' in INSTALLER_HOST and
        'MANUAL ELEVATED TARGET (not sent to the VM)' in INSTALLER_HOST and
        'keyboardputstring "$runner_command"' not in INSTALLER_HOST and
        'installer-runner.ps1' in INSTALLER_HOST and
        'sha256sum -- "$stage/installer/installer-runner.ps1"' in INSTALLER_HOST and
        'Runner digest mismatch' in INSTALLER_HOST,
        "host must display a fixed local runner bootstrap pinned to runner and manifest digests and never type it into an uncertain console")
require("\\\\VBOXSVR" not in RUNNER and "keyboardputstring \"$runner_command\"" not in INSTALLER_HOST,
        "elevated runner must not depend on unavailable VBox shared-folder access and host must not blind-type its command")
require(QA_VM.index('if [[ "$mode" == --installer-dry-run ]]') < QA_VM.index('for utility in VBoxManage') and
        "no VirtualBox, guest, CI, or network commands" in QA_VM,
        "installer dry-run must return before all VM preflight and CI/network paths")

# Concrete edge cases for the same source contracts, including Python's bool-is-int trap.
for path in ("../escape", "package/../escape", "C:/absolute", "/rooted", "package\\app.exe",
             "package/CON", "package/com1.log", "package/name.", "package/bad?.exe", "package//double"):
    require(not safe_windows_manifest_path(path), f"unsafe manifest path accepted: {path!r}")
for path in ("package/DeyttConnect.Windows.exe", "package/app..exe", "installer/Package.wxs"):
    require(safe_windows_manifest_path(path), f"valid manifest path rejected: {path!r}")
fixed_paths = {"installer/Package.wxs", "installer/DEYTTConnect.Windows.Installer.wixproj"}
require("installer/package.wxs" not in fixed_paths and "installer/Package.wxs" in fixed_paths,
        "case-mismatched fixed path accepted")
require(not fixed_package_root_case_ok("Package/DeyttConnect.Windows.exe") and
        fixed_package_root_case_ok("package/DeyttConnect.Windows.exe"),
        "case-mismatched package root accepted")
require(is_json_integer(1) and not is_json_integer(True) and not is_json_integer(1.0),
        "boolean or floating point values must not pass integer schema/count checks")
require(is_finite_nonnegative_duration(0) and is_finite_nonnegative_duration(12.5) and
        not is_finite_nonnegative_duration(True) and not is_finite_nonnegative_duration(float("nan")) and
        not is_finite_nonnegative_duration(float("inf")) and not is_finite_nonnegative_duration(3600.1),
        "duration contract must reject bool, NaN, infinity, and out-of-range values")

print("PASS: offline SSH bootstrap and MSI source guardrails")
