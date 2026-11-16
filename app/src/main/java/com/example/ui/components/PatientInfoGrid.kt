package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.util.ContactActions
import com.example.utils.MediaActions
import com.example.R
import androidx.compose.ui.res.stringResource

/** What a field can be acted on as, which decides the sheet it opens. */
enum class InfoAction { NONE, PHONE, EMAIL, ADDRESS }

data class InfoItem(
    val label: String,
    val value: String,
    val action: InfoAction = InfoAction.NONE,
    /**
     * Prose (treatment plan, imaging findings…) is shown under its label across the
     * full width instead of in the right-hand column.
     */
    val fullWidth: Boolean = false
)

/**
 * One section of the patient record, laid out like a paper medical file:
 * a titled panel with label | value rows separated by thin rules.
 *
 * Labels sit in a fixed left column so the eye scans values straight down;
 * long text drops below its label so it keeps the whole width. Empty fields are
 * hidden. Contact values stay actionable (tap), and any value can be copied with
 * a long press.
 */
@Composable
fun PatientInfoGrid(
    title: String,
    items: List<InfoItem>,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    val visible = items.filter { it.value.isNotBlank() && it.value != "—" }
    if (visible.isEmpty()) return

    var sheetFor by remember { mutableStateOf<InfoItem?>(null) }
    sheetFor?.let { item ->
        ContactActionSheet(item = item, onDismiss = { sheetFor = null })
    }

    OutlinedCard(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        // Section header band
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        visible.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
            }
            InfoRow(item = item, onAction = { sheetFor = item })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InfoRow(
    item: InfoItem,
    onAction: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val actionable = item.action != InfoAction.NONE

    val rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .combinedClickable(
            onClick = { if (actionable) onAction() },
            // Long-press copies any value; insurance numbers and diagnoses get
            // retyped into other systems constantly.
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                MediaActions.copyToClipboard(context, item.label, item.value)
            }
        )
        .padding(horizontal = 14.dp, vertical = 10.dp)

    val labelText: @Composable (Modifier) -> Unit = { m ->
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = m
        )
    }
    val valueText: @Composable (Modifier) -> Unit = { m ->
        Text(
            text = item.value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (actionable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = if (item.fullWidth) 12 else 4,
            overflow = TextOverflow.Ellipsis,
            modifier = m
        )
    }

    if (item.fullWidth) {
        Column(modifier = rowModifier) {
            labelText(Modifier)
            Spacer(modifier = Modifier.size(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                valueText(Modifier.weight(1f))
                if (actionable) ActionIcon(item)
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = rowModifier) {
            labelText(Modifier.fillMaxWidth(0.38f))
            Spacer(modifier = Modifier.width(12.dp))
            valueText(Modifier.weight(1f))
            if (actionable) ActionIcon(item)
        }
    }
}

@Composable
private fun ActionIcon(item: InfoItem) {
    Spacer(modifier = Modifier.width(8.dp))
    Icon(
        imageVector = when (item.action) {
            InfoAction.PHONE -> Icons.Default.Call
            InfoAction.EMAIL -> Icons.Default.Email
            InfoAction.ADDRESS -> Icons.Default.Map
            InfoAction.NONE -> Icons.Default.MoreHoriz
        },
        contentDescription = item.label,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp)
    )
}

/**
 * Actions for one contact field.
 *
 * Messaging apps are listed only when installed. Anything not named — Signal,
 * Messenger, a regional messenger — is reachable through "Other apps", which
 * opens a chooser over `smsto:` rather than requiring every app to be known here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactActionSheet(item: InfoItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    val messagingApps = remember {
        if (item.action == InfoAction.PHONE) {
            ContactActions.availableMessagingApps(context)
        } else {
            emptyList()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(text = item.value, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.size(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

            when (item.action) {
                InfoAction.PHONE -> {
                    SheetAction(Icons.Default.Call, stringResource(R.string.contact_action_call)) {
                        ContactActions.dial(context, item.value); onDismiss()
                    }
                    SheetAction(Icons.AutoMirrored.Filled.Message, stringResource(R.string.contact_action_sms)) {
                        ContactActions.sms(context, item.value); onDismiss()
                    }
                    messagingApps.forEach { app ->
                        SheetAction(Icons.AutoMirrored.Filled.Chat, app.label) {
                            ContactActions.openMessagingApp(context, app, item.value)
                            onDismiss()
                        }
                    }
                    SheetAction(Icons.Default.MoreHoriz, stringResource(R.string.contact_action_other_apps)) {
                        ContactActions.shareToOtherApps(context, item.value); onDismiss()
                    }
                }

                InfoAction.EMAIL -> SheetAction(Icons.Default.Email, stringResource(R.string.contact_action_email)) {
                    ContactActions.email(context, item.value); onDismiss()
                }

                InfoAction.ADDRESS -> SheetAction(Icons.Default.Map, stringResource(R.string.contact_action_maps)) {
                    ContactActions.openMap(context, item.value); onDismiss()
                }

                InfoAction.NONE -> Unit
            }

            SheetAction(Icons.Default.ContentCopy, stringResource(R.string.action_copy)) {
                MediaActions.copyToClipboard(context, item.label, item.value)
                onDismiss()
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(18.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
