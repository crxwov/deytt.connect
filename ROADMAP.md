# DEYTT Connect interaction and visual refinement

## Objective

Refine the Android client's visual identity and interaction quality, with a restrained `./c` mark, polished route map selection, useful route/proxy diagnostics, and purposeful motion while preserving VPN and subscription contracts.

## Status

Completed. Implementation and validation passed. Commit `024b000df9fa830052967b3596442ff7b176a402` is on `main` and matches `origin/main`.

## Success Criteria

- Current visual baseline is captured before changes and final primary flows are verified on attached physical hardware.
- Branding, typography, alignment, surfaces, and navigation indicator feel consistent and deliberate.
- Route map locations support clear selection and an actionable route/bypass menu where existing app/backend contracts allow it.
- Ping/route diagnostics use supported methods and honest labels/timeouts; no fake latency or misleading tunnel guarantees.
- Motion is purposeful and reduced-motion aware, with bounded interaction transitions.
- Focused build/lint/tests and physical interaction checks pass; final diff is reviewed.
- Roadmap records evidence; changes are committed and pushed to `main`. Server delivery is performed only for server-facing changes.

## Stages

- [x] 1. Inspect app instructions, current physical-device baseline, UI architecture, route catalog, and diagnostic/API contracts. Result: four-screen A001 baseline, map touch gap, route-selection effects, direct-only latency implementation, Happ probe settings, and embedded libbox APIs are understood. Depends on project index and existing Roadmap.
- [x] 2. Shape the refined visual system and implement scoped UI, map-selection, and diagnostic improvements. Depends on 1. Implemented the lighter `./c` mark and font weights; map labels cleared; globe taps bridged to a native route sheet; country flags; corrected inset-aware nav indicator; loopback-only authenticated sing-box probe config; GET/HEAD Double request flow with 10s deadline; comparison UI; TUN guard and active-VPN safeguards. Added an Android consent flow after the real device exposed the VpnService foreground-start requirement.
- [x] 3. Build, run focused checks, install on A001, and verify the final visuals plus map/probe flows. Depends on 2. Unit tests, lint, and debug APK build pass. On A001, map-node selection opens the native route sheet; one-tap protocol comparison returns HTTPS proxy results. VPN remained off and the probe service exited.
- [x] 4. Review diff, record final evidence, and deliver the client changes. Depends on 3. Commit `024b000df9fa830052967b3596442ff7b176a402` is pushed to `origin/main`.

## Current State

Task resumed from inspection on A001 at baseline commit `0ef6a06`. Implementation, device verification, final diff review, commit, and push are complete. Untracked user-owned `.agents/` remains untouched. This is client-only work; no server deployment is required.

## Findings and Decisions

- Android source remains the separate Kotlin Views repository `crxwov/deytt.connect`; do not mix it with the DEYTT backend.
- Existing app includes a native route globe/WebView atlas, RU+DE route selection, AWG profile selection, and manual endpoint probes. Verify current implementation and server contract before changing behavior.
- Baseline captured on A001 (`/tmp/deytt-connect-baseline-{home,routes,profile,settings}.png`). Initial visible tab was Routes.
- Current atlas sets `selectOnTap: false`; the current route measurement first invokes system ICMP then times a direct TCP socket. Backend Happ profile metadata specifies proxy ping, the `check-url-via-proxy` endpoint, and a 10-second proxy timeout. The client probe must be tied to an actual proxy path before claiming Happ-like latency.
- Codebase-memory search found route symbols, but exact snippets did not match current `MainActivity.kt` line contents; the repository index was refreshed before further graph-based exploration. Generated index artifacts were restored before delivery.
- Refreshed codebase-memory index. Baseline now captured for all four primary tabs on A001. Home brand mark and wordmark use bold type; navigation indicator positions from full bar width despite padded cells; route screen repeats large grouped surfaces with abbreviation-only icons. Route map stays decorative on touch. Route selection currently stops a running tunnel; any new route picker must disclose this and preserve the selection contract.
- Official Happ App management docs confirm ping types via Proxy GET/HEAD, TCP, ICMP; `double` and `keepalive` are distinct proxy modes. The timeout range 5–15s is documented for iOS, so the Android app needs its own bounded timeout if implementing this behavior. Existing backend metadata currently uses `keepalive`; client work will not alter it.
- Embedded libbox exposes `CommandServer.startOrReloadService`; the existing VpnService's interface protects core sockets from its TUN. Planned probe path: run a temporary libbox service config with an authenticated loopback HTTP proxy inbound and the chosen outbound, without a TUN inbound; issue two GET/HEAD requests with a 10s total deadline. A hard guard will reject any unexpected `openTun` call. Physical validation must confirm this path before calling it complete.
- Product decisions: use a compact bottom sheet on a map-node tap to choose Auto, that country's VLESS/Trojan/Hysteria 2 route, and RU→DE when applicable; use the existing `SelectedRouteStore` and `MainActivity.selectRoute` contracts. Tapping the user's origin explains that it is not a VPN exit. If a tunnel is active, disclose that changing route stops it.
- New diagnostic decisions: start a temporary command-server configuration with an authenticated `mixed` inbound on loopback, selected route as final outbound, and no client subscription inbounds. Reject all `openTun` calls while diagnostic-only. Check two HTTPS responses within a shared 10-second budget; use actual tunnel traffic only for its currently selected route, and identify inactive AmneziaWG endpoint checks as TCP-only.
- Substantial UI work will stay in native Android Views. Compose-only adaptive and CSS-only animation recipes do not fit this repository; apply their restraint/reduced-motion principles without migrating frameworks.
- The request covers a broad visual refinement and map-driven route interaction; keep visual changes within client scope unless an existing API change is essential and separately evidenced.

## Issues and Failed Attempts

- Physical proxy comparison initially hit `ForegroundServiceDidNotStartInTimeException` twice. Early foreground promotion and explicit startup failure handling fixed it; a subsequent A001 comparison completed successfully.
- `HttpsURLConnection` through the authenticated local proxy failed to establish the HTTPS probe. Replaced it with explicit HTTP CONNECT, platform-trusted TLS plus hostname verification, and bounded GET/HEAD requests; repeated route comparisons then succeeded on A001.

## Important Files

- `DeyttUi.kt`, `MainActivity.kt`, `TopLevelPages.kt`, `ProtocolActivity.kt`, `RouteGlobeView.kt`, `RouteLatency.kt`, `ConnectVpnService.kt`, `RouteProxyProbe.kt`, `RouteProxyProbeTest.kt`, and the bundled route-map JavaScript/CSS.

## Validation and Blockers

- `ANDROID_HOME="$PWD/.toolchain/android-sdk" ANDROID_SDK_ROOT="$PWD/.toolchain/android-sdk" JAVA_HOME="$PWD/.toolchain/jdk17" ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain` passed after the final source changes.
- A001: updated APK installed and launched; map-node route sheet, protocol comparison screen, and results were inspected. Last sample: VLESS 540 ms, Trojan 377 ms, Hysteria 2 346 ms. These are transient measurements.
- After comparison, `active_vpn_transport=false` and no `ConnectVpnService` instance remained. The probe uses the loopback HTTP proxy; no device TUN was opened.
- `git diff --check` passed. A physical interaction test of the GET/HEAD preference menu was not performed; its default HEAD setting is visible and its change handler is covered by source review.
- `main` and `origin/main` both resolve to `024b000df9fa830052967b3596442ff7b176a402`.

## Next Action and Resume Context

No further action. No server pull/deploy or service restart applies to this client-only change.
