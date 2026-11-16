package com.example.ui.components

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.ContentLocale
import com.example.util.AppLocale

/**
 * Language selector.
 *
 * Each option is written in its own language — a French speaker looking for
 * their language scans for "Français", not for whatever "French" happens to be
 * called in the language currently on screen. "System default" is the exception
 * and is translated, since it describes a behaviour rather than naming a
 * language.
 *
 * Changing the language recreates the activity. That is the honest way to do it:
 * Compose caches resolved resources, so anything short of a recreate leaves half
 * the screen in the previous language.
 */
@Composable
fun LanguageSettingRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val current = remember { AppLocale.currentOption(context) }

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { menuOpen = true }
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ) {
                Icon(
                    imageVector = Icons.Default.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(7.dp)
                        .size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.language_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = AppLocale.displayName(current, context),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = stringResource(R.string.language_title)
            )
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            AppLocale.Option.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(AppLocale.displayName(option, context)) },
                    onClick = {
                        menuOpen = false
                        if (option != current) {
                            AppLocale.setOption(context, option)
                            // The reference library switches language with the
                            // UI, so the flag has to move before the recreate.
                            ContentLocale.refresh(context)
                            // Day names and labels on the home-screen widget follow too.
                            com.example.widget.OrthoWidgetProvider.refreshAll(context)
                            // Compose caches resolved resources, so only a full
                            // recreate re-reads every string in the new language.
                            (context as? Activity)?.recreate()
                        }
                    },
                    leadingIcon = {
                        if (option == current) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
            }
        }
    }
}
