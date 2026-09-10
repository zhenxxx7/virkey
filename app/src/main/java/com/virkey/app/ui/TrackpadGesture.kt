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
    private val holdDelayMillis: Long = 500L,
) {
    private var maxFingers = 1
    private var travel = 0f
    private var carryX = 0f
    private var carryY = 0f
    private var carryScroll = 0f
    private var finished = false
    private var suppressTap = false
    var isDragging = false
        private set

    init {
        require(scrollDistance > 0f)
        require(tapSlop > 0f)
        require(holdDelayMillis > 0L)
    }

    fun millisUntilHold(time: Long, buttonsHeld: Boolean = false): Long? {
        suppressTap = suppressTap || buttonsHeld
        if (finished || isDragging || suppressTap || maxFingers != 1 || travel >= tapSlop) return null
        return (holdDelayMillis - (time - startedAt)).coerceAtLeast(0L)
    }

    fun hold(time: Long, buttonsHeld: Boolean = false): List<RemoteAction> {
        if (millisUntilHold(time, buttonsHeld) != 0L) return emptyList()
        isDragging = true
        suppressTap = true
        return listOf(RemoteAction.MouseDown(1))
    }

    fun cancel(): List<RemoteAction> {
        finished = true
        return releaseDrag()
    }

    private fun releaseDrag(): List<RemoteAction> {
        if (!isDragging) return emptyList()
        isDragging = false
        return listOf(RemoteAction.MouseUp(1))
    }

    fun update(time: Long, points: List<TouchDelta>, buttonsHeld: Boolean = false): List<RemoteAction> {
        if (finished || points.isEmpty()) return emptyList()
        suppressTap = suppressTap || buttonsHeld
        val pressed = points.filter { it.pressed }
        maxFingers = maxOf(maxFingers, pressed.size)
        val actions = mutableListOf<RemoteAction>()
        if (maxFingers > 1) actions += releaseDrag()
        // A stationary hold can expire before the next motion event is delivered.
        // Never synthesize a press on lift or while another finger is joining.
        if (pressed.size == 1 && points.all { it.pressed == it.previouslyPressed }) {
            actions += hold(time, buttonsHeld)
        }
        // Individual motion matters: opposite finger movements must not become a right-click.
        travel += points.filter { it.previouslyPressed }.maxOfOrNull { hypot(it.dx, it.dy) } ?: 0f
        if (pressed.isEmpty()) {
            finished = true
            if (isDragging) return releaseDrag()
            return if (!suppressTap && time - startedAt in 0L until 350L && travel < tapSlop && maxFingers <= 2) {
                val button = if (maxFingers == 2) 2 else 1
                listOf(RemoteAction.MouseDown(button), RemoteAction.MouseUp(button))
            } else {
                emptyList()
            }
        }
        // Adding or lifting a finger must never jump the cursor or scroll the document.
        if (points.any { it.pressed != it.previouslyPressed }) return actions
        val dx = pressed.sumOf { it.dx.toDouble() }.toFloat() / pressed.size
        val dy = pressed.sumOf { it.dy.toDouble() }.toFloat() / pressed.size
        return actions + when {
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
