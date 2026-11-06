package com.inkwell.notes.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.indication
import com.inkwell.notes.R
import com.inkwell.notes.app.SettingsSection
import com.inkwell.notes.core.model.StorageInfo
import com.inkwell.notes.library.DropTarget
import com.inkwell.notes.library.FolderRow
import com.inkwell.notes.library.FolderStyle
import com.inkwell.notes.library.LibraryCollection
import com.inkwell.notes.library.LibraryLocation
import com.inkwell.notes.library.LibraryText
import com.inkwell.notes.library.LibraryUiState
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.delay

/** A rail destination. */
internal data class RailDestination(
    val icon: ImageVector,
    val label: Int,
    val collection: LibraryCollection?,
    val isTrash: Boolean = false,
)

internal val railDestinations = listOf(
    RailDestination(Icons.AutoMirrored.Outlined.LibraryBooks, R.string.lib_nav_library, null),
    RailDestination(Icons.Outlined.History, R.string.lib_nav_recent, LibraryCollection.RECENT),
    RailDestination(Icons.Outlined.StarOutline, R.string.lib_nav_favorites, LibraryCollection.FAVORITES),
    RailDestination(Icons.Outlined.Gesture, R.string.lib_nav_whiteboards, LibraryCollection.WHITEBOARDS),
    RailDestination(Icons.AutoMirrored.Outlined.Article, R.string.lib_nav_documents, LibraryCollection.DOCUMENTS),
    RailDestination(Icons.Outlined.PictureAsPdf, R.string.lib_nav_pdfs, LibraryCollection.PDFS),
    RailDestination(Icons.Outlined.Archive, R.string.lib_nav_archive, LibraryCollection.ARCHIVE),
    RailDestination(Icons.Outlined.Delete, R.string.lib_nav_trash, null, isTrash = true),
)

private fun RailDestination.isSelected(location: LibraryLocation): Boolean = when {
    isTrash -> false
    collection == null -> location is LibraryLocation.Root
    else -> location is LibraryLocation.Collection && location.collection == collection
}

private fun RailDestination.dropTarget(): DropTarget? = when {
    isTrash -> DropTarget.Trash
    collection == null -> DropTarget.Root
    else -> null
}

internal fun LibraryController.goTo(d: RailDestination) {
    when {
        d.isTrash -> nav.openTrash()
        d.collection == null -> openFolder(null)
        else -> showCollection(d.collection)
    }
}

/** The 232dp rail: wordmark, destinations, folder tree, storage summary and Settings. */
@Composable
internal fun LibraryRail(
    state: LibraryUiState,
    storage: StorageInfo?,
    controller: LibraryController,
    drag: DragDropState,
    expanded: Set<String>,
    onToggleExpanded: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNavigated: () -> Unit = {},
) {
    val c = Inkwell.colors
    Column(modifier.fillMaxHeight().width(Dimens.railWidth).background(c.ground)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = c.ink,
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        ) {
            for (d in railDestinations) {
                RailRow(
                    icon = d.icon,
                    label = stringResource(d.label),
                    selected = d.isSelected(state.location),
                    drag = drag,
                    dropTarget = d.dropTarget(),
                    onClick = {
                        controller.goTo(d)
                        onNavigated()
                    },
                )
            }
            Spacer(Modifier.height(20.dp))
            SectionHeader(stringResource(R.string.lib_folders), Modifier.padding(start = 12.dp)) {
                LibIconButton(Icons.Outlined.CreateNewFolder, stringResource(R.string.lib_new_folder), {
                    controller.dialog = LibraryDialog.NewFolder(null)
                })
            }
            val currentId = state.currentFolder?.id
            val rows = state.index.visibleRows(expanded)
            for (row in rows) {
                RailFolderRow(
                    row = row,
                    count = state.folderCounts[row.folder.id] ?: 0,
                    selected = row.folder.id == currentId && state.location is LibraryLocation.InFolder,
                    drag = drag,
                    controller = controller,
                    onToggle = { onToggleExpanded(row.folder.id) },
                    onOpen = {
                        controller.openFolder(row.folder.id)
                        onNavigated()
                    },
                )
            }
            RailRow(
                icon = Icons.Outlined.Add,
                label = stringResource(R.string.lib_new_folder),
                selected = false,
                drag = drag,
                dropTarget = null,
                muted = true,
                onClick = { controller.dialog = LibraryDialog.NewFolder(null) },
            )
            Spacer(Modifier.height(12.dp))
        }
        Hairline(Modifier.padding(horizontal = 12.dp))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            StorageRow(storage) {
                controller.vm.refreshStorage()
                controller.dialog = LibraryDialog.Storage
            }
            RailRow(
                icon = Icons.Outlined.Settings,
                label = stringResource(R.string.lib_settings),
                selected = false,
                drag = drag,
                dropTarget = null,
                onClick = {
                    controller.nav.openSettings(SettingsSection.GENERAL)
                    onNavigated()
                },
            )
        }
    }
}

