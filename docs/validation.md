# Virkey 0.3.0 local test-build validation

Built on Windows on 2026-09-18. Bluetooth remains the default; optional Wi-Fi
input and Windows media preview require the portable Windows host. USB is not
included. Source and release assets are published as v0.3.0.

## Completed

- Android `assembleDebug`, `testDebugUnitTest`, and `lintDebug` succeeded using
  the project-local JDK 17 and Android SDK.
- 111 automated Android tests passed: 34 HID/layout tests, 19 trackpad gesture
  tests, six mouse-button ownership tests, 26 Compose UI tests, and 26 network
  tests. Seven network tests use real loopback TLS sockets.
- Network checks cover certificate inspection without sending a PIN, explicit
  fingerprint pinning, wrong-certificate rejection, authentication failures,
  ordered input/media commands, heartbeat scheduling, reconnect queue clearing,
  bounded framing/artwork decoding, and track/capability validation.
- UI checks cover Bluetooth regression behavior, transport switching and
  releases, pairing cancellation, discovery, media metadata/artwork clearing,
  seeking, disabled unsupported controls, and avoiding duplicate HID/media
  commands. Unknown Wi-Fi lock feedback uses neutral indicators.
- Actual Compose views were rendered using Android's native Canvas at API 35.
  Full keyboard/media and compact panel layouts passed checks. The full media
  view and artwork panel were visually inspected; screenshots use test fixtures,
  not proof of a real tablet connection.
- Android lint: zero errors, 13 warnings. Warnings include dependency upgrades,
  orientation/backup guidance, Kotlin style, and the custom certificate trust
  manager. The latter is intentional for a user-confirmed self-signed host:
  inspection sends no PIN; the authenticated connection pins the complete
  SHA-256 certificate fingerprint. It is covered by loopback tests, not an
  independent security audit.
- APK signature verified: v2 signing, one Android development signer. Package
  `com.virkey.app`, version `0.3.0` (code 3), minimum API 28, target API 36.
  Internet permission supports local Wi-Fi; Bluetooth/session/notification
  permissions remain. No location or accessibility-service permission.
- Windows x64 self-contained single-file EXE published successfully with the
  project-local .NET 8 SDK. Six self-test groups passed, including actual TLS
  pairing, PIN rate limiting, second-client isolation, held-input release,
  rapid key re-press/repeat, disconnect/reset/stop, and heartbeat timeout.
  All injected events went to a recording sink, not the user's desktop.
- Read-only Windows media smoke test read a real Spotify session with artwork
  and playback timing. It did not send playback commands or keyboard/mouse input.
- Windows host form rendered and was visually inspected with example connection
  details; preview mode did not start a network listener.
- No firewall rules or startup entries were created.

## Still requires tablet/PC testing

No Android device was attached through ADB. Physical Bluetooth pairing, Wi-Fi
discovery/firewall behavior, end-to-end tablet input, real-player control/seek,
latency, reconnect/sleep, and disconnect release require the user's test.

Wi-Fi Caps Lock / Num Lock feedback is deliberately marked unknown: Windows
`GetKeyState` on a background thread is not reliable foreground toggle feedback.
The keys still work; check the PC's state if the keypad sends navigation instead
of numbers. Bluetooth LED feedback is unchanged.

The EXE is unsigned and may trigger a Windows reputation warning. It runs without
administrator privileges and cannot inject input into elevated applications or
the secure sign-in/UAC desktop. Player metadata and supported commands vary by
application. A passing mock/loopback test is not a physical-device acceptance test.

Follow [the testing guide](testing-0.3.0.md) when installing.

## Local outputs

- `.tools/release-artifacts/virkey-0.3.0.apk` (28,967,967 bytes)
  - SHA-256: `4039BB2CEC4BE6DD66467378DBC67E8C9B1957CACAF8300F7CD899C7D4504805`
- `.tools/release-artifacts/virkey-host-0.3.0.exe` (187,143,607 bytes)
  - SHA-256: `EF7A524D32801684FD1617FD8B295B9CD8E0B451C47C9E9FCEBBD0D718EE1D05`
- `app/build/outputs/previews/virkey-wifi-media.png`: full Wi-Fi media layout.
- `app/build/outputs/previews/virkey-live-media.png`: artwork/media panel fixture.
- `.tools/release-artifacts/virkey-host-preview.png`: Windows host UI fixture.
- `app/build/reports/tests/testDebugUnitTest/index.html`: Android test report.
- `app/build/reports/lint-results-debug.html`: Android lint report.
- `windows/build/bin/Virkey.Host/Release/net8.0-windows10.0.19041.0/win-x64/publish/host-test-results.txt`:
  Windows self-test report.
- `.tools/release-artifacts/host-smoke-test.txt`: read-only real media report.

Build helpers normalize inherited PATH quotes, disable Gradle file watching to
avoid Windows transform-cache rename locks, and restore process environment
variables on exit. Robolectric TLS tests open `java.net` reflection on the test
JVM only; this does not change APK security settings.
