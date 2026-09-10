package com.virkey.app.ui

/** Keep physical-button and touch-gesture ownership separate to avoid premature releases. */
internal class TrackpadButtons {
    var manualButtons: Int = 0
        private set
    var manualPressCount: Long = 0L
        private set
    private var gestureButtons = 0

    fun update(action: RemoteAction, manual: Boolean): List<RemoteAction> {
        if (action !is RemoteAction.MouseDown && action !is RemoteAction.MouseUp) return listOf(action)
        val before = manualButtons or gestureButtons
        val old = if (manual) manualButtons else gestureButtons
        val next = when (action) {
            is RemoteAction.MouseDown -> old or action.button
            is RemoteAction.MouseUp -> old and action.button.inv()
            else -> old
        }
        if (manual) manualButtons = next else gestureButtons = next
        val after = manualButtons or gestureButtons
        if (manual && action is RemoteAction.MouseDown && next != old) manualPressCount++
        return buildList {
            val released = before and after.inv()
            val pressed = after and before.inv()
            if (released != 0) add(RemoteAction.MouseUp(released))
            if (pressed != 0) add(RemoteAction.MouseDown(pressed))
        }
    }
}
