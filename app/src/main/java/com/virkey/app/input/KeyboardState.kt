package com.virkey.app.input

/** Physical key state. Repeated downs are idempotent; the host handles key repeat. */
class KeyboardState {
    private var modifiers = 0
    private val heldKeys = linkedSetOf<Int>()

    @Synchronized
    fun press(usage: Int): ByteArray {
        requireKeyUsage(usage)
        if (usage in MODIFIER_START..MODIFIER_END) {
            modifiers = modifiers or (1 shl (usage - MODIFIER_START))
        } else {
            heldKeys.add(usage)
        }
        return report()
    }

    @Synchronized
    fun release(usage: Int): ByteArray {
        requireKeyUsage(usage)
        if (usage in MODIFIER_START..MODIFIER_END) {
            modifiers = modifiers and (1 shl (usage - MODIFIER_START)).inv()
        } else {
            heldKeys.remove(usage)
        }
        return report()
    }

    @Synchronized
    fun releaseAll(): ByteArray {
        modifiers = 0
        heldKeys.clear()
        return report()
    }

    @Synchronized
    fun report(): ByteArray = ByteArray(8).also { bytes ->
        bytes[0] = modifiers.toByte()
        if (heldKeys.size > KEY_SLOTS) {
            // HID requires ErrorRollOver in every array slot, while retaining modifiers.
            // Keep all actual held keys so a subsequent release recovers the correct state.
            bytes.fill(ERROR_ROLLOVER, fromIndex = 2)
        } else {
            heldKeys.forEachIndexed { index, usage -> bytes[index + 2] = usage.toByte() }
        }
    }

    private fun requireKeyUsage(usage: Int) {
        require(usage in 0x04..MODIFIER_END) {
            "Expected a keyboard usage from 0x04 through 0xE7, got $usage"
        }
    }

    private companion object {
        const val MODIFIER_START = 0xE0
        const val MODIFIER_END = 0xE7
        const val KEY_SLOTS = 6
        const val ERROR_ROLLOVER: Byte = 0x01
    }
}
