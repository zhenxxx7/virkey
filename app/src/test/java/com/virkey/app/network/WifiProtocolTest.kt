package com.virkey.app.network

import android.graphics.Bitmap
import com.virkey.app.ui.RemoteAction
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WifiProtocolTest {
    @Test fun endpointNormalizesIpv4HostnameAndPort() {
        assertEquals(WifiEndpoint("192.168.1.2"), WifiEndpoint.parse(" 192.168.001.002 "))
        assertEquals(WifiEndpoint("office-pc.local", 443), WifiEndpoint.parse("OFFICE-PC.local.:443"))
        assertEquals(WifiEndpoint("localhost", 65535), WifiEndpoint.parse("localhost:65535"))
    }

    @Test fun endpointRejectsUrlsAmbiguousNumbersAndInvalidPorts() {
        listOf("", "https://pc", "pc/path", "user@pc", "pc?x", "pc#x", "pc name",
            "127.1", "256.1.2.3", "1.2.3.-1", "1234", "::1", "[::1]:49372",
            "-pc", "pc-", "pc..local", "pc:0", "pc:65536", "pc:", "pc:+80",
            "pc:8.0", "pc:000080", "a".repeat(64) + ".local").forEach { address ->
            assertThrows(address, IllegalArgumentException::class.java) { WifiEndpoint.parse(address) }
        }
    }

    @Test fun pinAndFingerprintRequireFullExactForms() {
        assertEquals("012345", normalizePin(" 012345 "))
        listOf("12345", "1234567", "abcdef", "123 456", "１２３４５６").forEach {
            assertThrows(IllegalArgumentException::class.java) { normalizePin(it) }
        }
        val fingerprint = "ab".repeat(32)
        assertEquals(fingerprint, normalizeFingerprint("AB:".repeat(31) + "AB"))
        listOf("ab".repeat(6), "ab".repeat(31), "ab".repeat(33), "zz".repeat(32)).forEach {
            assertThrows(IllegalArgumentException::class.java) { normalizeFingerprint(it) }
        }
    }

    @Test fun inspectionCannotAuthenticateEvenAfterValidCertificate() {
        val trust = PairingTrustManager(null)
        assertThrows(IllegalStateException::class.java) { authenticationFrame("123456", trust) }
        trust.checkServerTrusted(arrayOf(TestTlsIdentity.certificate), "RSA")
        assertEquals(TestTlsIdentity.fingerprint, trust.peerFingerprint)
        assertFalse(trust.canAuthenticate)
        assertThrows(IllegalStateException::class.java) { authenticationFrame("123456", trust) }
    }

    @Test fun onlyConfirmedFullCertificateAuthorizesPin() {
        val trust = PairingTrustManager(TestTlsIdentity.fingerprint)
        assertThrows(IllegalStateException::class.java) { authenticationFrame("123456", trust) }
        trust.checkServerTrusted(arrayOf(TestTlsIdentity.certificate), "RSA")
        val auth = JSONObject(authenticationFrame("012345", trust))
        assertEquals("auth", auth.getString("type"))
        assertEquals(1, auth.getInt("protocol"))
        assertEquals("012345", auth.getString("pin"))
        assertEquals(4, auth.length())
    }

    @Test fun certificateMismatchAndMissingCertificateNeverAuthorizePin() {
        val trust = PairingTrustManager("00".repeat(32))
        assertThrows(CertificateException::class.java) {
            trust.checkServerTrusted(arrayOf(TestTlsIdentity.certificate), "RSA")
        }
        assertFalse(trust.canAuthenticate)
        assertThrows(IllegalStateException::class.java) { authenticationFrame("123456", trust) }
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(emptyArray(), "RSA") }
        assertThrows(CertificateException::class.java) {
            trust.checkClientTrusted(arrayOf(TestTlsIdentity.certificate), "RSA")
        }
    }

    @Test fun failedRevalidationClearsPreviousTrust() {
        val trust = PairingTrustManager(TestTlsIdentity.fingerprint)
        trust.checkServerTrusted(arrayOf(TestTlsIdentity.certificate), "RSA")
        assertTrue(trust.canAuthenticate)
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(emptyArray(), "RSA") }
        assertFalse(trust.canAuthenticate)
        assertNull(trust.peerFingerprint)
    }

    @Test fun boundedFramesPreserveCrLfAndRejectTruncationOrOverflow() {
        val reader = BufferedReader(StringReader("1234\r\nnext\n"))
        assertEquals("1234", readFrame(reader, { true }, { false }, limit = 5))
        assertEquals("next", readFrame(reader, { true }, { false }, limit = 5))
        assertNull(readFrame(reader, { true }, { false }, limit = 5))
        assertThrows(IOException::class.java) {
            readFrame(BufferedReader(StringReader("123456\n")), { true }, { false }, limit = 5)
        }
        assertThrows(IOException::class.java) {
            readFrame(BufferedReader(StringReader("unfinished")), { true }, { false })
        }
    }

    @Test fun partialFrameSurvivesSocketReadTimeout() {
        val input = object : Reader() {
            val chunks = arrayOf("first", null, " second\n")
            var index = 0
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                if (index == chunks.size) return -1
                val chunk = chunks[index++] ?: throw SocketTimeoutException()
                chunk.toCharArray().copyInto(buffer, offset)
                return chunk.length
            }
            override fun close() = Unit
        }
        assertEquals("first second", readFrame(BufferedReader(input), { true }, { false }))
    }

    @Test fun heartbeatTimeoutAndCancellationStopPartialReads() {
        assertThrows(SocketTimeoutException::class.java) {
            readFrame(BufferedReader(StringReader("data")), { true }, { true })
        }
        assertNull(readFrame(BufferedReader(StringReader("data")), { false }, { false }))
    }

    @Test fun discoveryUsesSenderIpAndValidatesProtocolAndPort() {
        val response = """{"protocol":1,"port":49372,"name":"Office","address":"evil.example"}"""
        assertEquals(WifiHost("192.168.1.25:49372", "Office"), parseDiscoveryResponse(response, "192.168.1.25"))
        assertNull(parseDiscoveryResponse(response, "evil.example"))
        assertNull(parseDiscoveryResponse(response, "::1"))
        listOf(
            """{"protocol":"1","port":49372}""",
            """{"protocol":2,"port":49372}""",
            """{"protocol":1,"port":65536}""",
            """{"protocol":1,"port":"49372"}""",
            """{"protocol":1,"port":0}""",
            "{broken",
        ).forEach { assertNull(parseDiscoveryResponse(it, "192.168.1.25")) }
        assertEquals("192.168.1.25", parseDiscoveryResponse("""{"protocol":1,"port":80}""", "192.168.1.25")?.name)
    }

    @Test fun inputFramesPreservePressReleaseMotionAndConsumerOrdering() {
        val actions = listOf(
            RemoteAction.KeyDown(4), RemoteAction.KeyUp(4),
            RemoteAction.MediaDown(205), RemoteAction.MediaUp(205),
            RemoteAction.MouseDown(1), RemoteAction.MouseUp(1),
            RemoteAction.MovePointer(-4, 8), RemoteAction.Scroll(-2), RemoteAction.ReleaseAll,
        )
        val frames = actions.map { JSONObject(requireNotNull(inputFrame(it))) }
        assertEquals(listOf("key", "key", "mediaKey", "mediaKey", "button", "button", "move", "scroll", "release"),
            frames.map { it.getString("type") })
        (0..5).forEach { assertEquals(it % 2 == 0, frames[it].getBoolean("down")) }
        assertEquals(4, frames[0].getInt("usage"))
        assertEquals(205, frames[2].getInt("usage"))
        assertEquals(1, frames[4].getInt("button"))
        assertEquals(-4, frames[6].getInt("dx"))
        assertEquals(8, frames[6].getInt("dy"))
        assertEquals(-2, frames[7].getInt("amount"))
        assertEquals(1, frames[8].length())
    }

    @Test fun connectionUiActionsNeverSerializeAsRemoteInput() {
        listOf(RemoteAction.Connect("pc"), RemoteAction.Disconnect, RemoteAction.EnableBluetooth,
            RemoteAction.RequestPermissions, RemoteAction.PairNewDevice, RemoteAction.RefreshDevices)
            .forEach { assertNull(inputFrame(it)) }
    }

    @Test fun mediaCommandsRequireCurrentSessionTrackAndCapability() {
        val playing = NowPlayingState(available = true, sessionId = "session", trackId = "track")
        assertNull(mediaFrame(playing, "play", "session", "track"))
        assertNull(mediaFrame(playing.copy(canPlay = true), "play", "stale", "track"))
        assertNull(mediaFrame(playing.copy(canPlay = true), "play", "session", "old"))
        assertNull(mediaFrame(playing.copy(available = false, canPlay = true), "play", "session", "track"))
        assertNull(mediaFrame(playing.copy(canPlay = true), "launch", "session", "track"))
        val json = JSONObject(requireNotNull(mediaFrame(playing.copy(canPlay = true), "play", "session", "track")))
        assertEquals("media", json.getString("type"))
        assertEquals("play", json.getString("command"))
        assertEquals("session", json.getString("sessionId"))
        assertEquals("track", json.getString("trackId"))
        assertEquals(4, json.length())
    }

    @Test fun seekShuffleAndRepeatFramesValidatePayloads() {
        val playing = NowPlayingState(available = true, sessionId = "s", trackId = "t",
            durationMs = 10000, canSeek = true, canShuffle = true, canRepeat = true)
        assertNull(mediaFrame(playing, "seek", "s", "t", positionMs = -1))
        assertNull(mediaFrame(playing, "seek", "s", "t", positionMs = 10001))
        assertEquals(10000, JSONObject(requireNotNull(mediaFrame(playing, "seek", "s", "t", positionMs = 10000))).getLong("positionMs"))
        assertTrue(JSONObject(requireNotNull(mediaFrame(playing, "shuffle", "s", "t", enabled = true))).getBoolean("enabled"))
        assertEquals("one", JSONObject(requireNotNull(mediaFrame(playing, "repeat", "s", "t", mode = "one"))).getString("mode"))
        assertNull(mediaFrame(playing, "repeat", "s", "t", mode = "invalid"))
    }

    @Test fun artworkRetainedOnlyForSameSessionTrackAndArtworkId() {
        val parser = NowPlayingParser()
        val first = parser.parse(track().put("artwork", encodedBitmap(2, 2)))
        assertNotNull(first.artwork)
        assertSame(first.artwork, parser.parse(track()).artwork)
        assertNull(parser.parse(track().put("trackId", "new-track")).artwork)
        assertNull(parser.parse(track().put("sessionId", "other-session")).artwork)
        assertNull(parser.parse(track().put("artworkId", "other-art")).artwork)
    }

    @Test fun explicitMissingInvalidAndUnavailableArtworkClearOldImage() {
        val parser = NowPlayingParser()
        parser.parse(track().put("artwork", encodedBitmap(2, 2)))
        assertNull(parser.parse(track().put("artwork", "not-base64")).artwork)
        parser.parse(track().put("artwork", encodedBitmap(2, 2)))
        assertNull(parser.parse(track().put("artworkId", "")).artwork)
        parser.parse(track().put("artwork", encodedBitmap(2, 2)))
        val unavailable = parser.parse(JSONObject().put("available", false))
        assertEquals(NowPlayingState(), unavailable)
        assertNull(parser.parse(track()).artwork)
    }

    @Test fun artworkDecodeEnforcesBytesAndDimensions() {
        assertNotNull(decodeArtwork(encodedBitmap(1024, 1)))
        assertNull(decodeArtwork(encodedBitmap(1025, 1)))
        assertNull(decodeArtwork(encodedBitmap(1, 1025)))
        assertNull(decodeArtwork(Base64.getEncoder().encodeToString(ByteArray(MAX_ARTWORK_BYTES + 1))))
        assertNull(decodeArtwork("x".repeat(((MAX_ARTWORK_BYTES + 2) / 3) * 4 + 1)))
        assertNull(decodeArtwork(Base64.getEncoder().encodeToString("not an image".toByteArray())))
    }

    @Test fun missingAndWrongTypedMediaMetadataNeverInventsTrackData() {
        val parsed = NowPlayingParser().parse(JSONObject()
            .put("available", true).put("title", 123).put("playing", "true")
            .put("durationMs", -1).put("positionMs", 1.5).put("repeat", "unexpected"))
        assertEquals("", parsed.title)
        assertEquals("", parsed.artist)
        assertFalse(parsed.playing)
        assertEquals(0, parsed.durationMs)
        assertEquals(0, parsed.positionMs)
        assertEquals("off", parsed.repeat)
    }

    private fun track() = JSONObject().put("available", true).put("sessionId", "session")
        .put("trackId", "track").put("artworkId", "art")

    private fun encodedBitmap(width: Int, height: Int): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                Base64.getEncoder().encodeToString(it.toByteArray())
            }
        } finally { bitmap.recycle() }
    }
}
