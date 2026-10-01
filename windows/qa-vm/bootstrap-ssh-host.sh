#!/usr/bin/env bash
set -euo pipefail

readonly VM_NAME='win10'
readonly HOST_PORT='2222'
readonly SHARE_NAME='deytt-qa-ssh-bootstrap'
readonly SSH_USER='deyttqa'
readonly REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
readonly ARTIFACT_ROOT="${DEYTT_QA_ARTIFACT_ROOT:-/mnt/data/deytt-win10-qa}"
readonly MATERIAL_ROOT="$ARTIFACT_ROOT/ssh"
readonly STAGING_ROOT="$MATERIAL_ROOT/bootstrap-share"
readonly PRIVATE_KEY="$MATERIAL_ROOT/id_ed25519"
readonly PUBLIC_KEY="$MATERIAL_ROOT/id_ed25519.pub"
readonly KNOWN_HOSTS="$MATERIAL_ROOT/known_hosts"
readonly KEY_COMMENT='deytt-win10-qa-automation'
readonly FORWARD_NAME='deyttqa-ssh'

usage() {
  cat <<'EOF'
Usage: bootstrap-ssh-host.sh --prepare | --print-guest-command | --configure | --mount-share | --remove-share | --remove | --connect qa-status|installer-run

--prepare    Create a host-only Ed25519 key and a read-only guest bootstrap share.
--print-guest-command  Print the one-time PowerShell command without changing state.
--configure  While the VM is powered off, install only the 127.0.0.1:2222 NAT forward.
--mount-share While running, attach the read-only transient bootstrap share.
--remove-share While running, remove only that transient share.
--remove     While powered off, remove only the named QA forward.
--connect    Run one fixed QA dispatcher operation over loopback SSH.
EOF
}

run_vbox() { timeout --foreground 15s VBoxManage "$@"; }
print_guest_command() {
  local script_hash key_hash
  script_hash="$(sha256sum -- "$STAGING_ROOT/bootstrap-ssh.ps1" | awk '{print $1}')"
  key_hash="$(sha256sum -- "$STAGING_ROOT/id_ed25519.pub" | awk '{print $1}')"
  printf 'In a normal (non-elevated) guest PowerShell, stage the two public inputs:\n\n'
  printf '%s\n' "\$s=\"\$env:TEMP\\deytt-qa-ssh-bootstrap\"; New-Item -ItemType Directory -Path \$s -Force|Out-Null; Copy-Item '\\\\VBOXSVR\\$SHARE_NAME\\bootstrap-ssh.ps1','\\\\VBOXSVR\\$SHARE_NAME\\id_ed25519.pub' \$s -Force"
  printf '\nThen, in the already-open elevated guest PowerShell, run this verified bootstrap command:\n\n'
  printf '%s\n' "\$ErrorActionPreference='Stop'; Set-ExecutionPolicy -Scope Process Bypass -Force; \$s=\"\$env:TEMP\\deytt-qa-ssh-bootstrap\"; \$d=\"\$env:ProgramData\\DEYTT-QA-bootstrap\"; if(Test-Path \$d){throw 'Staging exists'}; New-Item -ItemType Directory -Path \$d|Out-Null; icacls.exe \$d /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F'|Out-Null; if(\$LASTEXITCODE -ne 0){throw 'ACL failed'}; Copy-Item \"\$s\\bootstrap-ssh.ps1\",\"\$s\\id_ed25519.pub\" \$d; \$f=\"\$d\\bootstrap-ssh.ps1\"; \$k=\"\$d\\id_ed25519.pub\"; if((Get-FileHash \$f).Hash -ne '$script_hash' -or (Get-FileHash \$k).Hash -ne '$key_hash'){throw 'SHA-256 verification failed'}; & \$f -PublicKey ((Get-Content \$k -Raw).Trim()); if(\$?){Remove-Item -LiteralPath \$d -Recurse -Force}"
}
vm_state() {
  local info
  info="$(run_vbox showvminfo "$VM_NAME" --machinereadable)"
  awk -F= '$1=="VMState" { gsub(/"/, "", $2); print $2; exit }' <<<"$info"
}
require_prepared() {
  [[ -f "$PRIVATE_KEY" && ! -L "$PRIVATE_KEY" && -f "$PUBLIC_KEY" && ! -L "$PUBLIC_KEY" ]] || {
    printf 'BLOCKED: run --prepare first.\n' >&2; exit 2;
  }
  [[ "$(stat -c '%a' "$PRIVATE_KEY")" == 600 ]] || { printf 'BLOCKED: private key permissions are not 0600.\n' >&2; exit 2; }
}

