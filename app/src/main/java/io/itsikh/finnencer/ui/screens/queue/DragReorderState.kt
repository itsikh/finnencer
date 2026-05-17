package io.itsikh.finnencer.ui.screens.queue

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Lightweight long-press-and-drag reorder for a [LazyColumn]. Avoids
 * pulling in `reorderable` as a dependency — the queue is the only
 * place we need it.
 *
 * Usage on each row: `Modifier.pointerInput(itemId) { … }` calling
 * [DragReorderState.onDragStart] / [onDrag] / [onDragEnd]. Apply the
 * `dragOffsetForKey(itemId)` translation to draw the row dragging in
 * place.
 */
class DragReorderState(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    private val onCommit: () -> Unit,
) {
    /** Item key currently being dragged, if any. */
    var draggingKey: Any? by mutableStateOf<Any?>(null)
        private set

    /** Total drag delta on Y since drag started, for the lifted row. */
    var draggingDy: Float by mutableStateOf(0f)
        private set

    /** Item index at drag start — kept so onDrag can compute new index. */
    private var startIndex: Int = -1

    fun startDrag(key: Any, index: Int) {
        draggingKey = key
        draggingDy = 0f
        startIndex = index
    }

    fun onDrag(dy: Float) {
        if (draggingKey == null) return
        draggingDy += dy

        // Translate cumulative drag into an index shift by measuring
        // against the dragged row's height as reported by LazyListState.
        val info = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == draggingKey } ?: return
        val rowHeight = info.size.toFloat().coerceAtLeast(1f)
        val currentIndex = info.index
        val targetIndex = (currentIndex + (draggingDy / rowHeight).toInt())
            .coerceIn(0, listState.layoutInfo.totalItemsCount - 1)

        if (targetIndex != currentIndex) {
            onMove(currentIndex, targetIndex)
            // Reset accumulated dy by the displacement we just consumed
            // so further drag of half-a-row triggers another shift.
            draggingDy -= (targetIndex - currentIndex) * rowHeight
        }
    }

    fun endDrag() {
        if (draggingKey != null) onCommit()
        draggingKey = null
        draggingDy = 0f
        startIndex = -1
    }
}

@Composable
fun rememberDragReorderState(
    listState: LazyListState,
    onMove: (Int, Int) -> Unit,
    onCommit: () -> Unit,
): DragReorderState {
    val scope = rememberCoroutineScope()
    return remember(listState) {
        DragReorderState(
            listState = listState,
            scope = scope,
            onMove = onMove,
            onCommit = onCommit,
        )
    }
}

/**
 * Attach as `Modifier.dragHandleModifier(state, itemKey, index)` on
 * the drag-handle icon. The whole row stays tappable elsewhere; only
 * the handle initiates drag.
 */
fun Modifier.dragHandleModifier(
    state: DragReorderState,
    itemKey: Any,
    index: Int,
): Modifier = this.pointerInput(itemKey) {
    detectDragGesturesAfterLongPress(
        onDragStart = { _: Offset -> state.startDrag(itemKey, index) },
        onDrag = { change, dragAmount ->
            change.consume()
            state.onDrag(dragAmount.y)
        },
        onDragEnd = { state.endDrag() },
        onDragCancel = { state.endDrag() },
    )
}
