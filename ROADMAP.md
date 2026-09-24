# Roadmap — DEYTT Connect visual redesign

## Objective

Translate the actual deytt.space visual identity into a polished native Android client, using installed VPN apps as product references. Preserve VPN, subscription, route, profile, and settings logic.

## Status

Completed: redesign and physical-device QA are complete. Commit and push to `main` are the remaining delivery actions. No server-side service consumes this Android-only change.

## Success Criteria

- Baseline and installed client/site references reviewed before redesign.
- Main flows rebuilt structurally where needed, while keeping business behavior and available features.
- Every meaningful implementation group built, installed, launched, captured, visually compared, and iterated on the physical phone.
- Final screens use the website's real map and visual language, clear hierarchy, native insets, legible states, and purposeful reduced-motion-aware animation.
- Final build and device review pass; limitations are recorded honestly.
- Commit and push on `main`; evaluate deploy/restart applicability.

## Stages

- [x] 1. Capture baseline and audit installed VPN references, deytt.space, screen states, and system insets.
- [x] 2. Define mobile visual direction and motion model from those references.
- [x] 3. Redesign the shared system, atlas, navigation, and primary screen flows.
- [x] 4. Repeat physical-device build/install/launch/capture/review cycles and fix evidenced weaknesses.
- [x] 5. Perform final build, on-device state review, diff review, and record limitations.

## Current State

Repository: `/home/hackov/Documents/deytt-connect`, branch `main`. Physical device A001 (1080×2392) runs the final debug APK. Home, Routes, country Protocol selection, Profile, Settings, Setup, and Setup with keyboard have been reviewed across the iterations. Current map uses the site's default bright blue marble, real Natural Earth boundaries, city nodes, route arcs, and local offline assets. The final Protocol flow selects/highlights locally and confirms through the existing route-save path. Route remains Auto and VPN remains disconnected. Setup's saved subscription URL remains masked. `.agents/` is unrelated user-owned untracked content and must not be staged.

## Findings and Decisions

- Inspected installed Incy, v2rayTun, Happ, and Amnezia, plus deytt.space at mobile and desktop widths. Site identity: paper/dither field, Inter Tight, Unbounded, JetBrains Mono, cobalt/sky/mint, and a strongly legible geographic globe.
- Reuse the first-party `network-atlas.js` and Natural Earth asset in a network-blocked local WebView; keep route selection in native rows. Android now uses the site's light-theme atlas palette, not the dark variant that hid the land and borders.
- Preserve the existing native View/Activity architecture. Use compact route/protocol rows, a site-like active underline in bottom navigation, a docked primary action, and an explicit selected/confirm state for country routes. `select(route)` still owns stopping/saving/navigation.
- Use short eased transitions, staggered route-row entry, connection-state feedback, and no idle traffic animation while disconnected or backgrounded. CSS and Android animations respect reduced-motion/animator settings.
- No backend, API, or server behavior changed. No server deployment/restart target exists for this client-only change.

## Important Files

- `app/src/main/java/space/deytt/connect/DeyttUi.kt`
- `app/src/main/java/space/deytt/connect/MainActivity.kt`
- `app/src/main/java/space/deytt/connect/RoutesActivity.kt`
- `app/src/main/java/space/deytt/connect/ProtocolActivity.kt`
- `app/src/main/java/space/deytt/connect/ProfileActivity.kt`
- `app/src/main/java/space/deytt/connect/SettingsActivity.kt`
- `app/src/main/java/space/deytt/connect/SetupActivity.kt`
- `app/src/main/java/space/deytt/connect/RouteGlobeView.kt`
- `app/src/main/assets/route-map/`
- `app/src/main/assets/fonts/`

## Validation and Limitations

- Final `:app:assembleDebug` succeeded; the ARM64 APK installed and launched on physical A001.
- Captured and reviewed current Home, Routes, Protocol disabled state, and Protocol selected state. UI tree confirms `Использовать VLESS`; it was not pressed. Returning Home confirms `Автоподбор` and `Не подключено`.
- Reviewed Profile, Settings, masked Setup, and Setup with keyboard in the preceding device iteration. Edge-to-edge header/dock clipping was corrected and rechecked on A001.
- No `IndexSizeError`, uncaught JavaScript error, or fatal app exception appeared in the final logcat sample. `git diff --check` passed. Android codebase-memory index refreshed; `detect_changes` is unavailable.
- Connected VPN state and remote import/error transitions were not forced because they would change tunnel/subscription/network state. Their rendering paths were inspected in code. ADB could not synthesize a two-pointer zoom gesture.
- The Android CLI layout helper was blocked by Play Protect; it was dismissed without changing security settings. Physical QA continued through the installed test-android-apps ADB/uiautomator workflow.

## Next Action and Resume Context

Commit only the Roadmap, Android source/assets, and bundled fonts; leave `.agents/` untouched. Push `main`. Do not pull/restart a server for this client-only change.
