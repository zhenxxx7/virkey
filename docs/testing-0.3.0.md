# Virkey 0.3.0 test guide

Historical guide for 0.3.0. For current installation, persistent pairing and
device checks, use [the 0.6.0 guide](testing-0.6.0.md).

This version keeps Bluetooth as the default and adds optional local Wi-Fi input
and a live media panel. USB transport is not included.

## Files

- Android: `.tools/release-artifacts/virkey-0.3.0.apk`
- Windows: `.tools/release-artifacts/virkey-host-0.3.0.exe`

Install the APK over the existing build. Run the Windows host only when
using Wi-Fi mode. The portable host needs no installer and does not start with
Windows automatically.

The test EXE is unsigned; Windows may show a reputation warning. No administrator
access is needed. Use only the locally built file you trust.

## Bluetooth

Open Virkey and connect to your paired PC as before. Keyboard, trackpad, numpad,
and media buttons operate without the Windows host. Bluetooth alone does not
provide track artwork or playback timing.

## Wi-Fi

1. Connect the tablet and PC to the same trusted local network.
2. Run `virkey-host-0.3.0.exe` and start hosting. If Windows Firewall prompts,
   allow access on your private network. Guest networks may isolate devices.
3. In Virkey, tap **Bluetooth** in the header to switch to **Wi-Fi**, then open
   **Connect to PC**.
4. Use **Find PCs**, or enter an address shown in the Windows host. Default port
   is 49372. Enter the host's six-digit PIN and connect.
5. Compare the certificate fingerprint shown on the tablet with the Windows
   host. Confirm **Trust & pair** only when they match.
6. Once connected, close the dialog and test typing and pointer movement in
   a normal (not administrator) Notepad window.
7. Start a track in Spotify or another player that exposes a Windows media
   session. Open **Numpad & media** to see artwork, track details, and timing.
   Test play/pause, previous/next, seeking, and volume. Controls appear or enable
   according to the player's capabilities.

Wi-Fi uses TLS directly between tablet and PC. It does not need an account,
Internet service, or Spotify developer credentials. Restarting the host creates
a new certificate and PIN; compare the fingerprint again when pairing.

Switching apps on the tablet ends the Wi-Fi input session and releases held
inputs. Reconnect explicitly on return. Switching transport also disconnects
the previous transport, so the same press cannot reach the PC twice.

## Check after installing

- Hold Shift/Ctrl while typing and verify release order and key repeat.
- Test keypad Num Lock and Caps Lock feedback.
- Hold one finger still on the trackpad, drag to select, then lift to release.
- Pause, seek, skip, and change tracks; verify artwork and timing update.
- Stop the player and verify old track details clear.
- Disconnect Wi-Fi while holding a key; verify it releases within four seconds.
- Switch tablet apps, switch connection modes, stop the host, and sleep the PC;
  verify no held key remains after each action.

Windows prevents a normal process from injecting input into administrator
windows or the secure sign-in/UAC desktop. Player metadata and supported media
commands depend on the player; live streams may have no seekable duration.

Wi-Fi does not report reliable Caps Lock / Num Lock state yet. The app shows
neutral indicators in Wi-Fi mode; the keys still work. Check the state on your
PC if the keypad sends navigation instead of numbers. Bluetooth LED feedback
is unchanged.
