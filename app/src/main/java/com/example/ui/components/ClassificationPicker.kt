package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MedicalInformation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.model.ChapterRef
import com.example.data.local.model.ClassificationBlock
import com.example.data.local.model.ClassificationChapter
import com.example.data.local.model.ClassificationEntry
import com.example.data.classification.ClassificationDocument
import com.example.data.repository.ClassificationRepository
import com.example.ui.components.OrthoSearchBar
import com.example.R
import androidx.compose.ui.res.stringResource

private data class ClassificationChoice(
    val label: String,
    val detail: String? = null
)

@Composable
fun ClassificationPickerButton(
    repository: ClassificationRepository,
    onClassificationSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { showPicker = true },
        modifier = modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text(stringResource(R.string.classification_picker_add_classification), modifier = Modifier.padding(start = 8.dp))
    }

    if (showPicker) {
        ClassificationPickerDialog(
            repository = repository,
            onDismiss = { showPicker = false },
            onClassificationSelected = {
                onClassificationSelected(it)
                showPicker = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClassificationPickerDialog(
    repository: ClassificationRepository,
    onDismiss: () -> Unit,
    onClassificationSelected: (String) -> Unit
) {
    var chapters by remember { mutableStateOf<List<ChapterRef>>(emptyList()) }
    var selectedChapter by remember { mutableStateOf<ClassificationChapter?>(null) }
    var selectedChapterRef by remember { mutableStateOf<ChapterRef?>(null) }
    var selectedPhrase by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val loadFailedText = stringResource(R.string.classification_picker_load_failed)
    val chapterFailedText = stringResource(R.string.classification_picker_chapter_load_failed)

    LaunchedEffect(Unit) {
        try {
            chapters = repository.getIndex().chapters
        } catch (error: Exception) {
            errorMessage = error.message ?: loadFailedText
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(selectedChapterRef) {
        val chapterRef = selectedChapterRef ?: return@LaunchedEffect
        isLoading = true
        errorMessage = null
        try {
            selectedChapter = repository.getChapter(chapterRef.file)
        } catch (error: Exception) {
            selectedChapter = null
            errorMessage = error.message ?: chapterFailedText
        } finally {
            isLoading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            selectedChapter?.title ?: stringResource(R.string.classification_picker_choose),
                            maxLines = 2
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (selectedChapterRef != null) {
                                    selectedChapterRef = null
                                    selectedChapter = null
                                    selectedPhrase = null
                                    searchQuery = ""
                                } else onDismiss()
                            }
                        ) {
                            Icon(
                                imageVector = if (selectedChapter == null) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(if (selectedChapter == null) R.string.action_close else R.string.action_back)
                            )
                        }
                    },
                    actions = {
                        if (selectedChapter != null && selectedPhrase != null) {
                            IconButton(
                                onClick = { onClassificationSelected(selectedPhrase!!) }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.classification_picker_confirm_selection)
                                )
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            when {
                isLoading -> Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                errorMessage != null -> Text(
                    text = errorMessage ?: stringResource(R.string.classification_picker_load_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(paddingValues).padding(24.dp)
                )

                selectedChapter == null -> ChapterChoices(
                    chapters = chapters,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    paddingValues = paddingValues,
                    onChapterSelected = { chapter ->
                        searchQuery = ""
                        selectedChapterRef = chapter
                    }
                )

                else -> ClassificationChoices(
                    chapter = selectedChapter!!,
                    paddingValues = paddingValues,
                    selectedPhrase = selectedPhrase,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onChoiceSelected = { selectedPhrase = it }
                )
            }
        }
    }
}

@Composable
private fun ChapterChoices(
    chapters: List<ChapterRef>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    paddingValues: PaddingValues,
    onChapterSelected: (ChapterRef) -> Unit
) {
    val filteredChapters = chapters.filter { chapter ->
        searchQuery.isBlank() || chapter.title.contains(searchQuery, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
        OrthoSearchBar(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            onSearch = onSearchQueryChange,
            onClear = { onSearchQueryChange("") },
            placeholderText = stringResource(R.string.classification_picker_search_all),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn(
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
        ) {
            items(filteredChapters, key = { it.id }) { chapter ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onChapterSelected(chapter) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.MedicalInformation, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(chapter.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "${chapter.classificationCount} classifications",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClassificationChoices(
    chapter: ClassificationChapter,
    paddingValues: PaddingValues,
    selectedPhrase: String?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onChoiceSelected: (String) -> Unit
) {
    val filteredSections = chapter.sections.mapNotNull { section ->
        val classifications = section.classifications.filter { entry ->
            searchQuery.isBlank() || entry.title.contains(searchQuery, ignoreCase = true) ||
                choicesForEntry(entry).any { choice -> choice.label.contains(searchQuery, ignoreCase = true) }
        }
        classifications.takeIf { it.isNotEmpty() }?.let { section.copy(classifications = it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        OrthoSearchBar(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            onSearch = onSearchQueryChange,
            onClear = { onSearchQueryChange("") },
            placeholderText = stringResource(R.string.classification_picker_search_chapter),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn(
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            filteredSections.forEach { section ->
                item {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }

                items(section.classifications, key = { it.id }) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            )
                            choicesForEntry(entry).forEach { choice ->
                                val classificationName = classificationName(entry.title)
                                val phrase = "${choice.label} selon la classification de $classificationName"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onChoiceSelected(phrase) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedPhrase == phrase,
                                        onClick = { onChoiceSelected(phrase) }
                                    )
                                    Text(
                                        text = phrase,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun choicesForEntry(entry: ClassificationEntry): List<ClassificationChoice> {
    val choices = buildList {
        entry.blocks.forEach { block ->
            when (block.type) {
                "item" -> {
                    block.label?.takeIf { it.isNotBlank() }?.let { add(ClassificationChoice(it, block.text)) }
                    block.sub.forEach { add(ClassificationChoice(it.label)) }
                }
                "table" -> block.rows.forEach { row ->
                    row.firstOrNull()?.takeIf { it.isNotBlank() }?.let { add(ClassificationChoice(it)) }
                }
            }
        }
        // Content written with the classification editor.
        ClassificationDocument.pickerChoices(entry.content).forEach { (label, detail) ->
            add(ClassificationChoice(label, detail))
        }
    }
    return choices.distinctBy { it.label }.ifEmpty { listOf(ClassificationChoice(entry.title)) }
}

private fun classificationName(title: String): String {
    val normalized = title
        .replace(Regex("(?i)^la classification de "), "")
        .replace(Regex("(?i)^classification de "), "")
        .replace(Regex("(?i)^les classifications radiologiques de "), "")
        .replace(Regex("(?i)^cotation fonctionnelle de "), "")
        .substringBefore("(")
        .trim()
    return normalized.ifBlank { title }
}
