# Release packaging

App source and documentation belong in the Git repository. APKs, EXEs and their
checksums belong in GitHub Releases. Do not commit `.tools/`, build directories,
signing keys, pairing stores, local settings or the separate `website/` directory.
The root `.gitignore` explicitly excludes `/website/`; never force-add it.

## Validate and package

1. Keep Android version name/code, Windows project version, build script output
   name and release documentation in sync. Current release: **0.6.0 / code 6**.
2. If the Android vector logo changes, regenerate the committed Windows ICO and
   README logo using `scripts/render-host-icon.ps1` before building.
3. Run the complete checks:

   ```powershell
   .\scripts\build.ps1 assembleDebug testDebugUnitTest lintDebug --no-daemon --max-workers=1
   .\scripts\build-host.ps1
   ```

4. Verify the APK using Android's `apksigner verify --verbose` and inspect its
   version with `aapt dump badging`. Copy it from
   `app/build/outputs/apk/debug/app-debug.apk` to
   `.tools/release-artifacts/virkey-0.6.0.apk`. The clean filename does not change
   its development signing status. The host script already creates
   `.tools/release-artifacts/virkey-host-0.6.0.exe` after its self-tests pass.
5. Refresh the native UI fixture previews in `docs/previews/`. Render the host
   with `--render-preview <png> --report <txt>`; this uses example connection
   details, not the real host settings or a listening server. Inspect images.
6. Compute SHA-256 hashes of the final binaries with `Get-FileHash`. Store them
   in `SHA256SUMS-0.6.0.txt` beside the assets and the release validation report.
   Any rebuild of a binary requires checking its hash again.

## Commit and publish

1. Inspect the full staged file list and `git diff --cached --check`. Stage only
   app/host source, build support, documentation and preview/logo assets. Check
   that no `website/`, executable package, private key or local settings are tracked.
2. Commit the verified changes and push the intended branch without rewriting
   existing history. Tag that exact commit `v0.6.0`; do not move an existing tag.
3. Create a draft GitHub release using `docs/releases/0.6.0.md` as its body.
   Upload only the versioned APK, EXE, checksum manifest and documentation PNG
   previews. No website files or local diagnostic logs are release assets.
4. Verify every uploaded filename, size and SHA-256 digest. Publish the draft
   after all assets are present and mark it latest. Preserve the APK development
   signing and unsigned EXE notices; do not call it production-signed.
5. Verify the published tag resolves to the pushed commit, download links and
   README images work, and the remote Git tree has no `website/` directory.

Retain historical validation reports as historical reports; link users to the
current setup guide instead of silently changing old test counts or hashes.