case "${1:-}" in
  --prepare)
    (($# == 1)) || { usage >&2; exit 64; }
    command -v ssh-keygen >/dev/null
    command -v realpath >/dev/null
    artifact_candidate="$(realpath -m -- "$ARTIFACT_ROOT")"
    case "$artifact_candidate/" in
      "$REPO_ROOT/"*) printf 'BLOCKED: private SSH material must be outside the repository.\n' >&2; exit 2 ;;
    esac
    [[ ! -e "$MATERIAL_ROOT" && ! -L "$MATERIAL_ROOT" ]] || {
      printf 'BLOCKED: SSH material directory already exists; refusing to change its contents or permissions.\n' >&2; exit 2;
    }
    [[ ! -e "$PRIVATE_KEY" && ! -L "$PRIVATE_KEY" && ! -e "$PUBLIC_KEY" && ! -L "$PUBLIC_KEY" ]] || {
      printf 'BLOCKED: refusing to overwrite existing SSH key material.\n' >&2; exit 2;
    }
    [[ ! -e "$STAGING_ROOT" && ! -L "$STAGING_ROOT" ]] || {
      printf 'BLOCKED: bootstrap staging path already exists.\n' >&2; exit 2;
    }
    [[ ! -L "$ARTIFACT_ROOT" ]] || {
      printf 'BLOCKED: artifact root must not be a symlink.\n' >&2; exit 2;
    }
    mkdir -p -- "$ARTIFACT_ROOT"
    [[ -d "$ARTIFACT_ROOT" && ! -L "$ARTIFACT_ROOT" ]] || {
      printf 'BLOCKED: artifact root must be a real directory, not a symlink.\n' >&2; exit 2;
    }
    artifact_real="$(cd -- "$ARTIFACT_ROOT" && pwd -P)"
    case "$artifact_real/" in
      "$REPO_ROOT/"*) printf 'BLOCKED: private SSH material must be outside the repository.\n' >&2; exit 2 ;;
    esac
    mkdir -m 700 -- "$MATERIAL_ROOT"
    prepare_complete=false
    cleanup_failed_prepare() {
      if [[ "$prepare_complete" != true ]]; then rm -rf -- "$MATERIAL_ROOT"; fi
    }
    trap cleanup_failed_prepare EXIT
    ssh-keygen -q -t ed25519 -N '' -C "$KEY_COMMENT" -f "$PRIVATE_KEY"
    chmod 600 -- "$PRIVATE_KEY" "$PUBLIC_KEY"
    mkdir -m 700 -- "$STAGING_ROOT"
    install -m 600 -- "$PUBLIC_KEY" "$STAGING_ROOT/id_ed25519.pub"
    install -m 600 -- "$REPO_ROOT/windows/qa-vm/bootstrap-ssh.ps1" "$STAGING_ROOT/bootstrap-ssh.ps1"
    : > "$KNOWN_HOSTS"
    chmod 600 -- "$KNOWN_HOSTS"
    prepare_complete=true
    trap - EXIT
    printf 'Prepared local key and guest bootstrap share at %s\n' "$MATERIAL_ROOT"
    printf 'After --configure and VM startup, run this one line in the already-open elevated guest PowerShell:\n\n'
    print_guest_command
    ;;
  --print-guest-command)
    (($# == 1)) || { usage >&2; exit 64; }
    require_prepared
    [[ -f "$STAGING_ROOT/bootstrap-ssh.ps1" && -f "$STAGING_ROOT/id_ed25519.pub" ]] || {
      printf 'BLOCKED: bootstrap share has not been staged.\n' >&2; exit 2;
    }
    print_guest_command
    ;;
  --configure)
    (($# == 1)) || { usage >&2; exit 64; }
    require_prepared
    [[ "$(vm_state)" == poweroff ]] || { printf 'BLOCKED: power off the VM before changing its VirtualBox settings.\n' >&2; exit 2; }
    command -v ss >/dev/null 2>&1 || { printf 'BLOCKED: `ss` is required to confirm loopback port availability.\n' >&2; exit 2; }
    vm_info="$(run_vbox showvminfo "$VM_NAME" --machinereadable)"
    grep -q '^nic1="nat"$' <<<"$vm_info" || { printf 'BLOCKED: adapter 1 is not configured for NAT.\n' >&2; exit 2; }
    if grep -Eq "^Forwarding\([^)]*\)=.*${FORWARD_NAME}" <<<"$vm_info"; then
      printf 'BLOCKED: a forwarding rule with the reserved name already exists.\n' >&2; exit 2
    fi
    if grep -Eq "127\.0\.0\.1,${HOST_PORT}(,|\")" <<<"$vm_info"; then
      printf 'BLOCKED: loopback host port is already forwarded.\n' >&2; exit 2
    fi
    if ss -H -ltn "sport = :$HOST_PORT" | grep -q .; then
      printf 'BLOCKED: host loopback port %s is already in use.\n' "$HOST_PORT" >&2; exit 2
    fi
    run_vbox modifyvm "$VM_NAME" --natpf1 "$FORWARD_NAME,tcp,127.0.0.1,$HOST_PORT,,22"
    printf 'Configured only the loopback NAT forward for %s.\n' "$VM_NAME"
    ;;
  --mount-share)
    (($# == 1)) || { usage >&2; exit 64; }
    require_prepared
    [[ "$(vm_state)" == running ]] || { printf 'BLOCKED: start the VM before mounting the transient share.\n' >&2; exit 2; }
    vm_info="$(run_vbox showvminfo "$VM_NAME" --machinereadable)"
    if grep -q "^SharedFolderName[^=]*=\"$SHARE_NAME\"$" <<<"$vm_info"; then
      printf 'BLOCKED: reserved shared-folder name already exists.\n' >&2; exit 2
    fi
    run_vbox sharedfolder add "$VM_NAME" --name "$SHARE_NAME" --hostpath "$STAGING_ROOT" --readonly --transient
    printf 'Mounted only the read-only transient bootstrap share.\n'
    ;;
  --remove-share)
    (($# == 1)) || { usage >&2; exit 64; }
    [[ "$(vm_state)" == running ]] || { printf 'BLOCKED: VM is not running.\n' >&2; exit 2; }
    run_vbox sharedfolder remove "$VM_NAME" --name "$SHARE_NAME"
    printf 'Removed only the transient bootstrap share.\n'
    ;;
  --remove)
    (($# == 1)) || { usage >&2; exit 64; }
    [[ "$(vm_state)" == poweroff ]] || { printf 'BLOCKED: power off the VM before changing its VirtualBox settings.\n' >&2; exit 2; }
    vm_info="$(run_vbox showvminfo "$VM_NAME" --machinereadable)"
    if ! grep -Eq "^Forwarding\([^)]*\)=.*${FORWARD_NAME}" <<<"$vm_info"; then
      printf 'Reserved QA NAT forward was already absent.\n'
      exit 0
    fi
    run_vbox modifyvm "$VM_NAME" --natpf1 delete "$FORWARD_NAME" >/dev/null || {
      printf 'BLOCKED: failed to remove the reserved QA NAT forward.\n' >&2; exit 2;
    }
    vm_info="$(run_vbox showvminfo "$VM_NAME" --machinereadable)" || {
      printf 'BLOCKED: could not verify NAT forward removal.\n' >&2; exit 2;
    }
    if grep -Eq "^Forwarding\([^)]*\)=.*${FORWARD_NAME}" <<<"$vm_info"; then
      printf 'BLOCKED: reserved QA NAT forward remains configured.\n' >&2; exit 2
    fi
    printf 'Removed only the reserved QA NAT forward.\n'
    ;;
  --connect)
    (($# == 2)) || { usage >&2; exit 64; }
    require_prepared
    case "$2" in qa-status|installer-run) ;; *) printf 'BLOCKED: operation is not allowlisted.\n' >&2; exit 64 ;; esac
    command -v ssh >/dev/null
    exec ssh -F /dev/null -i "$PRIVATE_KEY" -p "$HOST_PORT" \
      -o BatchMode=yes -o IdentitiesOnly=yes -o PasswordAuthentication=no \
      -o PreferredAuthentications=publickey -o StrictHostKeyChecking=accept-new \
      -o UserKnownHostsFile="$KNOWN_HOSTS" -o HostKeyAlias=deytt-win10-qa \
      "$SSH_USER@127.0.0.1" "$2"
    ;;
  -h|--help) usage ;;
  *) usage >&2; exit 64 ;;
esac
