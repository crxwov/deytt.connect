# Roadmap — deytt./connect Android

## Status

In progress — repair subscription import degradation and finish the Android
product-surface redesign after 0.7 feedback

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
- Connection actions recover from stale persisted state without clearing app data
  or re-importing the subscription.
- The route catalog exposes the first-party RU+DE bypass and selectable regional
  AmneziaWG profiles supplied by the subscription contract.
- Users can measure and compare route latency without starting a full VPN tunnel.
- The primary Android surfaces match the restrained premium visual language of
  deytt.space and contain no platform/build-label clutter.
- A connected tunnel produces a clear Android connection notification for both
  libbox and embedded AmneziaWG engines; the real VPN service remains the
  owner of foreground lifecycle.
- AWG 3.1 imports all advertised regional profiles, exposes a clear server
  picker, and reports a useful failure instead of silently hiding the family.
- Subscription import tolerates transient gateway resets and explains a final
  failure without exposing the token.

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
- [x] 5c. Implement the Android local DNS transport, physical-interface
      snapshot and default-network monitor expected by libbox; split transport
      and DNS startup canaries.
- [~] 6. Run real authenticated Android tunnel plus external HTTPS canary,
      build APK, record checksum, commit, push, and publish artifact; source and
      the debug prerelease are published, while device proof remains pending.
- [~] 7. Audit the complete Android VPN lifecycle against maintained open-source
      clients and repair the data plane instead of changing only canary copy.
- [x] 8. Replace the single-screen MVP with explicit subscription onboarding,
      import progress/success feedback, route catalog and connection dashboard.
- [x] 9. Add stable product metadata for owner, expiry and traffic; present the
      fixed DEYTT route set and embedded AmneziaWG 1.5/3.1 actions without
      leaking arbitrary internal outbound labels.
- [~] 10. Validate all states at phone widths, publish an ARM64 build, and keep
      the task open until a real external HTTPS request passes on the phone.
- [x] 11. Repair the connection state machine, add route/AWG latency and RU+DE
      selection, and replace the 0.5.0 UI with the deytt.space visual system.
- [~] 12. Run focused lifecycle/catalog/latency tests, clean build/lint, visual
      inspection, physical-phone tunnel checks, then commit, push, publish and
      deliver any required backend contract update.
- [~] 13. Repair AWG 3.1 lifecycle/selection and connection notifications; make subscription
      import retry transient 502/stream failures; refresh the primary mobile UI.
- [~] 13a. Keep core subscription import usable when optional AWG endpoints return
      transient gateway errors; preserve last-known-good AWG families and show a
      precise non-blocking warning.
- [~] 13b. Replace the generic stacked-card presentation on setup, home, route,
      protocol, profile, and settings surfaces with a restrained native design
      system and intentional motion/accessibility states.
- [ ] 14. Add account/subscription purchase and bot handoff flows, app update
      checks, split tunneling, geo controls, and a settings surface.
- [ ] 15. Audit all user copy/repository docs, remove implementation leakage,
      add release/download documentation, and complete real-device acceptance.

## Current State

