package io.itsikh.finnencer.ui.screens.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.dao.AiJobDao
import io.itsikh.finnencer.data.entity.AiJob
import io.itsikh.finnencer.data.entity.AiJobStage
import io.itsikh.finnencer.data.entity.AiJobStatus
import io.itsikh.finnencer.data.repo.AiJobsRepository
import io.itsikh.finnencer.logging.AppLogger
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.theme.FinnencerColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Real-time progress view for a single AI job (#43). Shows:
 *  - The stage timeline (which phase we're on, what's done, what's next)
 *  - The current stage's detail line and progress bar
 *  - A live tail of the in-app log filtered to this job's lines, behind
 *    a "Show logs" toggle so the simple view stays simple
 *  - A Retry button when the job ended in FAILED state
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    aiJobDao: AiJobDao,
    private val podcastDao: io.itsikh.finnencer.data.dao.PodcastDao,
    private val repo: AiJobsRepository,
) : ViewModel() {

    private val jobId: String = savedState.get<String>("jobId")
        ?: error("TaskDetailScreen opened without jobId")

    val job: StateFlow<AiJob?> = aiJobDao.observe(jobId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** When the validator flagged the script, the user reviews it from
     *  this screen. We observe the linked podcast row to pick up
     *  validation_notes + script_text. */
    val linkedPodcast: StateFlow<io.itsikh.finnencer.data.entity.Podcast?> = job
        .map { j -> j?.resultRefId?.toLongOrNull() }
        .distinctUntilChanged()
        .flatMapLatest { pid -> if (pid != null) podcastDao.observe(pid) else kotlinx.coroutines.flow.flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun proceedFromValidationReview() {
        viewModelScope.launch { repo.resumeFromValidationReview(jobId, podcastDao) }
    }

    fun cancelFromValidationReview() {
        viewModelScope.launch { repo.cancelFromValidationReview(jobId, podcastDao) }
    }

    /** Live tail of the in-memory log buffer, filtered to lines tagged
     *  with this job's first 8 characters of UUID. Initialized with
     *  whatever's already in the buffer; appended in real time via
     *  [AppLogger.stream]. */
    private val _logs = MutableStateFlow(initialLogs())
    val logs: StateFlow<List<AppLogger.LogEntry>> = _logs.asStateFlow()

    init {
        viewModelScope.launch {
            AppLogger.stream.collect { entry ->
                if (matches(entry)) {
                    _logs.value = (_logs.value + entry).takeLast(MAX_TAIL)
                }
            }
        }
    }

    private fun initialLogs(): List<AppLogger.LogEntry> =
        AppLogger.snapshot().filter { matches(it) }.takeLast(MAX_TAIL)

    private fun matches(entry: AppLogger.LogEntry): Boolean {
        // Match both the new tagged form "Foo[abcd1234]" and the older
        // untagged AiJobWorker / BundleSummarizer lines that mention
        // the full job UUID in the message body.
        val short = jobId.take(8)
        return entry.tag.contains("[$short]") ||
            entry.tag == "AiJobWorker" && entry.message.contains(jobId) ||
            entry.tag == "BundleSummarizer" && entry.message.contains(jobId)
    }

    fun retry() {
        viewModelScope.launch { repo.retry(jobId) }
    }

    private companion object { const val MAX_TAIL = 300 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    onBack: () -> Unit,
    onOpenResult: (AiJob) -> Unit,
) {
    val vm: TaskDetailViewModel = hiltViewModel()
    val job by vm.job.collectAsState()
    val logs by vm.logs.collectAsState()
    var showLogs by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Task",
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        val j = job
        if (j == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Loading…", color = FinnencerColors.TextSecondary)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ─── Header card ───
            GlassCard {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        j.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = FinnencerColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StatusPill(status = j.status)
                    j.stageDetail?.takeIf { it.isNotBlank() }?.let { d ->
                        Text(d, style = MaterialTheme.typography.bodyMedium, color = FinnencerColors.TextSecondary)
                    }
                    if (j.status == AiJobStatus.RUNNING.name && j.stageProgress > 0) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { j.stageProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = FinnencerColors.Violet,
                            trackColor = FinnencerColors.SurfaceGlass,
                        )
                        Text(
                            "${j.stageProgress}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.TextTertiary,
                        )
                    }
                    j.errorMessage?.takeIf { j.status == AiJobStatus.FAILED.name }?.let { err ->
                        Text(err, style = MaterialTheme.typography.bodyMedium, color = FinnencerColors.Coral)
                    }
                }
            }

            // ─── Actions ───
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (j.status == AiJobStatus.FAILED.name || j.status == AiJobStatus.CANCELED.name) {
                    FilledTonalButton(
                        onClick = { vm.retry() },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Retry")
                    }
                }
                if (j.status == AiJobStatus.COMPLETED.name && j.resultRefId != null) {
                    FilledTonalButton(onClick = { onOpenResult(j) }) {
                        Text("Open result")
                    }
                }
            }

            // ─── Validator review (PENDING_REVIEW only) ───
            if (j.status == AiJobStatus.PENDING_REVIEW.name) {
                val podcast by vm.linkedPodcast.collectAsState()
                ValidatorReviewCard(
                    notes = podcast?.validationNotes,
                    validatorModel = podcast?.validationModel,
                    script = podcast?.scriptText,
                    onProceed = { vm.proceedFromValidationReview() },
                    onCancel = { vm.cancelFromValidationReview() },
                )
            } else {
                // Also surface validator notes on a podcast that already
                // shipped — informational. Hidden when there are none.
                val podcast by vm.linkedPodcast.collectAsState()
                podcast?.validationNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                    GlassCard {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "VALIDATOR NOTES",
                                style = MaterialTheme.typography.labelSmall,
                                color = FinnencerColors.TextTertiary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(notes, style = MaterialTheme.typography.bodyMedium, color = FinnencerColors.TextPrimary)
                            podcast?.validationModel?.let { model ->
                                Text(
                                    "via $model",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = FinnencerColors.TextTertiary,
                                )
                            }
                        }
                    }
                }
            }

            // ─── Stage timeline ───
            GlassCard {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text(
                        "Stages",
                        style = MaterialTheme.typography.labelMedium,
                        color = FinnencerColors.TextTertiary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    val current = j.currentStage?.let {
                        runCatching { AiJobStage.valueOf(it) }.getOrNull()
                    }
                    val stagesShown = stagesToShow(j)
                    stagesShown.forEach { stage ->
                        StageRow(
                            stage = stage,
                            jobState = classifyStage(stage, current, j.status, stagesShown),
                            isCurrent = stage == current,
                            isFinalFailureSlot = stage == AiJobStage.FAILED && j.status == AiJobStatus.FAILED.name,
                        )
                    }
                }
            }

            // ─── Logs toggle ───
            TextButton(onClick = { showLogs = !showLogs }) {
                Text(if (showLogs) "Hide logs" else "Show logs (${logs.size})", color = FinnencerColors.Violet)
            }
            if (showLogs) {
                LogTail(entries = logs)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

private fun stagesToShow(j: AiJob): List<AiJobStage> {
    // Hide stages that don't apply to this job's type so the timeline
    // doesn't suggest the worker skipped a phase it never had.
    val baseFlow = listOf(
        AiJobStage.QUEUED,
        AiJobStage.CONNECTIVITY_CHECK,
        AiJobStage.WAITING_FOR_NETWORK,
    )
    val type = runCatching {
        io.itsikh.finnencer.data.entity.AiJobType.valueOf(j.type)
    }.getOrNull()
    val middle: List<AiJobStage> = when (type) {
        io.itsikh.finnencer.data.entity.AiJobType.SUMMARY_BATCH -> listOf(AiJobStage.GENERATING_SUMMARY)
        io.itsikh.finnencer.data.entity.AiJobType.REPORT_EARNINGS -> listOf(AiJobStage.GENERATING_REPORT)
        io.itsikh.finnencer.data.entity.AiJobType.PODCAST_BATCH -> listOf(
            AiJobStage.GENERATING_SCRIPT,
            AiJobStage.PERSISTING_SCRIPT,
            AiJobStage.VALIDATING_SCRIPT,
            AiJobStage.SYNTHESIZING_AUDIO,
        )
        io.itsikh.finnencer.data.entity.AiJobType.SUMMARY_AND_PODCAST_BATCH -> listOf(
            AiJobStage.GENERATING_SUMMARY,
            AiJobStage.GENERATING_SCRIPT,
            AiJobStage.PERSISTING_SCRIPT,
            AiJobStage.VALIDATING_SCRIPT,
            AiJobStage.SYNTHESIZING_AUDIO,
        )
        io.itsikh.finnencer.data.entity.AiJobType.EARNINGS_BRIEF_AND_PODCAST -> listOf(
            AiJobStage.GENERATING_REPORT,
            AiJobStage.GENERATING_SCRIPT,
            AiJobStage.PERSISTING_SCRIPT,
            AiJobStage.VALIDATING_SCRIPT,
            AiJobStage.SYNTHESIZING_AUDIO,
        )
        null -> emptyList()
    }
    val terminal = if (j.status == AiJobStatus.FAILED.name) {
        listOf(AiJobStage.FINALIZING, AiJobStage.FAILED)
    } else {
        listOf(AiJobStage.FINALIZING, AiJobStage.DONE)
    }
    return baseFlow + middle + terminal
}

private enum class StageState { DONE, CURRENT, UPCOMING, FAILED }

private fun classifyStage(
    stage: AiJobStage,
    current: AiJobStage?,
    jobStatus: String,
    order: List<AiJobStage>,
): StageState {
    if (jobStatus == AiJobStatus.COMPLETED.name) {
        return if (stage == AiJobStage.DONE) StageState.CURRENT else StageState.DONE
    }
    if (jobStatus == AiJobStatus.FAILED.name) {
        if (stage == AiJobStage.FAILED) return StageState.FAILED
        val currentIdx = order.indexOf(current).takeIf { it >= 0 } ?: order.indexOf(AiJobStage.FAILED)
        val stageIdx = order.indexOf(stage)
        return if (stageIdx < currentIdx) StageState.DONE else StageState.UPCOMING
    }
    if (current == null) return StageState.UPCOMING
    val currentIdx = order.indexOf(current)
    val stageIdx = order.indexOf(stage)
    return when {
        stageIdx < currentIdx -> StageState.DONE
        stageIdx == currentIdx -> StageState.CURRENT
        else -> StageState.UPCOMING
    }
}

@Composable
private fun StageRow(
    stage: AiJobStage,
    jobState: StageState,
    isCurrent: Boolean,
    isFinalFailureSlot: Boolean,
) {
    val color = when (jobState) {
        StageState.DONE -> FinnencerColors.Mint
        StageState.CURRENT -> FinnencerColors.Violet
        StageState.UPCOMING -> FinnencerColors.TextTertiary.copy(alpha = 0.5f)
        StageState.FAILED -> FinnencerColors.Coral
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.2f))
                .border(1.dp, color.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (jobState) {
                StageState.DONE -> Icon(Icons.Default.Check, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                StageState.CURRENT -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = color,
                )
                StageState.UPCOMING -> Text("·", color = color)
                StageState.FAILED -> Icon(Icons.Default.Close, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.size(10.dp))
        Text(
            stage.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = if (jobState == StageState.UPCOMING) FinnencerColors.TextTertiary
                    else FinnencerColors.TextPrimary,
            fontWeight = if (isCurrent || isFinalFailureSlot) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun StatusPill(status: String) {
    val (label, color) = when (status) {
        AiJobStatus.QUEUED.name -> "Queued" to FinnencerColors.Amber
        AiJobStatus.RUNNING.name -> "Running" to FinnencerColors.Violet
        AiJobStatus.COMPLETED.name -> "Completed" to FinnencerColors.Mint
        AiJobStatus.FAILED.name -> "Failed" to FinnencerColors.Coral
        AiJobStatus.CANCELED.name -> "Canceled" to FinnencerColors.TextTertiary
        AiJobStatus.PENDING_REVIEW.name -> "Needs review" to FinnencerColors.Amber
        else -> status to FinnencerColors.TextTertiary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label.uppercase(), color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LogTail(entries: List<AppLogger.LogEntry>) {
    val listState = rememberLazyListState()
    // Auto-scroll to the bottom as new entries land.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }
    GlassCard {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Log",
                style = MaterialTheme.typography.labelMedium,
                color = FinnencerColors.TextTertiary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            if (entries.isEmpty()) {
                Text(
                    "No log lines from this job yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FinnencerColors.TextTertiary,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                ) {
                    items(entries) { e ->
                        val tint = when (e.level.name) {
                            "ERROR" -> FinnencerColors.Coral
                            "WARN" -> FinnencerColors.Amber
                            else -> FinnencerColors.TextSecondary
                        }
                        Text(
                            "[${e.level.name.first()}] ${e.tag}: ${e.message.take(280)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ValidatorReviewCard(
    notes: String?,
    validatorModel: String?,
    script: String?,
    onProceed: () -> Unit,
    onCancel: () -> Unit,
) {
    var showScript by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    GlassCard {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "VALIDATOR FLAGGED THIS SCRIPT",
                style = MaterialTheme.typography.labelSmall,
                color = FinnencerColors.Amber,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                notes?.takeIf { it.isNotBlank() }
                    ?: "The validator stopped before audio rendering, but didn't include a reason.",
                style = MaterialTheme.typography.bodyMedium,
                color = FinnencerColors.TextPrimary,
            )
            validatorModel?.let { model ->
                Text(
                    "via $model",
                    style = MaterialTheme.typography.labelSmall,
                    color = FinnencerColors.TextTertiary,
                )
            }
            if (!script.isNullOrBlank()) {
                TextButton(onClick = { showScript = !showScript }) {
                    Text(
                        if (showScript) "Hide script" else "Read script (${script.length} chars)",
                        color = FinnencerColors.Violet,
                    )
                }
                if (showScript) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(FinnencerColors.Surface)
                            .border(1.dp, FinnencerColors.SurfaceBorder, RoundedCornerShape(10.dp)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp),
                        ) {
                            Text(
                                script,
                                style = MaterialTheme.typography.bodySmall,
                                color = FinnencerColors.TextPrimary,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onProceed) {
                    Text("Proceed anyway")
                }
                FilledTonalButton(
                    onClick = { confirmCancel = true },
                ) {
                    Text("Cancel", color = FinnencerColors.Coral)
                }
            }
        }
    }
    if (confirmCancel) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel this podcast?", color = FinnencerColors.TextPrimary) },
            text = {
                Text(
                    "The script will be marked failed. You can regenerate a fresh podcast from the same source later.",
                    color = FinnencerColors.TextPrimary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    onCancel()
                }) {
                    Text("Cancel podcast", color = FinnencerColors.Coral, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) {
                    Text("Keep", color = FinnencerColors.Violet)
                }
            },
            containerColor = FinnencerColors.Surface,
            titleContentColor = FinnencerColors.TextPrimary,
            textContentColor = FinnencerColors.TextPrimary,
        )
    }
}
