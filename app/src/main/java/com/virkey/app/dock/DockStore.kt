package com.virkey.app.dock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.virkey.app.network.decodeArtwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.Base64

class DockStore(context: Context) {
    private val preferences = context.getSharedPreferences("dock", Context.MODE_PRIVATE)
    private val mutableItems = MutableStateFlow(decodeDock(preferences.getString("items", "[]").orEmpty()))
    val items = mutableItems.asStateFlow()

    fun save(item: DockItem) {
        val existing = mutableItems.value
        if (existing.none { it.id == item.id } && existing.size >= MAX_DOCK_ITEMS) return
        update(if (existing.any { it.id == item.id }) existing.map { if (it.id == item.id) item else it } else existing + item)
    }

    fun remove(id: String) = update(mutableItems.value.filterNot { it.id == id })

    fun move(id: String, offset: Int) {
        val next = mutableItems.value.toMutableList()
        val from = next.indexOfFirst { it.id == id }
        if (from < 0 || from + offset !in next.indices) return
        val item = next.removeAt(from)
        next.add(from + offset, item)
        update(next)
    }

    private fun update(items: List<DockItem>) {
        preferences.edit().putString("items", encodeDock(items)).apply()
        mutableItems.value = items
    }
}

fun encodeDockIcon(bitmap: Bitmap?): String {
    if (bitmap == null) return ""
    val scaled = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
    return try {
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
        if (output.size() > MAX_DOCK_ICON_BYTES) "" else Base64.getEncoder().encodeToString(output.toByteArray())
    } finally { if (scaled !== bitmap) scaled.recycle() }
}

fun decodeDockIcon(encoded: String): Bitmap? = if (encoded.length <= 43692) decodeArtwork(encoded) else null

/** Downsample the selected image before decoding; never retain permission to the original. */
fun importDockIcon(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size() + read <= 8 * 1024 * 1024) { "Choose an image smaller than 8 MB" }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    } ?: throw IllegalArgumentException("Cannot open this image")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384) { "Choose a PNG, JPEG or WebP image" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IllegalArgumentException("Cannot decode this image")
    return try { encodeDockIcon(bitmap).also { require(it.isNotEmpty()) { "Image could not be saved" } } }
        finally { bitmap.recycle() }
}