The active work is the Android-only import/UI repair. Optional AWG failures no
longer abort the required sing-box import; the shared native visual primitives
and six primary activities have been refreshed. No backend contract or server
deployment change is part of this stage.

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
Real-device 0.3.1 starts the core but the domain canary still cannot resolve.
The custom PlatformInterface is incomplete: local DNS is `null`, interfaces
are empty, and openTun invents a fallback instead of consuming libbox options
exactly as the official Android client does.
Android 0.3.2 replaces those stubs with a physical-network resolver, interface
snapshot and network monitor, restores typed `local-dns`, and consumes TUN DNS
addresses supplied by libbox without inventing a fallback.
Real-device 0.3.3 proves that auto-pick establishes a working full tunnel and
passes traffic. Manual choices still fail because the MVP derives its picker
from every raw sing-box outbound/endpoint, exposing internal, test and
composition-only tags as if they were supported user routes. The next release
must use an explicit first-party route catalog and keep internal graph nodes
out of the UI.
The 0.4.0 candidate now accepts only `urltest` auto-pick and versioned
`route:<country>` groups as user routes. Raw outbounds and the misleading
standard-WireGuard endpoint stay hidden. Subscription headers populate the
owner/title, accumulated traffic and expiry card. AmneziaWG 1.5 and 3.1 are
shown separately and open the existing versioned first-party import flow.
Phone feedback invalidates the 0.4.0 product solution: NL/RU/DE country groups
do not pass traffic, only Finland and auto-pick work; the catalog contains only
Hysteria; AmneziaWG opens another application; and conditional blocks inside
one Activity are not the requested multi-screen user flow. The next version
must not reuse that information architecture.
Android 0.5.0 replaces it with separate setup, home, route, protocol, and
subscription Activities. Stable VLESS, Trojan, and Hysteria 2 tags are consumed
by libbox; official AmneziaWG Android sources are pinned as a submodule and
compiled into the same APK for AWG 1.5/3.1. Import validates every payload
before replacing the last-known-good profile bundle.

Physical-phone feedback on 0.5.0 reports stale actions that recover only after
clearing app data/re-importing, no visible RU+DE bypass, no latency measurement,
no regional AmneziaWG choice, and excessive platform/build copy. Inspection
shows that `MainActivity.toggleTunnel()` derives control flow from persisted,
localized status text rather than actual engine state; a dead service with a
stale `VPN подключён` value therefore turns every tap into a no-op stop path.

Local JDK 17, Android API 35, build-tools 35.0.0, and Gradle 8.11.1 are
bootstrapped under `.toolchain/` and are not part of the repository artifact.

## Findings and Decisions

- App source lives in `/home/hackov/Documents/deytt-connect`, separate from the
  DEYTTT bot/admin repository.
- Replace the rejected single-surface UI with real Android destinations:
  setup/import, home, country routes, protocol choice, and subscription info.
  The visual system uses an ink background, signal-blue actions, compact type,
  and one route-spine motif instead of stacked rounded cards.
- One app will own two internal engines: pinned libbox for VLESS, Trojan, and
  Hysteria 2, plus the official Apache-2.0 AmneziaWG Android tunnel module for
  AWG 1.5/3.1. Amnezia Box itself only exposes standard WireGuard and therefore
  cannot preserve AWG 3.1 obfuscation parameters.
- The first-party route contract will expose stable tags per country/protocol
  and only advertise configurations actually present for the subscriber.
- The first engine candidate is pinned `libbox` 1.14.1; GPL notices and source
  availability are part of the release contract.
- The first app does not pretend that a successful APK build proves a working
  tunnel. Device permission, VPN service startup, authenticated subscription,
  and external HTTPS must be validated separately.
- The working auto-pick is now the data-plane baseline. Do not modify TUN/DNS
  behavior without a focused regression; repair manual selection at the
  catalog/selector boundary first.
- Raw outbound tags are implementation details, not a product API. The client
  must only present versioned, explicitly selectable DEYTT routes and must not
  infer labels such as `VPN основной` or `Авито прокси` from engine config.
- The server-side sing-box generator now removes deprecated inbound `sniff`
  fields, emits the equivalent route action, and represents AmneziaWG as a
  WireGuard endpoint required by libbox 1.14.1. DNS interception now uses the
  current route `hijack-dns` action and typed DNS servers.
- Manual countries are urltest groups, not direct raw endpoints. This preserves
  failover among profiles inside the chosen country and keeps implementation
  names out of the product contract.
- Treat runtime state as typed data owned by the active engine. Persisted display
  text is restoration copy only and must never decide whether a tap starts or
  stops a tunnel.
- Route latency is an explicit user request and must have bounded concurrency,
  cancellation-safe UI updates, and clear unavailable/error states; a successful
  TCP/HTTPS probe is not tunnel proof.
