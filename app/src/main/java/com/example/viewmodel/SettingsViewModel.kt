package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.backup.BackupManager
import com.example.data.prefs.FontSizePreference
import com.example.data.prefs.SettingsRepository
import com.example.data.prefs.ThemeMode
import com.example.data.prefs.UserSettings
import com.example.data.repository.OrthoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Removing the operative guide's data is no longer a setting: it goes with the
 * Operative Guide extension (Extensions → Manage → Delete).
 */
enum class DatabaseConfirmAction { REINITIALIZE, DELETE_PATIENTS }

data class SettingsUiState(
    val userSettings: UserSettings = UserSettings(),
    val backupJson: String? = null,
    val importMessage: String? = null,
    val isExportDialogOpen: Boolean = false,
    val isImportDialogOpen: Boolean = false
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val backupManager: BackupManager,
    private val orthoRepository: OrthoRepository,
    private val patientRepository: com.example.data.repository.PatientRepository
) : ViewModel() {

    /** Patients on this device, shown in the delete confirmation. */
    private val _patientCount = MutableStateFlow(0)
    val patientCount = _patientCount.asStateFlow()

    fun refreshPatientCount() {
        viewModelScope.launch { _patientCount.value = patientRepository.getPatientCount() }
    }

    fun requestDeleteAllPatients() {
        _databaseActionMessage.value = null
        refreshPatientCount()
        _pendingDatabaseAction.value = DatabaseConfirmAction.DELETE_PATIENTS
    }

    val settings: StateFlow<UserSettings> = settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UserSettings()
        )

    private val _backupJson = MutableStateFlow<String?>(null)
    val backupJson = _backupJson.asStateFlow()

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage = _importMessage.asStateFlow()

    private val _isExportDialogOpen = MutableStateFlow(false)
    val isExportDialogOpen = _isExportDialogOpen.asStateFlow()

    private val _isImportDialogOpen = MutableStateFlow(false)
    val isImportDialogOpen = _isImportDialogOpen.asStateFlow()

    private val _pendingDatabaseAction = MutableStateFlow<DatabaseConfirmAction?>(null)
    val pendingDatabaseAction = _pendingDatabaseAction.asStateFlow()

    private val _databaseActionMessage = MutableStateFlow<String?>(null)
    val databaseActionMessage = _databaseActionMessage.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDynamicColor(enabled)
        }
    }

    fun setOperativeMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOperativeMode(enabled)
        }
    }

    fun setHighContrast(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHighContrast(enabled)
        }
    }

    fun setFontSize(size: FontSizePreference) {
        viewModelScope.launch {
            settingsRepository.setFontSize(size)
        }
    }

    fun setQuickPearls(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setQuickPearls(enabled)
        }
    }

    fun setAppLock(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAppLock(enabled) }
    }

    fun setSmartSuggestions(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSmartSuggestions(enabled)
        }
    }

    fun generateBackup() {
        viewModelScope.launch {
            val json = backupManager.exportDataJson()
            _backupJson.value = json
            _isExportDialogOpen.value = true
        }
    }

    fun closeExportDialog() {
        _isExportDialogOpen.value = false
        _backupJson.value = null
    }

    fun openImportDialog() {
        _isImportDialogOpen.value = true
        _importMessage.value = null
    }

    fun closeImportDialog() {
        _isImportDialogOpen.value = false
        _importMessage.value = null
    }

    fun restoreBackup(jsonString: String) {
        viewModelScope.launch {
            val res = backupManager.importDataJson(jsonString)
            if (res.isSuccess) {
                _importMessage.value = "$MSG_RESTORED${res.getOrNull() ?: 0}"
            } else {
                _importMessage.value = "$MSG_RESTORE_ERROR${res.exceptionOrNull()?.localizedMessage.orEmpty()}"
            }
        }
    }

    fun requestReinitializeDatabase() {
        _databaseActionMessage.value = null
        _pendingDatabaseAction.value = DatabaseConfirmAction.REINITIALIZE
    }

    fun dismissDatabaseActionDialog() {
        _pendingDatabaseAction.value = null
    }

    fun confirmDatabaseAction() {
        val action = _pendingDatabaseAction.value ?: return
        _pendingDatabaseAction.value = null
        viewModelScope.launch {
            when (action) {
                DatabaseConfirmAction.REINITIALIZE -> {
                    orthoRepository.reinitializeDatabase()
                    _databaseActionMessage.value = MSG_DB_REINITIALIZED
                }
                DatabaseConfirmAction.DELETE_PATIENTS -> {
                    val removed = patientRepository.getPatientCount()
                    patientRepository.deleteAllPatients()
                    _patientCount.value = 0
                    _databaseActionMessage.value = "PATIENTS_DELETED:$removed"
                }
            }
        }
    }

    fun dismissDatabaseActionMessage() {
        _databaseActionMessage.value = null
    }

    companion object {
        // Message codes the screen turns into text in the app language, so the
        // ViewModel never holds English sentences.
        const val MSG_RESTORED = "RESTORED:"
        const val MSG_RESTORE_ERROR = "RESTORE_ERROR:"
        const val MSG_DB_REINITIALIZED = "DB_REINITIALIZED"
        fun provideFactory(
            settingsRepository: SettingsRepository,
            backupManager: BackupManager,
            orthoRepository: OrthoRepository,
            patientRepository: com.example.data.repository.PatientRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(
                    settingsRepository, backupManager, orthoRepository, patientRepository
                ) as T
            }
        }
    }
}
