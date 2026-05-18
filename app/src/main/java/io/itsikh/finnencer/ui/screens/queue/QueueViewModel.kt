package io.itsikh.finnencer.ui.screens.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.entity.QueueItem
import io.itsikh.finnencer.data.repo.QueueRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class QueueTab { TODO, DONE }

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val repo: QueueRepository,
) : ViewModel() {

    val todoItems: StateFlow<List<QueueItem>> =
        repo.observeIncomplete()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val doneItems: StateFlow<List<QueueItem>> =
        repo.observeCompleted()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _tab = MutableStateFlow(QueueTab.TODO)
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

    fun selectAll() {
        val items = if (_tab.value == QueueTab.TODO) todoItems.value else doneItems.value
        _selectedIds.value = items.map { it.id }.toSet()
    }

    fun invertSelection() {
        val items = if (_tab.value == QueueTab.TODO) todoItems.value else doneItems.value
        val all = items.map { it.id }.toSet()
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

    /** Swap [id] with the previous item in the TODO list (#33 — replaces
     *  drag-and-drop with explicit ↑ / ↓ taps). */
    fun moveTodoUp(id: Long) {
        val list = todoItems.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i <= 0) return
        val moved = list.removeAt(i)
        list.add(i - 1, moved)
        reorderTodo(list)
    }

    fun moveTodoDown(id: Long) {
        val list = todoItems.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0 || i >= list.lastIndex) return
        val moved = list.removeAt(i)
        list.add(i + 1, moved)
        reorderTodo(list)
    }
}
