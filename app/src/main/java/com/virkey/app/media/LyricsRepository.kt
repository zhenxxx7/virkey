package com.virkey.app.media

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class LyricsResponse(val status: Int, val body: String = "")
internal fun interface LyricsHttpClient {
    suspend fun get(track: LyricsTrack): LyricsResponse
}

/** Only called after the visible lyrics pane has received explicit user opt-in. */
class LyricsRepository internal constructor(
    private val client: LyricsHttpClient,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : LyricsSource {
    constructor() : this(LrclibHttpClient())

    private data class Cached(val result: LyricsResult, val until: Long)
    private val mutex = Mutex()
    private val cache = LinkedHashMap<LyricsTrack, Cached>(24, 0.75f, true)
    private var lastRequest = Long.MIN_VALUE

    override suspend fun lookup(track: LyricsTrack): LyricsResult = mutex.withLock {
        if (track.title.isBlank() || track.artist.isBlank() ||
            listOf(track.title, track.artist, track.album).any { it.length > 512 }) return@withLock LyricsResult.NotFound
        cache[track]?.takeIf { it.until > clockMs() }?.let { return@withLock it.result }
        if (lastRequest != Long.MIN_VALUE) delay((1000L - (clockMs() - lastRequest)).coerceIn(0L, 1000L))
        lastRequest = clockMs()
        val result = try {
            withTimeoutOrNull(12_000L) {
                val response = client.get(track)
                when (response.status) {
                    200 -> withContext(Dispatchers.Default) { decode(response.body, track) }
                    404 -> LyricsResult.NotFound
                    429, 503 -> LyricsResult.Failed("Lyrics service is busy. Try again shortly.")
                    else -> LyricsResult.Failed("Lyrics service unavailable. Try again shortly.")
                }
            } ?: LyricsResult.Failed("Lyrics request timed out. Check your Internet connection.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            LyricsResult.Failed("Could not load lyrics. Check your Internet connection.")
        } catch (_: org.json.JSONException) {
            LyricsResult.Failed("The lyrics service returned an invalid response.")
        } catch (_: IllegalArgumentException) {
            LyricsResult.Failed("The lyrics service returned an invalid response.")
        }
        currentCoroutineContext().ensureActive()
        val lifetime = when (result) {
            is LyricsResult.Failed -> 30_000L
            LyricsResult.NotFound -> 300_000L
            else -> 1_800_000L
        }
        cache[track] = Cached(result, clockMs() + lifetime)
        while (cache.size > 24) cache.remove(cache.keys.first())
        result
    }

    internal fun decode(body: String, track: LyricsTrack): LyricsResult {
        require(body.length <= MAX_RESPONSE_BYTES)
        val json = JSONObject(body)
        // Do not present lyrics for a similarly named recording or a different duration.
        if (normalizedTrackText(json.optString("trackName")) != normalizedTrackText(track.title) ||
            normalizedTrackText(json.optString("artistName")) != normalizedTrackText(track.artist)) return LyricsResult.NotFound
        if (track.durationMs > 0) {
            val seconds = json.optDouble("duration", Double.NaN)
            if (!seconds.isFinite() || abs(seconds * 1000.0 - track.durationMs) > 3000.0) return LyricsResult.NotFound
        }
        if (json.optBoolean("instrumental", false)) return LyricsResult.Instrumental
        fun field(name: String) = if (json.isNull(name)) "" else json.optString(name, "")
        val document = LrcParser.parse(field("syncedLyrics"), field("plainLyrics"))
        return if (document.synced || document.plain.isNotBlank()) LyricsResult.Found(document) else LyricsResult.NotFound
    }
}

internal const val MAX_RESPONSE_BYTES = 256 * 1024

internal fun lyricsUrl(track: LyricsTrack): URL {
    val params = linkedMapOf("track_name" to track.title, "artist_name" to track.artist)
    if (track.album.isNotBlank()) params["album_name"] = track.album
    // The service accepts duration in seconds, between one second and one hour.
    if (track.durationMs in 1000L..3_600_000L) params["duration"] = (track.durationMs / 1000.0).toString()
    val query = params.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
    return URL("https://lrclib.net/api/get?$query")
}

internal class LrclibHttpClient(
    private val connections: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : LyricsHttpClient {
    override suspend fun get(track: LyricsTrack): LyricsResponse = coroutineScope {
        val connection = connections(lyricsUrl(track))
        connection.connectTimeout = 6000
        connection.readTimeout = 6000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Virkey/0.6.0 (+https://github.com/zhenxxx7/virkey)")
        suspendCancellableCoroutine { continuation ->
            val worker = launch(Dispatchers.IO) {
                try {
                    val status = connection.responseCode
                    val body = if (status == 200) connection.inputStream.use { input ->
                        readLyricsBody(input) { currentCoroutineContext().ensureActive() }
                    } else ""
                    continuation.resume(LyricsResponse(status, body))
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                } finally {
                    connection.disconnect()
                }
            }
            continuation.invokeOnCancellation { connection.disconnect(); worker.cancel() }
        }
    }
}

internal suspend fun readLyricsBody(input: InputStream, checkActive: suspend () -> Unit = {}): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        checkActive()
        val count = input.read(buffer)
        if (count < 0) break
        if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException("Lyrics response too large")
        output.write(buffer, 0, count)
    }
    return output.toString("UTF-8")
}
