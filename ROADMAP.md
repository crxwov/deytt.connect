# DEYTT Connect product experience pass

## Objective

Deliver a cohesive native Android experience with genuinely continuous top-level paging, a restrained starfield identity and `./c` launcher mark, clearer connection progress, a more informative route map/traffic view, and useful account/settings actions backed by existing DEYTT services.

## Success Criteria

- Swipes and bottom navigation move between the same top-level pages without a release-only visual jump; reversal, tap, back, scroll, and map gestures remain correct.
- Starfield is legible behind every primary tab, lifecycle-aware, reduced-motion friendly, and does not cause material frame jank on A001.
- Launcher icon displays exactly `./c` in black on white.
- Connection state describes real service phases and elapsed waiting without invented percent progress or false success.
- Main map is materially larger and distinguishes verified current-IP location from selected VPN destination; traffic values are real, labeled, and gracefully unavailable.
- Profile/settings expose supported account operations; key reset has explicit confirmation and uses an authenticated existing API or an explicit Telegram bot handoff.
- AmneziaWG 1.5/3.1 profiles remain visible and easy to discover from the route UI.
- Build/install, primary screens and states, screenshots, gesture flow, and app launch are verified on physical A001. Preserve user data and keep the tunnel off unless a safe user-path check explicitly needs it.
- Review final diff, update this Roadmap, commit, push, then perform server deployment/restart only if backend/service files are changed.

## Stages

- [x] 1. Audit current UI, navigation/activity lifecycle, VPN state pipeline, map data sources, account/profile APIs, Telegram bot capabilities, build/test tooling, AmneziaWG visibility, and A001 baseline. Result: bounded implementation plan with no guessed backend operations.
- [x] 2. Replace release-only Activity navigation with a view-backed ViewPager2 host. Result: on A001 adjacent screens move side-by-side under a slow drag, settle without a release jump, and the bottom indicator tracks the gesture. Home↔Routes reversal and Home→Routes swipes both work; the Home map now yields one-finger horizontal gestures to the pager while retaining two-finger map gestures. Depends on 1.
- [x] 3. Add a native, low-cost starfield treatment across all primary tabs and replace launcher branding with exact `./c` black on white. Depends on 1; use reduced-motion/lifecycle handling. A001 confirms branding and reduced-motion. Final 64-star/16ms revision measured Home 0.63% janky frames (p95 12ms, p99 14ms) and Settings 2.27% (p95 13ms, p99 20ms) at 120Hz.
- [x] 4. Improve main connection action, truthful progress states, larger route map, origin/destination presentation, and traffic telemetry from verified sources. Depends on 1. A001 shows approximate origin, route arc, selected exit, and real subscription traffic; progress uses persisted monotonic elapsed time and honest phase labels. Temporary debug-only phase previews were used for screenshots and removed before final build; no live tunnel was started.
- [x] 5. Improve profile/settings and expose supported Telegram/account actions; implement key reset only when the authenticated backend contract and safe confirmation flow are verified. Depends on 1. Result: A001 confirms subscription usage, all five AmneziaWG profiles, privacy controls, and a disclosed Telegram reset handoff. No destructive action was triggered.
- [x] 6. Iterate through physical A001 builds, screenshots, gesture/state flows, accessibility/insets, and focused frame/jank measurement; fix findings. Depends on 2-5. Final clean build is installed; all four pages and both swipe directions are verified. Profile reset guidance now fits in its initial viewport. VPN remained disconnected.
- [x] 7. Review and record verified final state; commit and push Android changes. Server deployment/restart is not applicable because this is an Android-only repository change. Depends on 6.

## Current State

Task is ready for delivery on `/home/hackov/Documents/deytt-connect`, branch `main`, based on completed and pushed swipe commit `99d9034`. Do not stage or edit user-owned untracked `.agents/`. Physical A001 (`0022935AM001077`, 1080×2392, 120Hz) has the final clean debug APK installed. Normal launch shows the real idle VPN state. Reduced-motion is off; map origin is enabled. Final screenshots/UI trees are in `/tmp/final2-{home,routes,profile,settings}.{png,xml}`; final Home route swipe and return are verified.

