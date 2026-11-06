package com.inkwell.notes.editor.split

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.app.AppNavigator
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.editor.EditorSession
import com.inkwell.notes.editor.NotebookController
import com.inkwell.notes.editor.logic.Placement
import com.inkwell.notes.editor.receiveFragment
import com.inkwell.notes.editor.ui.KeepScreenOn
import com.inkwell.notes.editor.ui.NotebookPane
import com.inkwell.notes.editor.ui.PaneMode
import com.inkwell.notes.editor.ui.SplitHooks
import com.inkwell.notes.editor.ui.components.FloatingSurface
import com.inkwell.notes.editor.ui.components.IconAction
import com.inkwell.notes.editor.ui.selection.SelectionDragSink
import com.inkwell.notes.ui.theme.Inkwell
import kotlin.math.roundToInt

/** Drag of a selection from one pane to the other, tracked in root coordinates. */
private class PaneDrag {
    var active by mutableStateOf(false)
    var fromFirst by mutableStateOf(true)
    var position by mutableStateOf(Offset.Zero)
}

/**
 * Two documents side by side (stacked on narrow screens), each with its own canvas and slim
 * toolbar, a draggable divider (each pane keeps at least 30%), swap and close-pane buttons.
 */
@Composable
fun SplitLayout(leftDocId: String, rightDocId: String, nav: AppNavigator, keepScreenOn: Boolean) {
    KeepScreenOn(keepScreenOn)
    var first by rememberSaveable { mutableStateOf(leftDocId) }
    var second by rememberSaveable { mutableStateOf(rightDocId) }
    var fraction by rememberSaveable { mutableFloatStateOf(0.5f) }
    val controllers = remember { mutableStateMapOf<String, NotebookController>() }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val drag = remember { PaneDrag() }
    val panes = remember { HashMap<String, @Composable (Boolean, Modifier) -> Unit>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    fun otherOf(docId: String) = if (docId == first) second else first

    fun send(content: PageContent, fromDocId: String) {
        val target = controllers[otherOf(fromDocId)] ?: return
        target.receiveFragment(content, fromDocId)
        controllers[fromDocId]?.let { it.messages.show(it.str(R.string.editor_sent_to_other_pane)) }
    }

    fun closePane(docId: String) {
        val remaining = otherOf(docId)
        val origin = EditorSession.splitOrigin
        EditorSession.splitOrigin = null
        nav.back()
        if (remaining != origin) nav.openDocumentById(remaining)
    }

    fun sinkFor(docId: String) = object : SelectionDragSink {
        override fun start(rootPosition: Offset) {
            drag.fromFirst = docId == first
            drag.position = rootPosition
            drag.active = true
        }

        override fun move(rootPosition: Offset) { drag.position = rootPosition }

        override fun end() {
            drag.active = false
            val otherId = otherOf(docId)
            val target = bounds[otherId] ?: return
            if (target.contains(drag.position)) {
                val source = controllers[docId] ?: return
                val content = source.canvas.copySelection() ?: source.selection?.content ?: return
                send(content, docId)
            }
        }

        override fun cancel() { drag.active = false }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Inkwell.colors.desk)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        val stacked = maxWidth < 600.dp
        val total = if (stacked) constraints.maxHeight.toFloat() else constraints.maxWidth.toFloat()
        val dividerState = rememberDraggableState { delta ->
            if (total > 0f) fraction = Placement.splitFraction(fraction + delta / total)
        }
        val ids = listOf(first, second)
        // Each pane is movable content keyed by its document, so swapping the panes or switching
        // between stacked and side by side moves the editors instead of recreating them (which
        // would drop undo history, selection, open panels and text being typed).
        fun paneContent(id: String): @Composable (Boolean, Modifier) -> Unit = panes.getOrPut(id) {
            movableContentOf { isFirst: Boolean, mod: Modifier ->
                // Movable content resets the compound key, so without key(id) both panes would share
                // rememberSaveable keys (camera capture, launcher keys, scroll and search state) and,
                // after a swap, restore each other's saved values.
                key(id) {
                    // One sink per pane: a new object would restart the drag handle's gesture mid-drag.
                    val sink = remember { sinkFor(id) }
                    NotebookPane(
                        docId = id,
                        initialPage = null,
                        nav = nav,
                        mode = PaneMode.SPLIT,
                        modifier = mod
                            .onGloballyPositioned { bounds[id] = it.boundsInRoot() }
                            .border(1.dp, Inkwell.colors.outline),
                        split = SplitHooks(
                            isLeft = isFirst,
                            onClosePane = { closePane(id) },
                            onSendToOther = { content, from -> send(content, from) },
                            dragSink = sink,
                        ),
                        onController = { c -> if (c == null) controllers.remove(id) else controllers[id] = c },
                    )
                }
            }
        }
        if (stacked) {
            Column(Modifier.fillMaxSize()) {
                ids.forEachIndexed { i, id -> paneContent(id)(i == 0, Modifier.fillMaxWidth().weight(if (i == 0) fraction else 1f - fraction)) }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                ids.forEachIndexed { i, id -> paneContent(id)(i == 0, Modifier.fillMaxHeight().weight(if (i == 0) fraction else 1f - fraction)) }
            }
        }
        // Divider: a thin draggable strip on the pane boundary (so pens still reach the pane
        // edges) plus a swap button in its middle that can also be dragged.
        val offsetPx = (fraction * total).roundToInt()
        val handleLabel = stringResource(R.string.editor_resize_split)
        val stripHalf = with(density) { 6.dp.roundToPx() }
        val buttonHalf = with(density) { 24.dp.roundToPx() }
        val orientation = if (stacked) Orientation.Vertical else Orientation.Horizontal
        Box(
            (if (stacked) Modifier.fillMaxWidth().height(12.dp).offset { IntOffset(0, offsetPx - stripHalf) }
            else Modifier.fillMaxHeight().width(12.dp).offset { IntOffset(offsetPx - stripHalf, 0) })
                .draggable(dividerState, orientation)
                .semantics { contentDescription = handleLabel },
        )
        val maxW = constraints.maxWidth
        val maxH = constraints.maxHeight
        Box(
            Modifier
                .offset {
                    if (stacked) IntOffset(maxW / 2 - buttonHalf, offsetPx - buttonHalf)
                    else IntOffset(offsetPx - buttonHalf, maxH / 2 - buttonHalf)
                }
                .size(48.dp)
                .draggable(dividerState, orientation),
            contentAlignment = Alignment.Center,
        ) {
            FloatingSurface(shape = CircleShape) {
                IconAction(
                    if (stacked) Icons.Outlined.SwapVert else Icons.Outlined.SwapHoriz,
                    stringResource(R.string.editor_swap_panes),
                    onClick = { val t = first; first = second; second = t },
                    visual = 30.dp,
                )
            }
        }
        if (drag.active) {
            val p = drag.position - origin
            Box(Modifier.offset { IntOffset(p.x.roundToInt() + 12, p.y.roundToInt() + 12) }) {
                FloatingSurface(shape = RoundedCornerShape(12.dp)) {
                    Text(
                        stringResource(R.string.editor_drop_to_copy),
                        style = MaterialTheme.typography.labelMedium,
                        color = Inkwell.colors.ink,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
