package com.inkwell.notes.whiteboard.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import com.inkwell.notes.whiteboard.BoardOperations
import com.inkwell.notes.whiteboard.logic.Box as BoardBox
import com.inkwell.notes.whiteboard.logic.CropHandle
import com.inkwell.notes.whiteboard.logic.CropMath
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/** Insert PDF snippet: pick a PDF from the library, a page, crop a rectangle, insert as an image. */
@Composable
fun PdfSnippetDialog(ops: BoardOperations, pdfPageSizes: suspend (String) -> List<Pair<Float, Float>>, renderPage: suspend (String, Int, Int, Int) -> Bitmap?, onDismiss: () -> Unit) {
    var pdf by remember { mutableStateOf<DocumentMeta?>(null) }
    var pageIndex by remember { mutableStateOf<Int?>(null) }
    // Stays open while inserting: closing it would cancel the insert halfway and orphan the saved asset.
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val docs by produceState<List<DocumentMeta>?>(null) { value = runCatching { ops.documents(setOf(DocKind.PDF)) }.getOrDefault(emptyList()) }
    val sizes by produceState<List<Pair<Float, Float>>?>(null, pdf) {
        value = null
        val d = pdf ?: return@produceState
        value = runCatching { pdfPageSizes(d.id) }.getOrDefault(emptyList())
    }
    val currentPdf = pdf
    val currentPage = pageIndex
    when {
        currentPdf == null -> PickerShell(stringResource(R.string.wb_snippet_choose_pdf), onDismiss) {
            DocumentList(docs, stringResource(R.string.wb_snippet_no_pdfs), onPick = { pdf = it })
        }
        currentPage == null -> PickerShell(currentPdf.title, onDismiss, onBack = { pdf = null }) {
            val s = sizes
            when {
                s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Inkwell.colors.accent, strokeWidth = 2.dp) }
                s.isEmpty() -> Text(stringResource(R.string.wb_snippet_no_pages), style = MaterialTheme.typography.bodyMedium, color = Inkwell.colors.inkMuted, modifier = Modifier.padding(24.dp))
                else -> LazyVerticalGrid(GridCells.Adaptive(132.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                    itemsIndexed(s) { i, (w, h) ->
                        val aspect = if (w > 0f && h > 0f) w / h else 0.707f
                        PageThumbCell(
                            key = currentPdf.id to i,
                            aspect = aspect,
                            label = stringResource(R.string.wb_page_number, i + 1),
                            load = { renderPage(currentPdf.id, i, 280, (280 / aspect).roundToInt().coerceAtLeast(1)) },
                            onClick = { pageIndex = i },
                        )
                    }
                }
            }
        }
        else -> {
            val size = sizes?.getOrNull(currentPage) ?: (595f to 842f)
            PickerShell(stringResource(R.string.wb_snippet_crop), { if (!working) onDismiss() }, onBack = { if (!working) pageIndex = null }) {
                CropStep(
                    renderPreview = { w, h -> renderPage(currentPdf.id, currentPage, w, h) },
                    aspect = if (size.first > 0f && size.second > 0f) size.first / size.second else 0.707f,
                    busy = working || ops.isBusy(),
                    onInsert = { crop ->
                        if (!working) {
                            working = true
                            scope.launch {
                                val ok = try {
                                    ops.insertPdfSnippet(currentPdf.id, currentPage, size, crop)
                                } finally {
                                    working = false
                                }
                                if (ok) onDismiss()
                            }
                        }
                    },
                    onCancel = { if (!working) onDismiss() },
                )
            }
        }
    }
}

@Composable
private fun CropStep(
    renderPreview: suspend (Int, Int) -> Bitmap?,
    aspect: Float,
    busy: Boolean,
    onInsert: (BoardBox) -> Unit,
    onCancel: () -> Unit,
) {
    val c = Inkwell.colors
    val density = LocalDensity.current
    var crop by remember { mutableStateOf(CropMath.initial()) }
    val preview by produceState<Pair<Boolean, Bitmap?>>(false to null) {
        val w = 1400
        value = true to runCatching { renderPreview(w, (w / aspect).roundToInt().coerceAtLeast(1)) }.getOrNull()
    }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            val availW = constraints.maxWidth.toFloat()
            val availH = constraints.maxHeight.toFloat()
            val w = min(availW, availH * aspect)
            val h = w / aspect
            val (loaded, bmp) = preview
            Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() }).clip(RoundedCornerShape(4.dp)).background(c.surfaceAlt)) {
                when {
                    bmp != null -> Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    !loaded -> CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.Center))
                    else -> Text(stringResource(R.string.wb_snippet_render_failed), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, modifier = Modifier.align(Alignment.Center).padding(16.dp))
                }
                if (bmp != null) {
                    var handle by remember { mutableStateOf(CropHandle.NONE) }
                    val tol = with(density) { 22.dp.toPx() }
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(w, h) {
                                detectDragGestures(
                                    onDragStart = { p -> handle = CropMath.hitTest(crop, p.x / w, p.y / h, tol / w, tol / h) },
                                    onDragEnd = { handle = CropHandle.NONE },
                                    onDragCancel = { handle = CropHandle.NONE },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        crop = CropMath.drag(crop, handle, amount.x / w, amount.y / h)
                                    },
                                )
                            },
                    ) {
                        val l = crop.left * size.width
                        val t = crop.top * size.height
                        val r = crop.right * size.width
                        val b = crop.bottom * size.height
                        clipRect(l, t, r, b, clipOp = ClipOp.Difference) { drawRect(c.ink.copy(alpha = 0.45f)) }
                        drawRect(c.accent, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
                        val hs = 10.dp.toPx()
                        for ((x, y) in listOf(l to t, r to t, l to b, r to b)) {
                            drawRect(c.surface, Offset(x - hs / 2, y - hs / 2), Size(hs, hs))
                            drawRect(c.accent, Offset(x - hs / 2, y - hs / 2), Size(hs, hs), style = Stroke(2.dp.toPx()))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            DialogTextButton(stringResource(R.string.cd_cancel), onCancel)
            Spacer(Modifier.size(8.dp))
            DialogTextButton(stringResource(R.string.cd_insert), { onInsert(crop) }, primary = true, enabled = preview.second != null && !busy)
        }
    }
}

/** A compact text button following the design system (44dp tall, 12dp radius). */
@Composable
fun DialogTextButton(label: String, onClick: () -> Unit, primary: Boolean = false, enabled: Boolean = true) {
    val c = Inkwell.colors
    Box(
        Modifier
            .height(Dimens.minTouch)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary && enabled) c.accent else if (primary) c.surfaceAlt else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                primary && enabled -> c.onAccent
                !enabled -> c.inkMuted
                else -> c.accent
            },
        )
    }
}
