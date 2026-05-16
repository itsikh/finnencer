package io.itsikh.finnencer.ui.screens.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import io.itsikh.finnencer.data.dao.ScoredArticleRow
import io.itsikh.finnencer.data.entity.ArticleCategory
import io.itsikh.finnencer.data.entity.EarningsEvent
import io.itsikh.finnencer.data.entity.EarningsStatus
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
) {
    val vm: TickerFeedViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val pastEarnings by vm.pastEarnings.collectAsState()
    val picker by vm.picker.collectAsState()

    LaunchedEffect(picker.producedReportId) {
        picker.producedReportId?.let { id ->
            vm.closePicker()
            onOpenReport(id)
        }
    }
    val rows by vm.rows.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
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
                if (pastEarnings.isNotEmpty()) {
                    item {
                        Text(
                            "PAST EARNINGS",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.TextTertiary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(pastEarnings, key = { "earn-${it.id}" }) { event ->
                        EarningsCard(event = event, onTap = { vm.openPicker(event) })
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
                }
                if (rows.isEmpty()) {
                    item { EmptyFeedInline() }
                } else {
                    items(rows, key = { "art-${it.id}" }) { row ->
                        ArticleRowCard(row = row, onTap = { onOpenArticle(row.id) })
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
}

@Composable
private fun EarningsCard(event: EarningsEvent, onTap: () -> Unit) {
    GlassCard(onClick = onTap) {
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
            if (event.actualEps != null || event.consensusEps != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append("EPS ")
                        append(fmtMoney(event.actualEps) ?: "—")
                        if (event.consensusEps != null) {
                            append("  vs est ")
                            append(fmtMoney(event.consensusEps))
                        }
                        if (event.actualRevenue != null) {
                            append("    REV ")
                            append(fmtMoney(event.actualRevenue))
                            event.consensusRevenue?.let { append("  vs est ${fmtMoney(it)}") }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = FinnencerColors.TextSecondary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Tap to generate report — BRIEF / STANDARD / DEEP — then optionally convert to podcast.",
                style = MaterialTheme.typography.labelMedium,
                color = FinnencerColors.Violet,
            )
        }
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Score thresholds — taps cycle between 0 / 7 / 9
        val nextMin = when (minScore) { 0 -> 7; 7 -> 9; else -> 0 }
        FilterChip(
            selected = minScore > 0,
            onClick = { onMinScore(nextMin) },
            label = { Text(if (minScore == 0) "All scores" else "≥$minScore") },
            colors = chipColors(minScore > 0),
        )
        FilterChip(
            selected = category == ArticleCategory.EARNINGS,
            onClick = { onCategory(if (category == ArticleCategory.EARNINGS) null else ArticleCategory.EARNINGS) },
            label = { Text("Earnings") },
            colors = chipColors(category == ArticleCategory.EARNINGS),
        )
        FilterChip(
            selected = category == ArticleCategory.REGULATORY,
            onClick = { onCategory(if (category == ArticleCategory.REGULATORY) null else ArticleCategory.REGULATORY) },
            label = { Text("Regulatory") },
            colors = chipColors(category == ArticleCategory.REGULATORY),
        )
        FilterChip(
            selected = category == ArticleCategory.MANAGEMENT,
            onClick = { onCategory(if (category == ArticleCategory.MANAGEMENT) null else ArticleCategory.MANAGEMENT) },
            label = { Text("Management") },
            colors = chipColors(category == ArticleCategory.MANAGEMENT),
        )
    }
}

@Composable
private fun chipColors(selected: Boolean) = FilterChipDefaults.filterChipColors(
    containerColor = FinnencerColors.SurfaceGlass,
    labelColor = FinnencerColors.TextSecondary,
    selectedContainerColor = FinnencerColors.Violet.copy(alpha = 0.25f),
    selectedLabelColor = FinnencerColors.TextPrimary,
)

@Composable
private fun ArticleRowCard(row: ScoredArticleRow, onTap: () -> Unit) {
    GlassCard(onClick = onTap) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
