@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.itsikh.finnencer.ui.screens.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.LaunchedEffect
import io.itsikh.finnencer.data.ai.BundleSummarizer
import io.itsikh.finnencer.data.dao.ScoredArticleRow
import io.itsikh.finnencer.data.entity.ArticleCategory
import io.itsikh.finnencer.data.entity.EarningsEvent
import io.itsikh.finnencer.data.entity.EarningsStatus
import io.itsikh.finnencer.data.entity.ReportTier
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.screens.earnings.TierPickerSheetCore
import io.itsikh.finnencer.ui.theme.FinnencerColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TickerFeedScreen(
    onBack: () -> Unit,
    onOpenArticle: (articleId: String) -> Unit,
    onOpenReport: (reportId: Long) -> Unit,
    onOpenPodcast: (podcastId: Long) -> Unit,
    onOpenReader: () -> Unit = {},
) {
    val vm: TickerFeedViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val pastEarnings by vm.pastEarnings.collectAsState()
    val earningsReports by vm.earningsReports.collectAsState()
    val earningsBusy by vm.earningsBusy.collectAsState()
    val earningsError by vm.earningsError.collectAsState()
    val picker by vm.picker.collectAsState()
    val selection by vm.selection.collectAsState()
    val batchSheet by vm.batchSheet.collectAsState()
    val articleAction by vm.action.collectAsState()
    val syncRunning by vm.syncRunning.collectAsState()
    val showDiagnoseButtons by vm.showDiagnoseButtons.collectAsState()
    val moveState by vm.move.collectAsState()
    var earningsPodcastTarget by remember { mutableStateOf<EarningsEvent?>(null) }

    LaunchedEffect(batchSheet.producedPodcastId) {
        batchSheet.producedPodcastId?.let { pid ->
            vm.closeBatchSheet()
            onOpenPodcast(pid)
        }
    }

    LaunchedEffect(picker.producedReportId) {
        picker.producedReportId?.let { id ->
            vm.closePicker()
            onOpenReport(id)
        }
    }
    val rows by vm.rows.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (selection.isNotEmpty()) {
                SelectionActionBar(
                    count = selection.size,
                    onCancel = vm::clearSelection,
                    onSummarize = vm::openBatchSheet,
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.ticker?.symbol ?: "—",
                            style = MaterialTheme.typography.headlineMedium,
                            color = FinnencerColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            state.ticker?.name ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = FinnencerColors.TextPrimary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = FinnencerColors.TextSecondary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Top-of-screen sync indicator. Shows whenever the periodic
            // or one-off sync worker is RUNNING — so the toolbar refresh
            // button has visible effect.
            if (syncRunning) {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = FinnencerColors.Violet,
                    trackColor = FinnencerColors.SurfaceGlass,
                )
            } else {
                Spacer(Modifier.height(2.dp)) // reserve same vertical slot
            }
            FilterRow(
                minScore = state.filters.minScore,
                onMinScore = vm::setMinScore,
                category = state.filters.category,
                onCategory = vm::setCategory,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    MoveExplanationCard(
                        state = moveState,
                        onExplain = { vm.explainMove(force = false) },
                        onRegenerate = { vm.explainMove(force = true) },
                    )
                }
                // Earnings section is always rendered (even when empty)
                // so users can find the feature. Empty state explains why
                // there's nothing yet and offers a one-tap sync.
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    ) {
                        Text(
                            "PAST EARNINGS",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.TextTertiary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        val refreshingEarnings by vm.earningsSyncing.collectAsState()
                        if (refreshingEarnings) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = FinnencerColors.Violet,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            androidx.compose.material3.TextButton(
                                onClick = vm::refreshEarningsNow,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    "Sync",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = FinnencerColors.Violet,
                                )
                            }
                            // "Diagnose XBRL" pulls SEC EDGAR's parsed
                            // financial facts for this ticker and shows
                            // the last 4 quarters in a dialog. Hidden by
                            // default — flip on in Settings → App when
                            // troubleshooting EDGAR.
                            if (showDiagnoseButtons) {
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(FinnencerColors.Amber.copy(alpha = 0.18f))
                                        .border(1.dp, FinnencerColors.Amber.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                        .clickable(onClick = vm::runXbrlDiagnose)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        "Diagnose XBRL",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = FinnencerColors.Amber,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
                if (pastEarnings.isEmpty()) {
                    item {
                        EarningsEmptyState(
                            syncError = vm.earningsSyncError.collectAsState().value,
                            showDiagnose = showDiagnoseButtons,
                            onDiagnose = vm::runXbrlDiagnose,
                        )
                    }
                } else {
                    items(pastEarnings, key = { "earn-${it.id}" }) { event ->
                        val reportsForEvent = earningsReports.filter { it.earningsEventId == event.id }
                        EarningsCard(
                            event = event,
                            reports = reportsForEvent,
                            busyTier = earningsBusy[event.id],
                            errorMessage = earningsError[event.id],
                            onClearError = { vm.clearEarningsError(event.id) },
                            onHighlights = {
                                vm.requestEarningsReport(event.id, ReportTier.BRIEF) { id -> onOpenReport(id) }
                            },
                            onDeepDive = {
                                vm.requestEarningsReport(event.id, ReportTier.DEEP) { id -> onOpenReport(id) }
                            },
                            onMakePodcast = { earningsPodcastTarget = event },
                            onOpenReport = { id -> onOpenReport(id) },
                            onDeleteReport = { id -> vm.deleteReport(id) },
                            onOpenReader = { report ->
                                io.itsikh.finnencer.ui.screens.reader.ReaderHolder.store(
                                    io.itsikh.finnencer.ui.screens.reader.ReaderHolder.Payload(
                                        title = report.title,
                                        body = report.contentMarkdown,
                                        attribution = io.itsikh.finnencer.data.ai.friendlyModelLabel(report.model)?.let { "via $it" },
                                    )
                                )
                                onOpenReader()
                            },
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "NEWS",
                        style = MaterialTheme.typography.labelSmall,
                        color = FinnencerColors.TextTertiary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (rows.isEmpty()) {
                    item { EmptyFeedInline() }
                } else {
                    items(rows, key = { "art-${it.id}" }) { row ->
                        val isSelected = row.id in selection
                        val inSelectMode = selection.isNotEmpty()
                        ArticleRowCard(
                            row = row,
                            selected = isSelected,
                            inSelectMode = inSelectMode,
                            onTap = {
                                if (inSelectMode) vm.toggleSelect(row.id)
                                else onOpenArticle(row.id)
                            },
                            onLongPress = { vm.toggleSelect(row.id) },
                            onMore = { vm.openArticleAction(row.id) },
                        )
                    }
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }

    picker.event?.let { event ->
        TierPickerSheetCore(
            event = event,
            generating = picker.generating,
            error = picker.error,
            onClose = vm::closePicker,
            onPick = vm::generateReport,
        )
    }

    if (batchSheet.open) {
        BatchActionSheet(
            state = batchSheet,
            selectionSize = selection.size,
            onClose = vm::closeBatchSheet,
            onGenerate = { pages, minutes, prompt ->
                if (minutes != null) vm.summarizeBatchWithPodcast(pages, minutes, prompt)
                else vm.summarizeBatch(pages, prompt)
            },
        )
    }

    if (articleAction.article != null) {
        ArticleActionSheet(
            state = articleAction,
            onClose = vm::closeArticleAction,
            onApplyOverride = vm::applyOverride,
            onClearOverride = vm::clearOverride,
            onRescoreWithNote = vm::rescoreWithNote,
        )
    }

    val xbrlDiag by vm.xbrlDiag.collectAsState()
    if (xbrlDiag.ticker != null) {
        XbrlDiagDialog(state = xbrlDiag, onDismiss = vm::closeXbrlDiag)
    }

    earningsPodcastTarget?.let { target ->
        EarningsPodcastDialog(
            quarter = "Q${target.fiscalQuarter} ${target.fiscalYear}",
            onPick = { minutes ->
                vm.requestEarningsPodcast(
                    eventId = target.id,
                    eventLabel = "Q${target.fiscalQuarter} ${target.fiscalYear}",
                    minutes = minutes,
                    customPrompt = null,
                )
                earningsPodcastTarget = null
            },
            onDismiss = { earningsPodcastTarget = null },
        )
    }
}

@Composable
private fun EarningsPodcastDialog(
    quarter: String,
    onPick: (BundleSummarizer.PodcastMinutes) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Earnings podcast · $quarter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Generates a 1-page highlights summary (if one isn't cached yet) and a multi-voice podcast scripted from it. Watch progress in Tasks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FinnencerColors.TextSecondary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BundleSummarizer.PodcastMinutes.entries.forEach { m ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(FinnencerColors.Amber.copy(alpha = 0.18f))
                                .border(1.dp, FinnencerColors.Amber.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                .clickable { onPick(m) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${m.minutes} min",
                                style = MaterialTheme.typography.labelMedium,
                                color = FinnencerColors.Amber,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Diagnostic dialog showing the raw SEC EDGAR XBRL company-facts result
 * for the current ticker. Used to verify that the data the LLM will see
 * during report generation is actually correct, BEFORE generating
 * anything (and burning tokens on a maybe-empty report).
 */
@Composable
private fun XbrlDiagDialog(
    state: TickerFeedViewModel.XbrlDiagnostic,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("XBRL diagnose · ${state.ticker.orEmpty()}") },
        text = {
            Column(
                modifier = Modifier.heightIn(min = 80.dp, max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.cik?.let {
                    Text(
                        "CIK $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = FinnencerColors.TextTertiary,
                    )
                }
                when {
                    state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = FinnencerColors.Violet,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Fetching companyfacts JSON from SEC…", color = FinnencerColors.TextSecondary)
                    }
                    state.error != null -> Text(state.error, color = FinnencerColors.Coral)
                    state.quarters.isEmpty() -> Text(
                        "No standalone quarter rows came back. Either SEC hasn't parsed this ticker's XBRL yet (very small caps), or the User-Agent in API keys was rejected. Check logs for 'XBRL' lines.",
                        color = FinnencerColors.TextSecondary,
                    )
                    else -> {
                        Text(
                            "Most-recent ${state.quarters.size} quarters (10-Q/10-K standalone, 3-month periods):",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.TextTertiary,
                        )
                        state.quarters.forEach { q ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(FinnencerColors.SurfaceGlass)
                                    .border(1.dp, FinnencerColors.SurfaceBorder, RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                            ) {
                                Column {
                                    Text(
                                        "FY${q.fiscalYear} ${q.fiscalPeriod} · ${q.periodStart}→${q.periodEnd} · ${q.form}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = FinnencerColors.Mint,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    q.revenue?.let {
                                        Text("Revenue  \$${"%,.0f".format(it)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = FinnencerColors.TextPrimary)
                                    }
                                    q.grossProfit?.let {
                                        Text("Gross     \$${"%,.0f".format(it)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = FinnencerColors.TextPrimary)
                                    }
                                    q.netIncome?.let {
                                        Text("Net inc   \$${"%,.0f".format(it)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = FinnencerColors.TextPrimary)
                                    }
                                    q.epsDiluted?.let {
                                        Text("EPS dil   \$$it",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = FinnencerColors.TextPrimary)
                                    }
                                    q.accn?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = FinnencerColors.TextTertiary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun SelectionActionBar(count: Int, onCancel: () -> Unit, onSummarize: () -> Unit) {
    // Pad by navigationBars inset so the bar floats above the system gesture
    // bar / 3-button nav (covered by Samsung's hardware menu strip pre-fix).
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(FinnencerColors.BgTop.copy(alpha = 0.92f))
            .border(
                width = 1.dp,
                color = FinnencerColors.SurfaceBorder,
                shape = RoundedCornerShape(0.dp),
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$count selected",
                color = FinnencerColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text("Cancel", color = FinnencerColors.TextSecondary)
            }
            androidx.compose.material3.FilledTonalButton(
                onClick = onSummarize,
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = FinnencerColors.Violet,
                    contentColor = FinnencerColors.TextOnAccent,
                ),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Summarize", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun EarningsEmptyState(
    syncError: String?,
    showDiagnose: Boolean,
    onDiagnose: () -> Unit,
) {
    GlassCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "No past earnings synced for this ticker yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Earnings discovery pulls 8-K filings from SEC EDGAR. " +
                    "If you just added this ticker, tap Sync above; otherwise check Settings → API keys " +
                    "and make sure the EDGAR User-Agent (just your email) is set — EDGAR returns HTTP 403 without it.",
                style = MaterialTheme.typography.bodySmall,
                color = FinnencerColors.TextSecondary,
            )
            syncError?.let { err ->
                Text(
                    "Last sync: $err",
                    style = MaterialTheme.typography.labelSmall,
                    color = FinnencerColors.Coral,
                )
            }
            if (showDiagnose) {
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(FinnencerColors.Amber.copy(alpha = 0.18f))
                        .border(1.dp, FinnencerColors.Amber.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .clickable(onClick = onDiagnose)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        "Diagnose XBRL connection",
                        style = MaterialTheme.typography.labelLarge,
                        color = FinnencerColors.Amber,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EarningsCard(
    event: EarningsEvent,
    reports: List<io.itsikh.finnencer.data.entity.EarningsReport>,
    busyTier: ReportTier?,
    errorMessage: String?,
    onClearError: () -> Unit,
    onHighlights: () -> Unit,
    onDeepDive: () -> Unit,
    onMakePodcast: () -> Unit,
    onOpenReport: (Long) -> Unit,
    onDeleteReport: (Long) -> Unit,
    onOpenReader: (io.itsikh.finnencer.data.entity.EarningsReport) -> Unit,
) {
    val latestReport = reports.maxByOrNull { it.generatedAtMillis }
    val briefReport = reports.firstOrNull { it.tier == ReportTier.BRIEF.name }
    val deepReport = reports.firstOrNull { it.tier == ReportTier.DEEP.name }

    GlassCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Q${event.fiscalQuarter} ${event.fiscalYear}",
                    style = MaterialTheme.typography.titleMedium,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(10.dp))
                StatusPill(event.status)
                Spacer(Modifier.weight(1f))
                Text(
                    EARN_FMT.format(Instant.ofEpochMilli(event.scheduledAtMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = FinnencerColors.TextTertiary,
                )
            }
            // Numeric extract: actual vs consensus + beat/miss percentage
            // when we have both sides.
            EarningsNumericExtract(event)

            // Existing-report tags. Long-press deletes the specific
            // report (#26) — confirmed by dialog because the only undo
            // is to regenerate, which costs tokens.
            if (reports.isNotEmpty()) {
                var reportPendingDelete by remember { mutableStateOf<io.itsikh.finnencer.data.entity.EarningsReport?>(null) }
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    reports.forEach { r ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(FinnencerColors.Mint.copy(alpha = 0.15f))
                                .border(1.dp, FinnencerColors.Mint.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .combinedClickable(
                                    onClick = { onOpenReport(r.id) },
                                    onLongClick = { reportPendingDelete = r },
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                r.tier.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall,
                                color = FinnencerColors.Mint,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                reportPendingDelete?.let { target ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { reportPendingDelete = null },
                        title = { Text("Delete ${target.tier.lowercase()} report?") },
                        text = {
                            Text(
                                "Removes this cached ${target.tier.lowercase()} report. The underlying earnings calendar entry stays. You can regenerate later.",
                                color = FinnencerColors.TextSecondary,
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                onDeleteReport(target.id)
                                reportPendingDelete = null
                            }) {
                                Text("Delete", color = FinnencerColors.Coral)
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { reportPendingDelete = null }) {
                                Text("Cancel")
                            }
                        },
                    )
                }
            }

            errorMessage?.let { msg ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = FinnencerColors.Coral,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton(onClick = onClearError) {
                        Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            // FlowRow so chips wrap to a new line as whole chips when they
            // don't fit on one row (#22), rather than text breaking inside.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EarningsActionChip(
                    label = if (briefReport != null) "Highlights" else "Highlights",
                    accent = FinnencerColors.Violet,
                    busy = busyTier == ReportTier.BRIEF,
                    onClick = onHighlights,
                )
                EarningsActionChip(
                    label = "Deep dive",
                    accent = FinnencerColors.Amber,
                    busy = busyTier == ReportTier.DEEP,
                    onClick = onDeepDive,
                )
                EarningsActionChip(
                    label = "Podcast",
                    accent = FinnencerColors.Mint,
                    busy = false,
                    onClick = onMakePodcast,
                )
            }

            // Inline preview of the most recent report so the user gets a
            // quick read without leaving the screen.
            latestReport?.let { r ->
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(FinnencerColors.SurfaceGlass)
                        .border(1.dp, FinnencerColors.SurfaceBorder, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                r.tier.lowercase().replaceFirstChar { it.uppercase() } + " summary",
                                style = MaterialTheme.typography.labelSmall,
                                color = FinnencerColors.TextTertiary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            io.itsikh.finnencer.data.ai.friendlyModelLabel(r.model)?.let {
                                Text(
                                    "via $it",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = FinnencerColors.TextTertiary,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            r.contentMarkdown.take(280) + if (r.contentMarkdown.length > 280) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = FinnencerColors.TextPrimary,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            EarningsActionChip(
                                label = "Read mode",
                                accent = FinnencerColors.Violet,
                                busy = false,
                                onClick = { onOpenReader(r) },
                                small = true,
                            )
                            EarningsActionChip(
                                label = "Open viewer",
                                accent = FinnencerColors.Violet.copy(alpha = 0.7f),
                                busy = false,
                                onClick = { onOpenReport(r.id) },
                                small = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EarningsNumericExtract(event: EarningsEvent) {
    if (event.actualEps == null && event.consensusEps == null &&
        event.actualRevenue == null && event.consensusRevenue == null
    ) return
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        ExtractRow(
            label = "EPS",
            actual = event.actualEps,
            consensus = event.consensusEps,
            format = ::fmtMoney,
        )
        ExtractRow(
            label = "Rev",
            actual = event.actualRevenue,
            consensus = event.consensusRevenue,
            format = ::fmtMoney,
        )
    }
}

@Composable
private fun ExtractRow(
    label: String,
    actual: Double?,
    consensus: Double?,
    format: (Double?) -> String?,
) {
    val beat = if (actual != null && consensus != null && consensus != 0.0) {
        (actual - consensus) / kotlin.math.abs(consensus) * 100.0
    } else null
    val beatColor = when {
        beat == null -> FinnencerColors.TextSecondary
        beat >= 0.5 -> FinnencerColors.Mint
        beat <= -0.5 -> FinnencerColors.Coral
        else -> FinnencerColors.TextSecondary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = FinnencerColors.TextTertiary,
            modifier = Modifier.width(34.dp),
        )
        Text(
            format(actual) ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = FinnencerColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        if (consensus != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                "vs est ${format(consensus)}",
                style = MaterialTheme.typography.labelSmall,
                color = FinnencerColors.TextTertiary,
            )
        }
        beat?.let { b ->
            Spacer(Modifier.weight(1f))
            Text(
                (if (b >= 0) "▲ " else "▼ ") + "%.1f%%".format(kotlin.math.abs(b)),
                style = MaterialTheme.typography.labelSmall,
                color = beatColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun EarningsActionChip(
    label: String,
    accent: Color,
    busy: Boolean,
    onClick: () -> Unit,
    small: Boolean = false,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.18f))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(
                horizontal = if (small) 10.dp else 12.dp,
                vertical = if (small) 6.dp else 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = accent,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            maxLines = 1,
            softWrap = false,
            style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = accent,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatusPill(status: String) {
    val color = when (status) {
        EarningsStatus.REPORTED.name -> FinnencerColors.Mint
        EarningsStatus.MISSED.name -> FinnencerColors.Coral
        else -> FinnencerColors.Violet
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.20f))
            .border(1.dp, color.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            status.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyFeedInline() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No news yet",
            style = MaterialTheme.typography.titleMedium,
            color = FinnencerColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Sync runs every 15 minutes. Pull from the toolbar to refresh now.",
            style = MaterialTheme.typography.bodySmall,
            color = FinnencerColors.TextSecondary,
        )
    }
}

private fun fmtMoney(d: Double?): String? {
    if (d == null) return null
    return when {
        kotlin.math.abs(d) >= 1_000_000_000 -> "$%.2fB".format(d / 1_000_000_000)
        kotlin.math.abs(d) >= 1_000_000 -> "$%.1fM".format(d / 1_000_000)
        kotlin.math.abs(d) >= 1_000 -> "$%.1fK".format(d / 1_000)
        else -> "$%.2f".format(d)
    }
}

private val EARN_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())

/**
 * Compact filter row: two dropdown buttons, "Score: …" and "Type: …". Replaces
 * the older horizontally-scrollable FilterChip row which spilled off-screen
 * on tall phones and didn't fit all 10 article categories (issue #7).
 */
@Composable
private fun FilterRow(
    minScore: Int,
    onMinScore: (Int) -> Unit,
    category: ArticleCategory?,
    onCategory: (ArticleCategory?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScoreDropdown(minScore = minScore, onPick = onMinScore)
        TypeDropdown(category = category, onPick = onCategory)
    }
}

@Composable
private fun ScoreDropdown(minScore: Int, onPick: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (minScore == 0) "All scores" else "≥$minScore"
    val active = minScore > 0
    Box {
        DropdownButton(
            label = "Score: $label",
            active = active,
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            // Score thresholds — 0 means "show everything"; 4/5/6/7/8/9 floor
            // articles below that score from the feed.
            listOf(0, 4, 5, 6, 7, 8, 9).forEach { v ->
                DropdownMenuItem(
                    text = {
                        Text(if (v == 0) "All scores" else "≥ $v")
                    },
                    onClick = {
                        onPick(v)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TypeDropdown(category: ArticleCategory?, onPick: (ArticleCategory?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = category?.let { categoryLabel(it) } ?: "All"
    val active = category != null
    Box {
        DropdownButton(
            label = "Type: $label",
            active = active,
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("All types") },
                onClick = { onPick(null); expanded = false },
            )
            ArticleCategory.entries
                .filterNot { it == ArticleCategory.OTHER } // tag noise — never useful to filter by
                .forEach { cat ->
                    DropdownMenuItem(
                        text = { Text(categoryLabel(cat)) },
                        onClick = { onPick(cat); expanded = false },
                    )
                }
        }
    }
}

@Composable
private fun DropdownButton(label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (active) FinnencerColors.Violet.copy(alpha = 0.25f)
                else FinnencerColors.SurfaceGlass
            )
            .border(
                1.dp,
                if (active) FinnencerColors.Violet.copy(alpha = 0.55f)
                else FinnencerColors.SurfaceBorder,
                RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) FinnencerColors.TextPrimary else FinnencerColors.TextSecondary,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
        Icon(
            Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = if (active) FinnencerColors.TextPrimary else FinnencerColors.TextSecondary,
            modifier = Modifier.size(22.dp),
        )
    }
}

private fun categoryLabel(c: ArticleCategory): String = when (c) {
    ArticleCategory.EARNINGS -> "Earnings"
    ArticleCategory.M_AND_A -> "M&A"
    ArticleCategory.REGULATORY -> "Regulatory"
    ArticleCategory.MANAGEMENT -> "Management"
    ArticleCategory.MACRO -> "Macro"
    ArticleCategory.LEGAL -> "Legal"
    ArticleCategory.PRODUCT -> "Product"
    ArticleCategory.ANALYST -> "Analyst"
    ArticleCategory.INSIDER -> "Insider"
    ArticleCategory.OTHER -> "Other"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArticleRowCard(
    row: ScoredArticleRow,
    selected: Boolean = false,
    inSelectMode: Boolean = false,
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onMore: () -> Unit = {},
) {
    val accent = if (selected) FinnencerColors.Mint else Color.Transparent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = accent,
                shape = RoundedCornerShape(20.dp),
            )
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        GlassCard {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (inSelectMode) {
                        // Selection checkmark slot replaces the score badge when
                        // the row is selected; otherwise an empty circle hints at
                        // tappability.
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) FinnencerColors.Mint.copy(alpha = 0.25f)
                                    else FinnencerColors.SurfaceGlass
                                )
                                .border(
                                    1.dp,
                                    if (selected) FinnencerColors.Mint else FinnencerColors.SurfaceBorder,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Text(
                                    "✓",
                                    color = FinnencerColors.Mint,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    ScoreBadge(score = row.score)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = row.source_name,
                        style = MaterialTheme.typography.labelSmall,
                        color = FinnencerColors.TextTertiary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatRelative(row.published_at_millis),
                        style = MaterialTheme.typography.labelSmall,
                        color = FinnencerColors.TextTertiary,
                    )
                    if (!inSelectMode) {
                        Spacer(Modifier.size(6.dp))
                        IconButton(onClick = onMore, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Article actions",
                                tint = FinnencerColors.TextTertiary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!row.reason.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = row.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = FinnencerColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!row.category.isNullOrBlank() && row.category != ArticleCategory.OTHER.name) {
                    Spacer(Modifier.height(6.dp))
                    CategoryChip(category = row.category)
                }
            }
        }
    }
}

@Composable
private fun ScoreBadge(score: Int?) {
    val s = score ?: 0
    val color = when {
        s >= 9 -> FinnencerColors.Coral
        s >= 7 -> FinnencerColors.Amber
        s >= 4 -> FinnencerColors.Violet
        s == 0 -> FinnencerColors.TextTertiary
        else -> FinnencerColors.Neutral
    }
    val text = if (s == 0) "?" else s.toString()
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CategoryChip(category: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(FinnencerColors.SurfaceGlass)
            .border(1.dp, FinnencerColors.SurfaceBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = category.lowercase().replace('_', ' '),
            style = MaterialTheme.typography.labelSmall,
            color = FinnencerColors.TextSecondary,
        )
    }
}


private val FMT = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())

private fun formatRelative(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val diffMin = (now - epochMillis) / 60_000
    return when {
        diffMin < 1 -> "just now"
        diffMin < 60 -> "${diffMin}m ago"
        diffMin < 24 * 60 -> "${diffMin / 60}h ago"
        diffMin < 7 * 24 * 60 -> "${diffMin / (24 * 60)}d ago"
        else -> FMT.format(Instant.ofEpochMilli(epochMillis))
    }
}
