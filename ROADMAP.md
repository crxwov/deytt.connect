# Roadmap — Publish deytt.connect 0.8.7

## Objective and success criteria
Publish Android app version 0.8.7 as a GitHub release for `crxwov/deytt.connect`.
Success: use a suitable installable APK built from pushed `main`, validate its package/version/signature/hash, create tag/release, attach the APK, and verify the public release asset. Preserve unrelated working-tree changes.

## Stages
- [x] Inspect release/tag history, repository guidance, and Android signing/build configuration.
- [x] Build and validate the installable debug pre-release APK; confirm signing identity matches the previous published version.
- [x] Create the 0.8.7 GitHub release and attach the validated APK; verify tag and downloadable asset.
- [x] Record final release URL, artifact hash, validation, and status here.

## Current state
- Source implementation is pushed to `main` at `d5b2d39`.
- GitHub pre-release `v0.8.7-debug` is published at https://github.com/crxwov/deytt.connect/releases/tag/v0.8.7-debug and targets source commit `d5b2d396d5754b6290d9e3d32d6bebb504a96f1f`.
- Asset `app-arm64-v8a-debug.apk` is uploaded and was downloaded back from GitHub; its SHA-256 matches the local artifact.

## Findings and decisions
- Existing project convention is GitHub pre-release `vX.Y.Z-debug` with `app-arm64-v8a-debug.apk`; latest is `v0.8.6-debug`. No `v0.8.7-debug` tag/release exists.
- Validated artifact: `app-arm64-v8a-debug.apk`, package `space.deytt.connect`, versionName `0.8.7`, versionCode `21`, ABI `arm64-v8a`; APK signature verifies and matches the last release certificate.
- SHA-256: `2d500dce96dcb07269db6530db8ef5b485c23eec66589d586342053496eb2db1`.
- The project publishes debug builds as prereleases. This artifact is signed with the same certificate as `v0.8.6-debug`, preserving update compatibility; it is not a Play Store production-signed build.

## Issues and validation
- `aapt dump badging` and `apksigner verify` passed; package/version/ABI match 0.8.7 / 21 / arm64-v8a.
- GitHub release API reports the asset as uploaded. Downloaded release asset hash equals the local APK hash. Remote tag resolves to the intended `main` source commit.

## Next action and resume context
Completed; release link and verified APK are available above.
