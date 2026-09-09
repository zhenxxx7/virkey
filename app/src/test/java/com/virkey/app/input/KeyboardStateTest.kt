package com.virkey.app.input

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardStateTest {
    private val keyboard = KeyboardState()

    @Test
    fun `a physical key has a down report and an empty up report`() {
        assertArrayEquals(byteArrayOf(0, 0, 4, 0, 0, 0, 0, 0), keyboard.press(0x04))
        assertArrayEquals(ByteArray(8), keyboard.release(0x04))
    }

    @Test
    fun `modifiers use bitmap and can be released independently of ordinary keys`() {
        keyboard.press(0xE0)
        keyboard.press(0xE1)
        keyboard.press(0xE6)
        assertArrayEquals(byteArrayOf(0x43, 0, 6, 0, 0, 0, 0, 0), keyboard.press(0x06))
        assertArrayEquals(byteArrayOf(0x41, 0, 6, 0, 0, 0, 0, 0), keyboard.release(0xE1))
        assertArrayEquals(byteArrayOf(0x41, 0, 0, 0, 0, 0, 0, 0), keyboard.release(0x06))
    }

    @Test
    fun `all eight modifiers fit in first byte without occupying key slots`() {
        (0xE0..0xE7).forEach(keyboard::press)
        (4..9).forEach(keyboard::press)
        assertArrayEquals(byteArrayOf(-1, 0, 4, 5, 6, 7, 8, 9), keyboard.report())
        assertEquals(0x7F, keyboard.release(0xE7)[0].toInt())
    }

    @Test
    fun `duplicate downs and unmatched ups are idempotent`() {
        repeat(20) { keyboard.press(4) }
        keyboard.release(5)
        assertArrayEquals(byteArrayOf(0, 0, 4, 0, 0, 0, 0, 0), keyboard.report())
        keyboard.release(4)
        assertArrayEquals(ByteArray(8), keyboard.release(4))
    }

    @Test
    fun `releasing a held key preserves the other held keys`() {
        listOf(4, 5, 6).forEach(keyboard::press)
        assertArrayEquals(byteArrayOf(0, 0, 4, 6, 0, 0, 0, 0), keyboard.release(5))
    }

    @Test
    fun `more than six ordinary keys sends rollover then recovers after release`() {
        keyboard.press(0xE3)
        (4..10).forEach(keyboard::press)
        assertArrayEquals(byteArrayOf(8, 0, 1, 1, 1, 1, 1, 1), keyboard.report())
        assertArrayEquals(byteArrayOf(8, 0, 4, 5, 6, 8, 9, 10), keyboard.release(7))
        keyboard.press(11)
        assertArrayEquals(byteArrayOf(8, 0, 1, 1, 1, 1, 1, 1), keyboard.report())
        assertArrayEquals(ByteArray(8), keyboard.releaseAll())
    }

    @Test
    fun `safety release clears modifiers and regular keys`() {
        keyboard.press(0xE0)
        keyboard.press(4)
        assertArrayEquals(ByteArray(8), keyboard.releaseAll())
        assertArrayEquals(ByteArray(8), keyboard.report())
        assertArrayEquals(byteArrayOf(0, 0, 5, 0, 0, 0, 0, 0), keyboard.press(5))
    }

    @Test
    fun `report consumers cannot mutate held state`() {
        keyboard.press(4).fill(127)
        keyboard.report().fill(127)
        assertArrayEquals(byteArrayOf(0, 0, 4, 0, 0, 0, 0, 0), keyboard.report())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reserved no-event usage cannot be pressed`() {
        keyboard.press(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `out of range usages cannot be truncated to other keys`() {
        keyboard.press(0x104)
    }
}
