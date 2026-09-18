package com.virkey.app.media

import com.virkey.app.network.NowPlayingState
import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    @Test fun parsesRepeatedTimestampsAndPositiveOffset() {
        val doc = LrcParser.parse("[offset:+250]\n[00:01.5][00:10.250]Original line", "")
        assertEquals(listOf(LyricLine(1250, "Original line"), LyricLine(10000, "Original line")), doc.lines)
    }

    @Test fun sortsTimestampsAndGroupsTranslationsWithoutDroppingThem() {
        val doc = LrcParser.parse("[00:20]Second\n[00:10]First\n[00:10]Translation\n[00:10]First", "")
        assertEquals(listOf(LyricLine(10000, "First\nTranslation"), LyricLine(20000, "Second")), doc.lines)
    }

    @Test fun parsesFractionPrecisionsAndLegacyColonSeparator() {
        val doc = LrcParser.parse("[00:01.1]A\n[00:02.12]B\n[00:03.123]C\n[01:04:50]D", "")
        assertEquals(listOf(1100L, 2120L, 3123L, 64500L), doc.lines.map { it.timeMs })
    }

    @Test fun preservesInstrumentalBreaksAndFindsActiveLineAfterSeeking() {
        val doc = LrcParser.parse("[00:10]One\n[00:20]\n[00:30]Two", "")
        assertEquals(-1, doc.activeIndex(9999))
        assertEquals(0, doc.activeIndex(10000))
        assertEquals(1, doc.activeIndex(28000))
        assertEquals("", doc.lines[1].text)
        assertEquals(2, doc.activeIndex(90000))
        assertEquals(0, doc.activeIndex(11000))
    }

    @Test fun invalidTimestampsAndMetadataNeverBecomeLyrics() {
        val doc = LrcParser.parse("[ar:Artist]\n[ti:Title]\n[00:99]Invalid\n[00:04.0]<00:04.0>Real <00:04.5>line", "")
        assertEquals(listOf(LyricLine(4000, "Real line")), doc.lines)
    }

    @Test fun fallsBackToPlainTextWhenSyncedTextHasNoUsableLines() {
        assertEquals(LyricsDocument(plain = "A plain original line"), LrcParser.parse("[00:00]\n[ar:Example]", " A plain original line "))
    }

    @Test fun negativeOffsetDelaysAndPositiveOffsetClampsAtZero() {
        assertEquals(1500L, LrcParser.parse("[offset:-500]\n[00:01]Line", "").lines.single().timeMs)
        assertEquals(0L, LrcParser.parse("[offset:2000]\n[00:01]Line", "").lines.single().timeMs)
    }

    @Test fun oversizedLyricsAndExcessiveRepeatedTagsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { LrcParser.parse("a".repeat(80_001), "") }
        assertThrows(IllegalArgumentException::class.java) { LrcParser.parse("[00:01]".repeat(33) + "Line", "") }
    }

    @Test fun missingOrOversizedMetadataNeverCreatesALookup() {
        assertNull(LyricsTrack.from(NowPlayingState()))
        assertNull(LyricsTrack.from(NowPlayingState(available = true, title = "Title")))
        assertNull(LyricsTrack.from(NowPlayingState(available = true, title = "a".repeat(513), artist = "Artist")))
        assertEquals(LyricsTrack("Title", "Artist", "Album", 0), LyricsTrack.from(
            NowPlayingState(available = true, title = " Title ", artist = " Artist ", album = " Album ", durationMs = -1)))
    }
}
