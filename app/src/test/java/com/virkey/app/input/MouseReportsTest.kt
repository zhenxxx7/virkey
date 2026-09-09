package com.virkey.app.input

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseReportsTest {
    @Test
    fun `relative movement is encoded signed without a report id in payload`() {
        assertArrayEquals(byteArrayOf(0, 17, -42, 1), MouseReports.encode(0, 17, -42, 1))
    }

    @Test
    fun `buttons combine and undeclared bits are masked`() {
        assertArrayEquals(byteArrayOf(3, 0, 0, 0), MouseReports.encode(3))
        assertArrayEquals(byteArrayOf(7, 0, 0, 0), MouseReports.encode(0xFF))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), MouseReports.encode(8))
    }

    @Test
    fun `axes never exceed the descriptors signed range`() {
        assertArrayEquals(byteArrayOf(1, 127, -127, -127), MouseReports.encode(1, 999, -999, -128))
        assertArrayEquals(byteArrayOf(0, -127, 127, 127), MouseReports.encode(0, Int.MIN_VALUE, Int.MAX_VALUE, 128))
    }

    @Test
    fun `chunking preserves complete displacement scroll and drag button`() {
        val reports = MouseReports.chunked(1, 350, -260, 128)
        assertEquals(3, reports.size)
        assertEquals(350, reports.sumOf { it[1].toInt() })
        assertEquals(-260, reports.sumOf { it[2].toInt() })
        assertEquals(128, reports.sumOf { it[3].toInt() })
        assertTrue(reports.all { it[0].toInt() == 1 && it.drop(1).all { axis -> axis.toInt() in -127..127 } })
    }

    @Test
    fun `stationary chunking still sends button state for clicks and releases`() {
        val reports = MouseReports.chunked(2)
        assertEquals(1, reports.size)
        assertArrayEquals(byteArrayOf(2, 0, 0, 0), reports.single())
        assertArrayEquals(ByteArray(4), MouseReports.chunked(0).single())
    }
}
