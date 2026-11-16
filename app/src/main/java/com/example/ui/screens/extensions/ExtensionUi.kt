package com.example.ui.screens.extensions

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.extensions.ExtensionError
import com.example.data.extensions.ExtensionKinds
import com.example.data.extensions.ExtensionManifest
import com.example.data.extensions.ExtensionPermission
import com.example.data.extensions.ExtensionSource

/** The extension's name in the app language (manifest `name.<lang>`), else its default name. */
@Composable
internal fun ExtensionManifest.label(): String = localizedName(currentLanguage())

@Composable
internal fun ExtensionManifest.summary(): String = localizedDescription(currentLanguage())

/** Same as [label], for messages built outside composition (toasts). */
internal fun ExtensionManifest.label(context: android.content.Context): String =
    localizedName(context.resources.configuration.locales[0]?.language ?: DEFAULT_LANGUAGE)

@Composable
private fun currentLanguage(): String = LocalConfiguration.current.locales[0]?.language ?: DEFAULT_LANGUAGE

private const val DEFAULT_LANGUAGE = "fr"

@StringRes
internal fun ExtensionPermission.labelRes(): Int = when (this) {
    ExtensionPermission.INTERNET -> R.string.ext_perm_internet
    ExtensionPermission.DATABASE -> R.string.ext_perm_database
    ExtensionPermission.STORAGE -> R.string.ext_perm_storage
    ExtensionPermission.ASSETS -> R.string.ext_perm_assets
    ExtensionPermission.IMPORT_EXPORT -> R.string.ext_perm_import_export
    ExtensionPermission.PATIENTS -> R.string.ext_perm_patients
    ExtensionPermission.GEMINI -> R.string.ext_perm_gemini
    ExtensionPermission.SMS -> R.string.ext_perm_sms
    ExtensionPermission.NOTIFICATIONS -> R.string.ext_perm_notifications
    ExtensionPermission.BACKGROUND -> R.string.ext_perm_background
}

@StringRes
internal fun ExtensionError.messageRes(): Int = when (this) {
    ExtensionError.UNREADABLE -> R.string.ext_error_unreadable
    ExtensionError.NOT_A_PACKAGE -> R.string.ext_error_not_a_package
    ExtensionError.CORRUPTED -> R.string.ext_error_corrupted
    ExtensionError.UNSUPPORTED_FORMAT -> R.string.ext_error_unsupported_format
    ExtensionError.OUTDATED_PACKAGE -> R.string.ext_error_outdated_package
    ExtensionError.UNSUPPORTED_KIND -> R.string.ext_error_unsupported_kind
    ExtensionError.REQUIRES_NEWER_APP -> R.string.ext_error_requires_newer_app
    ExtensionError.INVALID_MANIFEST -> R.string.ext_error_invalid_manifest
    ExtensionError.INVALID_CONTENT -> R.string.ext_error_invalid_content
    ExtensionError.NOT_HTML -> R.string.ext_error_not_html
    ExtensionError.TOO_LARGE -> R.string.ext_error_too_large
    ExtensionError.INSTALL_FAILED -> R.string.ext_error_install_failed
    ExtensionError.STORAGE_FAILED -> R.string.ext_error_storage_failed
}

@StringRes
internal fun ExtensionSource.labelRes(): Int = when (this) {
    ExtensionSource.BUNDLED -> R.string.ext_source_bundled
    ExtensionSource.EXTERNAL -> R.string.ext_source_external
    ExtensionSource.LEGACY -> R.string.ext_source_legacy
}

@StringRes
internal fun extensionKindLabelRes(kind: String): Int = when (kind) {
    ExtensionKinds.HTML -> R.string.ext_kind_html
    ExtensionKinds.OPERATIVE_GUIDE -> R.string.ext_kind_operative_guide
    ExtensionKinds.CLASSIFICATIONS -> R.string.ext_kind_classifications
    else -> R.string.ext_kind_other
}

/** The extension's emoji on a tinted tile, as in the lists. */
@Composable
internal fun ExtensionIconTile(icon: String, modifier: Modifier = Modifier, size: Dp = 46.dp) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = icon, fontSize = (size.value * 0.48f).sp, maxLines = 1)
        }
    }
}

/** What an extension may use, one line each; reserved permissions are marked as such. */
@Composable
internal fun PermissionList(permissions: Set<ExtensionPermission>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (permissions.isEmpty()) {
            Text(
                text = stringResource(R.string.ext_review_no_permissions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        permissions.sortedBy { it.ordinal }.forEach { permission ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (permission.implemented) Icons.Outlined.CheckCircle else Icons.Outlined.HourglassEmpty,
                    contentDescription = null,
                    tint = if (permission.implemented) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(permission.labelRes()), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
