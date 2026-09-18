package com.virkey.app.media

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LyricsRepositoryTest {
    private val track = LyricsTrack("Sample track", "Sample artist", "Sample album", 180000L)
    private fun body(plain: String = "Original test line", synced: String = "[00:10]Original test line") = JSONObject()
        .put("trackName", track.title).put("artistName", track.artist).put("duration", 180.0)
        .put("plainLyrics", plain).put("syncedLyrics", synced)

    @Test fun requestUsesHttpsAndEncodedMetadataWithSecondsNotMilliseconds() {
        val url = lyricsUrl(track.copy(title = "A & B / ?", artist = "音楽"))
        assertEquals("https", url.protocol)
        assertEquals("lrclib.net", url.host)
        assertTrue(url.query.contains("track_name=A+%26+B+%2F+%3F"))
        assertTrue(url.query.contains("artist_name=%E9%9F%B3%E6%A5%BD"))
        assertTrue(url.query.contains("duration=180.0"))
        assertFalse(lyricsUrl(track.copy(album = "", durationMs = 0)).query.contains("album_name"))
        assertFalse(lyricsUrl(track.copy(durationMs = 3_600_001)).query.contains("duration="))
    }

    @Test fun syncedResultIsPreferredAndRepeatedLookupsUseCache() = runBlocking {
        var calls = 0
        val repo = LyricsRepository(LyricsHttpClient { calls++; LyricsResponse(200, body().toString()) })
        val result = repo.lookup(track) as LyricsResult.Found
        assertTrue(result.document.synced)
        assertEquals(result, repo.lookup(track))
        assertEquals(1, calls)
    }

    @Test fun rejectsDifferentTitleArtistAndRecordingDuration() {
        val repo = LyricsRepository(LyricsHttpClient { error("Not called") })
        assertEquals(LyricsResult.NotFound, repo.decode(body().put("trackName", "Other").toString(), track))
        assertEquals(LyricsResult.NotFound, repo.decode(body().put("artistName", "Other").toString(), track))
        assertEquals(LyricsResult.NotFound, repo.decode(body().put("duration", 190).toString(), track))
        assertEquals(LyricsResult.NotFound, repo.decode(body().put("duration", JSONObject.NULL).toString(), track))
    }

    @Test fun matchingToleratesCaseAndPunctuationAndSmallDurationRounding() {
        val repo = LyricsRepository(LyricsHttpClient { error("Not called") })
        assertTrue(repo.decode(body().put("trackName", "SAMPLE TRACK!").put("duration", 179.5).toString(), track) is LyricsResult.Found)
    }

    @Test fun plainLyricsAreUsedWhenNoSyncedLyricsExist() {
        val repo = LyricsRepository(LyricsHttpClient { error("Not called") })
        val result = repo.decode(body().put("syncedLyrics", JSONObject.NULL).toString(), track) as LyricsResult.Found
        assertFalse(result.document.synced)
        assertEquals("Original test line", result.document.plain)
    }

    @Test fun instrumentalAndEmptyResultsAreDistinct() {
        val repo = LyricsRepository(LyricsHttpClient { error("Not called") })
        assertEquals(LyricsResult.Instrumental, repo.decode(body().put("instrumental", true).toString(), track))
        assertEquals(LyricsResult.NotFound, repo.decode(body("", "").toString(), track))
    }

    @Test fun unavailableMetadataNeverCallsNetwork() = runBlocking {
        val repo = LyricsRepository(LyricsHttpClient { error("Must not request") })
        assertEquals(LyricsResult.NotFound, repo.lookup(track.copy(artist = "")))
        assertEquals(LyricsResult.NotFound, repo.lookup(track.copy(title = "a".repeat(513))))
    }

    @Test fun failuresAndMissesAreBoundedAndDoNotRetryAutomatically() = runBlocking {
        for (status in listOf(404, 429, 503, 500, 302)) {
            var calls = 0
            val repo = LyricsRepository(LyricsHttpClient { calls++; LyricsResponse(status) })
            val result = repo.lookup(track)
            if (status == 404) assertEquals(LyricsResult.NotFound, result) else assertTrue(result is LyricsResult.Failed)
            assertEquals(result, repo.lookup(track))
            assertEquals(1, calls)
        }
    }

    @Test fun malformedAndOversizedResponsesBecomeFriendlyErrors() = runBlocking {
        assertTrue(LyricsRepository(LyricsHttpClient { LyricsResponse(200, "bad json") }).lookup(track) is LyricsResult.Failed)
        assertTrue(LyricsRepository(LyricsHttpClient { LyricsResponse(200, body("a".repeat(80_001), "").toString()) })
            .lookup(track) is LyricsResult.Failed)
        assertTrue(LyricsRepository(LyricsHttpClient { throw IOException("private diagnostic") }).lookup(track) is LyricsResult.Failed)
    }

    @Test fun failedLookupCanBeRetriedAfterCooldown() = runBlocking {
        var now = 0L
        var calls = 0
        val repo = LyricsRepository(LyricsHttpClient { calls++; LyricsResponse(if (calls == 1) 503 else 200, body().toString()) }, { now })
        assertTrue(repo.lookup(track) is LyricsResult.Failed)
        now = 31_000
        assertTrue(repo.lookup(track) is LyricsResult.Found)
        assertEquals(2, calls)
    }

    @Test fun cacheEvictsOldestTrackAfter24Entries() = runBlocking {
        var calls = 0
        var now = 0L
        val repo = LyricsRepository(LyricsHttpClient { calls++; LyricsResponse(404) }, { now })
        for (index in 0..24) { now += 1500; repo.lookup(track.copy(title = "Track $index")) }
        now += 1500
        repo.lookup(track.copy(title = "Track 0"))
        assertEquals(26, calls)
    }

    @Test fun cancelledLookupIsNotCachedAndDoesNotBlockNextSong() = runBlocking {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        var now = 0L
        val repo = LyricsRepository(LyricsHttpClient {
            calls++
            if (calls == 1) { started.complete(Unit); awaitCancellation() }
            LyricsResponse(200, body().toString())
        }, { now })
        val pending = launch { repo.lookup(track) }
        started.await()
        pending.cancelAndJoin()
        now = 5000
        assertTrue(repo.lookup(track) is LyricsResult.Found)
        assertEquals(2, calls)
    }

    @Test fun transportUsesTimeoutsDisablesRedirectsAndIdentifiesApp() = runBlocking {
        val fake = FakeConnection(body().toString().byteInputStream())
        val response = LrclibHttpClient { fake }.get(track)
        assertEquals(200, response.status)
        assertEquals(6000, fake.connectTimeout)
        assertEquals(6000, fake.readTimeout)
        assertFalse(fake.instanceFollowRedirects)
        assertTrue(fake.getRequestProperty("User-Agent").startsWith("Virkey/"))
        assertTrue(fake.closed)
    }

    @Test fun cancellingTransportDisconnectsPendingRead() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val fake = object : FakeConnection(object : InputStream() {
            override fun read(): Int { started.countDown(); released.await(3, TimeUnit.SECONDS); return -1 }
        }) {
            override fun disconnect() { super.disconnect(); released.countDown() }
        }
        val pending = async { LrclibHttpClient { fake }.get(track) }
        // Yield to the async coroutine before waiting for its IO worker.
        kotlinx.coroutines.yield()
        assertTrue(started.await(3, TimeUnit.SECONDS))
        pending.cancelAndJoin()
        assertTrue(fake.closed)
        assertEquals(0L, released.count)
    }

    @Test fun responseReaderRejectsOversizedBodies() = runBlocking {
        try {
            readLyricsBody(ByteArrayInputStream(ByteArray(MAX_RESPONSE_BYTES + 1)))
            fail("Oversized response accepted")
        } catch (_: IOException) { /* Expected; do not allocate an unbounded response. */ }
        assertEquals("Original text", readLyricsBody("Original text".byteInputStream()))
    }

    private open class FakeConnection(private val input: InputStream) : HttpURLConnection(URL("https://lrclib.net/api/get")) {
        @Volatile var closed = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { closed = true }
        override fun getResponseCode() = 200
        override fun getInputStream() = input
    }
}
