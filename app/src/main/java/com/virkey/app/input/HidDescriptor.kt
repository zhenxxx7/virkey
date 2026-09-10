package com.virkey.app.input

/**
 * A keyboard, relative mouse, and media remote in separate HID application collections.
 *
 * Report IDs belong in the descriptor and Bluetooth API argument, not in the payload.
 * Keyboard input: modifiers, reserved, six usage codes. Keyboard output: LED bitmap.
 * Mouse input: three button bits, signed X, signed Y, signed vertical wheel.
 * Consumer input: one unsigned, little-endian 16-bit usage (zero releases it).
 *
 * Usage definitions: https://www.usb.org/hid
 */
object HidDescriptor {
    const val KEYBOARD_REPORT_ID = 1
    const val MOUSE_REPORT_ID = 2
    const val CONSUMER_REPORT_ID = 3

    val bytes: ByteArray
        get() = intArrayOf(
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x06,       // Usage (Keyboard)
            0xA1, 0x01,       // Collection (Application)
            0x85, KEYBOARD_REPORT_ID,
            0x05, 0x07,       // Usage Page (Keyboard/Keypad)
            0x19, 0xE0,       // Usage Minimum (Left Control)
            0x29, 0xE7,       // Usage Maximum (Right GUI)
            0x15, 0x00,       // Logical Minimum (0)
            0x25, 0x01,       // Logical Maximum (1)
            0x75, 0x01,       // Report Size (1 bit)
            0x95, 0x08,       // Report Count (8 modifiers)
            0x81, 0x02,       // Input (Data, Variable, Absolute)
            0x75, 0x08,
            0x95, 0x01,
            0x81, 0x01,       // Input (Constant reserved byte)
            0x05, 0x08,       // Usage Page (LEDs)
            0x19, 0x01,       // Num Lock
            0x29, 0x05,       // Through Kana
            0x75, 0x01,
            0x95, 0x05,
            0x91, 0x02,       // Output (Data, Variable, Absolute)
            0x75, 0x03,
            0x95, 0x01,
            0x91, 0x01,       // Output (Constant padding)
            0x05, 0x07,       // Usage Page (Keyboard/Keypad)
            0x19, 0x00,
            0x29, 0xE7,
            0x15, 0x00,
            0x26, 0xE7, 0x00, // Logical Maximum (231, positive)
            0x75, 0x08,
            0x95, 0x06,
            0x81, 0x00,       // Input (Data, Array, Absolute)
            0xC0,
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x02,       // Usage (Mouse)
            0xA1, 0x01,       // Collection (Application)
            0x85, MOUSE_REPORT_ID,
            0x09, 0x01,       // Usage (Pointer)
            0xA1, 0x00,       // Collection (Physical)
            0x05, 0x09,       // Usage Page (Buttons)
            0x19, 0x01,
            0x29, 0x03,
            0x15, 0x00,
            0x25, 0x01,
            0x75, 0x01,
            0x95, 0x03,
            0x81, 0x02,       // Input (three button bits)
            0x75, 0x05,
            0x95, 0x01,
            0x81, 0x01,       // Input (Constant padding)
            0x05, 0x01,
            0x09, 0x30,       // Usage (X)
            0x09, 0x31,       // Usage (Y)
            0x09, 0x38,       // Usage (Wheel)
            0x15, 0x81,       // Logical Minimum (-127)
            0x25, 0x7F,       // Logical Maximum (127)
            0x75, 0x08,
            0x95, 0x03,
            0x81, 0x06,       // Input (Data, Variable, Relative)
            0xC0,
            0xC0,
            0x05, 0x0C,       // Usage Page (Consumer)
            0x09, 0x01,       // Usage (Consumer Control)
            0xA1, 0x01,       // Collection (Application)
            0x85, CONSUMER_REPORT_ID,
            0x15, 0x00,       // Logical Minimum (0, no event)
            0x26, 0xFF, 0x03, // Logical Maximum (1023)
            0x19, 0x00,       // Usage Minimum (Unassigned)
            0x2A, 0xFF, 0x03, // Usage Maximum (1023)
            0x75, 0x10,       // Report Size (16 bits)
            0x95, 0x01,       // Report Count (one consumer usage)
            0x81, 0x00,       // Input (Data, Array, Absolute)
            0xC0,
        ).map(Int::toByte).toByteArray()
}
