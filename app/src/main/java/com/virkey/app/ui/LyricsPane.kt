package com.virkey.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virkey.app.media.LyricsDocument
import com.virkey.app.media.LyricsResult
import com.virkey.app.media.LyricsSource
import com.virkey.app.media.LyricsTrack
import com.virkey.app.network.NowPlayingState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

@Composable
internal fun LyricsPane(
    media: NowPlayingState,
    allowed: Boolean,
    onAllowed: (Boolean) -> Unit,
    source: LyricsSource,
    canSeek: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val track = LyricsTrack.from(media)
    var retry by remember(track) { mutableIntStateOf(0) }
    // Remember by identity so a new song cannot display one frame of old lyrics.
    var result by remember(track, media.sessionId, media.trackId, allowed, source, retry) { mutableStateOf<LyricsResult?>(null) }
    LaunchedEffect(track, media.sessionId, media.trackId, allowed, source, retry) {
        if (allowed && track != null) {
            delay(300L) // Skip transient sessions while the user skips tracks.
            val loaded = source.lookup(track)
            currentCoroutineContext().ensureActive()
            result = loaded
        }
    }
    Column(modifier.testTag("lyrics_pane"), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if ((result as? LyricsResult.Found)?.document?.synced == true) "SYNCED · LRCLIB" else "LYRICS · LRCLIB",
                color = MediaAccent, fontSize = if (compact) 8.sp else 9.sp, letterSpacing = 0.9.sp)
            Spacer(Modifier.weight(1f))
            if (allowed) LyricsChip("Online off", "Turn off online lyrics") { onAllowed(false) }
        }
        when {
            !allowed -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text("Lyrics, in time with your music", color = MediaIvory, fontWeight = FontWeight.Medium,
                    fontSize = if (compact) 13.sp else 19.sp)
                Text("Enable to send this and following tracks' title, artist, album and duration to LRCLIB. Internet required.",
                    color = MediaMuted, fontSize = if (compact) 10.sp else 12.sp,
                    modifier = Modifier.padding(vertical = if (compact) 4.dp else 10.dp))
                LyricsChip("Enable online lyrics", "Enable online lyrics") { onAllowed(true) }
            }
            track == null -> LyricsNotice("Track details unavailable for lyrics.", Modifier.weight(1f))
            result == null -> LyricsNotice("Finding lyrics…", Modifier.weight(1f))
            result is LyricsResult.Found -> key(track, media.sessionId, media.trackId) {
                LyricsLines((result as LyricsResult.Found).document, media.positionMs, media.durationMs,
                    canSeek, onSeek, Modifier.weight(1f), compact)
            }
            result == LyricsResult.Instrumental -> LyricsNotice("Instrumental · no lyrics", Modifier.weight(1f))
            result == LyricsResult.NotFound -> LyricsNotice("No matching lyrics found.", Modifier.weight(1f))
            else -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text((result as LyricsResult.Failed).message, color = MediaMuted, fontSize = if (compact) 11.sp else 13.sp)
                LyricsChip("Retry", "Retry lyrics") { retry++ }
            }
        }
    }
}

@Composable
private fun LyricsLines(document: LyricsDocument, position: Long, duration: Long, canSeek: Boolean,
    onSeek: (Long) -> Unit, modifier: Modifier, compact: Boolean) {
    val list = rememberLazyListState()
    val active = document.activeIndex(position)
    LaunchedEffect(active, document) { if (active >= 0) list.animateScrollToItem((active - 1).coerceAtLeast(0)) }
    LazyColumn(modifier.testTag("lyrics_lines"), state = list, verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 10.dp)) {
        if (document.synced) {
            itemsIndexed(document.lines) { index, line ->
                val selectedLine = index == active
                Text(line.text.ifBlank { "♪" }, color = if (selectedLine) MediaIvory else MediaMuted.copy(alpha = 0.65f),
                    fontSize = if (compact) 15.sp else 21.sp, fontWeight = if (selectedLine) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (selectedLine) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                        .clickable(enabled = canSeek && line.timeMs <= duration, role = Role.Button) { onSeek(line.timeMs) }
                        .padding(horizontal = 8.dp, vertical = if (compact) 3.dp else 6.dp).testTag("lyric_line_$index")
                        .semantics { selected = selectedLine })
            }
        } else {
            item { Text("Plain lyrics · timing unavailable", color = MediaAccent, fontSize = 10.sp) }
            itemsIndexed(document.plain.lines()) { _, line ->
                Text(line, color = MediaIvory, fontSize = if (compact) 14.sp else 19.sp, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

@Composable
private fun LyricsNotice(message: String, modifier: Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(message, color = MediaMuted, fontSize = 13.sp)
    }
}

@Composable
private fun LyricsChip(text: String, description: String, onClick: () -> Unit) {
    Text(text, color = MediaAccent, fontSize = 10.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.08f))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }
            .padding(horizontal = 10.dp, vertical = 6.dp))
}
