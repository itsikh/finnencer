package io.itsikh.finnencer.ui.screens.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.entity.QueueItem
import io.itsikh.finnencer.data.entity.QueueItemKind
import io.itsikh.finnencer.data.repo.QueueRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Queue page has three tabs:
 *  - [ARTICLES] — readable to-do items (news articles, AI summaries,
 *    batch summaries, earnings reports) that haven't been marked done.
 *  - [PODCASTS] — podcast to-do items that haven't been marked done.
 *  - [DONE] — every completed item, regardless of kind, in one
 *    unified list.
 */
enum class QueueTab { ARTICLES, PODCASTS, DONE }

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val repo: QueueRepository,
    private val viewModePrefs: io.itsikh.finnencer.data.repo.ViewModePreferences,
) : ViewModel() {

    val groupedByTicker: StateFlow<Boolean> = viewModePrefs.queueGrouped
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setGroupedByTicker(value: Boolean) {
        viewModelScope.launch { viewModePrefs.setQueueGrouped(value) }
    }

    /** All not-yet-done items. Kept private so the UI only ever sees the
     *  kind-filtered sub-tab views. */
    private val todoItems: StateFlow<List<QueueItem>> =
        repo.observeIncomplete()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Readable to-do items — everything except podcasts. */
    val articlesTodoItems: StateFlow<List<QueueItem>> =
        todoItems
            .map { list -> list.filter { it.kind != QueueItemKind.PODCAST.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Podcast to-do items. */
    val podcastsTodoItems: StateFlow<List<QueueItem>> =
        todoItems
            .map { list -> list.filter { it.kind == QueueItemKind.PODCAST.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val doneItems: StateFlow<List<QueueItem>> =
        repo.observeCompleted()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Podcasts is the default tab — that's the lane the user usually
    // returns to (queued listening) rather than article read-later.
    private val _tab = MutableStateFlow(QueueTab.PODCASTS)
    val tab: StateFlow<QueueTab> = _tab.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    val isSelecting: StateFlow<Boolean> = _selectedIds
        .mapState { it.isNotEmpty() }

    private fun <T, R> StateFlow<T>.mapState(transform: (T) -> R): StateFlow<R> =
        MutableStateFlow(transform(value)).also { out ->
            viewModelScope.launch {
                collect { out.value = transform(it) }
            }
        }.asStateFlow()

    fun setTab(t: QueueTab) {
        _tab.value = t
        _selectedIds.value = emptySet()
    }

    fun toggleSelection(id: Long) {
        _selectedIds.value = _selectedIds.value.toMutableSet().also {
            if (!it.add(id)) it.remove(id)
        }
    }

    /** Items currently rendered in the active tab — the source of truth
     *  for selectAll / invertSelection so multi-select never leaks across
     *  sub-tabs. */
    private fun activeTabItems(): List<QueueItem> = when (_tab.value) {
        QueueTab.ARTICLES -> articlesTodoItems.value
        QueueTab.PODCASTS -> podcastsTodoItems.value
        QueueTab.DONE -> doneItems.value
    }

    fun selectAll() {
        _selectedIds.value = activeTabItems().map { it.id }.toSet()
    }

    fun invertSelection() {
        val all = activeTabItems().map { it.id }.toSet()
        _selectedIds.value = all - _selectedIds.value
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun deleteSelected() {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repo.deleteAll(ids)
            _selectedIds.value = emptySet()
        }
    }

    fun markSelectedDone() {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { repo.markDone(it) }
            _selectedIds.value = emptySet()
        }
    }

    fun markSelectedUndone() {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { repo.markUndone(it) }
            _selectedIds.value = emptySet()
        }
    }

    fun markDone(id: Long) {
        viewModelScope.launch { repo.markDone(id) }
    }

    fun markUndone(id: Long) {
        viewModelScope.launch { repo.markUndone(id) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repo.delete(id) }
    }

    fun clearAllCompleted() {
        viewModelScope.launch {
            repo.clearAllCompleted()
            if (_tab.value == QueueTab.DONE) _selectedIds.value = emptySet()
        }
    }

    /** Persist the new order after a reorder. The list comes in
     *  already-reordered; we only write rows whose [sortOrder] actually
     *  changed. */
    fun reorderTodo(reordered: List<QueueItem>) {
        viewModelScope.launch { repo.reorder(reordered) }
    }

    /** Swap [id] with the previous item *within its sub-tab* (#33 —
     *  replaces drag-and-drop with explicit ↑ / ↓ taps). Reordering is
     *  scoped to the visible list so an article ↑ swaps with the
     *  article above it, never with a hidden podcast row. */
    fun moveTodoUp(id: Long) {
        val list = todoListForId(id) ?: return
        val i = list.indexOfFirst { it.id == id }
        if (i <= 0) return
        val moved = list.removeAt(i)
        list.add(i - 1, moved)
        reorderTodo(list)
    }

    fun moveTodoDown(id: Long) {
        val list = todoListForId(id) ?: return
        val i = list.indexOfFirst { it.id == id }
        if (i < 0 || i >= list.lastIndex) return
        val moved = list.removeAt(i)
        list.add(i + 1, moved)
        reorderTodo(list)
    }

    private fun todoListForId(id: Long): MutableList<QueueItem>? {
        val articles = articlesTodoItems.value
        if (articles.any { it.id == id }) return articles.toMutableList()
        val podcasts = podcastsTodoItems.value
        if (podcasts.any { it.id == id }) return podcasts.toMutableList()
        return null
    }
}
