package com.virkey.app.input

object MouseReports {
    const val BUTTON_LEFT = 1
    const val BUTTON_RIGHT = 2
    const val BUTTON_MIDDLE = 4

    fun encode(buttons: Int, dx: Int = 0, dy: Int = 0, wheel: Int = 0): ByteArray =
        byteArrayOf(
            (buttons and 0x07).toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte(),
        )

    /** Preserve the complete movement in multiple reports instead of truncating fast swipes. */
    fun chunked(buttons: Int, dx: Int = 0, dy: Int = 0, wheel: Int = 0): List<ByteArray> {
        var remainingX = dx
        var remainingY = dy
        var remainingWheel = wheel
        return buildList {
            do {
                val x = remainingX.coerceIn(-127, 127)
                val y = remainingY.coerceIn(-127, 127)
                val scroll = remainingWheel.coerceIn(-127, 127)
                add(encode(buttons, x, y, scroll))
                remainingX -= x
                remainingY -= y
                remainingWheel -= scroll
            } while (remainingX != 0 || remainingY != 0 || remainingWheel != 0)
        }
    }
}
