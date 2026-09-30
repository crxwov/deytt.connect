#!/usr/bin/env bash
set -euo pipefail

# Bounded preflight and one fixed, signed-out Windows QA fixture smoke.
readonly VM_NAME="${DEYTT_QA_VM:-win10}"
readonly MIN_FREE_KIB="${DEYTT_QA_MIN_FREE_KIB:-41943040}" # 40 GiB
readonly ARTIFACT_ROOT="${DEYTT_QA_ARTIFACT_ROOT:-/mnt/data/deytt-win10-qa}"
readonly VBOX_TIMEOUT="${DEYTT_QA_COMMAND_TIMEOUT_SECONDS:-15}"
readonly REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
readonly RUNNER="$REPO_ROOT/windows/qa-vm/guest-runner.ps1"
readonly CROP_HELPER="$REPO_ROOT/windows/qa-vm/crop-vbox-screenshot.py"
readonly QA_PROJECT="$REPO_ROOT/windows/DeyttConnect.Windows.QA/DeyttConnect.Windows.QA.csproj"
readonly DOTNET="$REPO_ROOT/.toolchain/dotnet/dotnet"
readonly SNAPSHOT_NAME="deytt-qa-ga-7.2.20-20260930T151115Z"
readonly KICK_TIMEOUT=15
readonly RUN_TIMEOUT=240
readonly SHUTDOWN_TIMEOUT=180

usage() {
  cat <<'EOF'
Usage: qa-vm.sh [--preflight | --dry-run | --smoke]

--preflight  Read sanitized VM/snapshot/Guest Additions and disk-filesystem status.
--dry-run    Print the bounded fixed-fixture smoke plan without VM changes.
--smoke      Publish and run only DeyttConnect.Windows.QA, collect its synthetic
             window screenshot/report, ACPI-shutdown, then restore the named GA snapshot.

The smoke does not read guest credentials, clipboard contents, or user files and
does not run guest commands from external input. On any failed gate it leaves the
VM powered on as-is; it never force-powers off or restores after an unclean shutdown.
EOF
}

