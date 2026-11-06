package com.inkwell.notes.settings.ui

import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Applies settings changes strictly in the order they were made (a quick on/off must never
 * land as on), on the app scope so a change survives leaving the screen.
 */
internal class SettingsWriter private constructor(private val graph: AppGraph) {
    private class Pending(val transform: (AppSettings) -> AppSettings, val onError: (Throwable) -> Unit, val onApplied: () -> Unit)

    private val queue = Channel<Pending>(Channel.UNLIMITED)

    init {
        graph.appScope.launch(Dispatchers.IO) {
            for (p in queue) {
                val applied = try {
                    graph.settings.update(p.transform)
                    true
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    Log.w(TAG, "Settings update failed: ${e.javaClass.simpleName}")
                    runCatching { p.onError(e) }
                    false
                }
                if (applied) runCatching { p.onApplied() }
            }
        }
    }

    /** [onApplied] runs (on IO) once the change is saved and visible in `graph.settings.current`. */
    fun submit(onError: (Throwable) -> Unit = {}, onApplied: () -> Unit = {}, transform: (AppSettings) -> AppSettings) {
        queue.trySend(Pending(transform, onError, onApplied))
    }

    companion object {
        private const val TAG = "Settings"

        @Volatile private var instance: SettingsWriter? = null

        fun get(graph: AppGraph): SettingsWriter = instance ?: synchronized(this) {
            instance ?: SettingsWriter(graph).also { instance = it }
        }
    }
}

/** What settings screens use to change settings and show short messages. */
@Stable
internal class SettingsActions(
    val graph: AppGraph,
    private val uiScope: CoroutineScope,
    val snackbar: SnackbarHostState,
) {
    private val writer = SettingsWriter.get(graph)

    /** Queues a settings change; [onApplied] runs once it is saved (for work that reads the new value). */
    fun update(onApplied: () -> Unit = {}, transform: (AppSettings) -> AppSettings) {
        writer.submit(onError = { message("Couldn't save that change. Please try again.") }, onApplied = onApplied, transform = transform)
    }

    fun message(text: String) {
        uiScope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Short)
        }
    }

    /** Shows [text] with an action (e.g. Undo); [onAction] runs only when the action is tapped. */
    fun messageWithAction(text: String, actionLabel: String, onAction: () -> Unit) {
        uiScope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(text, actionLabel = actionLabel, withDismissAction = true, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) onAction()
        }
    }

    /** Runs [block] on the IO dispatcher in the app scope; failures become a message. */
    fun background(failureMessage: String, block: suspend () -> Unit) {
        graph.appScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "Background action failed: ${e.javaClass.simpleName}")
                message(failureMessage)
            }
        }
    }

    private companion object {
        const val TAG = "Settings"
    }
}

@Composable
internal fun rememberSettingsActions(graph: AppGraph, snackbar: SnackbarHostState): SettingsActions {
    val scope = rememberCoroutineScope()
    return remember(graph, snackbar, scope) { SettingsActions(graph, scope, snackbar) }
}
