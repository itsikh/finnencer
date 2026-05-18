package io.itsikh.finnencer.ui.screens.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.ai.friendlyModelLabel
import io.itsikh.finnencer.ui.screens.reader.ReaderHolder
import io.itsikh.finnencer.data.entity.AiJob
import io.itsikh.finnencer.data.entity.AiJobResultKind
import io.itsikh.finnencer.data.entity.AiJobStatus
import io.itsikh.finnencer.data.entity.AiJobType
import io.itsikh.finnencer.data.entity.QueueItemKind
import io.itsikh.finnencer.data.repo.AiJobsRepository
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.components.QueueTogglePill
import io.itsikh.finnencer.ui.theme.FinnencerColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val repo: AiJobsRepository,
    private val viewModePrefs: io.itsikh.finnencer.data.repo.ViewModePreferences,
) : ViewModel() {

    val jobs: StateFlow<List<AiJob>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groupedByTicker: StateFlow<Boolean> = viewModePrefs.tasksGrouped
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setGroupedByTicker(value: Boolean) {
        viewModelScope.launch { viewModePrefs.setTasksGrouped(value) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
    }

    fun retry(id: String) {
        viewModelScope.launch { repo.retry(id) }
    }

    fun clearFinished() {
        viewModelScope.launch { repo.clearFinished() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    onBack: () -> Unit,
    onOpenPodcast: (Long) -> Unit,
    onOpenReader: () -> Unit,
    onOpenReport: (Long) -> Unit,
    onOpenTaskDetail: (String) -> Unit = {},
) {
    val vm: TasksViewModel = hiltViewModel()
    val jobs by vm.jobs.collectAsState()

    val running = jobs.filter { it.status == AiJobStatus.QUEUED.name || it.status == AiJobStatus.RUNNING.name }
    val finished = jobs.filter { it.status == AiJobStatus.COMPLETED.name }
    val failed = jobs.filter { it.status == AiJobStatus.FAILED.name || it.status == AiJobStatus.CANCELED.name }
    val grouped by vm.groupedByTicker.collectAsState()
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    // 1 Hz "now" tick so each RUNNING row's elapsed-seconds label updates
    // without the user having to leave and re-enter the screen. Only ticks
    // when something is actually running, so an idle Tasks screen stays
    // composition-quiet.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasRunning = running.any { it.status == AiJobStatus.RUNNING.name }
    androidx.compose.runtime.LaunchedEffect(hasRunning) {
        while (hasRunning) {
            kotlinx.coroutines.delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Tasks",
                        style = MaterialTheme.typography.headlineMedium,
                        color = FinnencerColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = FinnencerColors.TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.setGroupedByTicker(!grouped) }) {
                        Icon(
                            if (grouped) Icons.Default.Summarize
                            else Icons.Default.Bookmark,
                            contentDescription = if (grouped) "Switch to flat list" else "Group by ticker",
                            tint = if (grouped) FinnencerColors.Violet else FinnencerColors.TextSecondary,
                        )
                    }
                    if (finished.isNotEmpty() || failed.isNotEmpty()) {
                        TextButton(onClick = vm::clearFinished) {
                            Text("Clear", color = FinnencerColors.TextSecondary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        if (jobs.isEmpty()) {
            EmptyState(modifier = Modifier.padding(padding))
            return@Scaffold
        }
        // Shared row builder so flat (3-status-sections) and grouped
        // (per-ticker folder) paths produce identical JobRow instances.
        val jobRow: @androidx.compose.runtime.Composable (AiJob) -> Unit = { job ->
            JobRow(
                job = job,
                nowMs = nowMs,
                onOpen = {
                    if (job.status == AiJobStatus.COMPLETED.name) {
                        open(job, onOpenPodcast, onOpenReport)
                    } else {
                        onOpenTaskDetail(job.id)
                    }
                },
                onOpenPodcast = onOpenPodcast,
                onOpenReader = onOpenReader,
                onDelete = { vm.delete(job.id) },
                onRetry = { vm.retry(job.id) },
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (grouped) {
                // Folder-per-ticker view: ignore the running/finished/failed
                // split (status is already shown on each row's icon) and
                // group everything by AiJob.tickerSymbol. Status info is
                // preserved via StatusIcon on each row.
                val buckets = jobs.groupBy { job ->
                    job.tickerSymbol?.takeIf { it.isNotBlank() }
                        ?: io.itsikh.finnencer.ui.components.UNGROUPED_TICKER
                }
                val tickers = io.itsikh.finnencer.ui.components.sortedTickerGroups(buckets.keys)
                for (ticker in tickers) {
                    val bucket = buckets[ticker].orEmpty()
                    item(key = "header-$ticker") {
                        io.itsikh.finnencer.ui.components.TickerGroupHeader(
                            ticker = ticker,
                            count = bucket.size,
                            expanded = expandedGroups[ticker] ?: true,
                            onToggle = {
                                val curr = expandedGroups[ticker] ?: true
                                expandedGroups[ticker] = !curr
                            },
                        )
                    }
                    val expanded = expandedGroups[ticker] ?: true
                    if (expanded) {
                        items(bucket, key = { it.id }) { job -> jobRow(job) }
                    }
                }
            } else {
                if (running.isNotEmpty()) {
                    item { SectionHeader("Running", running.size) }
                    items(running, key = { it.id }) { job -> jobRow(job) }
                }
                if (finished.isNotEmpty()) {
                    item { SectionHeader("Finished", finished.size) }
                    items(finished, key = { it.id }) { job -> jobRow(job) }
                }
                if (failed.isNotEmpty()) {
                    item { SectionHeader("Failed", failed.size) }
                    items(failed, key = { it.id }) { job -> jobRow(job) }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

private fun open(
    job: AiJob,
    onOpenPodcast: (Long) -> Unit,
    onOpenReport: (Long) -> Unit,
) {
    val kind = job.resultKind ?: return
    when (AiJobResultKind.valueOf(kind)) {
        AiJobResultKind.PODCAST -> job.resultRefId?.toLongOrNull()?.let(onOpenPodcast)
        AiJobResultKind.EARNINGS_REPORT -> job.resultRefId?.toLongOrNull()?.let(onOpenReport)
        // Combo: tapping the card expands the summary inline; the explicit
        // "Open podcast" button inside the expanded card opens the podcast.
        else -> Unit // INLINE_TEXT / SUMMARY_AND_PODCAST render in-place when expanded
    }
}

@Composable
private fun SectionHeader(label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = FinnencerColors.TextTertiary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(FinnencerColors.SurfaceGlass)
                .border(1.dp, FinnencerColors.SurfaceBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Text("$count", style = MaterialTheme.typography.labelSmall, color = FinnencerColors.TextTertiary)
        }
    }
}

@Composable
private fun JobRow(
    job: AiJob,
    nowMs: Long,
    onOpen: () -> Unit,
    onOpenPodcast: (Long) -> Unit,
    onOpenReader: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = statusAccent(job.status)
    val hasInlineResult = job.resultText != null
    val hasNavigable = (
        job.resultKind == AiJobResultKind.PODCAST.name ||
        job.resultKind == AiJobResultKind.EARNINGS_REPORT.name
    ) && job.resultRefId != null
    val comboPodcastId: Long? = if (job.resultKind == AiJobResultKind.SUMMARY_AND_PODCAST.name) {
        job.resultRefId?.toLongOrNull()
    } else null

    GlassCard(onClick = {
        // Running / queued / failed / waiting jobs always go to the
        // detail screen so the user can see what's happening (#43).
        // Completed jobs prefer the inline expand-for-text path or
        // navigation to the produced artifact.
        val isLive = job.status == AiJobStatus.RUNNING.name ||
            job.status == AiJobStatus.QUEUED.name ||
            job.status == AiJobStatus.FAILED.name ||
            job.status == AiJobStatus.CANCELED.name
        when {
            isLive -> onOpen()
            hasNavigable -> onOpen()
            hasInlineResult -> expanded = !expanded
        }
    }) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(status = job.status, tint = statusColor)
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        job.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FinnencerColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = buildString {
                        append(typeLabel(job.type))
                        job.tickerSymbol?.let { append(" · ").append(it) }
                        if (job.status == AiJobStatus.RUNNING.name && job.startedAtMillis != null) {
                            // Use the screen-level [nowMs] tick (1 Hz) so the
                            // counter actually advances while the user is
                            // looking at it.
                            val secs = ((nowMs - job.startedAtMillis) / 1000).coerceAtLeast(0)
                            append(" · ").append(secs).append("s")
                        }
                    }
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = FinnencerColors.TextTertiary)
                    // Live stage indicator under the meta row — shows
                    // "Synthesizing audio · chunk 3/6 · 47%" etc. so the
                    // user can see at-a-glance what the worker is doing
                    // without opening the detail screen (#43).
                    if (job.status == AiJobStatus.RUNNING.name && job.currentStage != null) {
                        val stage = runCatching {
                            io.itsikh.finnencer.data.entity.AiJobStage.valueOf(job.currentStage)
                        }.getOrNull()
                        val stageLabel = stage?.displayName ?: job.currentStage
                        val parts = buildList {
                            add(stageLabel)
                            job.stageDetail?.takeIf { it.isNotBlank() }?.let(::add)
                            if (job.stageProgress > 0) add("${job.stageProgress}%")
                        }
                        Text(
                            parts.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.Violet,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    job.errorMessage?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = FinnencerColors.Coral, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (hasInlineResult) {
                    IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            tint = FinnencerColors.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                val canRetry = job.status == AiJobStatus.FAILED.name ||
                    job.status == AiJobStatus.CANCELED.name
                if (canRetry) {
                    IconButton(onClick = onRetry, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Retry",
                            tint = FinnencerColors.Violet,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = FinnencerColors.TextTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (expanded && job.resultText != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(FinnencerColors.SurfaceGlass)
                        .border(1.dp, FinnencerColors.SurfaceBorder, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                ) {
                    Column {
                        // Action chips up top so they're reachable without
                        // having to scroll past the entire prose blob.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(FinnencerColors.Violet.copy(alpha = 0.18f))
                                    .border(1.dp, FinnencerColors.Violet.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                    .clickable {
                                        ReaderHolder.store(
                                            ReaderHolder.Payload(
                                                title = job.title,
                                                body = job.resultText,
                                                attribution = job.resultModel?.let { "via ${friendlyModelLabel(it)}" },
                                            )
                                        )
                                        onOpenReader()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Read mode",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = FinnencerColors.Violet,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (comboPodcastId != null) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(FinnencerColors.Amber.copy(alpha = 0.18f))
                                        .border(1.dp, FinnencerColors.Amber.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                        .clickable { onOpenPodcast(comboPodcastId) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "▶  Open podcast",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = FinnencerColors.Amber,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            // Route the queue pill at the artifact the user
                            // actually wants to consume — not the AiJob.
                            // Before this, every Queue add from Tasks went
                            // in as BATCH_SUMMARY, which routed back to the
                            // Tasks page on tap from the Queue. For podcast
                            // jobs (PODCAST or SUMMARY_AND_PODCAST) we now
                            // queue the Podcast row id so tapping opens the
                            // player; for earnings reports we queue the
                            // report id; INLINE_TEXT (summary-only) keeps
                            // BATCH_SUMMARY so the expandable Tasks card
                            // remains the destination.
                            val pillKind: QueueItemKind
                            val pillRefId: String
                            when (job.resultKind) {
                                AiJobResultKind.PODCAST.name,
                                AiJobResultKind.SUMMARY_AND_PODCAST.name -> {
                                    pillKind = QueueItemKind.PODCAST
                                    pillRefId = job.resultRefId ?: job.id
                                }
                                AiJobResultKind.EARNINGS_REPORT.name -> {
                                    pillKind = QueueItemKind.EARNINGS_REPORT
                                    pillRefId = job.resultRefId ?: job.id
                                }
                                else -> {
                                    pillKind = QueueItemKind.BATCH_SUMMARY
                                    pillRefId = job.id
                                }
                            }
                            QueueTogglePill(
                                kind = pillKind,
                                refId = pillRefId,
                                title = job.title,
                                subtitle = job.resultModel?.let { "via ${friendlyModelLabel(it)}" },
                                tickerSymbol = job.tickerSymbol,
                            )
                        }
                        job.resultModel?.takeIf { it.isNotBlank() }?.let { id ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "via ${friendlyModelLabel(id)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = FinnencerColors.TextTertiary,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            job.resultText,
                            style = MaterialTheme.typography.bodySmall,
                            color = FinnencerColors.TextPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: String, tint: Color) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.18f))
            .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            AiJobStatus.RUNNING.name -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = tint,
            )
            AiJobStatus.QUEUED.name -> Text("•", color = tint, fontWeight = FontWeight.Bold)
            AiJobStatus.COMPLETED.name -> Icon(Icons.Default.Check, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            AiJobStatus.FAILED.name, AiJobStatus.CANCELED.name -> Icon(Icons.Default.Close, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun statusAccent(status: String): Color = when (status) {
    AiJobStatus.RUNNING.name -> FinnencerColors.Violet
    AiJobStatus.QUEUED.name -> FinnencerColors.Amber
    AiJobStatus.COMPLETED.name -> FinnencerColors.Mint
    AiJobStatus.FAILED.name, AiJobStatus.CANCELED.name -> FinnencerColors.Coral
    else -> FinnencerColors.Neutral
}

private fun typeLabel(type: String): String = when (type) {
    AiJobType.SUMMARY_BATCH.name -> "Batch summary"
    AiJobType.PODCAST_BATCH.name -> "Podcast"
    AiJobType.SUMMARY_AND_PODCAST_BATCH.name -> "Summary + podcast"
    AiJobType.REPORT_EARNINGS.name -> "Earnings report"
    AiJobType.EARNINGS_BRIEF_AND_PODCAST.name -> "Earnings brief + podcast"
    else -> type
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "No tasks yet",
            style = MaterialTheme.typography.titleMedium,
            color = FinnencerColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Long-press articles in a ticker feed, hit Summarize, and your job runs here. You can leave the app — finished tasks show a notification.",
            style = MaterialTheme.typography.bodySmall,
            color = FinnencerColors.TextSecondary,
        )
    }
}
