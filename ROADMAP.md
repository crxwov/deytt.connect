# Roadmap — Excalidraw mobile app updates

## Objective and Success Criteria
Implement the actionable feedback in the 2026-09-26 Excalidraw note in the Android client. Simplify and reorganize the route, home, profile, and settings journeys while preserving tunnel/subscription contracts and requiring explicit user action for account, key, and update operations.

- Group every available exit, including AmneziaWG profiles, under its country. Countries start collapsed; opening one country is the only trigger for measuring its candidates.
- Measure sing-box candidates with proxy-routed HTTP GET/HEAD latency and a five-second download-speed sample. Bypass this app's active VPN with socket-level protection where supported; clearly report that another VPN cannot be bypassed. Show averages and a relative grade, and select the best combined latency/download result. Keep AmneziaWG diagnostics labeled as endpoint-only until an actual AWG measurement tunnel exists; never use ICMP.
- Refresh the selected-route home latency every 10 seconds, show the Telegram avatar when available, and remove redundant diagnostics from the home flow.
- Remove AmneziaWG profile inventory from Profile. Provide app-native account, device/session, key-reset, plan/payment, support, and legal entry points where existing authenticated APIs safely support them. Keep Telegram proxy configuration out of the app.
- Check official app releases every three hours while the app is in use and present update state and a user-controlled install/restart flow.
- Preserve RU/EN localization, accessible state feedback, existing route/tunnel semantics, and user-owned repository files. Build and run targeted checks; install on the connected phone and inspect changed screens where real account data permits.

## Stages
- [x] 1. Read the Excalidraw comments and four reference screenshots; inspect the current client architecture and capture the clean-device baseline.
- [x] 2. Audit existing route catalog, proxy probe, public speed-test implementation, authenticated account APIs, update distribution, and current uncommitted state. Record any missing server capability before choosing a safe client behavior.
- [x] 3. Restructure Routes into collapsed country sections with all available protocols and original marks; remove eager and duplicate per-protocol measurement UI.
- [x] 4. Implement on-demand per-country latency/download sampling, scoring, classification, progress, cancellation, and localized display without routing measurements through the user's active VPN.
- [x] 5. Refine Home and Profile/Settings: Telegram avatar, 10-second selected-route latency, remove profile AWG inventory, and build supported in-app account/device/session/key/plan/support/legal flows.
- [x] 6. Add periodic official-release checking and a secure user-consented update flow compatible with the project's sideload distribution.
- [ ] 7. Review the scoped diffs, commit and push the client and backend, pull the server checkout, restart affected services, and verify service health.

## Current State
- Android repository: `/home/hackov/Documents/deytt-connect`, branch `main`. Preserve unrelated `.agents/`, `.codebase-memory/`, `.kotlin/`, and `ROADMAP.connect-ui-refinement.completed.md` files.
- Baseline screenshot: `/tmp/deytt-connect-20260926-baseline-app.png` on TECNO CH6i. Device has no linked app account/subscription; do not sign in, reset keys, start a VPN, or send support messages.
- Excalidraw Markdown and four attached reference screenshots are in `/home/hackov/1/Excalidraw` and `/home/hackov/1/Pasted Image 2026092614*.png`.
- Implemented collapsed country route groups with on-expand HTTP probes, averaged latency and bounded five-second download samples, relative scoring, AWG endpoint-only listing with unknown marks unassigned, 10-second selected-route refresh, approximate exit-region display, in-app account/payment/support flows, and official release updates.
- Removed the manual subscription URL form from Setup. Telegram pairing remains; validated `deytt.connect://import` links go directly through import. Existing linked sessions can retry fetching their subscription. Physical-device inspection caught an English/Russian mismatch; setup-screen copy is now covered by localization tests.
- Backend API work is in `/home/hackov/Documents/Projects` and tracked by `ROADMAP.android-mobile-api.paused.md`: bounded authenticated download and owner-scoped support history/reply/close. Focused backend tests passed earlier; backend commit is pushed and deployment is blocked pending the actual server checkout path.
- Client commit `a1e2289` and backend commit `e46bcee` are pushed to their respective `main` branches. Server preflight at `/home/twin/vpn-admin` failed because that path is not recognized as a Git repository on `nl-vpn`; no pull or service restart was attempted.
- Codebase-memory confirms native Android Views/ViewPager2. The probe service uses an isolated process, refuses another app's VPN, binds libbox sockets to the underlying physical network, and rejects accidental TUN creation.

## Findings and Decisions
- The latest and most specific measurement instruction is on-demand per expanded country; do not scan at page entry. Home latency may refresh separately every 10 seconds.
- Candidate quality should use proxy GET/HEAD latency plus download throughput. Use normalized within-country ranks with equal latency/speed weight unless source speed-test code establishes a better formula; handle small candidate sets without implying unavailable grades.
- AWG country assignment comes only from an NL/DE/RU/FI prefix in `shortLabel`; unknown marks remain unassigned and endpoint-only. Do not compare raw TCP with sing-box proxy scores.
- Bypass this app's VPN by binding each libbox socket to a physical `Network`; reject checks behind another app's VPN. Avoid process-wide network binding.
- Device control, key reset, payment, and support must remain behind the existing Telegram-linked identity and explicit confirmation. Never fabricate account/session data or expose credentials.
- Do not promise session revocation: no owner-scoped revoke API exists. Support history/reply/close and bounded download were added in the backend task.
- Keep Telegram pairing and authorized deep-link import; do not offer manual subscription URL entry.
- The APK itself has no server service. Backend changes require project deploy steps and restarts of `vpn-admin.service` and `uebot.service`.

## Issues and Failed Attempts
- The literal path ending in .excalidraw did not exist; the user drawing is the matching .excalidraw.md file with four embedded screenshot attachments.
- The device is authenticated only at Android level; app account state is empty. Do not attempt sign-in as the user or send test support messages.
- The first build used system Java 27 and failed Gradle's JVM check; use the repository JDK 17.
- `ProfileRoutesTest` had legacy label-based country expectations; its fixture now checks authoritative short-label prefixes and unknown-region placement.
- `AppLanguageTest` exposed an untranslated nested reset label. The translator was corrected; rerun the full suite after the setup-screen changes.

## Validation and Blockers
- Baseline screenshot captured and visually inspected. Updated APK installed and launched on TECNO CH6i; corrected English setup screen visually verified at `/tmp/deytt-connect-20260926-setup-fixed.png` without signing in. Full unit tests, lint, APK assembly, and `git diff --check` passed after the final setup-copy localization change.
- Device account is unlinked, so authenticated route, profile, support, payment, and tunnel journeys cannot be exercised here. No account was linked and VPN stayed off. Physical-device verification covers install/launch/setup presentation only.
- Delivery blocker: server checkout path must be confirmed before continuing. Production backend pull and restarts of `vpn-admin.service` and `uebot.service` are not verified.

## Next Action and Resume Context
Resume only after confirming the Git checkout path on `nl-vpn`; then pull the already-pushed backend commit, restart `vpn-admin.service` and `uebot.service`, verify health, and record delivery evidence.
