package com.virkey.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virkey.app.media.LyricsSource
import com.virkey.app.network.NowPlayingState
import java.util.Locale

internal val MediaIvory = Color(0xFFF0F5F2)
internal val MediaMuted = Color(0xFFAFBFBA)
internal val MediaAccent = Color(0xFFB5F5DE)

/** Soft artwork-colored light under a translucent, highlighted glass shell; works on API 28+. */
@Composable
internal fun GlassMediaSurface(artwork: Bitmap?, modifier: Modifier, content: @Composable () -> Unit) {
    val tint = remember(artwork) { artworkTint(artwork) }
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.shadow(10.dp, shape).clip(shape).background(Color(0xFF142021)).drawBehind {
        drawRect(Brush.radialGradient(listOf(tint.copy(alpha = 0.58f), Color.Transparent),
            center = Offset(size.width * 0.12f, size.height * 0.15f), radius = size.width.coerceAtLeast(1f) * 0.8f))
        drawRect(Brush.radialGradient(listOf(Color(0xFF7891C5).copy(alpha = 0.21f), Color.Transparent),
            center = Offset(size.width * 0.95f, size.height), radius = size.width.coerceAtLeast(1f) * 0.6f))
        drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.09f), Color.Transparent, Color.White.copy(alpha = 0.025f))))
    }.border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.38f),
        Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.18f))), shape)) { content() }
}

private fun artworkTint(bitmap: Bitmap?): Color {
    if (bitmap == null || bitmap.isRecycled) return Color(0xFF528A78)
    return try {
        var red = 0; var green = 0; var blue = 0
        for (y in 1..4) for (x in 1..4) {
            val pixel = bitmap.getPixel(bitmap.width * x / 5, bitmap.height * y / 5)
            red += android.graphics.Color.red(pixel)
            green += android.graphics.Color.green(pixel)
            blue += android.graphics.Color.blue(pixel)
        }
        Color(red / 16, green / 16, blue / 16)
    } catch (_: IllegalStateException) { Color(0xFF528A78) }
}

@Composable
fun NowPlayingCard(
    state: NowPlayingState,
    connected: Boolean,
    compact: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    showLyrics: Boolean = false,
    lyricsAllowed: Boolean = false,
    onLyricsAllowed: (Boolean) -> Unit = {},
    lyricsSource: LyricsSource? = null,
) {
    var scrubPosition by remember(state.sessionId, state.trackId, state.canSeek, connected) { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(0L)
    val position = if (duration > 0) state.positionMs.coerceIn(0L, duration) else state.positionMs.coerceAtLeast(0L)
    val fraction = scrubPosition ?: if (duration > 0) position.toFloat() / duration.toFloat() else 0f
    val canSeek = connected && state.available && state.canSeek && duration > 0
    GlassMediaSurface(state.artwork.takeIf { connected && state.available }, modifier.testTag("now_playing_card")) {
        Column(Modifier.fillMaxSize().padding(if (compact) 10.dp else 16.dp), verticalArrangement = Arrangement.Center) {
            if (!connected || !state.available) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Text(if (connected) "No media playing" else "Connect your PC", color = MediaIvory,
                        fontSize = if (compact) 14.sp else 19.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(5.dp))
                    Text(if (connected) "Open a media player on your PC" else "Use Wi-Fi with Virkey Host for now playing",
                        color = MediaMuted, fontSize = if (compact) 10.sp else 12.sp)
                }
            } else {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 22.dp)) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        val artworkSize = minOf(maxHeight, maxWidth * 0.47f, 224.dp).coerceAtLeast(1.dp)
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 22.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            val artShape = RoundedCornerShape(if (compact) 12.dp else 18.dp)
                            Box(Modifier.size(artworkSize).shadow(12.dp, artShape).clip(artShape)
                                .background(Color(0xFF334F49)).border(1.dp, Color.White.copy(alpha = 0.24f), artShape),
                                contentAlignment = Alignment.Center) {
                                if (state.artwork != null) {
                                    Image(state.artwork.asImageBitmap(), contentDescription = "Album artwork", contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().testTag("album_artwork"))
                                } else {
                                    Text("♫", color = MediaAccent.copy(alpha = 0.7f), fontSize = if (compact) 36.sp else 60.sp)
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp)) {
                                Text(listOf(state.player.takeIf { it.isNotBlank() }, if (state.playing) "PLAYING" else "PAUSED")
                                    .filterNotNull().joinToString(" · "), color = MediaAccent,
                                    fontSize = if (compact) 8.sp else 9.sp, letterSpacing = 0.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(state.title.ifBlank { "—" }, color = MediaIvory, fontSize = if (compact) 19.sp else 27.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("media_title"))
                                if (state.artist.isNotBlank()) Text(state.artist, color = MediaIvory.copy(alpha = 0.86f),
                                    fontSize = if (compact) 11.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!compact && state.album.isNotBlank()) Text(state.album, color = MediaMuted, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (showLyrics && lyricsSource != null) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.14f)))
                        LyricsPane(state, lyricsAllowed, onLyricsAllowed, lyricsSource, canSeek, onSeek,
                            Modifier.weight(1f).fillMaxHeight(), compact)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(mediaTime(if (scrubPosition != null) (fraction * duration).toLong() else position),
                        color = MediaMuted, fontSize = if (compact) 9.sp else 10.sp, modifier = Modifier.testTag("media_elapsed"))
                    Slider(value = fraction.coerceIn(0f, 1f), onValueChange = { scrubPosition = it },
                        onValueChangeFinished = {
                            val target = scrubPosition
                            scrubPosition = null
                            if (canSeek && target != null) onSeek((target.toDouble() * duration).toLong().coerceIn(0L, duration))
                        }, enabled = canSeek,
                        modifier = Modifier.weight(1f).height(if (compact) 24.dp else 32.dp).testTag("media_seek")
                            .semantics { contentDescription = "Playback position" },
                        colors = SliderDefaults.colors(thumbColor = MediaAccent, activeTrackColor = MediaAccent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.14f), disabledActiveTrackColor = MediaMuted))
                    Text(mediaTime(duration), color = MediaMuted, fontSize = if (compact) 9.sp else 10.sp)
                }
            }
        }
    }
}

private fun mediaTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
    return if (totalSeconds >= 3600L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", totalSeconds / 3600L, totalSeconds / 60L % 60L, totalSeconds % 60L)
    } else String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
