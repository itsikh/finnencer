package io.itsikh.finnencer.ui.screens.queue

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.data.entity.QueueItem
import io.itsikh.finnencer.data.entity.QueueItemKind
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.theme.FinnencerColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun QueueScreen(
    onBack: () -> Unit,
    onOpenArticle: (articleId: String) -> Unit,
    onOpenReport: (reportId: Long) -> Unit,
    onOpenPodcast: (podcastId: Long) -> Unit,
    onOpenTasks: () -> Unit,
) {
    val vm: QueueViewModel = hiltViewModel()
    val tab by vm.tab.collectAsState()
    val todo by vm.todoItems.collectAsState()
    val done by vm.doneItems.collectAsState()
    val selected by vm.selectedIds.collectAsState()
    val isSelecting by vm.isSelecting.collectAsState()

    val items = if (tab == QueueTab.TODO) todo else done

    // Local mirror used to drive in-flight drag reordering before
    // persisting. We rebuild it from `todo` whenever the DB changes
    // and we're not mid-drag.
    var liveOrder by remember { mutableStateOf(todo) }
    val listState = rememberLazyListState()
    val drag = rememberDragReorderState(
        listState = listState,
        onMove = { from, to ->
            liveOrder = liveOrder.toMutableList().also { list ->
                if (from in list.indices && to in list.indices) {
                    val moved = list.removeAt(from)
                    list.add(to, moved)
                }
            }
        },
        onCommit = { vm.reorderTodo(liveOrder) },
    )
    // Keep liveOrder in sync with DB when not dragging.
    if (drag.draggingKey == null && liveOrder.map { it.id } != todo.map { it.id }) {
        liveOrder = todo
    }

    val renderItems = if (tab == QueueTab.TODO) liveOrder else done

    var deleteSelectedConfirm by remember { mutableStateOf(false) }
    var clearDoneConfirm by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (isSelecting) {
                TopAppBar(
                    title = {
                        Text(
                            "${selected.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            color = FinnencerColors.TextPrimary,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) {
                            Icon(Icons.Default.Close, "Cancel selection", tint = FinnencerColors.TextPrimary)
                        }
                    },
                    actions = {
                        if (tab == QueueTab.TODO) {
                            IconButton(onClick = { vm.markSelectedDone() }) {
                                Icon(
                                    Icons.Default.Check,
                                    "Mark selected done",
                                    tint = FinnencerColors.Mint,
                                )
                            }
                        } else {
                            IconButton(onClick = { vm.markSelectedUndone() }) {
                                Icon(
                                    Icons.Default.Undo,
                                    "Move back to To do",
                                    tint = FinnencerColors.Violet,
                                )
                            }
                        }
                        IconButton(
                            onClick = { deleteSelectedConfirm = true },
                            enabled = selected.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.Delete, "Delete selected", tint = FinnencerColors.Coral)
                        }
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Default.MoreVert, "More", tint = FinnencerColors.TextPrimary)
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Select all") },
                                    onClick = { vm.selectAll(); overflowOpen = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("Invert selection") },
                                    onClick = { vm.invertSelection(); overflowOpen = false },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            "Queue",
                            style = MaterialTheme.typography.headlineMedium,
                            color = FinnencerColors.TextPrimary,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                "Back",
                                tint = FinnencerColors.TextPrimary,
                            )
                        }
                    },
                    actions = {
                        if (tab == QueueTab.DONE && done.isNotEmpty()) {
                            TextButton(onClick = { clearDoneConfirm = true }) {
                                Text(
                                    "Clear all",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = FinnencerColors.Coral,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = if (tab == QueueTab.TODO) 0 else 1,
                containerColor = Color.Transparent,
                contentColor = FinnencerColors.TextPrimary,
            ) {
                Tab(
                    selected = tab == QueueTab.TODO,
                    onClick = { vm.setTab(QueueTab.TODO) },
                    text = {
                        Text(
                            "To do · ${todo.size}",
                            color = if (tab == QueueTab.TODO) FinnencerColors.TextPrimary
                                    else FinnencerColors.TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                )
                Tab(
                    selected = tab == QueueTab.DONE,
                    onClick = { vm.setTab(QueueTab.DONE) },
                    text = {
                        Text(
                            "Done · ${done.size}",
                            color = if (tab == QueueTab.DONE) FinnencerColors.TextPrimary
                                    else FinnencerColors.TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                )
            }
            if (renderItems.isEmpty()) {
                EmptyQueue(tab = tab, onOpenTasks = onOpenTasks)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (tab == QueueTab.TODO) {
                        item {
                            Text(
                                "Long-press the handle to drag and reorder. Long-press a row to multi-select.",
                                style = MaterialTheme.typography.labelSmall,
                                color = FinnencerColors.TextTertiary,
                            )
                        }
                    }
                    items(renderItems, key = { it.id }) { item ->
                        val isDragging = drag.draggingKey == item.id
                        QueueRow(
                            item = item,
                            selected = item.id in selected,
                            selectionMode = isSelecting,
                            showDragHandle = tab == QueueTab.TODO && !isSelecting,
                            dragModifier = if (tab == QueueTab.TODO) {
                                Modifier.dragHandleModifier(
                                    drag, item.id, renderItems.indexOf(item),
                                )
                            } else Modifier,
                            translationY = if (isDragging) drag.draggingDy else 0f,
                            elevated = isDragging,
                            onTap = {
                                if (isSelecting) {
                                    vm.toggleSelection(item.id)
                                } else {
                                    when (item.kind) {
                                        QueueItemKind.ARTICLE.name -> onOpenArticle(item.refId)
                                        QueueItemKind.ARTICLE_SUMMARY.name -> onOpenArticle(item.refId)
                                        QueueItemKind.BATCH_SUMMARY.name -> onOpenTasks()
                                        QueueItemKind.EARNINGS_REPORT.name ->
                                            item.refId.toLongOrNull()?.let(onOpenReport)
                                        QueueItemKind.PODCAST.name ->
                                            item.refId.toLongOrNull()?.let(onOpenPodcast)
                                    }
                                }
                            },
                            onLongPress = { vm.toggleSelection(item.id) },
                            onComplete = {
                                if (tab == QueueTab.TODO) vm.markDone(item.id)
                                else vm.markUndone(item.id)
                            },
                        )
                    }
                    item { Spacer(Modifier.height(40.dp)) }
                }
            }
        }
    }

    if (deleteSelectedConfirm) {
        AlertDialog(
            onDismissRequest = { deleteSelectedConfirm = false },
            title = { Text("Delete ${selected.size} items?") },
            text = { Text("Removes them from your queue. The underlying article / report / podcast is untouched.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSelected()
                    deleteSelectedConfirm = false
                }) { Text("Delete", color = FinnencerColors.Coral) }
            },
            dismissButton = {
                TextButton(onClick = { deleteSelectedConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (clearDoneConfirm) {
        AlertDialog(
            onDismissRequest = { clearDoneConfirm = false },
            title = { Text("Clear all done items?") },
            text = { Text("Permanently removes everything in the Done tab. The underlying content stays intact.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAllCompleted()
                    clearDoneConfirm = false
                }) { Text("Clear all", color = FinnencerColors.Coral) }
            },
            dismissButton = {
                TextButton(onClick = { clearDoneConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(
    item: QueueItem,
    selected: Boolean,
    selectionMode: Boolean,
    showDragHandle: Boolean,
    dragModifier: Modifier,
    translationY: Float,
    elevated: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onComplete: () -> Unit,
) {
    val rowColor = if (selected) FinnencerColors.Violet.copy(alpha = 0.22f) else Color.Transparent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.translationY = translationY
                if (elevated) {
                    shadowElevation = 12f
                    alpha = 0.95f
                }
            },
    ) {
        GlassCard {
            // Outer Row is the layout container. The drag handle on the
            // trailing edge has its own touch zone — combinedClickable on
            // the content half stole the long-press gesture and made
            // drag-to-reorder impossible (#30).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                        .background(rowColor)
                        .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectionMode) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(if (selected) FinnencerColors.Violet else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (selected) FinnencerColors.Violet else FinnencerColors.TextTertiary,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = FinnencerColors.TextOnAccent,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    KindBadge(kind = item.kind, ticker = item.tickerSymbol)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FinnencerColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sub = item.subtitle
                        if (!sub.isNullOrBlank()) {
                            Text(
                                sub,
                                style = MaterialTheme.typography.labelSmall,
                                color = FinnencerColors.TextTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (!selectionMode) {
                        // Quick-action: mark done / undone via row-level icon.
                        IconButton(onClick = onComplete) {
                            Icon(
                                if (item.completedAtMillis == null) Icons.Default.Check else Icons.Default.Undo,
                                contentDescription = if (item.completedAtMillis == null) "Mark done" else "Move back to To do",
                                tint = if (item.completedAtMillis == null) FinnencerColors.Mint
                                       else FinnencerColors.Violet,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                if (showDragHandle) {
                    // Outside the combinedClickable Row so the
                    // detectDragGesturesAfterLongPress on `dragModifier`
                    // actually wins the long-press gesture instead of
                    // being preempted by the parent's onLongClick.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .then(dragModifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = "Drag to reorder",
                            tint = FinnencerColors.TextTertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KindBadge(kind: String, ticker: String?) {
    val (icon, color) = iconForKind(kind)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (ticker != null) {
            Text(
                ticker.take(4),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
    }
}

private fun iconForKind(kind: String): Pair<ImageVector, Color> = when (kind) {
    QueueItemKind.ARTICLE.name -> Icons.Default.Article to FinnencerColors.Violet
    QueueItemKind.ARTICLE_SUMMARY.name -> Icons.Default.Summarize to FinnencerColors.Mint
    QueueItemKind.BATCH_SUMMARY.name -> Icons.Default.Summarize to FinnencerColors.Amber
    QueueItemKind.EARNINGS_REPORT.name -> Icons.Default.EventNote to FinnencerColors.Violet
    QueueItemKind.PODCAST.name -> Icons.Default.Headphones to FinnencerColors.Coral
    else -> Icons.Default.Bookmark to FinnencerColors.TextSecondary
}

@Composable
private fun EmptyQueue(tab: QueueTab, onOpenTasks: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(FinnencerColors.Violet.copy(alpha = 0.10f))
                .border(1.dp, FinnencerColors.Violet.copy(alpha = 0.30f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Bookmark,
                contentDescription = null,
                tint = FinnencerColors.Violet,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            if (tab == QueueTab.TODO) "Your queue is empty"
            else "Nothing finished yet",
            style = MaterialTheme.typography.titleLarge,
            color = FinnencerColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (tab == QueueTab.TODO)
                "Save articles, AI summaries, earnings reports and podcasts from anywhere in the app — they show up here so you can come back to them later."
            else
                "Tap ✓ on a queued item to mark it done. Completed items live here so you can look back at what you've gotten through.",
            style = MaterialTheme.typography.bodyMedium,
            color = FinnencerColors.TextSecondary,
        )
    }
}

