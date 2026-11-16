package com.example.ui.screens.settings

import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.SwitchDefaults
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.example.BuildConfig
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.example.utils.AppLock
import com.example.utils.rememberProtectedAction
import com.example.R
import com.example.ui.components.LanguageSettingRow
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.WbIncandescent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.prefs.FontSizePreference
import com.example.data.prefs.ThemeMode
import com.example.utils.MediaActions
import com.example.viewmodel.DatabaseConfirmAction
import com.example.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToTelegramDrive: () -> Unit,
    onNavigateToGemini: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // Sensitive entries go through the phone's own lock when it is enabled.
    val lockAvailable = AppLock.isAvailable(LocalContext.current)
    val protect = rememberProtectedAction(settings.appLockEnabled && lockAvailable)
    val driveSubtitle = stringResource(R.string.settings_telegram_drive)
    val geminiSubtitle = stringResource(R.string.settings_gemini)
    val resetSubtitle = stringResource(R.string.settings_reinitialize_db)
    val deleteSubtitle = stringResource(R.string.settings_delete_patients)
    // The lock switch itself is guarded whichever way it is moved, so someone
    // holding an unlocked phone cannot quietly turn the protection off.
    val protectSwitch = rememberProtectedAction(lockAvailable)
    val lockSubtitle = stringResource(R.string.settings_app_lock)
    val isExportDialogOpen by viewModel.isExportDialogOpen.collectAsStateWithLifecycle()
    val isImportDialogOpen by viewModel.isImportDialogOpen.collectAsStateWithLifecycle()
    val backupJson by viewModel.backupJson.collectAsStateWithLifecycle()
    val importMessage by viewModel.importMessage.collectAsStateWithLifecycle()
    val pendingDatabaseAction by viewModel.pendingDatabaseAction.collectAsStateWithLifecycle()
    val databaseActionMessage by viewModel.databaseActionMessage.collectAsStateWithLifecycle()

    val context = LocalContext.current

    if (isExportDialogOpen && backupJson != null) {
        AlertDialog(
            onDismissRequest = { viewModel.closeExportDialog() },
            title = { Text(text = stringResource(R.string.settings_export_dialog_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.settings_export_dialog_body),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = backupJson ?: "",
                        onValueChange = {},
                        readOnly = true,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    MediaActions.shareClinicalTip(context, "Ortho Tips Backup", backupJson ?: "")
                    viewModel.closeExportDialog()
                }) { Text(stringResource(R.string.action_share_export)) }
            },
            dismissButton = {
                TextButton(onClick = { MediaActions.copyToClipboard(context, "Backup JSON", backupJson ?: "") }) {
                    Text(stringResource(R.string.action_copy))
                }
            }
        )
    }

    if (isImportDialogOpen) {
        var jsonInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { viewModel.closeImportDialog() },
            title = { Text(text = stringResource(R.string.settings_restore_dialog_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.settings_restore_dialog_body),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = jsonInput,
                        onValueChange = { jsonInput = it },
                        placeholder = { Text(stringResource(R.string.settings_paste_hint)) },
                        minLines = 4,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (importMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = importMessage.orEmpty().let { raw ->
                                when {
                                    raw.startsWith(SettingsViewModel.MSG_RESTORED) -> stringResource(
                                        R.string.settings_restore_success,
                                        raw.removePrefix(SettingsViewModel.MSG_RESTORED).toIntOrNull() ?: 0
                                    )
                                    raw.startsWith(SettingsViewModel.MSG_RESTORE_ERROR) -> stringResource(
                                        R.string.settings_restore_error,
                                        raw.removePrefix(SettingsViewModel.MSG_RESTORE_ERROR)
                                    )
                                    else -> raw
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.restoreBackup(jsonInput) }, enabled = jsonInput.isNotBlank()) {
                    Text(stringResource(R.string.action_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.closeImportDialog() }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }

    if (pendingDatabaseAction != null) {
        val action = pendingDatabaseAction
        val patients by viewModel.patientCount.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { viewModel.dismissDatabaseActionDialog() },
            title = {
                Text(
                    text = stringResource(
                        when (action) {
                            DatabaseConfirmAction.REINITIALIZE -> R.string.settings_confirm_reinitialize_title
                            else -> R.string.settings_confirm_delete_patients_title
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = when (action) {
                        DatabaseConfirmAction.REINITIALIZE -> stringResource(R.string.settings_confirm_reinitialize_message)
                        else -> stringResource(R.string.settings_confirm_delete_patients_message, patients)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDatabaseAction() },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(
                        stringResource(
                            when (action) {
                                DatabaseConfirmAction.REINITIALIZE -> R.string.settings_confirm_reinitialize_button
                                else -> R.string.settings_confirm_delete_patients_button
                            }
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDatabaseActionDialog() }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (databaseActionMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDatabaseActionMessage() },
            title = { Text(text = stringResource(R.string.settings_clinical_data_management), style = MaterialTheme.typography.titleMedium) },
            text = {
                val raw = databaseActionMessage ?: ""
                Text(
                    text = when {
                        raw.startsWith("PATIENTS_DELETED:") ->
                            stringResource(R.string.settings_patients_deleted, raw.substringAfter(':').toIntOrNull() ?: 0)
                        raw == SettingsViewModel.MSG_DB_REINITIALIZED -> stringResource(R.string.settings_db_reinitialized)
                        else -> raw
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.dismissDatabaseActionMessage() }) { Text(stringResource(R.string.action_ok)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = (-0.3).sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (settings.operativeMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth().testTag("operative_mode_card")
                ) {
                    Row(
                        modifier = Modifier
                            .toggleable(
                                value = settings.operativeMode,
                                role = Role.Switch,
                                onValueChange = { viewModel.setOperativeMode(it) }
                            )
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (settings.operativeMode) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.WbIncandescent,
                                    contentDescription = null,
                                    tint = if (settings.operativeMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_or_mode),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (settings.operativeMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_or_mode_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (settings.operativeMode) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.operativeMode,
                            onCheckedChange = null,
                            // Visible on the primary-coloured card when on.
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.onPrimary,
                                checkedThumbColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("operative_mode_switch")
                        )
                    }
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.language_title)) {
                    LanguageSettingRow()
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_display)) {
                    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_dynamic_color),
                        subtitle = stringResource(
                            if (supportsDynamicColor) R.string.settings_dynamic_color_subtitle
                            else R.string.settings_dynamic_color_unsupported
                        ),
                        checked = settings.dynamicColor && supportsDynamicColor,
                        enabled = supportsDynamicColor,
                        onCheckedChange = { viewModel.setDynamicColor(it) },
                        modifier = Modifier.testTag("dynamic_color_switch")
                    )
                    // Real segmented controls with translated names, replacing tiny
                    // "SYS / LIG / DAR" and "1x / 1.2x" tags that were hard to hit.
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_theme_appearance),
                        options = listOf(
                            stringResource(R.string.settings_theme_system),
                            stringResource(R.string.settings_theme_light),
                            stringResource(R.string.settings_theme_dark)
                        ),
                        selectedIndex = ThemeMode.entries.indexOf(settings.themeMode),
                        onSelect = { viewModel.setThemeMode(ThemeMode.entries[it]) }
                    )
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_font_size),
                        // Labels match the real multipliers (1.00 / 1.15 / 1.30).
                        options = listOf("100 %", "115 %", "130 %"),
                        selectedIndex = FontSizePreference.entries.indexOf(settings.fontSize),
                        onSelect = { viewModel.setFontSize(FontSizePreference.entries[it]) }
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_security)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_app_lock),
                        subtitle = stringResource(
                            if (lockAvailable) R.string.settings_app_lock_subtitle
                            else R.string.settings_app_lock_unavailable
                        ),
                        checked = settings.appLockEnabled,
                        enabled = lockAvailable,
                        onCheckedChange = { wanted ->
                            protectSwitch(lockSubtitle) { viewModel.setAppLock(wanted) }
                        }
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_smart_input)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_smart_suggestions),
                        subtitle = stringResource(R.string.settings_smart_suggestions_subtitle),
                        checked = settings.smartSuggestionsEnabled,
                        onCheckedChange = { viewModel.setSmartSuggestions(it) }
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_cloud)) {
                    SettingsActionRow(
                        icon = Icons.Default.Cloud,
                        title = stringResource(R.string.settings_telegram_drive),
                        subtitle = stringResource(R.string.settings_telegram_drive_subtitle),
                        onClick = { protect(driveSubtitle, onNavigateToTelegramDrive) }
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_ai)) {
                    SettingsActionRow(
                        icon = Icons.Default.AutoAwesome,
                        title = stringResource(R.string.settings_gemini),
                        subtitle = stringResource(R.string.settings_gemini_subtitle),
                        onClick = { protect(geminiSubtitle, onNavigateToGemini) }
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_data)) {
                    SettingsActionRow(
                        icon = Icons.Default.CloudUpload,
                        title = stringResource(R.string.settings_export_backup),
                        subtitle = stringResource(R.string.settings_export_backup_subtitle),
                        onClick = { viewModel.generateBackup() }
                    )
                    SettingsActionRow(
                        icon = Icons.Default.CloudDownload,
                        title = stringResource(R.string.settings_restore_backup),
                        subtitle = stringResource(R.string.settings_restore_backup_subtitle),
                        onClick = { viewModel.openImportDialog() }
                    )
                }
            }

            // Irreversible actions, grouped and shown in the error colour so they are
            // never mistaken for the backup rows above.
            item {
                SettingsSection(
                    title = stringResource(R.string.settings_section_danger),
                    danger = true
                ) {
                    SettingsActionRow(
                        destructive = true,
                        icon = Icons.Default.RestartAlt,
                        title = stringResource(R.string.settings_reinitialize_db),
                        subtitle = stringResource(R.string.settings_reinitialize_db_subtitle),
                        onClick = { protect(resetSubtitle) { viewModel.requestReinitializeDatabase() } }
                    )
                    SettingsActionRow(
                        destructive = true,
                        icon = Icons.Default.PersonRemove,
                        title = stringResource(R.string.settings_delete_patients),
                        subtitle = stringResource(R.string.settings_delete_patients_subtitle),
                        onClick = { protect(deleteSubtitle) { viewModel.requestDeleteAllPatients() } }
                    )
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.settings_section_disclaimer),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_disclaimer_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, danger: Boolean = false, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 6.dp, bottom = 8.dp)
                .semantics { heading() }
        )
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = if (danger) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)) else null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column { content() }
        }
    }
}

/** The whole row toggles (and reads as one switch for TalkBack), not just the thumb. */
@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsChoiceRow(title: String, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size)
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = accent)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}
