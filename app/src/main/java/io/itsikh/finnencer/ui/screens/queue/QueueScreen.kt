package io.itsikh.finnencer.ui.screens.queue

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.data.entity.QueueItem
import io.itsikh.finnencer.data.entity.QueueItemKind
import io.itsikh.finnencer.logging.AppLogger
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
    val grouped by vm.groupedByTicker.collectAsState()

    // Session-only expand/collapse map for ticker groups. Default
    // expanded so the first time you flip into grouped mode you see
    // your items, not empty headers.
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    val listState = rememberLazyListState()
    val renderItems = if (tab == QueueTab.TODO) todo else done

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
                        // Toggle: flat list ↔ grouped-by-ticker
                        IconButton(onClick = { vm.setGroupedByTicker(!grouped) }) {
                            Icon(
                                if (grouped) androidx.compose.material.icons.Icons.Default.Summarize
                                else androidx.compose.material.icons.Icons.Default.Bookmark,
                                contentDescription = if (grouped) "Switch to flat list" else "Group by ticker",
                                tint = if (grouped) FinnencerColors.Violet else FinnencerColors.TextSecondary,
                            )
                        }
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
                // Shared row-builder so the flat and grouped paths
                // produce identical QueueRow instances (same tap /
                // mark-done / reorder semantics).
                val rowFor: @androidx.compose.runtime.Composable (
                    item: io.itsikh.finnencer.data.entity.QueueItem,
                    isFirst: Boolean,
                    isLast: Boolean,
                    showReorder: Boolean,
                ) -> Unit = { item, isFirst, isLast, showReorder ->
                    val isPodcast = item.kind == QueueItemKind.PODCAST.name
                    QueueRow(
                        item = item,
                        selected = item.id in selected,
                        selectionMode = isSelecting,
                        isFirst = isFirst,
                        isLast = isLast,
                        showReorder = showReorder,
                        primaryActionIsPlay = isPodcast,
                        onTap = {
                            AppLogger.i("Queue", "row tap: kind=${item.kind} refId=${item.refId} selecting=$isSelecting")
                            if (isSelecting) {
                                vm.toggleSelection(item.id)
                            } else {
                                when (item.kind) {
                                    QueueItemKind.ARTICLE.name -> onOpenArticle(item.refId)
                                    QueueItemKind.ARTICLE_SUMMARY.name -> onOpenArticle(item.refId)
                                    QueueItemKind.BATCH_SUMMARY.name -> onOpenTasks()
                                    QueueItemKind.EARNINGS_REPORT.name ->
                                        item.refId.toLongOrNull()?.let(onOpenReport)
                                    QueueItemKind.PODCAST.name -> {
                                        val pid = item.refId.toLongOrNull()
                                        if (pid != null) onOpenPodcast(pid)
                                        else AppLogger.w("Queue", "podcast row had non-numeric refId='${item.refId}'")
                                    }
                                }
                            }
                        },
                        onLongPress = { vm.toggleSelection(item.id) },
                        onComplete = {
                            if (tab == QueueTab.TODO) vm.markDone(item.id)
                            else vm.markUndone(item.id)
                        },
                        onPrimaryAction = {
                            if (isPodcast) {
                                item.refId.toLongOrNull()?.let(onOpenPodcast)
                                    ?: vm.markDone(item.id)
                            } else if (tab == QueueTab.TODO) {
                                vm.markDone(item.id)
                            } else {
                                vm.markUndone(item.id)
                            }
                        },
                        onMoveUp = { vm.moveTodoUp(item.id) },
                        onMoveDown = { vm.moveTodoDown(item.id) },
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (tab == QueueTab.TODO && !grouped) {
                        item {
                            Text(
                                "Tap ↑ / ↓ to reorder. Long-press a row to multi-select.",
                                style = MaterialTheme.typography.labelSmall,
                                color = FinnencerColors.TextTertiary,
                            )
                        }
                    }

                    if (grouped) {
                        // Group by tickerSymbol with null/blank → "Other".
                        // Within each ticker, items keep their existing
                        // sort order (sortOrder for TODO, completed-at
                        // for DONE — already the order in renderItems).
                        val buckets = renderItems.groupBy { item ->
                            item.tickerSymbol?.takeIf { it.isNotBlank() }
                                ?: io.itsikh.finnencer.ui.components.UNGROUPED_TICKER
                        }
                        val tickers = io.itsikh.finnencer.ui.components.sortedTickerGroups(buckets.keys)
                        for (ticker in tickers) {
                            val bucketItems = buckets[ticker].orEmpty()
                            item(key = "header-$ticker") {
                                io.itsikh.finnencer.ui.components.TickerGroupHeader(
                                    ticker = ticker,
                                    count = bucketItems.size,
                                    expanded = expandedGroups[ticker] ?: true,
                                    onToggle = {
                                        val curr = expandedGroups[ticker] ?: true
                                        expandedGroups[ticker] = !curr
                                    },
                                )
                            }
                            val expanded = expandedGroups[ticker] ?: true
                            if (expanded) {
                                items(bucketItems, key = { it.id }) { row ->
                                    rowFor(row, false, false, false)
                                }
                            }
                        }
                    } else {
                        itemsIndexed(renderItems, key = { _, it -> it.id }) { index, item ->
                            rowFor(
                                item,
                                index == 0,
                                index == renderItems.lastIndex,
                                tab == QueueTab.TODO && !isSelecting,
                            )
                        }
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
    isFirst: Boolean,
    isLast: Boolean,
    showReorder: Boolean,
    primaryActionIsPlay: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onComplete: () -> Unit,
    onPrimaryAction: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val rowColor = if (selected) FinnencerColors.Violet.copy(alpha = 0.22f) else Color.Transparent
    // combinedClickable lives on the OUTER Box so the entire row width
    // (including any padding around the trailing controls) is tappable.
    // Nested IconButtons (PlayArrow, ↑/↓, Mark done) still claim their
    // own taps via their internal clickable, which beats the outer one
    // in Compose's inside-out pointer dispatch.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        GlassCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
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
                        // Primary right-edge action.
                        //   - Podcast rows: ▶ Play — taps go straight into
                        //     the player (#32). Mark-done happens
                        //     automatically when playback ends.
                        //   - Everything else: ✓ / ↶ Mark done / undone.
                        IconButton(onClick = onPrimaryAction) {
                            val (icon, desc, tint) = when {
                                primaryActionIsPlay -> Triple(
                                    Icons.Default.PlayArrow,
                                    "Play podcast",
                                    FinnencerColors.Violet,
                                )
                                item.completedAtMillis == null -> Triple(
                                    Icons.Default.Check,
                                    "Mark done",
                                    FinnencerColors.Mint,
                                )
                                else -> Triple(
                                    Icons.Default.Undo,
                                    "Move back to To do",
                                    FinnencerColors.Violet,
                                )
                            }
                            Icon(icon, desc, tint = tint, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                if (showReorder) {
                    // Replaces the long-press-drag handle (#33) with two
                    // explicit ↑ / ↓ taps. Disabled at list ends so the
                    // user gets visual feedback rather than a silent no-op.
                    Column(
                        modifier = Modifier.width(36.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        IconButton(
                            onClick = onMoveUp,
                            enabled = !isFirst,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "Move up",
                                tint = if (isFirst) FinnencerColors.TextTertiary.copy(alpha = 0.35f)
                                       else FinnencerColors.TextSecondary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        IconButton(
                            onClick = onMoveDown,
                            enabled = !isLast,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Move down",
                                tint = if (isLast) FinnencerColors.TextTertiary.copy(alpha = 0.35f)
                                       else FinnencerColors.TextSecondary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
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

