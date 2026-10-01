#!/usr/bin/env bash
set -euo pipefail

# Parameterless host orchestration for fixed MSI QA. The source gate stays closed
# until independent review approves the elevated runner and cleanup protocol.
readonly INSTALLER_REVIEW_GATE="pending"
readonly INSTALLER_MODE="${DEYTT_QA_INSTALLER_MODE:-prepare}"
readonly RUN_TIMEOUT=1200
create_private_stage_layout() {
  local artifact_root="$1" run_id="$2" run_dir stage out
  [[ "$run_id" =~ ^[0-9TZ-]+$ ]] || return 1
  run_dir="$artifact_root/installer-stage-$run_id"
  [[ ! -e "$run_dir" && ! -L "$run_dir" ]] || return 1
  mkdir -- "$run_dir" || return 1
  chmod 700 -- "$run_dir"
  stage="$run_dir/input"
  out="$run_dir/output"
  mkdir -- "$stage"
  mkdir -- "$stage/installer" "$out"
  chmod 700 -- "$stage" "$stage/installer" "$out"
  [[ ! -e "$stage/package" && ! -L "$stage/package" ]] || return 1
  printf '%s\n%s\n' "$run_dir" "$out"
}
invoke_package_builder() {
  local package_dir="$1" build_log="$2" builder="${3:-$REPO_ROOT/windows/scripts/build-windows-package.sh}"
  [[ ! -e "$package_dir" && ! -L "$package_dir" ]] || return 1
  timeout --foreground "${RUN_TIMEOUT}s" "$builder" "$package_dir" >"$build_log" 2>&1
}
installer_runner_command() {
  local run_id="$1" manifest_sha256="$2" runner_sha256="$3"
  [[ "$run_id" =~ ^[0-9TZ-]+$ && "$manifest_sha256" =~ ^[0-9a-f]{64}$ && "$runner_sha256" =~ ^[0-9a-f]{64}$ ]] || return 1
  python3 - "$run_id" "$manifest_sha256" "$runner_sha256" <<'PY'
import re,sys
run_id,manifest_sha256,runner_sha256=sys.argv[1:]
if not re.fullmatch(r'[0-9TZ-]+',run_id) or not re.fullmatch(r'[0-9a-f]{64}',manifest_sha256) or not re.fullmatch(r'[0-9a-f]{64}',runner_sha256):
    raise SystemExit(1)
print("$d=[Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData);$p=Join-Path (Join-Path $d 'Temp') 'deytt-qa-installer-source-"+run_id+"\\installer\\installer-runner.ps1';$b=[IO.File]::ReadAllBytes($p);$s=[Security.Cryptography.SHA256]::Create();try{$h=([BitConverter]::ToString($s.ComputeHash($b))).Replace('-','').ToLowerInvariant()}finally{$s.Dispose()};if($h -cne '"+runner_sha256+"'){throw 'Runner digest mismatch'};& ([ScriptBlock]::Create([Text.Encoding]::UTF8.GetString($b))) -ExpectedManifestSha256 '"+manifest_sha256+"'")
PY
}
if [[ "$INSTALLER_MODE" == dry-run ]]; then
  dry_run_root="$(mktemp -d)"
  trap 'rm -rf -- "$dry_run_root"' EXIT
  dry_run_id='20260930T000000Z-1'
  layout="$(create_private_stage_layout "$dry_run_root" "$dry_run_id")" || { printf 'BLOCKED: private stage layout creation failed.\n' >&2; exit 2; }
  dry_run_dir="${layout%%$'\n'*}"
  dry_run_out="${layout#*$'\n'}"
  [[ -d "$dry_run_dir/input" && -d "$dry_run_dir/input/installer" && -d "$dry_run_out" && ! -e "$dry_run_dir/input/package" ]] || { printf 'BLOCKED: builder output path is not absent from the private staging layout.\n' >&2; exit 2; }
  [[ "$(stat -c '%a' "$dry_run_dir")" == 700 && "$(stat -c '%a' "$dry_run_dir/input")" == 700 && "$(stat -c '%a' "$dry_run_dir/input/installer")" == 700 && "$(stat -c '%a' "$dry_run_out")" == 700 ]] || { printf 'BLOCKED: private staging directory permissions are not 0700.\n' >&2; exit 2; }
  dry_run_builder="$dry_run_root/fake-package-builder.sh"
  cat >"$dry_run_builder" <<'SH'
