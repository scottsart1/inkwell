package com.inkwell.notes.editor.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.app.AppNavigator
import com.inkwell.notes.audio.RecordingState
import com.inkwell.notes.audio.ui.RecordingDock
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.ToolbarPosition
import com.inkwell.notes.editor.NotebookController
import com.inkwell.notes.editor.logic.MicPermissions
import com.inkwell.notes.editor.logic.MicRequestOutcome
import com.inkwell.notes.editor.logic.Throttler
import com.inkwell.notes.editor.ui.selection.SelectionMenu
import com.inkwell.notes.editor.ui.toolbar.ToolOptionsActions
import com.inkwell.notes.editor.ui.toolbar.ToolbarCallbacks
import com.inkwell.notes.editor.ui.toolbar.ToolbarHost
import com.inkwell.notes.editor.pageAiContext
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The reusable editor body for one notebook or PDF: chrome (full top bar or a slim split
 * header), the canvas with its floating toolbar and overlays, and the side panels. Used by
 * both [com.inkwell.notes.editor.EditorScreen] and the split view.
 */
@Composable
fun NotebookPane(
    docId: String,
    initialPage: Int?,
    nav: AppNavigator,
    mode: PaneMode,
    modifier: Modifier = Modifier,
    split: SplitHooks? = null,
    onController: (NotebookController?) -> Unit = {},
) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val scope = rememberCoroutineScope()
    val controller = remember(docId) { NotebookController(graph, docId, context, initialPage, scope) }
    // Saved: a photo taken after the process was killed must still find its file and place.
    val ui = rememberSaveable(docId, saver = PaneUiState.CameraSaver) { PaneUiState() }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val recState by graph.recording.state.collectAsStateWithLifecycle()
    val recordingHere = (recState as? RecordingState.Active)?.docId == docId

    // ---------------------------------------------------------------- Launchers
    lateinit var actions: PaneActions
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { actions.onPhotoPicked(it) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { actions.onPhotoTaken(it) }
    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { actions.insertPdf(it) } }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // An empty result means the prompt was cancelled (another prompt was showing): not a denial.
        val activity = context.findActivity()
        val micNow = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val rationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ?: true
        when (val outcome = MicPermissions.outcome(result, micNow, rationale)) {
            MicRequestOutcome.Granted -> actions.startRecording()
            is MicRequestOutcome.Denied -> ui.dialog = PaneDialog.MicRationale(outcome.permanently)
            MicRequestOutcome.Cancelled -> Unit
        }
    }
    val requestMic = {
        val hasNotifications = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val perms = MicPermissions.toRequest(Build.VERSION.SDK_INT, hasNotifications)
        try {
            micPermission.launch(perms.toTypedArray())
        } catch (e: ActivityNotFoundException) {
            ui.dialog = PaneDialog.MicRationale(permanentlyDenied = true)
        }
    }
    actions = remember(controller) {
        PaneActions(
            controller, ui, nav, mode, split,
            PaneLaunchers(
                pickImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                takePicture = { takePicture.launch(it) },
                openPdf = { openPdf.launch(arrayOf("application/pdf")) },
                requestMic = requestMic,
                hasMicPermission = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
            ),
        )
    }

    // ---------------------------------------------------------------- Effects
    LaunchedEffect(controller) { controller.observe() }
    DisposableEffect(controller) {
        onController(controller)
        onDispose {
            onController(null)
            controller.dispose()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> controller.onPause()
                Lifecycle.Event.ON_STOP -> controller.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(controller, settings.input) { controller.canvas.setInputPrefs(settings.input) }
    LaunchedEffect(controller, settings.view.scrollDirection, controller.bound) {
        if (controller.bound) controller.canvas.setLayoutMode(controller.layoutModeFor(settings.view.scrollDirection))
    }
    // Playback highlights the notes written at the current position (throttled).
    LaunchedEffect(controller, settings.recording.highlightDuringPlayback) {
        val throttle = Throttler(150)
        var highlighting = false
        // A restart (the setting changed) must not leave the previous effect's highlight behind.
        controller.canvas.setPlaybackHighlight(null, 0)
        graph.playback.state.collect { st ->
            val rec = st.recording
            val active = settings.recording.highlightDuringPlayback && st.playing && rec != null && rec.documentId == docId
            if (active) {
                if (throttle.tryAcquire(System.currentTimeMillis())) {
                    controller.canvas.setPlaybackHighlight(rec!!.id, st.positionMs)
                    highlighting = true
                }
            } else if (highlighting) {
                controller.canvas.setPlaybackHighlight(null, 0)
                highlighting = false
                throttle.reset()
            }
        }
    }
    // Recording errors are reported by the RecordingDock overlay (DockErrorToast), once.
    // Wrong kind (e.g. a stale link to a whiteboard): open it in its own screen instead.
    val doc = controller.doc
    LaunchedEffect(doc?.kind) {
        val d = doc ?: return@LaunchedEffect
        if (mode == PaneMode.FULL && d.kind != DocKind.NOTEBOOK && d.kind != DocKind.PDF) {
            nav.back()
            nav.openDocument(d)
        }
    }
    // Canvas-originated requests that need a dialog.
    LaunchedEffect(controller) {
        snapshotFlow { controller.imagePlacement }.collect { p ->
            if (p != null) {
                ui.dialog = PaneDialog.ImageSource(p)
                controller.imagePlacement = null
            }
        }
    }
    val aiNamingAvailable by produceState(false, settings.ai) {
        value = withContext(Dispatchers.IO) { runCatching { graph.ai.isAvailable(AiTask.NAMING) }.getOrDefault(false) }
    }
    val aiOutlineAvailable by produceState(false, settings.ai) {
        value = withContext(Dispatchers.IO) { runCatching { graph.ai.isAvailable(AiTask.SUMMARIZE) }.getOrDefault(false) }
    }

    if (mode == PaneMode.FULL) ImmersiveMode(ui.focusMode)

    BackHandler(enabled = controller.selection != null || ui.left != null || ui.right != null || ui.focusMode) {
        if (controller.selection != null) controller.canvas.clearSelection() else ui.closeTopmost()
    }

    // ---------------------------------------------------------------- Layout
    val focusRoot = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRoot.requestFocus() } }

    BoxWithConstraints(
        modifier
            .background(Inkwell.colors.desk)
            .focusRequester(focusRoot)
            .focusable()
            .onKeyEvent { e -> editorActionFor(e)?.let { actions.onKey(it) } ?: false },
    ) {
        val paneWidth = maxWidth.value
        Column(Modifier.fillMaxSize()) {
            PaneChrome(controller, ui, actions, nav, mode, split, paneWidth, settings, aiNamingAvailable)
            PaneBody(
                controller = controller,
                ui = ui,
                actions = actions,
                nav = nav,
                paneWidthDp = paneWidth,
                aiOutlineAvailable = aiOutlineAvailable,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                CanvasArea(controller, ui, actions, mode, split, settings, recordingHere, focusRoot)
            }
        }
        if (controller.missing) MissingDocument(onBack = { nav.back() })
        SnackbarHost(controller.messages.host, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }

    PaneDialogHost(controller, ui, actions, settings.penPresets, onRequestMic = requestMic)
}

