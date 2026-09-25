# Roadmap — Telegram pairing and map activity release

## Objective and Success Criteria
Deliver the current Android UI and the pairing/map fixes as a versioned GitHub debug prerelease. Pair requests must not launch Telegram automatically; known Telegram chats receive the code directly; the explicit `/start` button remains a fallback. Traffic particles must follow recent device-wide RX/TX while the Android VPN transport is active and survive map gestures.

## Stages
- [x] 1. Trace pairing, service sampling, and map animation; confirm username submission was opening the bot and UID-only sampling used a 1.4 s visibility window.
- [x] 2. Remove automatic Telegram launch and retain an explicit manual `/start` action with clear code instructions.
- [x] 3. Sample device RX/TX only while the Android VPN transport is active, extend the visibility grace to 3.5 s, and add deterministic activity-window tests.
- [x] 4. Build, lint, and run Android unit tests: `BUILD SUCCESSFUL`; 53 tests, 0 failures/errors.
- [x] 5. Physical A001 QA on v0.8.2-debug: reconnected the stored RU→DE route, confirmed VPN transport, rotated map without switching tabs, and observed moving particles. Pairing with synthetic `@codexqa260926` stayed in-app and exposed only the manual bot button; no live code was sent. Four focused map swipes: 628 frames, 3 janky frames (0.48%), p95 25 ms.
- [x] 6. Build v0.8.3-debug (versionCode 17), install on A001, verify `ВЕРСИЯ 0.8.3`, reconnect the stored RU→DE route, pan the map with particles visible, and verify APK v2 signature. ARM64 APK SHA-256: `0f30c18e53095cbccd56428144cb5b40e221ced20992d1f05dc76cdf8b8902b3`.
- [x] 7. Commit and push Android (`a911cf0`) and backend (`88f2668`) changes; fast-forward production checkout to `88f2668`, sync the single changed API Python file, restart `vpn-admin.service`, and verify it is active with `/health` returning `{"status":"ok"}`.
- [x] 8. Publish the verified ARM64 APK as GitHub prerelease `v0.8.3-debug`; uploaded asset SHA-256 matches `0f30c18e53095cbccd56428144cb5b40e221ced20992d1f05dc76cdf8b8902b3`.

## Current State
- Android repo: `/home/hackov/Documents/deytt-connect`, `main`; the working tree contains the task edits plus unrelated existing `.agents/`, `.codebase-memory/`, and completed Roadmap artifacts. Stage only named task files.
- Backend repo: `/home/hackov/Documents/Projects`; use its active Roadmap and scoped `--mobile-pair-after-pull` deploy procedure.
- A001 serial `0022935AM001077` remains connected by ADB. Do not clear app data or test with a real Telegram username/code.
- GitHub prerelease: https://github.com/crxwov/deytt.connect/releases/tag/v0.8.3-debug, asset `app-arm64-v8a-debug.apk` (37,360,782 bytes); published digest matches the locally built APK.
- Production repository `/home/twin/deytttvpn` is at backend commit `88f2668`; one API file was synced with a scoped backup, and `vpn-admin.service` restarted healthy.

## Findings and Decisions
- Bot API delivery requires a previously known unique numeric chat ID; usernames only route requests. Failed/ambiguous delivery leaves the challenge available for the user's explicit `/start` fallback.
- `TrafficStats.getUidRxBytes(Process.myUid())` misses other apps routed through Android's VPN; use total counters only while the VPN transport is active.
- The release remains explicitly a debug prerelease, even though the tunnel was verified on the physical phone.

## Issues and Failed Attempts
- Package reinstall stopped the non-sticky VPN service; the stored RU→DE profile reconnected successfully without clearing data.
- Initial guess for the production Git checkout path was wrong; the deploy script identified `/home/twin/deytttvpn`. No server state was changed before using the correct path.
- `test-android-apps` is installed as a vendored QA skill but exposes no dedicated callable MCP tool in this session; its adb UI-tree/screenshot procedure and performance `gfxinfo` workflow were used on the physical phone.

## Validation and Blockers
- Android v0.8.3: `testDebugUnitTest`, `lintDebug`, and `assembleDebug` passed; 53 tests, 0 failures/errors. ARM64 APK versionCode 17/versionName 0.8.3; v2 signature verified.
- Physical A001 (`0022935AM001077`): final APK installed; RU→DE tunnel reconnected and `VPN CONNECTED` confirmed; pan gesture left Home selected and traffic dots visible.
- Backend `tests/test_mobile_pair.py`: 5 passed. Production `/health` returned `{"status":"ok"}` after API service restart; GitHub release asset digest verified.

## Next Action and Resume Context
All implementation, physical-device validation, GitHub publication, and API deployment criteria are complete. Final Roadmap sync commit is the only remaining administrative step.
