package com.example.ui.screens.storage

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.telegram.TelegramAuthState
import com.example.viewmodel.SetupStep
import com.example.viewmodel.SetupUiState
import com.example.viewmodel.TelegramSetupViewModel
import com.example.R
import com.example.ui.components.InternationalPhoneField
import androidx.compose.ui.res.stringResource

/**
 * First-time setup for Telegram Drive.
 *
 * Linear and explicit: the surgeon sees what is about to happen to their patient
 * data at every step, and can leave at any point without anything having been
 * switched over — the backend only changes on the initial-sync step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramSetupWizardScreen(
    viewModel: TelegramSetupViewModel,
    onNavigateBack: () -> Unit,
    onSetupComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Automatic steps start as soon as they appear. Keying on the step means a
    // recomposition cannot restart one, and the ViewModel ignores a repeat call
    // for a step that already succeeded.
    LaunchedEffect(state.step) {
        viewModel.runCurrentStep()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.telegram_setup_wizard_telegram_drive_setup)) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.cancel()
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
        ) {
            LinearProgressIndicator(
                progress = { (state.step.index + 1f) / SetupStep.total },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            )
            Text(
                text = stringResource(R.string.wizard_step_of, state.step.index + 1, SetupStep.total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                when (state.step) {
                    SetupStep.WELCOME -> WelcomeStep()
                    SetupStep.REQUIREMENTS -> RequirementsStep()
                    SetupStep.CREDENTIALS -> CredentialsStep(state, viewModel)
                    SetupStep.TELEGRAM_LOGIN -> LoginStep(state, viewModel)
                    SetupStep.NOTIFICATIONS -> PermissionsStep()
                    SetupStep.DRIVE_INITIALISATION -> DriveInitStep(state)
                    SetupStep.STORAGE_VERIFICATION -> VerificationStep(state)
                    SetupStep.CONNECTION_TEST -> ConnectionTestStep(state)
                    SetupStep.MANIFEST -> ManifestStep(state, viewModel)
                    SetupStep.INITIAL_SYNC -> InitialSyncStep(state)
                    SetupStep.COMPLETE -> CompleteStep(state, viewModel)
                }

                if (state.error == null && state.isStepPending) {
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = state.statusLine.ifBlank { stringResource(R.string.wizard_working) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                state.error?.let { error ->
                    Spacer(Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            if (state.step.isAutomatic) {
                                Spacer(Modifier.height(8.dp))
                                TextButton(
                                    onClick = viewModel::retryCurrentStep,
                                    enabled = !state.busy
                                ) { Text(stringResource(R.string.action_try_again)) }
                            }
                        }
                    }
                }
            }

            if (state.statusLine.isNotBlank()) {
                Text(
                    text = state.statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.step != SetupStep.WELCOME) {
                    OutlinedButton(
                        onClick = viewModel::back,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.action_back)) }
                }
                Button(
                    onClick = {
                        if (state.step.isLast) onSetupComplete() else viewModel.advance()
                    },
                    enabled = state.canAdvance,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(if (state.step.isLast) R.string.wizard_finish else R.string.wizard_continue))
                    }
                }
            }
        }
    }
}

@Composable
private fun StepHeading(title: String, body: String) {
    Text(text = title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(10.dp))
    Text(
        text = linkifyTelegram(body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private const val TELEGRAM_API_HOST = "my.telegram.org"

/**
 * Turns every "my.telegram.org" in a step's text into a tappable link that opens
 * the site in the browser. Works for both languages, since the host name itself
 * is never translated.
 */
@Composable
private fun linkifyTelegram(body: String): AnnotatedString {
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            textDecoration = TextDecoration.Underline
        )
    )
    return buildAnnotatedString {
        var from = 0
        while (true) {
            val at = body.indexOf(TELEGRAM_API_HOST, from)
            if (at < 0) {
                append(body.substring(from))
                break
            }
            append(body.substring(from, at))
            withLink(LinkAnnotation.Url("https://$TELEGRAM_API_HOST", linkStyles)) {
                append(TELEGRAM_API_HOST)
            }
            from = at + TELEGRAM_API_HOST.length
        }
    }
}

@Composable
private fun WelcomeStep() {
    StepHeading(
        title = stringResource(R.string.telegram_setup_wizard_store_your_records_in_telegram),
        body = stringResource(R.string.wizard_welcome_body)
    )
}