- The 0.6.0 design direction is an ink/graphite field, warm white typography,
  restrained electric blue, fine network-line structure, and one strong central
  connection object. Remove `connect`, `android`, build, and implementation copy
  from the primary hierarchy.
- The 0.7 design direction keeps one signature device: a quiet star/signal field
  behind a white `./c` mark and a route globe only on the location surface.
  Liquid-metal, particle, CRT, and orbit effects are references, not a license
  to animate every control or copy third-party code without license review.
- Commerce, bot authentication, update delivery, split tunneling, and geo rules
  are separate product capabilities. They must be introduced behind explicit
  contracts and tests rather than coupled to the tunnel toggle.
- The Luna audit found that an asynchronous AWG stop cannot be represented by
  `runtimeRunning` alone. A separate stopping state is now awaited before an
  engine switch; the wait fails closed with a user-visible error rather than
  starting the next engine after a fixed timeout. Redirect following is also
  disabled for bearer subscription URLs.
- Luna's final pass found no remaining compile or engine-switch P0/P1. The
  embedded upstream AWG `GoBackend.VpnService` still owns the actual VPN
  lifecycle, while the app now keeps a separate connected notification for AWG
  and clears it on stop. This is a user-visible notification, not a claim that
  the upstream service itself is a foreground service; verify both behaviors on
  a device before calling the release complete.
- The current import path still treats optional AmneziaWG 1.5/3.1 fetches as a
  hard failure. A 502 from one family therefore surfaces as “сервер подписки
  временно недоступен” even when the required sing-box profile is valid. The
  repair keeps the core import usable, retains the last-known-good AWG family,
  and exposes the degraded state as an explicit warning.

## Issues and Failed Attempts

- The first 0.6.0 Gradle run inherited unsupported system Java 26.0.2.1. All
  successful validation uses the repository-local JDK 17 toolchain.
- The clean validation process was interrupted after tests/packaging, so the
  scoped Gradle validation was rerun to completion instead of trusting partial
  output.
- No ADB device is connected. Visual/device interaction and authenticated
  tunnel proof remain pending and are not inferred from local tests.
- Production confirms AWG 3.1 is enabled with four catalog entries (NL, DE, FI,
  RU); a user token is still required for authenticated payload verification.

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
- `app/src/main/java/space/deytt/connect/RouteModels.kt`
- `app/src/main/java/space/deytt/connect/DeyttUi.kt`
- `app/src/main/java/space/deytt/connect/SetupActivity.kt`
- `app/src/main/java/space/deytt/connect/RoutesActivity.kt`
- `app/src/main/java/space/deytt/connect/ProtocolActivity.kt`
- `app/src/main/java/space/deytt/connect/ProfileActivity.kt`
- `app/src/main/java/space/deytt/connect/AwgTunnelController.kt`
- `app/src/main/java/space/deytt/connect/AwgProfileStore.kt`
- `app/src/main/java/space/deytt/connect/SubscriptionClient.kt`
- `app/src/main/java/space/deytt/connect/VpnRuntimeState.kt`
- `app/src/main/java/space/deytt/connect/RouteLatency.kt`
- `app/src/main/java/space/deytt/connect/ConnectionOrbView.kt`
- `app/src/main/java/space/deytt/connect/SignalBackdropDrawable.kt`
- `app/src/main/java/space/deytt/connect/NotificationStatus.kt`
- `app/src/main/java/space/deytt/connect/RouteGlobeView.kt`
- `app/src/main/java/space/deytt/connect/SettingsActivity.kt`
- `app/src/main/java/space/deytt/connect/SubscriptionErrorText.kt`
- `app/src/main/java/space/deytt/connect/SubscriptionHostPolicy.kt`
- `app/src/main/java/space/deytt/connect/SubscriptionRetryPolicy.kt`
- `app/src/main/java/space/deytt/connect/ReleaseVersion.kt`
- `app/src/main/java/space/deytt/connect/UpdateChecker.kt`
- `awg-tunnel/`
- `third_party/amneziawg-android` (pinned Git submodule)
- `app/src/main/java/space/deytt/connect/SignalDialView.kt` (removed)
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
- Android 0.3.2 clean validation passes `testDebugUnitTest`, `lintDebug`, and
  `assembleDebug`; its v2 signature verifies. SHA-256:
  `79c08179d9ec36c59649418e1f800b8b4a3e2f5cc2e606a8762e39c193b11c29`.
