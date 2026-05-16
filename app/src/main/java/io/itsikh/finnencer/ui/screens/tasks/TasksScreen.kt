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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import io.itsikh.finnencer.data.repo.AiJobsRepository
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.theme.FinnencerColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val repo: AiJobsRepository,
) : ViewModel() {

    val jobs: StateFlow<List<AiJob>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
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
) {
    val vm: TasksViewModel = hiltViewModel()
    val jobs by vm.jobs.collectAsState()

    val running = jobs.filter { it.status == AiJobStatus.QUEUED.name || it.status == AiJobStatus.RUNNING.name }
    val finished = jobs.filter { it.status == AiJobStatus.COMPLETED.name }
    val failed = jobs.filter { it.status == AiJobStatus.FAILED.name || it.status == AiJobStatus.CANCELED.name }

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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (running.isNotEmpty()) {
                item { SectionHeader("Running", running.size) }
                items(running, key = { it.id }) { job ->
                    JobRow(
                        job = job,
                        onOpen = { open(job, onOpenPodcast) },
                        onOpenPodcast = onOpenPodcast,
                        onOpenReader = onOpenReader,
                        onDelete = { vm.delete(job.id) },
                    )
                }
            }
            if (finished.isNotEmpty()) {
                item { SectionHeader("Finished", finished.size) }
                items(finished, key = { it.id }) { job ->
                    JobRow(
                        job = job,
                        onOpen = { open(job, onOpenPodcast) },
                        onOpenPodcast = onOpenPodcast,
                        onOpenReader = onOpenReader,
                        onDelete = { vm.delete(job.id) },
                    )
                }
            }
            if (failed.isNotEmpty()) {
                item { SectionHeader("Failed", failed.size) }
                items(failed, key = { it.id }) { job ->
                    JobRow(
                        job = job,
                        onOpen = { open(job, onOpenPodcast) },
                        onOpenPodcast = onOpenPodcast,
                        onOpenReader = onOpenReader,
                        onDelete = { vm.delete(job.id) },
                    )
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

private fun open(job: AiJob, onOpenPodcast: (Long) -> Unit) {
    val kind = job.resultKind ?: return
    when (AiJobResultKind.valueOf(kind)) {
        AiJobResultKind.PODCAST -> job.resultRefId?.toLongOrNull()?.let(onOpenPodcast)
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
    onOpen: () -> Unit,
    onOpenPodcast: (Long) -> Unit,
    onOpenReader: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = statusAccent(job.status)
    val hasInlineResult = job.resultText != null
    val hasNavigable = job.resultKind == AiJobResultKind.PODCAST.name && job.resultRefId != null
    val comboPodcastId: Long? = if (job.resultKind == AiJobResultKind.SUMMARY_AND_PODCAST.name) {
        job.resultRefId?.toLongOrNull()
    } else null

    GlassCard(onClick = {
        if (hasNavigable) onOpen() else if (hasInlineResult) expanded = !expanded
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
                            val secs = ((System.currentTimeMillis() - job.startedAtMillis) / 1000).coerceAtLeast(0)
                            append(" · ").append(secs).append("s")
                        }
                    }
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = FinnencerColors.TextTertiary)
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
