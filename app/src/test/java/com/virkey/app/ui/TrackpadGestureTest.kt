package com.virkey.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun stationaryHoldArmsAtDeadlineOnlyOnce() {
        val gesture = gesture()
        assertEquals(500L, gesture.millisUntilHold(0L))
        assertEquals(1L, gesture.millisUntilHold(499L))
        assertTrue(gesture.hold(499L).isEmpty())
        assertFalse(gesture.isDragging)
        assertEquals(0L, gesture.millisUntilHold(500L))
        assertEquals(listOf(RemoteAction.MouseDown(1)), gesture.hold(500L))
        assertTrue(gesture.isDragging)
        assertNull(gesture.millisUntilHold(501L))
        assertTrue(gesture.hold(600L).isEmpty())
        assertEquals(listOf(RemoteAction.MouseUp(1)), gesture.update(650L, listOf(TouchDelta(pressed = false))))
        assertFalse(gesture.isDragging)
        assertTrue(gesture.cancel().isEmpty())
    }

    @Test fun lateFirstMotionStartsDragBeforeMovingAndLiftReleases() {
        val gesture = gesture()
        assertEquals(
            listOf(RemoteAction.MouseDown(1), RemoteAction.MovePointer(12, -4)),
            gesture.update(500L, listOf(TouchDelta(dx = 12f, dy = -4f))),
        )
        assertTrue(gesture.isDragging)
        assertEquals(listOf(RemoteAction.MovePointer(30, 10)), gesture.update(550L, listOf(TouchDelta(dx = 30f, dy = 10f))))
        assertEquals(listOf(RemoteAction.MouseUp(1)), gesture.update(700L, listOf(TouchDelta(pressed = false))))
        assertFalse(gesture.isDragging)
        assertTrue(gesture.update(710L, listOf(TouchDelta(pressed = false))).isEmpty())
    }

    @Test fun cancellationReleasesActiveDragExactlyOnceAndPreventsRearming() {
        val gesture = gesture()
        gesture.hold(500L)
        assertEquals(listOf(RemoteAction.MouseUp(1)), gesture.cancel())
        assertFalse(gesture.isDragging)
        assertTrue(gesture.cancel().isEmpty())
        assertNull(gesture.millisUntilHold(700L))
        assertTrue(gesture.hold(700L).isEmpty())
        assertTrue(gesture.update(750L, listOf(TouchDelta(dx = 20f))).isEmpty())
    }

    @Test fun cancellingBeforeHoldDoesNotReleaseAnUnownedButton() {
        val gesture = gesture()
        assertTrue(gesture.cancel().isEmpty())
        assertNull(gesture.millisUntilHold(500L))
        assertTrue(gesture.hold(500L).isEmpty())
        assertTrue(gesture.update(600L, listOf(TouchDelta(pressed = false))).isEmpty())
    }

    @Test fun movementReachingSlopBeforeDeadlinePermanentlyDisablesHold() {
        val gesture = gesture()
        assertEquals(listOf(RemoteAction.MovePointer(8, 0)), gesture.update(100L, listOf(TouchDelta(dx = 8f))))
        assertNull(gesture.millisUntilHold(100L))
        assertTrue(gesture.hold(500L).isEmpty())
        assertEquals(listOf(RemoteAction.MovePointer(10, 0)), gesture.update(550L, listOf(TouchDelta(dx = 10f))))
        assertFalse(gesture.isDragging)
        assertTrue(gesture.update(700L, listOf(TouchDelta(pressed = false))).isEmpty())
    }

    @Test fun smallMovementBeforeDeadlineStillPermitsHold() {
        val gesture = gesture()
        gesture.update(100L, listOf(TouchDelta(dx = 2f)))
        assertEquals(400L, gesture.millisUntilHold(100L))
        assertEquals(listOf(RemoteAction.MouseDown(1)), gesture.hold(500L))
        assertEquals(listOf(RemoteAction.MouseUp(1)), gesture.cancel())
    }

    @Test fun secondFingerBeforeDeadlinePermanentlyDisablesHold() {
        val gesture = gesture()
        gesture.update(100L, listOf(TouchDelta(), TouchDelta(previouslyPressed = false)))
        assertNull(gesture.millisUntilHold(100L))
        assertTrue(gesture.hold(500L).isEmpty())
        gesture.update(550L, listOf(TouchDelta(), TouchDelta(pressed = false)))
        assertNull(gesture.millisUntilHold(600L))
        assertTrue(gesture.hold(600L).isEmpty())
        assertFalse(gesture.isDragging)
    }

    @Test fun secondFingerJoiningAfterDeadlineDoesNotSynthesizeDrag() {
        val gesture = gesture()
        assertTrue(gesture.update(600L, listOf(TouchDelta(), TouchDelta(previouslyPressed = false))).isEmpty())
        assertFalse(gesture.isDragging)
        assertNull(gesture.millisUntilHold(600L))
    }

    @Test fun secondFingerReleasesActiveDragThenAllowsScrollWithoutClick() {
        val gesture = gesture()
        gesture.hold(500L)
        assertEquals(
            listOf(RemoteAction.MouseUp(1)),
            gesture.update(550L, listOf(TouchDelta(), TouchDelta(previouslyPressed = false))),
        )
        assertFalse(gesture.isDragging)
        assertNull(gesture.millisUntilHold(550L))
        assertEquals(listOf(RemoteAction.Scroll(1)), gesture.update(600L, List(2) { TouchDelta(dy = 20f) }))
        assertTrue(gesture.update(650L, listOf(TouchDelta(), TouchDelta(pressed = false))).isEmpty())
        assertTrue(gesture.update(700L, listOf(TouchDelta(dx = 30f))).isEmpty())
        assertTrue(gesture.update(750L, listOf(TouchDelta(pressed = false))).isEmpty())
        assertTrue(gesture.cancel().isEmpty())
    }

    @Test fun manualButtonSeenByTimerPreventsHoldEvenAfterButtonIsReleased() {
        val gesture = gesture()
        assertNull(gesture.millisUntilHold(100L, buttonsHeld = true))
        assertNull(gesture.millisUntilHold(200L, buttonsHeld = false))
        assertTrue(gesture.hold(500L).isEmpty())
        assertFalse(gesture.isDragging)
        assertEquals(listOf(RemoteAction.MovePointer(5, 0)), gesture.update(600L, listOf(TouchDelta(dx = 5f))))
        assertTrue(gesture.update(650L, listOf(TouchDelta(pressed = false))).isEmpty())
    }

    @Test fun holdWhileManualButtonIsHeldNeverAcquiresOrReleasesIt() {
        val gesture = gesture()
        assertTrue(gesture.hold(500L, buttonsHeld = true).isEmpty())
        assertEquals(listOf(RemoteAction.MovePointer(15, 0)), gesture.update(550L, listOf(TouchDelta(dx = 15f)), buttonsHeld = true))
        assertTrue(gesture.update(700L, listOf(TouchDelta(pressed = false)), buttonsHeld = true).isEmpty())
        assertTrue(gesture.cancel().isEmpty())
    }

    @Test fun customHoldDelayIsRelativeToInitialDownTime() {
        val gesture = TrackpadGesture(startedAt = 1_000L, scrollDistance = 20f, tapSlop = 8f, holdDelayMillis = 600L)
        assertEquals(600L, gesture.millisUntilHold(1_000L))
        assertTrue(gesture.hold(1_599L).isEmpty())
        assertEquals(listOf(RemoteAction.MouseDown(1)), gesture.hold(1_600L))
    }
}
