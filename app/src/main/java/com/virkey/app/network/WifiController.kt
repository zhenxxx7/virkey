package com.virkey.app.network

import com.virkey.app.ui.RemoteAction
import com.virkey.app.dock.PcApp
import com.virkey.app.dock.decodeDockIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/** A single authenticated input connection. Nothing queued survives disconnect/reconnect. */
class WifiController internal constructor(
    private val pairings: WifiPairingStore,
    private val scan: (alive: () -> Boolean, found: (WifiHost) -> Unit) -> List<WifiHost> = ::scanWifiHosts,
) : AutoCloseable {
    constructor() : this(MemoryWifiPairingStore())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val mutableState = MutableStateFlow(WifiState(loadingSavedPc = true))
    val state: StateFlow<WifiState> = mutableState.asStateFlow()
    private var current: Connection? = null
    private var closed = false
    private var discoveryEpoch = 0L
    private var pairingRevision = 0L
    private var savedCredential: WifiCredential? = null

    init {
        scope.launch {
            try {
                val saved = pairings.load()
                synchronized(lock) {
                    if (!closed && pairingRevision == 0L) {
                        savedCredential = saved
                        mutableState.value = mutableState.value.copy(savedPc = saved?.pc, loadingSavedPc = false)
                    }
                }
            } catch (_: Exception) {
                synchronized(lock) {
                    if (!closed && pairingRevision == 0L) mutableState.value = mutableState.value.copy(
                        loadingSavedPc = false, status = "Saved pairing unavailable. Pair your PC again.")
                }
            }
        }
    }

    fun connect(address: String, pin: String, confirmedFingerprint: String? = null) {
        val endpoint: WifiEndpoint
        val normalizedPin: String
        val fingerprint: String?
        try {
            endpoint = WifiEndpoint.parse(address)
            normalizedPin = normalizePin(pin)
            fingerprint = confirmedFingerprint?.let(::normalizeFingerprint)
        } catch (error: IllegalArgumentException) {
            synchronized(lock) {
                if (!closed) mutableState.value = mutableState.value.copy(status = error.message.orEmpty())
            }
            return
        }
        startConnection(Connection(endpoint, normalizedPin, fingerprint))
    }

    fun reconnectSaved(address: String = "") {
        val saved = synchronized(lock) { savedCredential } ?: return
        val endpoint = try { WifiEndpoint.parse(address.ifBlank { saved.pc.address }) }
            catch (error: IllegalArgumentException) {
                synchronized(lock) { if (!closed) mutableState.value = mutableState.value.copy(status = error.message.orEmpty()) }
                return
            }
        startConnection(Connection(endpoint, "", saved.pc.fingerprint, saved))
    }

    fun forgetPc() {
        disconnect()
        synchronized(lock) {
            if (closed) return
            pairingRevision++
            mutableState.value = mutableState.value.copy(loadingSavedPc = true)
        }
        scope.launch {
            synchronized(lock) {
                if (closed) return@launch
                try {
                    pairings.clear()
                    savedCredential = null
                    mutableState.value = freshState("Saved PC forgotten. Pair again to reconnect.")
                        .copy(savedPc = null, loadingSavedPc = false)
                } catch (_: Exception) {
                    mutableState.value = mutableState.value.copy(loadingSavedPc = false, status = "Could not forget this PC. Try again.")
                }
            }
        }
    }

    private fun startConnection(connection: Connection, expected: Connection? = null) {
        val old = synchronized(lock) {
            if (closed || (expected != null && current !== expected)) return
            val previous = current
            previous?.active?.set(false)
            current = connection
            mutableState.value = freshState(if (connection.credential != null) "Reconnecting to your saved PC…"
                else "Connecting to ${connection.endpoint.host}…").copy(isConnecting = true)
            previous
        }
        old?.let { shutdown(it, release = true) }
        scope.launch { runConnection(connection) }
    }

    fun send(action: RemoteAction) {
        val frame = inputFrame(action) ?: return
        val connection = synchronized(lock) {
            if (!mutableState.value.isConnected) return
            current ?: return
        }
        enqueue(connection, frame, clearPending = action == RemoteAction.ReleaseAll)
    }

    fun media(
        command: String,
        sessionId: String,
        trackId: String,
        positionMs: Long = 0,
        enabled: Boolean = false,
        mode: String = "off",
    ) {
        val connection: Connection
        val playing: NowPlayingState
        synchronized(lock) {
            if (!mutableState.value.isConnected) return
            connection = current ?: return
            playing = mutableState.value.nowPlaying
        }
        if (!playing.available || playing.sessionId != sessionId || playing.trackId != trackId) return
        val frame = mediaFrame(playing, command, sessionId, trackId, positionMs, enabled, mode)
        if (frame == null) {
            update(connection) { it.copy(status = "This player does not support that media action") }
            return
        }
        enqueue(connection, frame)
    }

    fun requestApps() {
        val connection = synchronized(lock) {
            if (!mutableState.value.isConnected || !mutableState.value.dockSupported || mutableState.value.appsLoading) return
            mutableState.value = mutableState.value.copy(appsLoading = true, appMessage = "Finding PC apps…")
            current ?: return
        }
        enqueue(connection, "{\"type\":\"apps\"}")
    }

    fun launchApp(pcId: String, id: String) {
        val connection = synchronized(lock) {
            val state = mutableState.value
            if (!state.isConnected || !state.dockSupported || state.pcId != pcId) return
            current ?: return
        }
        enqueue(connection, JSONObject().put("type", "launchApp").put("pcId", pcId).put("id", id).toString())
    }

    fun disconnect() {
        val old = synchronized(lock) {
            if (closed) return
            val previous = current
            current = null
            previous?.active?.set(false)
            mutableState.value = freshState("Disconnected")
            previous
        }
        old?.let { shutdown(it, release = true) }
    }

    override fun close() {
        val old = synchronized(lock) {
            if (closed) return
            closed = true
            discoveryEpoch++
            val previous = current
            current = null
            previous?.active?.set(false)
            mutableState.value = freshState("Disconnected").copy(isDiscovering = false)
            previous
        }
        if (old == null) scope.cancel()
        else shutdown(old, release = true, closeController = true)
    }

    /** Discovery results are hints only: the certificate still needs explicit confirmation. */
    fun discover() {
        val epoch = synchronized(lock) {
            if (closed) return
            discoveryEpoch++
            mutableState.value = mutableState.value.copy(isDiscovering = true, hosts = emptyList())
            discoveryEpoch
        }
        scope.launch {
            val hosts = linkedMapOf<String, WifiHost>()
            var errorMessage: String? = null
            try {
                scan({ isActive && isDiscoveryCurrent(epoch) }) { host ->
                    hosts[host.address] = host
                    synchronized(lock) {
                        if (!closed && discoveryEpoch == epoch) mutableState.value = mutableState.value.copy(hosts = hosts.values.toList())
                    }
                }
            } catch (_: Exception) {
                errorMessage = "PC discovery failed. Enter the address shown by Virkey Host."
            } finally {
                synchronized(lock) {
                    if (!closed && discoveryEpoch == epoch) {
                        val previous = mutableState.value
                        mutableState.value = previous.copy(
                            isDiscovering = false,
                            status = if (hosts.isEmpty() && !previous.isConnected && !previous.isConnecting &&
                                previous.pendingFingerprint == null
                            ) errorMessage ?: "No PCs found. Enter the address shown by Virkey Host."
                            else previous.status,
                        )
                    }
                }
            }
        }
    }

    private fun isDiscoveryCurrent(epoch: Long): Boolean = synchronized(lock) {
        !closed && discoveryEpoch == epoch
    }

    private suspend fun runConnection(connection: Connection) {
        try {
            val trust = PairingTrustManager(connection.fingerprint)
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            val socket = context.socketFactory.createSocket() as SSLSocket
            connection.socket = socket
            if (!connection.active.get()) { socket.close(); return }
            socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            socket.tcpNoDelay = true
            socket.soTimeout = 4000
            socket.connect(InetSocketAddress(connection.endpoint.host, connection.endpoint.port), 4000)
            socket.startHandshake()
            if (!connection.active.get()) return
            if (connection.fingerprint == null) {
                val fingerprint = trust.peerFingerprint ?: throw IOException("PC certificate was unavailable")
                synchronized(lock) {
                    if (current === connection && !closed) {
                        current = null
                        connection.active.set(false)
                        mutableState.value = freshState("Compare this fingerprint with Virkey Host, then confirm").copy(
                            hostName = connection.endpoint.host, pendingFingerprint = fingerprint,
                        )
                    }
                }
                return
            }
            socket.soTimeout = 1000
            connection.writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
            writeFrame(connection, connection.credential?.let { rememberedAuthenticationFrame(it, trust) }
                ?: authenticationFrame(connection.pin, trust))
            connection.lastReceivedNanos = System.nanoTime()
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            val parser = NowPlayingParser()
            coroutineScope {
                val writer = launch(Dispatchers.IO) {
                    try {
                        for (frame in connection.commands) {
                            if (!connection.active.get()) break
                            writeFrame(connection, frame)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (connection.active.get()) {
                            connection.failure = error.message ?: "Could not send input to Virkey Host"
                            connection.closeSocket()
                        }
                    }
                }
                val heartbeat = launch(Dispatchers.IO) {
                    while (isActive && connection.active.get()) {
                        delay(1000)
                        if (!connection.active.get()) break
                        if (connection.timedOut()) {
                            connection.failure = "Virkey Host stopped responding"
                            connection.closeSocket()
                            break
                        }
                        if (connection.authenticated) enqueue(connection, "{\"type\":\"ping\"}")
                    }
                }
                try {
                    while (connection.active.get()) {
                        val frame = readFrame(reader, connection.active::get, connection::timedOut)
                            ?: throw IOException("Virkey Host closed the connection")
                        connection.lastReceivedNanos = System.nanoTime()
                        val json = JSONObject(frame)
                        if (!connection.authenticated && json.text("type") !in setOf("ready", "error")) {
                            throw IOException("Virkey Host did not complete pairing")
                        }
                        when (json.text("type")) {
                            "error" -> throw AuthenticationRejected(json.text("code"), json.text("message").ifBlank { "Virkey Host rejected the connection" })
                            "ready" -> {
                                if (connection.authenticated) throw IOException("Virkey Host sent a duplicate pairing response")
                                val pc = SavedWifiPc("${connection.endpoint.host}:${connection.endpoint.port}",
                                    json.text("name").take(128).ifBlank { connection.endpoint.host }, requireNotNull(connection.fingerprint))
                                val credential = connection.credential?.copy(pc = pc) ?: if (json.flag("rememberSupported"))
                                    WifiCredential(pc, normalizedHex(json.text("deviceId"), 32), normalizedHex(json.text("token"), 64)) else null
                                update(connection) {
                                    var status = "Connected over Wi-Fi"
                                    if (credential != null) {
                                        try {
                                            pairings.save(credential)
                                            savedCredential = credential
                                            pairingRevision++
                                        } catch (_: Exception) { status = "Connected, but could not save this PC for next time." }
                                    } else status = "Connected. Update Virkey Host to remember Wi-Fi pairing."
                                    connection.authenticated = true
                                    it.copy(isConnected = true, isConnecting = false,
                                        hostName = pc.name,
                                        status = status, pendingFingerprint = null, savedPc = savedCredential?.pc, loadingSavedPc = false,
                                        rememberedConnection = credential != null && savedCredential == credential,
                                        capsLock = json.flag("capsLock"), numLock = json.flag("numLock"),
                                        ledsKnown = json.flag("ledsKnown"), dockSupported = json.flag("dock"),
                                        pcId = json.text("pcId").take(80))
                                }
                                if (json.flag("dock")) requestApps()
                            }
                            "pong" -> Unit
                            else -> {
                                if (!connection.authenticated) throw IOException("Virkey Host did not complete pairing")
                                when (json.text("type")) {
                                    "leds" -> update(connection) {
                                        it.copy(capsLock = json.flag("capsLock"), numLock = json.flag("numLock"),
                                            ledsKnown = json.flag("ledsKnown"))
                                    }
                                    "nowPlaying" -> {
                                        val playing = parser.parse(json)
                                        update(connection) { it.copy(nowPlaying = playing) }
                                    }
                                    "appsBegin" -> update(connection) {
                                        if (json.text("pcId") == it.pcId) it.copy(apps = emptyList(), appsLoading = true, appMessage = "Finding PC apps…") else it
                                    }
                                    "app" -> {
                                        val id = json.text("id").take(80)
                                        val name = json.text("name").take(120)
                                        val app = PcApp(id, name, decodeDockIcon(json.text("icon")))
                                        update(connection) {
                                            if (json.text("pcId") == it.pcId && id.isNotBlank() && name.isNotBlank() && it.appsLoading &&
                                                it.apps.size < 256 && it.apps.none { entry -> entry.id == id }) it.copy(apps = it.apps + app) else it
                                        }
                                    }
                                    "appsEnd" -> update(connection) {
                                        if (json.text("pcId") == it.pcId) it.copy(appsLoading = false, appMessage = "${it.apps.size} apps available") else it
                                    }
                                    "appError" -> update(connection) {
                                        it.copy(appsLoading = false, appMessage = json.text("message").take(200), status = json.text("message").take(200))
                                    }
                                    "appLaunched" -> update(connection) { it.copy(status = "App opened on your PC") }
                                    "commandError" -> update(connection) {
                                        it.copy(status = json.text("message").ifBlank { "Player declined that command" })
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    writer.cancel()
                    heartbeat.cancel()
                    connection.closeSocket()
                }
            }
        } catch (error: Exception) {
            // DHCP may move the PC. Discovery is only a hint: retry once, with the original pin.
            if (connection.credential != null && !connection.authenticated && !connection.rediscovered &&
                error !is AuthenticationRejected && connection.active.get()) {
                update(connection) { it.copy(status = "Finding your remembered PC on this network…") }
                connection.closeSocket()
                val hosts = runCatching { scan({ connection.active.get() }) {} }.getOrDefault(emptyList())
                val relocated = hosts.firstOrNull { it.fingerprint == connection.fingerprint }
                    ?.let { WifiEndpoint.parse(it.address) }
                if (relocated != null && relocated != connection.endpoint && connection.active.get()) {
                    startConnection(Connection(relocated, "", connection.fingerprint, connection.credential, rediscovered = true), expected = connection)
                    return
                }
            }
            synchronized(lock) {
                if (current === connection && !closed) {
                    current = null
                    connection.active.set(false)
                    val message = connection.failure ?: connectionError(error)
                    mutableState.value = freshState(message).copy(pairingRequired =
                        (error is AuthenticationRejected && error.code == "pairingRequired") || "certificate changed" in message)
                }
            }
        } finally {
            connection.closeSocket()
            connection.commands.cancel()
        }
    }

    private fun writeFrame(connection: Connection, frame: String) {
        synchronized(connection.outputLock) {
            if (!connection.active.get()) return
            connection.writer?.run { write(frame); newLine(); flush() }
        }
    }

    private fun enqueue(connection: Connection, frame: String, clearPending: Boolean = false) {
        val overflow = synchronized(connection.queueLock) {
            if (!connection.active.get()) return
            if (clearPending) while (connection.commands.tryReceive().isSuccess) { /* discard old input */ }
            !connection.commands.trySend(frame).isSuccess
        }
        if (overflow) {
            synchronized(lock) {
                if (current === connection && !closed) {
                    current = null
                    connection.active.set(false)
                    mutableState.value = freshState("Connection could not keep up. Reconnect to continue.")
                }
            }
            shutdown(connection, release = true)
        }
    }

    /** Best-effort release has a 200ms limit; closing also makes the host release everything. */
    private fun shutdown(connection: Connection, release: Boolean, closeController: Boolean = false) {
        connection.active.set(false)
        synchronized(connection.queueLock) {
            while (connection.commands.tryReceive().isSuccess) { /* no input replay */ }
            connection.commands.close()
        }
        scope.launch {
            val deadline = launch { delay(200); connection.closeSocket() }
            try {
                if (release && connection.authenticated) synchronized(connection.outputLock) {
                    connection.writer?.run { write("{\"type\":\"release\"}"); newLine(); flush() }
                }
            } catch (_: Exception) {
                // Socket close and the host heartbeat are the final release safeguards.
            } finally {
                connection.closeSocket()
                deadline.cancel()
                if (closeController) scope.cancel()
            }
        }
    }

    private fun update(connection: Connection, transform: (WifiState) -> WifiState) {
        synchronized(lock) {
            if (current === connection && !closed && connection.active.get()) {
                mutableState.value = transform(mutableState.value)
            }
        }
    }

    private fun freshState(status: String) = WifiState(
        status = status, hosts = mutableState.value.hosts, isDiscovering = mutableState.value.isDiscovering,
        savedPc = savedCredential?.pc, loadingSavedPc = mutableState.value.loadingSavedPc,
    )

    private class Connection(val endpoint: WifiEndpoint, val pin: String, val fingerprint: String?,
        val credential: WifiCredential? = null, val rediscovered: Boolean = false) {
        val active = AtomicBoolean(true)
        val commands = Channel<String>(256)
        val queueLock = Any()
        val outputLock = Any()
        @Volatile var socket: SSLSocket? = null
        @Volatile var writer: BufferedWriter? = null
        @Volatile var authenticated = false
        @Volatile var lastReceivedNanos = System.nanoTime()
        @Volatile var failure: String? = null
        fun timedOut() = (System.nanoTime() - lastReceivedNanos) / 1_000_000 >= HEARTBEAT_TIMEOUT_MS
        fun closeSocket() { runCatching { socket?.close() } }
    }
}

private class AuthenticationRejected(val code: String, message: String) : IOException(message)

/** Bounded LAN hints. Never use a broadcast fingerprint in place of pinned TLS authentication. */
internal fun scanWifiHosts(alive: () -> Boolean, found: (WifiHost) -> Unit): List<WifiHost> {
    val hosts = linkedMapOf<String, WifiHost>()
    DatagramSocket().use { socket ->
        socket.broadcast = true
        socket.soTimeout = 250
        val targets = linkedSetOf(InetAddress.getByName("255.255.255.255"))
        NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.interfaceAddresses }?.mapNotNullTo(targets) { it.broadcast }
        val request = "VIRKEY_DISCOVER_V1".toByteArray(Charsets.US_ASCII)
        targets.forEach { target -> runCatching { socket.send(DatagramPacket(request, request.size, target, WIFI_PORT)) } }
        val until = System.nanoTime() + 2_000_000_000L
        while (alive() && System.nanoTime() < until) {
            val packet = DatagramPacket(ByteArray(2048), 2048)
            try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
            val host = parseDiscoveryResponse(String(packet.data, packet.offset, packet.length, Charsets.UTF_8),
                packet.address.hostAddress.orEmpty()) ?: continue
            if (hosts.size >= 64 && host.address !in hosts) continue
            hosts[host.address] = host
            found(host)
        }
    }
    return hosts.values.toList()
}

internal fun parseDiscoveryResponse(response: String, source: String): WifiHost? = runCatching {
    val json = JSONObject(response)
    if (json.opt("protocol") != 1) return null
    val port = json.opt("port") as? Int ?: return null
    if (port !in 1..65535) return null
    val endpoint = WifiEndpoint.parse("$source:$port")
    if (!endpoint.host.all { it in '0'..'9' || it == '.' }) return null
    val name = json.text("name").take(128).ifBlank { endpoint.host }
    val fingerprint = json.text("fingerprint").takeIf { it.isNotBlank() }?.let { normalizeFingerprint(it) }
    WifiHost("${endpoint.host}:${endpoint.port}", name, fingerprint)
}.getOrNull()

internal fun connectionError(error: Exception): String {
    if (error is SSLHandshakeException) {
        val causes = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.toList()
        if (causes.any { "certificate changed" in it }) return "PC certificate changed. Connect again and verify its new fingerprint."
        return "Secure connection failed. Check the PC address, date, and Virkey Host."
    }
    return error.message?.takeIf { it.isNotBlank() } ?: "Could not connect to Virkey Host"
}
