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

`--installer-smoke` is the fixed native WiX 6/MSI install-uninstall mode. Its
guest runner accepts no parameters, verifies a SHA-256 manifest, builds only
the pinned installer project, uses a fixed `1.0.0` package identity and SYSTEM
SID, checks fixed install footprints, then uninstalls only the matching DEYTT
product. It never launches the app or connects a tunnel. Logs stay in a unique
guest temp directory and are not returned; the host receives only a bounded
sanitized JSON report. The mode currently fails closed before changing VM state
because an elevated guest launch path has not yet passed independent review.
Do not treat preflight as MSI installation evidence.

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
  environment. The new diagnostic reported `InvalidOperationException` from
  Avalonia `Win32NativeControlHost` attachment (`DumbWindow..ctor` through
  `NativeControlHost.OnAttachedToVisualTree`). The fixture home constructs the
  native `RouteGlobeWebView`; this is the likely trigger, though the exception
  message is intentionally unavailable.
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
  does not validate MSI installation, native WebView2 behavior, a real account,
  VPN routes, tunnel health, or HTTPS egress.

Run manually:

```sh
windows/scripts/qa-vm.sh --preflight
windows/scripts/qa-vm.sh --dry-run
windows/scripts/qa-vm.sh --smoke
```
