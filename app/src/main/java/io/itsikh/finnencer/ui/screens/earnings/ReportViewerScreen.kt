@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.itsikh.finnencer.ui.screens.earnings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.data.ai.BundleSummarizer
import io.itsikh.finnencer.data.entity.ReportTier
import io.itsikh.finnencer.ui.theme.FinnencerColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportViewerScreen(
    onBack: () -> Unit,
    onListen: (reportId: Long) -> Unit,
    onOpenReportId: (Long) -> Unit = {},
    onOpenReader: () -> Unit = {},
) {
    val vm: ReportViewerViewModel = hiltViewModel()
    val report by vm.report.collectAsState()
    val action by vm.action.collectAsState()
    val isStale by vm.isStale.collectAsState()
    var podcastPickerOpen by remember { mutableStateOf(false) }

    // Navigate to the newly-produced report when a regenerate/upgrade
    // completes, so the user lands on the fresh version automatically.
    LaunchedEffect(action.producedReportId) {
        action.producedReportId?.let {
            vm.acknowledgeProduced()
            onOpenReportId(it)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        report?.title ?: "Report",
                        style = MaterialTheme.typography.titleMedium,
                        color = FinnencerColors.TextPrimary,
                    )
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
                    report?.let { r ->
                        IconButton(onClick = { onListen(r.id) }) {
                            Icon(
                                Icons.Default.Headphones,
                                contentDescription = "Listen now",
                                tint = FinnencerColors.Violet,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        val r = report
        if (r == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Loading report…", color = FinnencerColors.TextSecondary)
            }
            return@Scaffold
        }

        val currentTier = ReportTier.entries.firstOrNull { it.name == r.tier }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = 22.dp, vertical = 8.dp)),
        ) {
            Text(
                r.title,
                style = MaterialTheme.typography.headlineSmall,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${r.tier} · ${io.itsikh.finnencer.data.ai.friendlyModelLabel(r.model) ?: r.model}",
                style = MaterialTheme.typography.labelMedium,
                color = FinnencerColors.TextTertiary,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            if (isStale) {
                StaleBanner(
                    busy = action.regenerating,
                    onRegenerate = vm::regenerate,
                )
                Spacer(Modifier.height(10.dp))
            }

            // Action chips so the user can re-run / upgrade / make a
            // podcast / open in Reader from inside the report itself
            // instead of having to back out to the ticker feed (#21).
            ReportActionRow(
                currentTier = currentTier,
                action = action,
                onRegenerate = vm::regenerate,
                onUpgrade = vm::upgradeTier,
                onMakePodcast = { podcastPickerOpen = true },
                onOpenReader = {
                    io.itsikh.finnencer.ui.screens.reader.ReaderHolder.store(
                        io.itsikh.finnencer.ui.screens.reader.ReaderHolder.Payload(
                            title = r.title,
                            body = r.contentMarkdown,
                            attribution = io.itsikh.finnencer.data.ai.friendlyModelLabel(r.model)
                                ?.let { "via $it" },
                        )
                    )
                    onOpenReader()
                },
            )
            Spacer(Modifier.height(14.dp))

            // For MVP we render the markdown body as plain text styled with
            // light line-height. A future polish pass can swap in a real
            // markdown renderer (Markwon / RichText).
            Text(
                text = r.contentMarkdown,
                style = MaterialTheme.typography.bodyLarge,
                color = FinnencerColors.TextPrimary,
            )
            Spacer(Modifier.height(40.dp))
        }
    }

    if (podcastPickerOpen) {
        AlertDialog(
            onDismissRequest = { podcastPickerOpen = false },
            title = { Text("Earnings podcast") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Renders a multi-voice podcast scripted from this report's text. Progress in Tasks.",
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
                                    .clickable {
                                        vm.makePodcast(m)
                                        podcastPickerOpen = false
                                    }
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
                TextButton(onClick = { podcastPickerOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (action.podcastQueued) {
        AlertDialog(
            onDismissRequest = vm::resetPodcastQueued,
            title = { Text("Podcast queued") },
            text = {
                Text(
                    "Watch progress in the Tasks tab — your podcast will land in Podcasts once it's rendered.",
                    color = FinnencerColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = vm::resetPodcastQueued) { Text("OK") }
            },
        )
    }

    action.error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text("Something went wrong") },
            text = { Text(msg, color = FinnencerColors.TextSecondary) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } },
        )
    }
}

/**
 * Amber banner shown above a stale report — its cached markdown says
 * "data unavailable" but the underlying earnings event now has actual
 * numbers (Finnhub sync filled them in after the original generation).
 * One-tap regenerate so the user doesn't have to dig through the action
 * chips to figure out why their old brief is empty.
 */
@Composable
private fun StaleBanner(
    busy: Boolean,
    onRegenerate: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(FinnencerColors.Amber.copy(alpha = 0.14f))
            .border(1.dp, FinnencerColors.Amber.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Earnings results have since landed",
                    style = MaterialTheme.typography.titleSmall,
                    color = FinnencerColors.Amber,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "This brief was generated before the actual numbers were available. Regenerate to refresh with the latest data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FinnencerColors.TextSecondary,
                )
            }
            Spacer(Modifier.size(12.dp))
            ActionChip(
                label = "Regenerate",
                accent = FinnencerColors.Amber,
                busy = busy,
                onClick = onRegenerate,
            )
        }
    }
}

@Composable
private fun ReportActionRow(
    currentTier: ReportTier?,
    action: ReportViewerActionState,
    onRegenerate: () -> Unit,
    onUpgrade: () -> Unit,
    onMakePodcast: () -> Unit,
    onOpenReader: () -> Unit,
) {
    val nextTierLabel = when (currentTier) {
        ReportTier.BRIEF -> "Upgrade to Standard"
        ReportTier.STANDARD -> "Upgrade to Deep"
        ReportTier.DEEP -> null
        null -> null
    }
    // FlowRow so chips wrap to a new line *as whole chips* when they don't
    // fit on one row, instead of text breaking mid-word inside a chip
    // (#22 — "Podcas/t" on the S918B at default font scale).
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionChip(
            label = "Regenerate",
            accent = FinnencerColors.Violet,
            busy = action.regenerating,
            onClick = onRegenerate,
        )
        nextTierLabel?.let { label ->
            ActionChip(
                label = label,
                accent = FinnencerColors.Amber,
                busy = action.upgradeBusy,
                onClick = onUpgrade,
            )
        }
        ActionChip(
            label = "Podcast",
            accent = FinnencerColors.Mint,
            busy = false,
            onClick = onMakePodcast,
        )
        ActionChip(
            label = "Read mode",
            accent = FinnencerColors.Violet.copy(alpha = 0.7f),
            busy = false,
            onClick = onOpenReader,
        )
    }
}

@Composable
private fun ActionChip(
    label: String,
    accent: Color,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.18f))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = accent,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.size(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}
