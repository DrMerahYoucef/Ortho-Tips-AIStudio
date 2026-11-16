package com.example.ui.screens.classifications

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.classification.ClassificationDocument
import com.example.data.extensions.otips.OTipsFormat
import com.example.data.local.model.DocParagraph
import com.example.data.local.model.ParagraphStyles
import com.example.ui.components.ClassificationContentView
import com.example.ui.components.paragraphIndent
import com.example.ui.components.paragraphTextStyle
import com.example.ui.screens.extensions.CreateDocumentWithType
import com.example.ui.screens.extensions.label
import com.example.viewmodel.ClassificationDocumentViewModel
import com.example.viewmodel.ClassificationDraftViewModel
import com.example.viewmodel.ClassificationEditorHomeViewModel
import com.example.viewmodel.EditorEvent
import com.example.viewmodel.EditorLine

// =============================================================================
// Drafts home: your packs, installed packs to edit, a new pack.
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassificationEditorHomeScreen(
    viewModel: ClassificationEditorHomeViewModel,
    onNavigateBack: () -> Unit,
    onOpenDraft: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val openDraft by rememberUpdatedState(onOpenDraft)

    LaunchedEffect(Unit) { viewModel.reload() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it is EditorEvent.OpenDraft) openDraft(it.draftId) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ced_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.ced_new_pack)) },
                modifier = Modifier.testTag("ced_new_pack")
            )
        }
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { EditorIntro() }
            item { SectionLabel(stringResource(R.string.ced_your_drafts)) }
            if (state.drafts.isEmpty()) item { MutedLine(stringResource(R.string.ced_no_drafts)) }
            items(state.drafts, key = { it.draftId }) { draft ->
                Card(
                    onClick = { onOpenDraft(draft.draftId) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(draft.icon, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(draft.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.ced_draft_meta, draft.version, draft.chapters.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
            item { SectionLabel(stringResource(R.string.ced_installed_packs)) }
            if (state.installedPacks.isEmpty()) item { MutedLine(stringResource(R.string.ced_no_installed_packs)) }
            items(state.installedPacks, key = { "i_" + it.id }) { pack ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(pack.icon, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pack.manifest.label(), style = MaterialTheme.typography.bodyLarge)
                        Text("v" + pack.version, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    FilledTonalButton(onClick = { viewModel.edit(pack) }) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ced_edit))
                    }
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.ced_new_pack),
            label = stringResource(R.string.ced_pack_name),
            onConfirm = { creating = false; viewModel.create(it) },
            onDismiss = { creating = false }
        )
    }
}

@Composable
private fun EditorIntro() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.ced_intro),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(14.dp)
        )
    }
}

