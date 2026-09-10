# Virkey 0.2.0 test-build validation

Built on Windows on 2026-09-10 using the project-local JDK and Android SDK after the user authorized Google's SDK license acceptance.

## Completed

- `scripts/build.ps1`: `assembleDebug`, `testDebugUnitTest`, and `lintDebug` succeeded.
- 71 automated tests passed with no failures: 34 HID/key-layout/media tests, 19 trackpad-gesture tests, six mouse-button ownership tests, and 12 Compose UI tests.
- UI checks ran with Robolectric at Android API 35 in 1280 × 800 dp and 960 × 600 dp landscape configurations. They verify key/media press/release, disabled disconnected input, pairing availability before HID registration, deck switching, long-press timer/drag/cancellation, pause/resume safety, trackpad placement, and the watermark/removed side text.
- Both actual Compose decks were rendered through Android's native Canvas and visually inspected. These previews use a simulated connected state, not a live PC connection.
- Android lint: zero errors and 12 warnings. Warnings concern available dependency upgrades, Android 16's treatment of orientation requests, backup configuration guidance, and Kotlin style; no missing-permission or API-compatibility errors were reported.
- APK signature verified with Android `apksigner` (v2 signing, one signer).
- Packaged APK inspected: application `com.virkey.app`, version `0.2.0` (version code 2), minimum API 28, target API 36. It requests Bluetooth/session/notification permissions and no Internet, location, or accessibility-service permissions.

## Still requires physical-device testing

No Android device was connected through ADB during this build. The target tablet's Bluetooth firmware, actual Windows 11 HID enumeration, pairing/passkey behavior, reconnecting after sleep, input latency, and held-input release on a real radio disconnect have not been verified.

The new consumer-control HID collection changes the device descriptor. Existing Windows pairings may need to be removed and paired again for media controls to appear. Real PC tests must cover Num Lock feedback, all media controls, text selection, and moving away from the app during a pending long press.

Use the device acceptance checklist in [README.md](../README.md) for the first tablet/PC session. Do not interpret a passing host test or a simulated connected-state screenshot as proof of working Bluetooth hardware.

## Outputs

- `app/build/outputs/apk/debug/app-debug.apk`: signed development APK for tablet testing.
- `.tools/release-artifacts/virkey-0.2.0-debug.apk`: named local copy; not published to GitHub by this update.
- `app/build/outputs/previews/virkey-tablet.png`: cleaned laptop keyboard preview.
- `app/build/outputs/previews/virkey-numpad-media.png`: number pad and media deck preview.
- `app/build/reports/tests/testDebugUnitTest/index.html`: test report.
- `app/build/reports/lint-results-debug.html`: full Android lint report.

The build helper also normalizes stray quotes in PATH entries for its child processes, avoiding a host-specific Java test-worker launch failure. It restores the original environment when finished and does not modify the Windows PATH setting.
