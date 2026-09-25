# Roadmap — DEYTT Connect account and globe refinement

Status: Completed

## Objective and Success Criteria
Deliver a cohesive Android experience for globe routing, diagnostics, account linking, profile/subscription actions, and Telegram identity while preserving tunnel, subscription, and backend contracts.

Success: the globe focuses on real route locations, starts without fictitious traffic, shows only consented ephemeral IP location, and animates only a selected/active route; app swipe and globe gestures do not conflict; routes, pings, icons, profile, subscription actions, and edge-to-edge surfaces are consistent; Telegram linking imports the authenticated user's existing profiles over a short-lived secure flow; physical-device screenshots and focused checks pass.

## Stages
- [x] 1. Audit current physical UI, ExteraGram references, Android architecture, and bot/API contracts. Result: four A001 baseline screens, ExteraGram profile/settings interaction cues, verified API contract, and one-time Telegram pairing design.
- [x] 2. Implement globe state/gesture/consent model and visual system. The focused atlas, route/traffic semantics, and status-bar starfield now share a coherent viewport; verified on A001.
- [x] 3. Implement route cards, flags/protocol marks, automatic and gesture-triggered diagnostics, profile purchase/extension entry points, refined settings, and language selection. RU/EN labels, IP-derived city names, and route diagnostics are verified on A001.
- [x] 4. Implement Telegram bot pairing and authenticated Android profile bootstrap using expiring one-time challenges; add scoped tests. Client flow uses the Projects API, Android test/lint/assemble passed, backend focused tests passed.
- [x] 5. Iterate build/install/run/screenshots on A001 after each group. Final RU/EN Home, Routes, Profile, and Settings captures reviewed; map, safe-area, flags, pings, CTAs, and settings labels are consistent.
- [x] 6. Review diffs, update both Roadmaps, validate, commit, push, pull affected backend services, restart and verify them. Android commit `92e5546` and backend commit `3c00a98` are pushed; server pull/scoped sync completed, and `vpn-admin.service` plus `uebot.service` are active with API `/health` returning `{"status":"ok"}`.

