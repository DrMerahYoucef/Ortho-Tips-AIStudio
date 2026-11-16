package com.example.viewmodel

import com.example.R
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.prefs.PdfOptionsRepository
import com.example.data.prefs.SurgeonProfileRepository
import com.example.data.repository.PatientRepository
import com.example.utils.OperativeProtocolGenerator
import com.example.utils.PatientChartPdfOptions
import com.example.utils.ProtocolLanguage
import com.example.utils.ShareHelper
import com.example.utils.SurgeryPdfOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProtocolGenerationUiState(
    val isGenerating: Boolean = false,
    val error: String? = null
)

class OperativeProtocolViewModel(
    private val patientRepository: PatientRepository,
    private val surgeonProfileRepository: SurgeonProfileRepository,
    private val pdfOptionsRepository: PdfOptionsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProtocolGenerationUiState())
    val uiState: StateFlow<ProtocolGenerationUiState> = _uiState.asStateFlow()

    /** The surgeon's last-used "include in PDF" selections — dialogs seed their checkboxes
     *  from these instead of always resetting to everything-on. */
    val savedSurgeryOptions: StateFlow<SurgeryPdfOptions> = pdfOptionsRepository.surgeryOptionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SurgeryPdfOptions())

    val savedChartOptions: StateFlow<PatientChartPdfOptions> = pdfOptionsRepository.chartOptionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PatientChartPdfOptions())

    fun generateAndShare(
        context: Context,
        patientId: String,
        surgeryId: String,
        language: ProtocolLanguage,
        anonymize: Boolean,
        options: SurgeryPdfOptions = SurgeryPdfOptions()
    ) {
        viewModelScope.launch {
            _uiState.value = ProtocolGenerationUiState(isGenerating = true)
            try {
                pdfOptionsRepository.saveSurgeryOptions(options)

                val patient = patientRepository.getPatient(patientId)
                val surgery = patientRepository.getSurgery(surgeryId)
                val surgeon = surgeonProfileRepository.profileFlow.first()

                if (patient == null || surgery == null) {
                    _uiState.value = ProtocolGenerationUiState(error = context.getString(R.string.protocol_err_record_not_found))
                    return@launch
                }

                val file = withContext(Dispatchers.IO) {
                    OperativeProtocolGenerator.generate(context, patient, surgery, surgeon, language, anonymize, options)
                }
                ShareHelper.sharePdf(context, file, chooserTitle = context.getString(R.string.protocol_share_title))
                _uiState.value = ProtocolGenerationUiState()
            } catch (e: Exception) {
                _uiState.value = ProtocolGenerationUiState(error = e.message ?: context.getString(R.string.protocol_err_pdf))
            }
        }
    }

    fun generatePatientChart(
        context: Context,
        patientId: String,
        anonymize: Boolean,
        options: PatientChartPdfOptions = PatientChartPdfOptions()
    ) {
        viewModelScope.launch {
            _uiState.value = ProtocolGenerationUiState(isGenerating = true)
            try {
                pdfOptionsRepository.saveChartOptions(options)

                val patient = patientRepository.getPatient(patientId)
                if (patient == null) {
                    _uiState.value = ProtocolGenerationUiState(error = context.getString(R.string.protocol_err_patient_not_found))
                    return@launch
                }
                val surgeon = surgeonProfileRepository.profileFlow.first()
                val timeline = patientRepository.getTimelineForPatient(patientId).first().all
                val surgeries = patientRepository.getSurgeriesForPatient(patientId).first()

                val file = withContext(Dispatchers.IO) {
                    com.example.utils.PatientChartPdfGenerator.generate(context, patient, timeline, surgeries, surgeon, anonymize, options)
                }
                ShareHelper.sharePdf(context, file, chooserTitle = context.getString(R.string.chart_share_title))
                _uiState.value = ProtocolGenerationUiState()
            } catch (e: Exception) {
                _uiState.value = ProtocolGenerationUiState(error = e.message ?: context.getString(R.string.protocol_err_pdf))
            }
        }
    }

    companion object {
        fun provideFactory(
            patientRepository: PatientRepository,
            surgeonProfileRepository: SurgeonProfileRepository,
            pdfOptionsRepository: PdfOptionsRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return OperativeProtocolViewModel(patientRepository, surgeonProfileRepository, pdfOptionsRepository) as T
            }
        }
    }
}
