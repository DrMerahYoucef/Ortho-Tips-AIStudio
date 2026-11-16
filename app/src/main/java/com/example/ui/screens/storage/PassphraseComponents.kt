package com.example.ui.screens.storage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.crypto.PassphraseKeyBackup
import com.example.viewmodel.PassphraseOutcome
import com.example.viewmodel.PassphraseUi

@Composable
fun passphraseOutcomeText(outcome: PassphraseOutcome): String = stringResource(
    when (outcome) {
        PassphraseOutcome.SAVED -> R.string.passphrase_saved
        PassphraseOutcome.UNLOCKED -> R.string.passphrase_unlocked
        PassphraseOutcome.WRONG -> R.string.passphrase_wrong
        PassphraseOutcome.NO_BACKUP -> R.string.passphrase_no_backup
        PassphraseOutcome.LOCKED_DRIVE -> R.string.passphrase_locked_drive
        PassphraseOutcome.TOO_SHORT -> R.string.passphrase_too_short
        PassphraseOutcome.FAILED -> R.string.passphrase_failed
    }
)

private fun PassphraseOutcome.isError() =
    this != PassphraseOutcome.SAVED && this != PassphraseOutcome.UNLOCKED

/**
 * Passphrase entry. [confirm] adds a second field, used when choosing a new
 * passphrase so a typo cannot lock the user out.
 */
@Composable
fun PassphraseForm(
    confirm: Boolean,
    buttonLabel: String,
    working: Boolean,
    outcome: PassphraseOutcome?,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var value by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val tooShort = value.trim().length < PassphraseKeyBackup.MIN_LENGTH
    val mismatch = confirm && again.isNotEmpty() && again.trim() != value.trim()
    val canSubmit = !working && !tooShort && (!confirm || again.trim() == value.trim())
    val transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(stringResource(R.string.passphrase_label)) },
            supportingText = { Text(stringResource(R.string.passphrase_hint, PassphraseKeyBackup.MIN_LENGTH)) },
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(R.string.passphrase_show)
                    )
                }
            },
            enabled = !working,
            modifier = Modifier.fillMaxWidth()
        )
        if (confirm) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = again,
                onValueChange = { again = it },
                label = { Text(stringResource(R.string.passphrase_confirm_label)) },
                isError = mismatch,
                supportingText = {
                    if (mismatch) Text(stringResource(R.string.passphrase_mismatch))
                },
                visualTransformation = transformation,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !working,
                modifier = Modifier.fillMaxWidth()
            )
        }
        outcome?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = passphraseOutcomeText(it),
                style = MaterialTheme.typography.bodySmall,
                color = if (it.isError()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onSubmit(value.trim()) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (working) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(buttonLabel)
        }
    }
}

/** Rows for the "Encryption passphrase" section of Telegram Drive settings. */
@Composable
internal fun PassphraseRows(
    ui: PassphraseUi,
    onSave: (String) -> Unit,
    onUnlock: (String) -> Unit,
    onRecheck: () -> Unit,
    onDialogClosed: () -> Unit
) {
    var dialog by remember { mutableStateOf<Boolean?>(null) } // true = set, false = unlock

    val status = when {
        !ui.checked -> stringResource(R.string.passphrase_status_checking)
        ui.needsUnlock && ui.hasBackup -> stringResource(R.string.passphrase_status_locked, ui.lockedObjects)
        ui.needsUnlock -> stringResource(R.string.passphrase_status_locked_no_backup, ui.lockedObjects)
        ui.hasBackup -> stringResource(R.string.passphrase_status_set)
        else -> stringResource(R.string.passphrase_status_not_set)
    }
    DriveInfoRow(stringResource(R.string.passphrase_section_title), status)

    if (ui.needsUnlock) {
        DriveActionRow(
            icon = Icons.Default.LockOpen,
            title = stringResource(R.string.passphrase_unlock_title),
            subtitle = stringResource(R.string.passphrase_unlock_subtitle),
            onClick = { dialog = false }
        )
    } else if (ui.checked) {
        DriveActionRow(
            icon = Icons.Default.Key,
            title = stringResource(if (ui.hasBackup) R.string.passphrase_change_title else R.string.passphrase_set_title),
            subtitle = stringResource(R.string.passphrase_set_subtitle),
            onClick = { dialog = true }
        )
    } else {
        DriveActionRow(
            icon = Icons.Default.Key,
            title = stringResource(R.string.passphrase_recheck),
            subtitle = stringResource(R.string.passphrase_status_checking),
            onClick = onRecheck
        )
    }

    val mode = dialog
    if (mode != null) {
        // Close automatically once the action succeeded.
        LaunchedEffect(ui.outcome) {
            if (ui.outcome == PassphraseOutcome.SAVED || ui.outcome == PassphraseOutcome.UNLOCKED) {
                dialog = null
            }
        }
        AlertDialog(
            onDismissRequest = { if (!ui.working) { dialog = null; onDialogClosed() } },
            title = {
                Text(stringResource(if (mode) R.string.passphrase_set_title else R.string.passphrase_unlock_title))
            },
            text = {
                Column {
                    Text(
                        stringResource(if (mode) R.string.passphrase_set_explain else R.string.passphrase_unlock_explain),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    PassphraseForm(
                        confirm = mode,
                        buttonLabel = stringResource(if (mode) R.string.passphrase_save else R.string.passphrase_unlock_button),
                        working = ui.working,
                        outcome = ui.outcome?.takeIf { it != PassphraseOutcome.SAVED && it != PassphraseOutcome.UNLOCKED },
                        onSubmit = if (mode) onSave else onUnlock
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { dialog = null; onDialogClosed() },
                    enabled = !ui.working
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    ui.outcome?.takeIf { dialog == null && (it == PassphraseOutcome.SAVED || it == PassphraseOutcome.UNLOCKED) }?.let {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(passphraseOutcomeText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
