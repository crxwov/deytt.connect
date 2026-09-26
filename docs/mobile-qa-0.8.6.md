# Mobile remediation 0.8.6

## Validation
- Physical TECNO CH6i, preserved linked account and subscription; real touch flows through ADB.
- All 12 VLESS/Trojan/Hysteria outputs measured HTTP latency and bounded download; successful route grading and automatic selection while disconnected.
- NL: 549/524/328 ms, 33.5/35.3/26.0 Mbps; DE: 545/573/335 ms, 42.0/41.0/43.5 Mbps; RU: 480/463/535 ms, 30.8/34.7/30.0 Mbps; FI: 481/624/322 ms, 32.5/28.6/27.4 Mbps. These are one network/session, not service guarantees.
- FI VLESS connected and opened external HTTPS in the phone browser; origin location survived browser return. Home latency refreshed successfully. NL diagnostics during active FI preserved the tunnel.
- NL AWG3.1: 372 ms / 28.2 Mbps, temporary VPN restored previous selection and disconnected state. NL AWG1.5: 428 ms / 28.5 Mbps, restored the previously active FI VLESS tunnel.
- Actual Telegram avatar visible; language recreation preserved identity. Telegram album can be hidden while getChat exposes the current public photo. The backend supports both and recognizes raster bytes independently of upstream MIME.
- Support Activity recreation with the chat open (portrait to landscape) closed the old dialog safely; no AndroidRuntime crash, account remained linked. Original rotation settings restored.
- Profile lifetime traffic, support history/channel selection, individual-plan explanation, Happ block confirmation, session details and real legal pages verified. No real purchase, support message, key reset or device block executed.
- Android unit tests and lint/APK build pass. Lint has warnings; it is not a zero-warning codebase.
- Backend targeted avatar/session/HWID/payment tests pass; source synchronized through Git and affected API service restarted. HWID schema applied; no real devices blocked.

## Explicit limits
- HWID access blocking prevents future subscription downloads for that HWID; exported shared credentials and existing tunnels remain usable. Restore is owner-scoped and enforces device limits. No VPN key migration or bulk rotation.
- Happ provider limited-link APIs can manage one shared subscription. This deployment uses its own HWID registry and has no configured provider InstallID/API integration. Do not call local metadata blocking an immediate tunnel kick.
- Individual unlimited-device grants have no finite catalog price. Renewal directs to support and preserves grant terms; finite plan quotes are endpoint-tested.
- Update checking runs every three hours while the app is foreground. Android controls APK installation and process restart; no fictitious post-install restart is promised. Full elapsed three-hour and future-version OTA cycles were not reproduced.
- AWG requires a temporary Android VPN for a real measurement and cannot be measured as a proxy-only tunnel. The UI explicitly explains the switch and restores previous state.
- Xray and Hysteria use upstream marks. Trojan uses a neutral icon because no verified separate official asset was found.
- Physical API24–27 updater validation is unavailable; signature query flags now select the documented legacy API below28.

## Reproduction
Run `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` with the repository JDK17/SDK/Gradle home. Install the arm64 debug APK with replacement (no data clear). Check avatar, locale roundtrip, country measurements, connection during home ping, browser return, AWG temporary restoration, profile special plan, legal links, support close/reopen and account/session dialogs. Use synthetic backend fixtures for destructive paths.
