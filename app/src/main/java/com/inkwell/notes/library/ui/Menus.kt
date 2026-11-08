package com.inkwell.notes.library.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.inkwell.notes.R
import com.inkwell.notes.core.model.DocumentMeta

internal enum class DocAction { OPEN, SPLIT, RENAME, SUGGEST_NAME, FAVORITE, PIN, MOVE, DUPLICATE, TAGS, ARCHIVE, EXPORT, EMAIL_WORK, DELETE }

internal enum class FolderAction { OPEN, RENAME, APPEARANCE, NEW_INSIDE, MOVE, DELETE }

/** Per-document menu (overflow button and right click). */
@Composable
internal fun DocumentMenuItems(doc: DocumentMeta, aiNaming: Boolean, onAction: (DocAction) -> Unit) {
    LibMenuItem(stringResource(R.string.lib_action_open), Icons.AutoMirrored.Outlined.OpenInNew, { onAction(DocAction.OPEN) })
    LibMenuItem(stringResource(R.string.lib_action_open_split), Icons.Outlined.VerticalSplit, { onAction(DocAction.SPLIT) })
    MenuDivider()
    LibMenuItem(stringResource(R.string.lib_action_rename), Icons.Outlined.DriveFileRenameOutline, { onAction(DocAction.RENAME) })
    LibMenuItem(
        stringResource(if (aiNaming) R.string.lib_action_suggest_name_ai else R.string.lib_action_suggest_name),
        Icons.Outlined.AutoAwesome,
        { onAction(DocAction.SUGGEST_NAME) },
    )
    LibMenuItem(
        stringResource(if (doc.favorite) R.string.lib_action_unfavorite else R.string.lib_action_favorite),
        if (doc.favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline,
        { onAction(DocAction.FAVORITE) },
    )
    LibMenuItem(
        stringResource(if (doc.pinned) R.string.lib_action_unpin else R.string.lib_action_pin),
        Icons.Outlined.PushPin,
        { onAction(DocAction.PIN) },
    )
    LibMenuItem(stringResource(R.string.lib_action_move), Icons.AutoMirrored.Outlined.DriveFileMove, { onAction(DocAction.MOVE) })
    LibMenuItem(stringResource(R.string.lib_action_duplicate), Icons.Outlined.ContentCopy, { onAction(DocAction.DUPLICATE) })
    LibMenuItem(stringResource(R.string.lib_action_tags), Icons.AutoMirrored.Outlined.Label, { onAction(DocAction.TAGS) })
    LibMenuItem(
        stringResource(if (doc.archived) R.string.lib_action_unarchive else R.string.lib_action_archive),
        if (doc.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
        { onAction(DocAction.ARCHIVE) },
    )
    LibMenuItem(stringResource(R.string.lib_action_export), Icons.Outlined.IosShare, { onAction(DocAction.EXPORT) })
    LibMenuItem(stringResource(R.string.work_email_action), Icons.Outlined.Email, { onAction(DocAction.EMAIL_WORK) })
    MenuDivider()
    LibMenuItem(stringResource(R.string.lib_action_delete), Icons.Outlined.Delete, { onAction(DocAction.DELETE) }, destructive = true)
}

/** Per-folder menu. */
@Composable
internal fun FolderMenuItems(onAction: (FolderAction) -> Unit) {
    LibMenuItem(stringResource(R.string.lib_folder_open), Icons.Outlined.FolderOpen, { onAction(FolderAction.OPEN) })
    MenuDivider()
    LibMenuItem(stringResource(R.string.lib_folder_rename), Icons.Outlined.DriveFileRenameOutline, { onAction(FolderAction.RENAME) })
    LibMenuItem(stringResource(R.string.lib_folder_appearance), Icons.Outlined.Palette, { onAction(FolderAction.APPEARANCE) })
    LibMenuItem(stringResource(R.string.lib_folder_new_inside), Icons.Outlined.CreateNewFolder, { onAction(FolderAction.NEW_INSIDE) })
    LibMenuItem(stringResource(R.string.lib_folder_move), Icons.AutoMirrored.Outlined.DriveFileMove, { onAction(FolderAction.MOVE) })
    MenuDivider()
    LibMenuItem(stringResource(R.string.lib_folder_delete), Icons.Outlined.Delete, { onAction(FolderAction.DELETE) }, destructive = true)
}