// =============================================================================
// One pack: details, chapters, publish, export.
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassificationDraftScreen(
    viewModel: ClassificationDraftViewModel,
    onNavigateBack: () -> Unit,
    onOpenChapter: (String) -> Unit
) {
    val context = LocalContext.current
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var addingChapter by remember { mutableStateOf(false) }
    var deletingChapter by remember { mutableStateOf<String?>(null) }
    var deletingDraft by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf<String?>(null) }
    var description by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.reload() }
    LaunchedEffect(draft) {
        draft?.let {
            if (name == null) name = it.name
            if (description == null) description = it.description
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is EditorEvent.Published -> context.getString(R.string.ced_published, event.name, event.version)
                is EditorEvent.PublishFailed -> context.getString(R.string.ced_publish_failed, event.reason)
                is EditorEvent.Exported -> context.getString(if (event.ok) R.string.ext_msg_exported else R.string.ext_msg_export_failed)
                is EditorEvent.OpenDraft -> null
            }
            message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
    }
    val saveDetails = {
        val d = draft
        if (d != null && (name != d.name || description != d.description)) {
            viewModel.rename(name.orEmpty(), description.orEmpty())
        }
    }
    val export = rememberLauncherForActivityResult(CreateDocumentWithType()) { uri -> uri?.let(viewModel::export) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(draft?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { saveDetails(); onNavigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { saveDetails(); viewModel.publish() }, enabled = !busy, modifier = Modifier.testTag("ced_publish")) {
                        Icon(Icons.Filled.Publish, contentDescription = stringResource(R.string.ced_publish))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.ext_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ced_export)) },
                                leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    saveDetails()
                                    export.launch(CreateDocumentWithType.Input(viewModel.exportFileName(), OTipsFormat.MIME_TYPE))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ced_delete_draft)) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; deletingDraft = true }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addingChapter = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.ced_add_chapter)) }
            )
        }
    ) { padding ->
        val current = draft
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (current == null) return@Column
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = name.orEmpty(),
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.ced_pack_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
                item {
                    OutlinedTextField(
                        value = description.orEmpty(),
                        onValueChange = { description = it },
                        label = { Text(stringResource(R.string.ced_pack_description)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    MutedLine(stringResource(R.string.ced_pack_info, current.version, current.packId))
                }
                item { SectionLabel(stringResource(R.string.ced_chapters)) }
                if (current.chapters.isEmpty()) item { MutedLine(stringResource(R.string.ced_no_chapters)) }
                itemsIndexed(current.chapters, key = { _, it -> it.file }) { index, chapter ->
                    Card(
                        onClick = { saveDetails(); onOpenChapter(chapter.file) },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(chapter.title.ifBlank { chapter.id }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.ced_chapter_meta, chapter.sectionCount, chapter.classificationCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            IconButton(onClick = { viewModel.moveChapter(chapter.file, -1) }, enabled = index > 0) {
                                Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.ced_move_up))
                            }
                            IconButton(onClick = { viewModel.moveChapter(chapter.file, 1) }, enabled = index < current.chapters.lastIndex) {
                                Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.ced_move_down))
                            }
                            IconButton(onClick = { deletingChapter = chapter.file }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.ced_delete))
                            }
                        }
                    }
                }
            }
        }
    }

    if (addingChapter) {
        NameDialog(
            title = stringResource(R.string.ced_add_chapter),
            label = stringResource(R.string.ced_chapter_name),
            onConfirm = { addingChapter = false; viewModel.addChapter(it) },
            onDismiss = { addingChapter = false }
        )
    }
    deletingChapter?.let { file ->
        ConfirmDialog(
            text = stringResource(R.string.ced_delete_chapter_confirm),
            onConfirm = { deletingChapter = null; viewModel.deleteChapter(file) },
            onDismiss = { deletingChapter = null }
        )
    }
    if (deletingDraft) {
        ConfirmDialog(
            text = stringResource(R.string.ced_delete_draft_confirm),
            onConfirm = { deletingDraft = false; viewModel.deleteDraft(onNavigateBack) },
            onDismiss = { deletingDraft = false }
        )
    }
}

// =============================================================================
// The Word-style document editor.
// =============================================================================

/** One entry of the formatting ribbon. */
private data class StyleAction(val label: Int, val style: String, val level: Int? = null)

