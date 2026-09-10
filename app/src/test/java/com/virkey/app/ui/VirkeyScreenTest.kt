package com.virkey.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import com.virkey.app.input.MediaKeys
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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
class VirkeyScreenTest {
    @get:Rule val compose = createComposeRule()

    private val connected = RemoteUiState(permissionsReady = true, bluetoothEnabled = true,
        isSupported = true, isRegistered = true, isConnected = true, connectedName = "Windows 11 PC",
        statusMessage = "Connected. Your tablet is now a keyboard and trackpad.")

    @Test fun laptopLayoutRendersAboveTrackpadAndSavesPreview() {
        var renderedView: View? = null
        compose.setContent {
            renderedView = LocalView.current
            VirkeyScreen(connected) {}
        }
        compose.onNodeWithTag("keyboard").assertIsDisplayed()
        compose.onNodeWithTag("trackpad").assertIsDisplayed()
        compose.onNodeWithText("F12").assertIsDisplayed()
        compose.onNodeWithText("Backspace").assertIsDisplayed()
        compose.onNodeWithTag("watermark").assertIsDisplayed()
        compose.onNodeWithText("zhenx").assertIsDisplayed()
        compose.onNodeWithText("A LITTLE SPACE.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("ONE FINGER").assertDoesNotExist()
        compose.onNodeWithText("Hold LEFT + move on pad").assertDoesNotExist()
        val keys = compose.onNodeWithTag("keyboard").fetchSemanticsNode().boundsInRoot
        val pad = compose.onNodeWithTag("trackpad").fetchSemanticsNode().boundsInRoot
        assertTrue("Trackpad must sit below the keyboard", keys.bottom <= pad.top)
        val watermark = compose.onNodeWithTag("watermark").fetchSemanticsNode().boundsInRoot
        assertTrue("Watermark must be at the bottom-right", watermark.left > pad.right && watermark.top >= pad.bottom)
        compose.runOnIdle {
            // Draw the actual Android Compose view using Robolectric's native Canvas.
            // PixelCopy waits on a device frame clock that does not run in host tests.
            val view = requireNotNull(renderedView)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            assertTrue("The rendered screen must contain visible content", bitmap.getPixel(30, 30) != 0)
            val output = File("build/outputs/previews/virkey-tablet.png")
            requireNotNull(output.parentFile).mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun physicalKeyTouchSendsPressThenRelease() {
        val actions = mutableListOf<RemoteAction>()
        compose.setContent { VirkeyScreen(connected, actions::add) }
        compose.onNodeWithContentDescription("A").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(RemoteAction.KeyDown(0x04), RemoteAction.KeyUp(0x04)), actions) }
    }

    @Test fun disconnectedKeysDoNotSendInput() {
        val actions = mutableListOf<RemoteAction>()
        compose.setContent { VirkeyScreen(RemoteUiState(), actions::add) }
        compose.onNodeWithContentDescription("A").performTouchInput { click() }
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun pairingCanStartBeforeHidRegistration() {
        val actions = mutableListOf<RemoteAction>()
        compose.setContent { VirkeyScreen(RemoteUiState(permissionsReady = true, bluetoothEnabled = true), actions::add) }
        compose.onNodeWithText("Connect to PC").performClick()
        compose.onNodeWithText("Pair new Windows PC").performClick()
        compose.runOnIdle { assertTrue(actions.contains(RemoteAction.PairNewDevice)) }
    }

    @Test fun numpadAndMediaToggleRendersAndSendsPhysicalReports() {
        val actions = mutableListOf<RemoteAction>()
        var renderedView: View? = null
        compose.setContent {
            renderedView = LocalView.current
            VirkeyScreen(connected.copy(numLock = true), actions::add)
        }
        compose.onNodeWithTag("panel_toggle").performClick()
        compose.onNodeWithTag("keyboard").assertDoesNotExist()
        compose.onNodeWithTag("numpad").assertIsDisplayed()
        compose.onNodeWithTag("media_controls").assertIsDisplayed()
        compose.onNodeWithText("NUM LOCK ON").assertIsDisplayed()
        val deck = compose.onNodeWithTag("numpad_media_panel").fetchSemanticsNode().boundsInRoot
        val pad = compose.onNodeWithTag("trackpad").fetchSemanticsNode().boundsInRoot
        assertTrue(deck.bottom <= pad.top)
        compose.runOnIdle { actions.clear() }
        compose.onNodeWithContentDescription("Numpad 7").performTouchInput { click() }
        compose.onNodeWithContentDescription("Media Play / Pause").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(RemoteAction.KeyDown(0x5F), RemoteAction.KeyUp(0x5F),
                RemoteAction.MediaDown(MediaKeys.PLAY_PAUSE), RemoteAction.MediaUp(MediaKeys.PLAY_PAUSE)), actions)
            val view = requireNotNull(renderedView)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/outputs/previews/virkey-numpad-media.png")
            requireNotNull(output.parentFile).mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithTag("panel_toggle").performClick()
        compose.onNodeWithTag("keyboard").assertIsDisplayed()
        compose.onNodeWithTag("numpad_media_panel").assertDoesNotExist()
    }

    @Test fun disconnectedPanelCannotSendKeysOrMedia() {
        val actions = mutableListOf<RemoteAction>()
        compose.setContent { VirkeyScreen(RemoteUiState(), actions::add) }
        compose.onNodeWithTag("panel_toggle").performClick()
        compose.runOnIdle { actions.clear() }
        compose.onNodeWithContentDescription("Numpad 7").performTouchInput { click() }
        compose.onNodeWithContentDescription("Media Volume up").performTouchInput { click() }
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun holdThenMoveSelectsWithoutTheLeftButton() {
        val actions = mutableListOf<RemoteAction>()
        compose.setContent { VirkeyScreen(connected, actions::add) }
        compose.onNodeWithTag("trackpad_surface").performTouchInput {
            down(center)
            advanceEventTime(650L)
            moveBy(Offset(40f, 0f))
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf(RemoteAction.MouseDown(1), RemoteAction.MovePointer(40, 0), RemoteAction.MouseUp(1)), actions)
        }
    }

    @Test fun stationaryHoldTimerArmsAndCancellationReleasesOnce() {
        val actions = mutableListOf<RemoteAction>()
        compose.setContent { VirkeyScreen(connected, actions::add) }
        compose.onNodeWithTag("trackpad_surface").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(650L)
        compose.runOnIdle { assertEquals(listOf(RemoteAction.MouseDown(1)), actions) }
        compose.onNodeWithTag("trackpad_surface").performTouchInput { cancel() }
        compose.runOnIdle { assertEquals(listOf(RemoteAction.MouseDown(1), RemoteAction.MouseUp(1)), actions) }
    }

    @Test fun disconnectCancelsHeldDrag() {
        val actions = mutableListOf<RemoteAction>()
        val state = mutableStateOf(connected)
        compose.setContent { VirkeyScreen(state.value, actions::add) }
        compose.onNodeWithTag("trackpad_surface").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(650L)
        compose.runOnIdle { state.value = connected.copy(isConnected = false) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(RemoteAction.MouseDown(1), RemoteAction.MouseUp(1)), actions) }
        compose.onNodeWithTag("trackpad_surface").performTouchInput { up() }
    }

