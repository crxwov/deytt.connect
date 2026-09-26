# Roadmap — Stable traffic map and route diagnostics

## Objective and success criteria
Restore truthful, smooth traffic animation on the active route; keep measured ping visible while speed is tested; make AmneziaWG probes reliable; fit the launcher glyph inside a transparent circular safe area; clarify measurement state and route suitability.

## Stages
- [x] Inspect live device baseline, map byte sampling and lifecycle, probe state/error handling, and icon resources.
- [x] Fix active-route traffic animation, AWG measurement failures, retained ping/speed states, and transparent icon safe area; add focused regression tests.
- [x] Resolve the live Auto outbound from sing-box group state without IP geolocation, and animate the selected route only.
- [x] Run tests, lint, debug build, install, and verify Auto/direct/double-hop route behavior on TECNO CH6i.
- [ ] Record verified release state, commit and push `main`, then publish the downloadable APK if release tooling is available.

## Current state
- Repository `/home/hackov/Documents/deytt-connect`, branch `main`, base `874145f` (`0.8.8`); no tracked changes at task start.
- Connected test device: TECNO CH6i (`08357252AA002939`); existing account and approximate-location opt-out must be preserved.
- Previous `ROADMAP.md` captured completed `0.8.8` delivery and was archived as `ROADMAP.0.8.8.completed.md`.
- Installed and verified app version `0.8.9`; device is disconnected, Auto is selected, and location privacy remains disabled.

## Findings and decisions
- Baseline home screenshot is stable in Auto with no selected egress; approximate-location permission is off. User reports connected traffic particles stopped.
- Production never sampled traffic bytes, though `TrafficActivityWindow` and tests already existed. Map motion now follows device-wide counters only while the VPN is connected and Home is visible; it draws packets on known route arcs or a pulse on the confirmed exit where origin is private/unknown.
- Route probe `download` state replaced the ping label despite preserving it in `RouteProbeSample`; both HTTP and AmneziaWG speed states now include the measured ping.
- Manual AWG measurement did not request system VPN permission; a probe failure could also be overwritten by the batch fallback. Permission is requested through the existing consent sheet, and fallback is limited to truly pending rows.
- AmneziaWG partial samples remain visible with a safe, classified timeout/address/network failure.
- Launcher source had no safe-area inset; the original transparent mark is scaled inside its existing PNG canvas, with density variants regenerated.
- Auto selected exit was previously shown only from optional IP geolocation. The live sing-box `CommandGroup` stream now reports its selected outbound; whitelisted route tags are mapped to one confirmed country/route, and the double-hop can draw its RU→DE segment. No IP lookup or extra location permission is used.
- Device testing exposed a stale active-exit marker after disconnect because selected sing-box exit and optional IP geolocation shared one map state. They are now separate; the actual exit highlights while connected and clears after disconnect.
- Code index refreshed before discovery; Android CLI, design engineering, animation, and animation review skills are being applied.

## Issues and failed attempts
- Early Gradle attempts used system Java 27 and omitted the repository SDK; reran successfully with the bundled JDK 17 and SDK.

## Validation and blockers
- `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` pass after final changes using bundled JDK 17/Android SDK; JavaScript syntax and `git diff --check` pass.
- Physical Auto connection selected Helsinki from sing-box while approximate location stayed disabled. Consecutive frames retained stable map labels/camera; disconnect removed the active-exit marker.
- Automatic country diagnostics displayed VLESS, Trojan, Hysteria 2, and AmneziaWG 3.1 results. Germany AmneziaWG measured 581 ms and 18.1 Mbps; VLESS alone received the green “best choice” status.
- Final arm64 APK SHA-256: `76814bd4cfc45bdf0ef1d39fabae04cd43152fc3c4278ebb1be786b93c5cbed1`.

## Next action and resume context
Commit the validated 0.8.9 source and roadmap to `main`, push, publish the arm64 debug APK as the next GitHub pre-release, then record the release URL/checksum and finish delivery. This standalone Android client has no production server/service to pull or restart.
