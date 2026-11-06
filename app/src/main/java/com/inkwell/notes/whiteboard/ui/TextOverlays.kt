package com.inkwell.notes.whiteboard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.TextConfig
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Figtree
import com.inkwell.notes.ui.theme.Inkwell
import com.inkwell.notes.whiteboard.StickyEditRequest
import com.inkwell.notes.whiteboard.TextEditRequest
import com.inkwell.notes.whiteboard.kit.ColorSwatch
import com.inkwell.notes.whiteboard.kit.KitIconButton
import com.inkwell.notes.whiteboard.kit.PillDivider
import com.inkwell.notes.whiteboard.kit.PillSurface
import com.inkwell.notes.whiteboard.kit.consumeClicks
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Edits a board text box in place: a text field at the box's position, styled like the box
 * at the current zoom, with a small format bar. Tapping outside or Back commits; an emptied
 * existing box is removed by the canvas. [onDraft] receives every change, so the owner can
 * commit it if the screen goes away first.
 */
@Composable
fun TextBoxEditor(
    request: TextEditRequest,
    paperColor: Int,
    onDraft: (String, TextConfig) -> Unit = { _, _ -> },
    onDone: (String, TextConfig) -> Unit,
) {
    var value by remember(request) { mutableStateOf(TextFieldValue(request.initialText, TextRange(request.initialText.length))) }
    var config by remember(request) { mutableStateOf(request.config) }
    fun setConfig(next: TextConfig) {
        config = next
        onDraft(value.text, next)
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val c = Inkwell.colors

    fun commit() {
        keyboard?.hide()
        onDone(value.text, config)
    }
    BackHandler { commit() }
    LaunchedEffect(request) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.fillMaxSize().consumeClicks { commit() })
        val rect = request.viewRect
        val maxWpx = constraints.maxWidth.toFloat()
        val minWidthPx = with(density) { 220.dp.toPx() }
        val widthPx = max(rect.width(), minWidthPx).coerceAtMost(maxWpx - with(density) { 16.dp.toPx() })
        val fontPx = (config.fontSizePt * request.pxPerPt).coerceIn(with(density) { 12.dp.toPx() }, with(density) { 96.dp.toPx() })
        val barH = with(density) { 52.dp.toPx() }
        val x = rect.left.coerceIn(0f, max(0f, maxWpx - widthPx))
        val estimatedH = barH + fontPx * 1.6f + with(density) { 24.dp.toPx() }
        val y = (if (rect.top - barH >= 0f) rect.top - barH else rect.top)
            .coerceAtMost(max(0f, constraints.maxHeight - estimatedH))
        val style = TextStyle(
            fontFamily = Figtree,
            fontSize = with(density) { fontPx.toSp() },
            fontWeight = if (config.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (config.italic) FontStyle.Italic else FontStyle.Normal,
            color = Color(config.color),
        )
        Column(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt().coerceAtLeast(0)) }.wrapContentSize()) {
            PillSurface(shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.height(Dimens.minTouch).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    KitIconButton(Icons.Outlined.Remove, stringResource(R.string.wb_text_smaller), {
                        setConfig(config.copy(fontSizePt = (config.fontSizePt - 2f).coerceAtLeast(8f)))
                    })
                    Text(stringResource(R.string.wb_pt_value, config.fontSizePt.roundToInt()), style = MaterialTheme.typography.labelMedium, color = c.ink, modifier = Modifier.widthIn(min = 40.dp))
                    KitIconButton(Icons.Outlined.Add, stringResource(R.string.wb_text_larger), {
                        setConfig(config.copy(fontSizePt = (config.fontSizePt + 2f).coerceAtMost(96f)))
                    })
                    PillDivider()
                    KitIconButton(Icons.Outlined.FormatBold, stringResource(R.string.wb_bold), { setConfig(config.copy(bold = !config.bold)) }, selected = config.bold)
                    KitIconButton(Icons.Outlined.FormatItalic, stringResource(R.string.wb_italic), { setConfig(config.copy(italic = !config.italic)) }, selected = config.italic)
                    PillDivider()
                    BoardPalettes.pen.take(5).forEach { nc ->
                        ColorSwatch(nc.argb, BoardPalettes.sameColor(nc.argb, config.color), stringResource(nc.name), { setConfig(config.copy(color = nc.argb)) }, diameter = 20.dp)
                    }
                    PillDivider()
                    KitIconButton(Icons.Outlined.Check, stringResource(R.string.cd_done), { commit() }, tint = c.accent)
                }
            }
            Spacer(Modifier.height(6.dp))
            BasicTextField(
                value = value,
                onValueChange = {
                    value = it
                    onDraft(it.text, config)
                },
                textStyle = style,
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier
                    .width(with(density) { widthPx.toDp() })
                    .heightIn(min = with(density) { (fontPx * 1.4f).toDp() })
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(paperColor).copy(alpha = 0.94f))
                    .border(1.dp, c.accent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .focusRequester(focus),
                decorationBox = { inner ->
                    Box {
                        if (value.text.isEmpty()) {
                            Text(stringResource(R.string.wb_text_placeholder), style = style.copy(color = Color(config.color).copy(alpha = 0.4f)))
                        }
                        inner()
                    }
                },
            )
        }
    }
}

/** Edits a sticky note's text and colour over the canvas. [onDraft] receives every change. */
@Composable
fun StickyEditor(request: StickyEditRequest, onDraft: (String, Int) -> Unit = { _, _ -> }, onDone: (String, Int) -> Unit) {
    var value by remember(request) { mutableStateOf(TextFieldValue(request.initialText, TextRange(request.initialText.length))) }
    var color by remember(request) { mutableStateOf(request.color) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val c = Inkwell.colors

    fun commit() {
        keyboard?.hide()
        onDone(value.text, color)
    }
    BackHandler { commit() }
    LaunchedEffect(request) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.fillMaxSize().consumeClicks { commit() })
        val rect = request.viewRect
        val minSide = with(density) { 200.dp.toPx() }
        val side = max(max(rect.width(), rect.height()), minSide).coerceAtMost(constraints.maxWidth.toFloat() - with(density) { 16.dp.toPx() })
        val x = rect.left.coerceIn(0f, max(0f, constraints.maxWidth - side))
        val y = rect.top.coerceIn(0f, max(0f, constraints.maxHeight - side - with(density) { 60.dp.toPx() }))
        Column(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }) {
            BasicTextField(
                value = value,
                onValueChange = {
                    value = it
                    onDraft(it.text, color)
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFF1A1916)),
                cursorBrush = SolidColor(Color(0xFF1A1916)),
                modifier = Modifier
                    .size(with(density) { side.toDp() })
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(color))
                    .border(1.5.dp, c.accent, RoundedCornerShape(8.dp))
                    .padding(14.dp)
                    .focusRequester(focus),
                decorationBox = { inner ->
                    Box {
                        if (value.text.isEmpty()) {
                            Text(stringResource(R.string.wb_sticky_placeholder), style = MaterialTheme.typography.bodyLarge, color = Color(0x991A1916))
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            PillSurface(shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BoardPalettes.stickies.forEach { nc ->
                        ColorSwatch(nc.argb, BoardPalettes.sameColor(nc.argb, color), stringResource(nc.name), {
                            color = nc.argb
                            onDraft(value.text, nc.argb)
                        }, diameter = 22.dp)
                    }
                    PillDivider()
                    KitIconButton(Icons.Outlined.Check, stringResource(R.string.cd_done), { commit() }, tint = c.accent)
                }
            }
        }
    }
}
