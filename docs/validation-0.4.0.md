# Virkey 0.4.0 local validation

Historical validation of the local 0.4.0 app/soundboard dock build. It was not
published as a separate release; these features are included in
[0.6.0](releases/0.6.0.md). Counts and hashes below describe the original local build.

- Android assemble, unit tests and lint passed: **125 tests, zero failures,
  zero errors**. Lint has zero errors and 15 warnings (dependency, platform,
  intentional certificate pinning, and Kotlin style guidance).
- Dock checks cover keybind parsing, ordered modifier release, cancellation,
  persisted edits/reordering/removal, bounded configuration, copied/resized
  custom images, wrong-PC launch gating, app picking, and editor interaction.
- Real loopback TLS tests exercise app discovery and ID-only launch messages.
- Windows host published with seven self-test groups passing. New checks use
  a recording launcher to verify persistence, PC identity, ID validation,
  authenticated app commands and removal. No desktop application was launched
  by these tests.
- Read-only Windows discovery found **242 apps and 242 icons** on this PC.
  No user settings were written by that smoke test.
- Native Compose screenshot `virkey-app-dock.png` and Windows host preview were
  visually inspected. Preview app/sound names and artwork are fixtures.
- APK v2 signature verified, package `com.virkey.app`, version 0.4.0/code 4,
  minimum API 28 and target API 36. The APK retains the existing development key.

## Local outputs

- `.tools/release-artifacts/virkey-0.4.0.apk`
  - SHA-256: `09581B2E5F0C5844B9260C432792F8C029406CCEC08069065D4F3B67A322AB08`
- `.tools/release-artifacts/virkey-host-0.4.0.exe`
  - SHA-256: `2E4F96DB330A10CCC547630E31711FD67353A5AA680577D95BC69FBBBC8F684B`
- `.tools/release-artifacts/SHA256SUMS-0.4.0.txt`
- `.tools/release-artifacts/apps-smoke-0.4.0.txt`
- `docs/previews/virkey-app-dock.png`

Actual tablet-to-PC app launching and Voicemod playback remain for device testing.
Voicemod is controlled through user-assigned hotkeys; sound library synchronization
and voice processing are not implemented in Virkey. See [setup instructions](dock-0.4.0.md).
