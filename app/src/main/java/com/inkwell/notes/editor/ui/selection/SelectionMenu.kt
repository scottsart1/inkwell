package com.inkwell.notes.editor.ui.selection

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.FlipToFront
import androidx.compose.material.icons.outlined.LineWeight
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SpaceBar
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.editor.logic.Bounds
import com.inkwell.notes.editor.logic.FragmentOps
import com.inkwell.notes.editor.logic.Placement
import com.inkwell.notes.editor.logic.ToolLimits
import com.inkwell.notes.editor.ui.PlacedOverlay
import com.inkwell.notes.editor.ui.components.FloatingSurface
import com.inkwell.notes.editor.ui.components.IconAction
import com.inkwell.notes.editor.ui.components.LabeledSlider
import com.inkwell.notes.editor.ui.components.PillDivider
import com.inkwell.notes.editor.ui.components.Swatch
import com.inkwell.notes.editor.ui.toolbar.Palettes
import com.inkwell.notes.ink.InkSelection
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlin.math.roundToInt

/** What the user picked in the selection menu. */
sealed interface SelectionAction {
    data object Cut : SelectionAction
    data object Copy : SelectionAction
    data object Duplicate : SelectionAction
    data object Delete : SelectionAction
    data class Recolor(val color: Int) : SelectionAction
    data class Width(val width: Float) : SelectionAction
    data class Opacity(val opacity: Float) : SelectionAction
    data object Group : SelectionAction
    data object Ungroup : SelectionAction
    data object BringToFront : SelectionAction
    data object SendToBack : SelectionAction
    data object ConvertToText : SelectionAction
    data object CopyText : SelectionAction
    data object SearchThis : SelectionAction
    data object Straighten : SelectionAction
    data object Reflow : SelectionAction
    data class WordSpacing(val factor: Float) : SelectionAction
    data object Translate : SelectionAction
    data object Summarize : SelectionAction
    data object Explain : SelectionAction
    data object AskAi : SelectionAction
    data object SaveElement : SelectionAction
    data object PlayFromHere : SelectionAction
    data object SendToOtherPane : SelectionAction
    data object Crop : SelectionAction
}

/** Drag of the selection's handle toward the other split pane, in root coordinates. */
interface SelectionDragSink {
    fun start(rootPosition: Offset)
    fun move(rootPosition: Offset)
    fun end()
    fun cancel()
}

/**
 * Compact floating menu above the selection (below when there is no room). [minTopPx]
 * keeps it clear of a toolbar at the top.
 */
