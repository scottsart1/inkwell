package com.inkwell.notes.editor.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.editor.ui.components.IconAction
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

/** Header row of a side panel: title, optional actions, close. */
@Composable
fun PanelHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val c = Inkwell.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(Dimens.topBarHeight).padding(start = 16.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            actions()
            IconAction(Icons.Outlined.Close, stringResource(R.string.editor_close_panel), onClose)
        }
        HorizontalDivider(color = c.outline)
    }
}

/**
 * A side panel with a draggable inner edge. [width] is the current width; [onResize]
 * receives the new width (callers clamp and persist it).
 */
@Composable
fun SidePanel(
    width: Dp,
    atEnd: Boolean,
    modifier: Modifier = Modifier,
    onResize: ((Dp) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Inkwell.colors
    val density = LocalDensity.current
    Row(modifier.width(width).fillMaxHeight().background(c.surface)) {
        if (atEnd) Edge(onResize, width, density, grows = -1f)
        Column(Modifier.weight(1f).fillMaxHeight(), content = content)
        if (!atEnd) Edge(onResize, width, density, grows = 1f)
    }
}

@Composable
private fun Edge(onResize: ((Dp) -> Unit)?, width: Dp, density: androidx.compose.ui.unit.Density, grows: Float) {
    val c = Inkwell.colors
    if (onResize == null) {
        VerticalDivider(color = c.outline)
        return
    }
    val label = stringResource(R.string.editor_resize_panel)
    var current = width
    val state = rememberDraggableState { deltaPx ->
        current += with(density) { (deltaPx * grows).toDp() }
        onResize(current)
    }
    Box(
        Modifier
            .width(12.dp)
            .fillMaxHeight()
            .draggable(state, Orientation.Horizontal, onDragStarted = { current = width })
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider(color = c.outline, modifier = Modifier.align(if (grows < 0) Alignment.CenterStart else Alignment.CenterEnd))
        Box(Modifier.width(4.dp).height(36.dp).clip(CircleShape).background(c.outline))
    }
}

/** Panel shown over the pane (narrow layouts) with an outline on its inner edge. */
@Composable
fun OverlayPanel(width: Dp?, atEnd: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Inkwell.colors
    Box(modifier.fillMaxSize(), contentAlignment = if (atEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        Row(
            (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
                .fillMaxHeight()
                .background(c.surface)
                // Being hit at all keeps pen and touch on the panel's empty areas from reaching
                // the canvas drawn beneath it; children still handle their own input first.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        ) {
            if (atEnd && width != null) VerticalDivider(color = c.outline)
            Column(Modifier.weight(1f).fillMaxHeight(), content = content)
            if (!atEnd && width != null) VerticalDivider(color = c.outline)
        }
    }
}

/** Centered empty-state text inside panels. */
@Composable
fun PanelEmpty(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Inkwell.colors.inkMuted)
    }
}
