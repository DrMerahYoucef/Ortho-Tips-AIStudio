package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.prefs.SurgeonProfile
import com.example.data.prefs.SurgeonProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SurgeonProfileViewModel(
    private val repository: SurgeonProfileRepository
) : ViewModel() {

    val profile: StateFlow<SurgeonProfile> = repository.profileFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SurgeonProfile()
    )

    // `profile` above starts from a synthetic empty SurgeonProfile() before DataStore has
    // actually read the saved file from disk — that placeholder is indistinguishable from a
    // genuinely empty profile. This flips to true only once the real, persisted value has
    // been read at least once, so the screen knows when it's safe to populate the form.
    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    init {
        viewModelScope.launch {
            repository.profileFlow.first()
            _isLoaded.value = true
        }
    }

    fun save(profile: SurgeonProfile) {
        viewModelScope.launch { repository.saveProfile(profile) }
    }

    companion object {
        fun provideFactory(repository: SurgeonProfileRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SurgeonProfileViewModel(repository) as T
                }
            }
    }
}
