# Roadmap — deytt./connect Android

## Status

In progress — 0.3.1 direct DNS repair is published; real-device retry remains

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
- [x] 3d. Add selectable subscription routes and fail-closed tunnel startup:
      connected status is published only after an HTTPS canary passes through TUN.
- [ ] 4. Add profile URI parser, fixed DEYTTT mode/country presentation, and
      reconnect/network-change handling.
- [ ] 5. Add split tunnel, DNS leak protection, kill switch, telemetry-free
      diagnostics, and user-facing error recovery.
- [~] 5a. Make DNS bootstrap explicit, close libbox cleanly on every failed
      canary/start, and redesign the main screen around one connection action.
- [~] 5b. Remove `detour: direct` from UDP bootstrap DNS in both fresh and
      already-saved profiles after the 0.3.0 device parser rejection.
- [~] 6. Run real authenticated Android tunnel plus external HTTPS canary,
      build APK, record checksum, commit, push, and publish artifact; source and
      the debug prerelease are published, while device proof remains pending.

## Current State

The public GitHub repository now contains the first Android MVP commit on
`main`. The current server exposes a tokenized
`/sub/token/{token}?format=singbox` contract that returns a sing-box JSON config;
the app intentionally uses that contract instead of embedding endpoint secrets.
The rebuilt 0.2.0 debug APK is published as the explicitly non-production
`v0.2.0-debug` GitHub prerelease.
The published 0.2.1 debug prerelease fixes a real-device report: the previous app
declared success after libbox accepted the configuration, even when no traffic
could pass through the full-route TUN, and it did not expose imported routes.
The published 0.2.2 debug prerelease fixes the next real-device startup blocker without
changing the portable subscription: libbox receives an explicit private absolute
path for its Android cache file at runtime.
Real-device 0.2.2 evidence now shows a DNS-resolution failure on the first
attempt and `initialize cache-file: timeout` on a later attempt. The failure
path does not stop the native service before closing its command server, while
the profile's proxy-hostname resolver depends on DNS detoured through that same
proxy. Both must be fixed together.
The pending 0.3.0 build repairs older saved profiles at runtime, supplies a TUN
DNS fallback, stops libbox before closing its command server on every failure,
and replaces the stacked-card UI with a connection dial, one primary action,
compact route control, and secondary subscription editor.
Real-device 0.3.0 now reaches DNS startup but libbox rejects the migrated UDP
resolver because `detour: direct` targets an empty direct outbound. The UDP
resolver needs no detour; runtime migration must remove the stale field.
The pending 0.3.1 runtime migration now removes that field from both existing
`local-dns` entries and newly inserted bootstrap resolvers.

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
- Real-device report: 0.2.0 showed “VPN connected” with no selectable bypass
  route and broke all internet traffic. Cause: service success was reported
  immediately after `startOrReloadService`, which proves config acceptance but
  not a working transport; full-route TUN had already captured device traffic.
- Real-device report: 0.2.1 stopped at `initialize cache-file: timeout` before
  TUN validation. libbox starts a cache service for its platform log writer, but
  the profile relied on relative `cache.db`; no Android-private cache path was
  supplied at runtime.
- Real-device report: 0.2.2 can start the TUN but cannot resolve the HTTPS
  canary, then leaves libbox's cache service alive because `fail()` skips
  `closeService()`. The next start can therefore time out opening the cache.

## Important Changed Files

- `app/`
- `app/src/main/java/space/deytt/connect/MainActivity.kt`
- `app/src/main/java/space/deytt/connect/ConnectVpnService.kt`
- `app/src/main/java/space/deytt/connect/RuntimeProfile.kt`
- `app/src/main/java/space/deytt/connect/ProfileRoutes.kt`
- `app/src/main/java/space/deytt/connect/SignalDialView.kt`
- `app/src/test/java/space/deytt/connect/ProfileRoutesTest.kt`
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
- GitHub prerelease `v0.2.0-debug` publishes the verified APK with SHA-256
  `cbceefa6163cad720a7bc6aa55bb672d676f62606ab1055bccb3462209d3dd8a` and
  clearly labels it as a debug build without device tunnel proof.
- `adb devices` found no connected Android device or emulator; tunnel and
  external HTTPS canary are not yet verified.
- Android 0.2.1: `testDebugUnitTest`, `lintDebug`, and clean `assembleDebug`
  pass. The profile route test proves that choosing a route updates both
  `route.final` and remote DNS detours while preserving local DNS direct.
  SHA-256: `5e1be10ef43b50de88dd755de423dfcf5aca112f9ae09b62f5c9fc13168cb481`.
- GitHub prerelease `v0.2.1-debug` publishes the fixed APK. It remains a debug
  build until the real-device external HTTPS confirmation succeeds.
- Android 0.2.2: runtime-profile test, route tests, lint, and clean build pass.
  The cache-file test proves a private absolute path and no persistent mutation
  of routing fields. SHA-256:
  `cd24de955f2d287bf3d4de6369a170f1268105f94724da60d0363e42f4a35983`.
- GitHub prerelease `v0.2.2-debug` publishes the cache-file startup fix; only
  real-device tunnel proof remains pending.
- Android 0.3.0 clean validation: `testDebugUnitTest`, `lintDebug`, and
  `assembleDebug` pass; the v2 debug signature verifies. Runtime-profile
  regressions cover both migration of the old local resolver and insertion when
  it is absent. APK SHA-256:
  `a3735ff77eebea383bbbcb79863194ab1a3d9f5aa6c4c7049001825692613fe5`.
  No ADB device is connected, so DNS, cache cleanup, safe areas, and final
  visual judgment still require the user's phone.
- Commit `7c0c33f` is pushed to `origin/main`. GitHub prerelease
  `v0.3.0-debug` publishes `app-debug.apk`; the release asset digest matches
  the locally verified SHA-256.
- Android 0.3.1 clean validation passes `testDebugUnitTest`, `lintDebug`, and
  `assembleDebug`; its v2 signature verifies. SHA-256:
  `7a649dad7ea82646d849d37ea01bfbf51d0212dbc3189647ab0a9077332aa73f`.
- Commit `256287b` is pushed. GitHub prerelease `v0.3.1-debug` publishes the
  replacement APK, and its uploaded asset digest matches the local SHA-256.

## Next Action

Install `v0.3.1-debug` over 0.3.0 and retry connection. Existing saved profiles
are repaired at runtime, so re-import is optional for this parser fix.

## Resume Context

Continue in this repository, preserve the separate upstream boundary, and do
not add tokens or `.env` values to fixtures. Treat the APK as unverified until
an authenticated Android tunnel passes an external HTTPS canary.
