# Roadmap — Stable route map and fast diagnostics

## Objective and success criteria
Stop map labels and paths jumping during all routes; make Auto show a stable, confirmed exit; ensure one best route; remove AWG 1.5; reduce AWG 3.1 diagnostics and prepare a tested downloadable ARM64 debug APK.

## Stages
- [x] Capture device behavior and trace map, scoring, AWG diagnostics, and release setup.
- [x] Stabilize map rendering, make ranking unique, remove AWG 1.5, fix the AWG UAPI socket path, and remove its unrelated external DNS canary.
- [x] Combine AWG latency and speed into one bounded request; validate icon, build and device behavior.
- [x] Record the verified final state, commit, push, and publish the downloadable debug APK.

## Current state
- Repository: `/home/hackov/Documents/deytt-connect`, branch `main`; source commit `37e340c` pushed; debug prerelease `v0.8.8-debug` published. Prior baseline was `2bf938e` / `v0.8.7-debug`.
- Physical device available: TECNO CH6i. Preserve its account and approximate-IP-location opt-in.
- This is a standalone Android client. Server pull/restart does not apply.

## Findings and decisions
- Continuous traffic animation and Auto-candidate highlights caused the map jitter. They are removed; camera/layout stays fixed as origin or exit metadata changes. Auto draws a confirmed exit where available and otherwise leaves candidates unselected. Repeated device captures on Auto and Germany remained still.
- Tied rank grouping could show multiple best protocols; ranking now assigns only one GOOD result. App catalogue/import/storage/UI remove AWG 1.5.
- AWG startup's separate public HTTPS canary stalled on DNS. The native Android build also linked an obsolete Go module symbol; a narrow Gradle overlay now points the UAPI socket into app cache, verified in the rebuilt app. Four-second ping and five-second hard download deadlines now bound waits.
- AWG latency and speed now use one authenticated first-party request; report response latency immediately, then sample throughput for one second. Log only failure type for focused device diagnosis.
- The launcher previously painted a white background behind `./c`. A transparent PNG now contains the glyph and is selected in the manifest.

## Validation and blockers
- Android unit tests, lint, debug APK assembly, JS syntax, and whitespace checks pass with JDK 17.
- Installed 0.8.8 on TECNO CH6i. Germany AWG 3.1 measured 583 ms and 4.8 Mbps; its latency and speed use one bounded request. A stale persisted VPN phase no longer cancels a live AWG speed sample; cancellation still checks the live tunnel and selected route.
- The updated `./c` PNG has transparent background and white glyph; Android 26+ uses an adaptive icon without a white backing, Android 7–8 has density-specific PNG fallbacks.
- Germany and Auto connected map captures stayed still; one GOOD/best protocol appeared per country; AWG 1.5 is absent from the app and README.
- Approximate-IP-location consent remains disabled, so live egress city cannot be independently observed on Auto.

## Next action and resume context
Install the final density-fallback APK, restore Auto and disconnected state, record SHA-256 in README, inspect and stage only project files (exclude codebase-memory, `.agents`, `.kotlin`, completed roadmaps and submodule-generated patch), commit and push `main`, then publish `v0.8.8-debug` with the ARM64 APK. This standalone client has no server service to deploy or restart.
