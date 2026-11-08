package com.inkwell.notes.export.email

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Email to work" from any screen: prepares the email off the main thread and opens the
 * tablet's mail app with it, addressed to the work address from Settings (asked for once).
 */
@Stable
class WorkEmailLauncher internal constructor(
    private val context: Context,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    private sealed interface Target {
        class Document(val docId: String, val beforeExport: (suspend () -> Unit)?) : Target
        class Recording(val recordingId: String) : Target
    }

    private var asking by mutableStateOf<Target?>(null)

    /** An email is being prepared. */
    var busy by mutableStateOf(false)
        private set

    /** The whole document. [beforeExport] saves edits still in memory (the editor). */
    fun sendDocument(docId: String, beforeExport: (suspend () -> Unit)? = null) = start(Target.Document(docId, beforeExport))

    /** One recording's transcript. */
    fun sendRecording(recordingId: String) = start(Target.Recording(recordingId))

    private fun start(target: Target) {
        if (busy) return
        val to = graph.settings.current.destinations.workEmail
        if (!WorkEmail.isAddress(to)) {
            asking = target
            return
        }
        prepare(target, WorkEmail.normalize(to))
    }

    internal val askingForAddress: Boolean get() = asking != null

    internal fun cancelAddress() {
        asking = null
    }

    internal fun saveAddressAndSend(raw: String) {
        val target = asking ?: return
        asking = null
        val address = WorkEmail.normalize(raw)
        scope.launch {
            graph.settings.update { it.copy(destinations = it.destinations.copy(workEmail = address)) }
            prepare(target, address)
        }
    }

    private fun prepare(target: Target, to: String) {
        busy = true
        toast(R.string.work_email_preparing)
        scope.launch {
            try {
                val intent = when (target) {
                    is Target.Document -> {
                        target.beforeExport?.invoke()
                        withContext(Dispatchers.IO) { WorkEmailSender(graph).forDocument(target.docId, to) }
                    }
                    is Target.Recording -> withContext(Dispatchers.IO) { WorkEmailSender(graph).forRecording(target.recordingId, to) }
                }
                open(intent)
            } catch (c: CancellationException) {
                throw c
            } catch (e: WorkEmailSender.NoTranscript) {
                toast(R.string.work_email_no_transcript)
            } catch (e: Exception) {
                toast(R.string.work_email_failed)
            } finally {
                busy = false
            }
        }
    }

    private fun open(intent: Intent) {
        val activity = context.findActivity()
        if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val starter = activity ?: context
        try {
            starter.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // No app answers mailto: with attachments; offer every app that can send them.
            intent.selector = null
            try {
                starter.startActivity(Intent.createChooser(intent, context.getString(R.string.work_email_action)).apply {
                    if (activity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e2: Exception) {
                toast(R.string.work_email_no_app)
            }
        } catch (e: SecurityException) {
            toast(R.string.work_email_failed)
        }
    }

    private fun toast(@StringRes text: Int) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

/** A launcher for "Email to work"; shows the address prompt itself when needed. */
@Composable
fun rememberWorkEmail(): WorkEmailLauncher {
    val context = LocalContext.current
    val graph = remember(context) { AppGraph.get(context) }
    val scope = rememberCoroutineScope()
    val launcher = remember(context, scope) { WorkEmailLauncher(context, graph, scope) }
    if (launcher.askingForAddress) {
        WorkEmailAddressDialog(
            initial = graph.settings.current.destinations.workEmail,
            onSave = launcher::saveAddressAndSend,
            onDismiss = launcher::cancelAddress,
        )
    }
    return launcher
}

@Composable
private fun WorkEmailAddressDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Inkwell.colors
    var text by rememberSaveable { mutableStateOf(initial) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val valid = WorkEmail.isAddress(text)
    val save = {
        tried = true
        if (valid) onSave(text)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(stringResource(R.string.work_email_ask_title), style = MaterialTheme.typography.titleLarge, color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.work_email_ask_body), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.work_email_address)) },
                    isError = tried && !valid,
                    supportingText = if (tried && !valid) ({ Text(stringResource(R.string.work_email_invalid)) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier,
                )
            }
        },
        confirmButton = { TextButton(onClick = save) { Text(stringResource(R.string.work_email_ask_save), color = c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.work_email_cancel), color = c.inkMuted) } },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
