package com.virkey.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.virkey.app.input.MediaKeys
import com.virkey.app.media.LrcParser
import com.virkey.app.media.LyricsDocument
import com.virkey.app.media.LyricsResult
import com.virkey.app.media.LyricsSource
import com.virkey.app.network.NowPlayingState
import com.virkey.app.network.WifiHost
import com.virkey.app.network.WifiState
import com.virkey.app.network.SavedWifiPc
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WifiMediaUiTest {
    @get:Rule val compose = createComposeRule()

    private val connected = RemoteUiState(isConnected = true, numLock = true)
    private val media = NowPlayingState(
        available = true, sessionId = "test-player", trackId = "test-track", title = "Preview track",
        artist = "Sample artist", album = "Preview album", player = "Media preview", playing = true,
        positionMs = 83000L, durationMs = 240000L, canPlay = true, canPause = true,
        canPrevious = true, canNext = true, canStop = true, canSeek = true, canShuffle = true, canRepeat = true,
    )

    @Test fun liveTransportUsesSessionCommandsWithoutSendingHidTwice() {
        val input = mutableListOf<RemoteAction>()
        val commands = mutableListOf<List<Any>>()
        compose.setContent {
            NumpadMediaPanel(connected, input::add, Modifier.height(400.dp), media,
                { command, position, enabled, mode -> commands.add(listOf(command, position, enabled, mode)) }, isWifi = true)
        }
        compose.onNodeWithContentDescription("Media Play / Pause").performTouchInput { click() }
        compose.onNodeWithContentDescription("Media Next").performClick()
        compose.onNodeWithContentDescription("Media Shuffle").performClick()
        compose.onNodeWithContentDescription("Media Repeat").performClick()
        compose.onNodeWithContentDescription("Media Volume up").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(
                listOf("pause", 0L, false, "off"), listOf("next", 0L, false, "off"),
                listOf("shuffle", 0L, true, "off"), listOf("repeat", 0L, false, "all"),
            ), commands)
            assertEquals(listOf(RemoteAction.MediaDown(MediaKeys.VOLUME_UP), RemoteAction.MediaUp(MediaKeys.VOLUME_UP)), input)
        }
    }

    @Test fun unsupportedPlayerActionsStayDisabledAndDoNotFallBackToHid() {
        val input = mutableListOf<RemoteAction>()
        val commands = mutableListOf<String>()
        compose.setContent {
            NumpadMediaPanel(connected, input::add, Modifier.height(400.dp),
                media.copy(canPause = false, canSeek = false, canNext = false, canShuffle = false, canRepeat = false),
                { command, _, _, _ -> commands.add(command) }, isWifi = true)
        }
        compose.onNodeWithContentDescription("Media Play / Pause").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithContentDescription("Media Next").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("media_seek").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Media Shuffle").assertDoesNotExist()
        compose.onNodeWithContentDescription("Media Repeat").assertDoesNotExist()
        compose.runOnIdle { assertTrue(input.isEmpty()); assertTrue(commands.isEmpty()) }
    }

    @Test fun seekEmitsOnePositionWhenTouchEnds() {
        val seeks = mutableListOf<Long>()
        compose.setContent {
            NumpadMediaPanel(connected, {}, Modifier.height(400.dp), media,
                { command, position, _, _ -> if (command == "seek") seeks.add(position) }, isWifi = true)
        }
        compose.onNodeWithTag("media_seek").performTouchInput { click(center.copy(x = width * 0.75f)) }
        compose.runOnIdle {
            assertEquals(1, seeks.size)
            assertTrue(seeks.single() in 150000L..220000L)
        }
    }

    @Test fun losingSessionClearsArtworkAndMetadata() {
        val state = mutableStateOf(media.copy(artwork = sampleArtwork()))
        compose.setContent { NumpadMediaPanel(connected, {}, Modifier.height(400.dp), state.value, isWifi = true) }
        compose.onNodeWithTag("album_artwork").assertIsDisplayed()
        compose.onNodeWithText("Preview track").assertIsDisplayed()
        compose.runOnIdle { state.value = NowPlayingState() }
        compose.onNodeWithTag("album_artwork").assertDoesNotExist()
        compose.onNodeWithText("Preview track").assertDoesNotExist()
        compose.onNodeWithText("No media playing").assertIsDisplayed()
    }

    @Test fun liveMediaRendersArtworkTimelineAndNumpadPreview() {
        var renderedView: View? = null
        val art = sampleArtwork()
        compose.setContent {
            renderedView = LocalView.current
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF101315)).padding(28.dp), contentAlignment = Alignment.Center) {
                    NumpadMediaPanel(connected, {}, Modifier.fillMaxWidth().height(400.dp), media.copy(artwork = art), isWifi = true)
                }
            }
        }
        compose.onNodeWithText("Preview track").assertIsDisplayed()
        compose.onNodeWithText("Preview album").assertIsDisplayed()
        compose.onNodeWithText("1:23").assertIsDisplayed()
        compose.onNodeWithText("4:00").assertIsDisplayed()
        compose.onNodeWithTag("album_artwork").assertIsDisplayed()
        compose.onNodeWithContentDescription("Numpad Enter").assertIsDisplayed()
        compose.runOnIdle {
            val view = requireNotNull(renderedView)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/outputs/previews/virkey-live-media.png")
            requireNotNull(output.parentFile).mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    @Config(qualifiers = "w960dp-h600dp-land-mdpi")
    fun compactPanelKeepsTimelineAndPlaybackWithinBounds() {
        compose.setContent {
            NumpadMediaPanel(connected, {}, Modifier.fillMaxWidth().height(260.dp), media.copy(artwork = sampleArtwork()), isWifi = true)
        }
        compose.onNodeWithTag("album_artwork").assertIsDisplayed()
        compose.onNodeWithTag("media_seek").assertIsDisplayed()
        compose.onNodeWithContentDescription("Media Stop").assertIsDisplayed()
        compose.onNodeWithContentDescription("Media Repeat").assertIsDisplayed()
        val panel = compose.onNodeWithTag("numpad_media_panel").fetchSemanticsNode().boundsInRoot
        listOf("Media Stop", "Media Repeat", "Numpad Enter").forEach {
            val bounds = compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot
            assertTrue("$it must fit inside the panel", bounds.bottom <= panel.bottom && bounds.right <= panel.right)
        }
    }

    @Test fun pairingRequiresCompletePinAndTrustIsASeparateStep() {
        val requests = mutableListOf<Pair<String, String>>()
        val state = mutableStateOf(WifiState())
        var trusted = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WifiConnectionDialog(state.value, { address, pin -> requests.add(address to pin) }, { trusted++ }, {}, {})
            }
        }
        compose.onNodeWithTag("wifi_connect").assertIsNotEnabled()
        compose.onNodeWithTag("wifi_address").performTextInput("192.168.1.20")
        compose.onNodeWithTag("wifi_pin").performTextInput("123456")
        compose.onNodeWithTag("wifi_connect").performClick()
        compose.runOnIdle {
            assertEquals(listOf("192.168.1.20" to "123456"), requests)
            assertEquals(0, trusted)
            state.value = WifiState(pendingFingerprint = "ABCD12345678".repeat(5) + "ABCD")
        }
        compose.onNodeWithTag("wifi_address").assertDoesNotExist()
        compose.onNodeWithTag("wifi_pin").assertDoesNotExist()
        compose.onNodeWithText("ABCD 1234 5678").assertIsDisplayed()
        compose.onNodeWithTag("wifi_trust").performClick()
        compose.runOnIdle { assertEquals(1, trusted) }
    }

    @Test fun discoveredHostFillsAddressWithoutSendingPairRequest() {
        var requests = 0
        var searches = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WifiConnectionDialog(WifiState(hosts = listOf(WifiHost("192.168.1.20", "Test PC"))),
                    { _, _ -> requests++ }, {}, {}, {}, { searches++ })
            }
        }
        compose.onNodeWithText("Find PCs").performClick()
        compose.onNodeWithText("Test PC · 192.168.1.20").performClick()
        compose.onNodeWithTag("wifi_address").assertTextContains("192.168.1.20")
        compose.runOnIdle { assertEquals(1, searches); assertEquals(0, requests) }
    }

    @Test fun unknownLockFeedbackDoesNotClaimNumbersAreOnOrOff() {
        compose.setContent {
            NumpadMediaPanel(connected.copy(locksKnown = false), {}, Modifier.height(400.dp), isWifi = true)
        }
        compose.onNodeWithText("NUM LOCK ON").assertDoesNotExist()
        compose.onNodeWithText("NUM LOCK OFF").assertDoesNotExist()
        compose.onNodeWithText("Numbers ready").assertDoesNotExist()
        compose.onNodeWithText("Num Lock controls numbers").assertIsDisplayed()
    }

    @Test fun cancellingPairingDisconnectsBeforeDismissing() {
        val events = mutableListOf<String>()
        compose.setContent {
            WifiConnectionDialog(WifiState(isConnecting = true), { _, _ -> }, {},
                { events.add("disconnect") }, { events.add("dismiss") })
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(listOf("disconnect", "dismiss"), events) }
    }

    @Test fun dismissingConnectedDialogKeepsConnection() {
        val events = mutableListOf<String>()
        compose.setContent {
            WifiConnectionDialog(WifiState(isConnected = true), { _, _ -> }, {},
                { events.add("disconnect") }, { events.add("dismiss") })
        }
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertEquals(listOf("dismiss"), events) }
    }

    @Test fun rememberedPcShowsReconnectWithoutAPinAndUsesSavedAddress() {
        val requests = mutableListOf<String>()
        val saved = SavedWifiPc("192.168.1.10:49372", "Windows PC", "ab".repeat(32))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WifiConnectionDialog(WifiState(savedPc = saved), { _, _ -> error("Must not pair again") }, {}, {}, {},
                    onReconnect = requests::add)
            }
        }
        compose.onNodeWithTag("wifi_pin").assertDoesNotExist()
        compose.onNodeWithTag("wifi_address").assertTextContains(saved.address)
        compose.onNodeWithTag("wifi_reconnect").assertIsEnabled()
        compose.runOnIdle {
            val view = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/outputs/previews/virkey-wifi-reconnect.png")
            requireNotNull(output.parentFile).mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithTag("wifi_reconnect").performClick()
        compose.runOnIdle { assertEquals(listOf(saved.address), requests) }
    }

    @Test fun pairDifferentPcRequiresANewPinAndSavedModeCanBeRestored() {
        val saved = SavedWifiPc("192.168.1.10:49372", "Windows PC", "ab".repeat(32))
        compose.setContent { WifiConnectionDialog(WifiState(savedPc = saved), { _, _ -> }, {}, {}, {}) }
        compose.onNodeWithText("Pair a different PC").performClick()
        compose.onNodeWithTag("wifi_pin").assertIsDisplayed()
        compose.onNodeWithTag("wifi_connect").assertIsNotEnabled()
        compose.onNodeWithText("Use remembered PC").performClick()
        compose.onNodeWithTag("wifi_pin").assertDoesNotExist()
        compose.onNodeWithTag("wifi_reconnect").assertIsEnabled()
    }

    @Test fun revokedPairingShowsPinInsteadOfSilentlyRetryingToken() {
        val saved = SavedWifiPc("192.168.1.10:49372", "Windows PC", "ab".repeat(32))
        compose.setContent { WifiConnectionDialog(WifiState(savedPc = saved, pairingRequired = true), { _, _ -> }, {}, {}, {}) }
        compose.onNodeWithTag("wifi_pin").assertIsDisplayed()
        compose.onNodeWithTag("wifi_reconnect").assertDoesNotExist()
        compose.onNodeWithTag("wifi_connect").assertIsNotEnabled()
    }

    @Test fun forgettingPcNeedsConfirmation() {
        var forgotten = 0
        val saved = SavedWifiPc("192.168.1.10:49372", "Windows PC", "ab".repeat(32))
        compose.setContent { WifiConnectionDialog(WifiState(savedPc = saved), { _, _ -> }, {}, {}, {}, onForget = { forgotten++ }) }
        compose.onNodeWithTag("wifi_forget").performClick()
        compose.onNodeWithText("Forget this PC?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, forgotten) }
        compose.onNodeWithTag("wifi_confirm_forget").performClick()
        compose.runOnIdle { assertEquals(1, forgotten) }
    }

    @Test fun rememberedPcArrivingAfterDialogOpensPrefillsAddress() {
        val state = mutableStateOf(WifiState(loadingSavedPc = true))
        val saved = SavedWifiPc("192.168.1.10:49372", "Windows PC", "ab".repeat(32))
        compose.setContent { WifiConnectionDialog(state.value, { _, _ -> }, {}, {}, {}) }
        compose.onNodeWithTag("wifi_connect").assertIsNotEnabled()
        compose.runOnIdle { state.value = WifiState(savedPc = saved) }
        compose.onNodeWithTag("wifi_address").assertTextContains(saved.address)
        compose.onNodeWithTag("wifi_pin").assertDoesNotExist()
        compose.onNodeWithTag("wifi_reconnect").assertIsEnabled()
    }

    @Test fun lyricsRequiresOptInAndTimingUpdatesDoNotRepeatLookup() {
        var calls = 0
        val state = mutableStateOf(media)
        val source = LyricsSource { calls++; sampleLyrics() }
        compose.setContent { NumpadMediaPanel(connected, {}, Modifier.height(400.dp), state.value, isWifi = true, lyricsSource = source) }
        compose.onNodeWithContentDescription("Media Lyrics").performClick()
        compose.onNodeWithContentDescription("Numpad Enter").assertDoesNotExist()
        compose.onNodeWithText("Lyrics, in time with your music").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithContentDescription("Enable online lyrics").performClick()
        compose.mainClock.advanceTimeBy(600)
        awaitLyrics()
        compose.onNodeWithTag("lyric_line_1").assertIsSelected()
        compose.runOnIdle { state.value = media.copy(positionMs = 85000L) }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithContentDescription("Turn off online lyrics").performClick()
        compose.onNodeWithTag("lyrics_lines").assertDoesNotExist()
        compose.runOnIdle { state.value = media.copy(trackId = "other-track", title = "Other track") }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun tappingTimedLineSeeksOnlyWhenSupported() {
        val state = mutableStateOf(media)
        val commands = mutableListOf<Long>()
        compose.setContent {
            NumpadMediaPanel(connected, {}, Modifier.height(400.dp), state.value,
                { command, position, _, _ -> if (command == "seek") commands.add(position) },
                isWifi = true, lyricsSource = rememberLyricsFixture())
        }
        compose.onNodeWithContentDescription("Media Lyrics").performClick()
        compose.onNodeWithContentDescription("Enable online lyrics").performClick()
        compose.mainClock.advanceTimeBy(600)
        awaitLyrics()
        compose.onNodeWithTag("lyric_line_2").performClick()
        compose.runOnIdle { assertEquals(listOf(95000L), commands); state.value = media.copy(canSeek = false) }
        compose.onNodeWithTag("lyric_line_2").assertIsNotEnabled()
    }

    @Test fun changingTrackCancelsOldLyricsAndLateResultsCannotReplaceNewSong() {
        val old = CompletableDeferred<LyricsResult>()
        val started = CompletableDeferred<Unit>()
        val state = mutableStateOf(media)
        val source = LyricsSource { track ->
            if (track.title == media.title) { started.complete(Unit); old.await() }
            else LyricsResult.Found(LyricsDocument(plain = "New song original line"))
        }
        compose.setContent { NumpadMediaPanel(connected, {}, Modifier.height(400.dp), state.value, isWifi = true, lyricsSource = source) }
        compose.onNodeWithContentDescription("Media Lyrics").performClick()
        compose.onNodeWithContentDescription("Enable online lyrics").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Finding lyrics…").assertIsDisplayed()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Finding lyrics…").fetchSemanticsNodes()
            started.isCompleted
        }
        compose.runOnIdle { state.value = media.copy(trackId = "new-song", title = "New song") }
        compose.mainClock.advanceTimeBy(600)
        awaitLyrics()
        compose.onNodeWithText("New song original line").assertIsDisplayed()
        compose.runOnIdle { old.complete(LyricsResult.Found(LyricsDocument(plain = "Stale original line"))) }
        compose.onNodeWithText("Stale original line").assertDoesNotExist()
        compose.onNodeWithText("Plain lyrics · timing unavailable").assertIsDisplayed()
        compose.runOnIdle { state.value = NowPlayingState() }
        compose.onNodeWithTag("lyrics_pane").assertDoesNotExist()
        compose.onNodeWithText("New song original line").assertDoesNotExist()
    }

    @Test fun unavailableLyricsDoNotDisablePlaybackAndInstrumentalsAreClear() {
        val state = mutableStateOf(media)
        val source = LyricsSource { track -> if (track.title == media.title)
            LyricsResult.Failed("Check your Internet connection.") else LyricsResult.Instrumental }
        compose.setContent { NumpadMediaPanel(connected, {}, Modifier.height(400.dp), state.value, isWifi = true, lyricsSource = source) }
        compose.onNodeWithContentDescription("Media Lyrics").performClick()
        compose.onNodeWithContentDescription("Enable online lyrics").performClick()
        compose.mainClock.advanceTimeBy(600)
        awaitText("Check your Internet connection.")
        compose.onNodeWithText("Check your Internet connection.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry lyrics").assertIsDisplayed()
        compose.onNodeWithContentDescription("Media Play / Pause").assertIsEnabled()
        compose.runOnIdle { state.value = media.copy(trackId = "instrumental", title = "Instrumental") }
        compose.mainClock.advanceTimeBy(600)
        awaitText("Instrumental · no lyrics")
        compose.onNodeWithText("Instrumental · no lyrics").assertIsDisplayed()
    }

    @Test fun missingArtistAndHiddenPaneNeverRequestLyrics() {
        var calls = 0
        val state = mutableStateOf(media.copy(artist = ""))
        val source = LyricsSource { calls++; LyricsResult.NotFound }
        compose.setContent { NumpadMediaPanel(connected, {}, Modifier.height(400.dp), state.value, isWifi = true, lyricsSource = source) }
        compose.onNodeWithContentDescription("Media Lyrics").performClick()
        compose.onNodeWithContentDescription("Enable online lyrics").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Track details unavailable for lyrics.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Media Lyrics").performClick()
        compose.runOnIdle { state.value = media }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun liquidGlassLyricsRendersExpandedPreviewWithLargeArtwork() {
        var renderedView: View? = null
        val preview = media.copy(title = "Glass tides", artist = "Virkey studio", album = "Nightlight sessions", artwork = sampleArtwork())
        compose.setContent {
            renderedView = LocalView.current
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF101315)).padding(28.dp), contentAlignment = Alignment.Center) {
                    NumpadMediaPanel(connected, {}, Modifier.fillMaxWidth().height(400.dp), preview,
                        isWifi = true, lyricsSource = rememberLyricsFixture())
                }
            }
        }
        compose.onNodeWithContentDescription("Media Lyrics").performClick()
        compose.onNodeWithContentDescription("Enable online lyrics").performClick()
        compose.mainClock.advanceTimeBy(600)
        awaitLyrics()
        compose.onNodeWithTag("lyric_line_1").assertIsSelected().assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("album_artwork").fetchSemanticsNode().boundsInRoot.width >= 190f)
        compose.runOnIdle {
            val view = requireNotNull(renderedView)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/outputs/previews/virkey-lyrics.png")
            requireNotNull(output.parentFile).mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    @Config(qualifiers = "w960dp-h600dp-land-mdpi")
    fun compactLyricsAndArtworkFitAndNumberPadCanBeRestored() {
        compose.setContent {
            NumpadMediaPanel(connected, {}, Modifier.fillMaxWidth().height(260.dp), media.copy(artwork = sampleArtwork()),
                isWifi = true, lyricsSource = rememberLyricsFixture())
        }
        compose.onNodeWithContentDescription("Media Lyrics").performClick()
        compose.onNodeWithContentDescription("Enable online lyrics").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(600)
        awaitLyrics()
        compose.onNodeWithTag("lyric_line_1").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithTag("media_seek").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("album_artwork").fetchSemanticsNode().boundsInRoot.width >= 110f)
        compose.onNodeWithContentDescription("Media Expand player").performClick()
        compose.onNodeWithTag("lyrics_pane").assertDoesNotExist()
        compose.onNodeWithContentDescription("Numpad Enter").assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun rememberLyricsFixture(): LyricsSource = androidx.compose.runtime.remember { LyricsSource { sampleLyrics() } }

    private fun awaitLyrics() {
        // Lookup debounce uses the Android coroutine dispatcher, not the animation clock.
        compose.waitUntil(5000) { compose.onAllNodesWithTag("lyrics_lines").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun sampleLyrics() = LyricsResult.Found(LrcParser.parse(
        "[01:12]Soft light across the room\n[01:23]We follow where the colors flow\n[01:35]A little space to let it glow\n[01:48]The night moves gently on", ""))

    private fun sampleArtwork(): Bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply {
        // Clearly synthetic fixture; runtime artwork is supplied only by the PC.
        val canvas = Canvas(this)
        canvas.drawColor(AndroidColor.rgb(27, 57, 52))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = AndroidColor.rgb(142, 220, 192)
        canvas.drawCircle(130f, 105f, 80f, paint)
        paint.color = AndroidColor.rgb(53, 88, 77)
        canvas.drawCircle(140f, 135f, 65f, paint)
        paint.color = AndroidColor.rgb(27, 57, 52)
        canvas.drawCircle(125f, 160f, 49f, paint)
    }
}
