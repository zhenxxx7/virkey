package com.virkey.app.input

/**
 * One active consumer control, as described by the HID report's single array slot.
 * A newer press releases the previous control first. Releasing it never replays an
 * older held command (for example, Play/Pause), which could undo the original action.
 */
class ConsumerState {
    private val heldUsages = mutableSetOf<Int>()
    private var activeUsage = 0

    fun press(usage: Int): List<ByteArray> {
        requireUsage(usage)
        if (!heldUsages.add(usage)) return emptyList()
        val release = if (activeUsage != 0) byteArrayOf(0, 0) else null
        activeUsage = usage
        return listOfNotNull(release, report())
    }

    fun release(usage: Int): List<ByteArray> {
        requireUsage(usage)
        if (!heldUsages.remove(usage) || activeUsage != usage) return emptyList()
        activeUsage = 0
        return listOf(report())
    }

    fun releaseAll(): ByteArray {
        heldUsages.clear()
        activeUsage = 0
        return report()
    }

    fun report(): ByteArray = byteArrayOf(activeUsage.toByte(), (activeUsage ushr 8).toByte())

    private fun requireUsage(usage: Int) {
        require(usage in 1..0x03FF) { "Expected a consumer usage from 1 through 1023, got $usage" }
    }
}
