# Virkey local Wi-Fi protocol 1

Bluetooth remains the first-run default; the selected transport is remembered. Wi-Fi input requires the portable Windows host.
All traffic uses a persistent TLS socket (port 49372 by default), with UTF-8
newline-delimited JSON. Maximum incoming line: 524288 characters. Protocol v1
supports one authenticated tablet per host. No Internet service is involved in
this local protocol. Android's optional lyric lookup uses a separate, opt-in
HTTPS request to LRCLIB; lyrics never travel through the host or pairing socket.

## Pairing

Optional discovery uses UDP port 49372. Android broadcasts ASCII
`VIRKEY_DISCOVER_V1`; host replies to that sender with
`{"protocol":1,"name":"PC name","port":49372}`. Android takes the sender's IP,
never an address in the response. Discovery is an untrusted convenience only.
Manual address entry remains available on networks that block broadcast.

Host creates a self-signed certificate and displays its SHA-256 fingerprint
(first 12 hex digits grouped for manual comparison), its LAN IPv4 addresses,
and a random six-digit session PIN. First Android connection inspects the
certificate WITHOUT transmitting the PIN. The user compares the fingerprint
with the host and explicitly confirms. Android reconnects with the complete
fingerprint pinned; only then may it send the PIN. Never accept a changed
certificate using an old confirmation. PIN failures are globally rate limited.
No credentials or private keys are committed. No automatic firewall changes.

Client authentication: `{"type":"auth","protocol":1,"pin":"123456","name":"Virkey tablet"}`.
Server success: `{"type":"ready","name":"PC name","ledsKnown":false,"capsLock":false,"numLock":true}`.
Server failure: `{"type":"error","message":"Reason"}` followed by close.

### Remembered pairing extension (app and host 0.6.0)

- First-pair `auth` adds `remember:true`. Only after PIN validation and acquiring
  the single input session does the host issue a random 256-bit reconnect token.
- `ready` adds `rememberSupported:true`, `deviceId` (32 hexadecimal characters)
  and `token` (64 hexadecimal characters). These credentials are sent only over
  the fingerprint-confirmed TLS connection. Legacy PIN-only clients still work.
- Subsequent `auth` sends `deviceId` and `token` instead of `pin`. Sending both
  authentication methods is rejected. Tokens are checked in constant time by
  comparing SHA-256 hashes. PIN/token failures share the pairing rate limit.
- Reconnect `ready` does not reissue or rotate the token. A dropped response
  cannot invalidate the client's last working credential. Authentication still
  checks the complete saved certificate before sending any token.
- Host certificate/private key and token hashes persist in Windows CurrentUser
  DPAPI-protected `%LOCALAPPDATA%\Virkey\pairing.dat`. A lifetime file lease
  prevents concurrent hosts overwriting revocations. At most 16 recent pairings
  are retained. No plaintext tokens or PINs are stored by the host.
- Android stores one last-PC record in backup-excluded app storage using AES-GCM
  with an Android Keystore key. Name, address, certificate pin and token are all
  authenticated by encryption. UI state includes public display metadata only.
- Discovery replies may add `fingerprint`. A failed remembered connection may
  use that hint to retry once at a new address; TLS must still match the original
  saved fingerprint. Authentication rejection never triggers a PIN fallback.
- A revoked/unknown token returns `error` with `code:"pairingRequired"`. A second
  valid client gets `code:"busy"`; it cannot evict the active tablet.
- Host **Reset pairing** revokes tokens, disconnects input and rotates the PIN,
  without changing the PC certificate. Tablet **Forget PC** disconnects and
  deletes only its local credential. New PIN pairing can replace a forgotten or
  revoked credential. Neither operation disables certificate checking.

## Input

- `{"type":"key","usage":4,"down":true}`: physical USB HID keyboard usage.
- `{"type":"move","dx":4,"dy":-2}`: relative mouse motion.
- `{"type":"button","button":1,"down":true}`: left=1, right=2, middle=4 masks.
- `{"type":"scroll","amount":1}`: wheel ticks.
- `{"type":"mediaKey","usage":205,"down":true}`: consumer usage.
- `{"type":"release"}`: release every held input.
- `{"type":"ping"}` -> `{"type":"pong"}` every second, including while idle.

Host releases held inputs on disconnect, shutdown, suspend, and a four-second
heartbeat timeout. Android sends release when backgrounded, closes the socket,
and never replays queued input after reconnect. Bounded write queues fail closed
on overflow. Input state transitions must stay ordered. Duplicate downs and ups
are harmless. Unsupported keyboard usages are rejected without injecting input.

Server LED update: `{"type":"leds","ledsKnown":false,"capsLock":false,"numLock":true}`.
Only display lock state when `ledsKnown` is true (missing means false). The
Windows host currently marks it false: background-thread `GetKeyState` cannot
reliably observe the foreground application's toggles. Lock keys still operate.

## Media

