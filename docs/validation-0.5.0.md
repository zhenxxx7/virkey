# Virkey 0.5.0 local validation

Historical validation of the local 0.5.0 glass media player, enlarged artwork
and optional lyrics, including the earlier app/soundboard dock. It was not
published as a separate release; these features are included in
[0.6.0](releases/0.6.0.md). Counts and hashes below describe the original local build.

## Verified

- Android assemble, full JVM/UI tests and lint passed: **156 tests, zero
  failures, zero errors**. Lint: zero errors and 16 warnings (existing
  dependency/platform/certificate/style guidance plus one bitmap KTX suggestion).
- Nine lyric-model/parser tests cover repeated timestamps, offset signs,
  ordering, translations, instrumental breaks, seeking backwards, invalid
  tags, plain fallback, size limits and missing track metadata.
- Fifteen repository/transport tests cover matching, URL encoding and seconds,
  cache/cooldown/eviction, cancellation, bounded reads, malformed responses,
  offline failures, HTTPS configuration, redirect refusal and connection closure.
- Seven additional Compose tests cover explicit opt-in, no per-tick requests,
  disabling/hiding lyrics, current-line highlighting, supported tap-to-seek,
  stale request cancellation, plain/offline/instrumental states, enlarged
  artwork, compact bounds and restoring the number pad.
- Windows x64 self-contained publish and all eight self-test groups passed.
  Artwork checks verify 640-pixel bounds, preserved aspect ratio, no upscaling,
  source-size rejection and the existing 256 KiB payload cap. Input tests use
  recording sinks; no real keys, mouse events, app launches or media commands.
- A read-only Windows media smoke check found Spotify with artwork and a
  timeline. No playback or input commands were sent.
- One public LRCLIB sample lookup returned HTTP 200 with title, artist,
  duration, plain lyrics and synced lyrics fields. No private listening history
  was used; lyric text was not printed or saved by the smoke check.
- Native Compose glass/lyrics and full-screen media previews were visually
  inspected. Preview art and lyric lines are original synthetic fixtures.
  README preview files were refreshed. Host preview was rendered hidden,
  using example connection details without starting a listener.
- APK signature v2 verified: `com.virkey.app`, version 0.5.0 / code 5,
  minimum API 28, target API 36. Development-signed for local testing.

## Local artifacts

- `.tools/release-artifacts/virkey-0.5.0.apk`
  - SHA-256: `34593D100DBCE6C2EF7ADB4AE8A068F07C112537905724C86902C69C20BAB587`
- `.tools/release-artifacts/virkey-host-0.5.0.exe`
  - SHA-256: `1692533DA09D330926A1895F8000E439FAF3912DACEA4800CD71843273C290BC`
- `.tools/release-artifacts/SHA256SUMS-0.5.0.txt`
- `.tools/release-artifacts/media-smoke-0.5.0.txt`
- `.tools/release-artifacts/host-preview-0.5.0.txt`
- `docs/previews/virkey-lyrics.png`

Real tablet-to-PC lyric synchronization, touch behavior, app launching and
Voicemod playback still require device testing. Catalog coverage and PC
timeline accuracy vary. See [media checks](media-0.5.0.md) and
[dock checks](dock-0.4.0.md).
