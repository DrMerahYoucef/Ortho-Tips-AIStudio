package com.example.ui.screens.patients

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.repository.PatientRepository
import java.io.File

/**
 * Plays a patient video with Android's own VideoView and its standard controls
 * (play/pause, seek bar, timings). Nothing is added to the app's size, and the
 * video never leaves the app's private storage.
 */
@Composable
fun VideoPlayerScreen(
    repository: PatientRepository,
    mediaId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var path by remember(mediaId) { mutableStateOf<String?>(null) }
    var missing by remember(mediaId) { mutableStateOf(false) }

    LaunchedEffect(mediaId) {
        val media = repository.getImageOrNull(mediaId)
        val file = media?.filePath?.let(::File)
        if (file != null && file.exists()) path = file.absolutePath else missing = true
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            path != null -> AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(path)
                        val controller = MediaController(ctx)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setOnPreparedListener { player ->
                            player.isLooping = false
                            start()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize().align(Alignment.Center)
            )
            missing -> Text(
                text = stringResource(R.string.video_missing),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
            else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        IconButton(
            onClick = onNavigateBack,
            // Below the status bar (clock, cutout), not on top of it.
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint = Color.White
            )
        }

        // Some clips (unusual codecs) are better handled by a real player app.
        path?.let { filePath ->
            IconButton(
                onClick = { openExternally(context, File(filePath)) },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)
            ) {
                Icon(
                    Icons.Default.OpenInNew,
                    contentDescription = stringResource(R.string.video_open_externally),
                    tint = Color.White
                )
            }
        }
    }
}

private fun openExternally(context: android.content.Context, file: File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/" + file.extension.ifBlank { "mp4" })
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}
