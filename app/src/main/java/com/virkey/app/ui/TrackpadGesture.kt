package com.virkey.app.ui

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Platform-free per-gesture translation, also used to verify pointer-transition behavior. */
internal data class TouchDelta(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val pressed: Boolean = true,
    val previouslyPressed: Boolean = true,
)

internal class TrackpadGesture(
    private val startedAt: Long,
    private val scrollDistance: Float,
    private val tapSlop: Float,
) {
    private var maxFingers = 1
    private var travel = 0f
    private var carryX = 0f
    private var carryY = 0f
    private var carryScroll = 0f
    private var finished = false
    private var suppressTap = false

    init {
        require(scrollDistance > 0f)
        require(tapSlop > 0f)
    }

    fun update(time: Long, points: List<TouchDelta>, buttonsHeld: Boolean = false): List<RemoteAction> {
        if (finished || points.isEmpty()) return emptyList()
        suppressTap = suppressTap || buttonsHeld
        val pressed = points.filter { it.pressed }
        maxFingers = maxOf(maxFingers, pressed.size)
        // Individual motion matters: opposite finger movements must not become a right-click.
        travel += points.filter { it.previouslyPressed }.maxOfOrNull { hypot(it.dx, it.dy) } ?: 0f
        if (pressed.isEmpty()) {
            finished = true
            return if (!suppressTap && time - startedAt in 0L until 350L && travel < tapSlop && maxFingers <= 2) {
                val button = if (maxFingers == 2) 2 else 1
                listOf(RemoteAction.MouseDown(button), RemoteAction.MouseUp(button))
            } else {
                emptyList()
            }
        }
        // Adding or lifting a finger must never jump the cursor or scroll the document.
        if (points.any { it.pressed != it.previouslyPressed }) return emptyList()
        val dx = pressed.sumOf { it.dx.toDouble() }.toFloat() / pressed.size
        val dy = pressed.sumOf { it.dy.toDouble() }.toFloat() / pressed.size
        return when {
            pressed.size == 1 && maxFingers == 1 -> {
                carryX += dx
                carryY += dy
                val wholeX = carryX.roundToInt()
                val wholeY = carryY.roundToInt()
                carryX -= wholeX
                carryY -= wholeY
                if (wholeX == 0 && wholeY == 0) emptyList() else listOf(RemoteAction.MovePointer(wholeX, wholeY))
            }
            pressed.size == 2 && maxFingers <= 2 -> {
                carryScroll += dy / scrollDistance
                if (abs(carryScroll) < 1f) {
                    emptyList()
                } else {
                    val ticks = carryScroll.toInt()
                    carryScroll -= ticks
                    listOf(RemoteAction.Scroll(ticks))
                }
            }
            // Once a gesture uses two fingers, a remaining finger cannot unexpectedly move the pointer.
            else -> emptyList()
        }
    }
}
