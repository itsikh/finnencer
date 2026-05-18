package io.itsikh.finnencer.ui.screens.watchlist

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.data.repo.TickerQuote
import io.itsikh.finnencer.ui.theme.FinnencerColors
import io.itsikh.finnencer.ui.theme.MonoStyles
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    onOpenSettings: () -> Unit,
    onOpenEarnings: () -> Unit,
    onOpenPodcasts: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTickerFeed: (symbol: String) -> Unit,
) {
    val vm: WatchlistViewModel = hiltViewModel()
    val tickers by vm.tickers.collectAsState()
    val addSheet by vm.addSheet.collectAsState()
    val settingsSheet by vm.settingsSheet.collectAsState()
    val activeJobs by vm.activeJobCount.collectAsState()
    val queueCount by vm.queueCount.collectAsState()
    val quotes by vm.quotes.collectAsState()

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, tickers) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> vm.startQuotePolling()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> vm.stopQuotePolling()
                else -> Unit
            }
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            vm.startQuotePolling()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { TerminalBrand(trackedCount = tickers.size) },
                actions = {
                    NavChip(
                        label = "TSK",
                        badge = activeJobs.takeIf { it > 0 }?.toString(),
                        badgeColor = FinnencerColors.Coral,
                        onClick = onOpenTasks,
                    )
                    NavChip(
                        label = "QUE",
                        badge = queueCount.takeIf { it > 0 }?.toString(),
                        badgeColor = FinnencerColors.Violet,
                        onClick = onOpenQueue,
                    )
                    NavChip(label = "ERN", onClick = onOpenEarnings)
                    NavChip(label = "POD", onClick = onOpenPodcasts)
                    NavChip(label = "⚙", onClick = onOpenSettings)
                    Spacer(Modifier.size(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = vm::openAddSheet,
                containerColor = FinnencerColors.Violet,
                contentColor = FinnencerColors.TextOnAccent,
                shape = CircleShape,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add ticker")
            }
        },
    ) { padding ->
        if (tickers.isEmpty()) {
            EmptyWatchlist(modifier = Modifier.padding(padding), onAdd = vm::openAddSheet)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(top = 0.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(tickers, key = { it.symbol }) { ticker ->
                    TerminalRow(
                        ticker = ticker,
                        quote = quotes[ticker.symbol.uppercase()],
                        onTap = { onOpenTickerFeed(ticker.symbol) },
                        onLongPress = { vm.openSettings(ticker) },
                    )
                }
            }
        }
    }

    if (addSheet.open) {
        AddTickerSheet(
            state = addSheet,
            onClose = vm::closeAddSheet,
            onQueryChange = vm::onSearchQueryChanged,
            onAdd = vm::addTicker,
        )
    }
    settingsSheet.ticker?.let {
        TickerSettingsSheet(
            state = settingsSheet,
            onClose = vm::closeSettings,
            onThreshold = vm::setDraftThreshold,
            onCap = vm::setDraftCap,
            onMuted = vm::setDraftMuted,
            onSave = vm::saveSettings,
            onRemove = { vm.removeTicker(it.symbol) },
        )
    }
}

@Composable
private fun TerminalBrand(trackedCount: Int) {
    Column {
        Text(
            text = "FINNENCER",
            style = MonoStyles.Brand,
            color = FinnencerColors.TextPrimary,
        )
        Text(
            text = if (trackedCount == 0) "NO TICKERS" else "$trackedCount TRACKED",
            style = MonoStyles.BrandSub,
            color = FinnencerColors.TextTertiary,
        )
    }
}

/**
 * Bordered chip-style navigation button. Sized ≥ 48dp tall so the tap
 * target meets Material's accessibility floor even though the visible
 * box is tighter. An optional badge digit appears inside the chip,
 * colored independently from the label.
 */
@Composable
private fun NavChip(
    label: String,
    badge: String? = null,
    badgeColor: Color = FinnencerColors.Violet,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, FinnencerColors.HairlineStrong, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MonoStyles.NavLabel,
            color = FinnencerColors.TextSecondary,
        )
        if (badge != null) {
            Spacer(Modifier.size(4.dp))
            Text(
                text = badge,
                style = MonoStyles.NavLabel,
                color = badgeColor,
            )
        }
    }
}

