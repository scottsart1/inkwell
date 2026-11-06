package com.inkwell.notes.editor.ui.pages

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.editor.NotebookController
import com.inkwell.notes.editor.catchingNonCancel
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val THUMB_PX = 240

/** Loads a page thumbnail: the thumbnail service first, else a render from the live canvas. */
@Composable
fun rememberPageThumbnail(controller: NotebookController, page: PageMeta, index: Int): Bitmap? {
    val key = "${page.id}@${page.modifiedAt}@${page.rotation}@${page.paper.hashCode()}"
    val bitmap by produceState(controller.thumbCache.get(key), key) {
        // The initial value is used only once: after an edit, rotation or paper change [value]
        // still holds the old bitmap (shown until the new one is ready), so look up this key.
        controller.thumbCache.get(key)?.let { value = it; return@produceState }
        val fromService = withContext(Dispatchers.IO) {
            catchingNonCancel { controller.graph.thumbnails.pageThumbnail(controller.docId, page.id, THUMB_PX) }.getOrNull()
        }
        val bmp = fromService ?: catchingNonCancel { controller.canvas.renderPageBitmap(index, THUMB_PX) }.getOrNull()
        if (bmp != null) controller.thumbCache.put(key, bmp)
        value = bmp
    }
    return bitmap
}

/** A page card: paper-coloured placeholder until the thumbnail arrives. */
@Composable
fun PageThumbnail(
    controller: NotebookController,
    page: PageMeta,
    index: Int,
    current: Boolean,
    selecting: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = Inkwell.colors
    val rotated = page.rotation == 90 || page.rotation == 270
    val w = if (rotated) page.heightPt else page.widthPt
    val h = if (rotated) page.widthPt else page.heightPt
    val ratio = if (w > 0f && h > 0f) (w / h).coerceIn(0.4f, 2.5f) else 0.707f
    val bmp = rememberPageThumbnail(controller, page, index)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(shape)
            .background(Color(page.paper.color))
            .border(if (current || selected) 2.dp else 1.dp, if (current || selected) c.accent else c.outline, shape),
    ) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        if (page.bookmarked) {
            Icon(
                Icons.Outlined.Bookmark,
                contentDescription = null,
                tint = c.accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp),
            )
        }
        if (selecting) {
            Icon(
                if (selected) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (selected) c.accent else c.inkMuted,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(c.surface)
                    .size(20.dp),
            )
        }
    }
}