mode="${1:---preflight}"
case "$mode" in --preflight|--dry-run|--smoke) ;; -h|--help) usage; exit 0 ;; *) usage >&2; exit 64 ;; esac
if (($# > 1)); then usage >&2; exit 64; fi

for utility in VBoxManage timeout df awk date mkdir grep python3 chmod find sha256sum xdotool; do
  command -v "$utility" >/dev/null 2>&1 || { printf 'BLOCKED: required utility unavailable (%s)\n' "$utility" >&2; exit 2; }
done

run_vbox() { timeout --foreground "${VBOX_TIMEOUT}s" VBoxManage "$@" 2>/dev/null; }
if ! vm_info="$(run_vbox showvminfo "$VM_NAME" --machinereadable)"; then
  printf 'BLOCKED: VirtualBox could not read configured VM state.\n' >&2; exit 2
fi
get_vm_value() { awk -F= -v key="$1" '$1 == key { gsub(/"/, "", $2); print $2; exit }' <<<"$vm_info"; }
vm_state="$(get_vm_value VMState)"
vm_memory="$(get_vm_value memory)"
vm_cpus="$(get_vm_value cpus)"
vm_os="$(get_vm_value ostype)"
disk_path="$(awk -F= '$1 == "\"SATA-0-0\"" { gsub(/"/, "", $2); print $2; exit }' <<<"$vm_info")"
[[ -n "$disk_path" && -e "$disk_path" ]] || { printf 'BLOCKED: VM disk path unavailable for storage preflight.\n' >&2; exit 2; }
storage_mount="$(df -Pk -- "$disk_path" | awk 'NR == 2 { print $6; exit }')"
free_kib="$(df -Pk -- "$disk_path" | awk 'NR == 2 { print $4; exit }')"
[[ "$free_kib" =~ ^[0-9]+$ ]] || free_kib=0

snapshot_output="$(timeout --foreground "${VBOX_TIMEOUT}s" VBoxManage snapshot "$VM_NAME" list --details 2>/dev/null || true)"
snapshot_state=unavailable
if grep -q 'This machine does not have any snapshots' <<<"$snapshot_output"; then snapshot_state=none
elif grep -q 'Name:' <<<"$snapshot_output"; then snapshot_state=present; fi
current_snapshot=unknown
current_snapshot_name="$(get_vm_value CurrentSnapshotName)"
if [[ "$current_snapshot_name" == "$SNAPSHOT_NAME" ]] && grep -q "Name: $SNAPSHOT_NAME" <<<"$snapshot_output"; then
  current_snapshot=verified
else
  current_snapshot=not_verified
fi

ga_state=unavailable
if ga_output="$(run_vbox guestproperty get "$VM_NAME" /VirtualBox/GuestAdd/Version)"; then
  [[ "$ga_output" == *'Value:'* ]] && ga_state=reported || ga_state=not_reported
fi
storage_ok=false; (( free_kib >= MIN_FREE_KIB )) && storage_ok=true
timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

if [[ "$mode" == --dry-run ]]; then
  printf 'Read-only smoke plan for %s at %s\n' "$VM_NAME" "$timestamp"
  printf 'Gates: state=%s current_snapshot=%s guest_additions=%s VDI_filesystem_free_kib=%s storage_ok=%s\n' \
    "$vm_state" "$current_snapshot" "$ga_state" "$free_kib" "$storage_ok"
  printf 'Plan: publish fixed QA fixture; stage immutable package and runner on transient shares; run; retrieve sanitized report/window-only screenshot; ACPI shutdown; restore %s only after confirmed poweroff.\n' "$SNAPSHOT_NAME"
  printf 'No files written; VM state unchanged.\n'
  exit 0
fi

if [[ "$mode" == --smoke ]]; then
  [[ -x "$DOTNET" && -f "$QA_PROJECT" && -f "$RUNNER" && -f "$CROP_HELPER" ]] || { printf 'BLOCKED: fixed QA fixture/toolchain/runner unavailable.\n' >&2; exit 2; }
  python3 -c 'import PIL' >/dev/null 2>&1 || { printf 'BLOCKED: Pillow is required for the bounded fixture-window crop.\n' >&2; exit 2; }
  [[ "$vm_state" == running && "$current_snapshot" == verified && "$ga_state" == reported && "$storage_ok" == true ]] || {
    printf 'BLOCKED: smoke gates failed (state=%s current_snapshot=%s guest_additions=%s VDI_filesystem_free_kib=%s minimum_kib=%s). No VM state changed.\n' \
      "$vm_state" "$current_snapshot" "$ga_state" "$free_kib" "$MIN_FREE_KIB" >&2; exit 2;
  }

  mkdir -p -- "$ARTIFACT_ROOT"
  chmod 700 "$ARTIFACT_ROOT"
  run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
  share_read="deytt-qa-readonly-$run_id"
  share_out="deytt-qa-output-$run_id"
  run_dir="$ARTIFACT_ROOT/smoke-$run_id"
  mkdir -- "$run_dir"
  chmod 700 "$run_dir"
  stage="$run_dir/readonly"
  out="$run_dir/output"
  mkdir -p -- "$stage/app" "$out"
  chmod 700 "$stage" "$stage/app" "$out"
  publish_log="$run_dir/publish.log"
  publish_timeout=600
  if ! timeout --foreground "${publish_timeout}s" "$DOTNET" publish "$QA_PROJECT" \
      -c Release -r win-x64 --self-contained true -p:PublishSingleFile=false -p:NuGetAudit=false \
      -o "$stage/app" --nologo >"$publish_log" 2>&1; then
    printf 'BLOCKED: fixed QA fixture publish failed or exceeded %ss; private host log: %s\n' "$publish_timeout" "$publish_log" >&2
    exit 2
  fi
  [[ -f "$stage/app/DeyttConnect.Windows.QA.exe" ]] || { printf 'BLOCKED: expected QA executable missing after publish.\n' >&2; exit 2; }
  python3 - "$RUNNER" "$stage/guest-runner.ps1" "$run_id" <<'PY'
import os, pathlib, re, sys
source, dest, run_id = sys.argv[1:]
if not re.fullmatch(r'[0-9TZ-]+', run_id): raise SystemExit('invalid run id')
text = pathlib.Path(source).read_text(encoding='utf-8')
if text.count('__RUN_ID__') != 1: raise SystemExit('runner template marker mismatch')
fd = os.open(dest, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w', encoding='utf-8') as f: f.write(text.replace('__RUN_ID__', run_id))
PY
  python3 - "$stage/app" "$stage/manifest.json" <<'PY'
import hashlib, json, os, pathlib, sys
root = pathlib.Path(sys.argv[1])
files = []
for path in sorted(p for p in root.rglob('*') if p.is_file()):
    rel = path.relative_to(root).as_posix()
    files.append({'path': rel, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
if not files or len(files) > 2500 or not any(x['path'] == 'DeyttConnect.Windows.QA.exe' for x in files):
    raise SystemExit('package manifest policy failed')
fd = os.open(sys.argv[2], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w', encoding='utf-8') as f:
    json.dump({'schema': 1, 'files': files}, f, separators=(',', ':'))
PY

  if grep -Eq "^SharedFolderName[^=]*=\"($share_read|$share_out)\"$" <<<"$vm_info"; then
    printf 'BLOCKED: transient QA shared-folder name is already in use; VM unchanged.\n' >&2; exit 2
  fi
  if ! run_vbox sharedfolder add "$VM_NAME" --name "$share_read" --hostpath "$stage" --readonly --transient >/dev/null; then
    printf 'BLOCKED: could not add read-only transient package share; VM remains running.\n' >&2; exit 2
  fi
  if ! run_vbox sharedfolder add "$VM_NAME" --name "$share_out" --hostpath "$out" --transient >/dev/null; then
    timeout --foreground "${VBOX_TIMEOUT}s" VBoxManage sharedfolder remove "$VM_NAME" --name "$share_read" --transient >/dev/null 2>&1 || true
    printf 'BLOCKED: could not add transient output share; VM remains running.\n' >&2; exit 2
  fi

  # Win+R then invoke one constant, repository-owned guest runner path.
  vm_window_id="$(xdotool search --name '^win10' 2>/dev/null | head -n 1 || true)"
  [[ "$vm_window_id" =~ ^[0-9]+$ ]] || { printf 'BLOCKED: VirtualBox window for win10 was not found; VM remains running.\n' >&2; exit 2; }
  # The GUI can be unmapped after an interactive PowerShell diagnostic.
  xdotool windowmap "$vm_window_id" >/dev/null 2>&1 || true
  if ! xdotool windowactivate --sync "$vm_window_id" windowfocus --sync "$vm_window_id"; then
    printf 'BLOCKED: could not focus the verified VirtualBox window; VM remains running.\n' >&2; exit 2
  fi
  if ! timeout --foreground "${KICK_TIMEOUT}s" VBoxManage controlvm "$VM_NAME" keyboardputscancode e0 5b 13 93 e0 db >/dev/null 2>&1; then
    printf 'BLOCKED: could not open the Windows Run dialog; VM remains running.\n' >&2; exit 2
  fi
  sleep 1
  fixed_command="powershell.exe -NoLogo -NoProfile -NoExit -ExecutionPolicy Bypass -File \"\\\\VBOXSVR\\${share_read}\\guest-runner.ps1\""
  if ! timeout --foreground "${KICK_TIMEOUT}s" VBoxManage controlvm "$VM_NAME" keyboardputstring "$fixed_command" >/dev/null 2>&1 ||
     ! timeout --foreground "${KICK_TIMEOUT}s" VBoxManage controlvm "$VM_NAME" keyboardputscancode 1c 9c >/dev/null 2>&1; then
    printf 'BLOCKED: could not signal the fixed QA runner; VM remains running.\n' >&2; exit 2
  fi

  started="$out/runner-started.txt"
  start_deadline=$((SECONDS + 30))
  while (( SECONDS < start_deadline )) && [[ ! -f "$started" ]]; do sleep 2; done
  if [[ ! -f "$started" ]]; then
    printf 'BLOCKED: runner-start readiness signal missing after 30s; VM left running, no restore attempted.\n' >&2; exit 2
  fi
  window_ready="$out/window-ready.json"
  ready_deadline=$((SECONDS + 60))
  while (( SECONDS < ready_deadline )) && [[ ! -f "$window_ready" ]]; do sleep 2; done
  if [[ ! -f "$window_ready" ]]; then
    printf 'BLOCKED: QA window-ready signal missing after 60s; VM left running, no restore attempted.\n' >&2; exit 2
  fi
  full_screen="$run_dir/.full-screen-private.png"
  if ! timeout --foreground "${KICK_TIMEOUT}s" VBoxManage controlvm "$VM_NAME" screenshotpng "$full_screen" >/dev/null 2>&1; then
    printf 'BLOCKED: host could not capture the active guest display; no restore is attempted.\n' >&2; exit 2
  fi
  chmod 600 "$full_screen"
  fixture_screenshot="$out/fixture-window.png"
  if ! python3 "$CROP_HELPER" "$window_ready" "$run_id" "$full_screen" "$fixture_screenshot"; then
    unlink "$full_screen"
    printf 'BLOCKED: fixture bounds could not be safely cropped; VM remains running.\n' >&2; exit 2
  fi
  unlink "$full_screen"
  printf 'Private fixture crop ready for visual confirmation: %s\n' "$fixture_screenshot"
  confirm_marker="$run_dir/window-confirmed"
  confirm_deadline=$((SECONDS + 60))
  while (( SECONDS < confirm_deadline )) && [[ ! -f "$confirm_marker" ]]; do sleep 2; done
  confirmed=false
  [[ -f "$confirm_marker" ]] && confirmed=true
  python3 - "$out/host-capture-result.json" "$run_id" "$confirmed" <<'PY'
import json,os,sys
path,run_id,confirmed=sys.argv[1:]
fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w',encoding='utf-8') as f:
 json.dump({'schema':1,'run_id':run_id,'fixture_window_confirmed':confirmed=='true','screenshot_captured':confirmed=='true'},f)
PY
  if [[ "$confirmed" != true ]]; then
    printf 'BLOCKED: fixture screenshot was not visually confirmed within 60s; negative acknowledgement sent, no restore attempted.\n' >&2; exit 2
  fi
  report="$out/run-report.json"
  deadline=$((SECONDS + RUN_TIMEOUT))
  while (( SECONDS < deadline )) && [[ ! -f "$report" ]]; do sleep 3; done
  if [[ ! -f "$report" ]]; then
    printf 'BLOCKED: guest did not publish a runner report within %ss; VM left running, no restore attempted.\n' "$RUN_TIMEOUT" >&2; exit 2
  fi
  if ! python3 - "$report" "$out/fixture-window.png" <<'PY'
import json, os, sys
with open(sys.argv[1], encoding='utf-8') as f: x = json.load(f)
required = {'schema': 1, 'result': 'passed', 'fixture': 'DeyttConnect.Windows.QA',
 'fixture_window_seen': True, 'screenshot_captured': True, 'graceful_close_requested': True,
 'process_exited': True, 'process_exit_code': 0}
if not all(x.get(k) == v for k, v in required.items()): raise SystemExit(1)
if not isinstance(x.get('fixture_process_id'), int) or x.get('process_force_stop_used') is not False: raise SystemExit(1)
if not os.path.isfile(sys.argv[2]) or os.path.getsize(sys.argv[2]) < 1024: raise SystemExit(1)
print('Guest runner passed: synthetic fixture window, exit code 0, window screenshot captured.')
PY
  then
    printf 'BLOCKED: synthetic fixture smoke failed validation; VM left running, no restore attempted.\n' >&2; exit 2
  fi
  chmod 600 "$report" "$out/run.log" "$out/fixture-window.png"
  # Close only the visible PowerShell console started for this fixed runner.
  if ! timeout --foreground "${KICK_TIMEOUT}s" VBoxManage controlvm "$VM_NAME" keyboardputstring exit >/dev/null 2>&1 ||
     ! timeout --foreground "${KICK_TIMEOUT}s" VBoxManage controlvm "$VM_NAME" keyboardputscancode 1c 9c >/dev/null 2>&1; then
    printf 'BLOCKED: fixed runner passed but its visible PowerShell console did not close; guest was not shut down.\n' >&2; exit 2
  fi
  # Shares are transient and disappear on shutdown. Avoid disturbing the session before results validate.
  if ! timeout --foreground "${SHUTDOWN_TIMEOUT}s" VBoxManage controlvm "$VM_NAME" acpipowerbutton >/dev/null 2>&1; then
    printf 'BLOCKED: ACPI shutdown could not be requested; VM remains running and was not restored.\n' >&2; exit 2
  fi
  shutdown_deadline=$((SECONDS + SHUTDOWN_TIMEOUT))
  while (( SECONDS < shutdown_deadline )); do
    current_info="$(run_vbox showvminfo "$VM_NAME" --machinereadable || true)"
    current_state="$(awk -F= '$1 == "VMState" {gsub(/"/, "", $2); print $2; exit}' <<<"$current_info")"
    [[ "$current_state" == poweroff ]] && break
    sleep 3
  done
  if [[ "${current_state:-}" != poweroff ]]; then
    printf 'BLOCKED: guest did not reach poweroff within %ss; no forced stop or restore.\n' "$SHUTDOWN_TIMEOUT" >&2; exit 2
  fi
  if ! timeout --foreground "${VBOX_TIMEOUT}s" VBoxManage snapshot "$VM_NAME" restore "$SNAPSHOT_NAME" >/dev/null 2>&1; then
    printf 'BLOCKED: graceful shutdown succeeded but restoring the verified GA snapshot failed.\n' >&2; exit 2
  fi
  final_info="$(run_vbox showvminfo "$VM_NAME" --machinereadable || true)"
  final_state="$(awk -F= '$1 == "VMState" {gsub(/"/, "", $2); print $2; exit}' <<<"$final_info")"
  if [[ "$final_state" != poweroff ]]; then
    printf 'BLOCKED: snapshot restore completed but final VM state was not powered off.\n' >&2; exit 2
  fi
  printf 'SMOKE PASSED: fixture exit=0; fixture-only screenshot and sanitized report retained in private directory %s; VM gracefully powered off and restored to %s.\n' "$run_dir" "$SNAPSHOT_NAME"
  exit 0
fi

mkdir -p -- "$ARTIFACT_ROOT"
chmod 700 "$ARTIFACT_ROOT"
report_path="$ARTIFACT_ROOT/preflight-$(date -u +%Y%m%dT%H%M%SZ).json"
python3 - "$report_path" "$timestamp" "$VM_NAME" "$vm_state" "$vm_os" "$vm_memory" "$vm_cpus" \
  "$snapshot_state" "$current_snapshot" "$ga_state" "$free_kib" "$MIN_FREE_KIB" "$storage_ok" <<'PY'
import json, os, sys
(path, timestamp, vm, state, os_name, memory, cpus, snapshots, current, additions,
 free_kib, min_free_kib, storage_ok) = sys.argv[1:]
payload = {
 'timestamp_utc': timestamp, 'vm_name': vm, 'vm_state': state,
 'guest_os_type': os_name, 'memory_mib': int(memory) if memory.isdigit() else None,
 'cpu_count': int(cpus) if cpus.isdigit() else None, 'snapshot_state': snapshots,
 'qa_snapshot_current': current, 'guest_additions': additions,
 'disk_filesystem_free_kib': int(free_kib), 'storage_minimum_kib': int(min_free_kib),
 'storage_ok': storage_ok == 'true', 'guest_auth': 'not_checked',
 'guest_data_inspected': False,
}
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w', encoding='utf-8') as out:
 json.dump(payload, out, indent=2); out.write('\n')
print(f'Sanitized preflight report: {path}')
PY
printf 'Preflight: state=%s snapshots=%s qa_snapshot=%s guest_additions=%s disk_fs_free_kib=%s storage_ok=%s.\n' \
  "$vm_state" "$snapshot_state" "$current_snapshot" "$ga_state" "$free_kib" "$storage_ok"
printf 'No VM state changed; guest authentication/data were not inspected.\n'
