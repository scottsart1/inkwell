package com.inkwell.notes.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.app.AppNavigator
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.delay

private val MaxContentWidth = 960.dp
private val WideBreakpoint = 600.dp

/** Library-wide search across titles, typed text, handwriting, OCR, PDFs and transcripts. Works offline. */
@Composable
fun SearchScreen(initialQuery: String, nav: AppNavigator) {
    val context = LocalContext.current
    val graph = remember(context) { AppGraph.get(context) }
    val vm: SearchViewModel = viewModel { SearchViewModel(graph, initialQuery) }
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Inkwell.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val snackbar = remember { SnackbarHostState() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var text by rememberSaveable { mutableStateOf(vm.state.value.query) }
    var resumedOnce by remember { mutableStateOf(false) }

    fun setQuery(q: String) {
        text = q
        vm.onQueryChange(q)
    }

    // Search opened again on top of itself with another query (single-top navigation).
    var appliedInitial by rememberSaveable { mutableStateOf(initialQuery) }
    LaunchedEffect(initialQuery) {
        if (initialQuery != appliedInitial) {
            appliedInitial = initialQuery
            setQuery(initialQuery)
        }
    }
    LaunchedEffect(Unit) {
        // After process death the field restores its text but the ViewModel starts from the
        // navigation argument: hand it the text the user sees.
        if (text != vm.state.value.query) vm.onQueryChange(text)
        // Let the screen settle before focusing so the keyboard opens reliably.
        delay(120)
        runCatching { focus.requestFocus() }
        if (text.isEmpty()) keyboard?.show()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Back from a document: its text may have changed while it was open.
        if (resumedOnce) vm.refresh() else resumedOnce = true
    }
    val missingMessage = stringResource(R.string.search_document_missing)
    LaunchedEffect(state.message) {
        if (state.message == SearchMessage.DOCUMENT_MISSING) {
            vm.consumeMessage()
            snackbar.showSnackbar(missingMessage)
        }
    }

    Box(Modifier.fillMaxSize().background(c.ground)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= WideBreakpoint
            val side = if (wide) 24.dp else Dimens.gutter
            Column(Modifier.align(Alignment.TopCenter).widthIn(max = MaxContentWidth).fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = side - 8.dp, end = side, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.search_back), { nav.back() })
                    Spacer(Modifier.width(4.dp))
                    SearchQueryField(
                        value = text,
                        onValueChange = ::setQuery,
                        onSearch = {
                            vm.submit()
                            keyboard?.hide()
                        },
                        focusRequester = focus,
                        modifier = Modifier.weight(1f),
                    )
                }
                Box(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = side)) {
                    if (state.loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = c.accent, trackColor = c.surfaceAlt)
                    }
                }
                FilterBar(state, side, onFilter = vm::setFilter, onSemantic = { vm.setSemantic(!state.semanticOn) })
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Body(
                        state = state,
                        side = side,
                        wide = wide,
                        expanded = expanded,
                        vm = vm,
                        onRecent = { q ->
                            text = q
                            vm.useRecent(q)
                            keyboard?.hide()
                        },
                        onOpen = { docId, page ->
                            keyboard?.hide()
                            vm.open(docId, page) { doc, p -> nav.openDocument(doc, p) }
                        },
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp)) { data ->
            Snackbar(data, shape = Radius12, containerColor = c.ink, contentColor = c.ground)
        }
    }
}

@Composable
private fun FilterBar(state: SearchUiState, side: androidx.compose.ui.unit.Dp, onFilter: (SearchFilter) -> Unit, onSemantic: () -> Unit) {
    val c = Inkwell.colors
    val counts = remember(state.hits) { SearchResults.counts(state.hits) }
    val showCounts = state.searchedQuery.isNotEmpty() && state.hits.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = side - 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (f in SearchFilter.entries) {
            FilterPill(
                label = filterLabel(f),
                selected = state.filter == f,
                onClick = { onFilter(f) },
                count = if (showCounts && f != SearchFilter.ALL) counts[f]?.takeIf { it > 0 } else null,
            )
        }
        if (state.semanticAvailable) {
            Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(20.dp).background(c.outline))
            SemanticPill(state.semanticOn, onSemantic)
        }
    }
}

@Composable
private fun Body(
    state: SearchUiState,
    side: androidx.compose.ui.unit.Dp,
    wide: Boolean,
    expanded: MutableMap<String, Boolean>,
    vm: SearchViewModel,
    onRecent: (String) -> Unit,
    onOpen: (String, Int?) -> Unit,
) {
    when {
        state.query.isBlank() -> {
            if (state.recent.isEmpty()) EmptyHint(side) else RecentSearches(state.recent, side, onRecent, vm::removeRecent, vm::clearRecent)
        }
        state.failed -> Failed(side, vm::retry)
        state.searchedQuery.isEmpty() -> Unit
        else -> {
            val groups = remember(state.hits, state.filter) { SearchResults.group(state.hits, state.filter) }
            if (groups.isEmpty()) {
                if (!state.loading) NoResults(state, side, onShowAll = { vm.setFilter(SearchFilter.ALL) })
            } else {
                Results(state, groups, side, wide, expanded, vm, onOpen)
            }
        }
    }
}

