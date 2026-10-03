# Windows 10 VM QA

`../scripts/qa-vm.sh --preflight` records only sanitized VM state, the current
QA snapshot name, Guest Additions reporting, and free space on the filesystem
that contains the VM disk. The default minimum is 40 GiB. `--dry-run` prints
the fixed smoke plan without changing VM state.

`--smoke` publishes only `DeyttConnect.Windows.QA`, stages the package with a
SHA-256 manifest, and uses temporary VirtualBox shared folders. The guest runner
accepts no parameters or arbitrary commands. It verifies the package, launches
the signed-out synthetic fixture, and reports its PID and window bounds. The
host captures a temporary full-display image privately, crops to those bounds,
and waits for visual confirmation of the synthetic fixture before acknowledging
the capture. The guest then closes only the fixture and writes a sanitized JSON
result. Any failed stage leaves the VM as-is; shutdown and snapshot restore are
never attempted while the fixture may still be active. It never reads guest
credentials, clipboard contents, or user files and does not run a live
account/tunnel test.

`--installer-prepare` builds the fixed package and copies its manifest-verified
files from the non-elevated VirtualBox share into the logged-in user's local
`%TEMP%` directory. The host prints a fixed manual bootstrap command for an
Administrator PowerShell target under the same Windows account that ran the
stager; it never types into a guest window after the
`-NoExit` stager, because that console may still have focus. The command pins
both the host-generated manifest SHA-256 and runner SHA-256. It reads runner
bytes once, verifies them against the host digest, and executes those exact
verified bytes. The runner then verifies the manifest digest, rejects reparse
points and path escapes, copies every manifest-listed input into an
Administrators/SYSTEM-only protected directory under ProgramData, and
rehashes the protected copies before invoking fixed WiX/MSI operations from
that protected tree. It accepts only the expected digest argument; paths and
commands are fixed in the runner. It does not launch the app or connect a
tunnel. Logs stay in the protected guest staging directory.

`--installer-smoke` remains blocked while the source review gate in
`installer-smoke-host.sh` is `pending`. Package staging, a printed command, or
static checks do not count as MSI/install acceptance. Do not run the displayed
command until independent source review approves it and you have opened the
intended elevated PowerShell console yourself. Native MSI build/install,
uninstall, ACL behavior, UAC and focus behavior still require the user's later
Windows session.

The runner reports the SHA-256 digest of its locally built MSI. The collector
accepts it only as a 64-character lowercase hex digest and only reports a
passed run when install, uninstall, cleanup, and child-process fields all pass.
It reads the report only after the local `installer-complete.txt` marker is
present and contains the exact expected value.

The earlier membership-probe attempt did not produce its start sentinel; that
failure was superseded by a later elevated PowerShell probe, which confirmed
Windows 10 x64 and administrator membership. This validates only the current
interactive elevation prerequisite. No MSI build/install has run.

Host artifacts are created in a mode-0700 run directory under
`/mnt/data/deytt-win10-qa` by default; reports, logs, and screenshots are
mode-0600. Set `DEYTT_QA_ARTIFACT_ROOT` to choose another local directory. The
publish log is private and is not copied into the guest or smoke report.

## Current verified baseline

- Original baseline snapshot `deytt-qa-baseline-20260930T144407Z` is retained.
- Current QA snapshot: `deytt-qa-ga-7.2.20-20260930T151115Z`.
- Guest Additions reports version `7.2.20`.
- The verified GA ISO remains attached; the missing Windows installer ISO was
  not replaced.
- The published fixture is a signed-out synthetic UI and skips production
  session/API startup. It is not evidence of a live account or VPN tunnel.
- The host gate checks the VM disk's own filesystem (40 GiB free minimum), not
  the root filesystem.

## Smoke attempt status (2026-09-30)

- The bounded retry published and verified the synthetic fixture package, saw
  the guest runner sentinel and window, and captured a private host-side crop
  after visual confirmation. Guest-side GDI capture still fails with
  `Win32Exception`.
- The runner report is `failed`: phase `graceful-close`, process exit code
  `-532462766` (CLR exception), `graceful_close_requested=false`, and
  `process_force_stop_used=false`. The sanitized log/report contain no exception
  type or stack, so the cause cannot be narrowed without another diagnostic
  path. Do not interpret process exit as a passing graceful-close check.
- VM remains running on the current GA snapshot; no shutdown or restore was
  attempted. No account data was opened. No logon task or MSI installation test
  was attempted; MSI QA remains blocked until fixture cleanup is reliable.

## Bounded diagnostic retry (2026-09-30)

This section records a failed diagnostic attempt; its VM-state description was
superseded by the successful smoke below.

- Added an opt-in QA-only crash report that records exception type and up to 16
  stack method names, excluding exception messages, source paths, and process
  environment. The diagnostic reported `InvalidOperationException` from
  Avalonia `Win32NativeControlHost` attachment (`DumbWindow..ctor` through
  `NativeControlHost.OnAttachedToVisualTree`). At the time, Home hosted a native
  WebView for its atlas. Windows now hosts the same canvas atlas and geographic
  data as Android directly from bundled offline assets. Native QA is still
  needed to confirm WebView2 rendering and pointer input on Windows.
- The fixture window-ready signal was written, but the VM screenshot dimensions
  did not contain its reported window bounds. The private full-display capture
  was deleted without inspection; no screenshot was confirmed. Runner timed out
  waiting for the bounded host-capture acknowledgement, then wrote the sanitized
  report. No further retry, shutdown, or snapshot restore was attempted.
- VM remains running on the GA snapshot. The QA smoke is not stable, so no MSI
  install test or logon automation should run yet.

## Latest synthetic smoke (2026-09-30)

- The synthetic QA fixture smoke passed after the diagnostic retry: host-side
  screenshot capture succeeded and the fixture exited gracefully with exit
  code `0`.
- The VM snapshot was restored and the VM is powered off. The earlier
  guest-side GDI capture failure and CLR close crash are historical failed
  attempts, not the result of the latest smoke.
- This validates only the synthetic fixture lifecycle and screenshot path. It
  does not validate MSI installation, native map rendering/input, a real
  account, VPN routes, tunnel health, or HTTPS egress.

Run manually:

```sh
windows/scripts/qa-vm.sh --preflight
windows/scripts/qa-vm.sh --dry-run
windows/scripts/qa-vm.sh --smoke
```

The one-time SSH bootstrap and its two-step verified guest action are
documented in [SSH-BOOTSTRAP.md](SSH-BOOTSTRAP.md). Run the offline source
guardrails for the SSH bootstrap and gated MSI staging/runner with:

```sh
python3 windows/qa-vm/test-bootstrap-static.py
```

The static check does not parse or execute PowerShell, build a package, or
change VirtualBox or guest state.