@Composable
private fun RequirementsStep() {
    StepHeading(
        title = stringResource(R.string.telegram_setup_wizard_what_you_need),
        body = stringResource(R.string.wizard_requirements_body)
    )
    Spacer(Modifier.height(16.dp))
    ClinicalNoteCard(
        stringResource(R.string.wizard_medical_note)
    )
}

@Composable
private fun CredentialsStep(state: SetupUiState, viewModel: TelegramSetupViewModel) {
    StepHeading(
        title = stringResource(R.string.telegram_setup_wizard_telegram_api_credentials),
        body = stringResource(R.string.wizard_credentials_body)
    )
    Spacer(Modifier.height(20.dp))
    OutlinedTextField(
        value = state.apiId,
        onValueChange = viewModel::onApiId,
        label = { Text(stringResource(R.string.telegram_setup_wizard_api_id)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.apiHash,
        onValueChange = viewModel::onApiHash,
        label = { Text(stringResource(R.string.telegram_setup_wizard_api_hash)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun LoginStep(state: SetupUiState, viewModel: TelegramSetupViewModel) {
    StepHeading(
        title = stringResource(R.string.telegram_setup_wizard_sign_in_to_telegram),
        body = stringResource(R.string.telegram_setup_wizard_ortho_tips_signs_in_the)
    )
    Spacer(Modifier.height(20.dp))

    when (val auth = state.authState) {
        is TelegramAuthState.WaitingForPhoneNumber -> {
            InternationalPhoneField(
                fullNumber = state.phoneNumber,
                onFullNumberChange = viewModel::onPhoneNumber,
                enabled = !state.busy
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = viewModel::submitPhoneNumber,
                enabled = state.phoneNumber.length > 5 && !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.telegram_setup_wizard_send_code)) }
            TextButton(onClick = viewModel::useQrLogin, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.telegram_setup_wizard_sign_in_by_qr_code))
            }
        }

        is TelegramAuthState.WaitingForCode -> {
            Text(
                text = stringResource(R.string.wizard_code_sent_via, auth.channel.name.lowercase().replace('_', ' ')),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.code,
                onValueChange = viewModel::onCode,
                label = { Text(stringResource(R.string.telegram_setup_wizard_login_code)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = viewModel::submitCode,
                enabled = state.code.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.telegram_setup_wizard_verify)) }
            TextButton(onClick = viewModel::resendCode, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.telegram_setup_wizard_resend_code))
            }
        }

        is TelegramAuthState.WaitingForPassword -> {
            auth.passwordHint?.let {
                Text(
                    text = stringResource(R.string.wizard_password_hint, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPassword,
                label = { Text(stringResource(R.string.telegram_setup_wizard_two_step_verification_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = viewModel::submitPassword,
                enabled = state.password.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.telegram_setup_wizard_sign_in)) }
        }

        is TelegramAuthState.WaitingForEmailAddress -> {
            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::onEmail,
                label = { Text(stringResource(R.string.telegram_setup_wizard_login_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = viewModel::submitEmail,
                enabled = state.email.contains('@') && !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.telegram_setup_wizard_send_email_code)) }
        }

        is TelegramAuthState.WaitingForEmailCode -> {
            Text(
                text = stringResource(R.string.wizard_code_sent_to, auth.emailPattern),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.code,
                onValueChange = viewModel::onCode,
                label = { Text(stringResource(R.string.telegram_setup_wizard_email_code)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = viewModel::submitEmailCode,
                enabled = state.code.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.telegram_setup_wizard_verify)) }
        }

        is TelegramAuthState.WaitingForQrScan -> {
            Text(
                text = stringResource(R.string.wizard_qr_instructions),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = auth.link,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(14.dp)
                )
            }
            TextButton(onClick = viewModel::usePhoneLogin, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.telegram_setup_wizard_use_a_phone_number_instead))
            }
        }

        TelegramAuthState.RegistrationRequired -> ClinicalNoteCard(
            stringResource(R.string.wizard_registration_required)
        )

        TelegramAuthState.Ready -> SuccessNote(stringResource(R.string.wizard_signed_in_as, state.accountName.ifBlank { stringResource(R.string.wizard_your_account) }))

        is TelegramAuthState.Failed -> ClinicalNoteCard(auth.message)

        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.telegram_setup_wizard_connecting_to_telegram), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PermissionsStep() {
    StepHeading(
        title = stringResource(R.string.telegram_setup_wizard_notifications),
        body = stringResource(R.string.wizard_permissions_body)
    )
}

@Composable
private fun DriveInitStep(state: SetupUiState) {
    StepHeading(
        title = stringResource(R.string.telegram_setup_wizard_create_the_drive),
        body = stringResource(R.string.wizard_drive_init_body)
    )
    if (state.chatId != null) {
        Spacer(Modifier.height(16.dp))
        SuccessNote(
            if (state.existingDriveFound) stringResource(R.string.wizard_drive_found)
            else stringResource(R.string.wizard_drive_created)
        )
    }
}

@Composable
private fun VerificationStep(state: SetupUiState) {
    StepHeading(
        title = stringResource(R.string.telegram_setup_wizard_storage_check),
        body = stringResource(R.string.wizard_verification_body)
    )
    if (state.verifiedBytes > 0) {
        Spacer(Modifier.height(16.dp))
        SuccessNote(stringResource(R.string.wizard_mb_free, state.verifiedBytes / (1024 * 1024)))
    }
}

@Composable
private fun ConnectionTestStep(state: SetupUiState) {
    StepHeading(
        title = stringResource(R.string.telegram_setup_wizard_connection_test),
        body = stringResource(R.string.wizard_connection_body)
    )
    // Language-independent: the step is done, rather than matching English text.
    if (SetupStep.CONNECTION_TEST in state.completedSteps && state.statusLine.isNotBlank()) {
        Spacer(Modifier.height(16.dp))
        SuccessNote(state.statusLine)
    }
}

@Composable
private fun ManifestStep(state: SetupUiState, viewModel: TelegramSetupViewModel) {
    StepHeading(
        title = stringResource(R.string.telegram_setup_wizard_reading_the_drive),
        body = stringResource(R.string.wizard_manifest_body)
    )
    if (state.manifestEntries > 0) {
        Spacer(Modifier.height(16.dp))
        SuccessNote(stringResource(R.string.wizard_manifest_count, state.manifestEntries))
    }
    if (state.driveLocked) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.passphrase_unlock_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (state.driveHasKeyBackup) {
                stringResource(R.string.passphrase_wizard_locked, state.lockedObjects)
            } else {
                stringResource(R.string.passphrase_status_locked_no_backup, state.lockedObjects)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.driveHasKeyBackup) {
            Spacer(Modifier.height(12.dp))
            PassphraseForm(
                confirm = false,
                buttonLabel = stringResource(R.string.passphrase_unlock_button),
                working = state.passphrase.working,
                outcome = state.passphrase.outcome,
                onSubmit = viewModel::unlockDrive
            )
        }
        TextButton(onClick = viewModel::skipUnlock, enabled = !state.passphrase.working) {
            Text(stringResource(R.string.passphrase_skip))
        }
    } else if (state.passphrase.outcome == com.example.viewmodel.PassphraseOutcome.UNLOCKED) {
        Spacer(Modifier.height(12.dp))
        SuccessNote(stringResource(R.string.passphrase_unlocked))
    }
}

