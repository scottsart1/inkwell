package com.inkwell.notes.editor.ui.panels

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.SearchHit
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.editor.NotebookController
import com.inkwell.notes.editor.catchingNonCancel
import com.inkwell.notes.editor.ui.toolbar.ActionRow
import com.inkwell.notes.editor.ui.components.IconAction
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Search inside this document: handwriting, typed text, OCR, PDF text and transcripts. */
@Composable
fun SearchPanelContent(
    controller: NotebookController,
    initialQuery: String,
    onReveal: (SearchHit) -> Unit,
    onSearchEverywhere: (String) -> Unit,
) {
    val c = Inkwell.colors
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<SearchHit>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = null; loading = false; failed = false
            return@LaunchedEffect
        }
        delay(250)
        loading = true
        // Scoped to this document before the limit: a library-wide top list can miss its hits.
        val r = withContext(Dispatchers.IO) { catchingNonCancel { controller.graph.search.searchInDocument(controller.docId, q, limit = 500) } }
        loading = false
        failed = r.isFailure
        results = r.getOrNull()
            ?.sortedWith(compareBy({ it.pageIndex ?: Int.MAX_VALUE }, { it.timestampMs ?: 0L }))
            .orEmpty()
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.editor_search_placeholder)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(Dimens.icon)) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconAction(Icons.Outlined.Close, stringResource(R.string.editor_clear), { query = "" }) }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { results?.firstOrNull()?.let(onReveal) }),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.outline),
            modifier = Modifier.fillMaxWidth().padding(12.dp).focusRequester(focus),
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surfaceAlt) else HorizontalDivider(color = c.outline)
        val list = results
        when {
            query.trim().length < 2 -> PanelEmpty(stringResource(R.string.editor_search_hint))
            failed -> PanelEmpty(stringResource(R.string.editor_search_failed))
            list == null -> Unit
            list.isEmpty() -> Column {
                PanelEmpty(stringResource(R.string.editor_search_none), Modifier.heightIn(min = 120.dp))
                ActionRow(Icons.AutoMirrored.Outlined.ManageSearch, stringResource(R.string.editor_search_everywhere), { onSearchEverywhere(query.trim()) })
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text(
                        pluralStringResource(R.plurals.editor_search_count, list.size, list.size),
                        style = MaterialTheme.typography.labelMedium, color = c.inkMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(list.size) { i -> HitRow(list[i], onReveal) }
                item {
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        ActionRow(Icons.AutoMirrored.Outlined.ManageSearch, stringResource(R.string.editor_search_everywhere), { onSearchEverywhere(query.trim()) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HitRow(hit: SearchHit, onReveal: (SearchHit) -> Unit) {
    val c = Inkwell.colors
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.minTouch)
            .clickable(role = Role.Button) { onReveal(hit) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            hit.pageIndex?.let {
                Text(
                    stringResource(R.string.editor_page_short, it + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.accent,
                    modifier = Modifier
                        .border(1.dp, c.accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(
                stringResource(sourceLabel(hit.source)),
                style = MaterialTheme.typography.labelSmall,
                color = c.inkMuted,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        val excerpt = buildAnnotatedString {
            val text = hit.excerpt
            val s = hit.matchStart
            val e = hit.matchEnd
            if (s in 0 until e && e <= text.length) {
                append(text.substring(0, s))
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.accent)) { append(text.substring(s, e)) }
                append(text.substring(e))
            } else append(text)
        }
        Text(excerpt, style = MaterialTheme.typography.bodySmall, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

fun sourceLabel(s: TextSource): Int = when (s) {
    TextSource.TITLE -> R.string.editor_source_title
    TextSource.TYPED -> R.string.editor_source_typed
    TextSource.HANDWRITING -> R.string.editor_source_handwriting
    TextSource.OCR -> R.string.editor_source_ocr
    TextSource.PDF_TEXT -> R.string.editor_source_pdf
    TextSource.TRANSCRIPT -> R.string.editor_source_transcript
    TextSource.TAG -> R.string.editor_source_tag
    TextSource.FOLDER -> R.string.editor_source_folder
}
