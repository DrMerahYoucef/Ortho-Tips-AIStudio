package com.example.ui.screens.favorites

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.NoteEntity
import com.example.ui.components.BoneRow
import com.example.ui.components.EmptyStateView
import com.example.ui.components.NoteDialog
import com.example.viewmodel.SavedTab
import com.example.viewmodel.SavedViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.R
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    viewModel: SavedViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToBoneDetail: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isAddNoteDialogOpen = uiState.isAddNoteDialogOpen
    val noteToEdit = uiState.noteToEdit

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

    if (isAddNoteDialogOpen) {
        NoteDialog(
            initialNote = noteToEdit,
            onDismiss = { viewModel.closeNoteDialog() },
            onSave = { note -> viewModel.saveNote(note) }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.favorites_saved_clinical_notes),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = (-0.3).sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
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
            if (uiState.currentTab == SavedTab.NOTES) {
                FloatingActionButton(
                    onClick = { viewModel.openAddNoteDialog() },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.testTag("add_standalone_note_fab")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.favorites_new_case_note)
                    )
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(
                selectedTabIndex = if (uiState.currentTab == SavedTab.BOOKMARKS) 0 else 1,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = uiState.currentTab == SavedTab.BOOKMARKS,
                    onClick = { viewModel.selectTab(SavedTab.BOOKMARKS) },
                    text = { Text(text = stringResource(R.string.favorites_tab_bookmarks, uiState.bookmarks.size)) }
                )
                Tab(
                    selected = uiState.currentTab == SavedTab.NOTES,
                    onClick = { viewModel.selectTab(SavedTab.NOTES) },
                    text = { Text(text = stringResource(R.string.favorites_tab_notes, uiState.notes.size)) }
                )
            }

            when (uiState.currentTab) {
                SavedTab.BOOKMARKS -> {
                    if (uiState.bookmarks.isEmpty()) {
                        EmptyStateView(
                            icon = Icons.Default.BookmarkBorder,
                            title = stringResource(R.string.favorites_no_bookmarks_yet),
                            message = stringResource(R.string.favorites_save_high_yield_fracture_guides)
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("bookmarked_bones_list")
                        ) {
                            items(uiState.bookmarks, key = { it.id }) { bone ->
                                BoneRow(
                                    bone = bone,
                                    onClick = { onNavigateToBoneDetail(bone.id) },
                                    onBookmarkToggle = { viewModel.toggleBookmark(bone.id) }
                                )
                            }
                        }
                    }
                }
                SavedTab.NOTES -> {
                    if (uiState.notes.isEmpty()) {
                        EmptyStateView(
                            icon = Icons.Default.Description,
                            title = stringResource(R.string.favorites_no_case_notes_recorded),
                            message = stringResource(R.string.favorites_tap_the_button_below_to)
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("saved_notes_list")
                        ) {
                            items(uiState.notes, key = { it.id }) { note ->
                                NoteListItem(
                                    note = note,
                                    onEdit = { viewModel.openEditNoteDialog(note) },
                                    onDelete = { noteToDelete = note.id },
                                    onBoneClick = { boneId ->
                                        if (boneId != null) onNavigateToBoneDetail(boneId)
                                    }
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
private fun NoteListItem(
    note: NoteEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBoneClick: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val tagColor = when (note.colorTag) {
        "amber" -> MaterialTheme.colorScheme.secondary
        "blue" -> MaterialTheme.colorScheme.tertiary
        "purple" -> MaterialTheme.colorScheme.primaryContainer
        "crimson" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    val dateFormatter = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
    val formattedDate = dateFormatter.format(Date(note.updatedAt))

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .testTag("note_card_${note.id}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = tagColor,
                        modifier = Modifier.size(10.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = note.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row {
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

            if (note.content.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 10.sp
                )

                if (note.boneId != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.clickable { onBoneClick(note.boneId) }
                    ) {
                        Text(
                            text = stringResource(R.string.favorites_linked_procedure),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}
