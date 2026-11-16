package com.example.ui.screens.bone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.remember
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.ui.components.ConfirmDeleteDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.NoteEntity
import com.example.ui.components.FigureStrip
import com.example.ui.components.HighYieldChip
import com.example.ui.components.NoteDialog
import com.example.ui.components.PageSectionCard
import com.example.ui.components.RegionBadge
import com.example.utils.MediaActions
import com.example.viewmodel.BoneDetailViewModel
import com.example.viewmodel.SectionFilter
import com.example.R
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BoneDetailScreen(
    viewModel: BoneDetailViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToImageViewer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isNoteDialogOpen by viewModel.isNoteDialogOpen.collectAsStateWithLifecycle()
    val noteToEdit by viewModel.noteToEdit.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val keyPearlsLabel = stringResource(R.string.bone_detail_key_pearls)

    // Deleting a note asks first; it used to go on a single tap of the trash icon.
    var noteToDelete by rememberSaveable { mutableStateOf<String?>(null) }
    noteToDelete?.let { id ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.confirm_delete_note_title),
            message = stringResource(R.string.action_cannot_be_undone),
            onConfirm = { viewModel.deleteNote(id); noteToDelete = null },
            onDismiss = { noteToDelete = null }
        )
    }

    val detail = uiState.detail

    if (isNoteDialogOpen) {
        NoteDialog(
            initialNote = noteToEdit,
            boneId = detail?.bone?.id,
            onDismiss = { viewModel.closeNoteDialog() },
            onSave = { note -> viewModel.saveNote(note) }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = detail?.bone?.name ?: stringResource(R.string.bone_detail_procedure_technique),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("bone_detail_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (detail != null) {
                                val summary = "${detail.bone.name}\n${detail.bone.subtitle}\n\n$keyPearlsLabel\n" +
                                        detail.sections.joinToString("\n• ") { it.title + ": " + it.pearls.ifBlank { it.content.take(100) } }
                                MediaActions.shareClinicalTip(context, detail.bone.name, summary)
                            }
                        },
                        modifier = Modifier.testTag("share_procedure_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.bone_detail_share_technique)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.toggleBookmark() },
                        modifier = Modifier.testTag("detail_bookmark_toggle")
                    ) {
                        Icon(
                            imageVector = if (detail?.isBookmarked == true) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = stringResource(R.string.bone_detail_bookmark),
                            tint = if (detail?.isBookmarked == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.openAddNoteDialog() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag("add_case_note_fab")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.bone_detail_add_surgeon_note)
                )
            }
        },
        modifier = modifier
    ) { paddingValues ->
        if (uiState.isLoading || detail == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            // Recomputed only when the sections, filter or query change.
            val searchedSections = remember(detail.sections, selectedFilter, searchQuery) {
                val filteredSections = when (selectedFilter) {
                    SectionFilter.ALL -> detail.sections
                    SectionFilter.ANATOMY -> detail.sections.filter { it.sectionType.equals("ANATOMY", true) }
                    SectionFilter.APPROACH -> detail.sections.filter { it.sectionType.equals("APPROACH", true) }
                    SectionFilter.REDUCTION -> detail.sections.filter { it.sectionType.equals("REDUCTION", true) }
                    SectionFilter.FIXATION -> detail.sections.filter { it.sectionType.equals("FIXATION", true) }
                    SectionFilter.PEARLS -> detail.sections.filter { it.sectionType.equals("PEARLS", true) || it.pearls.isNotBlank() }
                    SectionFilter.COMPLICATIONS -> detail.sections.filter { it.sectionType.equals("COMPLICATIONS", true) || it.warnings.isNotBlank() }
                    SectionFilter.REHAB -> detail.sections.filter { it.sectionType.equals("REHAB", true) }
                }

                if (searchQuery.isBlank()) {
                    filteredSections
                } else {
                    filteredSections.filter {
                        it.title.contains(searchQuery, ignoreCase = true) ||
                            it.content.contains(searchQuery, ignoreCase = true)
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .testTag("procedure_detail_scroll"),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RegionBadge(region = detail.bone.region)
                            if (detail.bone.isHighYield) {
                                HighYieldChip()
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = detail.bone.name,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = detail.bone.subtitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )

                        if (detail.bone.classificationSummary.isNotBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Text(
                                    text = stringResource(R.string.bone_detail_classification_prefix, detail.bone.classificationSummary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }

                // Pinned under the app bar, so the filters stay in reach while reading.
                stickyHeader(key = "section_filters") {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 6.dp)
                        ) {
                            items(SectionFilter.entries.toTypedArray()) { filter ->
                                FilterChip(
                                    selected = selectedFilter == filter,
                                    onClick = { viewModel.setSectionFilter(filter) },
                                    label = {
                                        Text(
                                            text = sectionFilterLabel(filter),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    shape = RoundedCornerShape(50),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                        containerColor = MaterialTheme.colorScheme.surface
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selectedFilter == filter,
                                        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                        selectedBorderColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        placeholder = { Text(stringResource(R.string.bone_detail_search_this_bone_s_content)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.common_clear_search),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("bone_search_field")
                    )
                }

                if (detail.images.isNotEmpty()) {
                    item {
                        FigureStrip(
                            images = detail.images,
                            onImageClick = { onNavigateToImageViewer(it.id) },
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }

                if (searchQuery.isNotBlank() && searchedSections.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.bone_detail_no_matches, searchQuery),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                        )
                    }
                }

                items(searchedSections, key = { it.id }) { section ->
                    PageSectionCard(
                        section = section,
                        images = detail.images,
                        searchQuery = searchQuery,
                        onFigureClick = { onNavigateToImageViewer(it.id) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (detail.notes.isNotEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.bone_detail_my_notes_count, detail.notes.size),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            detail.notes.forEach { note ->
                                CaseNoteCard(
                                    note = note,
                                    onEdit = { viewModel.openEditNoteDialog(note) },
                                    onDelete = { noteToDelete = note.id },
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CaseNoteCard(
    note: NoteEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tagColor = when (note.colorTag) {
        "amber" -> MaterialTheme.colorScheme.secondary
        "blue" -> MaterialTheme.colorScheme.tertiary
        "purple" -> MaterialTheme.colorScheme.primaryContainer
        "crimson" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .testTag("case_note_item_${note.id}")
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = CircleShape,
                color = tagColor,
                modifier = Modifier
                    .size(10.dp)
                    .padding(top = 4.dp)
            ) {}

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (note.content.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = note.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }

            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.bone_detail_edit_note),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.bone_detail_delete_note),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun sectionFilterLabel(filter: SectionFilter): String = stringResource(
    when (filter) {
        SectionFilter.ALL -> R.string.section_filter_all
        SectionFilter.ANATOMY -> R.string.section_filter_anatomy
        SectionFilter.APPROACH -> R.string.section_filter_approach
        SectionFilter.REDUCTION -> R.string.section_filter_reduction
        SectionFilter.FIXATION -> R.string.section_filter_fixation
        SectionFilter.PEARLS -> R.string.section_filter_pearls
        SectionFilter.COMPLICATIONS -> R.string.section_filter_complications
        SectionFilter.REHAB -> R.string.section_filter_rehab
    }
)
