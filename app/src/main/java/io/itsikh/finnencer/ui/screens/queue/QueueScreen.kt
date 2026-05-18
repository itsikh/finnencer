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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.data.entity.QueueItem
import io.itsikh.finnencer.data.entity.QueueItemKind
import io.itsikh.finnencer.logging.AppLogger
import io.itsikh.finnencer.ui.theme.FinnencerColors
import io.itsikh.finnencer.ui.theme.MonoStyles

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
                    title = { QueueTitle(label = "${selected.size} SELECTED", sub = "TAP A ROW TO TOGGLE") },
                    navigationIcon = { QueueChip(label = "✕", onClick = vm::clearSelection) },
                    actions = {
                        if (tab == QueueTab.TODO) {
                            QueueChip(label = "DONE", accent = FinnencerColors.Mint, onClick = { vm.markSelectedDone() })
                        } else {
                            QueueChip(label = "UNDO", accent = FinnencerColors.Violet, onClick = { vm.markSelectedUndone() })
                        }
                        QueueChip(
                            label = "DELETE",
                            accent = if (selected.isNotEmpty()) FinnencerColors.Coral else FinnencerColors.TextTertiary,
                            onClick = { if (selected.isNotEmpty()) deleteSelectedConfirm = true },
                        )
                        Box {
                            QueueChip(label = "···", onClick = { overflowOpen = true })
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
                        Spacer(Modifier.size(8.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            } else {
                TopAppBar(
                    title = {
                        QueueTitle(
                            label = "QUEUE",
                            sub = "${todo.size} TO DO  ·  ${done.size} DONE",
                        )
                    },
                    navigationIcon = { QueueChip(label = "← BACK", onClick = onBack) },
                    actions = {
                        QueueChip(
                            label = if (grouped) "FLAT" else "GROUP",
                            accent = if (grouped) FinnencerColors.Violet else FinnencerColors.TextSecondary,
                            border = if (grouped) FinnencerColors.Violet else FinnencerColors.HairlineStrong,
                            onClick = { vm.setGroupedByTicker(!grouped) },
                        )
                        if (tab == QueueTab.DONE && done.isNotEmpty()) {
                            QueueChip(
                                label = "CLEAR",
                                accent = FinnencerColors.Coral,
                                onClick = { clearDoneConfirm = true },
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TerminalTabs(
                current = tab,
                todoCount = todo.size,
                doneCount = done.size,
                onPick = { vm.setTab(it) },
            )
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
                    contentPadding = PaddingValues(top = 0.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    if (tab == QueueTab.TODO && !grouped) {
                        item {
                            Text(
                                "TAP ↑ / ↓ TO REORDER · LONG-PRESS TO MULTI-SELECT",
                                style = MonoStyles.BrandSub,
                                color = FinnencerColors.TextTertiary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                    }

                    if (grouped) {
                        // Group by tickerSymbol when set; otherwise fall
                        // back to parsing the leading segment of the
                        // title (matches the same TICKER pattern as
                        // PodcastLibrary). This covers older rows queued
                        // before the Tasks-screen pill started passing
                        // tickerSymbol (#44) AND any row whose tagger
                        // didn't have a ticker to hand.
                        val buckets = renderItems.groupBy { item ->
                            val tagged = item.tickerSymbol?.takeIf { it.isNotBlank() }
                            tagged
                                ?: io.itsikh.finnencer.ui.components.tickerFromPodcastTitle(item.title)
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
    // Highlight a faint violet wash when this row is part of a
    // multi-select. Hairline below the row provides the only chrome —
    // no card background.
    val rowColor = if (selected) FinnencerColors.Violet.copy(alpha = 0.12f) else Color.Transparent
    // combinedClickable lives on the OUTER Box so the entire row width
    // (including any padding around the trailing controls) is tappable.
    // Nested IconButtons (PlayArrow, ↑/↓, Mark done) still claim their
    // own taps via their internal clickable, which beats the outer one
    // in Compose's inside-out pointer dispatch.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowColor)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
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
                            item.title.uppercase(),
                            style = MonoStyles.NavLabel,
                            color = FinnencerColors.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sub = item.subtitle
                        if (!sub.isNullOrBlank()) {
                            Spacer(Modifier.size(2.dp))
                            Text(
                                sub.uppercase(),
                                style = MonoStyles.BrandSub,
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
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(FinnencerColors.Hairline))
    }
}

/**
 * Compact bordered chip — terminal-style — that identifies a queue
 * item by its kind. When a ticker is known we show the symbol so the
 * user can scan the queue by stock at a glance; otherwise we fall
 * back to a 3-letter kind tag (ART / SUM / RPT / POD).
 */
@Composable
private fun KindBadge(kind: String, ticker: String?) {
    val (label, color) = kindTag(kind, ticker)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(label, style = MonoStyles.Chip, color = color)
    }
}

private fun kindTag(kind: String, ticker: String?): Pair<String, Color> {
    val color = when (kind) {
        QueueItemKind.ARTICLE.name -> FinnencerColors.Violet
        QueueItemKind.ARTICLE_SUMMARY.name -> FinnencerColors.Mint
        QueueItemKind.BATCH_SUMMARY.name -> FinnencerColors.Amber
        QueueItemKind.EARNINGS_REPORT.name -> FinnencerColors.Violet
        QueueItemKind.PODCAST.name -> FinnencerColors.Coral
        else -> FinnencerColors.TextSecondary
    }
    val kindLabel = when (kind) {
        QueueItemKind.ARTICLE.name -> "ART"
        QueueItemKind.ARTICLE_SUMMARY.name -> "SUM"
        QueueItemKind.BATCH_SUMMARY.name -> "SUM"
        QueueItemKind.EARNINGS_REPORT.name -> "RPT"
        QueueItemKind.PODCAST.name -> "POD"
        else -> "ITM"
    }
    val label = ticker?.takeIf { it.isNotBlank() }?.let { "$it·$kindLabel" } ?: kindLabel
    return label to color
}

/**
 * Brand-mark title cluster — caps mono label + sub. Used in both
 * normal and selection-mode top bars so they share alignment with the
 * rest of the Terminal Pro screens.
 */
@Composable
private fun QueueTitle(label: String, sub: String) {
    Column {
        Text(label, style = MonoStyles.Brand, color = FinnencerColors.TextPrimary)
        Text(sub, style = MonoStyles.BrandSub, color = FinnencerColors.TextTertiary)
    }
}

/**
 * Compact tappable chip with a hairline border. The default look is
 * dim grey; pass [accent]/[border] for colored variants like the
 * DONE / DELETE / CLEAR affordances. Minimum 44dp tall so the tap
 * target meets the accessibility floor.
 */
@Composable
private fun QueueChip(
    label: String,
    accent: Color = FinnencerColors.TextSecondary,
    border: Color = FinnencerColors.HairlineStrong,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MonoStyles.NavLabel, color = accent)
    }
}

/**
 * Two-tab segment for TO DO / DONE — replaces Material's TabRow with
 * a denser, terminal-style alternative. The active tab gets a 2dp
 * violet underline; the inactive tab stays dim.
 */
@Composable
private fun TerminalTabs(
    current: QueueTab,
    todoCount: Int,
    doneCount: Int,
    onPick: (QueueTab) -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(FinnencerColors.Hairline))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalTab(
            label = "TO DO  $todoCount",
            active = current == QueueTab.TODO,
            modifier = Modifier.weight(1f),
            onClick = { onPick(QueueTab.TODO) },
        )
        TerminalTab(
            label = "DONE  $doneCount",
            active = current == QueueTab.DONE,
            modifier = Modifier.weight(1f),
            onClick = { onPick(QueueTab.DONE) },
        )
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(FinnencerColors.Hairline))
}

@Composable
private fun TerminalTab(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = MonoStyles.NavLabel,
            color = if (active) FinnencerColors.TextPrimary else FinnencerColors.TextSecondary,
        )
        Spacer(Modifier.size(8.dp))
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(2.dp)
                .background(if (active) FinnencerColors.Violet else Color.Transparent),
        )
    }
}

@Composable
private fun EmptyQueue(tab: QueueTab, onOpenTasks: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (tab == QueueTab.TODO) "QUEUE EMPTY" else "NOTHING DONE YET",
            style = MonoStyles.Brand,
            color = FinnencerColors.TextPrimary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (tab == QueueTab.TODO)
                "SAVE ARTICLES · SUMMARIES · REPORTS · PODCASTS FROM ANYWHERE."
            else
                "TAP ✓ ON A TO-DO ITEM TO MARK IT DONE.",
            style = MonoStyles.BrandSub,
            color = FinnencerColors.TextSecondary,
        )
    }
}

