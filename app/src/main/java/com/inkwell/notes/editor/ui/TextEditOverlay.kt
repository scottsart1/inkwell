package com.inkwell.notes.editor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.DropdownMenu
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
import com.inkwell.notes.editor.TextEditRequest
import com.inkwell.notes.editor.logic.ToolLimits
import com.inkwell.notes.editor.ui.components.FloatingSurface
import com.inkwell.notes.editor.ui.components.IconAction
import com.inkwell.notes.editor.ui.components.PillDivider
import com.inkwell.notes.editor.ui.components.Swatch
import com.inkwell.notes.editor.ui.toolbar.Palettes
import com.inkwell.notes.editor.ui.toolbar.sameColor
import com.inkwell.notes.ui.theme.Figtree
import com.inkwell.notes.ui.theme.Inkwell
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Multi-line text editor over the canvas at the text box's position, styled like the box,
 * with a mini format bar. Tapping outside or Back commits; an emptied box is deleted.
 * [onDraft] receives every change, so the owner can commit it if the pane goes away first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextEditOverlay(
    request: TextEditRequest,
    onDraft: (text: String, config: TextConfig) -> Unit = { _, _ -> },
    onDone: (text: String, config: TextConfig) -> Unit,
) {
    var value by remember(request) { mutableStateOf(TextFieldValue(request.initialText, TextRange(request.initialText.length))) }
    var config by remember(request) { mutableStateOf(request.config) }
    fun setConfig(next: TextConfig) {
        config = next
        onDraft(value.text, next)
    }
    var colorsOpen by remember { mutableStateOf(false) }
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

    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { commit() },
    )

    val rect = request.viewRect
    val minWidthPx = with(density) { 220.dp.toPx() }
    val fieldWidthPx = max(rect.width(), minWidthPx)
    val fontPx = (config.fontSizePt * request.pxPerPt).coerceIn(with(density) { 10.dp.toPx() }, with(density) { 96.dp.toPx() })
    val style = TextStyle(
        fontFamily = Figtree,
        fontSize = with(density) { fontPx.toSp() },
        fontWeight = if (config.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (config.italic) FontStyle.Italic else FontStyle.Normal,
        color = Color(config.color),
    )

    // Keep the editor above the on-screen keyboard.
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val keyboardOverlap = (imeBottom - navBottom).coerceAtLeast(0)
    PlacedOverlay(place = { w, h, cw, ch ->
        val barH = with(density) { 52.dp.toPx() }
        val visibleH = (ch - keyboardOverlap).coerceAtLeast(h)
        val x = rect.left.coerceIn(0f, max(0f, cw - w.toFloat()))
        val wantY = rect.top - barH
        val y = (if (wantY >= 0f) wantY else min(rect.top, max(0f, ch - h.toFloat())))
            .coerceAtMost(max(0f, visibleH - h.toFloat()))
        IntOffset(x.roundToInt(), y.roundToInt().coerceAtLeast(0))
    }) {
        Column {
            FloatingSurface(shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.height(44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!request.sticky) {
                        IconAction(Icons.Outlined.Remove, stringResource(R.string.editor_text_smaller), {
                            setConfig(config.copy(fontSizePt = (config.fontSizePt - 2f).coerceAtLeast(ToolLimits.TEXT_MIN_PT)))
                        })
                        Text(
                            stringResource(R.string.editor_pt_value, config.fontSizePt.roundToInt()),
                            style = MaterialTheme.typography.labelMedium,
                            color = c.ink,
                            modifier = Modifier.widthIn(min = 40.dp),
                        )
                        IconAction(Icons.Outlined.Add, stringResource(R.string.editor_text_larger), {
                            setConfig(config.copy(fontSizePt = (config.fontSizePt + 2f).coerceAtMost(ToolLimits.TEXT_MAX_PT)))
                        })
                        PillDivider(vertical = true)
                        IconAction(Icons.Outlined.FormatBold, stringResource(R.string.editor_bold), { setConfig(config.copy(bold = !config.bold)) }, selected = config.bold)
                        IconAction(Icons.Outlined.FormatItalic, stringResource(R.string.editor_italic), { setConfig(config.copy(italic = !config.italic)) }, selected = config.italic)
                        Box {
                            IconAction(Icons.Outlined.Palette, stringResource(R.string.editor_color), { colorsOpen = true })
                            DropdownMenu(expanded = colorsOpen, onDismissRequest = { colorsOpen = false }) {
                                FlowRow(Modifier.width(240.dp).padding(horizontal = 8.dp)) {
                                    Palettes.pen.forEach { ink ->
                                        Swatch(ink.argb, sameColor(ink.argb, config.color), {
                                            setConfig(config.copy(color = ink.argb))
                                            colorsOpen = false
                                        }, stringResource(ink.name))
                                    }
                                }
                            }
                        }
                        PillDivider(vertical = true)
                    }
                    IconAction(Icons.Outlined.Check, stringResource(R.string.editor_done), { commit() }, tint = c.accent)
                }
            }
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .width(with(density) { fieldWidthPx.toDp() })
                    .heightIn(min = with(density) { max(rect.height(), fontPx * 1.6f).toDp() })
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.surface.copy(alpha = 0.92f))
                    .border(1.dp, c.accent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        onDraft(it.text, config)
                    },
                    textStyle = style,
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier
                        .focusRequester(focus)
                        .width(with(density) { fieldWidthPx.toDp() } - 16.dp)
                        .heightIn(min = with(density) { fontPx.toDp() } + 8.dp),
                )
            }
        }
    }
}
