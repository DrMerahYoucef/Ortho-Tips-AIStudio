package com.example.ui.screens.patients

import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.model.PatientDetail
import com.example.ui.components.InfoAction
import com.example.ui.components.InfoItem
import com.example.ui.components.PatientInfoGrid
import com.example.ui.components.PatientDriveStatusCard
import com.example.ui.components.EmptyStateView
import com.example.ui.components.ConfirmDeleteDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.ui.components.RegionBadge
import com.example.ui.components.SurgeryRow
import com.example.ui.components.TimelineEventRow
import com.example.utils.DateFormat
import com.example.viewmodel.PatientDetailViewModel
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.example.R
import com.example.data.local.entity.MediaType
import com.example.utils.MediaMetadata
import com.example.ui.components.PatientValueLabels
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.MedicalInformation
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.ui.res.stringResource

private enum class DetailTab { INFO, TIMELINE, SURGERIES, MEDIA }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PatientDetailScreen(
    viewModel: PatientDetailViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToEdit: () -> Unit,
    onOpenAssistant: () -> Unit = {},
    onNavigateToAddTimelineEvent: () -> Unit,
    onNavigateToEditTimelineEvent: (String) -> Unit,
    onNavigateToAddSurgery: () -> Unit,
    onNavigateToEditSurgery: (String) -> Unit,
    onNavigateToCapture: () -> Unit,
    onNavigateToAnnotate: (String) -> Unit,
    onNavigateToVideo: (String) -> Unit,
    onGenerateProtocol: (String, com.example.utils.ProtocolLanguage, Boolean, com.example.utils.SurgeryPdfOptions) -> Unit,
    onGenerateChartPdf: (Boolean, com.example.utils.PatientChartPdfOptions) -> Unit,
    savedSurgeryPdfOptions: com.example.utils.SurgeryPdfOptions = com.example.utils.SurgeryPdfOptions(),
    savedChartPdfOptions: com.example.utils.PatientChartPdfOptions = com.example.utils.PatientChartPdfOptions(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Swipe between tabs as well as tapping them; the page survives rotation.
    val pagerState = rememberPagerState { DetailTab.entries.size }
    val pagerScope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var protocolSurgeryId by remember { mutableStateOf<String?>(null) }
    var showChartOptions by remember { mutableStateOf(false) }
    // Saveable ids, so a pending confirmation survives rotation.
    var eventToDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var surgeryToDelete by rememberSaveable { mutableStateOf<String?>(null) }

    // Navigating from inside composition ran on every recomposition and could pop
    // the back stack twice, leaving a blank screen. Once, from an effect.
    val wasDeleted = uiState.wasDeleted && !uiState.isLoading
    LaunchedEffect(wasDeleted) {
        if (wasDeleted) onNavigateBack()
    }
    if (wasDeleted) return

    val detail = uiState.detail
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.patient?.fullName ?: stringResource(R.string.patient_detail_default_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenAssistant, modifier = Modifier.testTag("patient_assistant_button")) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = stringResource(R.string.patient_detail_assistant))
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.patient_detail_more_options))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.patient_detail_edit_patient)) },
                            onClick = { menuExpanded = false; onNavigateToEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.patient_detail_export_pdf)) },
                            onClick = { menuExpanded = false; showChartOptions = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(if (detail?.patient?.isArchived == true) R.string.patient_detail_unarchive else R.string.patient_detail_archive)) },
                            onClick = {
                                menuExpanded = false
                                if (detail?.patient?.isArchived == true) viewModel.unarchive() else viewModel.archive()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.patient_detail_delete_patient)) },
                            onClick = { menuExpanded = false; showDeleteConfirm = true }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            when (DetailTab.entries[pagerState.currentPage]) {
                DetailTab.TIMELINE -> FloatingActionButton(onClick = onNavigateToAddTimelineEvent) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.patient_detail_add_event))
                }
                DetailTab.SURGERIES -> FloatingActionButton(onClick = onNavigateToAddSurgery) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.patient_detail_add_surgery))
                }
                else -> {}
            }
        },
        modifier = modifier
    ) { paddingValues ->
        if (uiState.isLoading || detail == null) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(modifier = Modifier.padding(paddingValues)) {
            PatientHeader(detail)

            val tabLabels = listOf(
                stringResource(R.string.patient_detail_info),
                stringResource(R.string.patient_detail_timeline),
                stringResource(R.string.patient_detail_surgeries),
                stringResource(R.string.patient_detail_media)
            )
            PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                tabLabels.forEachIndexed { index, label ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { pagerScope.launch { pagerState.animateScrollToPage(index) } },
                        text = { TabLabel(label) }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                // Each page keeps its own scroll; neighbours are not pre-built.
                beyondViewportPageCount = 0,
                key = { DetailTab.entries[it].name },
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (DetailTab.entries[page]) {
                    DetailTab.INFO -> InfoTab(detail)
                    DetailTab.TIMELINE -> TimelineTab(
                        detail = detail,
                        onEventClick = onNavigateToEditTimelineEvent,
                        onEventDelete = { eventToDelete = it },
                    )
                    DetailTab.SURGERIES -> SurgeriesTab(
                        detail = detail,
                        onSurgeryClick = onNavigateToEditSurgery,
                        onSurgeryDelete = { surgeryToDelete = it },
                        onGenerateProtocol = { surgeryId -> protocolSurgeryId = surgeryId },
                    )
                    DetailTab.MEDIA -> MediaTab(
                        detail = detail,
                        viewModel = viewModel,
                        onAddMedia = onNavigateToCapture,
                        onImageClick = onNavigateToAnnotate,
                        onVideoClick = onNavigateToVideo
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.patient_detail_delete_patient_2)) },
            text = { Text(stringResource(R.string.patient_detail_delete_confirm_message, detail?.patient?.fullName ?: stringResource(R.string.patient_detail_this_patient))) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; viewModel.delete() }) {
                    Text(stringResource(R.string.patient_detail_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    protocolSurgeryId?.let { surgeryId ->
        ProtocolLanguageDialog(
            initialOptions = savedSurgeryPdfOptions,
            onDismiss = { protocolSurgeryId = null },
            onConfirm = { language, anonymize, options ->
                onGenerateProtocol(surgeryId, language, anonymize, options)
                protocolSurgeryId = null
            }
        )
    }

    eventToDelete?.let { id ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.confirm_delete_event_title),
            message = stringResource(R.string.confirm_delete_event_message),
            onConfirm = { viewModel.deleteTimelineEvent(id); eventToDelete = null },
            onDismiss = { eventToDelete = null }
        )
    }

    surgeryToDelete?.let { id ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.confirm_delete_surgery_title),
            message = stringResource(R.string.confirm_delete_surgery_message),
            onConfirm = { viewModel.deleteSurgery(id); surgeryToDelete = null },
            onDismiss = { surgeryToDelete = null }
        )
    }

    if (showChartOptions) {
        PatientChartOptionsDialog(
            initialOptions = savedChartPdfOptions,
            onDismiss = { showChartOptions = false },
            onConfirm = { anonymize, options ->
                onGenerateChartPdf(anonymize, options)
                showChartOptions = false
            }
        )
    }
}

