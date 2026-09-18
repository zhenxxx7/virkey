package com.virkey.app.media

import com.virkey.app.network.NowPlayingState
import java.text.Normalizer
import java.util.Locale

data class LyricsTrack(val title: String, val artist: String, val album: String, val durationMs: Long) {
    companion object {
        fun from(state: NowPlayingState): LyricsTrack? {
            if (!state.available || state.title.isBlank() || state.artist.isBlank()) return null
            if (listOf(state.title, state.artist, state.album).any { it.length > 512 }) return null
            return LyricsTrack(state.title.trim(), state.artist.trim(), state.album.trim(), state.durationMs.coerceAtLeast(0))
        }
    }
}

data class LyricLine(val timeMs: Long, val text: String)
data class LyricsDocument(val lines: List<LyricLine> = emptyList(), val plain: String = "") {
    val synced: Boolean get() = lines.isNotEmpty()

    /** Last timestamp at or before playback, including blank instrumental breaks. */
    fun activeIndex(positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timeMs <= positionMs) low = middle + 1 else high = middle - 1
        }
        return high
    }
}

sealed interface LyricsResult {
    data class Found(val document: LyricsDocument) : LyricsResult
    data object NotFound : LyricsResult
    data object Instrumental : LyricsResult
    data class Failed(val message: String) : LyricsResult
}

fun interface LyricsSource {
    suspend fun lookup(track: LyricsTrack): LyricsResult
}

/** Bounded LRC parser; metadata tags are never shown as lyrics. */
object LrcParser {
    const val MAX_TEXT = 80_000
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val offsetTag = Regex("(?im)^\\s*\\[offset:([+-]?\\d{1,8})]\\s*$")
    private val wordTime = Regex("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>")

    fun parse(synced: String, plain: String): LyricsDocument {
        require(synced.length <= MAX_TEXT && plain.length <= MAX_TEXT)
        // LRC positive offset advances lyrics, so subtract it from each timestamp.
        val offset = offsetTag.find(synced)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val parsed = mutableListOf<LyricLine>()
        for (raw in synced.lineSequence()) {
            var rest = raw.trim()
            val times = mutableListOf<Long>()
            while (true) {
                val match = timestamp.find(rest)?.takeIf { it.range.first == 0 } ?: break
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0L
                if (seconds < 60) {
                    val time = match.groupValues[1].toLong() * 60_000L + seconds * 1000L + fraction - offset
                    if (time <= 86_400_000L) times.add(time.coerceAtLeast(0L))
                }
                rest = rest.substring(match.range.last + 1).trimStart()
                require(times.size <= 32)
            }
            if (times.isNotEmpty()) {
                val text = rest.replace(wordTime, "").trim().take(1000)
                times.forEach { parsed.add(LyricLine(it, text)) }
                require(parsed.size <= 2000)
            }
        }
        val lines = parsed.groupBy { it.timeMs }.toSortedMap().map { (time, entries) ->
            LyricLine(time, entries.map { it.text }.distinct().joinToString("\n"))
        }
        return if (lines.any { it.text.isNotBlank() }) LyricsDocument(lines = lines)
        else LyricsDocument(plain = plain.trim())
    }
}

internal fun normalizedTrackText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
