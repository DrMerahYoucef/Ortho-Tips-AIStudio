package com.example.ui.screens.extensions

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Transform
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.extensions.OrthoExtension
import com.example.data.extensions.PackagePreview
import com.example.data.extensions.PackageStatus
import com.example.data.extensions.otips.OTipsFormat
import com.example.viewmodel.ExtensionImportEvent
import com.example.viewmodel.ExtensionImportViewModel

/**
 * Where extensions are added: packages shipped with the app, files the user
 * picks, and a converter that turns an HTML program into an `.OTips` file.
 * Every package is described and its requested access listed before it is
 * installed (extension guide, section 4.3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionImportScreen(
    viewModel: ExtensionImportViewModel,
    onNavigateBack: () -> Unit,
    onOpenClassificationEditor: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    // The HTML file chosen for conversion, kept while the save dialog is open.
    var pendingHtml by rememberSaveable { mutableStateOf<String?>(null) }

    val pickPackage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::inspectDocument)
    }
    val saveConverted = rememberLauncherForActivityResult(CreateDocumentWithType()) { destination ->
        val html = pendingHtml?.let(android.net.Uri::parse)
        pendingHtml = null
        if (destination != null && html != null) viewModel.convertHtml(html, destination)
    }
    val pickHtml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingHtml = uri.toString()
            saveConverted.launch(CreateDocumentWithType.Input(OTipsFormat.fileName("extension"), OTipsFormat.MIME_TYPE))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is ExtensionImportEvent.Installed ->
                    if (event.previousVersion == null) context.getString(R.string.ext_msg_installed, event.manifest.label(context))
                    else context.getString(R.string.ext_msg_updated, event.manifest.label(context), event.manifest.version)
                is ExtensionImportEvent.Converted -> context.getString(R.string.ext_msg_converted, event.manifest.label(context))
                is ExtensionImportEvent.Failed -> context.getString(event.error.messageRes())
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ext_import_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("extension_import_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.ext_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ced_open_editor)) },
                                leadingIcon = { Icon(Icons.Outlined.EditNote, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenClassificationEditor()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ext_convert_action)) },
                                leadingIcon = { Icon(Icons.Outlined.Transform, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    pickHtml.launch(arrayOf("*/*"))
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("extension_import_list"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.updates.isNotEmpty()) {
                        item { SectionTitle(stringResource(R.string.ext_section_updates)) }
                        items(state.updates, key = { "u_" + it.manifest.id }) { preview ->
                            PackageRow(preview, enabled = !state.busy, onClick = { viewModel.reviewBundled(preview) })
                        }
                    }

                    item { SectionTitle(stringResource(R.string.ext_section_bundled)) }
                    if (state.bundled.isEmpty()) {
                        item { EmptyLine(stringResource(R.string.ext_section_bundled_empty)) }
                    }
                    items(state.bundled, key = { "b_" + it.manifest.id }) { preview ->
                        PackageRow(preview, enabled = !state.busy, onClick = { viewModel.reviewBundled(preview) })
                    }

                    item { SectionTitle(stringResource(R.string.ext_section_external)) }
                    item {
                        ExternalFileCard(
                            enabled = !state.busy,
                            onPick = { pickPackage.launch(arrayOf("*/*")) }
                        )
                    }

                    item { SectionTitle(stringResource(R.string.ext_section_installed)) }
                    if (state.installed.isEmpty()) {
                        item { EmptyLine(stringResource(R.string.ext_section_installed_empty)) }
                    }
                    items(state.installed, key = { "i_" + it.id }) { extension -> InstalledRow(extension) }
                }
            }
            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    state.review?.let { preview ->
        ReviewDialog(
            preview = preview,
            busy = state.busy,
            onConfirm = viewModel::confirmInstall,
            onDismiss = viewModel::dismissReview
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 16.dp, bottom = 2.dp)
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 2.dp)
    )
}

@Composable
private fun ExternalFileCard(enabled: Boolean, onPick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.ext_import_pick_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.ext_import_pick_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            // Any type: files shared from messaging apps usually arrive as
            // application/octet-stream; the package itself is checked anyway.
            FilledTonalButton(onClick = onPick, enabled = enabled, modifier = Modifier.testTag("extension_import_pick")) {
                Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.ext_import_pick_button))
            }
        }
    }
}

@Composable
private fun PackageRow(preview: PackagePreview, enabled: Boolean, onClick: () -> Unit) {
    val manifest = preview.manifest
    Card(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bundled_extension_${manifest.id}")
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ExtensionIconTile(icon = manifest.icon)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = manifest.label(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(extensionKindLabelRes(manifest.kind)) + " · v" + manifest.version,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(2.dp))
                StatusText(preview)
            }
            if (preview.status == PackageStatus.INSTALLED) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = stringResource(R.string.ext_status_installed),
                    tint = MaterialTheme.colorScheme.primary
                )
            } else if (preview.installable) {
                FilledTonalButton(onClick = onClick, enabled = enabled) { Text(stringResource(actionLabel(preview.status))) }
            }
        }
    }
}

@Composable
private fun StatusText(preview: PackagePreview) {
    val blocker = preview.blocker
    val (text, isWarning) = when {
        blocker != null -> stringResource(blocker.messageRes()) to true
        else -> statusLabel(preview) to (preview.status == PackageStatus.OLDER)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun statusLabel(preview: PackagePreview): String = when (preview.status) {
    PackageStatus.NEW -> stringResource(R.string.ext_status_new)
    PackageStatus.INSTALLED -> stringResource(R.string.ext_status_installed)
    PackageStatus.CHANGED -> stringResource(R.string.ext_status_changed, preview.manifest.version)
    PackageStatus.UPDATE -> stringResource(R.string.ext_status_update, preview.installedVersion.orEmpty(), preview.manifest.version)
    PackageStatus.OLDER -> stringResource(R.string.ext_status_older, preview.installedVersion.orEmpty())
}

private fun actionLabel(status: PackageStatus): Int = when (status) {
    PackageStatus.NEW -> R.string.ext_action_install
    PackageStatus.UPDATE -> R.string.ext_action_update
    PackageStatus.INSTALLED, PackageStatus.CHANGED -> R.string.ext_action_reinstall
    PackageStatus.OLDER -> R.string.ext_action_downgrade
}

@Composable
private fun InstalledRow(extension: OrthoExtension) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ExtensionIconTile(icon = extension.icon, size = 36.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = extension.manifest.label(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(extensionKindLabelRes(extension.kind)) + " · v" + extension.version +
                    " · " + stringResource(extension.source.labelRes()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun ReviewDialog(
    preview: PackagePreview,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val manifest = preview.manifest
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { ExtensionIconTile(icon = manifest.icon) },
        title = { Text(manifest.label(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(extensionKindLabelRes(manifest.kind)) + " · " +
                        stringResource(R.string.ext_review_version, manifest.version),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                if (manifest.author.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.ext_review_by, manifest.author),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                val summary = manifest.summary()
                if (summary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(modifier = Modifier.height(8.dp))
                StatusText(preview)
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    text = stringResource(R.string.ext_review_requests),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                PermissionList(manifest.permissions)
            }
        },
        confirmButton = {
            if (preview.installable) {
                TextButton(onClick = onConfirm, enabled = !busy, modifier = Modifier.testTag("extension_review_confirm")) {
                    Text(stringResource(actionLabel(preview.status)))
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.ext_cancel)) }
        }
    )
}