@Composable
private fun Results(
    state: SearchUiState,
    groups: List<DocGroup>,
    side: androidx.compose.ui.unit.Dp,
    wide: Boolean,
    expanded: MutableMap<String, Boolean>,
    vm: SearchViewModel,
    onOpen: (String, Int?) -> Unit,
) {
    val c = Inkwell.colors
    val listState = rememberLazyListState()
    val terms = state.terms
    LaunchedEffect(state.searchedQuery, state.filter) { listState.scrollToItem(0) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "summary") {
            Column {
                val matches = groups.sumOf { it.hits.size }
                Text(
                    stringResource(
                        R.string.search_summary,
                        pluralStringResource(R.plurals.search_result_count, matches, matches),
                        pluralStringResource(R.plurals.search_document_count, groups.size, groups.size),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.inkMuted,
                )
                if (state.semanticFailed) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = c.inkMuted)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.search_semantic_failed), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
                    }
                }
            }
        }
        items(groups, key = { it.documentId }) { g ->
            DocumentResultCard(
                group = g,
                terms = terms,
                wide = wide,
                expanded = expanded[g.documentId] == true,
                onToggleExpanded = { expanded[g.documentId] = expanded[g.documentId] != true },
                onOpen = { page -> onOpen(g.documentId, page) },
                vm = vm,
            )
        }
    }
}

@Composable
private fun RecentSearches(
    recent: List<String>,
    side: androidx.compose.ui.unit.Dp,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    val c = Inkwell.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 16.dp, bottom = 32.dp)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.search_recent_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = c.inkMuted,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = onClear, modifier = Modifier.heightIn(min = Dimens.minTouch)) {
                    Text(stringResource(R.string.search_recent_clear), style = MaterialTheme.typography.labelLarge, color = c.accent)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        items(recent, key = { "recent:$it" }) { q ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { onPick(q) }
                    .padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.History, null, Modifier.size(Dimens.icon), tint = c.inkMuted)
                Spacer(Modifier.width(16.dp))
                Text(q, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                SearchIconButton(Icons.Outlined.Close, stringResource(R.string.search_recent_remove, q), { onRemove(q) })
            }
            HorizontalDivider(Modifier.padding(start = 40.dp), thickness = 1.dp, color = c.outline.copy(alpha = 0.6f))
        }
    }
}

/** Centered icon + title + body, used for the empty, no-result and failure states. */
@Composable
private fun StateMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String?,
    side: androidx.compose.ui.unit.Dp,
    extra: (@Composable () -> Unit)? = null,
) {
    val c = Inkwell.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = side).padding(top = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).background(c.surfaceAlt, Radius12), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = c.inkMuted)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        }
        if (extra != null) {
            Spacer(Modifier.height(12.dp))
            extra()
        }
    }
}

@Composable
private fun EmptyHint(side: androidx.compose.ui.unit.Dp) {
    StateMessage(Icons.Outlined.Search, stringResource(R.string.search_empty_title), stringResource(R.string.search_empty_body), side)
}

@Composable
private fun NoResults(state: SearchUiState, side: androidx.compose.ui.unit.Dp, onShowAll: () -> Unit) {
    val c = Inkwell.colors
    val filtered = state.filter != SearchFilter.ALL && state.hits.isNotEmpty()
    StateMessage(
        icon = Icons.Outlined.SearchOff,
        title = stringResource(R.string.search_no_results_title, state.searchedQuery),
        body = if (filtered) stringResource(R.string.search_no_results_filtered) else stringResource(R.string.search_no_results_body),
        side = side,
        extra = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (filtered) {
                    TextButton(onClick = onShowAll, modifier = Modifier.heightIn(min = Dimens.minTouch)) {
                        Text(stringResource(R.string.search_show_all_types), style = MaterialTheme.typography.labelLarge, color = c.accent)
                    }
                }
                if (state.pendingPages > 0) {
                    Text(
                        pluralStringResource(R.plurals.search_pending_pages, state.pendingPages, state.pendingPages),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.inkMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 420.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun Failed(side: androidx.compose.ui.unit.Dp, onRetry: () -> Unit) {
    val c = Inkwell.colors
    StateMessage(
        icon = Icons.Outlined.ErrorOutline,
        title = stringResource(R.string.search_failed),
        body = null,
        side = side,
        extra = {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = Dimens.minTouch)) {
                Text(stringResource(R.string.search_retry), style = MaterialTheme.typography.labelLarge, color = c.accent)
            }
        },
    )
}
