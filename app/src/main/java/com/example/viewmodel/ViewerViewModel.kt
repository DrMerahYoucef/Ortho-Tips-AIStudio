package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.ImageEntity
import com.example.data.repository.OrthoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ViewerUiState(
    val images: List<ImageEntity> = emptyList(),
    val startIndex: Int = 0,
    val isLoading: Boolean = true
) {
    val image: ImageEntity? get() = images.getOrNull(startIndex)
}

/**
 * Loads every figure belonging to the same bone as [initialImageId] so the viewer can
 * page/swipe through all of that bone's figures, starting on the one the user tapped.
 */
class ViewerViewModel(
    private val repository: OrthoRepository,
    private val initialImageId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState(isLoading = true))
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    init {
        loadImages()
    }

    private fun loadImages() {
        viewModelScope.launch {
            val tapped = repository.getImageById(initialImageId)
            if (tapped == null) {
                _uiState.value = ViewerUiState(isLoading = false)
                return@launch
            }
            val boneImages = repository.getImagesForBoneOnce(tapped.boneId)
            val images = boneImages.ifEmpty { listOf(tapped) }
            val startIndex = images.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0)
            _uiState.value = ViewerUiState(
                images = images,
                startIndex = startIndex,
                isLoading = false
            )
        }
    }

    companion object {
        fun provideFactory(
            repository: OrthoRepository,
            imageId: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ViewerViewModel(repository, imageId) as T
            }
        }
    }
}
