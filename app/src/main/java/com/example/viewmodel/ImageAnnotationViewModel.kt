package com.example.viewmodel

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.repository.PatientRepository
import com.example.utils.AnnotationSerializer
import com.example.utils.AnnotationShape
import com.example.utils.ImageCompressor
import com.example.utils.OcrProcessor
import com.example.utils.PerspectiveCrop
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AnnotationTool { NONE, ARROW, TEXT, RECTANGLE, FREEHAND }

data class ImageAnnotationUiState(
    val filePath: String? = null,
    val shapes: List<AnnotationShape> = emptyList(),
    val activeTool: AnnotationTool = AnnotationTool.NONE,
    val notes: String = "",
    val ocrText: String = "",
    val isRunningOcr: Boolean = false,
    /** Result of scanning just a quad-selected region — kept separate from [ocrText]
     *  (the whole-image scan) since it isn't persisted unless the user asks to keep it. */
    val quadOcrText: String? = null,
    val isRunningQuadOcr: Boolean = false,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    /** True once a pre-edit backup exists, so the screen can offer "Restore original". */
    val hasBackup: Boolean = false,
    /** Bumped whenever the underlying file is overwritten (crop/rotate) so AsyncImage re-reads it. */
    val fileRevision: Int = 0
)

class ImageAnnotationViewModel(
    private val repository: PatientRepository,
    private val imageId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImageAnnotationUiState())
    val uiState: StateFlow<ImageAnnotationUiState> = _uiState.asStateFlow()

    init {
        loadImage()
    }

    private fun loadImage() {
        viewModelScope.launch {
            val image = repository.getImageOrNull(imageId)
            if (image != null) {
                // An offloaded image has a valid record and no bytes. The screen
                // decodes the path to size its canvas and spins forever on a
                // missing file, so fetch it back before reporting ready.
                val resolved = repository.resolveImageFile(image)
                _uiState.value = _uiState.value.copy(
                    filePath = resolved?.absolutePath ?: image.filePath,
                    shapes = AnnotationSerializer.deserialize(image.annotationsJson),
                    notes = image.notes,
                    ocrText = image.ocrText,
                    hasBackup = backupFile(File(image.filePath)).exists(),
                    isLoading = false
                )
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun setActiveTool(tool: AnnotationTool) {
        _uiState.value = _uiState.value.copy(activeTool = if (_uiState.value.activeTool == tool) AnnotationTool.NONE else tool)
    }

    fun addShape(shape: AnnotationShape) {
        _uiState.value = _uiState.value.copy(shapes = _uiState.value.shapes + shape)
    }

    fun removeShape(shapeId: String) {
        _uiState.value = _uiState.value.copy(shapes = _uiState.value.shapes.filterNot { it.id == shapeId })
    }

    fun updateNotes(notes: String) {
        _uiState.value = _uiState.value.copy(notes = notes)
    }

    fun saveAnnotations() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val image = repository.getImageOrNull(imageId) ?: return@launch
            repository.saveImage(
                image.copy(
                    annotationsJson = AnnotationSerializer.serialize(_uiState.value.shapes),
                    notes = _uiState.value.notes,
                    ocrText = _uiState.value.ocrText
                )
            )
            _uiState.value = _uiState.value.copy(isSaving = false)
        }
    }

    /** Re-runs ML Kit Text Recognition against the whole current file and persists the result. */
    fun rerunOcr(context: Context) {
        viewModelScope.launch {
            val path = _uiState.value.filePath ?: return@launch
            _uiState.value = _uiState.value.copy(isRunningOcr = true)
            val ocrSource = repository.getImageOrNull(imageId)?.let { repository.resolveImageFile(it) }
                ?: File(path)
            val text = OcrProcessor.extractText(context, ocrSource)
            _uiState.value = _uiState.value.copy(ocrText = text, isRunningOcr = false)
            val image = repository.getImageOrNull(imageId) ?: return@launch
            repository.saveImage(image.copy(ocrText = text))
        }
    }

    /**
     * Scans just the quad-selected region for text, without touching the saved file or its
     * canonical [ImageAnnotationUiState.ocrText]. [corners] are in source-bitmap pixel space,
     * clockwise from top-left (see [PerspectiveCrop.warp]).
     */
    fun scanQuadText(corners: List<PointF>) {
        viewModelScope.launch {
            val path = _uiState.value.filePath ?: return@launch
            _uiState.value = _uiState.value.copy(isRunningQuadOcr = true, quadOcrText = null)
            val quadSource = repository.getImageOrNull(imageId)?.let { repository.resolveImageFile(it) }
            val text = withContext(Dispatchers.IO) {
                val source = BitmapFactory.decodeFile(quadSource?.absolutePath ?: path)
                    ?: return@withContext ""
                val warped = runCatching { PerspectiveCrop.warp(source, corners) }.getOrNull()
                source.recycle()
                val result = warped?.let { OcrProcessor.extractTextFromBitmap(it) } ?: ""
                warped?.recycle()
                result
            }
            _uiState.value = _uiState.value.copy(quadOcrText = text, isRunningQuadOcr = false)
        }
    }

    /** Saves the last quad-scanned text into the image's persisted OCR text (appending if it
     *  already has some), so a region scan can be kept without needing a full-image re-scan. */
    fun keepQuadOcrText() {
        val extracted = _uiState.value.quadOcrText ?: return
        viewModelScope.launch {
            val image = repository.getImageOrNull(imageId) ?: return@launch
            val merged = listOf(image.ocrText, extracted).filter { it.isNotBlank() }.joinToString("\n\n")
            repository.saveImage(image.copy(ocrText = merged))
            _uiState.value = _uiState.value.copy(ocrText = merged, quadOcrText = null)
        }
    }

    fun dismissQuadOcr() {
        _uiState.value = _uiState.value.copy(quadOcrText = null)
    }

    /** Rotates the underlying JPEG 90° clockwise and refreshes the thumbnail. */
    fun rotate90() {
        viewModelScope.launch {
            val image = repository.getImageOrNull(imageId) ?: return@launch
            // Resolve first: with a cloud backend the bytes may live only in the
            // drive, and rotating a zero-byte placeholder would destroy the image.
            val file = repository.resolveImageFile(image) ?: return@launch
            withContext(Dispatchers.IO) {
                backupOriginalIfNeeded(file)
                ImageCompressor.rotateFile(file, 90f)
                image.thumbnailPath?.let { ImageCompressor.writeThumbnail(file, File(it)) }
            }
            // Re-saving the unchanged row is what re-queues the altered pixels for
            // upload; the hash differs, so the drive takes a new revision.
            repository.saveImage(image)
            _uiState.value = _uiState.value.copy(fileRevision = _uiState.value.fileRevision + 1, hasBackup = true)
        }
    }

    /**
     * Perspective-crops to the quad defined by [corners] (source-bitmap pixel space,
     * clockwise from top-left — see [PerspectiveCrop.warp]), overwrites the file, refreshes
     * the thumbnail, and re-runs whole-image OCR since the pixel content changed. Clears
     * prior overlay shapes, since their coordinates were relative to the pre-crop frame.
     * A backup of the pre-crop file is kept so [restoreOriginal] can undo this.
     */
    fun cropQuad(context: Context, corners: List<PointF>) {
        viewModelScope.launch {
            val image = repository.getImageOrNull(imageId) ?: return@launch
            val cropTarget = repository.resolveImageFile(image) ?: return@launch
            val cropSucceeded = withContext(Dispatchers.IO) {
                val file = cropTarget
                backupOriginalIfNeeded(file)
                val source = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext false
                val warped = runCatching { PerspectiveCrop.warp(source, corners) }.getOrNull()
                source.recycle()
                if (warped == null) return@withContext false
                ImageCompressor.saveJpeg(warped, file)
                warped.recycle()
                image.thumbnailPath?.let { ImageCompressor.writeThumbnail(file, File(it)) }
                true
            }
            if (cropSucceeded) {
                _uiState.value = _uiState.value.copy(
                    shapes = emptyList(),
                    fileRevision = _uiState.value.fileRevision + 1,
                    hasBackup = true
                )
                rerunOcr(context)
            }
        }
    }

    /** Restores the file as it was before the first crop/rotate in this session. */
    fun restoreOriginal(context: Context) {
        viewModelScope.launch {
            val image = repository.getImageOrNull(imageId) ?: return@launch
            val restored = withContext(Dispatchers.IO) {
                val file = File(image.filePath)
                val backup = backupFile(file)
                if (!backup.exists()) return@withContext false
                backup.copyTo(file, overwrite = true)
                image.thumbnailPath?.let { ImageCompressor.writeThumbnail(file, File(it)) }
                true
            }
            if (restored) {
                _uiState.value = _uiState.value.copy(shapes = emptyList(), fileRevision = _uiState.value.fileRevision + 1)
                rerunOcr(context)
            }
        }
    }

    private fun backupFile(file: File): File = File(file.parentFile, "${file.nameWithoutExtension}.original.jpg")

    /** Only ever keeps the *first* backup — a second crop shouldn't overwrite the true original. */
    private fun backupOriginalIfNeeded(file: File) {
        val backup = backupFile(file)
        if (!backup.exists() && file.exists()) {
            file.copyTo(backup, overwrite = false)
        }
    }

    companion object {
        fun provideFactory(repository: PatientRepository, imageId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ImageAnnotationViewModel(repository, imageId) as T
                }
            }
    }
}