    @Test fun switchingPanelsReleasesAHeldKeyboardKey() {
        val actions = mutableListOf<RemoteAction>()
        compose.setContent { VirkeyScreen(connected, actions::add) }
        compose.onNodeWithContentDescription("A").performTouchInput { down(center) }
        compose.onNodeWithTag("panel_toggle").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(RemoteAction.KeyDown(0x04), actions.first())
            assertTrue(actions.contains(RemoteAction.ReleaseAll))
            assertEquals(1, actions.count { it == RemoteAction.KeyUp(0x04) })
        }
        compose.onNodeWithTag("numpad_media_panel").performTouchInput { up() }
    }

    @Test
    @Config(qualifiers = "w960dp-h600dp-land-mdpi")
    fun compactLayoutKeepsNumpadAndTransportVisible() {
        compose.setContent { VirkeyScreen(connected) {} }
        compose.onNodeWithTag("panel_toggle").performClick()
        compose.onNodeWithContentDescription("Numpad Enter").assertIsDisplayed()
        compose.onNodeWithContentDescription("Media Stop").assertIsDisplayed()
        compose.onNodeWithTag("trackpad").assertIsDisplayed()
        compose.onNodeWithTag("watermark").assertIsDisplayed()
    }

    @Test fun pausingCancelsPendingHoldAndResumeRequiresAFreshTouch() {
        val actions = mutableListOf<RemoteAction>()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                VirkeyScreen(connected, actions::add)
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("trackpad_surface").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(100L)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.mainClock.advanceTimeBy(650L)
        compose.runOnIdle {
            assertTrue(actions.contains(RemoteAction.ReleaseAll))
            assertTrue("Pending hold must not press: $actions", actions.none { it is RemoteAction.MouseDown })
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.mainClock.advanceTimeBy(650L)
        compose.onNodeWithTag("trackpad_surface").performTouchInput { up() }
        compose.runOnIdle { assertTrue(actions.none { it is RemoteAction.MouseDown }); actions.clear() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("trackpad_surface").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(650L)
        compose.onNodeWithTag("trackpad_surface").performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(RemoteAction.MouseDown(1), RemoteAction.MouseUp(1)), actions) }
    }
}