#!/usr/bin/env bash
[[ ! -e "$1" && ! -L "$1" ]] || exit 1
printf 'called-with-absent-output\n'
SH
  chmod 700 -- "$dry_run_builder"
  invoke_package_builder "$dry_run_dir/input/package" "$dry_run_root/builder.log" "$dry_run_builder" || { printf 'BLOCKED: builder dry-run did not receive an absent output path.\n' >&2; exit 2; }
  [[ "$(cat "$dry_run_root/builder.log")" == called-with-absent-output ]] || { printf 'BLOCKED: builder dry-run invocation marker missing.\n' >&2; exit 2; }
  generated_command="$(installer_runner_command "$dry_run_id" "$(printf 'a%.0s' {1..64})" "$(printf 'b%.0s' {1..64})")" || { printf 'BLOCKED: runner command generation failed.\n' >&2; exit 2; }
  [[ "$generated_command" == *'installer-runner.ps1'* && "$generated_command" == *'-ExpectedManifestSha256'* && "$generated_command" == *'Runner digest mismatch'* ]] || { printf 'BLOCKED: runner command does not pin the fixed local runner and manifest.\n' >&2; exit 2; }
  printf 'Private staging dry-run passed: builder output directory is absent at invocation; all staging directories are 0700.\n'
  printf 'Fixed elevated bootstrap is generated for the local verified runner and requires host runner and manifest digests.\n'
  exit 0
fi
if [[ "$INSTALLER_MODE" != prepare && "$INSTALLER_MODE" != smoke ]]; then
  printf 'BLOCKED: unsupported fixed installer QA mode.\n' >&2; exit 64
fi
if [[ "$INSTALLER_MODE" == smoke && "$INSTALLER_REVIEW_GATE" != approved ]]; then
  printf 'BLOCKED: MSI host orchestration is wired but its source review gate is still pending. No guest action or package build was started.\n' >&2
  exit 2
fi
readonly VM_NAME="${DEYTT_QA_VM_NAME:-win10}"
readonly REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
readonly ARTIFACT_ROOT="${DEYTT_QA_ARTIFACT_ROOT:-/mnt/data/deytt-win10-qa}"
readonly SNAPSHOT_NAME="deytt-qa-ga-7.2.20-20260930T151115Z"
readonly COMMAND_TIMEOUT="${DEYTT_QA_COMMAND_TIMEOUT_SECONDS:-15}"
readonly MIN_FREE_KIB="${DEYTT_QA_MIN_FREE_KIB:-41943040}"
readonly SHUTDOWN_TIMEOUT=180
[[ "$VM_NAME" == win10 ]] || { printf 'BLOCKED: installer QA only targets the fixed win10 VM.\n' >&2; exit 2; }
for utility in VBoxManage timeout df awk date mkdir chmod grep python3 find sort xargs sha256sum xdotool; do
  command -v "$utility" >/dev/null 2>&1 || { printf 'BLOCKED: required utility unavailable (%s)\n' "$utility" >&2; exit 2; }
