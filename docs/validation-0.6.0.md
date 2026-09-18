# Virkey 0.6.0 release validation

Validation for [release 0.6.0](releases/0.6.0.md), including the app/soundboard
dock, glass media player with optional lyrics, remembered Wi-Fi pairing and
Windows application logo. The earlier local 0.6.0 host was rebuilt to include
the logo; use the final EXE hash below. The APK is unchanged.

## Verified

- Android assemble, full JVM/UI tests and lint passed: **178 tests, zero
  failures, zero errors**. Lint: zero errors and 17 warnings, covering existing
  dependency/platform/certificate guidance and Kotlin style suggestions.
- Nine pairing-storage tests cover encrypted persistence, absence of plaintext
  credentials, fresh encryption nonces, missing/deleted records, tampering,
  wrong keys, invalid or oversized data, interrupted writes, key failures and
  silently failed commits. The production store verifies the committed bytes
  before reporting that a pairing was remembered.
- Two protocol tests cover certificate-gated token authentication without a PIN
  and validation of discovery fingerprints.
- Six controller tests cover first pairing and reuse in a new controller,
  changed-certificate rejection before sending credentials, revoked-token
  rejection without PIN fallback, changed-address discovery with the original
  certificate pin, forgetting during an in-flight reply and honest save-failure
  status.
- Five Compose tests cover the prefilled PIN-free Reconnect form, switching
  between a different PC and the remembered PC, re-pairing after revocation,
  confirmation before forgetting and asynchronous loading of the saved PC.
- Windows x64 self-contained publish and all **11 self-test groups** passed.
  New checks exercise DPAPI persistence, the stable host certificate, token
  hashes, exclusive store access, revocation and corruption rejection. Real
  local TLS tests cover token issuance, PIN-free reconnect after restarting the
  host, invalid credentials, active-client isolation and reset revocation.
  Input tests use recording sinks; no real keys, mouse events, app launches or
  media commands were sent.
- Logo checks decode all nine ICO frames (16 through 256 pixels), extract each
  native EXE icon with Windows and compare every pixel with the embedded asset.
  Window icons are also checked after their resource streams have been closed.
  The host, extra-apps window and tray use the same logo. Host publish has no
  compiler warnings.
- The reconnect dialog and updated host preview were rendered and visually
  inspected. The host preview uses example connection details without starting
  a listener or modifying the real pairing store. All README local preview and
  documentation links resolve.
- APK signature v2 verified: `com.virkey.app`, version 0.6.0 / code 6,
  minimum API 28, target API 36. Development-signed for local testing; the
  downloadable filename is `virkey-0.6.0.apk`.
- `git diff --check` passed. APK/EXE artifacts and the separate `website/`
  directory are ignored by Git and excluded from the source commit. Release
  assets contain only the final binaries, checksums and documentation previews.

The storage tests inject a JVM AES key. Their Windows-only Robolectric shadow
implements Android's atomic replacement semantics because Java's Windows
`File.renameTo` cannot replace an existing file. Encryption and disk I/O remain
real; the shadow also permits testing a silent commit failure. This is a test
harness adjustment, not a production storage fallback.

## Release artifacts and local reports

Download binaries and checksums from
[GitHub Releases](https://github.com/zhenxxx7/virkey/releases/tag/v0.6.0).
The following local copies and reports are retained for validation:

- `.tools/release-artifacts/virkey-0.6.0.apk`
  - SHA-256: `479D9AF505E6401DF2A29132E619C9F8246AA8A57D379873777537CF01FC6615`
- `.tools/release-artifacts/virkey-host-0.6.0.exe`
  - SHA-256: `2AEEFEFC994B48CB5CADCEF6AF110EE68077C9B9383E07EB42F138A17008F25F`
- `.tools/release-artifacts/SHA256SUMS-0.6.0.txt`
- `.tools/release-artifacts/host-tests-0.6.0.txt`
- `.tools/release-artifacts/host-preview-0.6.0.txt`
- `docs/previews/virkey-wifi-reconnect.png`
- `docs/previews/virkey-host-preview.png`
- `docs/previews/virkey-logo.png`

## Still requires device testing

Update both APK and host, fully exit the previous host from its tray menu and
pair once using its PIN and verified fingerprint. Subsequent connections use
**Reconnect** without a PIN. Connection remains explicit; Virkey does not start
typing or connect automatically on launch.

Real Android Keystore persistence, device reboots, changing network addresses
on the actual LAN and held-input behavior during disconnect/reset still need
tablet-to-PC checks. Automated JVM and loopback TLS tests are not a substitute
for those checks. See [reconnect setup and acceptance checks](reconnect-0.6.0.md),
[media checks](media-0.5.0.md) and [dock checks](dock-0.4.0.md).
