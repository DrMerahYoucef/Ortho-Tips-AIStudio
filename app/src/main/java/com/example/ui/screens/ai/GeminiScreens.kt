package com.example.ui.screens.ai

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.format.DateUtils
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.ai.AssistantDiagnosis
import com.example.data.ai.AssistantRequest
import com.example.data.ai.AssistantTreatment
import com.example.data.ai.GeminiModels
import com.example.data.ai.ModelFamily
import com.example.data.ai.PatientAssistant
import com.example.viewmodel.GeminiSettingsViewModel
import com.example.viewmodel.PatientAssistantViewModel

// =========================================================================================
// Réglages → Gemini
// =========================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeminiSettingsScreen(viewModel: GeminiSettingsViewModel, onNavigateBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showKey by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gemini_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("gemini_settings"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.gemini_key_label), style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = state.keyInput,
                            onValueChange = viewModel::setKeyInput,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = {
                                Text(stringResource(if (state.hasKey) R.string.gemini_key_saved_placeholder else R.string.gemini_key_placeholder))
                            },
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = stringResource(R.string.gemini_show_key))
                                }
                            }
                        )
                        Text(stringResource(R.string.gemini_key_help), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.gemini_get_key),
                            style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp).clickable {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = viewModel::saveKey, enabled = state.keyInput.isNotBlank() && !state.busy, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.gemini_save_key))
                            }
                            OutlinedButton(onClick = { viewModel.test() }, enabled = state.hasKey && !state.busy, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.gemini_test))
                            }
                            OutlinedButton(
                                onClick = viewModel::removeKey, enabled = state.hasKey && !state.busy, modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) { Text(stringResource(R.string.gemini_remove_key)) }
                        }
                        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        state.message?.let {
                            Text(it, color = if (state.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.gemini_model), style = MaterialTheme.typography.titleSmall)
                        Box {
                            OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(preferenceLabel(state.preference), modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ExpandMore, contentDescription = null)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                ModelFamily.entries.forEach { f ->
                                    val pref = GeminiModels.AUTO_PREFIX + f.key
                                    DropdownMenuItem(text = { Text(preferenceLabel(pref)) }, onClick = { menuOpen = false; viewModel.setPreference(pref) })
                                }
                                if (state.models.isNotEmpty()) HorizontalDivider()
                                state.models.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m.id + if (m.preview) " (préversion)" else "") },
                                        onClick = { menuOpen = false; viewModel.setPreference(m.id) }
                                    )
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.gemini_current_model, state.currentModel) +
                                if (state.checkedAt > 0) " · " + stringResource(R.string.gemini_list_checked,
                                    DateUtils.getRelativeTimeSpanString(state.checkedAt).toString()) else "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(stringResource(R.string.gemini_models_help), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { viewModel.refreshModels(force = true) }, enabled = state.hasKey && !state.busy,
                            modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.gemini_refresh_models))
                        }
                    }
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.gemini_privacy_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.gemini_privacy_body), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.gemini_extensions_note), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun preferenceLabel(preference: String): String = when (GeminiModels.familyOf(preference)) {
    ModelFamily.FLASH_LITE -> stringResource(R.string.gemini_auto_flash_lite)
    ModelFamily.FLASH -> stringResource(R.string.gemini_auto_flash)
    ModelFamily.PRO -> stringResource(R.string.gemini_auto_pro)
    null -> preference
}

