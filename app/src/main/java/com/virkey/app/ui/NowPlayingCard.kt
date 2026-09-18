package com.virkey.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.virkey.app.network.NowPlayingState
import java.util.Locale

private val MediaIvory = Color(0xFFEAEDE7)
private val MediaMuted = Color(0xFF919C9D)
private val MediaAccent = Color(0xFF8EDCC0)

/** Metadata only comes from the connected PC. Empty sessions never retain a previous track. */
@Composable
fun NowPlayingCard(
    state: NowPlayingState,
    connected: Boolean,
    compact: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrubPosition by remember(state.sessionId, state.trackId, state.canSeek, connected) { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(0L)
    val position = state.positionMs.coerceIn(0L, duration)
    val fraction = scrubPosition ?: if (duration > 0) position.toFloat() / duration.toFloat() else 0f
    val canSeek = connected && state.available && state.canSeek && duration > 0
    Column(
        modifier.testTag("now_playing_card").clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF151A1C)).padding(if (compact) 8.dp else 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (!connected || !state.available) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text(if (connected) "No media playing" else "Connect your PC", color = MediaIvory,
                    fontSize = if (compact) 14.sp else 19.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(5.dp))
                Text(if (connected) "Open a media player on your PC" else "Use Wi-Fi with Virkey Host for now playing",
                    color = MediaMuted, fontSize = if (compact) 10.sp else 12.sp)
            }
        } else {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val artworkSize = if (compact) 54.dp else 84.dp
                Box(Modifier.size(artworkSize).clip(RoundedCornerShape(8.dp)).background(Color(0xFF293733)),
                    contentAlignment = Alignment.Center) {
                    if (state.artwork != null) {
                        Image(state.artwork.asImageBitmap(), contentDescription = "Album artwork", contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().testTag("album_artwork"))
                    } else {
                        Text("♫", color = MediaAccent.copy(alpha = 0.7f), fontSize = if (compact) 25.sp else 37.sp)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 3.dp)) {
                    Text(
                        listOf(state.player.takeIf { it.isNotBlank() }, if (state.playing) "PLAYING" else "PAUSED")
                            .filterNotNull().joinToString(" · "),
                        color = MediaAccent, fontSize = if (compact) 8.sp else 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(state.title.ifBlank { "—" }, color = MediaIvory, fontSize = if (compact) 16.sp else 22.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("media_title"))
                    if (state.artist.isNotBlank()) {
                        Text(state.artist, color = MediaIvory.copy(alpha = 0.85f), fontSize = if (compact) 10.sp else 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!compact && state.album.isNotBlank()) {
                        Text(state.album, color = MediaMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(mediaTime(if (scrubPosition != null) (fraction * duration).toLong() else position),
                    color = MediaMuted, fontSize = if (compact) 9.sp else 10.sp, modifier = Modifier.testTag("media_elapsed"))
                Slider(
                    value = fraction.coerceIn(0f, 1f),
                    onValueChange = { scrubPosition = it },
                    onValueChangeFinished = {
                        val target = scrubPosition
                        scrubPosition = null
                        if (canSeek && target != null) onSeek((target.toDouble() * duration).toLong().coerceIn(0L, duration))
                    },
                    enabled = canSeek,
                    modifier = Modifier.weight(1f).height(if (compact) 24.dp else 32.dp).testTag("media_seek")
                        .semantics { contentDescription = "Playback position" },
                    colors = SliderDefaults.colors(thumbColor = MediaAccent, activeTrackColor = MediaAccent,
                        inactiveTrackColor = Color(0xFF35423E), disabledActiveTrackColor = MediaMuted),
                )
                Text(mediaTime(duration), color = MediaMuted, fontSize = if (compact) 9.sp else 10.sp)
            }
        }
    }
}

private fun mediaTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
    return if (totalSeconds >= 3600L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", totalSeconds / 3600L, totalSeconds / 60L % 60L, totalSeconds % 60L)
    } else {
        String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
    }
}
