package com.inkwell.notes.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.collectAsState
import com.inkwell.notes.R
import com.inkwell.notes.app.AppNavigator
import com.inkwell.notes.app.SettingsSection
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.export.email.rememberWorkEmail
import com.inkwell.notes.export.ui.ExportDialog
import com.inkwell.notes.library.ItemSet
import com.inkwell.notes.library.LibraryCollection
import com.inkwell.notes.library.LibraryEvent
import com.inkwell.notes.library.LibraryJobs
import com.inkwell.notes.library.LibraryLayout
import com.inkwell.notes.library.LibraryLocation
import com.inkwell.notes.library.LibraryViewModel
import com.inkwell.notes.library.RailMode
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The rail's expanded folder ids. */
private val ExpandedFoldersSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

private fun LibraryLocation.key(): String = when (this) {
    LibraryLocation.Root -> "root"
    is LibraryLocation.InFolder -> "f:$folderId"
    is LibraryLocation.Collection -> "c:${collection.name}"
}

/** The library screen: rail, top bar, content, dialogs, drag layer and snackbars. */
@Composable
internal fun LibraryHome(vm: LibraryViewModel, nav: AppNavigator) {
    val c = Inkwell.colors
    val state by vm.state.collectAsState()
    val decor by vm.decor.collectAsState()
    val storage by vm.storage.collectAsState()
    val aiNaming by vm.aiNaming.collectAsState()
    val progress by vm.jobProgress.collectAsState()
    val settings by vm.settings.collectAsState()
    val resources = LocalContext.current.resources
    val controller = remember(vm, nav) { LibraryController(vm, nav, resources) }
    controller.state = state
    val workEmail = rememberWorkEmail()
    controller.emailToWork = { workEmail.sendDocument(it) }
    val drag = remember { DragDropState() }
    drag.canDrop = controller::canDrop
    val launchers = rememberLibraryLaunchers(vm)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // Saveable: the library leaves the composition while a document is open and comes back to it.
    var expanded by rememberSaveable(stateSaver = ExpandedFoldersSaver) { mutableStateOf(emptySet<String>()) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    val rootFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var shownLocation by rememberSaveable { mutableStateOf(state.location.key()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowMs = System.currentTimeMillis()
        }
    }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    LifecycleResumeEffect(vm) {
        vm.onResume()
        nowMs = System.currentTimeMillis()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.selectableKeys) { vm.pruneSelection(state.selectableKeys) }
    LaunchedEffect(vm.selection.active) {
        if (vm.selection.active && searchFocused) {
            focusManager.clearFocus()
            runCatching { rootFocus.requestFocus() }
        }
    }
    // Reveal the open folder in the rail tree; the user can still collapse its ancestors.
    val openPath = state.breadcrumb.map { it.id }
    LaunchedEffect(openPath) {
        val ancestors = openPath.dropLast(1)
        if (!expanded.containsAll(ancestors)) expanded = expanded + ancestors
    }
    LaunchedEffect(state.location) {
        val key = state.location.key()
        if (key != shownLocation) {
            shownLocation = key
            launch { runCatching { gridState.scrollToItem(0) } }
            launch { runCatching { listState.scrollToItem(0) } }
        }
    }

    suspend fun handle(e: LibraryEvent) {
        when (e) {
            is LibraryEvent.Open -> nav.openDocument(e.doc)
            is LibraryEvent.ShowFolder -> controller.openFolder(e.folderId)
            is LibraryEvent.Message -> scope.launch {
                val m = e.message
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(
                    message = m.text.resolve(resources),
                    actionLabel = m.actionLabel?.resolve(resources),
                    duration = if (m.long) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) m.action?.invoke()
            }
        }
    }
    LaunchedEffect(vm) { vm.events.collect { handle(it) } }
    LaunchedEffect(Unit) { LibraryJobs.events.collect { handle(it) } }

    BackHandler(enabled = drawerState.isOpen || controller.canHandleBack) {
        if (drawerState.isOpen) scope.launch { drawerState.close() } else controller.handleBack()
    }

    val onNew: (NewAction) -> Unit = { a ->
        when (a) {
            NewAction.NOTEBOOK -> vm.create(DocKind.NOTEBOOK)
            NewAction.TEMPLATE -> controller.dialog = LibraryDialog.Template
            NewAction.WHITEBOARD -> vm.create(DocKind.WHITEBOARD)
            NewAction.TYPED -> vm.create(DocKind.TYPED)
            NewAction.IMPORT_PDF -> launchers.importPdf()
            NewAction.IMPORT_IMAGES -> launchers.importImages()
            NewAction.SCAN -> launchers.scanDocument()
            NewAction.IMPORT_PACKAGE -> launchers.importPackage()
            NewAction.FOLDER -> controller.dialog = LibraryDialog.NewFolder(vm.creationFolder())
        }
    }
    val onEmptyAction: () -> Unit = {
        val loc = state.location
        when {
            state.folderMissing -> controller.openFolder(null)
            loc is LibraryLocation.Collection -> when (loc.collection) {
                LibraryCollection.WHITEBOARDS -> vm.create(DocKind.WHITEBOARD)
                LibraryCollection.DOCUMENTS -> vm.create(DocKind.TYPED)
                LibraryCollection.PDFS -> launchers.importPdf()
                else -> controller.openFolder(null)
            }
            else -> vm.create(DocKind.NOTEBOOK)
        }
    }
    val openSearch: (String) -> Unit = { q -> nav.openSearch(q.trim()) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .onGloballyPositioned { rootOffset = it.positionInRoot() }
            // Key handling sits above the focus target so it sees events whether the screen
            // itself or a child (the search field) has focus.
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val ctrl = e.isCtrlPressed || e.isMetaPressed
                when {
                    ctrl && e.key == Key.N -> {
                        vm.create(DocKind.NOTEBOOK)
                        true
                    }
                    ctrl && e.key == Key.F -> {
                        runCatching { searchFocus.requestFocus() }
                        true
                    }
                    ctrl && e.key == Key.A && !searchFocused && vm.selection.active -> {
                        vm.selectAll()
                        true
                    }
                    (e.key == Key.Delete || e.key == Key.Backspace) && !searchFocused && vm.selection.active -> {
                        controller.deleteSelection()
                        true
                    }
                    e.key == Key.Escape && vm.selection.active -> {
                        vm.clearSelection()
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        val windowWidth = maxWidth.value
        val mode = LibraryLayout.railMode(windowWidth)
        val railInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)
        val pane: @Composable (Modifier, Boolean) -> Unit = { m, menuButton ->
            MainPane(
                modifier = m.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(if (mode == RailMode.DRAWER) WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical else WindowInsetsSides.End + WindowInsetsSides.Vertical),
                ),
                windowWidth = windowWidth,
                controller = controller,
                drag = drag,
                decor = decor,
                aiNaming = aiNaming,
                progress = progress,
                env = CardEnv(nowMs, state.autoNaming, state.autoNameDelayHours, state.defaultPaper, vm.selection.active),
                searchFocus = searchFocus,
                onSearchFocused = { searchFocused = it },
                showMenuButton = menuButton,
                onMenu = { scope.launch { drawerState.open() } },
                onNew = onNew,
                onEmptyAction = onEmptyAction,
                onDeepSearch = openSearch,
                gridState = gridState,
                listState = listState,
            )
        }
        val toggleExpanded: (String) -> Unit = { id -> expanded = if (id in expanded) expanded - id else expanded + id }
        if (mode == RailMode.FULL) {
            Row(Modifier.fillMaxSize()) {
                LibraryRail(state, storage, controller, drag, expanded, toggleExpanded, Modifier.windowInsetsPadding(railInsets))
                VerticalDivider(thickness = 1.dp, color = c.outline)
                pane(Modifier.weight(1f), false)
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = mode == RailMode.DRAWER || drawerState.isOpen,
                scrimColor = if (c.isDark) c.ground.copy(alpha = 0.6f) else c.ink.copy(alpha = 0.32f),
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = c.ground,
                        drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
                        modifier = Modifier.width(Dimens.railWidth),
                    ) {
                        LibraryRail(
                            state, storage, controller, drag, expanded, toggleExpanded,
                            Modifier,
                            onNavigated = { scope.launch { drawerState.close() } },
                        )
                    }
                },
            ) {
                Row(Modifier.fillMaxSize()) {
                    if (mode == RailMode.COMPACT) {
                        CompactRail(state, controller, drag, { scope.launch { drawerState.open() } }, Modifier.windowInsetsPadding(railInsets))
                        VerticalDivider(thickness = 1.dp, color = c.outline)
                    }
                    pane(Modifier.weight(1f), mode == RailMode.DRAWER)
                }
            }
        }
        DragGhost(drag, rootOffset)
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp),
        ) { data ->
            Snackbar(
                data,
                shape = R12,
                containerColor = c.ink,
                contentColor = c.ground,
                actionColor = c.accentContainer,
            )
        }
    }

    LibraryDialogs(controller, state, storage, settings.notebookDefaults.template, nowMs, launchers, nav)
}