@Composable
private fun InitialSyncStep(state: SetupUiState) {
    StepHeading(
        title = stringResource(R.string.telegram_setup_wizard_first_synchronisation),
        body = stringResource(R.string.wizard_initial_sync_body)
    )
    state.initialSyncReport?.let { report ->
        Spacer(Modifier.height(16.dp))
        SuccessNote(
            stringResource(R.string.wizard_queued_both, report.uploadsQueued, report.downloadsQueued)
        )
    }
}

@Composable
private fun CompleteStep(state: SetupUiState, viewModel: TelegramSetupViewModel) {
    StepHeading(
        title = stringResource(R.string.telegram_setup_wizard_telegram_drive_is_on),
        body = stringResource(R.string.wizard_complete_body)
    )
    state.initialSyncReport?.let {
        Spacer(Modifier.height(16.dp))
        SuccessNote(stringResource(R.string.wizard_queued_uploads, it.uploadsQueued))
    }
    if (!state.driveHasKeyBackup) {
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.passphrase_set_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.passphrase_set_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        PassphraseForm(
            confirm = true,
            buttonLabel = stringResource(R.string.passphrase_save),
            working = state.passphrase.working,
            outcome = state.passphrase.outcome,
            onSubmit = viewModel::savePassphrase
        )
    } else if (state.passphrase.outcome == com.example.viewmodel.PassphraseOutcome.SAVED) {
        Spacer(Modifier.height(16.dp))
        SuccessNote(stringResource(R.string.passphrase_saved))
    }
}

@Composable
private fun SuccessNote(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ClinicalNoteCard(text: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp)
        )
    }
}
