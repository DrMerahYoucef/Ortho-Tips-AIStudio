package com.example.ui.screens.home

import androidx.compose.foundation.lazy.itemsIndexed
import com.example.ui.components.PatientValueLabels
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.MedicalInformation
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsKabaddi
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.model.ChapterWithCount
import com.example.ui.components.BoneRow
import com.example.ui.screens.classifications.ClassificationChaptersScreen
import com.example.ui.screens.extensions.ExtensionsGrid
import com.example.ui.screens.extensions.label
import com.example.viewmodel.ExtensionsViewModel
import com.example.viewmodel.ClassificationViewModel
import com.example.viewmodel.HomeViewModel
import com.example.data.local.displayDescription
import com.example.data.local.displayTitle
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.example.data.extensions.ExtensionKinds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    classificationViewModel: ClassificationViewModel,
    extensionsViewModel: ExtensionsViewModel,
    onNavigateToChapter: (String, String) -> Unit,
    onNavigateToBoneDetail: (String) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToSaved: () -> Unit,
    onNavigateToImport: () -> Unit,
    onNavigateToClassificationChapter: (String) -> Unit,
    onNavigateToExtension: (String) -> Unit,
    onNavigateToExtensionImport: () -> Unit,
    onNavigateToClassificationEditor: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val extensions by extensionsViewModel.extensions.collectAsStateWithLifecycle()

    // The extension shown inside this screen (the guide or the classifications);
    // null shows the grid. Saved, so coming back from a procedure keeps it open.
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val openedExtension = extensions.firstOrNull { it.kind == opened }
    LaunchedEffect(extensions, opened) {
        // Deleted while open: back to the grid.
        if (opened != null && extensions.isNotEmpty() && openedExtension == null) opened = null
    }
    BackHandler(enabled = opened != null) { opened = null }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (opened != null) {
                        IconButton(onClick = { opened = null }, modifier = Modifier.testTag("extension_back_to_grid")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ext_grid_back))
                        }
                    }
                },
                title = {
                    if (openedExtension != null) {
                        Text(
                            text = openedExtension.icon + "  " + openedExtension.manifest.label(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                    } else Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_ortho_logo),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.nav_extensions),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    letterSpacing = (-0.3).sp
                                )
                                Text(
                                    text = stringResource(R.string.ext_hub_subtitle),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                    }
                },
                // Search, saved items and settings as standard app-bar actions. The
                // strip of three buttons under the title is gone: shifts moved to
                // Agenda, and importing a database lives in Settings.
                actions = {
                    IconButton(onClick = onNavigateToSearch, modifier = Modifier.testTag("home_search_action")) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.common_search))
                    }
                    IconButton(onClick = onNavigateToSaved, modifier = Modifier.testTag("home_saved_top_button")) {
                        Icon(Icons.Outlined.BookmarkBorder, contentDescription = stringResource(R.string.home_saved_and_case_notes))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                modifier = Modifier.testTag("clinical_studies_top_bar")
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
            when (opened) {
                ExtensionKinds.OPERATIVE_GUIDE -> OperativeGuidesView(
                    uiState = uiState,
                    onNavigateToChapter = onNavigateToChapter,
                    onNavigateToBoneDetail = onNavigateToBoneDetail,
                    onNavigateToSearch = onNavigateToSearch,
                    onNavigateToImport = onNavigateToImport,
                    onToggleBookmark = { viewModel.toggleBookmark(it) },
                    onSwitchToClassifications = {
                        if (extensions.any { it.kind == ExtensionKinds.CLASSIFICATIONS }) opened = ExtensionKinds.CLASSIFICATIONS
                    }
                )

                ExtensionKinds.CLASSIFICATIONS -> ClassificationChaptersScreen(
                    viewModel = classificationViewModel,
                    onOpenChapter = { chapter -> onNavigateToClassificationChapter(chapter.file) },
                    onOpenEditor = onNavigateToClassificationEditor
                )

                else -> ExtensionsGrid(
                    viewModel = extensionsViewModel,
                    onOpenExtension = { extension ->
                        // Web tools run full screen; the guide and the classifications
                        // are shown here. Kinds this build cannot display are not opened.
                        when (extension.kind) {
                            ExtensionKinds.HTML -> onNavigateToExtension(extension.id)
                            ExtensionKinds.OPERATIVE_GUIDE, ExtensionKinds.CLASSIFICATIONS -> opened = extension.kind
                            else -> Toast.makeText(context, R.string.ext_cannot_open, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onImport = onNavigateToExtensionImport
                )
            }
        }
    }
}

@Composable
private fun OperativeGuidesView(
    uiState: com.example.viewmodel.HomeUiState,
    onNavigateToChapter: (String, String) -> Unit,
    onNavigateToBoneDetail: (String) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToImport: () -> Unit,
    onToggleBookmark: (String) -> Unit,
    onSwitchToClassifications: () -> Unit
) {
    if (uiState.isDatabaseEmpty) {
        EmptyDatabaseState(
            onImportClick = onNavigateToImport,
            onBrowseClassificationsClick = onSwitchToClassifications,
            modifier = Modifier
                .fillMaxSize()
                .testTag("home_empty_state")
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_scroll_container"),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // A search entry in place of the large decorative banner: the one action it
        // offered, without a third of the screen.
        item {
            SearchEntry(
                onClick = onNavigateToSearch,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
            )
        }

        item {
            SectionHeader(
                title = stringResource(R.string.home_anatomic_chapters),
                trailing = PatientValueLabels.plural(R.plurals.home_sections_count, uiState.chapters.size)
            )
        }

        itemsIndexed(uiState.chapters, key = { _, item -> item.chapter.id }) { index, item ->
            ChapterCard(
                chapterWithCount = item,
                index = index,
                onClick = { onNavigateToChapter(item.chapter.id, item.chapter.displayTitle) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
            )
        }

        if (uiState.highYieldBones.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(8.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.home_high_yield_operative_guides),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(uiState.highYieldBones, key = { it.id }) { bone ->
                BoneRow(
                    bone = bone,
                    onClick = { onNavigateToBoneDetail(bone.id) },
                    onBookmarkToggle = { onToggleBookmark(bone.id) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = trailing,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** Looks like a search field; opens the search screen. */
@Composable
private fun SearchEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .testTag("home_hero_card")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.home_search_placeholder),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyDatabaseState(
    onImportClick: () -> Unit,
    onBrowseClassificationsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(80.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.FolderOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = stringResource(R.string.home_no_anatomical_database_loaded),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.home_import_a_json_database_file),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onImportClick)
                .testTag("empty_state_import_button")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 13.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.UploadFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_import_database),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Helpful tip that the classifications reference is available regardless of import state
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBrowseClassificationsClick)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.MedicalInformation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_classifications_ready),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = stringResource(R.string.home_browse_gustilo_anderson_schatzker_garden),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ChapterCard(
    chapterWithCount: ChapterWithCount,
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chapter = chapterWithCount.chapter
    val icon = when (chapter.icon) {
        "healing" -> Icons.Default.Healing
        "accessibility_new" -> Icons.Default.AccessibilityNew
        "view_agenda" -> Icons.Default.MedicalServices
        "child_care" -> Icons.Default.ChildCare
        "sports_kabaddi" -> Icons.Default.SportsKabaddi
        else -> Icons.Default.MedicalServices
    }

    val iconBg = when (index % 4) {
        0 -> MaterialTheme.colorScheme.primaryContainer
        1 -> MaterialTheme.colorScheme.tertiaryContainer
        2 -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val iconColor = when (index % 4) {
        0 -> MaterialTheme.colorScheme.onPrimaryContainer
        1 -> MaterialTheme.colorScheme.onTertiaryContainer
        2 -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier
            .fillMaxWidth()
            .testTag("chapter_card_${chapter.id}")
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = iconBg,
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = chapter.displayDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            ) {
                Text(
                    text = PatientValueLabels.plural(R.plurals.home_guides_count, chapterWithCount.boneCount),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
