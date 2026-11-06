package com.inkwell.notes.editor.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import com.inkwell.notes.ai.ui.AiPanelContext
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.OutlineEntry
import com.inkwell.notes.core.model.PenConfig
import com.inkwell.notes.core.model.PenPreset
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.core.storage.db.ReusableElementEntity
import com.inkwell.notes.editor.ImagePlacement
import com.inkwell.notes.editor.SelectionText
import com.inkwell.notes.editor.logic.PendingCapture
import com.inkwell.notes.editor.ui.toolbar.ColorTarget
import com.inkwell.notes.ui.theme.Dimens

enum class PaneMode { FULL, SPLIT }

enum class LeftPanel { PAGES, OUTLINE }

enum class RightPanel { AI, RECORDINGS, SEARCH }

/** The one dialog a pane shows at a time. */
sealed interface PaneDialog {
    data object Rename : PaneDialog
    data class Paper(val pageIds: List<String>) : PaneDialog
    data object DocInfo : PaneDialog
    data class Export(val indices: List<Int>?) : PaneDialog
    data object PickSplitPartner : PaneDialog
    data class PickCopyTarget(val pageIds: List<String>) : PaneDialog
    data object PickMergePartner : PaneDialog
    data class ConfirmDeletePages(val pageIds: List<String>) : PaneDialog
    data object ConfirmSplitHere : PaneDialog
    data class BookmarkTitle(val pageId: String) : PaneDialog
    data object AddOutlineEntry : PaneDialog
    data class RenameOutlineEntry(val entry: OutlineEntry) : PaneDialog
    data class ConfirmAiOutline(val request: AiRequest, val tokens: Int, val costUsd: Double?, val model: String?) : PaneDialog
    data object MyElements : PaneDialog
    data object SaveElement : PaneDialog
    data class RenameElement(val element: ReusableElementEntity) : PaneDialog
    data class ConvertText(val result: SelectionText) : PaneDialog
    data object Crop : PaneDialog
    data class CustomColor(val target: ColorTarget, val initial: Int) : PaneDialog
    data class SavePreset(val tool: ToolType, val pen: PenConfig) : PaneDialog
    data class EditPreset(val preset: PenPreset) : PaneDialog
    data class ImageSource(val placement: ImagePlacement) : PaneDialog
    data class MicRationale(val permanentlyDenied: Boolean) : PaneDialog
    data class RecordingElsewhere(val otherDocId: String) : PaneDialog
    data object RecordingControls : PaneDialog
}

/** UI-only state of one editor pane (panels, dialog, focus mode). */
@Stable
class PaneUiState(initialRightWidth: Dp = Dimens.panelWidth) {
    var left by mutableStateOf<LeftPanel?>(null)
    var right by mutableStateOf<RightPanel?>(null)
    var rightWidth by mutableStateOf(initialRightWidth)
    var aiContext by mutableStateOf<AiPanelContext?>(null)
    var searchQuery by mutableStateOf("")
    var dialog by mutableStateOf<PaneDialog?>(null)
    var focusMode by mutableStateOf(false)
    /** Where a picked or captured image goes. */
    var pendingPlacement by mutableStateOf<ImagePlacement?>(null)
    var cameraFile by mutableStateOf<java.io.File?>(null)

    fun toggleLeft(p: LeftPanel) { left = if (left == p) null else p }

    fun openAi(context: AiPanelContext) {
        aiContext = context
        right = RightPanel.AI
    }

    fun openSearch(query: String = searchQuery) {
        searchQuery = query
        right = RightPanel.SEARCH
    }

    fun toggleRight(p: RightPanel) { right = if (right == p) null else p }

    /** Closes the topmost transient UI; returns false when nothing was open. */
    fun closeTopmost(): Boolean = when {
        dialog != null -> { dialog = null; true }
        right != null -> { right = null; true }
        left != null -> { left = null; true }
        focusMode -> { focusMode = false; true }
        else -> false
    }

    companion object {
        /**
         * Saves only a camera capture in flight (the camera app may outlive this process);
         * everything else starts fresh.
         */
        val CameraSaver: Saver<PaneUiState, String> = Saver(
            save = { s ->
                s.cameraFile?.let { f ->
                    val p = s.pendingPlacement
                    PendingCapture(f.path, p?.pageIndex, p?.at?.x, p?.at?.y).encode()
                }
            },
            restore = { saved ->
                PaneUiState().apply {
                    PendingCapture.decode(saved)?.let { c ->
                        cameraFile = java.io.File(c.path)
                        val at = if (c.x != null && c.y != null) android.graphics.PointF(c.x, c.y) else null
                        if (c.pageIndex != null || at != null) pendingPlacement = ImagePlacement(c.pageIndex, at)
                    }
                }
            },
        )
    }
}
