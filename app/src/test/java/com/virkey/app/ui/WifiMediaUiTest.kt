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
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.virkey.app.input.MediaKeys
import com.virkey.app.network.NowPlayingState
import com.virkey.app.network.WifiHost
import com.virkey.app.network.WifiState
import java.io.File
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
