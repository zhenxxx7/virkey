package com.virkey.app.dock

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.virkey.app.ui.RemoteAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DockTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before fun clear() { context.getSharedPreferences("dock", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun hotkeyUsesPhysicalKeysAndRejectsAmbiguousChords() {
        assertEquals(listOf(0xE0, 0xE2, 0x1E), Hotkeys.parse("Ctrl+Alt+1"))
        assertEquals(listOf(0x41), Hotkeys.parse("f8"))
        listOf("Ctrl", "Ctrl++A", "Ctrl+Ctrl+A", "A+B", "Fn+1", "Ctrl+F25").forEach {
            assertTrue("Reject $it", runCatching { Hotkeys.parse(it) }.isFailure)
        }
    }

    @Test fun hotkeyReleasesInReverseOrderAfterItsHold() = runBlocking {
        val events = mutableListOf<RemoteAction>()
        Hotkeys.play("Ctrl+Alt+1", events::add, pause = {})
        assertEquals(listOf(RemoteAction.KeyDown(0xE0), RemoteAction.KeyDown(0xE2), RemoteAction.KeyDown(0x1E),
            RemoteAction.KeyUp(0x1E), RemoteAction.KeyUp(0xE2), RemoteAction.KeyUp(0xE0)), events)
    }

    @Test fun cancellationStillReleasesEveryPressedModifier() = runBlocking {
        val events = mutableListOf<RemoteAction>()
        try {
            Hotkeys.play("Ctrl+Alt+1", events::add) { if (events.size == 2) throw CancellationException("Paused") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(listOf(RemoteAction.KeyDown(0xE0), RemoteAction.KeyDown(0xE2), RemoteAction.KeyUp(0xE2), RemoteAction.KeyUp(0xE0)), events)
    }

    @Test fun dockPersistsEditsOrderAndRemovalAcrossRecreation() {
        val store = DockStore(context)
        val sound = DockItem(label = "Airhorn", kind = DockKind.HOTKEY, hotkey = "Ctrl+Alt+1")
        val app = DockItem(label = "My app", kind = DockKind.APP, appId = "app", pcId = "pc", customIcon = "custom", automaticIcon = "auto")
        store.save(sound); store.save(app); store.move(app.id, -1)
        store.save(sound.copy(label = "Horn"))
        assertEquals(listOf(app, sound.copy(label = "Horn")), DockStore(context).items.value)
        store.remove(app.id)
        assertEquals(listOf(sound.copy(label = "Horn")), DockStore(context).items.value)
    }

    @Test fun malformedAndOversizedConfigDoesNotCrashOrCreateUnsafeEntries() {
        assertTrue(decodeDock("not-json").isEmpty())
        assertTrue(decodeDock("x".repeat(2_200_001)).isEmpty())
        val invalid = DockItem(label = "Bad", kind = DockKind.HOTKEY, hotkey = "no+such+keys")
        assertTrue(decodeDock(encodeDock(listOf(invalid))).isEmpty())
    }

    @Test fun dockLimitAndReorderingBoundsPreserveEntries() {
        val store = DockStore(context)
        repeat(25) { store.save(DockItem(label = "Sound $it", kind = DockKind.HOTKEY, hotkey = "F8")) }
        assertEquals(24, store.items.value.size)
        val before = store.items.value
        store.move(before.first().id, -1)
        store.move(before.last().id, 1)
        assertEquals(before, store.items.value)
    }

    @Test fun customImageIsCopiedAndDownsampledWithoutKeepingSourceAccess() {
        val file = File.createTempFile("dock-icon", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val encoded = importDockIcon(context, Uri.fromFile(file))
        assertTrue(file.delete())
        val item = DockItem(label = "My sound", kind = DockKind.HOTKEY, hotkey = "F8", customIcon = encoded)
        DockStore(context).save(item)
        val restored = decodeDockIcon(DockStore(context).items.value.single().customIcon)
        assertNotNull(restored)
        assertEquals(64, restored!!.width)
        assertEquals(64, restored.height)
    }
}
