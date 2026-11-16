package com.example.ui.screens.patients

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import com.example.viewmodel.PatientListUiState
import com.example.ui.components.ConfirmDeleteDialog
import com.example.ui.components.LibraryDriveSpaceBanner
import com.example.ui.components.OrthoSearchBar
import com.example.ui.components.PatientCard
import com.example.viewmodel.PatientListTab
import com.example.viewmodel.PatientListViewModel
import com.example.R
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientListScreen(
    viewModel: PatientListViewModel,
    onNavigateToPatientDetail: (String) -> Unit,
    onNavigateToAddPatient: () -> Unit,
    onNavigateToSurgeonProfile: () -> Unit,
    onNavigateToDataTransfer: () -> Unit,
    onNavigateToQrScanner: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onExportSelected: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    // Local to the screen: a filter row that hides itself is a view preference,
    // not something the ViewModel or a saved state needs to know about.
    var showRegionFilters by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showBulkDeleteConfirm by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }

    // Back leaves selection mode first instead of leaving the app mid-selection.
    androidx.activity.compose.BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

    if (showBulkDeleteConfirm && uiState.isSelectionMode) {
        val count = uiState.selectedIds.size
        ConfirmDeleteDialog(
            title = com.example.ui.components.PatientValueLabels.plural(R.plurals.confirm_delete_patients_title, count),
            message = stringResource(R.string.confirm_delete_patients_message),
            onConfirm = {
                showBulkDeleteConfirm = false
                viewModel.deleteSelected()
            },
            onDismiss = { showBulkDeleteConfirm = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (uiState.isSelectionMode) {
                        Text(
                            text = stringResource(R.string.patient_list_selected_count, uiState.selectedIds.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.MedicalServices,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.patient_list_patients_cases),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = (-0.2).sp
                                )
                                Text(
                                    text = stringResource(R.string.patient_list_clinical_records_surgical_logs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (uiState.isSelectionMode) {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.patient_list_cancel_selection))
                        }
                    }
                },
                actions = {
                    if (uiState.isSelectionMode) {
                        IconButton(onClick = {
                            onExportSelected(uiState.selectedIds.toList())
                            viewModel.clearSelection()
                        }) {
                            Icon(Icons.Default.CloudUpload, contentDescription = stringResource(R.string.patient_list_export_selected))
                        }
                        if (uiState.tab == PatientListTab.ACTIVE) {
                            IconButton(onClick = { viewModel.archiveSelected() }) {
                                Icon(Icons.Default.Archive, contentDescription = stringResource(R.string.patient_list_archive))
                            }
                        } else {
                            IconButton(onClick = { viewModel.unarchiveSelected() }) {
                                Icon(Icons.Default.Unarchive, contentDescription = stringResource(R.string.patient_list_unarchive))
                            }
                        }
                        IconButton(onClick = { showBulkDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.patient_detail_delete))
                        }
                    } else {
                        IconButton(onClick = onNavigateToSurgeonProfile) {
                            Icon(Icons.Default.Badge, contentDescription = stringResource(R.string.patient_list_surgeon_profile))
                        }
                        IconButton(onClick = onNavigateToDataTransfer) {
                            Icon(Icons.Default.ImportExport, contentDescription = stringResource(R.string.patient_list_import_export_data))
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = stringResource(R.string.nav_settings)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                modifier = Modifier.testTag("patient_list_top_bar")
            )
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = onNavigateToAddPatient,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.patient_list_new_patient), fontWeight = FontWeight.Bold) },
                    modifier = Modifier.testTag("add_patient_fab")
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // The KPI card that used to sit here repeated the tab counts and duplicated
            // the FAB and the QR button; removing it brings the first patient up.
            // Cohort switcher tabs
            PrimaryTabRow(
                selectedTabIndex = uiState.tab.ordinal,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = uiState.tab == PatientListTab.ACTIVE,
                    onClick = { viewModel.setTab(PatientListTab.ACTIVE) },
                    text = {
                        Text(
                            text = stringResource(R.string.patient_list_tab_active, uiState.activeCount),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (uiState.tab == PatientListTab.ACTIVE) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    modifier = Modifier.testTag("tab_patients_active")
                )
                Tab(
                    selected = uiState.tab == PatientListTab.ARCHIVED,
                    onClick = { viewModel.setTab(PatientListTab.ARCHIVED) },
                    text = {
                        Text(
                            text = stringResource(R.string.patient_list_tab_archived, uiState.archivedCount),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (uiState.tab == PatientListTab.ARCHIVED) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    modifier = Modifier.testTag("tab_patients_archived")
                )
            }

            // Search bar & QR Action
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        OrthoSearchBar(
                            query = uiState.searchQuery,
                            onQueryChange = { viewModel.setSearchQuery(it) },
                            onSearch = { viewModel.setSearchQuery(it) },
                            onClear = { viewModel.setSearchQuery("") },
                            placeholderText = stringResource(R.string.patient_list_search_placeholder)
                        )
                    }

                    FilledTonalIconToggleButton(
                        checked = showRegionFilters,
                        onCheckedChange = { showRegionFilters = it },
                        modifier = Modifier.testTag("region_filter_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = stringResource(R.string.patient_filter_region)
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onNavigateToQrScanner,
                        modifier = Modifier.testTag("qr_scanner_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = stringResource(R.string.patient_list_scan_qr)
                        )
                    }
                }

                // Region filters stay collapsed until asked for. A permanently
                // visible scrolling chip row cost a band of vertical space on
                // every visit to show a control used occasionally, and it was the
                // fourth stacked strip of chrome above the first patient.
                val hasActiveRegionFilter = uiState.regionFilter != PatientListUiState.ALL_REGIONS

                // Chips come from the regions actually recorded on patients, shown in the
                // app language, instead of a fixed English list that matched little.
                androidx.compose.animation.AnimatedVisibility(
                    visible = (showRegionFilters || hasActiveRegionFilter) && uiState.regions.isNotEmpty()
                ) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        item(key = "all") {
                            FilterChip(
                                selected = !hasActiveRegionFilter,
                                onClick = { viewModel.setRegionFilter(PatientListUiState.ALL_REGIONS) },
                                label = { Text(stringResource(R.string.patient_list_filter_all)) }
                            )
                        }
                        items(uiState.regions, key = { it }) { region ->
                            FilterChip(
                                selected = uiState.regionFilter.equals(region, ignoreCase = true),
                                onClick = { viewModel.setRegionFilter(region) },
                                label = { Text(region) }
                            )
                        }
                    }
                }
            }

            when {
                uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                uiState.isEmpty -> {
                    ModernPatientEmptyState(
                        isSearching = uiState.searchQuery.isNotBlank() ||
                            uiState.regionFilter != PatientListUiState.ALL_REGIONS,
                        isArchivedTab = uiState.tab == PatientListTab.ARCHIVED,
                        searchQuery = uiState.searchQuery.ifBlank { uiState.regionFilter },
                        onAddPatient = onNavigateToAddPatient
                    )
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 4.dp,
                            bottom = 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("patients_scroll_container")
                    ) {
                        // Renders nothing unless the drive is on and something is
                        // reclaimable, so the list is untouched otherwise.
                        item { LibraryDriveSpaceBanner() }
                        items(uiState.filteredPatients, key = { it.id }) { patient ->
                            PatientCard(
                                patient = patient,
                                isSelected = uiState.selectedIds.contains(patient.id),
                                isSelectionMode = uiState.isSelectionMode,
                                onClick = {
                                    if (uiState.isSelectionMode) {
                                        viewModel.toggleSelection(patient.id)
                                    } else {
                                        onNavigateToPatientDetail(patient.id)
                                    }
                                },
                                onLongClick = { viewModel.toggleSelection(patient.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernPatientEmptyState(
    isSearching: Boolean,
    isArchivedTab: Boolean,
    searchQuery: String,
    onAddPatient: () -> Unit
) {
    if (isSearching) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PersonSearch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.patient_list_no_patients_found),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.patient_list_no_match, searchQuery),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        return
    }

    if (isArchivedTab) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Archive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.patient_list_no_archived_patients),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.patient_list_patients_you_mark_as_archived),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        return
    }

    // Modern Onboarding Guide for New Patient Management
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 24.dp)
    ) {
        item {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(76.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.MedicalServices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.patient_list_clinical_patient_management),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.patient_list_organize_surgical_cases_operative_protocols),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onAddPatient)
                    .testTag("empty_state_register_patient_button")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 13.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.patient_list_register_first_patient),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.patient_list_how_patient_management_works),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.5.sp
                    )

                    WorkflowStep(
                        step = "1",
                        title = stringResource(R.string.patient_list_demographics_orthopedic_diagnosis),
                        description = stringResource(R.string.patient_list_step1_description),
                        icon = Icons.Default.PersonAdd
                    )

                    WorkflowStep(
                        step = "2",
                        title = stringResource(R.string.patient_list_surgical_procedures_implant_logs),
                        description = stringResource(R.string.patient_list_step2_description),
                        icon = Icons.Default.MedicalServices
                    )

                    WorkflowStep(
                        step = "3",
                        title = stringResource(R.string.patient_list_imaging_photos_annotations),
                        description = stringResource(R.string.patient_list_step3_description),
                        icon = Icons.Default.CameraAlt
                    )

                    WorkflowStep(
                        step = "4",
                        title = stringResource(R.string.patient_list_pdf_protocols_anonymized_charts),
                        description = stringResource(R.string.patient_list_step4_description),
                        icon = Icons.Default.Description
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkflowStep(
    step: String,
    title: String,
    description: String,
    icon: ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(34.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )
        }
    }
}
