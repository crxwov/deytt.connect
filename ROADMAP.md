# Roadmap — deytt./connect Android

## Status

In progress — Android MVP, sing-box compatibility hardening and product polish

## Objective

Build an open-source Android client for DEYTTT with a native `VpnService`, a
shared mature networking core, versioned subscription handling, safe rollback,
and a real APK artifact.

## Success Criteria

- Public repository contains no credentials, server secrets, or generated
  signing keys.
- HTTPS DEYTTT `format=singbox` subscription imports and validates atomically.
- Android `VpnService` starts the pinned libbox engine with the saved config.
- Debug APK builds reproducibly from the repository and its checksum is recorded.
- Real device validation is reported separately from local compilation.
- The app presents the DEYTTT visual language with clear connected, starting, and
  error states without exposing subscription tokens in the primary status UI.

## Stages

- [x] 1. Create a separate public repository and document GPL/core boundaries.
- [x] 2. Implement Android project, subscription storage, and profile validator.
- [~] 3. Integrate libbox with Android TUN and foreground-service lifecycle;
      local compilation and in-app status/error reporting are complete, device
      runtime proof is pending.
- [x] 3a. Migrate the server sing-box subscription away from deprecated inbound
      and WireGuard outbound fields; ship a minimal DEYTTT visual refresh.
- [x] 3b. Remove the remaining deprecated TUN address schema, add client-side
      compatibility preflight, and deploy the server contract fix.
- [x] 3c. Harden import/service state, token presentation, error recovery, and
      the Android visual hierarchy without adding decorative UI noise.
- [ ] 4. Add profile URI parser, fixed DEYTTT mode/country presentation, and
      reconnect/network-change handling.
- [ ] 5. Add split tunnel, DNS leak protection, kill switch, telemetry-free
      diagnostics, and user-facing error recovery.
- [~] 6. Run real authenticated Android tunnel plus external HTTPS canary,
      build APK, record checksum, commit, push, and publish artifact; source is
      pushed, while device proof remains pending.

## Current State

The public GitHub repository now contains the first Android MVP commit on
`main`. The current server exposes a tokenized
`/sub/token/{token}?format=singbox` contract that returns a sing-box JSON config;
the app intentionally uses that contract instead of embedding endpoint secrets.

Local JDK 17, Android API 35, build-tools 35.0.0, and Gradle 8.11.1 are
bootstrapped under `.toolchain/` and are not part of the repository artifact.

## Findings and Decisions

- App source lives in `/home/hackov/Documents/deytt-connect`, separate from the
  DEYTTT bot/admin repository.
- The first engine candidate is pinned `libbox` 1.14.1; GPL notices and source
  availability are part of the release contract.
- The first app does not pretend that a successful APK build proves a working
  tunnel. Device permission, VPN service startup, authenticated subscription,
  and external HTTPS must be validated separately.
- The server-side sing-box generator now removes deprecated inbound `sniff`
  fields, emits the equivalent route action, and represents AmneziaWG as a
  WireGuard endpoint required by libbox 1.14.1. DNS interception now uses the
  current route `hijack-dns` action and typed DNS servers.

## Issues and Failed Attempts

- Device-level VPN validation is not available until an Android device or
  emulator is connected.
- The first Android import exposed a server-side `format=singbox` 404: the
  `vpn-admin` service user could not read the public Hysteria certificate.
  Runtime permissions were narrowed to certificate read/traverse only and
  `vpn-admin.service` was restarted; the retry then exposed a second issue:
  generated inbounds still used the removed legacy `sniff` field. That server
  contract is now migrated and deployed.
- The next device attempt reached the following parser boundary and exposed the
  deprecated WireGuard outbound schema. The generator now emits the current
  root-level `endpoints` schema and has a focused regression test.
- The next device attempt reached Hysteria2 TLS parsing and exposed the legacy
  `tls.sni` field. Sing-box 1.14.1 expects `tls.server_name`; the generator
  change is covered by the existing sing-box config test.
- The next device attempt exposed the removed DNS outbound. The backend now
  emits typed HTTPS/local DNS servers and route-level `hijack-dns`; live format
  validation is clean after deployment.
- The next device attempt exposed the remaining legacy TUN address fields:
  `inet4_address` must be migrated to the current `address` field. The client
  also needs to reject stale configs before libbox sees them.
- The backend now emits `address`, and the client validates TUN/DNS/TLS/WireGuard
  compatibility before saving or starting a profile. Stale profiles now produce
  a re-import action instead of a raw libbox parser failure.
- The first visual refresh was intentionally too busy. The next UI removes the
  network map, technical labels, build badge, intro block, and verbose footer;
  the status card remains as the single place for useful runtime errors.

## Important Changed Files

