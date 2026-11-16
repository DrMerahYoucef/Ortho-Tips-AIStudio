package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ai.AssistantQuestion
import com.example.data.ai.AssistantReply
import com.example.data.ai.AssistantReport
import com.example.data.ai.AssistantRequest
import com.example.data.ai.GeminiException
import com.example.data.ai.GeminiModel
import com.example.data.ai.GeminiModels
import com.example.data.ai.GeminiService
import com.example.data.ai.PatientAssistant
import com.example.data.repository.PatientRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------------------
// Réglages → Gemini
// ---------------------------------------------------------------------------------------

data class GeminiSettingsUiState(
    val hasKey: Boolean = false,
    val keyInput: String = "",
    val preference: String = GeminiModels.DEFAULT_PREFERENCE,
    val models: List<GeminiModel> = emptyList(),
    val currentModel: String = "",
    val checkedAt: Long = 0L,
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false
)

class GeminiSettingsViewModel(private val service: GeminiService) : ViewModel() {

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<GeminiSettingsUiState> = _state.asStateFlow()

    init {
        if (service.hasKey()) refreshModels(force = false)
    }

    private fun snapshot(base: GeminiSettingsUiState = GeminiSettingsUiState()) = base.copy(
        hasKey = service.hasKey(),
        preference = service.preference,
        models = service.cachedModels(),
        currentModel = service.currentModel(),
        checkedAt = service.modelsCheckedAt()
    )

    private fun reload() = _state.update { snapshot(it) }

    fun setKeyInput(value: String) = _state.update { it.copy(keyInput = value, message = null) }

    fun saveKey() {
        val key = _state.value.keyInput.trim()
        if (key.isEmpty()) return
        service.saveApiKey(key)
        _state.update { snapshot(it).copy(keyInput = "") }
        test()
    }

    fun removeKey() {
        service.clearApiKey()
        _state.update { snapshot(it).copy(message = "Clé retirée de ce téléphone.", messageIsError = false) }
    }

    fun test() = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = null) }
        try {
            service.refreshModels(force = true)
            val model = service.test()
            _state.update { snapshot(it).copy(busy = false, message = "La clé fonctionne (modèle : $model).", messageIsError = false) }
        } catch (e: GeminiException) {
            _state.update { snapshot(it).copy(busy = false, message = e.message, messageIsError = true) }
        }
    }

    fun setPreference(value: String) {
        service.preference = value
        reload()
    }

    fun refreshModels(force: Boolean) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            service.refreshModels(force)
            _state.update { snapshot(it).copy(busy = false, message = if (force) "Liste des modèles actualisée." else it.message, messageIsError = false) }
        } catch (e: GeminiException) {
            _state.update { snapshot(it).copy(busy = false, message = e.message, messageIsError = true) }
        }
    }

    companion object {
        fun provideFactory(service: GeminiService): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = GeminiSettingsViewModel(service) as T
        }
    }
}

// ---------------------------------------------------------------------------------------
// Fiche patient → Assistant IA
// ---------------------------------------------------------------------------------------

data class PatientAssistantUiState(
    val loading: Boolean = true,
    val hasKey: Boolean = false,
    val patientName: String = "",
    val patientCode: String = "",
    val request: AssistantRequest = AssistantRequest.DIAGNOSIS,
    val question: String = "",
    val context: String = "",
    val started: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val questions: List<AssistantQuestion> = emptyList(),
    val answers: List<String> = emptyList(),
    val report: AssistantReport? = null,
    val model: String = "",
    val reportAt: Long = 0L,
    val refine: String = "",
    val savedToRecord: Boolean = false
)

/**
 * A conversation with Gemini about one patient. Only the anonymised context
 * (shown and editable on screen) is sent; the name stays on the phone.
 */
