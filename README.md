# Virkey

An Android tablet keyboard and trackpad for a Windows PC. Virkey presents itself as a standard Bluetooth HID keyboard and mouse; Windows does not need a receiver app.

This first build targets landscape Android tablets and Windows 11. Compiling and automated tests do not establish compatibility with a particular tablet's Bluetooth firmware: the real tablet-to-PC checks below are required.

## Preview

![Virkey laptop keyboard and trackpad interface](https://github.com/zhenxxx7/virkey/releases/download/v0.1.0/virkey-tablet-preview.png)

## First connection

1. Install the debug APK on the tablet and open **Virkey**.
2. Tap **Connect to PC**, allow **Nearby devices**, and enable Bluetooth if prompted. Allow notifications to make the session's Disconnect action accessible outside the app.
3. On Windows, open **Settings → Bluetooth & devices → Add device → Bluetooth**.
4. In Virkey, tap **Pair new Windows PC** and approve Android's discoverability prompt. Select the tablet's existing Bluetooth name in Windows; it may appear as the device's system Bluetooth name rather than Virkey.
5. Confirm the matching pairing codes on the tablet and PC. Return to Virkey, tap **Refresh**, and select your PC if it has not connected automatically.
6. Close the connection dialog with **Start typing**. Open Notepad on the PC and try the keyboard and trackpad.

Use **English (United States) / US** as the Windows keyboard layout for matching key legends. The tablet sends physical keyboard usages; Windows determines the resulting characters.

If the PC was paired with the tablet before Virkey was installed and only sees phone/file-transfer services, remove the tablet in Windows Bluetooth settings and pair again while Virkey's keyboard session is running. Virkey does not silently remove existing pairings.

## Controls

| Control | Behavior |
| --- | --- |
| Keyboard key | Press while touching; release when lifted or cancelled |
| Shift, Ctrl, Alt, Win | Hold with another finger while pressing another key |
| Caps | Toggle Windows Caps Lock; indicator follows Windows LED feedback |
| One finger on trackpad | Move the mouse pointer |
| One-finger tap | Left-click |
| Two-finger tap | Right-click |
| Two-finger vertical movement | Scroll |
| LEFT button held + another finger on trackpad | Drag |
| RIGHT button | Right mouse button, including press-and-hold |
| Release keys | Release every key and mouse button |
| Connection dialog / notification → Disconnect | End the Bluetooth keyboard session |

The keyboard includes Esc, F1–F12, Delete, a US QWERTY section, modifiers, and an inverted-T arrow cluster. It deliberately has no fake hardware Fn key. Mouse gestures are relative mouse input, not Windows Precision Touchpad gestures; three/four-finger Windows gestures are not implemented.

## Session behavior

- Keep Virkey visible while using the keyboard. The screen stays awake while the app is visible.
- A connected-device foreground service and notification maintain registration through pairing dialogs. Switching away releases all locally held inputs. Removing Virkey from Recents stops the session.
- Disconnects clear local key state. A fresh connection sends empty input reports before accepting new input. A failed send ends the connection rather than replaying input later.
- Only one PC is active at a time. Reconnecting is explicit; Virkey remembers the last selected PC but does not connect or type automatically after launch.
- No Internet permission, cloud service, accounts, analytics, or keystroke logging are included. Only the last PC address is saved locally.

Android 9 / API 28 is the minimum. The tablet's OS must expose the Bluetooth HID Device profile. Other desktop operating systems, pre-login screens, BIOS, and behavior under device battery management have not been certified. Wi-Fi transport, remote screen viewing, clipboard sync, media-key layers, and macros are outside this first build.

## Build

Use JDK 17, Android SDK platform 36 / build tools 35.0.0, and the included Gradle wrapper. Android Studio can open this directory directly; select JDK 17 for Gradle. The included Windows scripts can keep downloaded tools and caches in `.tools/` without changing machine-wide settings.

```powershell
# Download pinned official tool archives and complete Google's SDK license prompt.
.\scripts\setup-toolchain.ps1

# Build APK, run JVM tests, and run Android lint.
.\scripts\build.ps1
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. It is signed with a development key for testing; a production release requires a separate private signing key and release process. Never commit signing keys.

For build dependencies, checksums, and SDK setup details, see [docs/toolchain.md](docs/toolchain.md).

## Device acceptance checklist

Run these on your Android tablet and Windows 11 before treating the build as reliable:

1. Pair from a clean pairing and from an existing tablet pairing; verify keyboard and mouse both appear and work.
2. Type letters, numbers, punctuation, Enter, Tab, Backspace, and arrows in Notepad. Hold a key to test Windows key repeat.
3. Hold Shift while typing; test Ctrl+A, Ctrl+C, Ctrl+V, Alt+Tab, and Win. Check that releasing either finger releases that key and no modifier stays held.
4. Test pointer motion, one-finger tap, double-click, two-finger right-click, scrolling, and LEFT-button dragging. Lifting one finger after a scroll must not cause a click or cursor jump.
5. While holding a key or dragging, switch apps, lock the tablet, turn Bluetooth off, and disconnect. Confirm no stuck input remains; reconnect and repeat.
6. Sleep and wake Windows, reconnect manually, and verify Caps Lock feedback and input. Record any repair/re-pair requirement.
7. Try tablet multi-window and both landscape directions. Android 16 may ignore orientation requests on large displays; use landscape full-screen for the laptop arrangement.

## Code map

- `input/`: pure Kotlin HID reports, physical key mapping, rollover handling.
- `bluetooth/`: Android HID profile, pairing/connection state, foreground session.
- `ui/`: Compose laptop surface, connection dialog, pointer gestures.
- `MainActivity`: Android permission and system pairing flows, lifecycle release handling.
- `app/src/test/`: HID, layout, gesture, and UI checks.

## Platform references

- [Android Bluetooth HID Device](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice)
- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Android connected-device foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)
- [Windows built-in HID transports](https://learn.microsoft.com/en-us/windows-hardware/drivers/hid/hid-transports)
- [Bluetooth HID profile and boot protocol](https://www.bluetooth.com/wp-content/uploads/2023/08/HID-Lite-WP-V10.pdf)
