package com.virkey.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virkey.app.dock.*
import com.virkey.app.network.WifiState
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppDockTest {
    @get:Rule val compose = createComposeRule()
    private val app = DockItem(id = "app-button", label = "Music", kind = DockKind.APP, appId = "music", pcId = "this-pc")
    private val sound = DockItem(id = "sound-button", label = "Airhorn", kind = DockKind.HOTKEY, hotkey = "Ctrl+Alt+1")

    @Test fun bluetoothEnablesHotkeysAndDisablesPcLaunches() {
        val ran = mutableListOf<String>()
        compose.setContent { MaterialTheme { AppDock(listOf(app, sound), true, ConnectionMode.BLUETOOTH, WifiState(), { ran.add(it.id) }, {}) } }
        compose.onNodeWithContentDescription("Dock Music").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Dock Airhorn").performClick()
        compose.runOnIdle { assertEquals(listOf(sound.id), ran) }
    }

    @Test fun appButtonsCannotLaunchOnDifferentPc() {
        compose.setContent { MaterialTheme { AppDock(listOf(app), true, ConnectionMode.WIFI,
            WifiState(isConnected = true, dockSupported = true, pcId = "other-pc"), {}, {}) } }
        compose.onNodeWithContentDescription("Dock Music").assertIsNotEnabled()
    }

    @Test fun editorCreatesValidatedSoundboardButton() {
        val saved = mutableListOf<DockItem>()
        compose.setContent { MaterialTheme { DockEditor(emptyList(), WifiState(), saved::add, {}, { _, _ -> }, {}, {}) } }
        compose.onNodeWithTag("add_dock_hotkey").performClick()
        compose.onNodeWithTag("dock_label").performTextReplacement("Airhorn")
        compose.onNodeWithTag("dock_hotkey").performTextReplacement("Ctrl+Ctrl+A")
        compose.onNodeWithTag("save_dock_item").assertIsNotEnabled()
        compose.onNodeWithTag("dock_hotkey").performTextReplacement("Ctrl+Alt+1")
        compose.onNodeWithTag("save_dock_item").performClick()
        compose.runOnIdle { assertEquals("Airhorn", saved.single().label); assertEquals("Ctrl+Alt+1", saved.single().hotkey) }
    }

    @Test fun pickerSelectsKnownPcAppWithAutomaticIcon() {
        val saved = mutableListOf<DockItem>()
        compose.setContent { MaterialTheme { DockEditor(emptyList(), WifiState(isConnected = true, dockSupported = true,
            pcId = "this-pc", apps = listOf(com.virkey.app.dock.PcApp("music", "Music", icon()))), saved::add, {}, { _, _ -> }, {}, {}) } }
        compose.onNodeWithTag("add_dock_app").performClick()
        compose.onNodeWithText("Music", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("save_dock_item").performClick()
        compose.runOnIdle { assertEquals("this-pc", saved.single().pcId); assertNotNull(decodeDockIcon(saved.single().automaticIcon)) }
    }

    @Test fun editingDockReleasesKeyboardAndPreventsHiddenKeyPresses() {
        val actions = mutableListOf<RemoteAction>()
        compose.setContent { VirkeyScreen(RemoteUiState(isConnected = true), ConnectionMode.BLUETOOTH, dockItems = listOf(sound), onAction = actions::add) }
        compose.onNodeWithTag("customize_dock").performClick()
        compose.runOnIdle { assertTrue(actions.contains(RemoteAction.ReleaseAll)) }
        compose.onNodeWithText("Your dock").assertIsDisplayed()
    }

    @Test fun dockLayoutRendersAlongsideKeyboardAndTrackpad() {
        var view: View? = null
        compose.setContent {
            view = LocalView.current
            VirkeyScreen(RemoteUiState(isConnected = true, connectedName = "Windows PC", statusMessage = "Connected over Wi-Fi"),
                ConnectionMode.WIFI, wifiState = WifiState(isConnected = true, dockSupported = true, pcId = "this-pc"),
                dockItems = listOf(app.copy(automaticIcon = encodeDockIcon(icon())), app.copy(id = "chat", label = "Chat"),
                    sound, sound.copy(id = "laugh", label = "Laugh", hotkey = "Ctrl+Alt+2"),
                    sound.copy(id = "stop", label = "Stop sounds", hotkey = "Ctrl+Alt+0")), onAction = {})
        }
        val keys = compose.onNodeWithTag("keyboard").fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("app_dock").fetchSemanticsNode().boundsInRoot
        val pad = compose.onNodeWithTag("trackpad").fetchSemanticsNode().boundsInRoot
        assertTrue(keys.bottom <= dock.top && dock.bottom <= pad.top)
        compose.runOnIdle {
            val rendered = requireNotNull(view)
            val bitmap = Bitmap.createBitmap(rendered.width, rendered.height, Bitmap.Config.ARGB_8888)
            rendered.draw(Canvas(bitmap))
            val output = File("build/outputs/previews/virkey-app-dock.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun icon() = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.drawColor(android.graphics.Color.rgb(33, 57, 48))
        canvas.drawCircle(32f, 32f, 22f, Paint().apply { color = android.graphics.Color.rgb(142, 220, 192) })
    }
}