@Composable
private fun MainPane(
    modifier: Modifier,
    windowWidth: Float,
    controller: LibraryController,
    drag: DragDropState,
    decor: Map<String, com.inkwell.notes.library.CardDecor>,
    aiNaming: Boolean,
    progress: LibraryJobs.Progress?,
    env: CardEnv,
    searchFocus: FocusRequester,
    onSearchFocused: (Boolean) -> Unit,
    showMenuButton: Boolean,
    onMenu: () -> Unit,
    onNew: (NewAction) -> Unit,
    onEmptyAction: () -> Unit,
    onDeepSearch: (String) -> Unit,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val vm = controller.vm
    val state = controller.state
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val width = maxWidth.value
        val pad = LibraryLayout.contentPadding(windowWidth)
        val singleRow = LibraryLayout.singleRowTopBar(width)
        val columns = LibraryLayout.gridColumns(width - 2 * (pad - 8f), LibraryLayout.minCardWidth(windowWidth), 8f)
        Column(Modifier.fillMaxSize()) {
            LibraryTopBar(
                state = state,
                controller = controller,
                drag = drag,
                queryText = vm.queryText,
                onQueryChange = vm::setQuery,
                onSubmitSearch = { q -> if (q.isNotBlank()) onDeepSearch(q) },
                searchFocus = searchFocus,
                onSearchFocused = onSearchFocused,
                singleRow = singleRow,
                showMenuButton = showMenuButton,
                onMenu = onMenu,
                horizontalPadding = pad.dp,
                onNew = onNew,
            )
            if (progress != null) JobProgressBar(progress, vm::cancelJob, pad.dp)
            LibraryContent(
                state = state,
                decor = decor,
                env = env,
                controller = controller,
                drag = drag,
                aiNaming = aiNaming,
                columns = columns,
                horizontalPadding = pad.dp,
                onEmptyAction = onEmptyAction,
                onDeepSearch = onDeepSearch,
                gridState = gridState,
                listState = listState,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LibraryDialogs(
    controller: LibraryController,
    state: com.inkwell.notes.library.LibraryUiState,
    storage: com.inkwell.notes.core.model.StorageInfo?,
    defaults: com.inkwell.notes.core.model.PageTemplate,
    nowMs: Long,
    launchers: LibraryLaunchers,
    nav: AppNavigator,
) {
    val vm = controller.vm
    val dismiss = { controller.dialog = null }
    when (val d = controller.dialog) {
        null -> Unit
        is LibraryDialog.RenameDoc -> NameDialog(
            title = stringResource(R.string.lib_dialog_rename_doc),
            initial = state.docsById[d.doc.id]?.title ?: d.doc.title,
            confirmLabel = stringResource(R.string.lib_save),
            onConfirm = { name ->
                dismiss()
                vm.renameDocument(d.doc.id, name)
            },
            onDismiss = dismiss,
        )
        is LibraryDialog.RenameFolder -> NameDialog(
            title = stringResource(R.string.lib_dialog_rename_folder),
            initial = state.index[d.folder.id]?.name ?: d.folder.name,
            confirmLabel = stringResource(R.string.lib_save),
            onConfirm = { name ->
                dismiss()
                vm.renameFolder(d.folder.id, name)
            },
            onDismiss = dismiss,
        )
        is LibraryDialog.NewFolder -> {
            val parentName = d.parentId?.let { state.index[it]?.name }
            FolderEditorDialog(
                title = parentName?.let { stringResource(R.string.lib_dialog_new_folder_in, it) } ?: stringResource(R.string.lib_dialog_new_folder),
                initialName = "",
                initialColor = 0,
                initialIcon = "folder",
                confirmLabel = stringResource(R.string.lib_create),
                onConfirm = { name, color, icon ->
                    dismiss()
                    vm.createFolder(name, d.parentId, color, icon)
                },
                onDismiss = dismiss,
            )
        }
        is LibraryDialog.FolderAppearance -> {
            val f = state.index[d.folder.id] ?: d.folder
            FolderEditorDialog(
                title = stringResource(R.string.lib_dialog_folder_appearance),
                initialName = null,
                initialColor = f.color,
                initialIcon = f.icon,
                confirmLabel = stringResource(R.string.lib_save),
                onConfirm = { _, color, icon ->
                    dismiss()
                    vm.updateFolderAppearance(f.id, color, icon)
                },
                onDismiss = dismiss,
            )
        }
        is LibraryDialog.Tags -> {
            val current = state.docsById[d.doc.id]?.tags ?: d.doc.tags
            val suggestions = remember(state.allDocuments) {
                state.allDocuments.flatMap { it.tags }.groupingBy { it }.eachCount()
                    .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
                    .map { it.key }
            }
            TagsDialog(
                initial = current,
                suggestions = suggestions,
                onSave = { tags ->
                    dismiss()
                    vm.setTags(d.doc.id, tags)
                },
                onDismiss = dismiss,
            )
        }
        is LibraryDialog.Move -> MoveDialog(
            items = d.items,
            state = state,
            canMoveTo = { t -> controller.canDrop(d.items, t) },
            onMove = { t ->
                dismiss()
                vm.drop(d.items, t)
            },
            onCreateFolder = { name, parent, created -> vm.createFolder(name, parent, 0, "folder") { f -> created(f.id) } },
            onDismiss = dismiss,
        )
        is LibraryDialog.Split -> SplitPickerDialog(
            doc = d.doc,
            candidates = state.allDocuments,
            nowMs = nowMs,
            onPick = { other ->
                dismiss()
                nav.openSplit(d.doc.id, other.id)
            },
            onDismiss = dismiss,
        )
        is LibraryDialog.DeleteFolder -> ConfirmDialog(
            title = stringResource(R.string.lib_dialog_delete_folder, d.folder.name),
            body = stringResource(R.string.lib_dialog_delete_folder_body),
            confirmLabel = stringResource(R.string.lib_delete),
            onConfirm = {
                dismiss()
                vm.trash(ItemSet(folderIds = setOf(d.folder.id)))
            },
            onDismiss = dismiss,
        )
        is LibraryDialog.ExportOne -> ExportDialog(d.docId, null, dismiss)
        is LibraryDialog.ExportMany -> ExportFormatDialog(
            count = d.docIds.size,
            onChoose = { format ->
                dismiss()
                launchers.exportTo(d.docIds, format)
            },
            onDismiss = dismiss,
        )
        LibraryDialog.Storage -> StorageDialog(
            info = storage,
            onManage = {
                dismiss()
                nav.openSettings(SettingsSection.STORAGE)
            },
            onDismiss = dismiss,
        )
        LibraryDialog.Template -> NotebookTemplateSheet(
            defaults = defaults,
            onCreate = { template, makeDefault ->
                dismiss()
                vm.createFromTemplate(template, makeDefault)
            },
            onDismiss = dismiss,
        )
    }
}