## Findings and Decisions

- User-provided JPGs are typography references; app icon must be exactly `./c`, black glyphs on white.
- The referenced Starry Sky page describes a Three.js web effect with layered twinkling stars, four-point flares, nebula, and parallax. Adapt the visual language in a native Canvas implementation; do not embed React/Three.js in this Android app.
- App source is a Kotlin Android Views app with separate top-level Activities and a custom `PageSwipeFrame`; it translates only the current ScrollView, then starts a destination Activity on release. Use ViewPager2 with a RecyclerView view adapter (no Compose conversion) so adjacent screens move under the finger. The route WebView currently claims horizontal gestures, so nested gesture arbitration must be tested and tuned on A001.
- Baseline screens show large unused space, oversized rounded surfaces and compressed headings. Main map height is 236dp. Routes repeats a small globe before a long list; Profile has one quota panel and a large blank lower area; Settings has update/privacy plus a disabled placeholder.
- AmneziaWG import is present in the installed version: Routes shows one AmneziaWG 1.5 server and four 3.1 servers. The groups sit below Auto, chain, and four country groups, making them easy to miss; improve discovery without changing the validated parser/import contract.
- The app hosts the same offline atlas assets as the website. Website `speed` gets approximate IP location from `ipinfo.io/json` without a referrer and calls `NetworkAtlas.setUserLocation`; the native atlas has no user-location bridge yet. Add a cached native lookup with a clear privacy control; never store or display the returned public IP.
- The local atlas already supports `setUserLocation` and draws a user-to-route arc. Its native `atlas-init.js` bridge currently only exposes route and traffic switches; add a location bridge and pass only approximate coordinates/city/country from the app.
- Subscription metadata provides actual uploaded, downloaded, and total bytes but no live throughput stream. Use those values for truthful quota bars; animate map traffic only when the tunnel is connected.
- The existing key-reset API is destructive and requires an authenticated Telegram session header. The Android client currently imports a subscription URL and has no Telegram session. Do not call reset with a subscription token; provide a confirmed handoff to the existing Telegram bot reset flow.
- Baseline Gradle build succeeded on 2026-09-24; device package is 0.8.2, arm64 APK install succeeded, and MainActivity is foreground. Outputs are split by ABI; there is no `app-debug.apk`.
- The public website source is available at `/home/hackov/Documents/Projects/public-site/`; direct web access to `deytt.space` failed, but local source provided the map/location behavior contract.
- No MCP `test-android-apps` device tool was exposed; the installed plugin scripts plus adb/Gradle are used.
- Pass 7/8 A001 validation: map-origin lookup succeeds with privacy-safe diagnostics; Home displays origin, route arc, selected exit and real subscription traffic. Disabling map origin clears marker/cache, re-enabling restores it. Routes shows AmneziaWG 1.5/3.1 with one/four profiles; Profile shows the disclosed Telegram key-reset handoff without executing it. Settings toggles reduced motion both ways. The launcher drawer confirms black `./c` on white. The tunnel remains off.
- Pass 8 `:app:assembleDebug` succeeded, arm64 APK installed and MainActivity relaunched. Screenshots/UI trees captured Home, Routes, Profile and Settings after install. Home presents the map/traffic and connection action within the main viewport.
- Connection progress uses persisted monotonic elapsed time for STARTING/CHECKING, phase-specific detail, retry/stop labels, and a disabled STOPPING button. Stop waits for both engines before rendering IDLE. A trailing-lambda compile error at `awaitEnginesStopped` was fixed by naming `action`.
- Final A001 performance at 120 Hz, `dumpsys gfxinfo` over six seconds: Home 1,434 frames / 9 janky (0.63%, p95 12ms, p99 14ms); Settings 1,408 / 32 janky (2.27%, p95 13ms, p99 20ms). Reduced-motion preference was restored off before final validation.
- Final iteration removed the temporary `BuildConfig.DEBUG` status injector from `MainActivity`; no debug phase intent/constant remains. A clean build/install/launch succeeds, the four-tab navigation is intact, and Home reports the app disconnected. Device UI screenshots of the temporary STARTING/CHECKING/CONNECTED/STOPPING/ERROR previews were captured before removal; no VPN service was activated.