- `app/`
- `app/src/main/java/space/deytt/connect/MainActivity.kt`
- `app/src/main/java/space/deytt/connect/NetworkBackdropView.kt` (removed)
- `README.md`
- `THIRD-PARTY-NOTICES.md`
- `DIFFERENCES.md`
- `ROADMAP.md`

## Validation and Blockers

- Upstream libbox API and SFA Android integration were inspected.
- Server subscription route and `format=singbox` behavior were inspected in the
  existing DEYTTT backend.
- `./gradlew assembleDebug` passed on 2026-09-22 after the endpoint/UI fix.
- `./gradlew lintDebug` passed on 2026-09-22 with no lint errors.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- APK 0.2.0: `app/build/outputs/apk/debug/app-debug.apk`.
- SHA-256: `cbceefa6163cad720a7bc6aa55bb672d676f62606ab1055bccb3462209d3dd8a`.
- Commit `7574b0b` was pushed to `origin/main` successfully.
- Latest visual refresh commit `9d364d0` was pushed to `origin/main` successfully.
- `vpn-admin/tests/test_protocols.py`: 34 passed after the WireGuard endpoint
  migration.
- Live server contract after deploy commit `872ecb52`: JSON valid, two inbounds,
  no legacy inbound sniff fields, first route action `sniff`, 18 outbounds.
- Android build and lint pass after the visual refresh; lint reports warnings
  only for existing Android deprecations and no errors.
- The `command.sock` read-only filesystem failure was traced to missing
  `Libbox.setup` path initialization; the new build sets writable `basePath`,
  `workingPath`, and `tempPath` before creating `CommandServer`.
- The new build broadcasts foreground-service startup, connected, stopped, and
  native/TUN errors back to the visible activity instead of leaving a permanent
  `Запускаю VPN…` status.
- On `nl-vpn`, `sudo -u nl openssl x509` now validates `server.crt`; the same
  user still cannot read `auth_pass` or `server.key`, and `vpn-admin.service`
  is active after restart.
- The live config error `inbounds[1]: legacy inbound fields are deprecated` was
  fixed by moving sniffing into route rules. The deployed live smoke-test now
  returns two inbounds, no inbound sniff fields, and a first `sniff` action.
- The live config error `outbounds[1].server: unknown field` was fixed by
  moving AmneziaWG to the endpoint schema. The deployed live smoke-test now
  returns no legacy WireGuard outbounds and one endpoint without server fields.
- The device error `outbounds[1].tls.sni: unknown field` was fixed by switching
  Hysteria2 and zapret TLS objects to `server_name`; the server change is
  deployed and the live smoke-test is clean.
- The second visual pass removes the animated network map and technical copy;
  the APK build and lint pass after the simplification.
- Current screen still exposes the full subscription token, allows connect
  while a profile is stale or importing, and loses the last service state when
  the activity is recreated; this pass masks the token, gates actions, persists
  service state, and uses a dedicated VPN notification icon.
- Live after the TLS migration: HTTP 200, valid JSON, 18 outbounds, zero
  legacy `tls.sni` outbounds, 13 `tls.server_name` outbounds, one WireGuard
  endpoint, no inbound sniff fields.
- Live after the DNS migration: HTTP 200, valid JSON, two inbounds, 17
  outbounds, no DNS outbound or legacy DNS fields, two DNS route rules, and a
  route-level `hijack-dns` rule.
- Live after the TUN migration: HTTP 200, valid JSON, `tun.address` contains
  `172.19.0.1/30`, no legacy TUN/DNS/TLS/WireGuard fields, one WireGuard
  endpoint, and `vpn-admin.service` is active after restart.
- Android 0.2.0 clean validation: `assembleDebug` and `lintDebug` pass with
  repository JDK 17 and Android SDK 35; only existing API deprecations and the
  expected unstrippable `libbox.so` warning remain.
- Live after the TUN migration: HTTP 200, valid JSON, `tun.address` contains
  `172.19.0.1/30`, no legacy TUN/DNS/TLS/WireGuard fields, one WireGuard
  endpoint, and `vpn-admin.service` is active after restart.
- Final Android validation: clean `assembleDebug` and `lintDebug` on the
  repository JDK 17/Android SDK; only existing API deprecation warnings and the
  expected unstrippable `libbox.so` packaging warning remain.
- `adb devices` found no connected Android device or emulator; tunnel and
  external HTTPS canary are not yet verified.

## Next Action

Install APK 0.2.0 on a real Android device, re-import the subscription, and
prove the tunnel with an external HTTPS canary. Device proof remains the only
unfinished part of this MVP; local build and live JSON validation are complete.

## Resume Context

Continue in this repository, preserve the separate upstream boundary, and do
not add tokens or `.env` values to fixtures. Treat the APK as unverified until
an authenticated Android tunnel passes an external HTTPS canary.
