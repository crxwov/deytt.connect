# Roadmap — DEYTT Connect visual redesign

## Objective

Rework the Android client's visual system and major screen layouts to a cohesive, production-quality native experience, preserving VPN, subscription, route, account, and settings behavior.

## Status

Completed: visual implementation and physical-device review passed. Commit and push on `main` are the next mandatory delivery steps; this client-only UI change has no server service to deploy or restart.

## Success Criteria

- Current app is built, installed, launched, and documented on the connected physical phone before source changes.
- Important screens and meaningful loading, empty, error, disabled, and connected states are inspected and captured.
- Findings cover hierarchy, typography, density, surfaces, color/contrast, iconography, navigation, system insets, and app character.
- Redesign changes screen structure where needed and preserves existing business logic and available features.
- Each meaningful implementation group is rebuilt, installed, launched, screenshotted, and visually reviewed on the same physical device; weak areas receive another pass.
- Final build and device review pass; remaining visual limitations are recorded honestly.
- Changes are committed and pushed on `main`; server deploy/restart is evaluated only if this client-only change has a real service target.

## Stages

- [x] 1. Preserve prior unfinished Android Roadmap; build/install/run the unchanged app and capture baseline screens on the physical phone.
- [x] 2. Audit screens, interaction states, UI structure, and insets; document evidence-backed design findings and a coherent direction.
- [x] 3. Implement the redesign in focused groups, retaining behavior and accessibility.
- [x] 4. Repeat physical-device build/install/launch/screenshot/review cycles until the primary screens feel coherent.
- [x] 5. Run final build and focused regression checks; capture and record final screen evidence.
- [~] 6. Review diff, record final Roadmap state, commit and push; assess deployment applicability.

## Current State

The Android repository is `/home/hackov/Documents/deytt-connect`, branch `main`. Baseline `0.8.2` and five implementation iterations were built and reviewed on the physical A001 phone. Final APK builds successfully and is installed. Final iteration 5 screenshots cover home, Routes, and Germany protocol; prior iteration 2 covers profile/settings/setup (the saved subscription URL remains masked). The home AUTO probe's loading/result states were captured (74 ms). The app uses the website atlas renderer and Natural Earth data from bundled assets in a network-blocked local WebView. Map loading, home/network view, country focus, mobile accessibility label, one-finger rotation, and route-list scrolling were verified on-device. Multi-touch zoom is implemented by the shared renderer but ADB's input interface cannot generate a two-pointer gesture, so it was not independently verified. Connected VPN/import-error states were not forced because that would change device/network or saved-subscription state. Repository has no server-side service target for this client-only redesign. Both repositories were indexed in codebase-memory with non-persistent Android refresh; `detect_changes` is unavailable.

## Findings and Decisions

