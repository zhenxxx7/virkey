package com.virkey.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
        val keys = compose.onNodeWithTag("keyboard").fetchSemanticsNode().boundsInRoot
        val pad = compose.onNodeWithTag("trackpad").fetchSemanticsNode().boundsInRoot
        assertTrue("Trackpad must sit below the keyboard", keys.bottom <= pad.top)
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
}
