package io.itsikh.finnencer.ui.screens.watchlist

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
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
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.theme.FinnencerColors

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

    // Foreground-only quote polling — start on screen resume, stop on
    // pause, restart any time the watched-ticker list changes (e.g.
    // user adds NVDA, poller now also covers it on the next tick).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, tickers) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> vm.startQuotePolling()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> vm.stopQuotePolling()
                else -> Unit
            }
        }
        // If we're already RESUMED when this effect installs (typical),
        // kick off polling immediately so the user sees prices fast
        // rather than waiting for the next ON_RESUME event.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            vm.startQuotePolling()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "finnencer",
                            style = MaterialTheme.typography.headlineMedium,
                            color = FinnencerColors.TextPrimary,
                        )
                        Text(
                            text = if (tickers.isEmpty()) "No tickers yet" else "${tickers.size} tracked",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.TextTertiary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenTasks) {
                        Box {
                            Icon(
                                Icons.AutoMirrored.Filled.Assignment,
                                contentDescription = "Tasks",
                                tint = if (activeJobs > 0) FinnencerColors.Violet else FinnencerColors.TextSecondary,
                            )
                            if (activeJobs > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .clip(CircleShape)
                                        .background(FinnencerColors.Coral)
                                        .padding(horizontal = 4.dp),
                                ) {
                                    Text(
                                        activeJobs.toString(),
                                        color = FinnencerColors.TextOnAccent,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = onOpenQueue) {
                        Box {
                            Icon(
                                Icons.Default.Bookmark,
                                contentDescription = "Queue",
                                tint = if (queueCount > 0) FinnencerColors.Violet else FinnencerColors.TextSecondary,
                            )
                            if (queueCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .clip(CircleShape)
                                        .background(FinnencerColors.Violet)
                                        .padding(horizontal = 4.dp),
                                ) {
                                    Text(
                                        queueCount.toString(),
                                        color = FinnencerColors.TextOnAccent,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = onOpenEarnings) {
                        Icon(
                            Icons.Default.EventNote,
                            contentDescription = "Earnings",
                            tint = FinnencerColors.TextSecondary,
                        )
                    }
                    IconButton(onClick = onOpenPodcasts) {
                        Icon(
                            Icons.Default.Headphones,
                            contentDescription = "Podcasts",
                            tint = FinnencerColors.TextSecondary,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = FinnencerColors.TextSecondary,
                        )
                    }
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
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tickers, key = { it.symbol }) { ticker ->
                    TickerCard(
                        ticker = ticker,
                        quote = quotes[ticker.symbol.uppercase()],
                        onTap = { onOpenTickerFeed(ticker.symbol) },
                        onLongPress = { vm.openSettings(ticker) },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) } // FAB clearance
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
private fun TickerCard(
    ticker: Ticker,
    quote: io.itsikh.finnencer.data.repo.TickerQuote?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    GlassCard(onClick = onTap) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Ticker monogram while we don't have logo URL data yet.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(FinnencerColors.Violet.copy(alpha = 0.18f))
                    .border(1.dp, FinnencerColors.Violet.copy(alpha = 0.40f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ticker.symbol.take(2),
                    style = MaterialTheme.typography.labelLarge,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ticker.symbol,
                    style = MaterialTheme.typography.titleLarge,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = ticker.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = FinnencerColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            QuoteColumn(quote)
            Spacer(Modifier.size(8.dp))
            ThresholdPill(ticker.notificationThreshold)
            Spacer(Modifier.size(8.dp))
            if (ticker.mutedUntilMillis != null) {
                Icon(
                    Icons.Default.NotificationsOff,
                    contentDescription = "Muted",
                    tint = FinnencerColors.TextTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onLongPress) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Ticker settings",
                    tint = FinnencerColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Right-aligned price + percent change column. Renders an em-dash
 * placeholder when we don't yet have a Yahoo quote for this row. PCT
 * is colored mint for up, coral for down, and tertiary text for flat.
 */
@Composable
private fun QuoteColumn(quote: io.itsikh.finnencer.data.repo.TickerQuote?) {
    Column(horizontalAlignment = Alignment.End) {
        if (quote == null) {
            Text(
                text = "—",
                style = MaterialTheme.typography.titleSmall,
                color = FinnencerColors.TextTertiary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "—",
                style = MaterialTheme.typography.labelMedium,
                color = FinnencerColors.TextTertiary,
            )
        } else {
            val priceText = String.format(java.util.Locale.US, "$%,.2f", quote.price)
            val pct = quote.changePercent
            val pctColor = when {
                pct > 0.0 -> FinnencerColors.Mint
                pct < 0.0 -> FinnencerColors.Coral
                else -> FinnencerColors.TextTertiary
            }
            val sign = if (pct > 0.0) "+" else if (pct < 0.0) "−" else ""
            val pctText = String.format(java.util.Locale.US, "%s%.2f%%", sign, kotlin.math.abs(pct))
            Text(
                text = priceText,
                style = MaterialTheme.typography.titleSmall,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = pctText,
                style = MaterialTheme.typography.labelMedium,
                color = pctColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ThresholdPill(threshold: Int) {
    val color = when {
        threshold >= 8 -> FinnencerColors.Coral
        threshold >= 6 -> FinnencerColors.Amber
        else -> FinnencerColors.Violet
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = "≥$threshold",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyWatchlist(modifier: Modifier = Modifier, onAdd: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
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
                Icons.Default.Add,
                contentDescription = null,
                tint = FinnencerColors.Violet,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Add your first ticker",
            style = MaterialTheme.typography.headlineSmall,
            color = FinnencerColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Search for any US-traded company. finnencer pulls news every 15 min and ranks importance with Claude.",
            style = MaterialTheme.typography.bodyMedium,
            color = FinnencerColors.TextSecondary,
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(
            onClick = onAdd,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = FinnencerColors.Violet,
                contentColor = FinnencerColors.TextOnAccent,
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text("Add ticker", fontWeight = FontWeight.SemiBold)
        }
    }
}