class PatientAssistantViewModel(
    private val repository: PatientRepository,
    private val service: GeminiService,
    private val patientId: String
) : ViewModel() {

    private val _state = MutableStateFlow(PatientAssistantUiState(hasKey = service.hasKey()))
    val state: StateFlow<PatientAssistantUiState> = _state.asStateFlow()

    /** The conversation sent to Gemini (user and model turns). */
    private var turns = JSONArray()

    init {
        viewModelScope.launch {
            val detail = repository.getPatientDetail(patientId).first()
            _state.update {
                it.copy(
                    loading = false,
                    patientName = detail?.patient?.fullName.orEmpty(),
                    patientCode = detail?.patient?.patientCode.orEmpty(),
                    context = detail?.let { d -> PatientAssistant.anonymisedContext(d) }.orEmpty()
                )
            }
        }
    }

    fun refreshKey() = _state.update { it.copy(hasKey = service.hasKey()) }
    fun setRequest(value: AssistantRequest) = _state.update { it.copy(request = value) }
    fun setQuestion(value: String) = _state.update { it.copy(question = value) }
    fun setContext(value: String) = _state.update { it.copy(context = value) }
    fun setAnswer(index: Int, value: String) = _state.update { s ->
        s.copy(answers = s.answers.toMutableList().also { list -> while (list.size <= index) list += ""; list[index] = value })
    }
    fun setRefine(value: String) = _state.update { it.copy(refine = value) }

    fun start() {
        val s = _state.value
        turns = JSONArray()
        _state.update { it.copy(started = true, questions = emptyList(), answers = emptyList(), report = null, savedToRecord = false) }
        ask(PatientAssistant.firstMessage(s.request, s.question, s.context))
    }

    fun sendAnswers() {
        val s = _state.value
        ask(PatientAssistant.answersMessage(s.questions, s.answers))
    }

    fun skipQuestions() = ask(PatientAssistant.SKIP_MESSAGE)

    /** A change requested on the report; Gemini gets the report as the surgeon edited it. */
    fun sendRefine() {
        val s = _state.value
        val report = s.report ?: return
        if (s.refine.isBlank()) return
        ask(PatientAssistant.refineMessage(s.refine) + "\n\nRapport actuel, tel que corrigé par le chirurgien :\n" +
            PatientAssistant.toText(report, PatientAssistant.requestLabel(s.request), s.model, s.reportAt))
        _state.update { it.copy(refine = "") }
    }

    fun retry() {
        // The last user turn is still the last one: send the conversation again.
        if (turns.length() > 0) call()
    }

    private fun ask(text: String) {
        turns.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        call()
    }

    private fun call() = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        try {
            val result = service.generateJson(PatientAssistant.SYSTEM_INSTRUCTION, turns, PatientAssistant.RESPONSE_SCHEMA)
            val reply = PatientAssistant.parse(result.text)
            turns.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", result.text))))
            _state.update { s ->
                when (reply) {
                    is AssistantReply.Questions -> s.copy(busy = false, model = result.model,
                        questions = reply.questions, answers = List(reply.questions.size) { "" })
                    is AssistantReply.Report -> s.copy(busy = false, model = result.model, questions = emptyList(), answers = emptyList(),
                        report = reply.report, reportAt = System.currentTimeMillis(), savedToRecord = false)
                }
            }
        } catch (e: GeminiException) {
            _state.update { it.copy(busy = false, error = e.message, hasKey = service.hasKey()) }
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(busy = false, error = (e.message ?: "Réponse illisible") + ". Réessayez.") }
        }
    }

    fun updateReport(transform: (AssistantReport) -> AssistantReport) =
        _state.update { s -> s.report?.let { s.copy(report = transform(it), savedToRecord = false) } ?: s }

    fun plainText(): String {
        val s = _state.value
        val report = s.report ?: return ""
        return PatientAssistant.toText(report, PatientAssistant.requestLabel(s.request), s.model, s.reportAt)
    }

    fun printableHtml(): String {
        val s = _state.value
        val report = s.report ?: return ""
        return PatientAssistant.toHtml(report, s.patientName, s.patientCode, PatientAssistant.requestLabel(s.request), s.model, s.reportAt)
    }

    /** Appends the (edited) report to the patient's notes. */
    fun addToRecord() = viewModelScope.launch {
        val text = plainText().takeIf { it.isNotBlank() } ?: return@launch
        val patient = repository.getPatient(patientId) ?: return@launch
        val notes = listOf(patient.generalNotes.trim(), text).filter { it.isNotBlank() }.joinToString("\n\n")
        repository.savePatient(patient.copy(generalNotes = notes, updatedAt = System.currentTimeMillis()))
        _state.update { it.copy(savedToRecord = true) }
    }

    fun newConversation() {
        turns = JSONArray()
        _state.update { it.copy(started = false, questions = emptyList(), answers = emptyList(), report = null, error = null, refine = "", savedToRecord = false) }
    }

    companion object {
        fun provideFactory(repository: PatientRepository, service: GeminiService, patientId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = PatientAssistantViewModel(repository, service, patientId) as T
            }
    }
}
