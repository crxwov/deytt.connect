# Subscription loading reliability

The onboarding path loads the account, downloads the required sing-box profile, validates its UI route catalog, fetches optional AWG profiles, then commits the profile files and source ownership.

## Failure handling

- Retry read-only requests for transient DNS/socket/HTTP failures, at most three attempts. Never automatically replay Telegram POST requests. Respect bounded Retry-After.
- Keep TLS certificate and hostname validation. Never follow subscription redirects or send the bearer link to an unexpected port.
- Bound account/core/optional stages with monotonic deadlines and disconnect cancellation. Native DNS cancellation remains subject to Android's resolver.
- Validate the complete profile and route catalog before replacing working data. Optional AWG failures can retain same-subscription profiles only.
- Coordinate session changes and subscription commits under one lock. Stale requests cannot clear a newer session or commit after it changes.
- Use atomic file replacement and an undo journal across core/AWG index files. Interrupted writes recover before readers see a generation; damaged recovery evidence is retained and unusable profiles are hidden.
- Persist and show only safe stage/error codes. Never log raw exceptions, subscription links, account details or profile bodies.

## Checks

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

For physical-device checks without changing the installed user's app or VPN:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PisolatedQa=true
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w space.deytt.connect.qa.test/space.deytt.connect.SubscriptionReliabilityInstrumentation
adb uninstall space.deytt.connect.qa.test
adb uninstall space.deytt.connect.qa
```

The device suite uses synthetic profiles and isolated preferences. It checks successful import, rejection without data loss, optional outage, stale session/commit rejection and error-screen recreation. It refuses to run against the production application ID.

Build the distributable APK **without** `-PisolatedQa=true`. Check package name `space.deytt.connect`, version, SHA-256 and signer before release. The QA APK must never be published as an update.
