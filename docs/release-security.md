# Release signing and installation checks

## Distribution contract

Public APKs use the `release` build type with both Java and native debugging
disabled. `assembleRelease` deliberately produces unsigned artifacts; signing is
an explicit, verified step. Never rename a debug build to a release filename.
The updater selects a release APK matching the device ABI, checks its package,
version and signing identity, and asks Android to install it after user action.
Production builds reject debuggable update candidates.

The APK needs `REQUEST_INSTALL_PACKAGES` for that explicit update flow. It does
not request Accessibility, SMS, contacts, device administrator or package-query
access. The VPN services require Android's `BIND_VPN_SERVICE` permission and
foreground notifications. Imported subscription links require a tap before
network requests or replacement of an active configuration.

Only AmneziaWG's userspace engines (`libwg-go.so` and `libdeytt-awg.so`) are
packaged. The unused privileged `wg` / `wg-quick` tools and Java root-shell
backend are excluded; their removal is not evidence they caused antivirus alerts.

## Signing identity and migration

`release/signing-policy.json` records public certificate SHA-256 fingerprints.
`release/signing-lineage.bin` contains the old-key-authorized proof of rotation;
it contains no private key. Android 9+ uses the dedicated release key. Android
7–8 continues to see the original certificate through v1/v2 signatures, allowing
existing installations to update without uninstalling. Both paths are release
builds with debugging disabled. The old certificate has no rollback capability
in the lineage.

The updater in 0.8.12 and earlier compares entire certificate histories and
rejects a rotated update. The first transition therefore requires opening the
new APK manually in Android's installer. Do not uninstall the existing app or
clear its data. Subsequent updates support forward rotation. Devices with a
different debug certificate are a separate migration case: never force an
uninstall or claim their update is compatible.

Private keystores and password files live outside the checkout with permissions
0700 on the directory and 0600 on files. Keep a separate encrypted offline backup
of both the dedicated release key and original compatibility key. Losing either
can prevent updates for part of the install base. Do not commit passwords,
keystores, or a private signing configuration.

## Build and sign

Use JDK 17, Android SDK/build-tools 35.0.0 and the NDK/CMake versions configured
in the project. Set `JAVA_HOME`, `PATH` and `ANDROID_HOME` for that toolchain.

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease \
  --no-daemon --console=plain --max-workers=2

python3 scripts/release.py sign \
  app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk \
  --output output/release/app-arm64-v8a-release.apk \
  --keystore /secure/signing/release.p12 \
  --password-file /secure/signing/release.password \
  --legacy-keystore /secure/signing/legacy.keystore \
  --legacy-password-env DEYTT_LEGACY_PASSWORD

python3 scripts/release.py verify output/release/app-arm64-v8a-release.apk
```

Supply the legacy password through the named environment variable; never put
password values in command arguments or tracked scripts. The script refuses
debuggable APKs, unexpected package identities, SDK levels, permissions, legacy
root tools, missing AWG engines, bad alignment, and mismatching certificates.
It verifies both API 24–27 and API 28+ signatures and writes a SHA-256 sidecar.
`--qa` only permits the isolated `.qa` package; it is not a distribution APK.

Gradle checks the wrapper ZIP against the official distribution SHA-256.
Dependency verification locks the baseline Maven artifact/metadata SHA-256
values; JitPack is restricted to the pinned libbox group. The initial checksum
baseline is a trust-on-first-use snapshot, not an independent source audit.
Review changes to verification metadata during dependency updates. Do not run
`--write-verification-metadata` during ordinary release builds to bypass a
verification error. Go modules retain their checked-in `go.sum` checks.

## Device checks and vendor review

Before publishing, test a signed update over the old installed certificate,
retention of app data, cold launch, import confirmation and both VPN engines.
Use the isolated QA package for corrupted/unrelated-signature fixtures. An ADB
installation validates Android signature/update compatibility; it does not prove
that Play Protect or an OEM's interactive installer accepts the same APK.

Google distinguishes unknown-app scanning, harmful-app classification and
other restrictions. Samsung Auto Blocker may reject an outside-store install
regardless of code quality. Xiaomi warnings require the exact text and scanner
classification. Do not disable these protections or describe signature changes
as a way to bypass them. Collect the APK SHA-256, device/OS, screenshot and exact
detection before submitting a false-positive appeal. Do not upload secrets,
profiles or debug builds to scanners.

Release signing does not mean Google, Xiaomi or Samsung has approved the app.
Store publication, developer verification and vendor appeals require their own
accounts, review and checks. Any external submission must be explicitly authorized.

References: [Android APK signing](https://developer.android.com/tools/apksigner),
[Play Protect guidance](https://developers.google.com/android/play-protect/warning-dev-guidance),
[Samsung Auto Blocker](https://www.samsung.com/us/support/answer/ANS10003636/).