## Important Files

- `app/src/main/java/space/deytt/connect/DeyttUi.kt`
- `app/src/main/java/space/deytt/connect/MainActivity.kt`
- `app/src/main/java/space/deytt/connect/TopLevelPages.kt`
- `app/src/main/java/space/deytt/connect/IpNetworkLocation.kt`
- `app/src/main/java/space/deytt/connect/RoutesActivity.kt`
- `app/src/main/java/space/deytt/connect/ProfileActivity.kt`
- `app/src/main/java/space/deytt/connect/SettingsActivity.kt`
- `app/src/main/java/space/deytt/connect/ConnectVpnService.kt`
- `app/src/main/java/space/deytt/connect/VpnRuntimeState.kt`
- `app/src/main/java/space/deytt/connect/RouteGlobeView.kt`
- `app/src/main/res/`

## Validation and Limitations

- Latest direct web open of `deytt.space` failed; site design and account operations require repository/API inspection.
- Final A001 install captures all four primary tabs. UI trees confirm offline route list with both AmneziaWG families, subscription/account actions, privacy/reduced-motion toggles, edge-to-edge safe content, and the disconnected Home CTA. Swipe from the map to Routes and reverse to Home were repeated on the final build. Temporary phase screenshots validate presentation only; no actual connection was attempted and no key reset was triggered.
- `IpNetworkLocation.kt` has a bounded HTTPS lookup and six-hour cache storing approximate coordinates/place only; the UI discloses `ipinfo.io`, clears cache when disabled, and never retains public IP.
- Earlier physical iterations simplified the route strip, moved ping to the footer, and made receiver registration/geolocation independent of first-page inflation after `onStart`.
- This project uses native Views. Compose-only Navigation 3/adaptive guidance does not fit; ViewPager2 is the narrow native solution for direct-manipulation paging.

## Issues and Failed Attempts

- `web.open` could not access `deytt.space`; domain-scoped search returned no results. Local source inspection is the fallback.
- First compile exposed nested Activity extension receiver mistakes, incorrect ScrollView layout params, missing top-level `spacer` invocation, and Float/Double animation math in the new navigation/starfield code. Those compile errors have been corrected; rebuild required before device validation.
- A later compile found `starfieldBackground` declared as an Activity extension but called as an object function; converted it to a Context factory. Rebuild required.
- Pass 3 compile first failed on one Activity-extension receiver, missing local byte formatter, and `RouteGlobeView` exposing an internal model type. Corrected all three and verified later builds.
- Pass 1 showed the map WebView claimed single-finger horizontal movement and blocked page paging. Removed that parent-intercept request for one-finger gestures; A001 now pages smoothly from the map itself, with two-finger gestures retained for the atlas.
- Pass 3 visual review found the traffic bar below the first viewport; Pass 4 removed the redundant route row. Pass 5 found title/ping crowding; Pass 6 moved ping to a footer. The initial location lookup was skipped when the first ViewPager child inflated after `onStart`; receiver registration and lookup now initialize independently of that child.
- Initial profile footnote was visibly clipped at the bottom edge. Shortened its redundant reminder; the final A001 screenshot shows the full sentence above the bottom navigation.

## Next Action and Resume Context

Final state recorded after two clean A001 installs, four-tab screenshots, swipe/reversal, profile-footer visual fix, idle-state and crash-log checks, and `git diff --check`. Next: inspect the complete source diff and working tree, stage only project changes (exclude `.agents/`), commit on `main`, then push `origin/main`. Do not perform server pull/deploy or restart: no server/service files changed.
