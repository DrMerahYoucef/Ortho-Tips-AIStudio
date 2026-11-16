package com.example.ui.screens.classifications

import com.example.ui.components.PatientValueLabels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.model.ChapterRef
import com.example.data.local.model.ClassificationBlock
import com.example.data.local.model.ClassificationChapter
import com.example.data.local.model.ClassificationEntry
import com.example.data.repository.ClassificationRepository
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.FilledTonalIconButton
import com.example.ui.components.ClassificationContentView
import com.example.ui.components.OrthoSearchBar
import com.example.viewmodel.ClassificationViewModel
import com.example.R
import androidx.compose.ui.res.stringResource

/**
 * Level 1: list of classification chapters from the installed classification extensions.
 * Meant to be embedded as tab content (e.g. Home > Classifications) — no Scaffold/back button.
 */
@Composable
fun ClassificationChaptersScreen(
    viewModel: ClassificationViewModel,
    onOpenChapter: (ChapterRef) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the classification editor; the button is hidden when null. */
    onOpenEditor: (() -> Unit)? = null
) {
    LaunchedEffect(Unit) { viewModel.loadIndex() }
    var searchQuery by remember { mutableStateOf("") }
    val filteredChapters = viewModel.chapters.filter { chapter ->
        searchQuery.isBlank() || chapter.title.contains(searchQuery, ignoreCase = true)
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            viewModel.isLoading && viewModel.chapters.isEmpty() -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            viewModel.errorMessage != null && viewModel.chapters.isEmpty() -> {
                Text(
                    text = viewModel.errorMessage ?: "Something went wrong.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        OrthoSearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onSearch = { searchQuery = it },
                            onClear = { searchQuery = "" },
                            placeholderText = stringResource(R.string.classif_search_placeholder),
                            modifier = Modifier.weight(1f)
                        )
                        if (onOpenEditor != null) {
                            FilledTonalIconButton(
                                onClick = onOpenEditor,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .testTag("classification_editor_open")
                            ) {
                                Icon(Icons.Filled.EditNote, contentDescription = stringResource(R.string.ced_open_editor))
                            }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("classification_chapters_list"),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredChapters, key = { it.id }) { chapter ->
                            ChapterRefCard(chapter = chapter, onClick = { onOpenChapter(chapter) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterRefCard(chapter: ChapterRef, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("classification_chapter_card_${chapter.id}")
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .width(44.dp)
                    .height(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.MedicalInformation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = PatientValueLabels.plural(R.plurals.classification_count, chapter.classificationCount) +
                        " • " + PatientValueLabels.plural(R.plurals.home_sections_count, chapter.sectionCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/**
 * Level 2/3 combined: a chapter's sections and their classification entries, as a standalone
 * navigation destination (has its own Scaffold + back button). Loads [chapterFile] into
 * [viewModel] itself, so callers only need to pass the file path from a [ChapterRef].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassificationChapterDetailRoute(
    viewModel: ClassificationViewModel,
    repository: ClassificationRepository,
    chapterFile: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(chapterFile) { viewModel.openChapter(chapterFile) }
    val chapter = viewModel.currentChapter

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = chapter?.title ?: "Classifications",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("classification_detail_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                viewModel.isLoading && chapter == null -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                viewModel.errorMessage != null && chapter == null -> {
                    Text(
                        text = viewModel.errorMessage ?: "Something went wrong.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )
                }
                chapter != null -> {
                    ClassificationChapterDetailScreen(chapter = chapter, repository = repository)
                }
            }
        }
    }
}

@Composable
fun ClassificationChapterDetailScreen(
    chapter: ClassificationChapter,
    repository: ClassificationRepository,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("classification_chapter_detail"),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            OrthoSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { searchQuery = it },
                onClear = { searchQuery = "" },
                placeholderText = stringResource(R.string.classif_search_chapter_placeholder),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        chapter.sections.forEach { section ->
            val filteredClassifications = section.classifications.filter { entry ->
                searchQuery.isBlank() || entry.title.contains(searchQuery, ignoreCase = true)
            }
            if (filteredClassifications.isEmpty()) return@forEach
            item {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 18.dp, bottom = 10.dp)
                )
            }
            items(filteredClassifications, key = { it.id }) { entry ->
                ClassificationEntryCard(
                    entry = entry,
                    repository = repository,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun ClassificationEntryCard(
    entry: ClassificationEntry,
    repository: ClassificationRepository,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("classification_entry_${entry.id}")
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Editor content may start without a classification title.
            if (entry.title.isNotBlank()) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider(
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                )
            }

            entry.images.forEach { path ->
                val bitmap = remember(path) { repository.loadImage(path) }
                bitmap?.let {
                    ClassificationImage(bitmap = it, description = entry.title)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entry.blocks.forEach { block ->
                    ClassificationBlockView(block)
                }
                if (entry.content.isNotEmpty()) {
                    ClassificationContentView(paragraphs = entry.content, loadImage = repository::loadImage)
                }
            }
        }
    }
}

@Composable
private fun ClassificationBlockView(block: ClassificationBlock) {
    when (block.type) {
        "text" -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    RoundedCornerShape(10.dp)
                )
                .padding(12.dp)
        ) {
            block.lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }

        "item" -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(34.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
            )
            Column(modifier = Modifier.weight(1f)) {
                if (!block.label.isNullOrBlank()) {
                    Text(
                        text = block.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                block.text?.takeIf { it.isNotBlank() }?.let { text ->
                    Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                }
                block.bullets.forEach { bullet ->
                    Text(
                        text = "•  $bullet",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp, top = 3.dp)
                    )
                }
                block.sub.forEach { sub ->
                    Text(
                        text = sub.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 12.dp, top = 7.dp)
                    )
                    sub.bullets.forEach { bullet ->
                        Text(
                            text = "–  $bullet",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 24.dp, top = 2.dp)
                        )
                    }
                }
            }
        }

        "table" -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .padding(vertical = 8.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            Row(modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer)) {
                block.headers.forEach { header ->
                    Text(
                        text = header,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .width(150.dp)
                            .padding(horizontal = 8.dp, vertical = 9.dp)
                    )
                }
            }
            block.rows.forEach { row ->
                Row(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    row.forEach { cell ->
                        Text(
                            text = cell,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .width(150.dp)
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        else -> Unit
    }
}

@Composable
private fun ClassificationImage(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    description: String
) {
    var showViewer by remember { mutableStateOf(false) }

    Image(
        bitmap = bitmap,
        contentDescription = "$description, toucher pour agrandir",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .padding(vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .clickable { showViewer = true }
    )

    if (showViewer) {
        Dialog(
            onDismissRequest = { showViewer = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                ZoomableClassificationImage(bitmap = bitmap, description = description)
                IconButton(
                    onClick = { showViewer = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.classifications_fermer), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
}

@Composable
private fun ZoomableClassificationImage(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    description: String
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Image(
        bitmap = bitmap,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
    )
}