- No ADB device is connected; the local build cannot prove physical-network
  DNS resolution or the external HTTPS canaries on the user's phone.
- Commit `bdd0596` is pushed. GitHub prerelease `v0.3.2-debug` publishes the
  verified APK, and the uploaded asset digest matches the local SHA-256.
- Device installer reports the downloaded package as invalid. A fresh download
  of the release asset passes ZIP integrity, manifest, v2 signature, version,
  and signer-parity checks against 0.3.1; likely remaining cause is an
  incomplete large browser download. Publish smaller standalone ABI APKs.
- ABI splitting produces a standalone ARM64 APK of 31,099,524 bytes instead of
  the 127,030,992-byte universal APK. Its ZIP, manifest and v2 signature pass;
  SHA-256 is
  `51cbbd13717d6d231aac3aa0b99dbcc54a2eb2fb47e55647fbe7fedd2fc3451c`.
- Commit `d327f1e` is pushed. The ARM64 APK is attached to
  `v0.3.2-debug`, and the uploaded asset digest matches the local SHA-256.
- Real-device 0.3.2 reaches the transport canary on the auto-pick route, but the
  UI reports it as unavailable. The IP-based Cloudflare probe incorrectly
  requires HTTP 200 even though any HTTP response proves TLS transport; accept
  all valid HTTP statuses and keep exact 204 only for the DNS canary.
- Android 0.3.3 adds focused status tests, accepts HTTP 100-599 for the
  transport-only probe, preserves exact 204 for the hostname/DNS probe, and
  allows three attempts for cold URLTest startup.
- Android 0.3.3 clean unit/lint validation and a subsequent assemble pass; the
  ARM64 APK archive, manifest and v2 signature verify. SHA-256:
  `aea7be2b8814840ee03a9e3c9c3de5b6574cb3101fba691434bdb5bc0ae5518b`.
- A combined clean test/lint/assemble invocation hit a transient parallel APK
  splitter failure without a cause; clean test/lint followed by assemble passes.
- Commit `b1c549d` is pushed. GitHub prerelease `v0.3.3-debug` publishes the
  standalone ARM64 APK, and its uploaded digest matches the local SHA-256.
- The release creation upload reserved its default asset name without exposing
  the asset; a second explicit-name upload completed successfully and is the
  canonical download for this build.
- Android 0.4.0 unit tests pass 9/9, lint has zero errors (14 warnings), and all
  debug ABI APKs build. ARM64 SHA-256:
  `0a2a7ebffa25e725b89981d28ae59506b4df21818a040a26cd2d291609a62a01`.
- Commit `97d79f6` is pushed to `main`. GitHub prerelease
  `v0.4.0-debug` publishes the standalone ARM64 APK, and GitHub reports the
  same SHA-256 digest as the locally verified artifact.
- Backend commit `4809b927` is pulled on production. The route generator was
  backed up and synchronized into the separate runtime, `vpn-admin.service`
  was restarted, is active, and `/health` returned `{"status":"ok"}`.
- No ADB device is connected; visual safe-area review and real manual-country
  HTTPS proof remain pending on the user's phone.
- Android 0.5.0 passes `testDebugUnitTest`, `lintDebug`, and `assembleDebug`
  (107 tasks). The ARM64 APK passes ZIP integrity and v2 signature checks and
  contains `libbox.so`, `libwg-go.so`, and `libwg-quick.so`. Current SHA-256:
  `c454f3ab3d95310f7a180247a76e2da9b4b55bb05eb52b3fe1573a40cb3dbfe1`.
