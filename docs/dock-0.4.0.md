# App dock and soundboard buttons

Introduced in the local 0.4.0 build and included in [release 0.6.0](releases/0.6.0.md).
Use `virkey-0.6.0.apk` and `virkey-host-0.6.0.exe` from GitHub Releases. Install
the APK over the existing app. Exit the old host from its tray menu before
running the new host for app discovery and launch.

## Add a PC application

1. Connect Virkey to your PC over Wi-Fi.
2. Select **Edit dock → + App**. Search the PC app list and choose an app.
3. Change its name if desired. Its Windows icon is filled automatically.
4. Use **Choose image** for a custom PNG/JPEG/WebP image. **Auto icon** restores
   the original. The app stores a small local copy of your image.
5. Save, close the editor, and tap the dock button to open the app on the PC.

If an app is absent, open **Extra apps** in Virkey Host and select its local
`.exe`, `.lnk` or `.url` shortcut. Refresh apps on the tablet. Automatic discovery
covers Start-menu shortcuts (up to 256 entries), not every executable installed
on the PC. Store apps may need a Windows shortcut added manually.

An app button belongs to the PC where it was selected. It stays disabled when
connected to another PC or using Bluetooth. You can use a Windows hotkey such
as `Win+1` as a separate hotkey button when you want a taskbar shortcut over
Bluetooth.

## Add a Voicemod sound or voice

1. In Voicemod, choose a sound or voice and set its keybind, for example
   `Ctrl+Alt+1`. Keep Voicemod running with keybinds enabled.
2. In Virkey, select **Edit dock → + Sound / hotkey**.
3. Name the button, enter the same shortcut and optionally choose an image.
4. Save, close the editor, and tap the button while connected to the PC.

Create additional buttons for other sounds, voices, or the stop-all-sounds
keybind. Avoid combinations already used by your game or another application.
Voicemod's audio routing and its feature/license requirements still apply.

This integration sends normal keyboard shortcuts; it does not embed Voicemod,
import its sound library, retrieve its sound artwork or play audio on Android.
Other soundboards that accept global keyboard shortcuts can use the same method.
See [Voicemod's keybind instructions](https://support.voicemod.net/hc/en-us/articles/16169711033490-Keybinds).

## Customize and test

- Use the left/right arrows in **Edit dock** to reorder buttons; × removes one.
- Up to 24 app and hotkey buttons share the scrollable dock.
- Button names, order, hotkeys and images persist on the tablet.
- Choosing an image opens Android's picker. Wi-Fi disconnects while Virkey is in
  the background; finish editing, then reconnect to run buttons.
- While a hotkey plays, other keyboard/trackpad presses are briefly disabled.
  Switching modes, leaving the app or releasing keys cancels it and releases
  modifiers.
- Test real app launches and Voicemod sounds on your tablet and PC. Automated
  checks use a recording launcher and input sink, so they do not launch desktop
  applications or play sounds.

PC launch settings are stored in `%LOCALAPPDATA%\Virkey\apps.json`. Network
launch requests contain a PC identity and catalog ID; the tablet never sends
an arbitrary command, path or command-line arguments to execute.
