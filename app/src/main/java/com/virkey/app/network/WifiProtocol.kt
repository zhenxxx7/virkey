package com.virkey.app.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.virkey.app.ui.RemoteAction
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import javax.net.ssl.X509TrustManager

internal const val WIFI_PORT = 49372
internal const val MAX_FRAME_CHARS = 524288
internal const val MAX_ARTWORK_BYTES = 262144
internal const val MAX_ARTWORK_DIMENSION = 1024
internal const val HEARTBEAT_TIMEOUT_MS = 4000L

internal data class WifiEndpoint(val host: String, val port: Int = WIFI_PORT) {
    companion object {
        fun parse(address: String): WifiEndpoint {
            val raw = address.trim()
            require(raw.isNotEmpty() && raw.length <= 260) { "Enter your PC's IP address or hostname" }
            require(raw.count { it == ':' } <= 1 && raw.none { it.isWhitespace() || it in "/\\@?#" }) {
                "Use an IPv4 address or hostname, optionally followed by :port"
            }
            val parts = raw.split(':')
            val port = if (parts.size == 2) {
                require(parts[1].matches(Regex("[0-9]{1,5}"))) { "Port must be between 1 and 65535" }
                parts[1].toInt()
            } else WIFI_PORT
            require(port in 1..65535) { "Port must be between 1 and 65535" }
            val host = parts[0].removeSuffix(".").lowercase(Locale.ROOT)
            require(host.isNotEmpty() && host.length <= 253) { "Enter a valid PC hostname" }
            val normalized = if (host.all { it.isDigit() || it == '.' }) {
                val octets = host.split('.')
                require(octets.size == 4 && octets.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 }) {
                    "Enter a valid IPv4 address"
                }
                octets.joinToString(".") { it.toInt().toString() }
            } else {
                require(host.split('.').all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) }) {
                    "Enter a valid PC hostname"
                }
                host
            }
            return WifiEndpoint(normalized, port)
        }
    }
}

internal fun normalizePin(pin: String): String = pin.trim().also {
    require(it.matches(Regex("[0-9]{6}"))) { "Enter the six-digit PIN shown by Virkey Host" }
}

internal fun normalizeFingerprint(fingerprint: String): String = fingerprint
    .replace(":", "").replace(" ", "").lowercase(Locale.ROOT).also {
        require(it.matches(Regex("[0-9a-f]{64}"))) { "Confirm the complete PC certificate fingerprint" }
    }

internal fun certificateFingerprint(certificate: X509Certificate): String = MessageDigest
    .getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

/** Inspection trusts only enough to read the certificate. It never authorizes a PIN. */
internal class PairingTrustManager(private val expectedFingerprint: String?) : X509TrustManager {
    @Volatile var peerFingerprint: String? = null
        private set

    val canAuthenticate: Boolean
        get() = expectedFingerprint != null && peerFingerprint == expectedFingerprint

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        peerFingerprint = null
        val leaf = chain?.firstOrNull() ?: throw CertificateException("PC did not present a certificate")
        leaf.checkValidity()
        val actual = certificateFingerprint(leaf)
        if (expectedFingerprint != null && !MessageDigest.isEqual(
                actual.toByteArray(Charsets.US_ASCII), expectedFingerprint.toByteArray(Charsets.US_ASCII),
            )
        ) throw CertificateException("PC certificate changed. Verify the new fingerprint.")
        peerFingerprint = actual
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not accepted")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal fun authenticationFrame(pin: String, trust: PairingTrustManager): String {
    check(trust.canAuthenticate) { "Certificate confirmation is required before sending a PIN" }
    return JSONObject().put("type", "auth").put("protocol", 1)
        .put("pin", normalizePin(pin)).put("remember", true).put("name", "Virkey tablet").toString()
}

internal fun rememberedAuthenticationFrame(credential: WifiCredential, trust: PairingTrustManager): String {
    check(trust.canAuthenticate && trust.peerFingerprint == credential.pc.fingerprint) { "Saved PC certificate must match before reconnecting" }
    return JSONObject().put("type", "auth").put("protocol", 1).put("name", "Virkey tablet")
        .put("deviceId", normalizedHex(credential.deviceId, 32)).put("token", normalizedHex(credential.token, 64)).toString()
}

internal fun inputFrame(action: RemoteAction): String? {
    val json = JSONObject()
    when (action) {
        is RemoteAction.KeyDown -> json.put("type", "key").put("usage", action.usage).put("down", true)
        is RemoteAction.KeyUp -> json.put("type", "key").put("usage", action.usage).put("down", false)
        is RemoteAction.MediaDown -> json.put("type", "mediaKey").put("usage", action.usage).put("down", true)
        is RemoteAction.MediaUp -> json.put("type", "mediaKey").put("usage", action.usage).put("down", false)
        is RemoteAction.MouseDown -> json.put("type", "button").put("button", action.button).put("down", true)
        is RemoteAction.MouseUp -> json.put("type", "button").put("button", action.button).put("down", false)
        is RemoteAction.MovePointer -> json.put("type", "move").put("dx", action.dx).put("dy", action.dy)
        is RemoteAction.Scroll -> json.put("type", "scroll").put("amount", action.amount)
        RemoteAction.ReleaseAll -> json.put("type", "release")
        else -> return null
    }
    return json.toString()
}