private val STYLE_ACTIONS = listOf(
    StyleAction(R.string.ced_style_title, ParagraphStyles.TITLE),
    StyleAction(R.string.ced_style_subtitle, ParagraphStyles.HEADING, 1),
    StyleAction(R.string.ced_style_subsubtitle, ParagraphStyles.HEADING, 2),
    StyleAction(R.string.ced_style_heading3, ParagraphStyles.HEADING, 3),
    StyleAction(R.string.ced_style_text, ParagraphStyles.TEXT),
    StyleAction(R.string.ced_style_note, ParagraphStyles.NOTE),
    StyleAction(R.string.ced_style_bullets, ParagraphStyles.BULLET),
    StyleAction(R.string.ced_style_numbered, ParagraphStyles.NUMBERED),
    StyleAction(R.string.ced_style_lettered, ParagraphStyles.LETTERED)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassificationDocumentScreen(
    viewModel: ClassificationDocumentViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var preview by rememberSaveable { mutableStateOf(false) }
    var pasting by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    val requesters = remember { mutableStateMapOf<Long, FocusRequester>() }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::insertImageFrom)
    }

    DisposableEffect(viewModel) { onDispose { viewModel.flush() } }
    // New lines (Enter, paste, insert) get the cursor.
    LaunchedEffect(state.focused) {
        state.focused?.let { key -> runCatching { requesters[key]?.requestFocus() } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.title.ifBlank { stringResource(R.string.ced_untitled) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(if (state.saved) R.string.ced_saved else R.string.ced_saving),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.flush(); onNavigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.ced_undo))
                    }
                    IconButton(onClick = viewModel::redo, enabled = state.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.ced_redo))
                    }
                    IconButton(
                        onClick = { pasting = clipboard.getText()?.text.orEmpty() },
                        modifier = Modifier.testTag("ced_paste")
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.ced_paste))
                    }
                    IconButton(onClick = { preview = !preview }) {
                        Icon(
                            if (preview) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(R.string.ced_preview)
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.ext_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.ced_select_all)) }, onClick = { menuOpen = false; viewModel.selectAll() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.ced_clear_selection)) }, onClick = { menuOpen = false; viewModel.clearSelection() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.ced_help)) }, onClick = { menuOpen = false; help = true })
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!preview) {
                FormattingRibbon(
                    state = state,
                    viewModel = viewModel,
                    onInsertImage = { pickImage.launch("image/*") }
                )
            }
        }
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        if (preview) {
            DocumentPreview(state.lines.map { it.paragraph }, viewModel, Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("ced_document"),
            contentPadding = PaddingValues(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 24.dp)
        ) {
            itemsIndexed(state.lines, key = { _, line -> line.key }) { index, line ->
                val requester = requesters.getOrPut(line.key) { FocusRequester() }
                DocumentLine(
                    line = line,
                    marker = state.markers.getOrNull(index),
                    selected = line.key in state.selection,
                    requester = requester,
                    onFocus = { viewModel.focus(line.key) },
                    onToggle = { viewModel.toggleSelection(line.key) },
                    onText = { viewModel.updateText(line.key, it) },
                    imageFile = viewModel::imageFile
                )
            }
        }
    }

    pasting?.let { initial ->
        PasteDialog(
            initial = initial,
            onConfirm = { pasting = null; viewModel.paste(it) },
            onDismiss = { pasting = null }
        )
    }
    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text(stringResource(R.string.ced_help)) },
            text = { Text(stringResource(R.string.ced_help_body)) },
            confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.ext_close)) } }
        )
    }
}

