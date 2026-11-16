package com.example.ui.screens.extensions

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.extensions.ExtensionKinds
import com.example.data.extensions.OrthoExtension
import com.example.data.extensions.otips.OTipsFormat
import java.text.DateFormat
import java.util.Date
import com.example.viewmodel.ExtensionEvent
import com.example.viewmodel.ExtensionsViewModel

/**
 * The Extensions area: every installed extension as a tile — its icon and name —
 * in a grid. A tap opens it; a long press shows its options (details, export,
 * clear its data, delete). The last tile adds an extension (the import screen).
 */
@Composable
fun ExtensionsGrid(
    viewModel: ExtensionsViewModel,
    onOpenExtension: (OrthoExtension) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val extensions by viewModel.extensions.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<OrthoExtension?>(null) }
    var pendingClear by remember { mutableStateOf<OrthoExtension?>(null) }
    var pendingExport by remember { mutableStateOf<OrthoExtension?>(null) }
    var details by remember { mutableStateOf<OrthoExtension?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(CreateDocumentWithType()) { uri ->
        val extension = pendingExport
        pendingExport = null
        if (uri != null && extension != null) viewModel.export(extension, uri)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is ExtensionEvent.Deleted -> context.getString(R.string.ext_msg_deleted, event.manifest.label(context))
                is ExtensionEvent.DataCleared -> context.getString(R.string.ext_msg_data_cleared, event.manifest.label(context))
                is ExtensionEvent.Exported ->
                    context.getString(if (event.ok) R.string.ext_msg_exported else R.string.ext_msg_export_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            loading && extensions.isEmpty() ->
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

            extensions.isEmpty() ->
                EmptyExtensions(onImport = onImport, modifier = Modifier.align(Alignment.Center))

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("extensions_grid")
            ) {
                items(extensions, key = { it.id }) { extension ->
                    ExtensionTile(
                        extension = extension,
                        onOpen = { onOpenExtension(extension) },
                        onDetails = { details = extension },
                        onExport = if (extension.exportable) {
                            {
                                pendingExport = extension
                                exportLauncher.launch(
                                    CreateDocumentWithType.Input(viewModel.exportFileName(extension), OTipsFormat.MIME_TYPE)
                                )
                            }
                        } else {
                            null
                        },
                        onClearData = { pendingClear = extension },
                        onDelete = { pendingDelete = extension }
                    )
                }
                item(key = "add") { AddTile(onClick = onImport) }
                item(key = "note", span = { GridItemSpan(maxLineSpan) }) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Text(
                            text = stringResource(R.string.ext_grid_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        PrivacyNote(modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }

    pendingDelete?.let { extension ->
        ConfirmDialog(
            title = stringResource(R.string.ext_delete_title, extension.manifest.label()),
            // Deleting the guide also deletes the data imported into it.
            body = stringResource(
                if (extension.kind == ExtensionKinds.OPERATIVE_GUIDE) R.string.ext_delete_body_operative_guide
                else R.string.ext_delete_body
            ),
            confirm = stringResource(R.string.ext_delete),
            onConfirm = {
                pendingDelete = null
                viewModel.delete(extension)
            },
            onDismiss = { pendingDelete = null }
        )
    }

    details?.let { extension ->
        DetailsDialog(extension = extension, onDismiss = { details = null })
    }

    pendingClear?.let { extension ->
        ConfirmDialog(
            title = stringResource(R.string.ext_clear_title, extension.manifest.label()),
            body = stringResource(R.string.ext_clear_body),
            confirm = stringResource(R.string.ext_clear),
            onConfirm = {
                pendingClear = null
                viewModel.clearData(extension)
            },
            onDismiss = { pendingClear = null }
        )
    }
}

/** One extension: icon and name. Tap opens it; a long press opens its options. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExtensionTile(
    extension: OrthoExtension,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onExport: (() -> Unit)?,
    onClearData: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                    onLongClickLabel = stringResource(R.string.ext_grid_options)
                )
                .padding(horizontal = 4.dp, vertical = 10.dp)
                .testTag("extension_tile_${extension.id}")
        ) {
            ExtensionIconTile(icon = extension.icon, size = 64.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = extension.manifest.label(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ext_action_open)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                onClick = { menuOpen = false; onOpen() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ext_action_details)) },
                leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                onClick = { menuOpen = false; onDetails() }
            )
            if (onExport != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ext_action_export)) },
                    leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                    onClick = { menuOpen = false; onExport() }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ext_action_clear_data)) },
                leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                onClick = { menuOpen = false; onClearData() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ext_action_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; onDelete() }
            )
        }
    }
}

/** The last tile: adds an extension (opens the import screen). */
@Composable
private fun AddTile(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp)
            .testTag("extensions_add_tile")
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.ext_grid_add),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EmptyExtensions(onImport: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(80.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.ext_empty_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.ext_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        FilledTonalButton(onClick = onImport, modifier = Modifier.testTag("extensions_import_empty")) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.ext_import_file))
        }
    }
}

@Composable
private fun PrivacyNote(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.ext_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/** Extension Details (extension guide, section 4.3): identity, origin and granted access. */
@Composable
private fun DetailsDialog(extension: OrthoExtension, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { ExtensionIconTile(icon = extension.icon) },
        title = { Text(extension.manifest.label()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val summary = extension.manifest.summary()
                if (summary.isNotBlank()) {
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                }
                DetailLine(stringResource(R.string.ext_details_kind), stringResource(extensionKindLabelRes(extension.kind)))
                DetailLine(stringResource(R.string.ext_details_version), extension.version)
                if (extension.author.isNotBlank()) DetailLine(stringResource(R.string.ext_details_author), extension.author)
                DetailLine(stringResource(R.string.ext_details_source), stringResource(extension.source.labelRes()))
                DetailLine(
                    stringResource(R.string.ext_details_size),
                    Formatter.formatShortFileSize(context, extension.sizeBytes)
                )
                DetailLine(
                    stringResource(R.string.ext_details_updated),
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(extension.updatedAt))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.ext_details_permissions),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                PermissionList(extension.granted)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ext_close)) }
        }
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(110.dp)
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ext_cancel)) }
        }
    )
}