/** 72dp icon rail for medium widths; the menu button opens the full rail as a drawer. */
@Composable
internal fun CompactRail(
    state: LibraryUiState,
    controller: LibraryController,
    drag: DragDropState,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Inkwell.colors
    Column(
        modifier.fillMaxHeight().width(72.dp).background(c.ground).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LibIconButton(Icons.Outlined.Menu, stringResource(R.string.lib_open_navigation), onOpenDrawer)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (d in railDestinations) {
                val target = d.dropTarget()
                val hovered = target != null && drag.hovered == target
                Box(
                    Modifier
                        .clip(R8)
                        .then(if (hovered) Modifier.border(1.dp, c.accent, R8) else Modifier)
                        .dropTarget(drag, target),
                ) {
                    LibIconButton(d.icon, stringResource(d.label), { controller.goTo(d) }, selected = d.isSelected(state.location) || hovered)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LibIconButton(Icons.Outlined.Settings, stringResource(R.string.lib_settings), { controller.nav.openSettings(SettingsSection.GENERAL) })
    }
}

@Composable
private fun RailRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    drag: DragDropState,
    dropTarget: DropTarget?,
    onClick: () -> Unit,
    muted: Boolean = false,
) {
    val c = Inkwell.colors
    val hovered = dropTarget != null && drag.hovered == dropTarget
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.minTouch)
            .clip(R8)
            .background(
                when {
                    hovered -> c.accentContainer
                    selected -> c.accentContainer
                    else -> Color.Transparent
                },
            )
            .then(if (hovered) Modifier.border(1.dp, c.accent, R8) else Modifier)
            .dropTarget(drag, dropTarget)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { if (selected) stateDescription = label }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(Dimens.icon), tint = if (selected || hovered) c.accent else c.inkMuted)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (muted) c.inkMuted else c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RailFolderRow(
    row: FolderRow,
    count: Int,
    selected: Boolean,
    drag: DragDropState,
    controller: LibraryController,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val c = Inkwell.colors
    val folder = row.folder
    val target = DropTarget.IntoFolder(folder.id)
    val hovered = drag.hovered == target
    val tint = FolderStyle.color(folder.color, c.inkMuted)
    var menuOffset by remember { mutableStateOf<DpOffset?>(null) }
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val openLabel = stringResource(R.string.lib_folder_open)
    val menuLabel = stringResource(R.string.lib_action_more_for, folder.name)
    // Hovering a collapsed folder while dragging opens it, so items can be dropped into sub-folders.
    LaunchedEffect(hovered, row.expanded) {
        if (hovered && row.hasChildren && !row.expanded) {
            delay(700)
            onToggle()
        }
    }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.minTouch)
                .clip(R8)
                .background(if (hovered || selected) c.accentContainer else Color.Transparent)
                .then(if (hovered) Modifier.border(1.dp, c.accent, R8) else Modifier)
                .dropTarget(drag, target)
                .itemGestures(
                    key = folder.id,
                    interactionSource = interaction,
                    drag = drag,
                    onTap = onOpen,
                    onLongPress = { menuOffset = DpOffset(16.dp, 0.dp) },
                    onSecondaryClick = { pos -> menuOffset = with(density) { DpOffset(pos.x.toDp(), 0.dp) } },
                    payload = { null },
                    onDrop = { _, _ -> },
                )
                .indication(interaction, ripple())
                .semantics {
                    role = Role.Button
                    onClick(openLabel) { onOpen(); true }
                    onLongClick(menuLabel) { menuOffset = DpOffset(16.dp, 0.dp); true }
                }
                .padding(start = (row.depth * 14).dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.hasChildren) {
                val toggleLabel = stringResource(if (row.expanded) R.string.lib_collapse_folder else R.string.lib_expand_folder, folder.name)
                Box(
                    Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (row.expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                        toggleLabel,
                        Modifier.size(18.dp),
                        tint = c.inkMuted,
                    )
                }
            } else {
                Spacer(Modifier.width(32.dp))
            }
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (FolderStyle.hasCustomIcon(folder.icon)) {
                    Icon(FolderStyle.icon(folder.icon), null, Modifier.size(18.dp), tint = tint)
                } else {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(tint))
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                folder.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) c.accent else c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (count > 0) {
                Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
            }
        }
        DropdownMenu(
            expanded = menuOffset != null,
            onDismissRequest = { menuOffset = null },
            offset = menuOffset ?: DpOffset.Zero,
            shape = R12,
            containerColor = c.surface,
        ) {
            FolderMenuItems { action ->
                menuOffset = null
                // "Open" navigates like a tap, which also closes the rail when it is a drawer.
                if (action == FolderAction.OPEN) onOpen() else controller.onFolderAction(folder, action)
            }
        }
    }
}

@Composable
private fun StorageRow(storage: StorageInfo?, onClick: () -> Unit) {
    val c = Inkwell.colors
    val text = storage?.let { LibraryText.storageSummary(it.documentCount, it.totalBytes) } ?: stringResource(R.string.lib_storage_loading)
    val label = stringResource(R.string.lib_storage_details)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.minTouch)
            .clip(R8)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Storage, null, Modifier.size(Dimens.icon), tint = c.inkMuted)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
