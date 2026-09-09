package com.virkey.app.input

data class KeySpec(
    val label: String,
    val usage: Int,
    val weight: Float = 1f,
    val secondary: String? = null,
)

/**
 * US ANSI legends and USB keyboard usages. Every row spans 15 key-width units.
 * A negative usage is a non-interactive spacer, preserving the inverted-T arrow cluster.
 */
object LaptopLayout {
    val rows: List<List<KeySpec>> = listOf(
        listOf(KeySpec("Esc", 0x29)) +
            (1..12).map { KeySpec("F$it", 0x39 + it) } +
            KeySpec("Delete", 0x4C, 2f),
        listOf(
            KeySpec("`", 0x35, secondary = "~"),
            KeySpec("1", 0x1E, secondary = "!"),
            KeySpec("2", 0x1F, secondary = "@"),
            KeySpec("3", 0x20, secondary = "#"),
            KeySpec("4", 0x21, secondary = "$"),
            KeySpec("5", 0x22, secondary = "%"),
            KeySpec("6", 0x23, secondary = "^"),
            KeySpec("7", 0x24, secondary = "&"),
            KeySpec("8", 0x25, secondary = "*"),
            KeySpec("9", 0x26, secondary = "("),
            KeySpec("0", 0x27, secondary = ")"),
            KeySpec("-", 0x2D, secondary = "_"),
            KeySpec("=", 0x2E, secondary = "+"),
            KeySpec("Backspace", 0x2A, 2f),
        ),
        listOf(
            KeySpec("Tab", 0x2B, 1.5f),
            KeySpec("Q", 0x14), KeySpec("W", 0x1A), KeySpec("E", 0x08),
            KeySpec("R", 0x15), KeySpec("T", 0x17), KeySpec("Y", 0x1C),
            KeySpec("U", 0x18), KeySpec("I", 0x0C), KeySpec("O", 0x12),
            KeySpec("P", 0x13),
            KeySpec("[", 0x2F, secondary = "{"),
            KeySpec("]", 0x30, secondary = "}"),
            KeySpec("\\", 0x31, 1.5f, "|"),
        ),
        listOf(
            KeySpec("Caps", 0x39, 1.75f),
            KeySpec("A", 0x04), KeySpec("S", 0x16), KeySpec("D", 0x07),
            KeySpec("F", 0x09), KeySpec("G", 0x0A), KeySpec("H", 0x0B),
            KeySpec("J", 0x0D), KeySpec("K", 0x0E), KeySpec("L", 0x0F),
            KeySpec(";", 0x33, secondary = ":"),
            KeySpec("'", 0x34, secondary = "\""),
            KeySpec("Enter", 0x28, 2.25f),
        ),
        listOf(
            KeySpec("Shift", 0xE1, 2.25f),
            KeySpec("Z", 0x1D), KeySpec("X", 0x1B), KeySpec("C", 0x06),
            KeySpec("V", 0x19), KeySpec("B", 0x05), KeySpec("N", 0x11),
            KeySpec("M", 0x10),
            KeySpec(",", 0x36, secondary = "<"),
            KeySpec(".", 0x37, secondary = ">"),
            KeySpec("/", 0x38, secondary = "?"),
            KeySpec("Shift", 0xE5, 0.75f),
            KeySpec("↑", 0x52),
            KeySpec("", -1),
        ),
        listOf(
            KeySpec("Ctrl", 0xE0, 1.25f),
            KeySpec("Win", 0xE3, 1.25f),
            KeySpec("Alt", 0xE2, 1.25f),
            KeySpec("Space", 0x2C, 6.25f),
            KeySpec("Alt", 0xE6),
            KeySpec("Ctrl", 0xE4),
            KeySpec("←", 0x50),
            KeySpec("↓", 0x51),
            KeySpec("→", 0x4F),
        ),
    )
}
