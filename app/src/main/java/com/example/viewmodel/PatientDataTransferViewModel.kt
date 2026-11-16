package com.example.viewmodel

import com.example.R
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.repository.PatientRepository
import com.example.utils.ImportExportProgress
import com.example.utils.PatientExportBundle
import com.example.utils.PatientZipExporter
import com.example.utils.PatientZipImporter
import com.example.utils.ShareHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class TransferMode { IDLE, EXPORTING, IMPORTING }

data class PatientDataTransferUiState(
    val mode: TransferMode = TransferMode.IDLE,
    val progress: ImportExportProgress = ImportExportProgress()
)

class PatientDataTransferViewModel(
    private val repository: PatientRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PatientDataTransferUiState())
    val uiState: StateFlow<PatientDataTransferUiState> = _uiState.asStateFlow()

    /** [patientIds] null exports every patient; otherwise only the given ids. */
    fun exportPatients(context: Context, patientIds: List<String>?) {
        viewModelScope.launch {
            _uiState.value = PatientDataTransferUiState(mode = TransferMode.EXPORTING)
            try {
                val patients = if (patientIds == null) {
                    // "export all": pull every active + archived patient directly.
                    withContext(Dispatchers.IO) {
                        val active = repository.getActivePatientSummaries().first().map { it.id }
                        val archived = repository.getArchivedPatientSummaries().first().map { it.id }
                        repository.getPatientsByIdsOnce(active + archived)
                    }
                } else {
                    withContext(Dispatchers.IO) { repository.getPatientsByIdsOnce(patientIds) }
                }

                // With a cloud backend some media may be drive-only. The exporter
                // skips files it cannot find on disk, so fetch them first rather
                // than writing a silently incomplete archive.
                withContext(Dispatchers.IO) {
                    patients.forEach { patient ->
                        runCatching { repository.prefetchPatientMedia(patient.id) }
                    }
                }

                val bundles = withContext(Dispatchers.IO) {
                    patients.map { patient ->
                        PatientExportBundle(
                            patient = patient,
                            timelineEvents = repository.getTimelineForPatient(patient.id).first().all,
                            surgeries = repository.getSurgeriesForPatient(patient.id).first(),
                            images = repository.getImagesForPatient(patient.id).first(),
                            documents = repository.getDocumentsForPatient(patient.id).first()
                        )
                    }
                }

                val outputDir = File(context.cacheDir, "exports").apply { mkdirs() }
                val destination = File(outputDir, "patients_export_${System.currentTimeMillis()}.zip")
                withContext(Dispatchers.IO) { PatientZipExporter.export(context, bundles, destination) }

                _uiState.value = PatientDataTransferUiState(
                    mode = TransferMode.IDLE,
                    progress = ImportExportProgress(patientsAdded = bundles.size, isComplete = true)
                )
                ShareHelper.shareFile(context, destination, mimeType = "application/zip", chooserTitle = context.getString(R.string.transfer_share_title))
            } catch (e: Exception) {
                _uiState.value = PatientDataTransferUiState(
                    mode = TransferMode.IDLE,
                    progress = ImportExportProgress(errors = listOf(e.message ?: context.getString(R.string.transfer_export_failed)), isComplete = true)
                )
            }
        }
    }

    fun importZip(context: Context, zipUri: Uri) {
        viewModelScope.launch {
            _uiState.value = PatientDataTransferUiState(mode = TransferMode.IMPORTING)
            try {
                val tempZip = File(context.cacheDir, "import_${System.currentTimeMillis()}.zip")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(zipUri)?.use { input ->
                        tempZip.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                PatientZipImporter.import(context, repository, tempZip) { progress ->
                    _uiState.value = PatientDataTransferUiState(mode = TransferMode.IMPORTING, progress = progress)
                }
                tempZip.delete()
                _uiState.value = _uiState.value.copy(mode = TransferMode.IDLE)
            } catch (e: Exception) {
                _uiState.value = PatientDataTransferUiState(
                    mode = TransferMode.IDLE,
                    progress = ImportExportProgress(errors = listOf(e.message ?: context.getString(R.string.transfer_import_failed)), isComplete = true)
                )
            }
        }
    }

    fun reset() {
        _uiState.value = PatientDataTransferUiState()
    }

    companion object {
        fun provideFactory(repository: PatientRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PatientDataTransferViewModel(repository) as T
                }
            }
    }
}
