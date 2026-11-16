package com.example.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.ImageCategory
import com.example.data.local.entity.MediaType
import com.example.data.local.entity.PatientImageEntity
import com.example.data.repository.PatientRepository
import com.example.utils.ImageCompressor
import com.example.utils.MediaMetadata
import com.example.utils.OcrProcessor
import com.example.utils.PatientMediaStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MediaCaptureUiState(
    val isSaving: Boolean = false,
    val savedCount: Int = 0,
    val totalToSave: Int = 0
)

class MediaCaptureViewModel(
    private val repository: PatientRepository,
    private val patientId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediaCaptureUiState())
    val uiState: StateFlow<MediaCaptureUiState> = _uiState.asStateFlow()

    /** Saves one or more freshly captured/scanned images (camera photo or scanner pages). */
    fun saveCapturedImages(context: Context, uris: List<Uri>, category: ImageCategory) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = MediaCaptureUiState(isSaving = true, savedCount = 0, totalToSave = uris.size)
            uris.forEachIndexed { index, uri ->
                saveOne(context, uri, category)
                _uiState.value = _uiState.value.copy(savedCount = index + 1)
            }
            _uiState.value = _uiState.value.copy(isSaving = false)
        }
    }

    /** Saves a recorded or imported video: the file, a first-frame thumbnail, its date. */
    fun saveCapturedVideos(context: Context, uris: List<Uri>, category: ImageCategory) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = MediaCaptureUiState(isSaving = true, savedCount = 0, totalToSave = uris.size)
            uris.forEachIndexed { index, uri ->
                withContext(Dispatchers.IO) { saveVideo(context, uri, category) }
                _uiState.value = _uiState.value.copy(savedCount = index + 1)
            }
            _uiState.value = _uiState.value.copy(isSaving = false)
        }
    }

    /** Photos and videos can arrive together from the gallery picker. */
    fun saveCapturedMedia(context: Context, uris: List<Uri>, category: ImageCategory) {
        val (videos, images) = uris.partition { MediaMetadata.isVideo(context, it) }
        if (images.isNotEmpty()) saveCapturedImages(context, images, category)
        if (videos.isNotEmpty()) saveCapturedVideos(context, videos, category)
    }

    private suspend fun saveVideo(context: Context, sourceUri: Uri, category: ImageCategory) {
        val mediaId = java.util.UUID.randomUUID().toString()
        val extension = MediaMetadata.displayName(context, sourceUri)
            ?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() && it.length <= 4 }
            ?: "mp4"
        val videoFile = PatientMediaStorage.newVideoFile(context, patientId, mediaId, extension)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            videoFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return

        val thumbFile = PatientMediaStorage.newThumbnailFile(context, patientId, mediaId)
        MediaMetadata.videoThumbnail(context, Uri.fromFile(videoFile))?.let { frame ->
            thumbFile.outputStream().use { out ->
                frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            }
            frame.recycle()
        }

        repository.saveImage(
            PatientImageEntity(
                id = mediaId,
                patientId = patientId,
                category = category.name,
                filePath = videoFile.absolutePath,
                thumbnailPath = thumbFile.takeIf { it.exists() }?.absolutePath,
                mediaType = MediaType.VIDEO.name,
                durationMs = MediaMetadata.durationMs(context, sourceUri) ?: 0L,
                capturedAt = MediaMetadata.capturedAt(context, sourceUri)
            )
        )
    }

    private suspend fun saveOne(context: Context, sourceUri: Uri, category: ImageCategory) {
        withContext(Dispatchers.IO) {
            val imageId = java.util.UUID.randomUUID().toString()
            // Read the date before compressing: the compressed copy has no EXIF.
            val capturedAt = MediaMetadata.capturedAt(context, sourceUri)
            val imageFile = PatientMediaStorage.newImageFile(context, patientId, imageId)
            val thumbFile = PatientMediaStorage.newThumbnailFile(context, patientId, imageId)

            ImageCompressor.compressToFile(context.contentResolver, sourceUri, imageFile)
            ImageCompressor.writeThumbnail(imageFile, thumbFile)
            val ocrText = OcrProcessor.extractText(context, imageFile)

            repository.saveImage(
                PatientImageEntity(
                    id = imageId,
                    patientId = patientId,
                    category = category.name,
                    filePath = imageFile.absolutePath,
                    thumbnailPath = thumbFile.absolutePath,
                    ocrText = ocrText,
                    capturedAt = capturedAt
                )
            )
        }
    }

    companion object {
        fun provideFactory(repository: PatientRepository, patientId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MediaCaptureViewModel(repository, patientId) as T
                }
            }
    }
}
