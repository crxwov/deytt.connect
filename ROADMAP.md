# DEYTT Connect visual redesign and AmneziaWG repair

## Objective

Restore AmneziaWG profile imports and redesign the Android client with a coherent deytt.space visual language, Exteragram-inspired grouped navigation, Material feedback, and purposeful motion.

## Success Criteria

- Find and repair why expected AmneziaWG configurations were missing.
- Preserve VPN, subscription, route, profile, and settings behavior.
- Add horizontal top-level swipes and direction-aware tab transitions.
- Iterate on the physical A001 device with builds, installs, launches, screenshots, comparison, and fixes.
- Record a reviewed final state, commit and push Android changes to main; evaluate server deployment applicability.

## Stages

- [x] 1. Inspect app and physical-device states; trace subscription and navigation behavior.
- [x] 2. Repair AWG visibility and implement directional tabs and horizontal swipes.
- [x] 3. Refine map, screens, shared visual system, motion, and interaction states.
- [x] 4. Build, install, capture, compare, and iterate on the physical device.
- [x] 5. Validate final state and record it before the required Android commit/push.

## Current State

Implementation and physical-device validation are complete; the Roadmap is recorded before the required commit/push. Repository: `/home/hackov/Documents/deytt-connect`, branch `main`. A001 (1080×2392) is left on MainActivity, VPN disconnected, Auto route selected. Version 0.8.2 debug is installed. Keep unrelated `.agents/` untracked.

## Findings and Decisions

- Both public AmneziaWG formats failed with HTTP 502 while direct Uvicorn requests returned 200. Their large response headers exceeded Nginx's default 4 KiB proxy buffer. A location-scoped 8 KiB buffer fixed this without changing API behavior.
- The Nginx fix was committed as `396c64e4`, pushed and pulled on the server; the exact live config was backed up, `nginx -t` passed, and Nginx gracefully reloaded.
- Physical import now shows five profiles: one AmneziaWG 1.5 and four AmneziaWG 3.1. Never read or expose profile bodies or subscription URLs.
- Top-level navigation uses directional Android transitions and left/right swipes. Edge gestures are reserved for system back, vertical gestures remain scroll, and swiping the globe retains map rotation.
- Visual language: deytt.space dark network atlas and restrained cobalt/cyan light; Exteragram-inspired grouped rows and compact icon tiles; Material ripple and touch feedback. Main, routes, protocol choices, and settings were restructured and tuned on device.
- No server deploy applies to Android-only source changes; the related backend/Nginx correction has already been deployed. No service restart is needed for the client repository.

## Important Files

- `app/src/main/java/space/deytt/connect/DeyttUi.kt`
- `app/src/main/java/space/deytt/connect/MainActivity.kt`
- `app/src/main/java/space/deytt/connect/RoutesActivity.kt`
- `app/src/main/java/space/deytt/connect/ProtocolActivity.kt`
- `app/src/main/java/space/deytt/connect/SettingsActivity.kt`
- `app/src/main/java/space/deytt/connect/SubscriptionClient.kt`
- `app/src/main/java/space/deytt/connect/SubscriptionRetryPolicy.kt`
- `app/src/main/assets/route-map/atlas-init.js`, `network-atlas.css`
- `app/src/main/res/anim/page_*.xml`

## Validation and Limitations

- `:app:assembleDebug` succeeded for the final UI build; APK installed and launched on physical A001.
- Main, Routes, both AWG protocol screens, and Settings were captured and reviewed. Protocol validation showed the single 1.5 item and all four 3.1 items. Horizontal navigation was exercised in both directions; a swipe over blank content changed tab, while the globe kept its own gesture. No VPN tunnel was started.
- Diagnostic `:app:testDebugUnitTest` passed for safe retry/error summaries. The final visual-only iteration was rebuilt and exercised on device.
- Screenshots: `/tmp/deytt-final-home-installed.png`, `/tmp/deytt-final-routes-top.png`, `/tmp/deytt-final-routes-bottom.png`, `/tmp/deytt-final-protocol-awg15-rest.png`, `/tmp/deytt-final-protocol-awg31.png`, `/tmp/deytt-final-settings.png`.
- Profile and setup screens inherit the shared theme but were not captured in order to avoid exposing personal usage/subscription data.
- No blocking visual or functional issue remains from the requested review. Final Git whitespace/diff review and Android commit/push are the next delivery actions.

## Issues and Failed Attempts

- Resolved a missing UI import and an Activity receiver mismatch during early builds.
- Horizontal touch was first delivered only to clickable rows; moved gesture handling to the screen shell so blank content swipes work. The map remains excluded from page gestures.

## Next Action and Resume Context

Commit the reviewed Android changes and Roadmap to `main`, push to `origin/main`, and verify the result. Do not stage `.agents/`. Server pull/reload is inapplicable to this Android-only commit; the backend header-buffer fix is already deployed.
