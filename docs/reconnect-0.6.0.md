# Remembered Wi-Fi connections

## Upgrade and use

1. Install `virkey-0.6.0.apk` and use `virkey-host-0.6.0.exe` on Windows.
   Exit the old host from its system-tray menu first; closing the window only
   hides it. Only one host instance may open the persistent pairing store.
2. Start hosting. On the tablet choose Wi-Fi, enter the host address and PIN,
   then verify the fingerprint and tap **Trust & pair**. One fresh pairing is
   needed after upgrading because older hosts did not save their identity.
3. Later, open **Connect to PC → Reconnect**. The last PC address is prefilled;
   no pairing code or repeat fingerprint confirmation is needed. App restarts,
   host restarts and ordinary network disconnects preserve this pairing.
4. Virkey remembers the last selected Bluetooth/Wi-Fi mode. Connection remains
   a deliberate action: it does not reconnect, launch apps or type on its own.
   Bluetooth still uses Android's existing bonded-PC list and selected device.

If DHCP changes the PC address, reconnect first tries the saved address, then
performs one bounded LAN scan for the saved identity and retries with the
original certificate pin. If broadcasts are blocked, use **Find PCs** or edit
the address manually. Never accept a different fingerprint without comparing
it with the intended PC. Hostname/IP alone is not proof of identity.

## Forget and revoke

- **Forget PC** on the tablet asks for confirmation, disconnects input and
  deletes its local credential. Pair again with the PIN when needed.
- **Reset pairing** on Windows asks for confirmation, revokes all remembered
  devices, disconnects the active tablet and changes the pairing PIN. Use this
  if a tablet is lost or someone should no longer control the PC.
- The host retains the 16 most recently issued device credentials. Pairing a
  seventeenth replaces the oldest. Only one tablet may provide input at a time.
- A revoked credential asks for a fresh PIN; there is no silent credential
  downgrade or automatic approval of changed certificates.

## Storage and recovery

The six-digit PIN is never saved on the tablet. Instead, a random reconnect
token is encrypted along with the PC identity/address using AES-GCM and an
Android Keystore key in the app's backup-excluded directory. Host token hashes
and its private certificate are protected using Windows DPAPI for the current
Windows account. TLS remains pinned on every connection.

Clearing/reinstalling the Android app, changing the Windows account, or deleting
the host's pairing data requires pairing again. Stop/Exit the host before
working with its settings. To recover an unreadable or expired host identity,
back up and rename `%LOCALAPPDATA%\Virkey\pairing.dat`, then start the host to
create a fresh identity. This intentionally invalidates saved tablet trust:
compare the new fingerprint and pair again. Do not share this file or private
keys. Virkey does not silently overwrite unreadable pairing data.

If a save fails, input can remain connected but the UI reports that the PC could
not be remembered. Fix local storage/permissions and pair again. Older hosts
remain usable with PIN pairing, but require upgrading for remembered reconnect.

## Real-device checks

1. Pair once, disconnect and reconnect without typing a PIN.
2. Close/reopen the tablet app and fully exit/restart the host; reconnect again.
3. Reboot both devices; ensure the certificate fingerprint is unchanged.
4. Change the PC's LAN address; check discovery fallback or edit the address.
5. Choose **Forget PC**; cancel first, then confirm, and verify PIN pairing is
   required afterwards.
6. Reset pairing on Windows; verify the previous token is rejected, including
   after restarting the host. Fresh PIN pairing should restore access.
7. While a key is held, disconnect or reset pairing; verify it releases and no
   old key events are replayed on reconnect.

Automated encryption tests use a JVM test key; real Android Keystore persistence
and your tablet's restart behavior must still be checked on the device.
