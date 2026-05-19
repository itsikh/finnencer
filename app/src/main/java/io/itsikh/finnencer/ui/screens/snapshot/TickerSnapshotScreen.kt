package io.itsikh.finnencer.ui.screens.snapshot

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.data.entity.TickerMetrics
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.theme.FinnencerColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TickerSnapshotScreen(onBack: () -> Unit) {
    val vm: TickerSnapshotViewModel = hiltViewModel()
    val metricsState by vm.metrics.collectAsState()
    val quote by vm.quote.collectAsState()
    val analysisState by vm.analysis.collectAsState()
    val analysisSheetOpen by vm.analysisSheetOpen.collectAsState()

    var infoTarget by remember { mutableStateOf<MetricInfo?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            vm.symbol,
                            style = MaterialTheme.typography.headlineMedium,
                            color = FinnencerColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Snapshot",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.TextTertiary,
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
                    IconButton(onClick = { vm.load(force = true) }) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TodayCard(
                price = quote?.price,
                pctChange = quote?.changePercent,
                onInfo = { infoTarget = it },
            )
            when (val m = metricsState) {
                MetricsUiState.Loading -> LoadingCard()
                is MetricsUiState.Error -> ErrorCard(message = m.message, onRetry = { vm.load(force = true) })
                is MetricsUiState.Loaded -> {
                    RangeAndValuationCard(metrics = m.metrics, currentPrice = quote?.price, onInfo = { infoTarget = it })
                    IncomeAndRiskCard(metrics = m.metrics, onInfo = { infoTarget = it })
                    VolumeCard(metrics = m.metrics, onInfo = { infoTarget = it })
                    AnalyzeButton(onClick = { vm.openAnalysisSheet() })
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    infoTarget?.let { info ->
        AlertDialog(
            onDismissRequest = { infoTarget = null },
            title = {
                Text(
                    info.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    info.definition,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FinnencerColors.TextPrimary,
                )
            },
            confirmButton = {
                TextButton(onClick = { infoTarget = null }) {
                    Text("Got it", color = FinnencerColors.Violet, fontWeight = FontWeight.SemiBold)
                }
            },
            containerColor = FinnencerColors.Surface,
            titleContentColor = FinnencerColors.TextPrimary,
            textContentColor = FinnencerColors.TextPrimary,
        )
    }

    if (analysisSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = vm::closeAnalysisSheet,
            containerColor = FinnencerColors.SurfaceGlassStrong,
        ) {
            AnalysisSheet(
                state = analysisState,
                onGenerate = { vm.analyze(force = false) },
                onRegenerate = { vm.analyze(force = true) },
            )
        }
    }
}

@Composable
private fun TodayCard(price: Double?, pctChange: Double?, onInfo: (MetricInfo) -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("TODAY")
            Row(verticalAlignment = Alignment.Bottom) {
                MetricCell(
                    label = "Price",
                    value = price?.let { String.format("$%.2f", it) } ?: "—",
                    info = MetricDefinitions.PRICE,
                    onInfo = onInfo,
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    label = "Today's change",
                    value = pctChange?.let { String.format(if (it >= 0) "+%.2f%%" else "%.2f%%", it) } ?: "—",
                    info = MetricDefinitions.PCT_CHANGE,
                    onInfo = onInfo,
                    accent = pctChange?.let { if (it >= 0) FinnencerColors.Mint else FinnencerColors.Coral },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RangeAndValuationCard(
    metrics: TickerMetrics,
    currentPrice: Double?,
    onInfo: (MetricInfo) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("RANGE & VALUATION")
            FiftyTwoWeekBar(metrics = metrics, currentPrice = currentPrice, onInfo = onInfo)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCell(
                    label = "Market cap",
                    value = metrics.marketCap?.let { formatMarketCap(it) } ?: "—",
                    info = MetricDefinitions.MARKET_CAP,
                    onInfo = onInfo,
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    label = "P/E (TTM)",
                    value = metrics.peTtm?.let { String.format("%.1f", it) } ?: "—",
                    info = MetricDefinitions.PE_TTM,
                    onInfo = onInfo,
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    label = "EPS (TTM)",
                    value = metrics.epsTtm?.let { String.format("$%.2f", it) } ?: "—",
                    info = MetricDefinitions.EPS_TTM,
                    onInfo = onInfo,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun IncomeAndRiskCard(metrics: TickerMetrics, onInfo: (MetricInfo) -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("INCOME & RISK")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCell(
                    label = "Dividend yield",
                    value = metrics.divYield?.let { String.format("%.2f%%", it) } ?: "—",
                    info = MetricDefinitions.DIV_YIELD,
                    onInfo = onInfo,
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    label = "Beta",
                    value = metrics.beta?.let { String.format("%.2f", it) } ?: "—",
                    info = MetricDefinitions.BETA,
                    onInfo = onInfo,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCell(
                    label = "Revenue growth YoY",
                    value = metrics.revGrowthYoy?.let { String.format(if (it >= 0) "+%.2f%%" else "%.2f%%", it) } ?: "—",
                    info = MetricDefinitions.REV_GROWTH_YOY,
                    onInfo = onInfo,
                    accent = metrics.revGrowthYoy?.let { if (it >= 0) FinnencerColors.Mint else FinnencerColors.Coral },
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    label = "Price/sales",
                    value = metrics.priceToSales?.let { String.format("%.2f", it) } ?: "—",
                    info = MetricDefinitions.PRICE_TO_SALES,
                    onInfo = onInfo,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun VolumeCard(metrics: TickerMetrics, onInfo: (MetricInfo) -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("VOLUME")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCell(
                    label = "Avg vol (10d)",
                    value = metrics.avgVol10d?.let { formatVolume(it) } ?: "—",
                    info = MetricDefinitions.AVG_VOL,
                    onInfo = onInfo,
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    label = "Avg vol (3m)",
                    value = metrics.avgVol3m?.let { formatVolume(it) } ?: "—",
                    info = MetricDefinitions.AVG_VOL,
                    onInfo = onInfo,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FiftyTwoWeekBar(metrics: TickerMetrics, currentPrice: Double?, onInfo: (MetricInfo) -> Unit) {
    val lo = metrics.fiftyTwoWeekLow
    val hi = metrics.fiftyTwoWeekHigh
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "52-week range",
                style = MaterialTheme.typography.labelSmall,
                color = FinnencerColors.TextTertiary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onInfo(MetricDefinitions.FIFTY_TWO_WEEK_RANGE) },
            )
        }
        if (lo != null && hi != null && hi > lo) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$${String.format("%.2f", lo)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FinnencerColors.TextSecondary,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(FinnencerColors.SurfaceGlass),
                ) {
                    val pos = currentPrice?.let { ((it - lo) / (hi - lo)).coerceIn(0.0, 1.0) }
                    if (pos != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(pos.toFloat())
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(FinnencerColors.Violet.copy(alpha = 0.55f)),
                        )
                    }
                }
                Text(
                    "$${String.format("%.2f", hi)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FinnencerColors.TextSecondary,
                )
            }
        } else {
            Text("—", style = MaterialTheme.typography.bodyMedium, color = FinnencerColors.TextSecondary)
        }
    }
}

@Composable
private fun MetricCell(
    label: String,
    value: String,
    info: MetricInfo,
    onInfo: (MetricInfo) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = FinnencerColors.TextTertiary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { onInfo(info) },
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = accent ?: FinnencerColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = FinnencerColors.TextTertiary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun LoadingCard() {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = FinnencerColors.Violet,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text("Fetching fundamentals…", color = FinnencerColors.TextSecondary)
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = FinnencerColors.Coral)
            AccentButton(label = "Retry", onClick = onRetry)
        }
    }
}

@Composable
private fun AnalyzeButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(FinnencerColors.Violet.copy(alpha = 0.18f))
            .border(1.dp, FinnencerColors.Violet.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Text(
            "Analyze with AI",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleSmall,
            color = FinnencerColors.Violet,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun AnalysisSheet(
    state: AnalysisUiState,
    onGenerate: () -> Unit,
    onRegenerate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI snapshot read", style = MaterialTheme.typography.titleMedium, color = FinnencerColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        when (state) {
            AnalysisUiState.Idle -> {
                Text(
                    "Ask Claude to read the numbers and tell you what kind of name this is right now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FinnencerColors.TextSecondary,
                )
                AccentButton(label = "Generate", onClick = onGenerate)
            }
            AnalysisUiState.Loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = FinnencerColors.Violet,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Analyzing…", color = FinnencerColors.TextSecondary)
                }
            }
            is AnalysisUiState.Loaded -> {
                Text(state.row.analysis, style = MaterialTheme.typography.bodyMedium, color = FinnencerColors.TextPrimary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.row.model,
                        style = MaterialTheme.typography.labelSmall,
                        color = FinnencerColors.TextTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRegenerate) {
                        Text("Regenerate", color = FinnencerColors.Violet, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            is AnalysisUiState.Error -> {
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = FinnencerColors.Coral)
                AccentButton(label = "Retry", onClick = onGenerate)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AccentButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(FinnencerColors.Violet.copy(alpha = 0.18f))
            .border(1.dp, FinnencerColors.Violet.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = FinnencerColors.Violet, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatMarketCap(v: Double): String = when {
    v >= 1_000_000.0 -> String.format("$%.2fT", v / 1_000_000.0)
    v >= 1_000.0 -> String.format("$%.2fB", v / 1_000.0)
    else -> String.format("$%.0fM", v)
}

private fun formatVolume(v: Double): String = when {
    v >= 1.0 -> String.format("%.2fM", v)
    else -> String.format("%.0fK", v * 1_000.0)
}
