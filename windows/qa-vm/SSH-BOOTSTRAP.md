# Windows 10 QA VM SSH bootstrap

This is a one-time, reviewable setup for the `win10` VirtualBox QA guest. It creates a dedicated standard Windows account (`deyttqa`) that authenticates with one host-only Ed25519 key. Password authentication is disabled in `sshd`; the account receives a random password that is discarded. The SSH server accepts that account only, forces a PowerShell dispatcher, and the key also disables PTY, agent, X11, and TCP forwarding. The dispatcher accepts only `qa-status` and `installer-run`.

The host forward binds to `127.0.0.1:2222` and targets guest TCP 22 through VirtualBox NAT. The guest firewall rule accepts the VirtualBox NAT gateway address only. The bootstrap share is read-only and contains only the public key and bootstrap script. It is removed after setup. No guest password is used, requested, or sent through the host.

## One-time setup

Review this document, `bootstrap-ssh.ps1`, and `bootstrap-ssh-host.sh` before running them. Start from the existing powered-off QA snapshot. On the Linux host, run:

```sh
windows/qa-vm/bootstrap-ssh-host.sh --prepare
windows/qa-vm/bootstrap-ssh-host.sh --configure
```

`--prepare` creates the private key outside the repository at `$DEYTT_QA_ARTIFACT_ROOT/ssh/id_ed25519` (default `/mnt/data/deytt-win10-qa/ssh/id_ed25519`) with mode `0600`. Its public half and the bootstrap script are staged outside the repository. `--configure` changes only VM 1's NAT port-forward rule; the VM must be powered off. Start the guest, then mount the temporary share:

```sh
windows/qa-vm/bootstrap-ssh-host.sh --mount-share
```

Sign in normally. First run the printed copy command in a normal, non-elevated PowerShell window; the administrator shell in this VM cannot read the `\\VBOXSVR` share. Then remove the transient share from the host:

```sh
windows/qa-vm/bootstrap-ssh-host.sh --remove-share
```

In the already-open elevated PowerShell, run the printed verified command. It copies the two files from the user's temp directory to a new ACL-protected local staging directory, validates both SHA-256 values printed independently by the host, and executes only that verified local copy. The scope-limited execution-policy change applies to that PowerShell process only. The guest script installs OpenSSH Server if needed, sets the account and restrictions, validates `sshd_config`, creates a disabled fixed MSI scheduled task, and starts `sshd`. The fixed task runs only `C:\ProgramData\DEYTT\QA\fixed-msi-installer.ps1` with no parameters; its current contents fail closed. The task DACL grants SYSTEM and Administrators full control and the QA account read/execute only. Keep `installer-run` unavailable until the separate MSI runner has been independently reviewed, installed in that ACL-protected path, and the task start permission has been validated in Windows.

After setup, power off the VM and run:

```sh
windows/qa-vm/bootstrap-ssh-host.sh --remove
```

This removes only the named NAT forward. Start the guest normally. Host operations are then fixed and allowlisted:

```sh
windows/qa-vm/bootstrap-ssh-host.sh --connect qa-status
```

First connection uses OpenSSH's `accept-new` host-key policy in a private, dedicated `known_hosts` file. A changed VM host key fails closed; remove only that pinned entry after independently restoring/reviewing the VM.

## Boundaries and rollback

- Do not enable the MSI scheduled task until its SYSTEM-run payload is fixed-path, ACL-protected, parameterless, and independently reviewed. The task is disabled and its installed payload deliberately reports `not provisioned`.
- The SSH account is standard, not a local administrator. SSH commands cannot select an executable, script, file path, task name, or task argument.
- Do not expose port 22 on a LAN interface. Do not replace the `127.0.0.1` host bind with `0.0.0.0` or a host address.
- If bootstrap reports an error, leave the VM on its current state for review. Do not rerun it over a partial setup. Restore the powered-off snapshot if needed.
- To undo a completed guest setup, sign in locally, open elevated PowerShell, and run `& 'C:\ProgramData\DEYTT\QA\bootstrap-ssh.ps1' -Rollback`. The rollback removes only the reserved QA account, task, firewall rule, SSH configuration, and OpenSSH capability installed by this bootstrap. Verify the guest is powered off before restoring its snapshot.
- Remove host credentials with `shred -u /mnt/data/deytt-win10-qa/ssh/id_ed25519` when this QA access is retired, then remove the rest of the private `ssh` staging directory. The key must never be copied into the repository, ticket, or logs.

The bootstrap checks Windows 10 x64 build 17763 or newer before creating its protected state directory or changing guest state. It refuses a pre-existing OpenSSH firewall rule rather than changing an unrelated baseline. If it installs the OpenSSH capability, it disables the default broad firewall rule immediately; the catch and rollback paths disable it as well. The bootstrap has not been run against the VM. Static validation and an independent review must pass before the one-time elevated guest action.

Run the offline SSH/MSI source guardrail check with:

```sh
python3 windows/qa-vm/test-bootstrap-static.py
```

This check inspects source ordering and fail-closed contracts only. It does not parse or execute PowerShell, build the MSI, or change VirtualBox or guest state.

## Implementation references

- [Microsoft OpenSSH Server configuration](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh-server-configuration) documents Windows `sshd_config`, account allowlists, authentication methods, and authorized key locations.
- [Microsoft Task Scheduler security contexts](https://learn.microsoft.com/en-us/windows/win32/taskschd/security-contexts-for-running-tasks) documents that running a task requires its execute permission and that the task DACL controls read, write, execute, and delete access. The bootstrap grants the QA SID read/execute only; SYSTEM and Administrators retain full control.
- [Microsoft OpenSSH Server install and first use](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh_install_firstuse) documents the optional capability and default firewall rule. The bootstrap disables the broad default rule and installs its NAT-source-limited rule.
- [Oracle VirtualBox User Manual](https://www.virtualbox.org/manual/) describes NAT port forwarding and `VBoxManage` VM network settings; the setup binds the host endpoint to loopback.
