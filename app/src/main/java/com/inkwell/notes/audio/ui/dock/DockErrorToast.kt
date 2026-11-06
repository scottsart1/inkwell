package com.inkwell.notes.audio.ui.dock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.audio.RecordingState
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.delay

/** Dark pill near the bottom of the editor reporting why recording stopped or could not start. */
@Composable
internal fun BoxWithConstraintsScope.DockErrorToast(error: RecordingState.Error, report: Int) {
    // Keyed by the report, not the state: the same error reported again is equal to the one
    // already seen (the state does not even change) but must be shown again.
    var visible by remember(error, report) { mutableStateOf(DockMemory.dismissedError != report) }
    LaunchedEffect(error, report) {
        if (visible) {
            delay(TOAST_MS)
            visible = false
            DockMemory.dismissedError = report
        }
    }
    val c = Inkwell.colors
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp, start = Dimens.gutter, end = Dimens.gutter),
    ) {
        Row(
            Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(c.ink)
                .padding(start = 16.dp, end = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.MicOff, contentDescription = null, tint = c.surface, modifier = Modifier.size(18.dp))
            Text(
                error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = c.surface,
                modifier = Modifier.weight(1f, fill = false).padding(start = 10.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            )
            val dismiss = stringResource(R.string.audio_dismiss)
            Box(
                Modifier.size(Dimens.minTouch).clip(CircleShape).clickable(role = Role.Button, onClickLabel = dismiss) {
                    visible = false
                    DockMemory.dismissedError = report
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Close, contentDescription = dismiss, tint = c.surface, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private const val TOAST_MS = 6_000L