- Production backend commits through `5859988` are deployed. A serial fleet
  check passed all 12 concrete sing-box VLESS/Trojan/Hysteria routes through a
  local SOCKS client and external HTTPS 204; the independent Happ fleet remains
  14/14. `vpn-admin.service` is active and `/health` is clean.
- Repository-local Git identity is now `crxwov` with the verified GitHub
  no-reply address, so the next commit is attributed to the repository owner.
- Commit `52c21a5` is pushed to `main` and GitHub attributes it to `crxwov`.
  Prerelease `v0.5.0-debug` publishes the standalone ARM64 APK; a fresh release
  download matches SHA-256
  `c454f3ab3d95310f7a180247a76e2da9b4b55bb05eb52b3fe1573a40cb3dbfe1`,
  passes ZIP integrity, and verifies with APK Signature Scheme v2.
- Android 0.6.0 passes 15 unit tests, `lintDebug`, and `assembleDebug` (107
  tasks). The ARM64 APK is 35,889,923 bytes, passes ZIP integrity and v2
  signature verification, and has SHA-256
  `f414982d23784fc9433ce4d115b1dd0714c1852eb3ab4602f5f1b3433fdf8328`.
- Commit `eeb4667` is pushed to `main`. GitHub prerelease `v0.6.0-debug`
  publishes the ARM64 APK; GitHub and a fresh download report the same SHA-256,
  and the downloaded archive passes ZIP integrity verification.
- Android 0.7 first compile exposed two Kotlin issues: the missing
  `TUNNEL_MISSING_CONFIG` branch and a trailing-lambda call with a non-function
  last parameter. Both are fixed. With repository JDK 17 and the local Android
  SDK, `testDebugUnitTest`, `lintDebug`, and `assembleDebug` now pass (107
  tasks). `git diff --check` and manifest XML parsing also pass.
- Android unit report contains 23 tests with zero failures. ARM64 debug APK is
  ZIP-valid, verifies with APK Signature Scheme v2, and has SHA-256
  `1d561d8ba7511eb839533865ed0372db9c3c15d67a9ded3fde58f56c327689ed`.
- Commits `28501da` and `b311ee0` are pushed to `origin/main`. GitHub
  prerelease `v0.7.0-debug` publishes the ARM64 APK with the same SHA-256 and
  explicitly keeps real-device AWG/tunnel proof pending; the downloaded asset
  was rechecked for SHA-256 and ZIP integrity.
- No ADB device or emulator is connected. Real AWG 3.1 traffic, the system
  notification, route latency, deep-link import, and GitHub update flow remain
  unverified on hardware.

## Next Action

Commit and push the validated Android changes. A real device is still required
to verify authenticated import, engine switching, AWG 1.5/3.1 traffic, the
connected notification, route latency, deep-link handoff, and update checking.
Only after that can Stage 13 be marked complete and Stage 14 begin.

## Resume Context

The 0.7.1 debug prerelease is published at GitHub tag `v0.7.1-debug` with the
validated ARM64 APK. Production AWG 3.1 is enabled for NL/DE/FI/RU.
Do not read or print production tokens or secrets; use mocked authenticated
responses for client tests and a real phone for the final tunnel/notification
check. The optional-AWG import repair and coordinated UI pass are now validated
locally: 23 unit tests, `lintDebug`, and `assembleDebug` pass with JDK 17; the
ARM64 APK is ZIP-valid with SHA-256
`d522611f9f7ac15806bd2efe3af97a19952626d8e356a6fdd84108134755743a`.
The first daemon-backed Gradle invocation was interrupted, then the same full
command passed with `--no-daemon`. No Android device or emulator is attached,
so authenticated import, AWG traffic, notification, and visual QA remain open.
The uploaded GitHub asset was downloaded again and matched the same hash and
ZIP integrity.