/** The canvas with the floating toolbar and every overlay drawn above it. */
@Composable
private fun CanvasArea(
    controller: NotebookController,
    ui: PaneUiState,
    actions: PaneActions,
    mode: PaneMode,
    split: SplitHooks?,
    settings: com.inkwell.notes.core.model.AppSettings,
    recordingHere: Boolean,
    focusRoot: FocusRequester,
) {
    val density = LocalDensity.current
    val textEdit = controller.textEdit
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(controller) {
                // Touching the canvas gives this pane the keyboard (split view, DeX).
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Press && controller.textEdit == null) runCatching { focusRoot.requestFocus() }
                    }
                }
            },
    ) {
        AndroidView(
            factory = { _ -> controller.canvas.also { (it.parent as? ViewGroup)?.removeView(it) } },
            modifier = Modifier.fillMaxSize(),
        )
        if (!controller.pagesLoaded || (controller.doc == null && !controller.missing)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Inkwell.colors.accent, trackColor = Inkwell.colors.surfaceAlt)
        } else if (controller.pages.isEmpty() && !controller.missing) {
            EmptyDocument(onAddPage = { controller.addPage(0) }, modifier = Modifier.align(Alignment.Center))
        }

        RecordingDock(controller.docId, Modifier.fillMaxSize())

        val toolbarTop = settings.toolbar.position == ToolbarPosition.TOP && !(ui.focusMode && mode == PaneMode.FULL)
        val selection = controller.selection
        if (selection != null && textEdit == null) {
            SelectionMenu(
                selection = selection,
                minTopPx = if (toolbarTop) with(density) { 60.dp.toPx() } else 0f,
                inSplit = split != null,
                onAction = actions::onSelectionAction,
                dragSink = split?.dragSink,
            )
        }

        ToolbarHost(
            controller = controller,
            prefs = settings.toolbar,
            focusMode = ui.focusMode && mode == PaneMode.FULL,
            forceCompact = mode == PaneMode.SPLIT,
            presets = settings.penPresets,
            recordingActive = recordingHere,
            aiOpen = ui.right == RightPanel.AI,
            callbacks = ToolbarCallbacks(
                onRecord = actions::onRecord,
                onAi = { if (ui.right == RightPanel.AI) ui.right = null else ui.openAi(controller.pageAiContext()) },
                onPrefs = actions::updateToolbar,
                onMyElements = { ui.dialog = PaneDialog.MyElements },
                options = ToolOptionsActions(
                    onCustomColor = { target, initial -> ui.dialog = PaneDialog.CustomColor(target, initial) },
                    onSavePreset = { tool, pen -> ui.dialog = PaneDialog.SavePreset(tool, pen) },
                    onEditPreset = { ui.dialog = PaneDialog.EditPreset(it) },
                    onPickPhoto = { actions.pickPhoto(null) },
                    onCamera = { actions.takePhoto(null) },
                    onPasteImage = { actions.pasteImage(null) },
                    onSelectAll = { controller.canvas.selectAll(controller.currentPage) },
                ),
            ),
        )

        TemporaryToolToast(
            controller.tools.temporary,
            Modifier.align(Alignment.TopCenter).padding(top = if (toolbarTop) 64.dp else 16.dp),
        )
        BusyPill(controller.busy.labels, Modifier.align(Alignment.BottomStart).padding(16.dp))

        controller.longPress?.let { req -> LongPressMenu(controller, req, actions) }

        if (textEdit != null) {
            TextEditOverlay(
                textEdit,
                onDraft = { text, config -> controller.onTextDraft(textEdit, text, config) },
            ) { text, config -> actions.commitText(textEdit, text, config) }
        }

        if (ui.focusMode && mode == PaneMode.FULL) FocusModeExits(onExit = { ui.focusMode = false })

        if (controller.inputLocked) {
            // Swallows pen and touch above the canvas and toolbar while pages move to another document.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    },
            )
        }
    }
}

/** Shown when the document was deleted or the link is stale. */
@Composable
private fun MissingDocument(onBack: () -> Unit) {
    val c = Inkwell.colors
    Box(Modifier.fillMaxSize().background(c.ground), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(R.string.editor_doc_missing),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = c.ink,
            )
            androidx.compose.material3.TextButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.editor_back_to_library))
            }
        }
    }
}

@Composable
private fun EmptyDocument(onAddPage: () -> Unit, modifier: Modifier) {
    val c = Inkwell.colors
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.Text(
            androidx.compose.ui.res.stringResource(R.string.editor_no_pages),
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = c.ink,
        )
        androidx.compose.material3.TextButton(onClick = onAddPage, modifier = Modifier.padding(top = 8.dp)) {
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.editor_add_page))
        }
    }
}