// =========================================================================================
// Fiche patient → Assistant IA
// =========================================================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PatientAssistantScreen(
    viewModel: PatientAssistantViewModel,
    onNavigateBack: () -> Unit,
    onOpenGeminiSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var contextOpen by rememberSaveable { mutableStateOf(false) }
    // Coming back from Réglages → Gemini after saving a key.
    LaunchedEffect(Unit) { viewModel.refreshKey() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.assistant_title))
                        if (state.patientName.isNotBlank()) Text(state.patientName, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.started) TextButton(onClick = viewModel::newConversation) { Text(stringResource(R.string.assistant_new)) }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("patient_assistant"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(stringResource(R.string.assistant_disclaimer), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            if (!state.hasKey) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.assistant_no_key))
                            Button(onClick = onOpenGeminiSettings) { Text(stringResource(R.string.assistant_setup_gemini)) }
                        }
                    }
                }
            }
            if (!state.started) {
                item {
                    Column {
                        Text(stringResource(R.string.assistant_request), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AssistantRequest.entries.forEach { r ->
                                FilterChip(selected = state.request == r, onClick = { viewModel.setRequest(r) }, label = { Text(requestChip(r)) })
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = state.question, onValueChange = viewModel::setQuestion, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(if (state.request == AssistantRequest.FREE) R.string.assistant_question_required else R.string.assistant_question_optional)) },
                        minLines = 2
                    )
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.assistant_sent_data), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { contextOpen = !contextOpen }) {
                                    Icon(if (contextOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                                }
                            }
                            Text(stringResource(R.string.assistant_sent_data_help), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (contextOpen) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = state.context, onValueChange = viewModel::setContext,
                                    modifier = Modifier.fillMaxWidth(), minLines = 6)
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = viewModel::start,
                        enabled = state.hasKey && !state.loading && !state.busy && (state.request != AssistantRequest.FREE || state.question.isNotBlank()),
                        modifier = Modifier.fillMaxWidth().testTag("assistant_ask")
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.assistant_ask))
                    }
                }
            } else {
                item {
                    Text(PatientAssistant.requestLabel(state.request) + if (state.question.isNotBlank()) " — " + state.question else "",
                        style = MaterialTheme.typography.titleSmall)
                }
            }

            if (state.busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.assistant_thinking))
                    }
                }
            }
            state.error?.let { error ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                            if (state.started) TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.assistant_retry)) }
                        }
                    }
                }
            }

            if (state.questions.isNotEmpty()) {
                item { Text(stringResource(R.string.assistant_questions), style = MaterialTheme.typography.titleSmall) }
                itemsIndexed(state.questions) { i, q ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(q.question, fontWeight = FontWeight.SemiBold)
                            if (q.why.isNotBlank()) Text(q.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(value = state.answers.getOrNull(i).orEmpty(), onValueChange = { viewModel.setAnswer(i, it) },
                                modifier = Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.assistant_answer)) })
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::sendAnswers, enabled = !state.busy) { Text(stringResource(R.string.assistant_send_answers)) }
                        OutlinedButton(onClick = viewModel::skipQuestions, enabled = !state.busy) { Text(stringResource(R.string.assistant_skip)) }
                    }
                }
            }

            state.report?.let { report ->
                item {
                    Column {
                        Text(stringResource(R.string.assistant_report) + " · " + state.model, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.assistant_report_help), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    EditableBlock(stringResource(R.string.assistant_summary), report.summary) { v -> viewModel.updateReport { it.copy(summary = v) } }
                }
                item { Text(stringResource(R.string.assistant_diagnoses), style = MaterialTheme.typography.titleSmall) }
                itemsIndexed(report.diagnoses) { i, d ->
                    DiagnosisCard(d,
                        onChange = { nd -> viewModel.updateReport { r -> r.copy(diagnoses = r.diagnoses.toMutableList().also { it[i] = nd }) } },
                        onRemove = { viewModel.updateReport { r -> r.copy(diagnoses = r.diagnoses.filterIndexed { j, _ -> j != i }) } })
                }
                item {
                    EditableBlock(stringResource(R.string.assistant_investigations), report.investigations) { v -> viewModel.updateReport { it.copy(investigations = v) } }
                }
                item {
                    EditableBlock(stringResource(R.string.assistant_management), report.management) { v -> viewModel.updateReport { it.copy(management = v) } }
                }
                item { Text(stringResource(R.string.assistant_treatments), style = MaterialTheme.typography.titleSmall) }
                itemsIndexed(report.treatments) { i, t ->
                    TreatmentCard(t,
                        onChange = { nt -> viewModel.updateReport { r -> r.copy(treatments = r.treatments.toMutableList().also { it[i] = nt }) } },
                        onRemove = { viewModel.updateReport { r -> r.copy(treatments = r.treatments.filterIndexed { j, _ -> j != i }) } })
                }
                item {
                    TextButton(onClick = {
                        viewModel.updateReport { r -> r.copy(treatments = r.treatments + AssistantTreatment("autre", "", "", "", "", "", "", "a_verifier", "")) }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.assistant_add_treatment))
                    }
                }
                item {
                    EditableBlock(stringResource(R.string.assistant_warnings), report.warnings) { v -> viewModel.updateReport { it.copy(warnings = v) } }
                }
                item {
                    EditableBlock(stringResource(R.string.assistant_to_verify), report.toVerify) { v -> viewModel.updateReport { it.copy(toVerify = v) } }
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { printHtml(context, viewModel.printableHtml(), "Assistant IA - " + state.patientName) }) {
                            Icon(Icons.Default.Print, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.assistant_print))
                        }
                        OutlinedButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Assistant IA", viewModel.plainText()))
                            Toast.makeText(context, R.string.assistant_copied, Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.assistant_copy))
                        }
                        OutlinedButton(onClick = viewModel::addToRecord, enabled = !state.savedToRecord) {
                            Icon(Icons.Default.Save, contentDescription = null); Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (state.savedToRecord) R.string.assistant_saved else R.string.assistant_add_to_record))
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = state.refine, onValueChange = viewModel::setRefine, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.assistant_refine)) }, minLines = 2,
                        trailingIcon = {
                            IconButton(onClick = viewModel::sendRefine, enabled = state.refine.isNotBlank() && !state.busy) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.assistant_refine_send))
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun requestChip(r: AssistantRequest): String = stringResource(
    when (r) {
        AssistantRequest.DIAGNOSIS -> R.string.assistant_req_diagnosis
        AssistantRequest.PAIN -> R.string.assistant_req_pain
        AssistantRequest.INFECTION -> R.string.assistant_req_infection
        AssistantRequest.PROCEDURE -> R.string.assistant_req_procedure
        AssistantRequest.FREE -> R.string.assistant_req_free
    }
)