@Composable
private fun ProtocolLanguageDialog(
    initialOptions: com.example.utils.SurgeryPdfOptions,
    onDismiss: () -> Unit,
    onConfirm: (com.example.utils.ProtocolLanguage, Boolean, com.example.utils.SurgeryPdfOptions) -> Unit
) {
    // Starts in the app's language rather than always French.
    var language by remember {
        mutableStateOf(
            if (com.example.data.local.ContentLocale.isFrench) com.example.utils.ProtocolLanguage.FRENCH
            else com.example.utils.ProtocolLanguage.ENGLISH
        )
    }
    var anonymize by remember { mutableStateOf(false) }
    var includeHospital by remember { mutableStateOf(initialOptions.includeHospital) }
    var includeSurgeon by remember { mutableStateOf(initialOptions.includeSurgeon) }
    var includeAssistants by remember { mutableStateOf(initialOptions.includeAssistants) }
    var includeAnesthesia by remember { mutableStateOf(initialOptions.includeAnesthesia) }
    var includeDiagnosis by remember { mutableStateOf(initialOptions.includeDiagnosis) }
    var includeProcedure by remember { mutableStateOf(initialOptions.includeProcedure) }
    var includeOperativeSteps by remember { mutableStateOf(initialOptions.includeOperativeSteps) }
    var includeMaterials by remember { mutableStateOf(initialOptions.includeMaterials) }
    var includeComplications by remember { mutableStateOf(initialOptions.includeComplications) }
    var includePostOp by remember { mutableStateOf(initialOptions.includePostOp) }
    var includeSignature by remember { mutableStateOf(initialOptions.includeSignature) }
    var includeQrCode by remember { mutableStateOf(initialOptions.includeQrCode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.patient_detail_rows_generate_operative_protocol)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                Text(stringResource(R.string.language_title), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = language == com.example.utils.ProtocolLanguage.FRENCH,
                        onClick = { language = com.example.utils.ProtocolLanguage.FRENCH },
                        label = { Text(stringResource(R.string.patient_detail_fran_ais)) }
                    )
                    androidx.compose.material3.FilterChip(
                        selected = language == com.example.utils.ProtocolLanguage.ENGLISH,
                        onClick = { language = com.example.utils.ProtocolLanguage.ENGLISH },
                        label = { Text(stringResource(R.string.patient_detail_english)) }
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    androidx.compose.material3.Checkbox(checked = anonymize, onCheckedChange = { anonymize = it })
                    Text(
                        stringResource(R.string.patient_detail_anonymize_patient_name_and_surgeon),
                        modifier = Modifier.clickable { anonymize = !anonymize }
                    )
                }

                androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.patient_detail_include_in_pdf), style = MaterialTheme.typography.labelMedium)
                PdfOptionRow(stringResource(R.string.pdf_option_hospital), includeHospital) { includeHospital = it }
                PdfOptionRow(stringResource(R.string.pdf_option_surgeon), includeSurgeon, enabled = !anonymize) { includeSurgeon = it }
                PdfOptionRow(stringResource(R.string.pdf_option_assistants), includeAssistants) { includeAssistants = it }
                PdfOptionRow(stringResource(R.string.pdf_option_anesthesia), includeAnesthesia) { includeAnesthesia = it }
                PdfOptionRow(stringResource(R.string.patient_edit_diagnosis), includeDiagnosis) { includeDiagnosis = it }
                PdfOptionRow(stringResource(R.string.pdf_option_procedure), includeProcedure) { includeProcedure = it }
                PdfOptionRow(stringResource(R.string.pdf_option_operative_steps), includeOperativeSteps) { includeOperativeSteps = it }
                PdfOptionRow(stringResource(R.string.pdf_option_materials), includeMaterials) { includeMaterials = it }
                PdfOptionRow(stringResource(R.string.pdf_option_complications), includeComplications) { includeComplications = it }
                PdfOptionRow(stringResource(R.string.pdf_option_postop), includePostOp) { includePostOp = it }
                PdfOptionRow(stringResource(R.string.surgeon_profile_signature), includeSignature) { includeSignature = it }
                PdfOptionRow(stringResource(R.string.pdf_option_qr_code), includeQrCode, enabled = !anonymize) { includeQrCode = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    language,
                    anonymize,
                    com.example.utils.SurgeryPdfOptions(
                        includeHospital = includeHospital,
                        includeSurgeon = includeSurgeon,
                        includeAssistants = includeAssistants,
                        includeAnesthesia = includeAnesthesia,
                        includeDiagnosis = includeDiagnosis,
                        includeProcedure = includeProcedure,
                        includeOperativeSteps = includeOperativeSteps,
                        includeMaterials = includeMaterials,
                        includeComplications = includeComplications,
                        includePostOp = includePostOp,
                        includeSignature = includeSignature,
                        includeQrCode = includeQrCode
                    )
                )
            }) { Text(stringResource(R.string.patient_detail_generate)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun PatientChartOptionsDialog(
    initialOptions: com.example.utils.PatientChartPdfOptions,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, com.example.utils.PatientChartPdfOptions) -> Unit
) {
    var anonymize by remember { mutableStateOf(false) }
    var includeContact by remember { mutableStateOf(initialOptions.includeContact) }
    var includeInsurance by remember { mutableStateOf(initialOptions.includeInsurance) }
    var includeMedicalHistory by remember { mutableStateOf(initialOptions.includeMedicalHistory) }
    var includeAllergies by remember { mutableStateOf(initialOptions.includeAllergies) }
    var includeChronicDiseases by remember { mutableStateOf(initialOptions.includeChronicDiseases) }
    var includePreviousSurgeries by remember { mutableStateOf(initialOptions.includePreviousSurgeries) }
    var includeMedications by remember { mutableStateOf(initialOptions.includeMedications) }
    var includeNotes by remember { mutableStateOf(initialOptions.includeNotes) }
    var includeOrthopedicInfo by remember { mutableStateOf(initialOptions.includeOrthopedicInfo) }
    var includeTimeline by remember { mutableStateOf(initialOptions.includeTimeline) }
    var includeSurgeries by remember { mutableStateOf(initialOptions.includeSurgeries) }
    var includeQrCode by remember { mutableStateOf(initialOptions.includeQrCode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.patient_detail_export_patient_chart)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                ) {
                    androidx.compose.material3.Checkbox(checked = anonymize, onCheckedChange = { anonymize = it })
                    Text(
                        stringResource(R.string.patient_detail_anonymize_patient_name_contact_insurance),
                        modifier = Modifier.clickable { anonymize = !anonymize }
                    )
                }

                androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.patient_detail_include_in_pdf), style = MaterialTheme.typography.labelMedium)
                PdfOptionRow(stringResource(R.string.pdf_option_contact), includeContact, enabled = !anonymize) { includeContact = it }
                PdfOptionRow(stringResource(R.string.patient_detail_insurance), includeInsurance, enabled = !anonymize) { includeInsurance = it }
                PdfOptionRow(stringResource(R.string.patient_detail_medical_history), includeMedicalHistory) { includeMedicalHistory = it }
                PdfOptionRow(stringResource(R.string.patient_edit_allergies), includeAllergies) { includeAllergies = it }
                PdfOptionRow(stringResource(R.string.patient_edit_chronic_diseases), includeChronicDiseases) { includeChronicDiseases = it }
                PdfOptionRow(stringResource(R.string.patient_edit_previous_surgeries), includePreviousSurgeries) { includePreviousSurgeries = it }
                PdfOptionRow(stringResource(R.string.patient_edit_medications), includeMedications) { includeMedications = it }
                PdfOptionRow(stringResource(R.string.patient_edit_notes), includeNotes) { includeNotes = it }
                PdfOptionRow(stringResource(R.string.patient_edit_section_orthopedic), includeOrthopedicInfo) { includeOrthopedicInfo = it }
                PdfOptionRow(stringResource(R.string.patient_detail_timeline), includeTimeline) { includeTimeline = it }
                PdfOptionRow(stringResource(R.string.patient_detail_surgeries), includeSurgeries) { includeSurgeries = it }
                PdfOptionRow(stringResource(R.string.pdf_option_qr_code), includeQrCode, enabled = !anonymize) { includeQrCode = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    anonymize,
                    com.example.utils.PatientChartPdfOptions(
                        includeContact = includeContact,
                        includeInsurance = includeInsurance,
                        includeMedicalHistory = includeMedicalHistory,
                        includeAllergies = includeAllergies,
                        includeChronicDiseases = includeChronicDiseases,
                        includePreviousSurgeries = includePreviousSurgeries,
                        includeMedications = includeMedications,
                        includeNotes = includeNotes,
                        includeOrthopedicInfo = includeOrthopedicInfo,
                        includeTimeline = includeTimeline,
                        includeSurgeries = includeSurgeries,
                        includeQrCode = includeQrCode
                    )
                )
            }) { Text(stringResource(R.string.patient_detail_generate)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun PdfOptionRow(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    // The whole row is the target (and one TalkBack item), not just the small box.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked && enabled,
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Checkbox,
                onValueChange = onCheckedChange
            )
    ) {
        androidx.compose.material3.Checkbox(checked = checked && enabled, onCheckedChange = null, enabled = enabled)
        Text(
            text = label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun PatientHeader(detail: PatientDetail) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = detail.patient.patientCode,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            val ageText = detail.age?.let { stringResource(R.string.patient_age_years, it) }
            val genderText = PatientValueLabels.gender(detail.patient.gender)
            val ageGender = listOfNotNull(ageText, genderText.takeIf { it.isNotBlank() }).joinToString(" • ")
            if (ageGender.isNotBlank()) {
                Text(text = ageGender, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (detail.patient.diagnosis.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = detail.patient.diagnosis)
        }
        if (detail.patient.bodyRegion.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RegionBadge(region = PatientValueLabels.bodyRegion(detail.patient.bodyRegion))
                if (detail.patient.side.isNotBlank() && detail.patient.side != "NOT_APPLICABLE") {
                    RegionBadge(region = PatientValueLabels.side(detail.patient.side))
                }
            }
        }
    }
}

@Composable
private fun InfoTab(detail: PatientDetail) {
    val p = detail.patient
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Renders nothing unless Telegram Drive is set up, so this screen is
        // unchanged for anyone not using it.
        item { PatientDriveStatusCard(patientId = p.id) }
        item {
            PatientInfoGrid(
                title = stringResource(R.string.patient_detail_contact),
                icon = Icons.Default.Person,
                items = listOf(
                    InfoItem(stringResource(R.string.patient_edit_date_of_birth), DateFormat.formatDate(p.dateOfBirth)),
                    InfoItem(stringResource(R.string.patient_edit_phone), p.phone, InfoAction.PHONE),
                    InfoItem(stringResource(R.string.patient_edit_email), p.email, InfoAction.EMAIL),
                    InfoItem(stringResource(R.string.patient_edit_address), p.address, InfoAction.ADDRESS, fullWidth = true),
                    InfoItem(stringResource(R.string.patient_edit_emergency_contact_name), p.emergencyContactName),
                    InfoItem(stringResource(R.string.patient_edit_emergency_contact_phone), p.emergencyContactPhone, InfoAction.PHONE)
                )
            )
        }
        item {
            PatientInfoGrid(
                title = stringResource(R.string.patient_detail_insurance),
                icon = Icons.Default.Shield,
                items = listOf(
                    InfoItem(stringResource(R.string.patient_edit_insurance_provider), p.insuranceProvider),
                    InfoItem(stringResource(R.string.patient_edit_policy_number), p.insuranceNumber)
                )
            )
        }
        item {
            PatientInfoGrid(
                title = stringResource(R.string.patient_detail_medical_history),
                icon = Icons.Default.MedicalInformation,
                items = listOf(
                    InfoItem(stringResource(R.string.patient_edit_allergies), p.allergies),
                    InfoItem(stringResource(R.string.patient_edit_chronic_diseases), p.chronicDiseases),
                    InfoItem(stringResource(R.string.patient_edit_medications), p.medications),
                    InfoItem(stringResource(R.string.patient_edit_previous_surgeries), p.previousSurgeries),
                    InfoItem(stringResource(R.string.patient_edit_medical_history), p.medicalHistory, fullWidth = true),
                    InfoItem(stringResource(R.string.patient_edit_notes), p.generalNotes, fullWidth = true)
                )
            )
        }
        item {
            PatientInfoGrid(
                title = stringResource(R.string.patient_detail_orthopedic),
                icon = Icons.Default.Accessibility,
                items = listOf(
                    InfoItem(stringResource(R.string.patient_edit_diagnosis), p.diagnosis, fullWidth = true),
                    InfoItem(stringResource(R.string.patient_edit_body_region), PatientValueLabels.bodyRegion(p.bodyRegion)),
                    InfoItem(stringResource(R.string.patient_edit_side), if (p.side == "NOT_APPLICABLE") "" else PatientValueLabels.side(p.side)),
                    InfoItem(stringResource(R.string.patient_edit_injury_date), DateFormat.formatDate(p.injuryDate)),
                    InfoItem(stringResource(R.string.patient_edit_trauma_mechanism), p.traumaMechanism),
                    InfoItem(stringResource(R.string.patient_edit_fracture_classification), p.fractureClassification, fullWidth = true),
                    InfoItem(stringResource(R.string.patient_edit_imaging_findings), p.imagingFindings, fullWidth = true),
                    InfoItem(stringResource(R.string.patient_edit_treatment_plan), p.treatmentPlan, fullWidth = true)
                )
            )
        }
    }
}