- Existing Android Roadmap preserved as `ROADMAP.device-acceptance.paused.md` because it contains unrelated unfinished tunnel acceptance and product work.
- The available phone serial is the attached physical device; emulator-only validation is out of scope.
- Test Android Apps plugin is installed; its adb-driven QA procedures are being applied to the physical phone.
- The website's own `network-atlas.js` has the desired lit globe, real country boundaries, spherical route arcs, collision-aware labels, and drag/pinch camera. Prefer hosting that same renderer locally over maintaining a visually divergent Android reimplementation. Serve only bundled atlas files from a synthetic HTTPS origin and block every unbundled request. Route selection remains in native list actions; disable map-tap route selection and continuous particle motion in the embedded app to avoid state drift and idle battery drain.
- The existing UI is built imperatively from Android Views across five Activities, not XML or Compose. The shared UI helper centralizes spacing, colors, surfaces, and edge-to-edge insets. Preserve Activity/business-flow ownership; host the standalone atlas canvas in a local WebView to keep visual parity with the site without migrating the rest of the app.
- Baseline design audit: the home screen gives a generic glowing orb and oversized connect button more weight than route context; country marks on the abstract globe collide; route/protocol pages repeat the globe and latency CTA without clear row hierarchy; profile stats are compressed; settings uses oversized generic rows for sparse content; bottom navigation is text-only and secondary screens add redundant back plus global navigation. Backgrounds, card shapes, and accent colors repeat without product-specific geography.
- Baseline state coverage: disconnected home and existing warning observed; unavailable AWG profiles appear as non-selectable-looking rows; route/protocol empty/loading/error outcomes are code-inspected but not triggered because refresh/probing could change remote or device state. Connected tunnel is not activated during visual QA.
- Decision: keep the existing native View/Activity architecture. The app is not XML-based or Compose-based, has no Compose dependencies, and its VPN/subscription entry points are already tied to Activity lifecycle; a full Compose/Navigation3 migration would add broad risk without helping the map or visual redesign. Use the applicable Views-compatible Android guidance for insets, semantics, interaction states, and physical-device validation.
- Visual direction: align with deytt.space's deep navy, cobalt/sky and mint map palette; make the offline geographic map the distinctive product asset; prioritize legibility and route clarity over decorative grid/star backgrounds and repeated cards.
- Map implementation: bundle the site's first-party atlas renderer and 1:110m Natural Earth topology; use a synthetic HTTPS origin with strict asset allowlisting, CSP, and external-request blocking. Retain real city coordinates, route mesh, lit globe, collision-aware labels, and map drag/pinch; native route rows remain the source of selection and map taps cannot silently change saved routes.
- Navigation plan: retain four primary destinations (home, routes, profile, settings) as icon+label bottom items; keep protocol/setup as drill-in flows with a back action and no duplicated global navigation.
- First device review: the new map makes Europe and the city locations recognizable, but the `RU→DE` badge clipped in its narrow slot and the home AWG warning repeated two near-identical paragraphs. Shorten the badge and summarize duplicate-version warnings.
- User review after iteration 2: map quality is materially below the website reference. Rework map composition and rendering, then make the Android sphere read as the same branded network atlas instead of calling the first geographic pass complete.
- Chosen map implementation: local Android WebView for the standalone 2D canvas only, reuse the first-party site renderer and Natural Earth asset, disable map taps that would bypass the native route store, retain drag/pinch, block external loads, and stop continuous ambient animation for device battery.
- Iteration 3 first device capture found a bad asset path: the country JSON returned a blocked response. Corrected the asset mapping to the existing top-level bundle; subsequent physical capture confirms geography loaded and the NL focus state is correct.
- Iteration 5 final capture confirms a loaded globe, correct country focus, and mobile-specific accessible gesture copy. Horizontal globe rotation and scrolling from the route list were checked on the device; multi-touch zoom could not be synthesized by the available ADB `input` commands.
- The setup/update baseline unexpectedly showed the saved subscription URL in clear text. Its screenshot was deleted from QA artifacts; fix masking before capturing this screen again. Never expose or log the URL.

## Issues and Failed Attempts

- Initial codebase-memory lookup found no indexed project. Repository indexing succeeded.
- First baseline Gradle attempt stopped before compilation because `ANDROID_HOME` was unset; the retry with the repository SDK path explicitly set passed.
- `adb install` first used a nonexistent default filename; the actual ARM64 artifact was located and installed successfully.
- The first UI-tree parse included the uiautomator status trailer; subsequent extraction uses only the complete `<hierarchy>` XML element.
- Broad natural-language graph searches returned no matching UI symbols; retry with exact symbol and file searches through codebase-memory before reading source.
- First design-group compilation found an unsupported typeface constant and missing imports; these were fixed and the build passed.
- The first design-group physical launch crashed in `DeyttUi.screen()` because `WindowCompat.getInsetsController` received the unattached screen root. The crash stack pointed to this call; using `window.decorView` fixed it and the next launch succeeded.

## Validation and Blockers

- [x] ADB detects the physical phone in `device` state.
- [x] Package `space.deytt.connect` is installed.
- [x] Codebase-memory index created/refreshed.
- [x] Baseline APK built, installed, and launched on the physical phone; screenshots were reviewed. Initial assumed APK path was wrong; the ARM64 variant was then installed successfully.
- [x] Compared Android map with the live deytt.space map and the indexed site renderer: Android has no geographic land/country contours; the site uses bundled country topology, real locations, lit relief, and route arcs.
- [x] Design-group APK compiles successfully after fixing source-level errors.
- [x] Relaunched the corrected design-group APK on the physical device and captured/reviewed home, routes, and NL protocol screenshots.
- [x] Build/install/review profile, settings, and setup; verify the saved URL remains masked in the retained screenshot.
- [x] Run the route probe loading/result state (74 ms) without changing VPN or route selection.
- [x] Final `:app:assembleDebug` build succeeded; installed ARM64 APK and relaunched on the physical phone.
- [x] Final iteration 5 home, Routes, and Germany protocol screenshots visually reviewed; accessibility tree reports the mobile route-map description, and no stale loading status remains.
- [x] Profile/settings/setup screenshots from iteration 2 remain valid; the setup screenshot contains only a masked subscription link.
- [x] `git diff --check` passed; final source diff reviewed. `detect_changes` is not available; refreshed the codebase-memory index without persisting a generated artifact.
- [ ] Commit changes on `main` and push to `origin/main`.
- [x] Server deploy/restart applicability assessed: no server-side service consumes this Android UI code.

## Next Action and Resume Context

Commit and push the reviewed Android redesign plus this Roadmap on `main`. Do not deploy/restart a server for this client-only change. If the push fails, record the failure and stop without improvising.
