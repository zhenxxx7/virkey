package com.virkey.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackpadGestureTest {
    private fun gesture() = TrackpadGesture(startedAt = 0L, scrollDistance = 20f, tapSlop = 8f)

    @Test fun quickSingleFingerTapClicksLeftOnce() {
        val gesture = gesture()
        assertEquals(
            listOf(RemoteAction.MouseDown(1), RemoteAction.MouseUp(1)),
            gesture.update(90L, listOf(TouchDelta(pressed = false))),
        )
        assertTrue(gesture.update(100L, listOf(TouchDelta(pressed = false))).isEmpty())
    }

    @Test fun twoFingerTapClicksRightEvenWhenFingersLiftSeparately() {
        val gesture = gesture()
        assertTrue(gesture.update(30L, listOf(TouchDelta(), TouchDelta(previouslyPressed = false))).isEmpty())
        assertTrue(gesture.update(70L, listOf(TouchDelta(pressed = false), TouchDelta())).isEmpty())
        assertEquals(
            listOf(RemoteAction.MouseDown(2), RemoteAction.MouseUp(2)),
            gesture.update(100L, listOf(TouchDelta(pressed = false))),
        )
    }

    @Test fun pointerMotionKeepsFractionalDeltasAndDoesNotClickAfterMovement() {
        val gesture = gesture()
        assertTrue(gesture.update(20L, listOf(TouchDelta(dx = 0.4f))).isEmpty())
        assertEquals(listOf(RemoteAction.MovePointer(1, 0)), gesture.update(40L, listOf(TouchDelta(dx = 0.4f))))
        assertEquals(listOf(RemoteAction.MovePointer(10, -4)), gesture.update(60L, listOf(TouchDelta(dx = 10f, dy = -4f))))
        assertTrue(gesture.update(100L, listOf(TouchDelta(pressed = false))).isEmpty())
    }

    @Test fun scrollAccumulatesAndLiftingOneFingerDoesNotMoveCursor() {
        val gesture = gesture()
        gesture.update(20L, listOf(TouchDelta(), TouchDelta(previouslyPressed = false)))
        assertTrue(gesture.update(50L, listOf(TouchDelta(dy = -12f), TouchDelta(dy = -12f))).isEmpty())
        assertEquals(listOf(RemoteAction.Scroll(-1)), gesture.update(70L, listOf(TouchDelta(dy = -12f), TouchDelta(dy = -12f))))
        assertTrue(gesture.update(90L, listOf(TouchDelta(pressed = false), TouchDelta(dx = 90f))).isEmpty())
        assertTrue(gesture.update(100L, listOf(TouchDelta(dx = 10f))).isEmpty())
        assertTrue(gesture.update(120L, listOf(TouchDelta(pressed = false))).isEmpty())
    }

    @Test fun oppositeFingerMotionAndThreeFingerGesturesNeverClick() {
        val pinch = gesture()
        pinch.update(10L, listOf(TouchDelta(), TouchDelta(previouslyPressed = false)))
        assertTrue(pinch.update(40L, listOf(TouchDelta(dx = -12f), TouchDelta(dx = 12f))).isEmpty())
        assertTrue(pinch.update(80L, listOf(TouchDelta(pressed = false), TouchDelta(pressed = false))).isEmpty())
        val three = gesture()
        three.update(10L, listOf(TouchDelta(), TouchDelta(previouslyPressed = false), TouchDelta(previouslyPressed = false)))
        assertTrue(three.update(80L, List(3) { TouchDelta(pressed = false) }).isEmpty())
    }

    @Test fun LongPressWithoutMotionDoesNotClick() {
        assertTrue(gesture().update(500L, listOf(TouchDelta(pressed = false))).isEmpty())
    }

    @Test fun tapWhilePhysicalButtonIsHeldCannotReleaseADrag() {
        val gesture = gesture()
        gesture.update(20L, listOf(TouchDelta()), buttonsHeld = true)
        assertTrue(gesture.update(90L, listOf(TouchDelta(pressed = false)), buttonsHeld = true).isEmpty())
    }
}
