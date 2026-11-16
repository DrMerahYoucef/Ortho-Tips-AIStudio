package com.example.ui.screens.bone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.repository.OrthoRepository
import com.example.ui.components.BoneRow
import com.example.ui.components.EmptyStateView
import com.example.ui.components.OrthoSearchBar
import kotlinx.coroutines.launch
import com.example.R
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoneListScreen(
    chapterId: String,
    chapterTitle: String,
    repository: OrthoRepository,
    onNavigateBack: () -> Unit,
    onNavigateToBoneDetail: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val bones by repository.getBonesByChapter(chapterId).collectAsStateWithLifecycle(initialValue = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    val filteredBones = remember(bones, searchQuery) {
        if (searchQuery.isBlank()) bones
        else bones.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    it.subtitle.contains(searchQuery, ignoreCase = true) ||
                    it.classificationSummary.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = chapterTitle,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("bone_list_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            OrthoSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = {},
                onClear = { searchQuery = "" },
                placeholderText = stringResource(R.string.bone_list_filter_in_chapter),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (filteredBones.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Default.MedicalServices,
                    title = stringResource(R.string.bone_list_no_procedures_found),
                    message = stringResource(R.string.bone_list_no_matching_procedures_or_fracture)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("bone_list_container")
                ) {
                    items(filteredBones, key = { it.id }) { bone ->
                        BoneRow(
                            bone = bone,
                            onClick = { onNavigateToBoneDetail(bone.id) },
                            onBookmarkToggle = {
                                coroutineScope.launch {
                                    repository.toggleBookmark(bone.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
