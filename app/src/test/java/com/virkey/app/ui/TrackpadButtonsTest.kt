package com.virkey.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackpadButtonsTest {
    @Test fun gestureReleaseCannotReleaseLeftWhileManualOwnerRemains() {
        val buttons = TrackpadButtons()
        assertEquals(listOf(RemoteAction.MouseDown(1)), buttons.update(RemoteAction.MouseDown(1), manual = false))
        assertTrue(buttons.update(RemoteAction.MouseDown(1), manual = true).isEmpty())
        assertEquals(1, buttons.manualButtons)
        assertTrue(buttons.update(RemoteAction.MouseUp(1), manual = false).isEmpty())
        assertEquals(listOf(RemoteAction.MouseUp(1)), buttons.update(RemoteAction.MouseUp(1), manual = true))
        assertEquals(0, buttons.manualButtons)
    }

    @Test fun manualReleaseCannotReleaseLeftWhileGestureOwnerRemains() {
        val buttons = TrackpadButtons()
        assertEquals(listOf(RemoteAction.MouseDown(1)), buttons.update(RemoteAction.MouseDown(1), manual = true))
        assertTrue(buttons.update(RemoteAction.MouseDown(1), manual = false).isEmpty())
        assertTrue(buttons.update(RemoteAction.MouseUp(1), manual = true).isEmpty())
        assertEquals(0, buttons.manualButtons)
        assertEquals(listOf(RemoteAction.MouseUp(1)), buttons.update(RemoteAction.MouseUp(1), manual = false))
    }

    @Test fun differentButtonsAndOverlappingMasksRemainIndependent() {
        val buttons = TrackpadButtons()
        assertEquals(listOf(RemoteAction.MouseDown(3)), buttons.update(RemoteAction.MouseDown(3), manual = true))
        assertTrue(buttons.update(RemoteAction.MouseDown(1), manual = false).isEmpty())
        assertEquals(listOf(RemoteAction.MouseUp(2)), buttons.update(RemoteAction.MouseUp(3), manual = true))
        assertEquals(0, buttons.manualButtons)
        assertEquals(listOf(RemoteAction.MouseUp(1)), buttons.update(RemoteAction.MouseUp(1), manual = false))
    }

    @Test fun repeatedPressReleaseAndUnownedReleaseDoNotEmitDuplicates() {
        val buttons = TrackpadButtons()
        assertTrue(buttons.update(RemoteAction.MouseUp(1), manual = false).isEmpty())
        assertTrue(buttons.update(RemoteAction.MouseUp(2), manual = true).isEmpty())
        assertEquals(listOf(RemoteAction.MouseDown(1)), buttons.update(RemoteAction.MouseDown(1), manual = false))
        assertTrue(buttons.update(RemoteAction.MouseDown(1), manual = false).isEmpty())
        assertEquals(listOf(RemoteAction.MouseUp(1)), buttons.update(RemoteAction.MouseUp(1), manual = false))
        assertTrue(buttons.update(RemoteAction.MouseUp(1), manual = false).isEmpty())
    }

    @Test fun pointerAndScrollActionsPassThroughWithoutChangingOwnership() {
        val buttons = TrackpadButtons()
        buttons.update(RemoteAction.MouseDown(2), manual = true)
        val move = RemoteAction.MovePointer(10, -4)
        val scroll = RemoteAction.Scroll(-1)
        assertEquals(listOf(move), buttons.update(move, manual = false))
        assertEquals(listOf(scroll), buttons.update(scroll, manual = false))
        assertEquals(2, buttons.manualButtons)
        assertEquals(listOf(RemoteAction.MouseUp(2)), buttons.update(RemoteAction.MouseUp(2), manual = true))
    }

    @Test fun cancellingGestureWhileLeftButtonIsHeldReleasesOnlyGestureOwnership() {
        val buttons = TrackpadButtons()
        val gesture = TrackpadGesture(startedAt = 0L, scrollDistance = 20f, tapSlop = 8f)
        val pressed = gesture.hold(500L).flatMap { buttons.update(it, manual = false) }
        assertEquals(listOf(RemoteAction.MouseDown(1)), pressed)
        assertTrue(buttons.update(RemoteAction.MouseDown(1), manual = true).isEmpty())
        assertTrue(gesture.cancel().flatMap { buttons.update(it, manual = false) }.isEmpty())
        assertEquals(1, buttons.manualButtons)
        assertEquals(listOf(RemoteAction.MouseUp(1)), buttons.update(RemoteAction.MouseUp(1), manual = true))
    }
}
