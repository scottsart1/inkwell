package com.inkwell.notes.editor.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.Texture
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.app.AppNavigator
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.AutoNameStatus
import com.inkwell.notes.core.model.ScrollDirection
import com.inkwell.notes.core.naming.DateNaming
import com.inkwell.notes.editor.EditorSession
import com.inkwell.notes.export.email.WorkEmailLauncher
import com.inkwell.notes.export.email.rememberWorkEmail
import com.inkwell.notes.editor.NotebookController
import com.inkwell.notes.editor.copyPageTextToClipboard
import com.inkwell.notes.editor.logic.PageRanges
import com.inkwell.notes.editor.logic.TopBarAction
import com.inkwell.notes.editor.logic.TopBarLayout
import com.inkwell.notes.editor.pageAiContext
import com.inkwell.notes.editor.ui.components.IconAction
import kotlinx.coroutines.launch

/** Top bar and session tabs (full editor) or the slim header of a split pane. */
@Composable
fun PaneChrome(
    controller: NotebookController,
    ui: PaneUiState,
    actions: PaneActions,
    nav: AppNavigator,
    mode: PaneMode,
    split: SplitHooks?,
    paneWidthDp: Float,
    settings: AppSettings,
    aiNamingAvailable: Boolean,
) {
    // Kept across focus mode so an email being prepared still opens.
    val workEmail = rememberWorkEmail()
    if (mode == PaneMode.FULL && ui.focusMode) return
    val doc = controller.doc
    val (x, n) = PageRanges.displayPosition(controller.currentPage, controller.pages.size)
    val pageLabel = if (n > 0 && TopBarLayout.showPageLabel(paneWidthDp)) stringResource(R.string.editor_page_x_of_n, x, n) else null
    val chip = if (doc != null && settings.naming.autoNameEnabled && doc.autoNameStatus == AutoNameStatus.PENDING && DateNaming.isUntitled(doc.title)) {
        stringResource(R.string.editor_auto_names_after, settings.naming.delayHours)
    } else null
    val (shown, folded) = TopBarLayout.split(if (mode == PaneMode.SPLIT) paneWidthDp - 44f else paneWidthDp).let { (s, f) ->
        if (mode == PaneMode.SPLIT) s.filter { it != TopBarAction.FOCUS } to f.filter { it != TopBarAction.FOCUS } else s to f
    }
    val active = buildSet {
        if (ui.right == RightPanel.SEARCH) add(TopBarAction.SEARCH)
        if (ui.left == LeftPanel.PAGES) add(TopBarAction.PAGES)
        if (ui.left == LeftPanel.OUTLINE) add(TopBarAction.OUTLINE)
        if (ui.right == RightPanel.AI) add(TopBarAction.AI)
        if (ui.right == RightPanel.RECORDINGS) add(TopBarAction.RECORDINGS)
    }
    val onAction: (TopBarAction) -> Unit = { a -> topBarAction(a, controller, ui) }
    val overflow = overflowItems(controller, ui, actions, mode, folded, settings, aiNamingAvailable, workEmail)

    EditorTopBar(
        title = doc?.title.orEmpty(),
        autoNameChip = chip,
        pageLabel = pageLabel,
        actions = shown,
        activeActions = active,
        onBack = if (mode == PaneMode.FULL || split?.isLeft == true) ({ nav.back() }) else null,
        onTitleClick = { if (doc != null) ui.dialog = PaneDialog.Rename },
        onPageLabelClick = { ui.toggleLeft(LeftPanel.PAGES) },
        onAction = onAction,
        overflow = overflow,
        trailing = if (split != null) {
            { IconAction(Icons.Outlined.Close, stringResource(R.string.editor_close_pane), split.onClosePane) }
        } else null,
        height = if (mode == PaneMode.SPLIT) 44.dp else com.inkwell.notes.ui.theme.Dimens.topBarHeight,
    )
    if (mode == PaneMode.FULL) {
        val tabs by EditorSession.tabs.tabs.collectAsState()
        if (tabs.size >= 2) {
            TabsStrip(
                tabs = tabs,
                currentDocId = controller.docId,
                onOpen = { nav.openDocumentById(it) },
                onClose = { id ->
                    val next = EditorSession.tabs.close(id)
                    if (id == controller.docId) {
                        // Leave this editor (it saves on the way out) and show the neighbour.
                        nav.back()
                        if (next != null) nav.openDocumentById(next)
                    }
                },
            )
        }
    }
}

private fun topBarAction(a: TopBarAction, controller: NotebookController, ui: PaneUiState) {
    when (a) {
        TopBarAction.SEARCH -> ui.toggleRight(RightPanel.SEARCH)
        TopBarAction.PAGES -> ui.toggleLeft(LeftPanel.PAGES)
        TopBarAction.OUTLINE -> ui.toggleLeft(LeftPanel.OUTLINE)
        TopBarAction.AI -> if (ui.right == RightPanel.AI) ui.right = null else ui.openAi(controller.pageAiContext())
        TopBarAction.RECORDINGS -> ui.toggleRight(RightPanel.RECORDINGS)
        TopBarAction.EXPORT -> controller.uiScope.launch {
            controller.saveNow()
            ui.dialog = PaneDialog.Export(null)
        }
        TopBarAction.FOCUS -> ui.focusMode = true
    }
}

