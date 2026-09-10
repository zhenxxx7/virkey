package com.virkey.app.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HidDescriptorTest {
    @Test
    fun `descriptor report bit counts agree with actual payloads`() {
        val fields = parseFields(HidDescriptor.bytes)
        assertEquals(64, fields.filter { it.reportId == HidDescriptor.KEYBOARD_REPORT_ID && it.kind == INPUT }.sumOf { it.size * it.count })
        assertEquals(8, fields.filter { it.reportId == HidDescriptor.KEYBOARD_REPORT_ID && it.kind == OUTPUT }.sumOf { it.size * it.count })
        assertEquals(32, fields.filter { it.reportId == HidDescriptor.MOUSE_REPORT_ID && it.kind == INPUT }.sumOf { it.size * it.count })
        assertEquals(16, fields.filter { it.reportId == HidDescriptor.CONSUMER_REPORT_ID && it.kind == INPUT }.sumOf { it.size * it.count })
        assertTrue(fields.none { it.reportId == HidDescriptor.MOUSE_REPORT_ID && it.kind == OUTPUT })
        assertTrue(fields.none { it.reportId == HidDescriptor.CONSUMER_REPORT_ID && it.kind == OUTPUT })
    }

    @Test
    fun `mouse axes are signed relative eight bit fields with X Y and wheel usages`() {
        val axes = parseFields(HidDescriptor.bytes).single {
            it.reportId == HidDescriptor.MOUSE_REPORT_ID && it.usagePage == 1 && it.kind == INPUT
        }
        assertEquals(listOf(0x30, 0x31, 0x38), axes.usages)
        assertEquals(-127, axes.minimum)
        assertEquals(127, axes.maximum)
        assertEquals(8, axes.size)
        assertEquals(3, axes.count)
        assertEquals(0x06, axes.flags)
    }

    @Test
    fun `keyboard array represents six usages and accepts positive high usage codes`() {
        val array = parseFields(HidDescriptor.bytes).single {
            it.reportId == HidDescriptor.KEYBOARD_REPORT_ID && it.kind == INPUT && it.flags == 0
        }
        assertEquals(7, array.usagePage)
        assertEquals(0, array.minimum)
        assertEquals(0xE7, array.maximum)
        assertEquals(6, array.count)
        assertEquals(8, array.size)
    }

    @Test
    fun `media report is one unsigned sixteen bit consumer usage with zero as release`() {
        val field = parseFields(HidDescriptor.bytes).single {
            it.reportId == HidDescriptor.CONSUMER_REPORT_ID && it.kind == INPUT
        }
        assertEquals(0x0C, field.usagePage)
        assertEquals(0, field.minimum)
        assertEquals(0x03FF, field.maximum)
        assertEquals(0, field.usageMinimum)
        assertEquals(0x03FF, field.usageMaximum)
        assertEquals(16, field.size)
        assertEquals(1, field.count)
        assertEquals("Data, Array, Absolute", 0, field.flags)
        val usages = listOf(MediaKeys.MUTE, MediaKeys.VOLUME_DOWN, MediaKeys.VOLUME_UP,
            MediaKeys.PREVIOUS, MediaKeys.PLAY_PAUSE, MediaKeys.NEXT, MediaKeys.STOP)
        assertTrue(usages.all { it in field.minimum..field.maximum })
    }

    @Test
    fun `descriptor reads return independent byte arrays`() {
        HidDescriptor.bytes.fill(0)
        assertEquals(0x05, HidDescriptor.bytes.first().toInt())
    }

    private data class Field(
        val reportId: Int, val kind: Int, val size: Int, val count: Int,
        val minimum: Int, val maximum: Int, val flags: Int, val usagePage: Int,
        val usages: List<Int>,
        val usageMinimum: Int?, val usageMaximum: Int?,
    )

    /** Decode HID short items rather than asserting one fixed descriptor byte sequence. */
    private fun parseFields(bytes: ByteArray): List<Field> {
        val fields = mutableListOf<Field>()
        var cursor = 0
        var reportId = 0
        var size = 0
        var count = 0
        var minimum = 0
        var maximum = 0
        var usagePage = 0
        var collections = 0
        var applicationCollections = 0
        var usageMinimum: Int? = null
        var usageMaximum: Int? = null
        val usages = mutableListOf<Int>()
        while (cursor < bytes.size) {
            val prefix = bytes[cursor++].toInt() and 0xFF
            require(prefix != 0xFE) { "This descriptor uses only short items" }
            val length = when (prefix and 3) { 3 -> 4; else -> prefix and 3 }
            val type = (prefix shr 2) and 3
            val tag = prefix shr 4
            require(cursor + length <= bytes.size) { "Truncated HID item" }
            var value = 0
            repeat(length) { offset -> value = value or ((bytes[cursor + offset].toInt() and 0xFF) shl (8 * offset)) }
            val signedValue = if (length in 1..3) (value shl (32 - length * 8)) shr (32 - length * 8) else value
            cursor += length
            when (type) {
                1 -> when (tag) {
                    0 -> usagePage = value
                    1 -> minimum = signedValue
                    2 -> maximum = if (minimum < 0) signedValue else value
                    7 -> size = value
                    8 -> reportId = value
                    9 -> count = value
                }
                2 -> when (tag) {
                    0 -> usages.add(value)
                    1 -> usageMinimum = value
                    2 -> usageMaximum = value
                }
                0 -> {
                    when (tag) {
                        INPUT, OUTPUT -> fields.add(Field(reportId, tag, size, count, minimum, maximum, value, usagePage, usages.toList(), usageMinimum, usageMaximum))
                        0xA -> {
                            if (value == 1) {
                                assertEquals("Application collection must be top-level", 0, collections)
                                applicationCollections++
                            }
                            collections++
                        }
                        0xC -> { collections--; require(collections >= 0) }
                    }
                    usages.clear()
                    usageMinimum = null
                    usageMaximum = null
                }
            }
        }
        assertEquals("Unclosed HID collections", 0, collections)
        assertEquals("Keyboard, mouse, and consumer application collections", 3, applicationCollections)
        return fields
    }

    private companion object {
        const val INPUT = 8
        const val OUTPUT = 9
    }
}
