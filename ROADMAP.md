# Roadmap — Telegram identity and Android product polish

## Objective and Success Criteria
Make account linking immediate for users already known to the Telegram bot; clearly identify users who must start the bot once. Show their Telegram avatar and render the linked handle as `./username`. Redesign route and home connection panels, show RU as an explicit hop in RU→DE routes, and use the official Amnezia mark for Amnezia profiles. Preserve tunnel/subscription behavior and deliver a tested APK.

## Stages
- [x] 1. Build/install/launch current client on TECNO CH6i and capture baseline. Fresh install shows only subscription setup; other signed-in screens require an account/subscription.
- [x] 2. Trace pairing/identity/avatar and current screen rendering; identify API's ambiguous delivery result, missing sign-in on first launch, transient avatar reset, and the underspecified route timeline.
- [x] 3. Implement immediate pairing feedback, first-run Telegram sign-in, Telegram profile/avatar display, and consistent `./username` identity.
- [x] 4. Refine route cards and home entry→RU→exit diagram, install official Amnezia logo asset, and polish surfaces/typography/motion across core screens.
- [ ] 5. Build/install and run checks; finish visual iteration on the real device after unlocking it and capturing app screenshots.
- [x] 6. Commit/push Android/backend changes, deploy backend through the documented pull/sync/restart procedure, verify health, install APK on TECNO, and publish GitHub prerelease v0.8.4-debug.

## Current State
- Android repository `/home/hackov/Documents/deytt-connect`, branch `main`; preserve existing untracked `.agents/`, `.codebase-memory/`, and completed Roadmap files.
- Backend repository `/home/hackov/Documents/Projects`, branch `main`; preserve existing untracked tool/skill and Roadmap artifacts.
- Connected physical test phone: TECNO CH6i, serial `08357252AA002939`; final APK is installed. The device is currently locked, so the app UI is not visible for screenshot review.
- Final code is version 0.8.4 (versionCode 18); Android unit tests/build pass. GitHub prerelease `v0.8.4-debug` is published with APK SHA-256 `829cd35c6462e19f8f923d672cb6d7440dfd96afae9f582c1d79190e59b6207f`.
- Setup now keeps known Telegram users in-app for code delivery and offers the bot only when the account must start it; the existing authenticated avatar request is retained and username updates no longer clear the photo while it reloads.

## Findings and Decisions
- Telegram username alone is insufficient for a bot to initiate a private chat; direct delivery requires a known bot chat/user id. Unknown users need an in-app explanation and one explicit bot-start action.
- Reuse the existing authenticated avatar endpoint if it can safely return Telegram profile bytes; never expose the bot token or a tokenized Telegram file URL to the client.
- Keep the existing route/profile and tunnel contracts; RU is a visual hop when the selected route is RU→DE.

## Issues and Failed Attempts
- `android layout` stalled while installing its instrumentation helper; stopped it and used direct `uiautomator` successfully.

## Validation and Blockers
- Baseline screenshot: `/tmp/deytt-oldphone-baseline-home.png`. A fresh install has no linked account/subscription, so signed-in screens require a real account and must not be accessed by sending a test code.
- Physical-device visual iteration remains incomplete: the vendor lock-screen prompt was denied and the device stayed locked. No permissions or unlock controls were bypassed.
- Live Telegram messages will not be sent during QA; use mocked backend tests and a synthetic username.
- Android unit tests and debug build pass; backend pairing/avatar tests pass (7/7). Backend commit `d2f33b3` is deployed, `vpn-admin.service` is active, and `/health` returned `{"status":"ok"}`.
- Android commit `8280ff6` is pushed; prerelease asset is uploaded and verified against the SHA-256 above. App version 0.8.4 (code 18) is installed on TECNO.
- `SYSTEM_ALERT_WINDOW` is absent from the merged app manifest. Vendor overlay permission prompt was denied; no sensitive access was granted. Device sleep timeout is 30 minutes; temporary USB-awake setting was restored.

## Next Action and Resume Context
Resume physical-device QA once TECNO is unlocked: capture the setup and pairing screens, inspect accessible screens, compare against the baseline and fix any evidenced visual issues. The APK release and backend deployment are already complete; do not send a real pairing code during QA.