@Composable
fun SelectionMenu(
    selection: InkSelection,
    minTopPx: Float,
    inSplit: Boolean,
    onAction: (SelectionAction) -> Unit,
    dragSink: SelectionDragSink?,
) {
    val density = LocalDensity.current
    val gap = with(density) { 10.dp.toPx() }
    val margin = with(density) { 8.dp.toPx() }
    val b = selection.boundsView
    PlacedOverlay(place = { w, h, cw, ch ->
        val p = Placement.selectionMenu(Bounds(b.left, b.top, b.right, b.bottom), w.toFloat(), h.toFloat(), cw.toFloat(), ch.toFloat(), gap, margin, minTopPx)
        IntOffset(p.x.roundToInt(), p.y.roundToInt())
    }) {
        FloatingSurface(shape = RoundedCornerShape(16.dp)) {
            MenuRow(selection, inSplit, onAction, dragSink)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MenuRow(selection: InkSelection, inSplit: Boolean, onAction: (SelectionAction) -> Unit, dragSink: SelectionDragSink?) {
    val content = selection.content
    val handwriting = selection.hasHandwriting
    val hasText = handwriting || content.texts.isNotEmpty()
    var colorOpen by remember { mutableStateOf(false) }
    var widthOpen by remember { mutableStateOf(false) }
    var aiOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val initialWidth = remember(selection) { content.strokes.firstOrNull()?.width ?: content.shapes.firstOrNull()?.strokeWidth ?: 1.6f }
    var width by remember(selection) { mutableFloatStateOf(initialWidth) }
    var opacity by remember(selection) { mutableFloatStateOf(1f) }

    Row(Modifier.height(Dimens.toolbarHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (inSplit && dragSink != null) DragToPaneHandle(dragSink)
        IconAction(Icons.Outlined.ContentCut, stringResource(R.string.editor_sel_cut), { onAction(SelectionAction.Cut) })
        IconAction(Icons.Outlined.ContentCopy, stringResource(R.string.editor_sel_copy), { onAction(SelectionAction.Copy) })
        IconAction(Icons.Outlined.CopyAll, stringResource(R.string.editor_sel_duplicate), { onAction(SelectionAction.Duplicate) })
        IconAction(Icons.Outlined.DeleteOutline, stringResource(R.string.editor_sel_delete), { onAction(SelectionAction.Delete) })
        PillDivider(vertical = true)
        Box {
            IconAction(Icons.Outlined.Palette, stringResource(R.string.editor_sel_color), { colorOpen = true })
            DropdownMenu(expanded = colorOpen, onDismissRequest = { colorOpen = false }) {
                FlowRow(Modifier.width(280.dp).padding(horizontal = 8.dp)) {
                    (Palettes.pen + Palettes.highlighter).distinctBy { it.argb }.forEach { ink ->
                        Swatch(ink.argb, selected = false, onClick = { colorOpen = false; onAction(SelectionAction.Recolor(ink.argb)) }, contentDescription = stringResource(ink.name))
                    }
                }
            }
        }
        Box {
            IconAction(Icons.Outlined.LineWeight, stringResource(R.string.editor_sel_thickness), { widthOpen = true })
            DropdownMenu(expanded = widthOpen, onDismissRequest = { widthOpen = false }) {
                Column(Modifier.width(260.dp).padding(horizontal = 14.dp)) {
                    LabeledSlider(
                        label = stringResource(R.string.editor_width),
                        value = width,
                        range = ToolLimits.PEN_MIN_WIDTH..ToolLimits.HIGHLIGHTER_MAX_WIDTH,
                        valueText = stringResource(R.string.editor_points_value, width),
                        onChange = { width = (it * 10f).roundToInt() / 10f },
                        onChangeFinished = { onAction(SelectionAction.Width(width)) },
                    )
                    LabeledSlider(
                        label = stringResource(R.string.editor_opacity),
                        value = opacity,
                        range = 0.1f..1f,
                        valueText = "${(opacity * 100).roundToInt()}%",
                        onChange = { opacity = it },
                        onChangeFinished = { onAction(SelectionAction.Opacity(opacity)) },
                    )
                }
            }
        }
        if (handwriting) {
            PillDivider(vertical = true)
            IconAction(Icons.Outlined.TextFields, stringResource(R.string.editor_sel_convert), { onAction(SelectionAction.ConvertToText) })
        }
        Box {
            IconAction(Icons.Outlined.AutoAwesome, stringResource(R.string.editor_sel_ai), { aiOpen = true })
            DropdownMenu(expanded = aiOpen, onDismissRequest = { aiOpen = false }) {
                MenuEntry(Icons.Outlined.Translate, stringResource(R.string.editor_sel_translate)) { aiOpen = false; onAction(SelectionAction.Translate) }
                MenuEntry(Icons.Outlined.Summarize, stringResource(R.string.editor_sel_summarize)) { aiOpen = false; onAction(SelectionAction.Summarize) }
                MenuEntry(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.editor_sel_explain)) { aiOpen = false; onAction(SelectionAction.Explain) }
                MenuEntry(Icons.Outlined.QuestionAnswer, stringResource(R.string.editor_sel_ask)) { aiOpen = false; onAction(SelectionAction.AskAi) }
            }
        }
        Box {
            IconAction(Icons.Outlined.MoreHoriz, stringResource(R.string.editor_sel_more), { moreOpen = true })
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                fun act(a: SelectionAction) { moreOpen = false; onAction(a) }
                if (FragmentOps.canGroup(content)) MenuEntry(Icons.Outlined.Workspaces, stringResource(R.string.editor_sel_group)) { act(SelectionAction.Group) }
                if (FragmentOps.canUngroup(content)) MenuEntry(Icons.Outlined.Widgets, stringResource(R.string.editor_sel_ungroup)) { act(SelectionAction.Ungroup) }
                MenuEntry(Icons.Outlined.FlipToFront, stringResource(R.string.editor_sel_front)) { act(SelectionAction.BringToFront) }
                MenuEntry(Icons.Outlined.FlipToBack, stringResource(R.string.editor_sel_back)) { act(SelectionAction.SendToBack) }
                if (hasText) {
                    HorizontalDivider(color = Inkwell.colors.outline)
                    MenuEntry(Icons.AutoMirrored.Outlined.TextSnippet, stringResource(R.string.editor_sel_copy_text)) { act(SelectionAction.CopyText) }
                    MenuEntry(Icons.Outlined.Search, stringResource(R.string.editor_sel_search)) { act(SelectionAction.SearchThis) }
                }
                if (handwriting) {
                    HorizontalDivider(color = Inkwell.colors.outline)
                    MenuEntry(Icons.Outlined.Straighten, stringResource(R.string.editor_sel_straighten)) { act(SelectionAction.Straighten) }
                    MenuEntry(Icons.AutoMirrored.Outlined.WrapText, stringResource(R.string.editor_sel_reflow)) { act(SelectionAction.Reflow) }
                    MenuEntry(Icons.Outlined.SpaceBar, stringResource(R.string.editor_sel_tighter)) { act(SelectionAction.WordSpacing(0.85f)) }
                    MenuEntry(Icons.Outlined.SpaceBar, stringResource(R.string.editor_sel_wider)) { act(SelectionAction.WordSpacing(1.18f)) }
                }
                if (content.images.size == 1 && FragmentOps.itemCount(content) == 1) {
                    MenuEntry(Icons.Outlined.Crop, stringResource(R.string.editor_sel_crop)) { act(SelectionAction.Crop) }
                }
                HorizontalDivider(color = Inkwell.colors.outline)
                MenuEntry(Icons.Outlined.Widgets, stringResource(R.string.editor_sel_save_element)) { act(SelectionAction.SaveElement) }
                if (FragmentOps.firstAudioAnchor(content) != null) {
                    MenuEntry(Icons.Outlined.PlayArrow, stringResource(R.string.editor_play_from_here)) { act(SelectionAction.PlayFromHere) }
                }
                if (inSplit) {
                    MenuEntry(Icons.Outlined.ViewColumn, stringResource(R.string.editor_sel_send_to_pane)) { act(SelectionAction.SendToOtherPane) }
                }
            }
        }
    }
}

@Composable
private fun MenuEntry(icon: ImageVector, text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, null, Modifier.size(Dimens.icon)) },
        onClick = onClick,
    )
}

/** Drag this to the other pane to copy the selection there. */
@Composable
private fun DragToPaneHandle(sink: SelectionDragSink) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var pos by remember { mutableStateOf(Offset.Zero) }
    // Keyed on Unit: a recomposition handing in another sink must not cancel a drag in progress.
    val currentSink by rememberUpdatedState(sink)
    val label = stringResource(R.string.editor_sel_drag_to_pane)
    Box(
        Modifier
            .size(width = 28.dp, height = Dimens.minTouch)
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start -> pos = origin + start; currentSink.start(pos) },
                    onDragEnd = { currentSink.end() },
                    onDragCancel = { currentSink.cancel() },
                ) { change, amount ->
                    change.consume()
                    pos += amount
                    currentSink.move(pos)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.DragIndicator, null, Modifier.size(18.dp), tint = Inkwell.colors.inkMuted)
    }
}
