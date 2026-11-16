package com.example.utils

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Wraps Play Services' ML Kit Document Scanner: automatic document/rectangle detection,
 * four-corner quad adjustment (the scanner's own edit step), and perspective correction —
 * the user gets a native full-screen scan UI and this returns the corrected page images.
 *
 * [onScanned] receives one [Uri] per corrected page, already written to cache by the scanner.
 */
@Composable
fun rememberDocumentScannerLauncher(onScanned: (List<Uri>) -> Unit): () -> Unit {
    val context = LocalContext.current

    val options = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val scannerClient = remember(options) { GmsDocumentScanning.getClient(options) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val uris = scanResult?.pages?.map { it.imageUri } ?: emptyList()
            if (uris.isNotEmpty()) onScanned(uris)
        }
    }

    return {
        val activity = context as? Activity
        if (activity != null) {
            scannerClient.getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    launcher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
        }
    }
}
