# Virkey 0.1.0 test-build validation

Built on Windows on 2026-09-09 using the project-local JDK and Android SDK after the user authorized Google's SDK license acceptance.

## Completed

- `scripts/build.ps1`: `assembleDebug`, `testDebugUnitTest`, and `lintDebug` succeeded.
- 35 automated tests passed with no failures: 24 HID/key-layout tests, seven trackpad-gesture tests, and four Compose UI tests.
- UI checks ran with Robolectric at Android API 35 and a 1280 × 800 dp landscape configuration. They verify physical key press/release, disabled disconnected input, pairing availability before HID registration, and the keyboard positioned above the trackpad.
- The actual Compose view was rendered through Android's native Canvas and visually inspected. The preview uses a simulated connected state, not a live PC connection.
- Android lint: zero errors, 12 warnings, one hint. Warnings concern available dependency upgrades, Android 16's treatment of orientation requests, backup configuration guidance, and Kotlin style; no missing-permission or API-compatibility errors were reported.
- APK signature verified with Android `apksigner` (v2 signing, one signer).
- Packaged APK inspected: application `com.virkey.app`, version `0.1.0`, minimum API 28, target API 36. It requests Bluetooth/session/notification permissions and no Internet, location, or accessibility-service permissions.

## Still requires physical-device testing

No Android device was connected through ADB during this build. The target tablet's Bluetooth firmware, actual Windows 11 HID enumeration, pairing/passkey behavior, reconnecting after sleep, input latency, and held-input release on a real radio disconnect have not been verified.

Use the device acceptance checklist in [README.md](../README.md) for the first tablet/PC session. Do not interpret a passing host test or a simulated connected-state screenshot as proof of working Bluetooth hardware.

## Outputs

- GitHub Release asset `virkey-0.1.0-debug.apk`: signed development APK for tablet testing.
- GitHub Release asset `virkey-tablet-preview.png`: native-rendered UI preview with simulated connection state.
- `app/build/reports/tests/testDebugUnitTest/index.html`: test report.
- `app/build/reports/lint-results-debug.html`: full Android lint report.

The build helper also normalizes stray quotes in PATH entries for its child processes, avoiding a host-specific Java test-worker launch failure. It restores the original environment when finished and does not modify the Windows PATH setting.
