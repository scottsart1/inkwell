package com.inkwell.notes.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.inkwell.notes.R
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.SearchHit
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.ocr.text.TextMatch
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import java.io.File

internal val Radius8 = RoundedCornerShape(8.dp)
internal val Radius12 = RoundedCornerShape(12.dp)
private val ThumbWidth = 40.dp
private val ThumbHeight = 52.dp
private const val VISIBLE_ROWS = 4

/** 44dp icon button with a 20dp outlined icon. */
@Composable
internal fun SearchIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(Dimens.minTouch).clip(Radius8).clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(Dimens.icon), tint = Inkwell.colors.inkMuted)
    }
}

/** The large query field: 56dp, 12dp radius, 1dp outline that turns accent while focused. */
@Composable
internal fun SearchQueryField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val c = Inkwell.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val textStyle = MaterialTheme.typography.titleLarge.copy(color = c.ink, fontWeight = FontWeight.Normal)
    // Text set from outside (the query the screen opened with, a recent search) gets the cursor at
    // its end. The String overload of BasicTextField starts it at 0, so typing on after the screen
    // focuses the field would insert in front of the query.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))
    BasicTextField(
        value = shown,
        onValueChange = { v ->
            field = v
            if (v.text != value) onValueChange(v.text)
        },
        modifier = modifier.focusRequester(focusRequester),
        textStyle = textStyle,
        singleLine = true,
        cursorBrush = SolidColor(c.accent),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(Radius12)
                    .background(c.surface)
                    .border(1.dp, if (focused) c.accent else c.outline, Radius12)
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(Dimens.icon), tint = c.inkMuted)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            stringResource(R.string.search_placeholder),
                            style = textStyle.copy(color = c.inkMuted),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
                if (value.isNotEmpty()) {
                    SearchIconButton(Icons.Outlined.Close, stringResource(R.string.search_clear_query), { onValueChange("") })
                }
            }
        },
    )
}

