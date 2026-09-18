# Glass media player and lyrics

Introduced in the local 0.5.0 build and included in [release 0.6.0](releases/0.6.0.md).
Install `virkey-0.6.0.apk` and use `virkey-host-0.6.0.exe` over Wi-Fi for track
metadata and remembered pairing. Hosts from 0.5.0 onward supply artwork up to
640 pixels on its longest edge, with a
320-pixel fallback to preserve the existing 256 KiB limit; small images are not upscaled.
Bluetooth input and media hotkeys still work, but Bluetooth HID does not supply
the current track or its lyrics.

## Use

1. Start the Windows host, connect over Wi-Fi and play a song on the PC.
2. Open **Numpad & media**. The larger artwork and glass tint follow the PC's
   current media session. **Expand** hides the number pad, not the trackpad.
3. Tap **Lyrics**, then **Enable online lyrics**. This opt-in sends track title,
   artist, album and duration to [LRCLIB](https://lrclib.net/docs) over HTTPS.
   The provider can see your public IP. No artwork, keyboard input, pairing
   secrets or PC IDs are sent. No Spotify account is needed.
4. Timed lyrics follow the PC's reported position, including pause and seeking.
   Tap a timed line to seek if the player supports it. Scroll to read ahead;
   playback automatically follows again at the next line change.
5. **Online off** clears lyrics and disables requests. Hiding Lyrics stops
   lookup; enabling remains remembered while this media deck stays open.
   **Number pad** restores the keypad. Leaving the deck resets the lyrics opt-in.

## Availability and privacy

- Lyrics come from an independent public catalog, not Windows or Spotify's
  private lyric service. Coverage and timing accuracy vary by recording.
- Matching checks title, artist and (when reported) duration. No speculative
  broad-search fallback is used, to avoid showing another song's lyrics.
- Plain text is used if timed lyrics do not exist. Instrumental, missing track
  details, not found, offline, timeout and busy-service states are explicit.
- Requests are debounced, cancellable and rate-limited. No automatic retry loop.
  Errors have a 30-second cooldown; **Retry** works after that cooldown.
- At most 24 track results are cached in memory, expiring after 30 minutes
  (five minutes for misses, 30 seconds for failures). Nothing is saved to disk.
- Responses are bounded to 256 KiB, lyric strings to 80,000 characters and
  parsed timed entries to 2,000. The renderer displays text, never HTML.
- The glass effect uses artwork-colored gradients, translucency and highlights;
  no Android 12-only blur requirement, animation loop, or extra graphics library.
- Preview images use original synthetic art and lyric fixtures.

## Device checks still needed

1. Play a song with a matching title and artist. Enable lyrics and verify a line
   follows the actual PC audio; seek forward/backward and pause/resume.
2. Skip rapidly; a previous track's artwork and lyrics must not reappear.
3. Try an instrumental and a track without lyrics; playback remains usable.
4. Disconnect Internet while keeping local Wi-Fi connected; input and playback
   must continue. Re-enable Internet, wait 30 seconds and tap **Retry**.
5. Disable online lyrics and change songs; no lyric lookups should occur.
6. Check artwork, long titles, scroll, timed-line seeking, volume, repeat,
   trackpad and dock in full-screen landscape and a smaller window.
7. Switch apps/disconnect the PC; no stale lyrics, held keys or mouse buttons.

The test APK is development-signed. APK naming intentionally omits the
build type; it does not imply production signing. Do not publish private keys.
