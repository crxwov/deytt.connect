# Roadmap — deytt.connect 0.8.7

## Objective and success criteria
Polish the Android client against the supplied screenshots and brand assets; fix route-map labels and itinerary, connected speed/latency visibility and automatic route diagnostics, the RU→DE LTE+whitelist label, protocol marks, dialogs, profile, and settings. Validate on the attached Android phone without rotating keys or changing account data.

## Stages
- [x] Inspect app, assets, existing device state, and route contracts.
- [x] Implement cohesive dialogs, map labels/leaders, aligned itinerary, regular-weight launcher mark, profile/document/settings improvements, and RU→DE-only LTE+whitelist labeling.
- [x] Improve concurrent latency checks, bounded speed probes, automatic app-only AWG checks, and restore the user's active route after diagnostics.
- [x] Run automated checks and physical-device acceptance; install and verify 0.8.7 (versionCode 21).
- [ ] Commit the scoped Android changes and Roadmap to `main`, then push. No backend or server service changed in this task; server pull/restart is not applicable.

## Current state
- Final Android APK built and installed on TECNO CH6i. Physical screen confirms the account avatar, map labels/leaders, route strip, ping, and live speed on an active route; final idle baseline also shows the fully loaded map and automatic ping with VPN disconnected. The route strip keeps the selected Amsterdam exit aligned with the map and labels Frankfurt only as approximate IP geolocation.
- Device restored to baseline: English, Auto-select, network-location lookup off, VPN idle/disconnected.
- User data and VPN keys were preserved. Backend profile metadata work (`4544fe3`) was already deployed in the preceding task and was not changed here.

## Findings and decisions
- Route checks were physically exercised for NL and DE protocols, available NL/DE AmneziaWG profiles, FI VLESS/Trojan/Hysteria, and the RU→DE double route. Temporary AWG measurement is app-only and restores the user's selected active tunnel.
- Finland AmneziaWG is configured in the client catalogue but has no active AWG service/interface/listener on the FI host. Official Amnezia documentation warns that reinstalling/removing the server protocol can delete users and require new connection keys; no reinstall or key mutation was attempted. The app reports this endpoint unavailable.
- VLESS and Hysteria use their upstream marks. Trojan has no standalone mark in the upstream project documentation, so the UI uses a neutral diamond rather than claiming an unofficial logo.
- The live account has no registration timestamp; tenure is omitted rather than fabricated.
- Read-only key review: Happ credentials for VLESS, Trojan, and Hysteria derive from the existing subscription token; no extra identities are required to revoke that token. Xray HandlerService removes a VLESS/Trojan user from the live inbound, while the documented `sib` disconnect targets a source IP; the docs do not promise that removing a user closes already-established streams. Device-HWID revoke is enforced on subscription refresh and does not terminate an active tunnel. Amnezia guest access is explicitly revocable per protocol/device; its full-access key has different limitations. Profile copy now explains that an established Happ tunnel may persist until reconnect. No keys or sessions were changed.

## Validation
- `:app:testDebugUnitTest`: 74 tests, 0 failures/errors/skips.
- `:app:lintDebug`: passed, 0 errors (71 warnings).
- `:app:assembleDebug`: passed; ARM64 APK installed and launched on the attached phone.
- Physical route diagnostics passed on NL, DE and RU→DE; Finland AWG remains blocked by server provisioning. Active NL Trojan reconnect, ping/speed display, map-to-itinerary consistency, language, privacy setting, final profile copy, and baseline restoration verified. After final install, Auto-select idle home was left open until the map and ping finished loading; VPN remained disconnected.
- `git diff --check`: passed.
- First build attempt used unsupported system JDK 27 and failed in Kotlin version parsing; rerunning with the repository JDK 17 and Android SDK succeeded.
- Final JDK 17 build after profile copy refinement passed: 74 unit tests (0 failures/errors/skips), lint (0 errors; 71 warnings), and debug assembly. `git diff --check` passes. Final APK SHA256: `2d500dce96dcb07269db6530db8ef5b485c23eec66589d586342053496eb2db1`.

## Changed files
Android app UI and behavior in `MainActivity`, `TopLevelPages`, `ProfileFlows`, `DeyttUi`, `AppLanguage`, `ConnectVpnService`, `AwgTunnelController`, `RouteProxyProbe`, `ProtocolActivity`, and `AppDialog`, `AwgDiagnosticConfig`, `LegalDocument`, `NativeDocumentActivity`; route map asset; launcher vector/theme/manifest; focused unit tests; protocol-mark and font-license docs.

## Next action and resume context
Commit only the Android implementation, tests, owned documentation, and this Roadmap on `main`, then push. Keep `.codebase-memory`, `.agents`, and unrelated completed Roadmaps untouched. No server deployment or restart is needed because this is a client-only change.
