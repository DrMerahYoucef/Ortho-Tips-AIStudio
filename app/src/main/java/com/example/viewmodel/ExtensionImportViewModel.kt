package com.example.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.extensions.ExtensionError
import com.example.data.extensions.ExtensionManifest
import com.example.data.extensions.ExtensionPackageSource
import com.example.data.extensions.ExtensionRepository
import com.example.data.extensions.OrthoExtension
import com.example.data.extensions.PackagePreview
import com.example.data.extensions.PackageStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ExtensionImportUiState(
    val loading: Boolean = true,
    /** Packages shipped in the app, each with its status against what is installed. */
    val bundled: List<PackagePreview> = emptyList(),
    val installed: List<OrthoExtension> = emptyList(),
    /** The package awaiting confirmation (import-time review, extension guide 4.3). */
    val review: PackagePreview? = null,
    val busy: Boolean = false
) {
    /** Shipped packages newer than, or different from, what is installed. */
    val updates: List<PackagePreview>
        get() = bundled.filter { it.installable && (it.status == PackageStatus.UPDATE || it.status == PackageStatus.CHANGED) }
}

sealed interface ExtensionImportEvent {
    data class Installed(val manifest: ExtensionManifest, val previousVersion: String?) : ExtensionImportEvent
    data class Failed(val error: ExtensionError) : ExtensionImportEvent
    data class Converted(val manifest: ExtensionManifest) : ExtensionImportEvent
}

class ExtensionImportViewModel(
    private val repository: ExtensionRepository
) : ViewModel() {

    private val bundled = MutableStateFlow<List<PackagePreview>>(emptyList())
    private val review = MutableStateFlow<PackagePreview?>(null)
    private val busy = MutableStateFlow(false)
    private val loading = MutableStateFlow(true)

    val uiState: StateFlow<ExtensionImportUiState> = combine(
        bundled, repository.extensions, review, busy, loading
    ) { bundledList, installed, pending, isBusy, isLoading ->
        ExtensionImportUiState(
            loading = isLoading,
            bundled = bundledList,
            installed = installed,
            review = pending,
            busy = isBusy
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExtensionImportUiState())

    private val _events = MutableSharedFlow<ExtensionImportEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ExtensionImportEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.refresh()
            bundled.value = repository.bundledPackages()
            loading.value = false
        }
    }

    /** A file picked by the user: describe it, then wait for confirmation. */
    fun inspectDocument(uri: Uri) = runBusy {
        when (val result = repository.inspect(ExtensionPackageSource.Document(uri))) {
            is ExtensionRepository.InspectResult.Ready -> review.value = result.preview
            is ExtensionRepository.InspectResult.Failed -> _events.emit(ExtensionImportEvent.Failed(result.error))
        }
    }

    /** A shipped package: shown in the same review before installing. */
    fun reviewBundled(preview: PackagePreview) {
        review.value = preview
    }

    fun dismissReview() {
        if (!busy.value) review.value = null
    }

    fun confirmInstall() {
        val pending = review.value ?: return
        runBusy {
            val event = when (val result = repository.install(pending.source)) {
                is ExtensionRepository.InstallResult.Installed -> ExtensionImportEvent.Installed(
                    manifest = result.extension.manifest,
                    previousVersion = result.previousVersion
                )
                is ExtensionRepository.InstallResult.Failed -> ExtensionImportEvent.Failed(result.error)
            }
            review.value = null
            bundled.value = repository.bundledPackages()
            _events.emit(event)
        }
    }

    /**
     * Packages an HTML program into an `.OTips` file saved at [destination],
     * then opens the review for that new file so it can be installed at once.
     */
    fun convertHtml(html: Uri, destination: Uri) = runBusy {
        when (val result = repository.packageHtml(html, destination)) {
            is ExtensionRepository.ConvertResult.Converted -> {
                _events.emit(ExtensionImportEvent.Converted(result.manifest))
                when (val inspected = repository.inspect(ExtensionPackageSource.Document(destination))) {
                    is ExtensionRepository.InspectResult.Ready -> review.value = inspected.preview
                    is ExtensionRepository.InspectResult.Failed -> _events.emit(ExtensionImportEvent.Failed(inspected.error))
                }
            }
            is ExtensionRepository.ConvertResult.Failed -> _events.emit(ExtensionImportEvent.Failed(result.error))
        }
    }

    private fun runBusy(block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } finally {
                busy.value = false
            }
        }
    }

    companion object {
        fun provideFactory(repository: ExtensionRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ExtensionImportViewModel(repository) as T
            }
    }
}
