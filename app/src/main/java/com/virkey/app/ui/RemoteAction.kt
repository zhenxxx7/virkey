package com.virkey.app.ui

sealed interface RemoteAction {
    data object EnableBluetooth : RemoteAction
    data object RequestPermissions : RemoteAction
    data object PairNewDevice : RemoteAction
    data object RefreshDevices : RemoteAction
    data class Connect(val address: String) : RemoteAction
    data object Disconnect : RemoteAction
    data class KeyDown(val usage: Int) : RemoteAction
    data class KeyUp(val usage: Int) : RemoteAction
    data object ReleaseAll : RemoteAction
    data class MovePointer(val dx: Int, val dy: Int) : RemoteAction
    data class Scroll(val amount: Int) : RemoteAction
    /** HID button masks: left = 1, right = 2, middle = 4. */
    data class MouseDown(val button: Int) : RemoteAction
    data class MouseUp(val button: Int) : RemoteAction
}
