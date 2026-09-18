# Virkey 0.6.0 installation and device checks

Download the APK and Windows host from
[GitHub Releases](https://github.com/zhenxxx7/virkey/releases/tag/v0.6.0).
Local builds are saved under the ignored `.tools/release-artifacts/` directory.

## Install or upgrade

1. Install `virkey-0.6.0.apk` on the Android tablet. Keep the existing app data
   when upgrading; dock configuration and custom images are stored there.
2. If an older host is running, choose **Exit** from its tray menu. Closing its
   window only hides it. Run `virkey-host-0.6.0.exe` when using Wi-Fi; it is a
   portable Windows x64 app and needs no separate .NET installation.
3. The APK is development-signed for testing. The EXE is unsigned, so Windows
   may show a reputation warning. Download only from this repository's release
   and compare its SHA-256 checksum if needed. Administrator access is not needed.
4. The first launch defaults to Bluetooth; subsequent launches restore the last
   selected transport. Only one transport sends input at a time.

## Bluetooth

Use the [README's Bluetooth setup](../README.md#first-connection). Keyboard,
trackpad, numpad, media keys and soundboard hotkeys work without the Windows host.
Bluetooth HID does not provide the PC app catalog, live track artwork or timing.

## Wi-Fi and remembered pairing

1. Keep the tablet and PC on the same reachable, trusted local network. Start
   hosting; allow the host on a private network if Windows Firewall prompts.
   Guest networks may block communication between devices.
2. Select **Wi-Fi → Connect to PC** in Virkey. Use **Find PCs** or enter the
   address from the host; the default port is 49372.
3. Enter the host's PIN, compare the certificate fingerprint on both devices
   and confirm **Trust & pair** only when they match.
4. One fresh pairing is required when upgrading from the older, nonpersistent
   host. Afterwards, use **Reconnect** without a PIN. App and host restarts keep
   the pairing; if the PC address changes, discovery tries to find the same
   pinned PC. Manual address editing is also available.
5. **Forget PC** removes tablet trust. **Reset pairing** in Windows revokes all
   remembered tablets and changes the PIN. Both request confirmation. Read the
   [reconnect guide](reconnect-0.6.0.md) for recovery and security details.

Reconnection remains explicit. Leaving the tablet app, switching transports,
disconnecting or stopping the host releases held input. The host also releases
input after a four-second heartbeat timeout; events are not replayed later.

## Acceptance checks

- Type in a normal, non-administrator Notepad window. Test key repeat, modifier
  release order, arrow keys, Num Lock and Caps Lock, trackpad taps and scrolling.
- Hold one finger still, then drag to select text; lifting must release the drag.
- Disconnect while holding a key or mouse button. Switch apps, stop the host,
  sleep the PC and change transport; no held input should remain afterwards.
- Pair once, reconnect without a PIN, restart both apps and then reboot both
  devices. Repeat the reconnect, forget, revoke and changed-IP checks in the
  [reconnect guide](reconnect-0.6.0.md#real-device-checks).
- Verify the Virkey logo in Explorer, the host window, taskbar and tray. Closing
  the window must preserve the tray icon; **Exit** must remove it.
- Add, reorder and edit [app and soundboard dock buttons](dock-0.4.0.md). Test
  app launching over Wi-Fi and hotkeys over both transports.
- Play media on the PC and test artwork, timing, supported playback controls,
  expand/collapse and [opt-in lyrics](media-0.5.0.md). Disable online lyrics;
  keyboard input and local playback controls must still work without Internet.

Wi-Fi lock indicators are neutral because reliable Caps/Num Lock feedback is
not implemented for this transport. Bluetooth LED feedback is unchanged.
Windows blocks normal apps from injecting input into elevated apps or the
secure sign-in/UAC desktop. Player metadata, controls and lyric availability
vary. USB and other desktop operating systems are not included.

See [automated validation](validation-0.6.0.md) for what has been tested. Passing
JVM and loopback tests does not establish physical-device compatibility.
