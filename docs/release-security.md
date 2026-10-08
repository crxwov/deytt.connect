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

`release/signing-policy.json` records the expected public certificate SHA-256.
The normal v0.8.30 APK keeps the v0.8.18–v0.8.26 signer on Android 8.1 and
lower, and uses the supplied release key (`release.p12`, certificate SHA-256
`bb39b663e555c7ed7b725c543ed73ea2602432b003591b2e1370f0359c4c4333`) on
Android 9 and newer.

Uploaded Android assets must be exactly `deytt-connect-{version}.apk` and its
matching `.apk.sha256` checksum. No ABI suffixes, legacy variants, or unversioned
updater aliases are published. Future builds use the universal release APK.
The existing v0.8.30 canonical APK is ARM64 and remains byte-for-byte unchanged.
Older clients that require `app-{abi}-release.apk` need manual installation;
future updater code prefers the canonical name for the exact release tag.
Historical v0.8.17 installations on Android 8.1 and lower cannot use the normal
APK as an in-place signing upgrade. Do not weaken certificate checks.

Windows assets must be `deytt-connect-{version}.msi` and
`deytt-connect-{version}-portable.zip`, with matching SHA256 sidecars.
Publish Windows only after native acceptance and signing gates pass.
GitHub-generated source archives are managed by GitHub, not uploaded binaries.

Future updates are compatible only while the new private key is preserved and
used for every APK. Private keystores and password files must stay outside the
checkout. Store the release key in a protected location and keep a separate
encrypted backup. Do not commit passwords, keystores, or private signing
configuration. Losing the new key will require another reinstall migration.

## Build and sign

Use JDK 17, Android SDK/build-tools 35.0.0 and the NDK/CMake versions configured
in the project. Set `JAVA_HOME`, `PATH` and `ANDROID_HOME` for that toolchain.

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease \
  --no-daemon --console=plain --max-workers=2

python3 scripts/release.py sign \
  app/build/outputs/apk/release/app-universal-release-unsigned.apk \
  --output output/release/deytt-connect-0.8.30.apk \
  --keystore /secure/signing/release.p12 \
  --password-file /secure/signing/release.password \
  --legacy-keystore /secure/signing/current-release.p12 \
  --legacy-key-alias deytt-connect

python3 scripts/release.py verify output/release/deytt-connect-0.8.30.apk
```

The release script refuses
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