@Composable
private fun DocumentLine(
    line: EditorLine,
    marker: String?,
    selected: Boolean,
    requester: FocusRequester,
    onFocus: () -> Unit,
    onToggle: () -> Unit,
    onText: (String) -> Unit,
    imageFile: (String) -> java.io.File?
) {
    val p = line.paragraph
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(8.dp))
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        // The margin: tap to add/remove this line from the selection.
        Box(
            modifier = Modifier
                .width(30.dp)
                .heightIn(min = 36.dp)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            } else {
                Text(styleTag(p), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        Row(Modifier.weight(1f).padding(start = paragraphIndent(p), top = 6.dp, bottom = 6.dp)) {
            if (marker != null) {
                Text(
                    marker,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.widthIn(min = 24.dp)
                )
            }
            when (p.style) {
                ParagraphStyles.IMAGE -> {
                    val bitmap = remember(p.image) {
                        p.image?.let(imageFile)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
                    }
                    Box(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
                        if (bitmap != null) {
                            Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp))
                        } else {
                            Text(stringResource(R.string.ced_image_missing), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                else -> {
                    val isTable = p.style == ParagraphStyles.TABLE
                    val value = if (isTable) ClassificationDocumentViewModel.tableText(p.rows) else p.text
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(stringResource(placeholder(p)), style = paragraphTextStyle(p).copy(color = MaterialTheme.colorScheme.outline))
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onText,
                            textStyle = if (isTable) {
                                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                            } else {
                                paragraphTextStyle(p)
                            },
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(requester)
                                .onFocusChanged { if (it.isFocused) onFocus() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FormattingRibbon(
    state: com.example.viewmodel.DocumentEditorState,
    viewModel: ClassificationDocumentViewModel,
    onInsertImage: () -> Unit
) {
    val focused = state.lines.firstOrNull { it.key == state.focused }?.paragraph
    val hasSelection = state.selection.isNotEmpty()
    Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding().navigationBarsPadding()) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                STYLE_ACTIONS.forEach { action ->
                    val active = focused != null && focused.style == action.style &&
                        (action.level == null || focused.level == action.level)
                    FilterChip(
                        selected = active,
                        onClick = { viewModel.applyStyle(action.style, action.level) },
                        enabled = hasSelection,
                        label = { Text(stringResource(action.label)) }
                    )
                }
            }
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RibbonButton(Icons.AutoMirrored.Filled.FormatIndentDecrease, R.string.ced_outdent, hasSelection) { viewModel.indent(-1) }
                RibbonButton(Icons.AutoMirrored.Filled.FormatIndentIncrease, R.string.ced_indent, hasSelection) { viewModel.indent(1) }
                RibbonButton(Icons.Filled.ArrowUpward, R.string.ced_move_up, hasSelection) { viewModel.moveSelection(-1) }
                RibbonButton(Icons.Filled.ArrowDownward, R.string.ced_move_down, hasSelection) { viewModel.moveSelection(1) }
                RibbonButton(Icons.Filled.Add, R.string.ced_new_line, true) { viewModel.insertLine() }
                RibbonButton(Icons.Filled.Image, R.string.ced_insert_image, true, onInsertImage)
                RibbonButton(Icons.Filled.TableChart, R.string.ced_insert_table, true) { viewModel.insertTable() }
                RibbonButton(Icons.Filled.Delete, R.string.ced_delete, hasSelection) { viewModel.deleteSelection() }
            }
        }
    }
}

@Composable
private fun RibbonButton(icon: ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

/** The chapter as the Classifications tab will show it. */
@Composable
private fun DocumentPreview(paragraphs: List<DocParagraph>, viewModel: ClassificationDocumentViewModel, modifier: Modifier) {
    val chapter = remember(paragraphs) { ClassificationDocument.toChapter("preview", paragraphs) }
    val loadImage: (String) -> androidx.compose.ui.graphics.ImageBitmap? = { path ->
        viewModel.imageFile(path)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text(chapter.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        chapter.sections.forEach { section ->
            if (section.title.isNotBlank()) {
                item { Text(section.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            }
            items(section.classifications) { entry ->
                Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        if (entry.title.isNotBlank()) {
                            Text(entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 4.dp))
                        }
                        ClassificationContentView(entry.content, loadImage)
                    }
                }
            }
        }
    }
}

@Composable
private fun PasteDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ced_paste)) },
        text = {
            Column {
                Text(stringResource(R.string.ced_paste_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 6,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.ced_insert)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ext_cancel)) } }
    )
}

// ---- small shared pieces --------------------------------------------------------

@Composable
private fun NameDialog(title: String, label: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(stringResource(R.string.ced_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ext_cancel)) } }
    )
}

@Composable
private fun ConfirmDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.ced_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ext_cancel)) } }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp)
    )
}

@Composable
private fun MutedLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

/** A short tag in the margin telling the line's style at a glance, like Word's style area. */
private fun styleTag(p: DocParagraph): String = when (p.style) {
    ParagraphStyles.TITLE -> "T"
    ParagraphStyles.HEADING -> "H${p.level}"
    ParagraphStyles.NOTE -> "N"
    ParagraphStyles.IMAGE -> "▣"
    ParagraphStyles.TABLE -> "▦"
    in ParagraphStyles.LISTS -> ""
    else -> "¶"
}

private fun placeholder(p: DocParagraph): Int = when (p.style) {
    ParagraphStyles.TITLE -> R.string.ced_style_title
    ParagraphStyles.HEADING -> when (p.level) {
        1 -> R.string.ced_style_subtitle
        2 -> R.string.ced_style_subsubtitle
        else -> R.string.ced_style_heading3
    }
    ParagraphStyles.NOTE -> R.string.ced_style_note
    ParagraphStyles.TABLE -> R.string.ced_table_hint
    else -> R.string.ced_type_here
}