/** Validate both track identity and capability before putting a media command on the wire. */
internal fun mediaFrame(
    playing: NowPlayingState,
    command: String,
    sessionId: String,
    trackId: String,
    positionMs: Long = 0,
    enabled: Boolean = false,
    mode: String = "off",
): String? {
    if (!playing.available || sessionId.isEmpty() || trackId.isEmpty() ||
        playing.sessionId != sessionId || playing.trackId != trackId
    ) return null
    val allowed = when (command) {
        "play" -> playing.canPlay
        "pause" -> playing.canPause
        "previous" -> playing.canPrevious
        "next" -> playing.canNext
        "stop" -> playing.canStop
        "seek" -> playing.canSeek && positionMs >= 0 &&
            (playing.durationMs <= 0 || positionMs <= playing.durationMs)
        "shuffle" -> playing.canShuffle
        "repeat" -> playing.canRepeat && mode in setOf("off", "all", "one")
        else -> false
    }
    if (!allowed) return null
    return JSONObject().put("type", "media").put("command", command)
        .put("sessionId", sessionId).put("trackId", trackId).apply {
            when (command) {
                "seek" -> put("positionMs", positionMs)
                "shuffle" -> put("enabled", enabled)
                "repeat" -> put("mode", mode)
            }
        }.toString()
}

/** Keeps partial frames across socket read timeouts without allowing unbounded lines. */
internal fun readFrame(
    reader: BufferedReader,
    alive: () -> Boolean,
    timedOut: () -> Boolean,
    limit: Int = MAX_FRAME_CHARS,
): String? {
    require(limit > 0)
    val frame = StringBuilder()
    while (alive()) {
        if (timedOut()) throw SocketTimeoutException("Virkey Host stopped responding")
        val char = try { reader.read() } catch (_: SocketTimeoutException) { continue }
        if (char == -1) {
            if (frame.isNotEmpty()) throw IOException("Incomplete message from Virkey Host")
            return null
        }
        if (char == '\n'.code) return frame.toString().removeSuffix("\r")
        if (frame.length >= limit) throw IOException("Virkey Host message exceeds the size limit")
        frame.append(char.toChar())
    }
    return null
}

internal fun JSONObject.text(name: String): String = (opt(name) as? String).orEmpty()
internal fun JSONObject.flag(name: String): Boolean = opt(name) as? Boolean ?: false
internal fun JSONObject.nonnegativeLong(name: String): Long {
    val value = opt(name)
    return when (value) {
        is Long -> value.coerceAtLeast(0)
        is Int -> value.toLong().coerceAtLeast(0)
        else -> 0L
    }
}

internal class NowPlayingParser {
    private var previous = NowPlayingState()
    private var artworkId = ""

    fun parse(json: JSONObject): NowPlayingState {
        if (!json.flag("available")) {
            artworkId = ""
            return NowPlayingState().also { previous = it }
        }
        val session = json.text("sessionId")
        val track = json.text("trackId")
        val newArtworkId = json.text("artworkId")
        val sameArtwork = session == previous.sessionId && track == previous.trackId &&
            newArtworkId.isNotEmpty() && newArtworkId == artworkId
        val image = when {
            newArtworkId.isEmpty() -> null
            json.has("artwork") -> decodeArtwork(json.text("artwork"))
            sameArtwork -> previous.artwork
            else -> null
        }
        artworkId = newArtworkId
        return NowPlayingState(
            available = true, sessionId = session, trackId = track,
            title = json.text("title"), artist = json.text("artist"),
            album = json.text("album"), player = json.text("player"),
            playing = json.flag("playing"), positionMs = json.nonnegativeLong("positionMs"),
            durationMs = json.nonnegativeLong("durationMs"), canPlay = json.flag("canPlay"),
            canPause = json.flag("canPause"), canPrevious = json.flag("canPrevious"),
            canNext = json.flag("canNext"), canStop = json.flag("canStop"),
            canSeek = json.flag("canSeek"), canShuffle = json.flag("canShuffle"),
            canRepeat = json.flag("canRepeat"), shuffle = json.flag("shuffle"),
            repeat = json.text("repeat").takeIf { it in setOf("off", "all", "one") } ?: "off",
            artwork = image,
        ).also { previous = it }
    }
}

internal fun decodeArtwork(encoded: String): Bitmap? {
    if (encoded.isEmpty() || encoded.length > ((MAX_ARTWORK_BYTES + 2) / 3) * 4) return null
    return try {
        val bytes = Base64.getDecoder().decode(encoded)
        if (bytes.size > MAX_ARTWORK_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ARTWORK_DIMENSION || bounds.outHeight !in 1..MAX_ARTWORK_DIMENSION) return null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: IllegalArgumentException) {
        null
    }
}