Host sends `nowPlaying` on changes and about once per second while connected.
Fields: `available` (boolean), `sessionId`, `trackId`, `title`, `artist`, `album`,
`player` (strings), `playing` (boolean), `positionMs`, `durationMs` (nonnegative
integers), `canPlay`, `canPause`, `canPrevious`, `canNext`, `canStop`, `canSeek`,
`canShuffle`, `canRepeat` (booleans), `shuffle` (boolean), and `repeat`
(`off`, `all`, or `one`). An unavailable session sends `available:false` and
clears the previous track. Missing metadata must stay empty rather than invented.

`artworkId` identifies current artwork. `artwork` is optional base64 image data,
sent on artwork/track changes and at initial connection; maximum decoded size
262144 bytes. Omitted artwork with an unchanged artworkId means retain the image.
An empty artworkId or a new track without artwork clears the old image.

Media commands:
`{"type":"media","command":"seek","sessionId":"...","trackId":"...","positionMs":12345}`.
Commands: `play`, `pause`, `previous`, `next`, `stop`, `seek`, `shuffle`, `repeat`.
`shuffle` adds `enabled` boolean; `repeat` adds `mode` (`off`, `all`, `one`).
Host checks session and track identity and capability before invoking Windows.
Requests that the player declines produce `{"type":"commandError","message":"..."}`.
Network media commands and HID media usages must not both fire for a single tap.

## Android shared API

### Optional app dock extension (host 0.4.0)

`ready` adds `dock:true` and a stable `pcId`. Older hosts omit these fields; the
tablet leaves app launch disabled. Only authenticated connections use the dock.

- Client `{"type":"apps"}` requests the bounded local app catalog.
- Host sends `appsBegin` with `pcId`, then up to 256 `app` frames containing
  `pcId`, `id`, `name`, and optional `icon` (base64 PNG, at most 32768 decoded
  bytes), followed by `appsEnd` with `pcId`.
- Client `{"type":"launchApp","pcId":"...","id":"..."}` requests a known app.
  The host resolves the ID against discovered/locally added paths. Paths and
  arbitrary command strings are never accepted from the tablet.
- Host acknowledges with `appLaunched`, or `appError` with a bounded message.
- App discovery/launch runs separately from input and heartbeat processing.
- Soundboard buttons emit ordinary ordered `key` down/up events. They introduce
  no new remote command protocol and also work over Bluetooth HID.

### Common state

Package `com.virkey.app.network`:

- `WifiState(isConnected:Boolean=false, isConnecting:Boolean=false,
  hostName:String="", status:String="Connect to Virkey Host on your PC",
  pendingFingerprint:String?=null, capsLock:Boolean=false, numLock:Boolean=false, ledsKnown:Boolean=false,
  nowPlaying:NowPlayingState=NowPlayingState())`.
- `WifiHost(address:String,name:String,fingerprint:String?=null)` and `WifiState.hosts:List<WifiHost>`
  (default empty), `WifiState.isDiscovering:Boolean=false`,
  `WifiController.discover()` support bounded two-second LAN discovery.
- `WifiState.savedPc:SavedWifiPc?`, `loadingSavedPc`, `pairingRequired`, and
  `rememberedConnection` expose pairing UI state without exposing the token.
  `SavedWifiPc` contains the last address, display name and certificate fingerprint.
- `NowPlayingState(available:Boolean=false, sessionId:String="", trackId:String="",
  title:String="", artist:String="", album:String="", player:String="",
  playing:Boolean=false, positionMs:Long=0, durationMs:Long=0,
  canPlay:Boolean=false, canPause:Boolean=false, canPrevious:Boolean=false,
  canNext:Boolean=false, canStop:Boolean=false, canSeek:Boolean=false,
  canShuffle:Boolean=false, canRepeat:Boolean=false, shuffle:Boolean=false,
  repeat:String="off", artwork:android.graphics.Bitmap?=null)`.
- `WifiController`: constructor no Activity required, `val state:StateFlow<WifiState>`,
  `connect(address:String,pin:String,confirmedFingerprint:String?=null)`,
  `reconnectSaved(address:String="")`, `forgetPc()`,
  `send(action:RemoteAction)`, `media(command:String,sessionId:String,trackId:String,
  positionMs:Long=0,enabled:Boolean=false,mode:String="off")`,
  `disconnect()`, `close()`.
- The production activity supplies `EncryptedWifiPairingStore` using the app's
  backup-excluded storage; the no-argument controller uses an in-memory test store.
- Controller owns coroutine scope and IO; public methods are nonblocking.
  UI updates are StateFlow; artwork decode off main thread with bounded dimensions.
  Parse address as IPv4/hostname optionally followed by :port; no arbitrary URLs.

Wi-Fi state is independent of Bluetooth state. The activity routes keyboard and
mouse actions through exactly one selected input transport. Media preview is
visible only when Wi-Fi has live session data. Switching transport releases and
disconnects previous input to prevent duplicate or stuck events.
