package com.example.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.classification.ClassificationDocument
import com.example.data.classification.ClassificationDraft
import com.example.data.classification.ClassificationDraftRepository
import com.example.data.extensions.ExtensionKinds
import com.example.data.extensions.ExtensionRepository
import com.example.data.extensions.OrthoExtension
import com.example.data.local.model.DocParagraph
import com.example.data.local.model.ParagraphStyles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface EditorEvent {
    data class Published(val name: String, val version: String) : EditorEvent
    data class PublishFailed(val reason: String) : EditorEvent
    data class Exported(val ok: Boolean) : EditorEvent
    data class OpenDraft(val draftId: String) : EditorEvent
}

// ---- drafts list ---------------------------------------------------------------

data class EditorHomeState(
    val drafts: List<ClassificationDraft> = emptyList(),
    /** Installed classification packs that can be opened for editing. */
    val installedPacks: List<OrthoExtension> = emptyList(),
    val loading: Boolean = true
)

class ClassificationEditorHomeViewModel(
    private val drafts: ClassificationDraftRepository,
    extensions: ExtensionRepository
) : ViewModel() {

    private val draftList = MutableStateFlow<List<ClassificationDraft>?>(null)

    val state: StateFlow<EditorHomeState> = combine(
        draftList,
        extensions.extensions.map { all -> all.filter { it.kind == ExtensionKinds.CLASSIFICATIONS } }
    ) { list, packs ->
        EditorHomeState(drafts = list.orEmpty(), installedPacks = packs, loading = list == null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorHomeState())

    private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<EditorEvent> = _events.asSharedFlow()

    fun reload() {
        viewModelScope.launch { draftList.value = drafts.list() }
    }

    fun create(name: String) {
        viewModelScope.launch {
            val draft = drafts.create(name)
            reload()
            _events.emit(EditorEvent.OpenDraft(draft.draftId))
        }
    }

    fun edit(pack: OrthoExtension) {
        viewModelScope.launch {
            val draft = drafts.editInstalled(pack)
            reload()
            _events.emit(EditorEvent.OpenDraft(draft.draftId))
        }
    }

    companion object {
        fun provideFactory(drafts: ClassificationDraftRepository, extensions: ExtensionRepository) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ClassificationEditorHomeViewModel(drafts, extensions) as T
            }
    }
}

// ---- one draft (pack) ------------------------------------------------------------

class ClassificationDraftViewModel(
    private val drafts: ClassificationDraftRepository,
    private val draftId: String
) : ViewModel() {

    private val _draft = MutableStateFlow<ClassificationDraft?>(null)
    val draft: StateFlow<ClassificationDraft?> = _draft.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<EditorEvent> = _events.asSharedFlow()

    fun reload() {
        viewModelScope.launch { _draft.value = drafts.get(draftId) }
    }

    fun rename(name: String, description: String) = act { drafts.rename(draftId, name, description) }
    fun addChapter(title: String) = act { drafts.addChapter(draftId, title) }
    fun deleteChapter(file: String) = act { drafts.deleteChapter(draftId, file) }
    fun moveChapter(file: String, delta: Int) = act { drafts.moveChapter(draftId, file, delta) }

    fun publish() = act {
        when (val result = drafts.publish(draftId)) {
            is ClassificationDraftRepository.PublishResult.Published ->
                _events.emit(EditorEvent.Published(result.extension.name, result.extension.version))
            is ClassificationDraftRepository.PublishResult.Failed -> _events.emit(EditorEvent.PublishFailed(result.reason))
        }
    }

    fun export(destination: Uri) = act { _events.emit(EditorEvent.Exported(drafts.export(draftId, destination))) }

    fun deleteDraft(onDeleted: () -> Unit) {
        viewModelScope.launch {
            drafts.delete(draftId)
            onDeleted()
        }
    }

    fun exportFileName(): String = _draft.value?.let(drafts::exportFileName) ?: "classifications.OTips"

    private fun act(block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
                _draft.value = drafts.get(draftId)
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        fun provideFactory(drafts: ClassificationDraftRepository, draftId: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ClassificationDraftViewModel(drafts, draftId) as T
            }
    }
}

// ---- the Word-style document ----------------------------------------------------

/** One line of the document being edited; [key] is stable across edits. */
data class EditorLine(val key: Long, val paragraph: DocParagraph)

data class DocumentEditorState(
    val lines: List<EditorLine> = emptyList(),
    /** Lines the formatting toolbar applies to. */
    val selection: Set<Long> = emptySet(),
    /** The line with the cursor, if any. */
    val focused: Long? = null,
    val markers: List<String?> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val loading: Boolean = true,
    val saved: Boolean = true
) {
    val title: String get() = lines.firstOrNull { it.paragraph.style == ParagraphStyles.TITLE }?.paragraph?.text.orEmpty()
}

/**
 * Word-like editing of one chapter: lines with styles and indent levels, a
 * selection the toolbar acts on, undo/redo, and saving shortly after every
 * change (and when leaving).
 */
