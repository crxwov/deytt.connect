# Roadmap — deytt./connect Android

## Status

In progress — Android MVP, subscription compatibility and visual refresh

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
      fields and ship the first DEYTTT visual refresh; backend and APK checks
      pass, while device tunnel proof remains separate.
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
  fields and emits the equivalent route action required by libbox 1.14.1.

## Issues and Failed Attempts

- Device-level VPN validation is not available until an Android device or
  emulator is connected.
- The first Android import exposed a server-side `format=singbox` 404: the
  `vpn-admin` service user could not read the public Hysteria certificate.
  Runtime permissions were narrowed to certificate read/traverse only and
  `vpn-admin.service` was restarted; the retry then exposed a second issue:
  generated inbounds still used the removed legacy `sniff` field. That server
  contract is now migrated and deployed.

## Important Changed Files

- `app/`
- `app/src/main/java/space/deytt/connect/MainActivity.kt`
- `app/src/main/java/space/deytt/connect/NetworkBackdropView.kt`
- `README.md`
- `THIRD-PARTY-NOTICES.md`
- `DIFFERENCES.md`
- `ROADMAP.md`

## Validation and Blockers

- Upstream libbox API and SFA Android integration were inspected.
- Server subscription route and `format=singbox` behavior were inspected in the
  existing DEYTTT backend.
- `./gradlew assembleDebug` passed on 2026-09-22.
- `./gradlew lintDebug` passed on 2026-09-22 with no lint errors.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- SHA-256: `a7fa99663472f13cc7808cf4edd64d95a46dfbcdd708187f1b0c298732e1850b`.
- Commit `7574b0b` was pushed to `origin/main` successfully.
- `vpn-admin/tests/test_protocols.py`: 33 passed after the sing-box migration.
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
- `adb devices` found no connected Android device or emulator; tunnel and
  external HTTPS canary are not yet verified.

## Next Action

Install the new debug APK on the Android phone, import the already working
subscription, and capture the resulting status/error. If it reaches connected,
verify an external HTTPS canary; if not, use the surfaced error for the next
adapter fix. Do not treat the live JSON smoke-test or local APK build as a
substitute for device tunnel proof.

## Resume Context

Continue in this repository, preserve the separate upstream boundary, and do
not add tokens or `.env` values to fixtures. Treat the APK as unverified until
an authenticated Android tunnel passes an external HTTPS canary.
