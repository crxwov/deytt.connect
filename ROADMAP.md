# Roadmap — Mobile remediation 0.8.6

## Objective and status
Deliver the evidenced mobile fixes from the 40-item Excalidraw audit, preserve account/keys, validate on the physical TECNO. Implementation and bounded acceptance completed; delivery pending. Full original acceptance is not claimed: remaining constraints below are explicit.

## Stages
- [x] Diagnose actual API/runtime and phone failures.
- [x] Repair avatar, account sessions, HWID subscription access, diagnostics endpoint, price/support/legal contracts; deploy backend via Git and restart API.
- [x] Complete native home/profile/diagnostic/update flows, bilingual states and lifecycle fixes.
- [x] Unit tests, lint/APK build, physical touch flows and external HTTPS verification.
- [ ] Commit/push Android main, publish verified APK, verify installed/released identity and final device state.

## Verified state
Android 0.8.6 (20) installed without data reset; final APK SHA256 3036bf33633fed3d39a8fc29347938c28dad65aeb7f199bd0232a48f07eb4f39.
65 Android tests pass, lint 0 errors / 66 warnings, APK assembly pass. Backend combined targeted suite42 tests passes; additional HWID/subscription regression suite passed. Backend commits a67cc52/e37b9ac/51d1c10/d4a1a33 pushed and delivered;9/9 latest runtime files match checkout, migration present, zero real HWID blocks, API active. Legal aliases validated nginx syntax and real phone browser pages.
Real avatar shown. All12 country protocol samples give speed+latency. NL AWG3.1 temporary test restores VPNoff; NL AWG1.5 restores active FI VLESS. FI tunnel opens external HTTPS; origin survives browser return; country diagnostic preserves active tunnel. Home5s ping retains results, Connect preempts background check. Language recreation preserves account/photo. Support chat recreation closes old poll/dialog without crash; OS rotation settings restored. Profile traffic, special plan explanation, HWID confirmation, session details and legal links verified.
See docs/mobile-qa-0.8.6.md for metrics/reproduction. Private evidence: /home/hackov/.codex/visualizations/2026/09/26/01a0ddcc-bcd9-7822-b240-2690823c64fa/fix-qa/fixes.md.

## Decisions / limits
No purchases, support sends, real key resets, device/session revocations, or new VPN credentials. Synthetic tests cover destructive paths. HWID block prevents future subscription retrieval; downloaded shared credentials/existing tunnels remain usable. Happ provider APIs support one-link device management but no provider InstallID integration is configured here. No credential migration or bulk rotation.
Unlimited-device grants retain special terms; support renews them rather than inventing a finite tariff price. Update checks every3h foreground; no3h wait/future OTA cycle claimed; Android controls install/restart. AWG requires temporary systemVPN, all individual AWG regions not tested. Trojan neutral icon; official Xray/Hysteria/Amnezia marks. API24–27 signature flags fixed, no old physical device available.

## Findings / failed attempts
Telegram album returns0 photos but getChat supplies current public image; upstream MIME application/octet-stream. Partial-body and MIME assumptions repaired with17tests. Home queue completion previously erased successful latency. Unlimited grant previously returned bad_custom_params. Server support/download initially stale despite checkout. One deploy supplied incorrect pre-pull revision, safely rejected before sync; verified revision rerun succeeded. Country DE test during API restart failed; stable post-restart repeat passed. All unrelated graph artifacts, skills and concurrent backend work preserved.

## Next action / resume
Deliver Android commit+push+prerelease APK, verify GitHub digest and installed version, restore user device preferences. Preserve roadmap as acceptance/delivery record.
