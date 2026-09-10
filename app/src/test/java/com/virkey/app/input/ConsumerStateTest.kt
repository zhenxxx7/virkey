package com.virkey.app.input

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumerStateTest {
    private val consumer = ConsumerState()

    @Test
    fun `each media control has a down and neutral up report`() {
        val usages = listOf(MediaKeys.MUTE, MediaKeys.VOLUME_DOWN, MediaKeys.VOLUME_UP,
            MediaKeys.PREVIOUS, MediaKeys.PLAY_PAUSE, MediaKeys.NEXT, MediaKeys.STOP)
        usages.forEach { usage ->
            assertArrayEquals(byteArrayOf(usage.toByte(), 0), consumer.press(usage).single())
            assertArrayEquals(ByteArray(2), consumer.release(usage).single())
        }
    }

    @Test
    fun `high consumer usages use little endian sixteen bit encoding without report ID`() {
        assertArrayEquals(byteArrayOf(0x23, 0x02), consumer.press(0x0223).single())
    }

    @Test
    fun `newer media press releases active usage before asserting new usage`() {
        consumer.press(MediaKeys.PLAY_PAUSE)
        val transition = consumer.press(MediaKeys.NEXT)
        assertEquals(2, transition.size)
        assertArrayEquals(ByteArray(2), transition[0])
        assertArrayEquals(byteArrayOf(MediaKeys.NEXT.toByte(), 0), transition[1])
        assertArrayEquals(ByteArray(2), consumer.release(MediaKeys.NEXT).single())
        assertArrayEquals("Play Pause is never replayed", ByteArray(2), consumer.report())
        assertTrue(consumer.release(MediaKeys.PLAY_PAUSE).isEmpty())
    }

    @Test
    fun `releasing superseded control leaves the newer control held`() {
        consumer.press(MediaKeys.VOLUME_UP)
        consumer.press(MediaKeys.VOLUME_DOWN)
        assertTrue(consumer.release(MediaKeys.VOLUME_UP).isEmpty())
        assertArrayEquals(byteArrayOf(MediaKeys.VOLUME_DOWN.toByte(), 0), consumer.report())
        assertArrayEquals(ByteArray(2), consumer.release(MediaKeys.VOLUME_DOWN).single())
    }

    @Test
    fun `duplicate downs and unmatched ups do not send extra commands`() {
        consumer.press(MediaKeys.PLAY_PAUSE)
        assertTrue(consumer.press(MediaKeys.PLAY_PAUSE).isEmpty())
        assertTrue(consumer.release(MediaKeys.STOP).isEmpty())
        consumer.press(MediaKeys.NEXT)
        assertTrue("A superseded held usage cannot reassert on a repeated down", consumer.press(MediaKeys.PLAY_PAUSE).isEmpty())
        consumer.release(MediaKeys.NEXT)
        assertTrue(consumer.release(MediaKeys.NEXT).isEmpty())
    }

    @Test
    fun `release all clears both active and superseded keys for a fresh session`() {
        consumer.press(MediaKeys.VOLUME_UP)
        consumer.press(MediaKeys.PLAY_PAUSE)
        assertArrayEquals(ByteArray(2), consumer.releaseAll())
        assertArrayEquals(ByteArray(2), consumer.report())
        assertTrue(consumer.release(MediaKeys.VOLUME_UP).isEmpty())
        assertTrue(consumer.release(MediaKeys.PLAY_PAUSE).isEmpty())
        assertArrayEquals(byteArrayOf(MediaKeys.PLAY_PAUSE.toByte(), 0), consumer.press(MediaKeys.PLAY_PAUSE).single())
    }

    @Test
    fun `callers cannot mutate active usage through returned reports`() {
        consumer.press(MediaKeys.MUTE).single().fill(0)
        consumer.report().fill(0)
        assertArrayEquals(byteArrayOf(MediaKeys.MUTE.toByte(), 0), consumer.report())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero is reserved for release and cannot be pressed`() {
        consumer.press(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `out of descriptor range cannot truncate to another command`() {
        consumer.press(0x100CD)
    }
}
