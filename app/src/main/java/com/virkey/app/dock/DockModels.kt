package com.virkey.app.dock

import android.graphics.Bitmap
import com.virkey.app.input.LaptopLayout
import com.virkey.app.ui.RemoteAction
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

const val MAX_DOCK_ITEMS = 24
const val MAX_DOCK_ICON_BYTES = 32768

data class PcApp(val id: String, val name: String, val icon: Bitmap? = null)

enum class DockKind { APP, HOTKEY }

data class DockItem(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val kind: DockKind,
    val appId: String = "",
    val pcId: String = "",
    val hotkey: String = "",
    val automaticIcon: String = "",
    val customIcon: String = "",
) {
    val icon: String get() = customIcon.ifEmpty { automaticIcon }
}

object Hotkeys {
    private val modifiers = linkedMapOf("CTRL" to 0xE0, "SHIFT" to 0xE1, "ALT" to 0xE2, "WIN" to 0xE3)
    private val keys = LaptopLayout.rows.flatten().filter { it.usage in 4..0xDF }
        .associate { it.label.uppercase(Locale.ROOT) to it.usage } + mapOf(
        "SPACE" to 0x2C, "UP" to 0x52, "DOWN" to 0x51, "LEFT" to 0x50, "RIGHT" to 0x4F,
        "HOME" to 0x4A, "END" to 0x4D, "PAGEUP" to 0x4B, "PAGEDOWN" to 0x4E, "INSERT" to 0x49,
    )

    fun parse(value: String): List<Int> {
        val parts = value.uppercase(Locale.ROOT).split('+').map(String::trim)
        require(parts.size in 1..5 && parts.none(String::isEmpty)) { "Use a shortcut such as Ctrl+Alt+1 or F8" }
        val prefix = parts.dropLast(1)
        require(prefix.distinct().size == prefix.size && prefix.all(modifiers::containsKey)) {
            "Use Ctrl, Shift, Alt or Win before one key"
        }
        val key = keys[parts.last()] ?: throw IllegalArgumentException("Choose a letter, number, F1–F12 or standard key")
        return prefix.map { modifiers.getValue(it) } + key
    }

    suspend fun play(value: String, send: (RemoteAction) -> Unit, pause: suspend (Long) -> Unit = { delay(it) }) {
        val keys = parse(value)
        val pressed = mutableListOf<Int>()
        try {
            for (usage in keys) {
                pressed += usage
                send(RemoteAction.KeyDown(usage))
                pause(12)
            }
            pause(60)
        } finally {
            pressed.asReversed().forEach { send(RemoteAction.KeyUp(it)) }
        }
    }
}

internal fun encodeDock(items: List<DockItem>): String {
    require(items.size <= MAX_DOCK_ITEMS)
    return JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().put("id", item.id).put("label", item.label.take(40)).put("kind", item.kind.name)
                .put("appId", item.appId).put("pcId", item.pcId).put("hotkey", item.hotkey)
                .put("automaticIcon", item.automaticIcon).put("customIcon", item.customIcon))
        }
    }.toString()
}

internal fun decodeDock(value: String): List<DockItem> = runCatching {
    if (value.length > 2_200_000) return emptyList()
    val array = JSONArray(value)
    (0 until minOf(array.length(), MAX_DOCK_ITEMS)).mapNotNull { index ->
        runCatching {
            val json = array.getJSONObject(index)
            val item = DockItem(
                id = json.getString("id").take(80), label = json.getString("label").take(40),
                kind = DockKind.valueOf(json.getString("kind")), appId = json.optString("appId").take(80),
                pcId = json.optString("pcId").take(80), hotkey = json.optString("hotkey").take(80),
                automaticIcon = json.optString("automaticIcon").takeIf { it.length <= 43692 }.orEmpty(),
                customIcon = json.optString("customIcon").takeIf { it.length <= 43692 }.orEmpty(),
            )
            require(item.id.isNotBlank() && item.label.isNotBlank())
            if (item.kind == DockKind.HOTKEY) Hotkeys.parse(item.hotkey)
            else require(item.appId.isNotBlank() && item.pcId.isNotBlank())
            item
        }.getOrNull()
    }.distinctBy { it.id }
}.getOrDefault(emptyList())