/**
 * Dense Terminal Pro watchlist row.
 *
 * Two-line layout:
 *   TICKER  NAME ........................... ALR n
 *   PRICE   ──── sparkline ────             ▲ X.XX%
 *
 * No card background; a single hairline below the row provides the
 * only chrome. Tap opens the per-ticker feed; long-press opens the
 * per-ticker settings sheet (threshold / cap / mute / remove).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalRow(
    ticker: Ticker,
    quote: TickerQuote?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = ticker.symbol,
                style = MonoStyles.Ticker,
                color = FinnencerColors.TextPrimary,
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = ticker.name.uppercase(Locale.US),
                style = MonoStyles.BrandSub,
                color = FinnencerColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            AlertTag(
                threshold = ticker.notificationThreshold,
                muted = ticker.mutedUntilMillis != null,
            )
        }
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = quote?.let { String.format(Locale.US, "%,.2f", it.price) } ?: "—",
                style = MonoStyles.Price,
                color = FinnencerColors.TextPrimary,
            )
            Spacer(Modifier.size(12.dp))
            Sparkline(
                points = quote?.closes.orEmpty(),
                positive = (quote?.changePercent ?: 0.0) >= 0.0,
                modifier = Modifier
                    .weight(1f)
                    .height(22.dp),
            )
            Spacer(Modifier.size(12.dp))
            PctText(quote)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FinnencerColors.Hairline),
    )
}

/**
 * Alert-threshold tag — "ALR 8" in coral for ≥8, amber for 6-7, dim
 * for ≤5. When the ticker is muted the label switches to "MUTED" and
 * dims so silenced tickers read as silenced at a glance.
 */
@Composable
private fun AlertTag(threshold: Int, muted: Boolean) {
    val color = when {
        muted -> FinnencerColors.TextTertiary
        threshold >= 8 -> FinnencerColors.Coral
        threshold >= 6 -> FinnencerColors.Amber
        else -> FinnencerColors.TextSecondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, FinnencerColors.HairlineStrong, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            text = if (muted) "MUTED" else "ALR $threshold",
            style = MonoStyles.Chip,
            color = color,
        )
    }
}

/**
 * Right-aligned percent text. Mint for positive, coral for negative,
 * dim for null/zero. Sign is drawn with ▲/▼ arrows for instant glance
 * recognition; the percent is tabular so digit widths line up across
 * rows.
 */
@Composable
private fun PctText(quote: TickerQuote?) {
    if (quote == null) {
        Text(
            text = "—",
            style = MonoStyles.Pct,
            color = FinnencerColors.TextTertiary,
        )
        return
    }
    val pct = quote.changePercent
    val (arrow, color) = when {
        pct > 0.0 -> "▲" to FinnencerColors.Up
        pct < 0.0 -> "▼" to FinnencerColors.Down
        else -> "·" to FinnencerColors.TextTertiary
    }
    Text(
        text = String.format(Locale.US, "%s %.2f%%", arrow, abs(pct)),
        style = MonoStyles.Pct,
        color = color,
    )
}

/**
 * Tiny line-chart drawn directly on a Canvas. A single stroked
 * polyline whose color reflects the day's direction. When [points] is
 * empty (e.g. before the first quote lands, or when Yahoo returned no
 * candles) the chart is rendered as a subtle hairline so the row's
 * vertical rhythm doesn't shift once data arrives.
 */
@Composable
private fun Sparkline(
    points: List<Double>,
    positive: Boolean,
    modifier: Modifier = Modifier,
) {
    val stroke = if (positive) FinnencerColors.Up else FinnencerColors.Down
    Canvas(modifier = modifier) {
        if (points.size < 2) {
            val midY = size.height / 2f
            drawLine(
                color = FinnencerColors.Hairline,
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 1f,
            )
            return@Canvas
        }
        val minV = points.min()
        val maxV = points.max()
        val range = (maxV - minV).coerceAtLeast(1e-9)
        val stepX = size.width / (points.size - 1).toFloat()
        var prev = Offset(0f, normY(points[0], minV, range, size.height))
        for (i in 1 until points.size) {
            val cur = Offset(
                i * stepX,
                normY(points[i], minV, range, size.height),
            )
            drawLine(
                color = stroke,
                start = prev,
                end = cur,
                strokeWidth = 2f,
                cap = StrokeCap.Round,
            )
            prev = cur
        }
    }
}

private fun normY(v: Double, min: Double, range: Double, h: Float): Float =
    (h - ((v - min) / range * h)).toFloat()

@Composable
private fun EmptyWatchlist(modifier: Modifier = Modifier, onAdd: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "NO TICKERS YET",
            style = MonoStyles.Brand,
            color = FinnencerColors.TextPrimary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "ADD YOUR FIRST · US COMMON STOCK",
            style = MonoStyles.BrandSub,
            color = FinnencerColors.TextSecondary,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(
            onClick = onAdd,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = FinnencerColors.Violet,
                contentColor = FinnencerColors.TextOnAccent,
            ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("ADD TICKER", style = MonoStyles.NavLabel, fontWeight = FontWeight.Bold)
        }
    }
}
