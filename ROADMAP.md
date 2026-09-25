# Roadmap — Telegram identity and Android product polish

## Objective and Success Criteria
Make account linking immediate for users already known to the Telegram bot; clearly identify users who must start the bot once. Show their Telegram avatar and render the linked handle as `./username`. Redesign route and home connection panels, show RU as an explicit hop in RU→DE routes, and use the official Amnezia mark for Amnezia profiles. Preserve tunnel/subscription behavior and deliver a tested APK.

## Stages
- [x] 1. Build/install/launch current client on TECNO CH6i and capture baseline. Fresh install shows only subscription setup; other signed-in screens require an account/subscription.
- [x] 2. Trace pairing/identity/avatar and current screen rendering; identify API's ambiguous delivery result, missing sign-in on first launch, transient avatar reset, and the underspecified route timeline.
- [ ] 3. Implement immediate pairing feedback, first-run Telegram sign-in, Telegram profile/avatar display, and consistent `./username` identity.
- [ ] 4. Refine route cards and home entry→RU→exit diagram, install official Amnezia logo asset, and polish surfaces/typography/motion across core screens.
- [ ] 5. Run focused backend and Android checks; iterate build/install/real-device screenshots until main screens are coherent.
- [ ] 6. Commit/push scoped Android/backend changes, deploy backend through the documented pull/sync/restart procedure, verify health, install final APK on TECNO, publish GitHub prerelease v0.8.4-debug, and record screenshots/digest.

## Current State
- Android repository `/home/hackov/Documents/deytt-connect`, branch `main`; preserve existing untracked `.agents/`, `.codebase-memory/`, and completed Roadmap files.
- Backend repository `/home/hackov/Documents/Projects`, branch `main`; preserve existing untracked tool/skill and Roadmap artifacts.
- Connected physical test phone: TECNO CH6i, serial `08357252AA002939`; current APK is installed and on the setup screen.
- Final code is version 0.8.4 (versionCode 18); Android unit tests/build pass and APK installed on TECNO. Launcher was invoked, but UI capture is blocked by the phone lock screen; no lock or app permissions were granted.
- Existing `TelegramPairingClient.avatar()` calls `/api/tg/me/avatar`; investigate why profile photo is absent before replacing this contract.

## Findings and Decisions
- Telegram username alone is insufficient for a bot to initiate a private chat; direct delivery requires a known bot chat/user id. Unknown users need an in-app explanation and one explicit bot-start action.
- Reuse the existing authenticated avatar endpoint if it can safely return Telegram profile bytes; never expose the bot token or a tokenized Telegram file URL to the client.
- Keep the existing route/profile and tunnel contracts; RU is a visual hop when the selected route is RU→DE.

## Issues and Failed Attempts
- `android layout` stalled while installing its instrumentation helper; stopped it and used direct `uiautomator` successfully.

## Validation and Blockers
- Baseline APK installed; screenshot `/tmp/deytt-oldphone-baseline-home.png`. Clean state prevents visual access to signed-in pages without an account/subscription.
- `SetupActivity` has no Telegram sign-in entry. Profile refresh already calls `/api/tg/me/avatar`; `MainActivity` formats the handle as `./c @username`.
- The home route summary compresses Petersburg into an exit subtitle; Amnezia profiles currently use an app-drawn generic mark instead of the upstream logo.
- Live Telegram messages will not be sent during QA; use mocked backend tests and a synthetic username.
- Current iteration adds the first-run pairing sheet, delivery-state copy, three-node RU→DE route strip, original AmneziaWG asset plus Apache-2.0 notice, and stronger connection panel hierarchy.
- Backend targeted pairing tests pass (5/5); Android debug build is running before physical-device install.
- Final Android unit test/build pass; version 0.8.4-debug (code 18) is installed. TECNO presented a vendor lock-screen overlay prompt; it was denied, then the device remained locked despite wake and a normal unlock swipe. Temporary USB-awake setting was restored to false.

## Next Action and Resume Context
Resume physical-device QA once TECNO is unlocked: capture the setup/pairing screens, inspect any authenticated screens available without sending a real code, and then publish v0.8.4-debug. The active app task still needs main-branch commit/push and a GitHub prerelease; backend has its own Roadmap/deploy stage.