@Composable
private fun TimelineTab(
    detail: PatientDetail,
    onEventClick: (String) -> Unit,
    onEventDelete: (String) -> Unit
) {
    val timeline = detail.timeline
    if (timeline.all.isEmpty()) {
        EmptyStateView(
            icon = Icons.Default.Add,
            title = stringResource(R.string.patient_detail_no_timeline_events),
            message = stringResource(R.string.patient_detail_tap_to_log_a_consultation),
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (timeline.future.isNotEmpty()) {
            item { TimelineSectionLabel(stringResource(R.string.patient_detail_timeline_upcoming)) }
            items(timeline.future, key = { it.id }) { TimelineEventRow(it, onClick = { onEventClick(it.id) }, onDelete = { onEventDelete(it.id) }) }
        }
        if (timeline.current.isNotEmpty()) {
            item { TimelineSectionLabel(stringResource(R.string.patient_detail_timeline_today)) }
            items(timeline.current, key = { it.id }) { TimelineEventRow(it, onClick = { onEventClick(it.id) }, onDelete = { onEventDelete(it.id) }) }
        }
        if (timeline.past.isNotEmpty()) {
            item { TimelineSectionLabel(stringResource(R.string.patient_detail_timeline_previous)) }
            items(timeline.past, key = { it.id }) { TimelineEventRow(it, onClick = { onEventClick(it.id) }, onDelete = { onEventDelete(it.id) }) }
        }
    }
}

@Composable
private fun TimelineSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun SurgeriesTab(
    detail: PatientDetail,
    onSurgeryClick: (String) -> Unit,
    onSurgeryDelete: (String) -> Unit,
    onGenerateProtocol: (String) -> Unit
) {
    if (detail.surgeries.isEmpty()) {
        EmptyStateView(
            icon = Icons.Default.Add,
            title = stringResource(R.string.patient_detail_no_surgeries_recorded),
            message = stringResource(R.string.patient_detail_tap_to_add_a_surgery),
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(detail.surgeries, key = { it.id }) { surgery ->
            SurgeryRow(
                surgery = surgery,
                onClick = { onSurgeryClick(surgery.id) },
                onDelete = { onSurgeryDelete(surgery.id) },
                onGenerateProtocol = { onGenerateProtocol(surgery.id) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTab(
    detail: PatientDetail,
    viewModel: PatientDetailViewModel,
    onAddMedia: () -> Unit,
    onImageClick: (String) -> Unit,
    onVideoClick: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedIds by rememberSaveable(
        saver = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.MutableState<Set<String>>, String>(
            save = { it.value.toList() },
            restore = { mutableStateOf(it.toSet()) }
        )
    ) { mutableStateOf<Set<String>>(emptySet()) }
    // Back clears a media selection before it leaves the patient.
    androidx.activity.compose.BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptySet() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMoveMenu by remember { mutableStateOf(false) }
    var pendingGallerySaveIds by remember { mutableStateOf<Set<String>?>(null) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val ids = pendingGallerySaveIds
        pendingGallerySaveIds = null
        if (granted && ids != null) {
            scope.launch { saveImagesToGallery(context, detail, ids) }
        }
    }

    fun requestSaveToGallery(ids: Set<String>) {
        val needsLegacyPermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsLegacyPermission) {
            pendingGallerySaveIds = ids
            permissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            scope.launch { saveImagesToGallery(context, detail, ids) }
        }
    }

    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val radiologyLabel = stringResource(R.string.media_category_radiology)
    val clinicalLabel = stringResource(R.string.media_category_clinical)
    val otherLabel = stringResource(R.string.media_category_other)
    val shareTitle = stringResource(R.string.patient_detail_share)
    // Filtered and sorted once per change, not on every recomposition of the grid.
    val sections = remember(detail.images, radiologyLabel) {
        listOf(
            radiologyLabel to detail.images.filter { it.category == com.example.data.local.entity.ImageCategory.RADIOLOGY.name },
            clinicalLabel to detail.images.filter { it.category == com.example.data.local.entity.ImageCategory.CLINICAL_PHOTO.name },
            otherLabel to detail.images.filter {
                it.category != com.example.data.local.entity.ImageCategory.RADIOLOGY.name &&
                    it.category != com.example.data.local.entity.ImageCategory.CLINICAL_PHOTO.name
            }
        ).map { (label, images) -> label to images.sortedBy { it.capturedAt } }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (detail.images.isEmpty() && detail.documents.isEmpty()) {
            EmptyStateView(
                icon = Icons.Default.Image,
                title = stringResource(R.string.patient_detail_no_media),
                message = stringResource(R.string.patient_detail_no_media_message),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                if (selectedIds.isNotEmpty()) {
                    MediaSelectionBar(
                        count = selectedIds.size,
                        onClose = { selectedIds = emptySet() },
                        onShare = {
                            val chosen = detail.images.filter { it.id in selectedIds }
                            val hasVideo = chosen.any { it.mediaType == MediaType.VIDEO.name }
                            val hasImage = chosen.any { it.mediaType != MediaType.VIDEO.name }
                            val mime = when {
                                hasVideo && hasImage -> "*/*"
                                hasVideo -> "video/*"
                                else -> "image/*"
                            }
                            com.example.utils.ShareHelper.shareFiles(
                                context,
                                chosen.map { java.io.File(it.filePath) },
                                mime,
                                shareTitle
                            )
                        },
                        onSave = { requestSaveToGallery(selectedIds) },
                        onMove = { showMoveMenu = true },
                        onDelete = { showDeleteConfirm = true }
                    )
                }

                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                    contentPadding = PaddingValues(16.dp, top = 16.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    sections.forEach { (label, images) ->
                        if (images.isNotEmpty()) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                                )
                            }
                            gridItems(images, key = { it.id }) { image ->
                                val isSelected = image.id in selectedIds
                                val isVideo = image.mediaType == MediaType.VIDEO.name
                                Column {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(110.dp)
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                        .then(
                                            if (isSelected) {
                                                Modifier.border(3.dp, MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .combinedClickable(
                                            onClick = {
                                                if (selectedIds.isNotEmpty()) {
                                                    selectedIds = if (isSelected) selectedIds - image.id else selectedIds + image.id
                                                } else if (isVideo) {
                                                    // Annotating a video makes no sense; play it instead.
                                                    onVideoClick(image.id)
                                                } else {
                                                    onImageClick(image.id)
                                                }
                                            },
                                            onLongClick = {
                                                selectedIds = if (isSelected) selectedIds - image.id else selectedIds + image.id
                                            }
                                        )
                                ) {
                                    coil.compose.AsyncImage(
                                        model = "file://${image.thumbnailPath ?: image.filePath}",
                                        contentDescription = image.category,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (isVideo) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.Center)
                                                .background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape)
                                                .padding(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = stringResource(R.string.media_video),
                                                tint = Color.White
                                            )
                                        }
                                        if (image.durationMs > 0) {
                                            Text(
                                                text = formatDuration(image.durationMs),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White,
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(4.dp)
                                                    .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 4.dp)
                                            )
                                        }
                                    }
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                                        )
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = stringResource(R.string.media_selected),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                                        )
                                    }
                                }
                                // When it was taken, from the file's own metadata.
                                Text(
                                    text = MediaMetadata.formatDate(image.capturedAt, locale),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.padding(top = 3.dp, start = 2.dp)
                                )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (selectedIds.isEmpty()) {
            FloatingActionButton(
                onClick = onAddMedia,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.patient_detail_add_media))
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(PatientValueLabels.plural(R.plurals.media_delete_items_title, selectedIds.size)) },
            text = { Text(stringResource(R.string.action_cannot_be_undone)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteImages(selectedIds)
                    selectedIds = emptySet()
                    showDeleteConfirm = false
                }) { Text(stringResource(R.string.patient_detail_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    if (showMoveMenu) {
        AlertDialog(
            onDismissRequest = { showMoveMenu = false },
            title = { Text(stringResource(R.string.patient_detail_move_to_section)) },
            text = {
                Column {
                    listOf(
                        radiologyLabel to com.example.data.local.entity.ImageCategory.RADIOLOGY,
                        clinicalLabel to com.example.data.local.entity.ImageCategory.CLINICAL_PHOTO,
                        otherLabel to com.example.data.local.entity.ImageCategory.OTHER
                    ).forEach { (label, category) ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updateImageCategory(selectedIds, category)
                                    selectedIds = emptySet()
                                    showMoveMenu = false
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMoveMenu = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun MediaSelectionBar(
    count: Int,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.patient_detail_clear_selection))
        }
        Text(
            text = PatientValueLabels.plural(R.plurals.media_selected_count, count),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.patient_detail_share)) }
        IconButton(onClick = onSave) { Icon(Icons.Default.Download, contentDescription = stringResource(R.string.patient_detail_save_to_gallery)) }
        IconButton(onClick = onMove) { Icon(Icons.Default.DriveFileMove, contentDescription = stringResource(R.string.patient_detail_move_to_section)) }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.patient_detail_delete), tint = MaterialTheme.colorScheme.error) }
    }
}

/** Tab labels stay on one line: a long translation used to wrap to a stray letter. */
@Composable
private fun TabLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis
    )
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** Copies run on the IO dispatcher: several full-size images froze the UI on the main thread. */
private suspend fun saveImagesToGallery(context: android.content.Context, detail: PatientDetail, ids: Set<String>) {
    val images = detail.images.filter { it.id in ids }
    val successCount = withContext(Dispatchers.IO) {
        images.count { image ->
            com.example.utils.GallerySaver.saveToGallery(context, java.io.File(image.filePath), "IMG_${image.id}.jpg") is com.example.utils.GallerySaver.Result.Success
        }
    }
    val message = if (successCount == images.size) {
        context.resources.getQuantityString(R.plurals.media_saved_to_gallery, successCount, successCount)
    } else {
        context.getString(R.string.media_saved_partial_to_gallery, successCount, images.size)
    }
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}
