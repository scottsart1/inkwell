package com.inkwell.notes.ink.input

import com.inkwell.notes.core.model.SideButtonMode
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.input.StylusButtonStateMachine.Companion.BUTTON_STYLUS_PRIMARY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure decision rules of the input router and the multi-finger tap tracker. */
class InputRulesTest {

    @Test fun multiFingerTapTrackerCanBeCancelledBetweenGestures() {
        val tap = MultiFingerTapTracker(tapTimeoutMs = 320, slopPx = 20f)
        // A one-finger scroll begins the tracker and never ends it (single fingers are not multi taps).
        tap.begin(0); tap.moved(100f)
        assertTrue(tap.active)
        tap.cancel()
        assertFalse(tap.active)
        // The next two-finger tap must count on its own.
        tap.begin(5000); tap.pointerCount(2)
        assertEquals(2, tap.end(5200))
        assertFalse(tap.active)
    }

    @Test fun stylusLongPressIsNotArmedForInkingOrErasing() {
        // Long-pressing with the eraser must never cancel (restore) what it erased to show a menu.
        assertFalse(InputRouter.allowsStylusLongPress(ToolType.ERASER, isInking = false))
        assertFalse(InputRouter.allowsStylusLongPress(ToolType.PEN, isInking = true))
        assertFalse(InputRouter.allowsStylusLongPress(ToolType.HIGHLIGHTER, isInking = true))
        assertTrue(InputRouter.allowsStylusLongPress(ToolType.LASSO, isInking = false))
        assertTrue(InputRouter.allowsStylusLongPress(ToolType.HAND, isInking = false))
        assertTrue(InputRouter.allowsStylusLongPress(ToolType.TEXT, isInking = false))
    }

    @Test fun releasingHoldToLassoEndsALoop() {
        // Drawing a lasso loop: releasing the button ends the contact.
        assertTrue(InputRouter.endsContactOnLassoRelease(ToolType.LASSO, SideButtonMode.HOLD_TO_LASSO, temporaryTool = null))
        // Button still (or again) held, another mode, or a non-lasso contact: switch tools as usual.
        assertFalse(InputRouter.endsContactOnLassoRelease(ToolType.LASSO, SideButtonMode.HOLD_TO_LASSO, temporaryTool = ToolType.LASSO))
        assertFalse(InputRouter.endsContactOnLassoRelease(ToolType.LASSO, SideButtonMode.HOLD_TO_ERASE, temporaryTool = null))
        assertFalse(InputRouter.endsContactOnLassoRelease(ToolType.PEN, SideButtonMode.HOLD_TO_LASSO, temporaryTool = null))
        assertFalse(InputRouter.endsContactOnLassoRelease(null, SideButtonMode.HOLD_TO_LASSO, temporaryTool = null))
    }

    @Test fun sideButtonChangeKeepsARunningSelectionManipulation() {
        // Moving, resizing or rotating the selection (or a pending tap on it): releasing hold-to-lasso
        // back to the pen, or pressing it mid-contact, keeps the same manipulation. Restarting it
        // would ink from a rotate handle the commit moved, or turn one press into a double tap.
        assertTrue(InputRouter.keepsSelectionManipulation(manipulatingSelection = true, ToolType.PEN))
        assertTrue(InputRouter.keepsSelectionManipulation(manipulatingSelection = true, ToolType.LASSO))
        assertTrue(InputRouter.keepsSelectionManipulation(manipulatingSelection = true, ToolType.HIGHLIGHTER))
        // The eraser never acts on the selection: commit and switch (or end the contact).
        assertFalse(InputRouter.keepsSelectionManipulation(manipulatingSelection = true, ToolType.ERASER))
        // Not on the selection: the usual commit-and-switch path.
        assertFalse(InputRouter.keepsSelectionManipulation(manipulatingSelection = false, ToolType.PEN))
    }

    @Test fun toggleModeDoesNotToggleAgainWhenTheHeldButtonSurvivesALostHover() {
        val sm = StylusButtonStateMachine()
        sm.mode = SideButtonMode.TOGGLE_ERASER
        sm.setBaseTool(ToolType.PEN)
        var t = 0L
        fun ev(p: StylusPhase, b: Int) = sm.onEvent(p, PointerKind.STYLUS, b, t.also { t += 8 })
        assertEquals(ToolType.ERASER, ev(StylusPhase.HOVER_MOVE, BUTTON_STYLUS_PRIMARY).requestTool)
        sm.setBaseTool(ToolType.ERASER)
        ev(StylusPhase.HOVER_EXIT, BUTTON_STYLUS_PRIMARY)
        // The pen left hover range with the button still down and comes back 300 ms later, still down.
        assertFalse(sm.onTimeout(t + 300).temporaryChanged)
        t += 300
        assertNull("re-entering with the button still held is not a new press", ev(StylusPhase.HOVER_ENTER, BUTTON_STYLUS_PRIMARY).requestTool)
        assertNull(ev(StylusPhase.DOWN, BUTTON_STYLUS_PRIMARY).requestTool)
        assertEquals(ToolType.ERASER, sm.effectiveTool)
        // Releasing and pressing again is a real press.
        ev(StylusPhase.MOVE, 0)
        assertEquals(ToolType.PEN, ev(StylusPhase.MOVE, BUTTON_STYLUS_PRIMARY).requestTool)
    }

    @Test fun toggleModeStillCountsAnExplicitPressAfterALostHover() {
        val sm = StylusButtonStateMachine()
        sm.mode = SideButtonMode.TOGGLE_ERASER
        sm.setBaseTool(ToolType.PEN)
        var t = 0L
        fun ev(p: StylusPhase, b: Int) = sm.onEvent(p, PointerKind.STYLUS, b, t.also { t += 8 })
        ev(StylusPhase.HOVER_MOVE, BUTTON_STYLUS_PRIMARY)
        sm.setBaseTool(ToolType.ERASER)
        ev(StylusPhase.HOVER_EXIT, BUTTON_STYLUS_PRIMARY)
        sm.onTimeout(t + 300)
        t += 300
        // Released out of range and pressed again in range: the framework reports a BUTTON_PRESS.
        assertEquals(ToolType.PEN, ev(StylusPhase.BUTTON_PRESS, BUTTON_STYLUS_PRIMARY).requestTool)
    }

    @Test fun holdModeReleasesAfterALostHoverAndReArmsOnReEntry() {
        val sm = StylusButtonStateMachine()
        sm.mode = SideButtonMode.HOLD_TO_ERASE
        sm.setBaseTool(ToolType.PEN)
        var t = 0L
        fun ev(p: StylusPhase, b: Int) = sm.onEvent(p, PointerKind.STYLUS, b, t.also { t += 8 })
        assertEquals(ToolType.ERASER, ev(StylusPhase.HOVER_MOVE, BUTTON_STYLUS_PRIMARY).effectiveTool)
        ev(StylusPhase.HOVER_EXIT, BUTTON_STYLUS_PRIMARY)
        val released = sm.onTimeout(t + 300)
        assertTrue(released.temporaryChanged)
        assertEquals(ToolType.PEN, released.effectiveTool)
        t += 300
        val back = ev(StylusPhase.HOVER_ENTER, BUTTON_STYLUS_PRIMARY)
        assertEquals(ToolType.ERASER, back.effectiveTool)
        assertTrue(back.temporaryChanged)
    }
}
