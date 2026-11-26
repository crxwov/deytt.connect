# Windows 0.8.50 interaction review

Scope: existing dirty Windows redesign checkout; local fixes/package only. Synthetic QA fixtures never establish authenticated VPN acceptance.

| Area | Review and evidence |
| --- | --- |
| Window shell/navigation | Minimize/maximize/restore/close handler regression test; header drag/double-click source review; persistent-page navigation tests; wide/compact rendered screens. |
| Home globe | Pointerdown redraw and lost capture regression executed in WebView2; both true. Default route no longer triggers compact mode automatically. Whole panel hover matrix is identity; children have no independent rotation. |
| Traffic | Hover and one-click hint, double-click inspector, isolated selected line/dots, moving flow, stable geography on open and alternate line all true in WebView QA. Space/ESC immersive tested. |
| Home state/metrics | Error shows sanitized actual service detail in both panel variants. Probe update88ms/48Mbit fixture verifies refresh bridge; production measurement remains a live check. |
| Routes | Country expand/collapse, route selection, persistent probe state, keyboard/scroll tests; dark/light compact/wide renders and vector protocol icons inspected. |
| Profile/settings | Source review of load/error/empty states, device/session/help flows, reset/refresh concurrency. Duplicate reset cancel regression; terminal missing-device retry removed. Rendered both themes and compact layouts. |
| Dialogs | Existing confirm/cancel/focus/serialization tests; new320px-height scrollable footer regression. Initial focus, ESC and focus restoration source review. |
| Motion/theme | Short button/page/dialog fades; whole-panel180ms rotate; state/inspector200ms entrance. Reduced motion preserves positioning. Fluent template hover rectangles removed, focus border visible. |
| Startup/service | Canary hosts resolve before strict TUN; initial HTTPS uses captured IPs and original hostname/TLS validation.75s IPC budget. Diagnostic redaction extended; DNS bootstrap/cancellation/deadline tests. |
| Updates/account actions | Read-only source review of URL/hash/redirect verification and loading/retry handlers. No publication, payments, reset, session revoke, install, or real connect exercised. |

## Validation
107 protocol/service tests +78 Windows client tests +71 headless UI tests passed (256 total).27 targeted service tests are included in the107. Win-x64 app/service publish0.8.50 passed. QA captures/logs: D:\DeyttConnectBuild\review-0.8.50\qa. Tested1366x768,720x520,520x420, immersive; short-dialog regression320px height. Existing Chromium shutdown log: class unregister1412; no app failure found.
Frame sample: approximately165fps, p95 interval6.1ms, p95 drawing0.5ms, no intervals>20ms in360-frame sample; this is local fixture evidence, not a guarantee across devices.

## Remaining acceptance
Installed service Status was connected after successful retry, so original first failure detail was unavailable. A concrete DNS verification dependency was removed, but actual first-connect acceptance requires running the new installed client. Physical native drag/high-DPI lifecycle, real account/server flows and installer upgrade/removal not exercised. Do not mark the reported first-connect issue confirmed resolved until live acceptance.

Final package delivered: C:\Users\hackov\Downloads\deytt-connect-0.8.50.msi,88218009bytes, SHA-2563fa28aaf5c0b04bcc17e9a9f67f9ecd15f8981b06799c0c7c09a1a67f5450631; sidecar matches. Final native hover renders and reduced-motion1366/720 layouts inspected.20 total QA captures. All source/staged map assets match; previous .49 installer hash unchanged. MSI checks passed with six existing WiX warnings and zero errors. Live first-connect acceptance remains pending.
