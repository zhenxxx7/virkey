package com.virkey.app.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaptopLayoutTest {
    @Test
    fun `six keyboard rows span a uniform width`() {
        assertEquals(6, LaptopLayout.rows.size)
        LaptopLayout.rows.forEach { row ->
            assertEquals(15f, row.sumOf { it.weight.toDouble() }.toFloat(), 0.001f)
            assertTrue(row.all { it.weight > 0f })
        }
    }

    @Test
    fun `all keys are distinct physical HID usages with no phantom Fn key`() {
        val keys = LaptopLayout.rows.flatten().filter { it.usage >= 0 }
        assertEquals(keys.size, keys.map { it.usage }.distinct().size)
        assertTrue(keys.all { it.usage in 0x04..0xE7 })
        assertTrue(keys.none { it.label == "Fn" })
        assertEquals((0x04..0x1D).toSet(), keys.filter { it.label.length == 1 && it.label[0] in 'A'..'Z' }.map { it.usage }.toSet())
    }

    @Test
    fun `function row and navigation keys use keyboard usages`() {
        val keys = LaptopLayout.rows.flatten().associateBy { it.label }
        (1..12).forEach { assertEquals(0x39 + it, keys.getValue("F$it").usage) }
        assertEquals(0x4C, keys.getValue("Delete").usage)
        assertEquals(0x52, keys.getValue("↑").usage)
        assertEquals(0x51, keys.getValue("↓").usage)
        assertEquals(0x50, keys.getValue("←").usage)
        assertEquals(0x4F, keys.getValue("→").usage)
        assertEquals(0x2A, keys.getValue("Backspace").usage)
    }

    @Test
    fun `shifted legends do not change physical key usages`() {
        val keys = LaptopLayout.rows.flatten().associateBy { it.label }
        assertEquals("@", keys.getValue("2").secondary)
        assertEquals(0x1F, keys.getValue("2").usage)
        assertEquals("|", keys.getValue("\\").secondary)
        assertEquals(0x31, keys.getValue("\\").usage)
        assertEquals("\"", keys.getValue("'").secondary)
    }

    @Test
    fun `up arrow is directly above down in inverted T cluster`() {
        fun startOf(row: List<KeySpec>, usage: Int): Float =
            row.takeWhile { it.usage != usage }.sumOf { it.weight.toDouble() }.toFloat()
        assertEquals(startOf(LaptopLayout.rows[4], 0x52), startOf(LaptopLayout.rows[5], 0x51), 0.001f)
        assertTrue(LaptopLayout.rows[4].last().usage < 0)
        assertTrue(LaptopLayout.rows[4].last().label.isEmpty())
    }
}
