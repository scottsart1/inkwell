package com.inkwell.notes.whiteboard.kit

import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** A user-visible message, resolved from string resources at display time. */
data class UiMessage(
    @param:StringRes val text: Int,
    val args: List<Any> = emptyList(),
    @param:StringRes val action: Int? = null,
    val onAction: (() -> Unit)? = null,
    val long: Boolean = false,
)

/** Shows every message of [messages] in [host] (one at a time). */
@Composable
fun CollectMessages(messages: Flow<UiMessage>, host: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(messages, host) {
        messages.collect { m ->
            val text = context.getString(m.text, *m.args.toTypedArray())
            val result = host.showSnackbar(
                message = text,
                actionLabel = m.action?.let { context.getString(it) },
                duration = if (m.long || m.action != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) m.onAction?.invoke()
        }
    }
}

/**
 * [launch] for a user action: a failure goes to [onError] instead of ending the process, and,
 * unlike a scope-wide handler, leaves the scope alive for the next action. Cancellation passes.
 */
fun CoroutineScope.launchCatching(onError: (Throwable) -> Unit, block: suspend CoroutineScope.() -> Unit): Job = launch {
    try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        onError(t)
    }
}
