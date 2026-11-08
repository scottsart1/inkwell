package com.inkwell.notes.export.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.inkwell.notes.R
import com.inkwell.notes.export.email.WorkEmail

/**
 * "Email to work": the user's own work address. Notes and transcripts reach a work computer that
 * can't open Google through the tablet's mail app (menus in the library, editor and recordings).
 */
@Composable
internal fun WorkEmailCard(saved: String, onSave: (String) -> Unit) {
    val focus = LocalFocusManager.current
    var text by rememberSaveable { mutableStateOf(saved) }
    var tried by rememberSaveable { mutableStateOf(false) }
    var justSaved by rememberSaveable { mutableStateOf(false) }
    // Follow the stored value when it changes elsewhere (the address prompt in a menu).
    LaunchedEffect(saved) {
        if (WorkEmail.normalize(text) != saved) text = saved
    }
    val normalized = WorkEmail.normalize(text)
    val valid = normalized.isEmpty() || WorkEmail.isAddress(normalized)
    val changed = normalized != saved
    val save = {
        tried = true
        if (valid && changed) {
            onSave(normalized)
            text = normalized
            justSaved = true
            tried = false
            focus.clearFocus()
        }
    }

    StorageCard(
        title = stringResource(R.string.work_email_title),
        subtitle = stringResource(R.string.work_email_subtitle),
        icon = Icons.Outlined.Email,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                justSaved = false
            },
            singleLine = true,
            label = { Text(stringResource(R.string.work_email_address)) },
            placeholder = { Text("name@company.com") },
            isError = tried && !valid,
            supportingText = when {
                tried && !valid -> ({ Text(stringResource(R.string.work_email_invalid)) })
                justSaved && !changed -> ({ Text(stringResource(R.string.work_email_saved)) })
                else -> null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            colors = inkFieldColors(),
            shape = R8,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SecondaryAction(text = stringResource(R.string.work_email_save), onClick = save, enabled = changed)
        }
    }
}