class ClassificationDocumentViewModel(
    private val drafts: ClassificationDraftRepository,
    private val draftId: String,
    private val chapterFile: String
) : ViewModel() {

    private val _state = MutableStateFlow(DocumentEditorState())
    val state: StateFlow<DocumentEditorState> = _state.asStateFlow()

    private var nextKey = 1L
    private val undo = ArrayDeque<List<EditorLine>>()
    private val redo = ArrayDeque<List<EditorLine>>()
    /** The line being typed in; consecutive typing there is one undo step. */
    private var typingKey: Long? = null
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val paragraphs = drafts.loadDocument(draftId, chapterFile)
                .ifEmpty { listOf(DocParagraph(ParagraphStyles.TITLE, "")) }
            publish(paragraphs.map { EditorLine(nextKey++, it) }, dirty = false, loading = false)
        }
    }

    // -- selection --

    /** Tapping a line's text: it becomes the only selected line and gets the cursor. */
    fun focus(key: Long) {
        if (_state.value.focused == key && _state.value.selection == setOf(key)) return
        typingKey = null
        _state.value = _state.value.copy(focused = key, selection = setOf(key))
    }

    /** Tapping a line's margin: adds or removes it from the selection, like shift-selecting lines. */
    fun toggleSelection(key: Long) {
        val selection = _state.value.selection.toMutableSet()
        if (!selection.add(key)) selection.remove(key)
        _state.value = _state.value.copy(selection = selection)
    }

    fun selectAll() {
        _state.value = _state.value.copy(selection = _state.value.lines.map { it.key }.toSet())
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selection = emptySet(), focused = null)
    }

    // -- text --

    /**
     * Typing in a line. A newline splits it like pressing Enter in Word: the
     * text after the cursor becomes new lines of the same list style (a heading
     * or title continues as plain text).
     */
    fun updateText(key: Long, text: String) {
        val lines = _state.value.lines
        val index = lines.indexOfFirst { it.key == key }
        if (index < 0) return
        val line = lines[index]
        if (typingKey != key) {
            pushUndo(lines)
            typingKey = key
        }
        if (!text.contains('\n') || line.paragraph.style == ParagraphStyles.TABLE) {
            val updated = if (line.paragraph.style == ParagraphStyles.TABLE) {
                line.paragraph.copy(rows = parseTable(text), text = "")
            } else {
                line.paragraph.copy(text = text)
            }
            publish(lines.replaceAt(index, line.copy(paragraph = updated)))
            return
        }
        val parts = text.split('\n')
        val continuation = when (line.paragraph.style) {
            ParagraphStyles.TITLE, ParagraphStyles.HEADING, ParagraphStyles.IMAGE -> DocParagraph(ParagraphStyles.TEXT)
            else -> line.paragraph.copy(text = "")
        }
        val added = parts.drop(1).map { EditorLine(nextKey++, continuation.copy(text = it)) }
        val result = lines.toMutableList().apply {
            set(index, line.copy(paragraph = line.paragraph.copy(text = parts.first())))
            addAll(index + 1, added)
        }
        typingKey = null
        publish(result, focused = added.last().key, selection = setOf(added.last().key))
    }

    /** Pasted text becomes lines (lists, indents and tables recognised) after the cursor. */
    fun paste(text: String) {
        val paragraphs = ClassificationDocument.parsePastedText(text)
        if (paragraphs.isEmpty()) return
        insertAfterCursor(paragraphs.map { EditorLine(nextKey++, it) })
    }

    fun insertLine(style: String = ParagraphStyles.TEXT) {
        val line = EditorLine(nextKey++, DocParagraph(style, level = if (style == ParagraphStyles.HEADING) 1 else 0))
        insertAfterCursor(listOf(line))
    }

    fun insertImage(relativePath: String) {
        insertAfterCursor(listOf(EditorLine(nextKey++, DocParagraph(ParagraphStyles.IMAGE, image = relativePath))))
    }

    /** Copies a picked picture into the draft and inserts it as an image line. */
    fun insertImageFrom(uri: Uri) {
        viewModelScope.launch { drafts.importImage(draftId, uri)?.let(::insertImage) }
    }

    fun imageFile(relativePath: String): java.io.File? = drafts.imageFile(draftId, relativePath)

    fun insertTable() {
        val rows = listOf(listOf("", ""), listOf("", ""))
        insertAfterCursor(listOf(EditorLine(nextKey++, DocParagraph(ParagraphStyles.TABLE, rows = rows))))
    }

    // -- formatting the selection --

    /** Applies a style; for headings [level] is the depth (1 = subtitle, 2 = sub-subtitle, …). */
    fun applyStyle(style: String, level: Int? = null) = editSelection { p ->
        when (style) {
            ParagraphStyles.HEADING -> p.copy(style = style, level = (level ?: 1).coerceAtLeast(1))
            ParagraphStyles.TITLE -> p.copy(style = style, level = 0)
            else -> p.copy(style = style, level = if (p.style == ParagraphStyles.HEADING || p.style == ParagraphStyles.TITLE) 0 else p.level)
        }.let { if (p.style == ParagraphStyles.TABLE && style != ParagraphStyles.TABLE) it.copy(text = tableText(p.rows), rows = emptyList()) else it }
    }

    /** Indent (+1) or outdent (-1): nests list items, or moves a heading one level deeper/shallower. */
    fun indent(delta: Int) = editSelection { p ->
        when (p.style) {
            ParagraphStyles.TITLE, ParagraphStyles.IMAGE, ParagraphStyles.TABLE -> p
            ParagraphStyles.HEADING -> p.copy(level = (p.level + delta).coerceAtLeast(1))
            else -> p.copy(level = (p.level + delta).coerceIn(0, ClassificationDocument.MAX_INDENT))
        }
    }

    fun moveSelection(delta: Int) {
        val lines = _state.value.lines
        val selection = _state.value.selection
        if (selection.isEmpty()) return
        val indexes = lines.indices.filter { lines[it].key in selection }
        if ((delta < 0 && indexes.first() == 0) || (delta > 0 && indexes.last() == lines.lastIndex)) return
        pushUndo(lines)
        val result = lines.toMutableList()
        val order = if (delta < 0) indexes else indexes.reversed()
        for (i in order) {
            val j = i + delta
            val tmp = result[j]; result[j] = result[i]; result[i] = tmp
        }
        publish(result)
    }

    fun deleteSelection() {
        val lines = _state.value.lines
        val selection = _state.value.selection
        if (selection.isEmpty()) return
        pushUndo(lines)
        val remaining = lines.filterNot { it.key in selection }
            .ifEmpty { listOf(EditorLine(nextKey++, DocParagraph(ParagraphStyles.TITLE, ""))) }
        publish(remaining, focused = null, selection = emptySet())
    }

    // -- history --

    fun undo() {
        val previous = undo.removeLastOrNull() ?: return
        redo.addLast(_state.value.lines)
        typingKey = null
        publish(previous)
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(_state.value.lines)
        typingKey = null
        publish(next)
    }

    /** Saves now; called when leaving the editor. */
    fun flush() {
        saveJob?.cancel()
        val paragraphs = _state.value.lines.map { it.paragraph }
        if (_state.value.loading) return
        CoroutineScope(Dispatchers.IO + NonCancellable).launch { drafts.saveDocument(draftId, chapterFile, paragraphs) }
    }

    override fun onCleared() {
        flush()
    }

    // -- internals --

    private fun editSelection(transform: (DocParagraph) -> DocParagraph) {
        val lines = _state.value.lines
        val selection = _state.value.selection
        if (selection.isEmpty()) return
        pushUndo(lines)
        typingKey = null
        publish(lines.map { if (it.key in selection) it.copy(paragraph = transform(it.paragraph)) else it })
    }

    private fun insertAfterCursor(added: List<EditorLine>) {
        val lines = _state.value.lines
        pushUndo(lines)
        typingKey = null
        val anchor = _state.value.focused ?: _state.value.selection.lastOrNull()
        val at = lines.indexOfFirst { it.key == anchor }.let { if (it < 0) lines.size else it + 1 }
        val result = lines.toMutableList().apply { addAll(at, added) }
        publish(result, focused = added.last().key, selection = setOf(added.last().key))
    }

    private fun pushUndo(lines: List<EditorLine>) {
        undo.addLast(lines)
        if (undo.size > HISTORY) undo.removeFirst()
        redo.clear()
    }

    private fun publish(
        lines: List<EditorLine>,
        focused: Long? = _state.value.focused,
        selection: Set<Long> = _state.value.selection,
        /** False only for loading from disk: everything else needs saving. */
        dirty: Boolean = true,
        loading: Boolean = _state.value.loading
    ) {
        val keys = lines.map { it.key }.toSet()
        _state.value = DocumentEditorState(
            lines = lines,
            selection = selection.filter { it in keys }.toSet(),
            focused = focused?.takeIf { it in keys },
            markers = ClassificationDocument.listMarkers(lines.map { it.paragraph }),
            canUndo = undo.isNotEmpty(),
            canRedo = redo.isNotEmpty(),
            loading = loading,
            saved = !dirty
        )
        if (dirty && !loading) scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            drafts.saveDocument(draftId, chapterFile, _state.value.lines.map { it.paragraph })
            _state.value = _state.value.copy(saved = true)
        }
    }

    private fun List<EditorLine>.replaceAt(index: Int, line: EditorLine) = toMutableList().apply { set(index, line) }

    companion object {
        private const val HISTORY = 200
        private const val SAVE_DELAY_MS = 700L

        /** A table is edited as text: one row per line, cells separated by `|`. */
        fun tableText(rows: List<List<String>>): String = rows.joinToString("\n") { it.joinToString(" | ") }

        fun parseTable(text: String): List<List<String>> =
            text.split('\n').map { row -> row.split('|').map { it.trim() } }

        fun provideFactory(drafts: ClassificationDraftRepository, draftId: String, chapterFile: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ClassificationDocumentViewModel(drafts, draftId, chapterFile) as T
            }
    }
}