@Composable
private fun overflowItems(
    controller: NotebookController,
    ui: PaneUiState,
    actions: PaneActions,
    mode: PaneMode,
    folded: List<TopBarAction>,
    settings: AppSettings,
    aiNamingAvailable: Boolean,
    workEmail: WorkEmailLauncher,
): List<MenuItemSpec> {
    val page = controller.currentPageMeta
    val items = mutableListOf<MenuItemSpec>()
    items += MenuItemSpec(stringResource(R.string.editor_rename), Icons.Outlined.Edit, 0) { ui.dialog = PaneDialog.Rename }
    items += MenuItemSpec(stringResource(if (aiNamingAvailable) R.string.editor_suggest_name_ai else R.string.editor_suggest_name), Icons.Outlined.AutoAwesome, 0) { actions.suggestName() }
    folded.forEach { a ->
        items += MenuItemSpec(stringResource(topBarLabel(a)), topBarIcon(a), 1) { topBarAction(a, controller, ui) }
    }
    // Edits still in memory are saved first so the email has the latest ink.
    items += MenuItemSpec(stringResource(R.string.work_email_action), Icons.Outlined.Email, 1, enabled = controller.doc != null && !workEmail.busy) {
        workEmail.sendDocument(controller.docId) { controller.saveNow() }
    }
    items += MenuItemSpec(stringResource(R.string.editor_add_page), Icons.Outlined.PostAdd, 2) { controller.addPage() }
    items += MenuItemSpec(stringResource(R.string.editor_paper_and_size), Icons.Outlined.Texture, 2, enabled = page != null) {
        page?.let { ui.dialog = PaneDialog.Paper(listOf(it.id)) }
    }
    items += MenuItemSpec(
        stringResource(if (page?.bookmarked == true) R.string.editor_remove_bookmark else R.string.editor_bookmark_page),
        if (page?.bookmarked == true) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
        2,
        enabled = page != null,
    ) { page?.let { if (it.bookmarked) controller.toggleBookmark(it) else ui.dialog = PaneDialog.BookmarkTitle(it.id) } }
    if (TopBarAction.OUTLINE !in folded) {
        items += MenuItemSpec(stringResource(R.string.editor_action_outline), Icons.AutoMirrored.Outlined.Toc, 2) { ui.toggleLeft(LeftPanel.OUTLINE) }
    }
    if (mode == PaneMode.FULL) {
        // A folded Focus button is already listed above.
        if (TopBarAction.FOCUS !in folded) {
            items += MenuItemSpec(stringResource(R.string.editor_action_focus), Icons.Outlined.CenterFocusWeak, 3) { ui.focusMode = true }
        }
        items += MenuItemSpec(stringResource(R.string.editor_open_side_by_side), Icons.Outlined.VerticalSplit, 3) { ui.dialog = PaneDialog.PickSplitPartner }
    }
    val horizontal = settings.view.scrollDirection == ScrollDirection.HORIZONTAL
    items += MenuItemSpec(
        stringResource(if (horizontal) R.string.editor_scroll_vertical else R.string.editor_scroll_horizontal),
        if (horizontal) Icons.Outlined.ViewDay else Icons.Outlined.ViewWeek,
        3,
    ) { actions.toggleScrollDirection() }

    items += MenuItemSpec(stringResource(R.string.editor_insert_pdf_pages), Icons.Outlined.FileOpen, 4) { actions.openPdfPicker() }
    items += MenuItemSpec(stringResource(R.string.editor_extract_current_page), Icons.Outlined.Unarchive, 4, enabled = page != null) {
        actions.extractPages(listOf(controller.currentPage))
    }
    items += MenuItemSpec(stringResource(R.string.editor_split_here), Icons.AutoMirrored.Outlined.CallSplit, 4, enabled = controller.currentPage > 0) {
        ui.dialog = PaneDialog.ConfirmSplitHere
    }
    items += MenuItemSpec(stringResource(R.string.editor_merge_with), Icons.AutoMirrored.Outlined.CallMerge, 4) { ui.dialog = PaneDialog.PickMergePartner }
    items += MenuItemSpec(stringResource(R.string.editor_copy_page_text), Icons.Outlined.ContentCopy, 4, enabled = page != null) {
        controller.copyPageTextToClipboard(controller.currentPage)
    }
    items += MenuItemSpec(stringResource(R.string.editor_my_elements), Icons.Outlined.Widgets, 5) { ui.dialog = PaneDialog.MyElements }
    items += MenuItemSpec(stringResource(R.string.editor_doc_info), Icons.Outlined.Info, 5) { ui.dialog = PaneDialog.DocInfo }
    return items
}
