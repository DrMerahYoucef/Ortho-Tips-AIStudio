package com.example.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ui.theme.OrthoTipsTheme

// Previews of the shared building blocks, in light and dark, so a style change
// can be checked in Android Studio without running the app.

@Preview(name = "Form fields – light", showBackground = true)
@Preview(name = "Form fields – dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun FormFieldsPreview() {
    OrthoTipsTheme {
        Surface {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                FormSectionLabel("Identité")
                FormTextField(value = "Jeanne Martin", onValueChange = {}, label = "Nom complet")
                FormTextField(
                    value = "",
                    onValueChange = {},
                    label = "Identifiant patient",
                    isError = true,
                    errorText = "L’identifiant est obligatoire"
                )
                DateField(label = "Date de naissance", dateMillis = 0L, onDateSelected = {})
            }
        }
    }
}

@Preview(name = "Delete confirmation", showBackground = true)
@Composable
private fun ConfirmDeleteDialogPreview() {
    OrthoTipsTheme {
        ConfirmDeleteDialog(
            title = "Supprimer cette chirurgie ?",
            message = "Le dossier opératoire sera supprimé. Cette action est irréversible.",
            onConfirm = {},
            onDismiss = {}
        )
    }
}
