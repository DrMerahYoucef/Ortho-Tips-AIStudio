package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.example.R

/**
 * Guards a form against leaving with unsaved edits, by the top-bar arrow or the
 * phone's back gesture. Returns the function the back button should call.
 *
 * Nothing changes while [isDirty] is false, so an untouched form still closes in
 * one tap.
 */
@Composable
fun rememberLeaveGuard(
    isDirty: Boolean,
    onSave: () -> Unit,
    onLeave: () -> Unit
): () -> Unit {
    var asking by remember { mutableStateOf(false) }

    BackHandler(enabled = isDirty) { asking = true }

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.unsaved_message)) },
            confirmButton = {
                TextButton(onClick = { asking = false; onSave() }) {
                    Text(stringResource(R.string.unsaved_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { asking = false; onLeave() }) {
                    Text(stringResource(R.string.unsaved_discard))
                }
            }
        )
    }

    return { if (isDirty) asking = true else onLeave() }
}