/** Filter chip: 32dp visible, 44dp touch target, 8dp radius, accent container when selected. */
@Composable
internal fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    icon: ImageVector? = null,
    toggle: Boolean = false,
) {
    val c = Inkwell.colors
    val bg = if (selected) c.accentContainer else c.surface
    val border = if (selected) c.accent else c.outline
    val fg = if (selected) c.ink else c.inkMuted
    val interaction = if (toggle) {
        Modifier.toggleable(value = selected, role = Role.Switch, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected = selected, role = Role.Tab, onClick = onClick)
    }
    Box(modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .clip(Radius8)
                .background(bg)
                .border(1.dp, border, Radius8)
                .then(interaction)
                .padding(start = if (icon != null) 10.dp else 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp), tint = if (selected) c.accent else fg)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
            if (count != null) {
                Spacer(Modifier.width(6.dp))
                Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = if (selected) c.accent else c.inkMuted.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
internal fun filterLabel(f: SearchFilter): String = stringResource(
    when (f) {
        SearchFilter.ALL -> R.string.search_filter_all
        SearchFilter.HANDWRITING -> R.string.search_filter_handwriting
        SearchFilter.TYPED -> R.string.search_filter_typed
        SearchFilter.PDF_OCR -> R.string.search_filter_pdf_ocr
        SearchFilter.TRANSCRIPTS -> R.string.search_filter_transcripts
        SearchFilter.TITLES -> R.string.search_filter_titles
    },
)

internal fun sourceIcon(s: TextSource): ImageVector = when (s) {
    TextSource.HANDWRITING -> Icons.Outlined.Draw
    TextSource.TYPED -> Icons.Outlined.TextFields
    TextSource.PDF_TEXT -> Icons.Outlined.PictureAsPdf
    TextSource.OCR -> Icons.Outlined.DocumentScanner
    TextSource.TRANSCRIPT -> Icons.Outlined.GraphicEq
    TextSource.TITLE -> Icons.Outlined.Title
    TextSource.TAG -> Icons.Outlined.Sell
    TextSource.FOLDER -> Icons.Outlined.Folder
}

@Composable
internal fun sourceLabel(s: TextSource): String = stringResource(
    when (s) {
        TextSource.HANDWRITING -> R.string.search_source_handwriting
        TextSource.TYPED -> R.string.search_source_typed
        TextSource.PDF_TEXT -> R.string.search_source_pdf
        TextSource.OCR -> R.string.search_source_ocr
        TextSource.TRANSCRIPT -> R.string.search_source_transcript
        TextSource.TITLE -> R.string.search_source_title
        TextSource.TAG -> R.string.search_source_tag
        TextSource.FOLDER -> R.string.search_source_folder
    },
)

internal fun kindIcon(k: DocKind): ImageVector = when (k) {
    DocKind.NOTEBOOK -> Icons.AutoMirrored.Outlined.MenuBook
    DocKind.PDF -> Icons.Outlined.PictureAsPdf
    DocKind.WHITEBOARD -> Icons.Outlined.Dashboard
    DocKind.TYPED -> Icons.Outlined.Description
}

@Composable
internal fun kindLabel(k: DocKind): String = stringResource(
    when (k) {
        DocKind.NOTEBOOK -> R.string.search_kind_notebook
        DocKind.PDF -> R.string.search_kind_pdf
        DocKind.WHITEBOARD -> R.string.search_kind_whiteboard
        DocKind.TYPED -> R.string.search_kind_typed
    },
)

/** [text] with every match of [terms] (and the engine's own match range) in accent semibold. */
internal fun highlighted(text: String, terms: List<String>, accent: Color, primary: IntRange? = null): AnnotatedString {
    val ranges = TextMatch.mergeRanges(TextMatch.highlightRanges(text, terms) + listOfNotNull(primary))
    if (ranges.isEmpty()) return AnnotatedString(text)
    val style = SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        append(text)
        for (r in ranges) {
            val start = r.first.coerceIn(0, text.length)
            val end = (r.last + 1).coerceIn(start, text.length)
            if (end > start) addStyle(style, start, end)
        }
    }
}

/** Paper-like thumbnail frame (40x52dp, 8dp radius, 1dp outline). */
@Composable
private fun ThumbFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Inkwell.colors
    Box(
        modifier.size(ThumbWidth, ThumbHeight).clip(Radius8).background(c.surfaceAlt).border(1.dp, c.outline, Radius8),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun DocThumbnail(path: String?, kind: DocKind) {
    val c = Inkwell.colors
    ThumbFrame {
        Icon(kindIcon(kind), null, Modifier.size(Dimens.icon), tint = c.inkMuted)
        if (path != null) {
            AsyncImage(
                model = File(path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PagePreview(vm: SearchViewModel, docId: String, pageId: String, pageIndex: Int) {
    val bitmap by produceState<ImageBitmap?>(vm.cachedPreview(pageId), docId, pageId) {
        if (value == null) value = vm.preview(docId, pageId)
    }
    ThumbFrame {
        val b = bitmap
        if (b == null) {
            Text((pageIndex + 1).toString(), style = MaterialTheme.typography.labelSmall, color = Inkwell.colors.inkMuted)
        } else {
            Image(
                b,
                stringResource(R.string.search_page_preview, pageIndex + 1),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize().background(Inkwell.colors.surface),
            )
        }
    }
}

@Composable
private fun KindBadge(kind: DocKind) {
    val c = Inkwell.colors
    Box(Modifier.clip(Radius8).background(c.surfaceAlt).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(kindLabel(kind), style = MaterialTheme.typography.labelSmall, color = c.inkMuted, maxLines = 1)
    }
}

/** One document with its matches; the header opens the document, rows open the matching page. */
@Composable
internal fun DocumentResultCard(
    group: DocGroup,
    terms: List<String>,
    wide: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpen: (pageIndex: Int?) -> Unit,
    vm: SearchViewModel,
) {
    val c = Inkwell.colors
    val openLabel = stringResource(R.string.search_open_document, group.title)
    Column(
        Modifier.fillMaxWidth().clip(Radius12).background(c.surface).border(1.dp, c.outline, Radius12),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = openLabel, role = Role.Button) { onOpen(null) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DocThumbnail(group.thumbnailPath, group.kind)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val title = remember(group.title, terms, c.accent) { highlighted(group.title, terms, c.accent) }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    group.folderPath.ifBlank { stringResource(R.string.search_root_folder) },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            KindBadge(group.kind)
        }
        val rows = group.rows
        if (rows.isNotEmpty()) {
            HorizontalDivider(thickness = 1.dp, color = c.outline)
            val visible = if (expanded) rows else rows.take(VISIBLE_ROWS)
            visible.forEachIndexed { i, hit ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 48.dp), thickness = 1.dp, color = c.outline.copy(alpha = 0.6f))
                MatchRow(hit, terms, wide, vm) { onOpen(hit.pageIndex) }
            }
            if (rows.size > VISIBLE_ROWS) {
                HorizontalDivider(thickness = 1.dp, color = c.outline.copy(alpha = 0.6f))
                Box(
                    Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch).clickable(role = Role.Button, onClick = onToggleExpanded)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        if (expanded) stringResource(R.string.search_show_less)
                        else pluralStringResource(R.plurals.search_show_more, rows.size - VISIBLE_ROWS, rows.size - VISIBLE_ROWS),
                        style = MaterialTheme.typography.labelLarge,
                        color = c.accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun MatchRow(hit: SearchHit, terms: List<String>, wide: Boolean, vm: SearchViewModel, onClick: () -> Unit) {
    val c = Inkwell.colors
    val location = SearchResults.location(hit)
    val related = hit.matchStart < 0 && hit.source != TextSource.FOLDER && hit.source != TextSource.TAG &&
        TextMatch.highlightRanges(hit.excerpt, terms).isEmpty()
    val primary = if (hit.matchStart >= 0 && hit.matchEnd > hit.matchStart) hit.matchStart until hit.matchEnd else null
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(sourceIcon(hit.source), sourceLabel(hit.source), Modifier.padding(top = 1.dp).size(Dimens.icon), tint = c.inkMuted)
        Spacer(Modifier.width(12.dp))
        if (wide) {
            Text(
                location.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = c.inkMuted,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 52.dp).padding(top = 2.dp, end = 8.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            if (!wide && location != null || related) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!wide && location != null) Text(location, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
                    if (related) Text(stringResource(R.string.search_related), style = MaterialTheme.typography.labelMedium, color = c.accent)
                }
                Spacer(Modifier.height(2.dp))
            }
            val excerpt = remember(hit.excerpt, terms, c.accent, primary) { highlighted(hit.excerpt, terms, c.accent, primary) }
            Text(
                excerpt,
                style = MaterialTheme.typography.bodyMedium,
                color = c.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val pageId = hit.pageId
        val pageIndex = hit.pageIndex
        if (wide && pageId != null && pageIndex != null && SearchResults.hasPagePreview(hit)) {
            Spacer(Modifier.width(12.dp))
            PagePreview(vm, hit.documentId, pageId, pageIndex)
        }
    }
}

/** Semantic toggle chip. */
@Composable
internal fun SemanticPill(on: Boolean, onToggle: () -> Unit) {
    FilterPill(
        label = stringResource(R.string.search_semantic),
        selected = on,
        onClick = onToggle,
        icon = Icons.Outlined.AutoAwesome,
        toggle = true,
    )
}
