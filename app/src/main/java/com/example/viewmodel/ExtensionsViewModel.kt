package com.example.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.extensions.ExtensionManifest
import com.example.data.extensions.ExtensionRepository
import com.example.data.extensions.OrthoExtension
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One-off outcomes shown as a short message by the extension manager. */
sealed interface ExtensionEvent {
    data class Deleted(val manifest: ExtensionManifest) : ExtensionEvent
    data class DataCleared(val manifest: ExtensionManifest) : ExtensionEvent
    data class Exported(val ok: Boolean) : ExtensionEvent
}

/** The installed-extensions manager. Installing happens on the import screen. */
class ExtensionsViewModel(
    private val repository: ExtensionRepository
) : ViewModel() {

    val extensions: StateFlow<List<OrthoExtension>> = repository.extensions

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _events = MutableSharedFlow<ExtensionEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ExtensionEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.refresh()
            _loading.value = false
        }
    }

    fun exportFileName(extension: OrthoExtension): String = repository.exportFileName(extension)

    fun delete(extension: OrthoExtension) {
        viewModelScope.launch {
            repository.delete(extension.id)
            _events.emit(ExtensionEvent.Deleted(extension.manifest))
        }
    }

    fun clearData(extension: OrthoExtension) {
        viewModelScope.launch {
            repository.clearData(extension.id)
            _events.emit(ExtensionEvent.DataCleared(extension.manifest))
        }
    }

    fun export(extension: OrthoExtension, destination: Uri) {
        viewModelScope.launch {
            _events.emit(ExtensionEvent.Exported(repository.export(extension.id, destination)))
        }
    }

    companion object {
        fun provideFactory(repository: ExtensionRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ExtensionsViewModel(repository) as T
            }
    }
}