## Current State
- Android app is separate repo /home/hackov/Documents/deytt-connect, branch main; preserve its user-owned untracked .agents/.
- Server/control-plane repo is /home/hackov/Documents/Projects, branch main; preserve all existing untracked skills, indexes, roadmaps, and artifacts.
- A001 (`0022935AM001077`) is attached via adb and unlocked. Latest screenshot after inset fix: /tmp/deytt-home-final-v6.png; previous settings/pairing screenshots: /tmp/deytt-settings-final-v6.png, /tmp/deytt-pair-dialog-v4.png. Baseline screenshots: /tmp/deytt-baseline-home.png, /tmp/deytt-baseline-routes.png, /tmp/deytt-baseline-profile.png, /tmp/deytt-baseline-settings.png. ExteraGram settings screenshot: /tmp/exteragram-settings-tab.png.
- Code graph confirms native ViewPager2 screens and a WebView atlas. The atlas uses configured locations, renders a static selected route while disconnected, and keeps IP-derived coordinates in memory only; camera zoom is user-controlled. App chrome shows Telegram identity when linked and a `./c` fallback otherwise.
- A001 previously showed stale Connected without VPN transport. Runtime state reconciliation now prevents that stale value from controlling connect/disconnect behavior or map traffic; no tunnel was started in this task.
- First English device pass exposed mixed-language map labels and traffic units, Russian route-ping labels, and `RouteLatencyResult(...)` leaking into ping buttons. RU/EN formatting and map translation were added; route ping rendering was corrected to display the measured minimum milliseconds.
- Latest RU/EN device pass verified all four main screens and automatic route pings. English Amnezia counts, idle map label leaders, primary CTA color, selected-route focus, four-flag Auto icon, Amnezia heading, and residual RU/EN labels were refined. IP-derived Russian cities now render in Cyrillic. The settings diagnostic action was shortened to avoid truncation.
- After reinstall, Home showed that the selected Germany route still rendered as Auto: persisted IDs use `route:DE:VLESS`, while globe focus parsed only the final `VLESS` token. Route token parsing and regression cases for NL/DE/FI/RU/RU+DE/Auto are now included in the next build.
- Route-ID parsing is now validated on A001: Germany focuses only Ufa and Frankfurt, and a static direct route appears without animated traffic. The first rendering reused the download pink/orange palette while disconnected, so idle selected routes now use a quieter cyan/teal stroke; final visual review is pending.
- English screenshot review found one untranslated Amnezia section heading and the auto-pick icon still used a generic globe despite the user's request for flags. The heading is localized and auto-pick now uses a compact four-flag mark for NL/DE/FI/RU. Later review localized `Ufa` and shortened the settings row action; the final RU/EN screenshots show both fixes.
- `test-android-apps` has no callable tool in the installed-tool registry; validation uses the physical A001 through adb and Gradle tests/lint.
- Backend already provides `/api/tg/me`, `/api/tg/keys`, and hashed Telegram Mini App sessions. `/api/tg/keys` returns the current `happ.sub_url`; importing it through `SubscriptionClient` retrieves the sing-box subscription and AmneziaWG 1.5/3.1 profiles.
- Stage 2 first group is implemented: the atlas removes idle mesh routes, shows only configured locations, focuses the server cluster, draws selected routes as static lines, and only animates when UID bytes increase while Android reports a VPN transport. Map touch ownership now blocks ViewPager interception. IP coordinates are memory-only; legacy persisted coordinates are cleared and first-run consent defaults off.
- `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` passed with repository `.toolchain/jdk17` and `.toolchain/android-sdk`. An initial build using system Java 27 failed in Kotlin version parsing; no files were changed for that failure.
- The latest arm64 APK installed over existing data and all four screens were inspected on A001 in RU and EN. No tunnel was started and no Telegram message was triggered.

## Findings and Decisions
- Telegram usernames alone do not authenticate a user; pairing must prove control of the Telegram account via a bot deep-link/one-time challenge.
- The app must not store IP-derived location coordinates or city. Store at most the user's consent choice; keep approved coarse location in memory for the session.
- No actual bot message, key reset, payment, or VPN tunnel will be triggered during validation.
- Keep route and profile data contracts unchanged unless the authenticated pairing endpoint requires a narrow addition.

## Issues and Failed Attempts
- The first ExteraGram screenshot showed the physical lock screen; the user unlocked the device and the profile/settings pages were inspected without opening chats. Device is now awake for A001 validation.

## Important Files
- Expected Android: MainActivity.kt, RouteGlobeView.kt, TopLevelPages.kt, DeyttUi.kt, profile/account stores, map JavaScript assets.
- Expected backend: bot handler/DB, vpn-admin router/session repository, focused tests.

## Validation and Blockers
- A001 detected, unlocked, and interactive. Baseline and ExteraGram audit complete. Latest `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` passed; APK installed via `adb install -r`; all four screens reviewed in RU/EN. Final captures: `/tmp/deytt-home-final-ru-r5.png`, `/tmp/deytt-routes-final-ru-r5.png`, `/tmp/deytt-profile-final-ru-r5.png`, `/tmp/deytt-settings-final-ru-r5.png`, with matching `*-en-r5.png` files. JS syntax and `git diff --check` passed. ARM64 artifact: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
- Backend pairing implementation is in the separate Projects repository; API and bot focused tests passed.
- Server deployment reached `3c00a981`; seven API/bot files were synced after a clean fast-forward, both affected services restarted successfully, and `/health` returned `{"status":"ok"}` after Uvicorn opened its listener. The first immediate probe raced service readiness; the later check succeeded without code changes.

## Next Action and Resume Context
- Android visual QA, focused builds/tests, commits, pushes, scoped backend deployment, service restart, and API health verification are complete. Live Telegram account pairing and an active tunnel were not exercised.