done
readonly DOTNET="$REPO_ROOT/.toolchain/dotnet/dotnet"
[[ -x "$DOTNET" && -x "$REPO_ROOT/.toolchain/amnezia-box" ]] || { printf 'BLOCKED: fixed host .NET/engine toolchain unavailable.\n' >&2; exit 2; }
run_vbox() { timeout --foreground "${COMMAND_TIMEOUT}s" VBoxManage "$@" 2>/dev/null; }
vm_info="$(run_vbox showvminfo "$VM_NAME" --machinereadable)" || { printf 'BLOCKED: VM state unavailable.\n' >&2; exit 2; }
vm_state="$(awk -F= '$1=="VMState"{gsub(/"/,"",$2);print $2;exit}' <<<"$vm_info")"
snap="$(awk -F= '$1=="CurrentSnapshotName"{gsub(/"/,"",$2);print $2;exit}' <<<"$vm_info")"
ga="$(run_vbox guestproperty get "$VM_NAME" /VirtualBox/GuestAdd/Version || true)"
disk="$(awk -F= '$1=="\"SATA-0-0\""{gsub(/"/,"",$2);print $2;exit}' <<<"$vm_info")"
[[ -n "$disk" && -e "$disk" ]] || { printf 'BLOCKED: VM disk unavailable.\n' >&2; exit 2; }
free_kib="$(df -Pk -- "$disk" | awk 'NR==2{print $4;exit}')"
[[ "$vm_state" == running && "$snap" == "$SNAPSHOT_NAME" && "$ga" == *Value:* && "$free_kib" =~ ^[0-9]+$ && "$free_kib" -ge "$MIN_FREE_KIB" ]] || {
  printf 'BLOCKED: installer staging gates failed (VM must be running on the verified snapshot with Guest Additions and VDI space).\n' >&2; exit 2;
}

mkdir -p -- "$ARTIFACT_ROOT"; chmod 700 -- "$ARTIFACT_ROOT"
run_id="$(date -u +%Y%m%dT%H%M%S%NZ)-$$-$RANDOM"
layout="$(create_private_stage_layout "$ARTIFACT_ROOT" "$run_id")" || { printf 'BLOCKED: unique private staging directory could not be created.\n' >&2; exit 2; }
run_dir="${layout%%$'\n'*}"
out="${layout#*$'\n'}"
stage="$run_dir/input"
build_log="$run_dir/package-build.log"
if ! invoke_package_builder "$stage/package" "$build_log"; then
  printf 'BLOCKED: Windows package build failed or timed out; private host log: %s\n' "$build_log" >&2; exit 2
fi

# Merge the service output exactly as the production MSI build script does.
service_dir="$stage/package/service"
[[ -d "$service_dir" ]] || { printf 'BLOCKED: package service payload missing.\n' >&2; exit 2; }
while IFS= read -r -d '' service_file; do
  rel="${service_file#"$service_dir"/}"
  if [[ -e "$stage/package/$rel" ]]; then
    cmp -s "$service_file" "$stage/package/$rel" || { printf 'BLOCKED: package/service collision.\n' >&2; exit 2; }
  else cp -a -- "$service_file" "$stage/package/$rel"; fi
done < <(find "$service_dir" -mindepth 1 -maxdepth 1 -type f -print0)
if find "$service_dir" -mindepth 1 -maxdepth 1 ! -type f -print -quit | grep -q .; then
  printf 'BLOCKED: unsupported nested service payload.\n' >&2; exit 2
fi
python3 - "$REPO_ROOT" "$stage/installer" "$run_id" <<'PY'
import os,pathlib,re,sys
root,out,rid=sys.argv[1:]
if not re.fullmatch(r'[0-9TZ-]+',rid): raise SystemExit('invalid run id')
sources={'Package.wxs':pathlib.Path(root)/'windows/installer/Package.wxs','DEYTTConnect.Windows.Installer.wixproj':pathlib.Path(root)/'windows/installer/DEYTTConnect.Windows.Installer.wixproj','installer-runner.ps1':pathlib.Path(root)/'windows/qa-vm/installer-runner.ps1','installer-collect.ps1':pathlib.Path(root)/'windows/qa-vm/installer-collect.ps1','installer-stage.ps1':pathlib.Path(root)/'windows/qa-vm/installer-stage.ps1'}
for name,src in sources.items():
 text=src.read_text(encoding='utf-8')
 if '__RUN_ID__' in text:
  if text.count('__RUN_ID__')!=1: raise SystemExit('template marker mismatch')
  text=text.replace('__RUN_ID__',rid)
 fd=os.open(pathlib.Path(out)/name,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
 with os.fdopen(fd,'w',encoding='utf-8') as f:f.write(text)
PY
(cd "$stage/package" && find . -type f ! -name SHA256SUMS.txt -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS.txt)
python3 - "$stage" <<'PY'
import hashlib,json,pathlib,sys
root=pathlib.Path(sys.argv[1]); files=[]
for p in sorted(root.rglob('*')):
 if p.is_symlink(): raise SystemExit('symlink in installer stage')
 if p.is_file(): files.append({'path':p.relative_to(root).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
required={'package/DeyttConnect.Windows.exe','package/DeyttConnect.Windows.Service.exe','installer/Package.wxs','installer/DEYTTConnect.Windows.Installer.wixproj','installer/installer-runner.ps1','installer/installer-collect.ps1','installer/installer-stage.ps1'}
if not 10 <= len(files) <= 10000 or not required.issubset({x['path'] for x in files}): raise SystemExit('fixed stage contents rejected')
with open(root/'manifest.json','x',encoding='utf-8') as f: json.dump({'schema':1,'files':files},f,separators=(',',':'))
PY

share_in="deytt-installer-input-$run_id"; share_out="deytt-installer-output-$run_id"
if grep -Eq "^SharedFolderName[^=]*=\"($share_in|$share_out)\"$" <<<"$vm_info"; then printf 'BLOCKED: transient share collision.\n' >&2; exit 2; fi
run_vbox sharedfolder add "$VM_NAME" --name "$share_in" --hostpath "$stage" --readonly --transient >/dev/null || { printf 'BLOCKED: read-only package share unavailable.\n' >&2; exit 2; }
run_vbox sharedfolder add "$VM_NAME" --name "$share_out" --hostpath "$out" --transient >/dev/null || { printf 'BLOCKED: output share unavailable; VM left running.\n' >&2; exit 2; }
window_id="$(xdotool search --name '^win10' 2>/dev/null | head -n 1 || true)"
[[ "$window_id" =~ ^[0-9]+$ ]] && xdotool windowactivate --sync "$window_id" windowfocus --sync "$window_id" >/dev/null 2>&1 || { printf 'BLOCKED: guest window focus failed; VM left running.\n' >&2; exit 2; }
timeout --foreground "${COMMAND_TIMEOUT}s" VBoxManage controlvm "$VM_NAME" keyboardputscancode e0 5b 13 93 e0 db >/dev/null 2>&1 || { printf 'BLOCKED: Run dialog unavailable; VM left running.\n' >&2; exit 2; }
sleep 1
stage_command="powershell.exe -NoLogo -NoProfile -NoExit -ExecutionPolicy Bypass -File \"\\\\VBOXSVR\\${share_in}\\installer\\installer-stage.ps1\""
timeout --foreground "${COMMAND_TIMEOUT}s" VBoxManage controlvm "$VM_NAME" keyboardputstring "$stage_command" >/dev/null 2>&1 && timeout --foreground "${COMMAND_TIMEOUT}s" VBoxManage controlvm "$VM_NAME" keyboardputscancode 1c 9c >/dev/null 2>&1 || { printf 'BLOCKED: fixed non-elevated stager launch failed; VM left running.\n' >&2; exit 2; }
stage_report="$out/installer-stage.json"; deadline=$((SECONDS + 90))
while (( SECONDS < deadline )) && [[ ! -f "$stage_report" ]]; do sleep 2; done
if [[ ! -f "$stage_report" ]] || ! python3 - "$stage_report" <<'PY'
import json,sys
x=json.load(open(sys.argv[1],encoding='utf-8'))
if type(x.get('schema')) is not int or x['schema']!=1 or x.get('staged') is not True or type(x.get('file_count')) is not int or x['file_count']<10: raise SystemExit(1)
PY
then printf 'BLOCKED: guest local staging did not verify; VM left running. Private artifacts: %s\n' "$run_dir" >&2; exit 2; fi
chmod 600 "$stage_report"
printf 'STAGING PASSED: fixed hashed payload copied into guest local temp by non-elevated PowerShell. MSI runner remains gated for independent review. Private artifacts: %s\n' "$run_dir"

manifest_sha256="$(sha256sum -- "$stage/manifest.json" | awk '{print $1}')"
runner_sha256="$(sha256sum -- "$stage/installer/installer-runner.ps1" | awk '{print $1}')"
runner_command="$(installer_runner_command "$run_id" "$manifest_sha256" "$runner_sha256")" || { printf 'BLOCKED: fixed runner reference generation failed; VM left running.\n' >&2; exit 2; }
printf 'MANUAL ELEVATED TARGET (not sent to the VM): open the intended Administrator PowerShell console and run this digest-pinned command:\n%s\n' "$runner_command"
printf 'The installer gate remains pending. No keyboard input is sent after the non-elevated stager.\n'

if [[ "$INSTALLER_MODE" == prepare ]]; then
  exit 0
fi
printf 'BLOCKED: smoke requires the user to invoke the displayed fixed command in the intended elevated console; no blind keyboard hand-off is implemented.\n' >&2
exit 2