/** A text block of the report, edited in place (one item per line for lists). */
@Composable
private fun EditableBlock(title: String, value: String, onChange: (String) -> Unit) {
    if (value.isBlank() && title.isBlank()) return
    OutlinedTextField(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), label = { Text(title) }, minLines = 2)
}

@Composable
private fun DiagnosisCard(d: AssistantDiagnosis, onChange: (AssistantDiagnosis) -> Unit, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = d.name, onValueChange = { onChange(d.copy(name = it)) }, modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.assistant_diagnosis) + " · " + d.likelihood) })
                IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.assistant_remove)) }
            }
            OutlinedTextField(value = d.arguments, onValueChange = { onChange(d.copy(arguments = it)) }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.assistant_arguments)) }, minLines = 2)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TreatmentCard(t: AssistantTreatment, onChange: (AssistantTreatment) -> Unit, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = t.dci, onValueChange = { onChange(t.copy(dci = it)) }, modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.assistant_dci) + if (t.purpose.isNotBlank()) " · " + t.purpose else "") })
                IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.assistant_remove)) }
            }
            OutlinedTextField(value = t.brands, onValueChange = { onChange(t.copy(brands = it)) }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.assistant_brands)) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = t.dosage, onValueChange = { onChange(t.copy(dosage = it)) }, modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.assistant_dosage)) })
                OutlinedTextField(value = t.route, onValueChange = { onChange(t.copy(route = it)) }, modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.assistant_route)) })
            }
            OutlinedTextField(value = t.posology, onValueChange = { onChange(t.copy(posology = it)) }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.assistant_posology)) })
            OutlinedTextField(value = t.duration, onValueChange = { onChange(t.copy(duration = it)) }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.assistant_duration)) })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PatientAssistant.REIMBURSEMENT_VALUES.forEach { v ->
                    FilterChip(selected = t.reimbursement == v, onClick = { onChange(t.copy(reimbursement = v)) },
                        label = { Text(PatientAssistant.reimbursementLabel(v)) })
                }
            }
            OutlinedTextField(value = t.notes, onValueChange = { onChange(t.copy(notes = it)) }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.assistant_notes)) })
        }
    }
}

// ---- printing ------------------------------------------------------------------------------

/** WebViews being printed: kept alive until Android's print dialog has the document. */
private val printing = mutableListOf<WebView>()

/** Android's print dialog (printer, or "Save as PDF"), from an HTML page. */
private fun printHtml(context: Context, html: String, jobName: String) {
    if (html.isBlank()) return
    val activity = context.findActivity() ?: return
    val webView = WebView(activity)
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            val manager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
            runCatching { manager.print(jobName, view.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build()) }
            printing.remove(view)
        }
    }
    printing += webView
    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
