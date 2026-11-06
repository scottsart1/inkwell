package com.inkwell.notes.ink.engine

import android.content.res.Configuration
import android.graphics.PointF
import android.view.KeyEvent
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.input.KeyCommand
import com.inkwell.notes.ink.input.KeyCommands
import com.inkwell.notes.ink.model.ContentOps
import com.inkwell.notes.ink.model.Items
import com.inkwell.notes.ink.tools.EditSession
import kotlin.math.hypot

/**
 * Physical keyboard (and Samsung DeX) shortcuts while the canvas has focus: delete, copy / cut /
 * paste through an internal clipboard, duplicate, select all, undo / redo, escape, and arrow-key
 * nudging (a burst of nudges is one undo step). Main thread only.
 */
internal class KeyboardController(private val e: InkEngine) {
    private var clipboard: PageContent? = null
    private var clipPageId: String? = null
    private var clipCenter: PointF? = null
    private var pasteCount = 0
    private var nudge: EditSession? = null
    private var nudgePageId: String? = null
    private val commitNudge = Runnable { flush() }

    /** True when a hardware keyboard is attached (the canvas then takes focus on touch). */
    fun hasHardwareKeyboard(): Boolean {
        val c = e.context.resources.configuration
        return c.keyboard != Configuration.KEYBOARD_NOKEYS && c.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        val cmd = KeyCommands.map(event.keyCode, event.isCtrlPressed || event.isMetaPressed, event.isShiftPressed, event.isAltPressed)
            ?: return false
        if (!applies(cmd)) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            try {
                execute(cmd)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Keyboard command failed", t)
            }
        }
        // Up (and multiple) events of a handled key are consumed too.
        return true
    }

    /** Whether [cmd] does something right now (otherwise the key goes to the system). */
    private fun applies(cmd: KeyCommand): Boolean {
        val hasSelection = e.selectionPageIndex >= 0 && e.selectedIds.isNotEmpty()
        return when (cmd) {
            KeyCommand.Delete, KeyCommand.Copy, KeyCommand.Cut, KeyCommand.Duplicate, KeyCommand.ClearSelection,
            KeyCommand.Group, KeyCommand.Ungroup -> hasSelection
            KeyCommand.Paste -> clipboard != null
            else -> true
        }
    }

    private fun execute(cmd: KeyCommand) {
        if (cmd !is KeyCommand.Nudge) flush()
        val ed = e.editor
        when (cmd) {
            KeyCommand.Delete -> ed.deleteSelection()
            KeyCommand.Copy -> copy()
            KeyCommand.Cut -> { copy(); ed.deleteSelection() }
            KeyCommand.Paste -> paste()
            KeyCommand.Duplicate -> ed.duplicateSelection()
            KeyCommand.SelectAll -> ed.selectAll(null)
            KeyCommand.Undo -> e.undo()
            KeyCommand.Redo -> e.redo()
            KeyCommand.ClearSelection -> e.clearSelection()
            KeyCommand.Group -> ed.groupSelection()
            KeyCommand.Ungroup -> ed.ungroupSelection()
            is KeyCommand.Nudge -> nudge(cmd)
        }
    }

    private fun copy() {
        val sel = e.editor.buildSelection() ?: return
        val frag = e.editor.copySelection() ?: return
        clipboard = frag
        clipPageId = sel.pageId
        clipCenter = PointF(sel.boundsPage.centerX(), sel.boundsPage.centerY())
        pasteCount = 0
    }

    private fun paste() {
        val frag = clipboard ?: return
        val page = e.editor.resolvePage(null)
        val h = e.page(page) ?: return
        val center = clipCenter
        pasteCount++
        val at = if (center != null && h.id == clipPageId && e.viewportCtl.isPageVisible(page)) {
            PointF(center.x + PASTE_OFFSET * pasteCount, center.y + PASTE_OFFSET * pasteCount)
        } else null
        e.editor.insertItems(page, frag, at, select = true)
    }

    private fun nudge(cmd: KeyCommand.Nudge) {
        val i = e.selectionPageIndex
        val ids = e.selectedIds
        if (i < 0 || ids.isEmpty()) {
            // Nothing selected: arrows scroll the canvas.
            val step = (if (cmd.large) 4f else 1f) * PAN_STEP_DP * e.density
            e.viewportCtl.panBy(-cmd.dx * step, -cmd.dy * step)
            return
        }
        // The items are lifted off the page during a drag or resize: nudging now would record
        // them as added by the nudge (undoing it would delete them). Ignore arrows until release.
        if (e.selection.isManipulating) return
        val h = e.page(i) ?: return
        if (!h.isLoaded || !e.isEditable(i)) return
        // Arrow directions are on screen; convert one screen step into page points.
        val step = if (cmd.large) LARGE_STEP else SMALL_STEP
        val a = FloatArray(2); val b = FloatArray(2)
        e.pageToView(i, 0f, 0f, a)
        e.viewToPage(i, a[0] + cmd.dx, a[1] + cmd.dy, b)
        val len = hypot(b[0], b[1]).coerceAtLeast(1e-6f)
        val dx = b[0] / len * step; val dy = b[1] / len * step
        if (nudge != null && nudgePageId != h.id) flush()
        val session = nudge ?: e.beginEdit("Nudge").also { nudge = it; nudgePageId = h.id }
        val content = session.content(i) ?: return
        val changed = Bounds()
        val tmp = Bounds()
        val moved = ContentOps.mapItems(content, ids) { item ->
            changed.union(ItemGeometry.bounds(item, tmp, content))
            Items.translate(item, dx, dy).also { changed.union(ItemGeometry.bounds(it, tmp, content)) }
        }
        // Connectors attached to moved items follow; include their area.
        for (c in content.connectors) if (c.fromItemId in ids || c.toItemId in ids) {
            changed.union(ItemGeometry.bounds(c, tmp, content)).union(ItemGeometry.bounds(c, tmp, moved))
        }
        session.update(i, moved, changed.outset(2f))
        e.reportSelection()
        e.view.removeCallbacks(commitNudge)
        e.view.postDelayed(commitNudge, NUDGE_COMMIT_MS)
    }

    /** Records pending nudges as one undo step. */
    fun flush() {
        e.view.removeCallbacks(commitNudge)
        val s = nudge ?: return
        nudge = null
        nudgePageId = null
        s.commit()
    }

    companion object {
        private const val TAG = "InkKeys"
        const val SMALL_STEP = 1f
        const val LARGE_STEP = 10f
        const val PASTE_OFFSET = 16f
        const val PAN_STEP_DP = 48f
        const val NUDGE_COMMIT_MS = 800L
    }
}
