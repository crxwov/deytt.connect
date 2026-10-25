# Release signing and installation checks

## Distribution contract

Public APKs use the `release` build type with both Java and native debugging
disabled. `assembleRelease` deliberately produces unsigned artifacts; signing is
an explicit, verified step. Never rename a debug build to a release filename.
Each release publishes one universal APK (`app-universal-release.apk`) covering
arm64-v8a, armeabi-v7a and x86_64, plus its checksum and corresponding source.
The app pins the NDK used to strip native debug symbols and filters unsupported
ABIs, keeping the universal download below the updater's 120 MiB limit.
The updater selects that universal APK, checks its package,
version and signing identity, and asks Android to install it after user action.
Production builds reject debuggable update candidates.

The APK needs `REQUEST_INSTALL_PACKAGES` for that explicit update flow. The split
tunnel picker queries only apps with a launcher activity through a manifest
`<queries>` intent; it does not request broad `QUERY_ALL_PACKAGES` access. It
does not request Accessibility, SMS, contacts or device administrator access.
The VPN services require Android's `BIND_VPN_SERVICE` permission and foreground
notifications. Imported subscription links require a tap before network requests
or replacement of an active configuration.

AmneziaWG's userspace engines (`libwg-go.so`, `libdeytt-awg.so`) and the protected
OpenFlux engine (`libopenflux_jni.so`) are packaged. The unused privileged `wg` /
`wg-quick` tools and Java root-shell backend are excluded.

## Signing identity and migration

`release/signing-policy.json` fixes the only distribution signer to certificate
`bb39…` on every supported API (24+). The release helper checks the selected
PKCS#12 certificate with JDK `keytool` before signing, then verifies the APK
signature independently for API 24–27, 28+, and API 36. Historical lineage
files remain archived for reference; they are not signing inputs.

Android 7–8 installs carrying the former `25e335…` or `305741…` signer cannot
update in place to this single `bb39…` signer because those OS versions do not
apply the newer signing rotation history. No automatic migration is provided.

Use the approved private key at `%USERPROFILE%\Downloads\AyuGram Desktop\release.p12`
with alias `deytt-connect`. A protected backup may be used only if it contains
the same approved `bb39…` certificate. Keep its password in a separate file
outside the checkout and pass only the file path to the release helper. Never
commit passwords, keystores, or signing configuration. Future APKs must retain
the same `bb39…` signer.

## Build and sign

Use JDK 17, Android SDK/build-tools 35.0.0 and the NDK/CMake versions configured
in the project. Set `JAVA_HOME`, `PATH` and `ANDROID_HOME` for that toolchain.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease `
  --no-daemon --console=plain --max-workers=2

python scripts/release.py sign `
  app/build/outputs/apk/release/app-release-unsigned.apk `
  --output output/release/app-universal-release.apk `
  --keystore "$env:USERPROFILE\Downloads\AyuGram Desktop\release.p12" `
  --password-file "$env:USERPROFILE\Downloads\AyuGram Desktop\release.password"

python scripts/release.py verify output/release/app-universal-release.apk
```

The release script refuses
debuggable APKs, unexpected package identities, SDK levels, permissions, legacy
root tools, missing AWG/OpenFlux engines, bad alignment, and mismatching certificates.
It requires the selected keystore to contain the fixed signer, verifies API
24–27, 28+, and API 36 signatures, and writes a SHA-256 sidecar.
`--qa` only permits the isolated `.qa` package; it is not a distribution APK.
Do not publish separate ABI packages or duplicate updater/branded filenames.
Retain old files locally until the new single APK has passed signature, size,
device-update and download checks.

The byte-identical v0.11.64 APK is also available from the
[first-party mirror](https://deytt.space/connect/releases/v0.11.64/app-universal-release.apk)
with its [SHA-256 sidecar](https://deytt.space/connect/releases/v0.11.64/app-universal-release.apk.sha256).
This provides a direct download without GitHub redirects; it does not establish
acceptance by an OEM installer.

Windows builds can use `scripts/prepare-awg-windows.py` to prepare the pinned,
patched Go SDK without modifying a shared Go installation. See
[`openflux-tunnel/README.md`](../openflux-tunnel/README.md) for OpenFlux prerequisites.

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

