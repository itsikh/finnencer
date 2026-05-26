package io.itsikh.finnencer.ui.screens.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.components.Sparkline
import io.itsikh.finnencer.ui.theme.FinnencerColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    onOpenTickerFeed: (symbol: String) -> Unit,
) {
    val vm: WatchlistViewModel = hiltViewModel()
    val tickers by vm.tickers.collectAsState()
    val visibleTickers by vm.visibleTickers.collectAsState()
    val addSheet by vm.addSheet.collectAsState()
    val settingsSheet by vm.settingsSheet.collectAsState()
    val quotes by vm.quotes.collectAsState()
    val nextEarnings by vm.nextEarningsBySymbol.collectAsState()
    val analystSnapshots by vm.analystSnapshotsBySymbol.collectAsState()
    val highScoreNewsCounts by vm.highScoreNewsCounts.collectAsState()
    val whyMovingState by vm.whyMoving.collectAsState()
    val sortOption by vm.sortOption.collectAsState()
    val sortDescending by vm.sortDescending.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    val searchActive by vm.searchActive.collectAsState()

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

    var sortMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            WatchlistTopBar(
                tickerCount = tickers.size,
                visibleCount = visibleTickers.size,
                searchActive = searchActive,
                searchQuery = searchQuery,
                onSearchClick = vm::openSearch,
                onSearchClose = vm::closeSearch,
                onSearchChange = vm::setSearchQuery,
                sortMenuOpen = sortMenuOpen,
                onSortToggle = { sortMenuOpen = !sortMenuOpen },
                onSortDismiss = { sortMenuOpen = false },
                sortOption = sortOption,
                sortDescending = sortDescending,
                onSortPick = { opt ->
                    vm.setSortOption(opt)
                    // Keep menu open on direction-flip taps; close
                    // only when the user picks a different option.
                    if (opt != sortOption) sortMenuOpen = false
                },
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
        } else if (visibleTickers.isEmpty() && searchQuery.isNotBlank()) {
            EmptySearchResult(
                modifier = Modifier.padding(padding),
                query = searchQuery,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(visibleTickers, key = { it.symbol }) { ticker ->
                    TickerCard(
                        ticker = ticker,
                        quote = quotes[ticker.symbol.uppercase()],
                        nextEarnings = nextEarnings[ticker.symbol],
                        analystSnapshot = analystSnapshots[ticker.symbol],
                        highScoreNewsCount = highScoreNewsCounts[ticker.symbol] ?: 0,
                        onTap = { onOpenTickerFeed(ticker.symbol) },
                        onLongPress = { vm.openWhyMoving(ticker.symbol) },
                        onSettingsTap = { vm.openSettings(ticker) },
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

    // Why-is-this-moving sheet — opens on long-press of any watchlist
    // row. Pulls quote / analyst snapshot / earnings from the same
    // maps the card uses so its header signals stay in sync with what
    // the user just long-pressed.
    val whySymbol = when (val s = whyMovingState) {
        is WhyMovingState.Loading -> s.symbol
        is WhyMovingState.Ready -> s.symbol
        is WhyMovingState.NoNews -> s.symbol
        is WhyMovingState.Error -> s.symbol
        else -> null
    }
    if (whySymbol != null) {
        WhyMovingSheet(
            state = whyMovingState,
            quote = quotes[whySymbol.uppercase()],
            analystSnapshot = analystSnapshots[whySymbol],
            daysUntilEarnings = nextEarnings[whySymbol]?.let { ev ->
                val days = (ev.scheduledAtMillis - System.currentTimeMillis()) /
                    (24L * 60 * 60 * 1000)
                days.toInt()
            },
            onDismiss = vm::closeWhyMoving,
        )
    }
}

@Composable
private fun TickerCard(
    ticker: Ticker,
    quote: io.itsikh.finnencer.data.repo.TickerQuote?,
    nextEarnings: io.itsikh.finnencer.data.entity.EarningsEvent?,
    analystSnapshot: io.itsikh.finnencer.data.entity.TickerAnalystSnapshot?,
    highScoreNewsCount: Int,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSettingsTap: () -> Unit,
) {
    GlassCard(onClick = onTap, onLongClick = onLongPress) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Symbol Text must NEVER wrap to a second line
                    // (#65). weight(1f, fill = false) lets it claim
                    // remaining row space without stretching past its
                    // intrinsic width, so an "Earnings in 3d" pill on
                    // the right still gets to sit beside it. maxLines
                    // + Ellipsis is the safety net for pathological
                    // long tickers / very narrow screens.
                    Text(
                        text = ticker.symbol,
                        style = MaterialTheme.typography.titleLarge,
                        color = FinnencerColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val earningsDays = daysUntilEarnings(nextEarnings)
                    if (earningsDays != null && earningsDays in 0..EARNINGS_SOON_DAYS) {
                        Spacer(Modifier.size(8.dp))
                        EarningsPill(daysUntil = earningsDays)
                    }
                }
                Text(
                    text = ticker.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = FinnencerColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Signal pill row — collapses to nothing when no signal
                // is active so the card height is stable across rows
                // with and without hot news / 52w / vol spike.
                SignalPillRow(
                    quote = quote,
                    sector = ticker.sector,
                    highScoreNewsCount = highScoreNewsCount,
                )
            }
            // Sparkline column — drawn between the name block and the
            // quote column. Sized tight (40×20dp) so it doesn't crowd
            // the name column on narrow phones (#65 — name+symbol got
            // squeezed onto two lines on a Galaxy S23).
            Sparkline(
                closes = quote?.intradayCloses.orEmpty(),
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = 40.dp, height = 20.dp),
            )
            QuoteWithAnalystColumn(quote = quote, analystSnapshot = analystSnapshot)
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
            IconButton(onClick = onSettingsTap) {
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

private const val FIFTY_TWO_WEEK_NEAR_THRESHOLD = 0.02
private const val VOLUME_SPIKE_THRESHOLD = 2.0

/**
 * Compact row of "signal pills" rendered below the company name.
 *
 * Only renders pills that actually carry a signal — the row collapses
 * to nothing when none are active so quiet tickers stay quiet on the
 * watchlist (no permanent visual noise).
 *
 * Order: sector chip → 52w hi/lo badge → volume spike → high-importance news.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignalPillRow(
    quote: io.itsikh.finnencer.data.repo.TickerQuote?,
    sector: String?,
    highScoreNewsCount: Int,
) {
    val price = quote?.price
    val nearHigh = price != null && quote.fiftyTwoWeekHigh != null &&
        quote.fiftyTwoWeekHigh > 0.0 &&
        (quote.fiftyTwoWeekHigh - price) / quote.fiftyTwoWeekHigh <= FIFTY_TWO_WEEK_NEAR_THRESHOLD &&
        price <= quote.fiftyTwoWeekHigh * 1.005
    val nearLow = price != null && quote.fiftyTwoWeekLow != null &&
        quote.fiftyTwoWeekLow > 0.0 &&
        (price - quote.fiftyTwoWeekLow) / quote.fiftyTwoWeekLow <= FIFTY_TWO_WEEK_NEAR_THRESHOLD &&
        price >= quote.fiftyTwoWeekLow * 0.995
    val volRatio = quote?.volumeRatio
    val volSpike = volRatio != null && volRatio >= VOLUME_SPIKE_THRESHOLD

    val hasAny = !sector.isNullOrBlank() || nearHigh || nearLow || volSpike || highScoreNewsCount > 0
    if (!hasAny) return

    Spacer(Modifier.height(4.dp))
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!sector.isNullOrBlank()) {
            FaintChip(label = sector, color = FinnencerColors.TextTertiary)
        }
        if (nearHigh) {
            FaintChip(label = "52w high", color = FinnencerColors.Mint)
        } else if (nearLow) {
            FaintChip(label = "52w low", color = FinnencerColors.Coral)
        }
        if (volSpike) {
            FaintChip(
                label = String.format(java.util.Locale.US, "Vol %.1f×", volRatio),
                color = FinnencerColors.Amber,
            )
        }
        if (highScoreNewsCount > 0) {
            FaintChip(
                label = "🔥 $highScoreNewsCount",
                color = FinnencerColors.Coral,
            )
        }
    }
}

/** Tiny rounded chip used by [SignalPillRow]. Same visual language
 *  as [EarningsPill] but without an icon — labels are short enough
 *  on their own. */
@Composable
private fun FaintChip(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private const val EARNINGS_SOON_DAYS = 14

/** Whole days until [event]'s scheduled time. Null if no event. */
private fun daysUntilEarnings(event: io.itsikh.finnencer.data.entity.EarningsEvent?): Int? {
    val ms = event?.scheduledAtMillis ?: return null
    val deltaMs = ms - System.currentTimeMillis()
    val days = (deltaMs / (24L * 60 * 60 * 1000)).toInt()
    return days
}

@Composable
private fun EarningsPill(daysUntil: Int) {
    val label = when (daysUntil) {
        0 -> "Earnings today"
        1 -> "Earnings tomorrow"
        else -> "Earnings in ${daysUntil}d"
    }
    val color = when {
        daysUntil <= 1 -> FinnencerColors.Coral
        daysUntil <= 7 -> FinnencerColors.Amber
        else -> FinnencerColors.Violet
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.EventNote,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Right-aligned price + percent-change + (optional) analyst PT delta
 * column. Renders an em-dash placeholder when we don't yet have a
 * Yahoo quote for this row. PCT is colored mint for up, coral for
 * down, and tertiary text for flat.
 *
 * When both a live quote AND an analyst snapshot are available, a
 * third line shows the consensus price target vs the current price as
 * a percentage delta (e.g. "PT +12%" if the mean target is 12% above
 * spot). This is the watchlist's "do the pros agree the stock has
 * upside?" signal at a glance.
 */
@Composable
private fun QuoteWithAnalystColumn(
    quote: io.itsikh.finnencer.data.repo.TickerQuote?,
    analystSnapshot: io.itsikh.finnencer.data.entity.TickerAnalystSnapshot?,
) {
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
            // Extended-hours line — only when Yahoo's chart series
            // shows a trade outside today's regular session. We don't
            // fold this into the day % because conflating regular and
            // extended moves would confuse the sort + threshold logic.
            val extPct = quote.extendedChangePercent
            val extSession = quote.extendedSession
            if (extPct != null && extSession != null) {
                val extColor = when {
                    extPct > 0.0 -> FinnencerColors.Mint
                    extPct < 0.0 -> FinnencerColors.Coral
                    else -> FinnencerColors.TextTertiary
                }
                val extSign = if (extPct > 0.0) "+" else if (extPct < 0.0) "−" else ""
                val sessionLabel = when (extSession) {
                    io.itsikh.finnencer.data.repo.ExtendedSession.PRE -> "Pre"
                    io.itsikh.finnencer.data.repo.ExtendedSession.POST -> "After"
                }
                Text(
                    text = String.format(
                        java.util.Locale.US,
                        "%s %s%.2f%%",
                        sessionLabel,
                        extSign,
                        kotlin.math.abs(extPct),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = extColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val target = analystSnapshot?.targetMean
            if (target != null && target > 0.0 && quote.price > 0.0) {
                val targetDelta = ((target - quote.price) / quote.price) * 100.0
                val targetColor = when {
                    targetDelta > 0.0 -> FinnencerColors.Mint
                    targetDelta < 0.0 -> FinnencerColors.Coral
                    else -> FinnencerColors.TextTertiary
                }
                val arrow = when {
                    targetDelta > 0.0 -> "▲"
                    targetDelta < 0.0 -> "▼"
                    else -> "·"
                }
                Text(
                    text = String.format(
                        java.util.Locale.US,
                        "PT %s%.0f%%",
                        arrow,
                        kotlin.math.abs(targetDelta),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = targetColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
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

/**
 * Top app bar with two modes:
 *  - **Default**: app title + "X tracked" sub, with Search and Sort
 *    icon actions on the right.
 *  - **Search**: title region is replaced by an [OutlinedTextField]
 *    that drives the live filter. A close (×) navigationIcon dismisses
 *    search mode and clears the query.
 *
 * The sort dropdown anchors off the sort icon; tapping a row that is
 * already selected flips direction, tapping any other row picks it
 * (with that option's preferred default direction).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchlistTopBar(
    tickerCount: Int,
    visibleCount: Int,
    searchActive: Boolean,
    searchQuery: String,
    onSearchClick: () -> Unit,
    onSearchClose: () -> Unit,
    onSearchChange: (String) -> Unit,
    sortMenuOpen: Boolean,
    onSortToggle: () -> Unit,
    onSortDismiss: () -> Unit,
    sortOption: SortOption,
    sortDescending: Boolean,
    onSortPick: (SortOption) -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            if (searchActive) {
                IconButton(onClick = onSearchClose) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close search",
                        tint = FinnencerColors.TextPrimary,
                    )
                }
            }
        },
        title = {
            if (searchActive) {
                SearchField(query = searchQuery, onQueryChange = onSearchChange)
            } else {
                Column {
                    Text(
                        "finnencer",
                        style = MaterialTheme.typography.headlineMedium,
                        color = FinnencerColors.TextPrimary,
                    )
                    val subline = when {
                        tickerCount == 0 -> "No tickers yet"
                        sortOption != SortOption.DEFAULT ->
                            "$tickerCount tracked · sorted by ${sortOption.label.lowercase()}"
                        else -> "$tickerCount tracked"
                    }
                    Text(
                        text = subline,
                        style = MaterialTheme.typography.labelSmall,
                        color = FinnencerColors.TextTertiary,
                    )
                }
            }
        },
        actions = {
            if (!searchActive) {
                IconButton(onClick = onSearchClick) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        tint = FinnencerColors.TextSecondary,
                    )
                }
                Box {
                    IconButton(onClick = onSortToggle) {
                        Icon(
                            Icons.Default.SwapVert,
                            contentDescription = "Sort",
                            tint = if (sortOption != SortOption.DEFAULT)
                                FinnencerColors.Violet
                            else FinnencerColors.TextSecondary,
                        )
                    }
                    SortMenu(
                        expanded = sortMenuOpen,
                        current = sortOption,
                        descending = sortDescending,
                        onPick = onSortPick,
                        onDismiss = onSortDismiss,
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(
                "Search by symbol or name…",
                color = FinnencerColors.TextTertiary,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = FinnencerColors.TextSecondary,
                    )
                }
            }
        },
        colors = TextFieldDefaults.colors(
            focusedTextColor = FinnencerColors.TextPrimary,
            unfocusedTextColor = FinnencerColors.TextPrimary,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = FinnencerColors.Violet,
            unfocusedIndicatorColor = FinnencerColors.SurfaceBorder,
            cursorColor = FinnencerColors.Violet,
        ),
    )
}

@Composable
private fun SortMenu(
    expanded: Boolean,
    current: SortOption,
    descending: Boolean,
    onPick: (SortOption) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SortOption.entries.forEach { opt ->
            val selected = opt == current
            DropdownMenuItem(
                onClick = { onPick(opt) },
                leadingIcon = {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = FinnencerColors.Violet,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Spacer(Modifier.size(18.dp))
                    }
                },
                trailingIcon = {
                    if (selected) {
                        Icon(
                            if (descending) Icons.Default.ArrowDownward
                            else Icons.Default.ArrowUpward,
                            contentDescription = if (descending) "Descending" else "Ascending",
                            tint = FinnencerColors.Violet,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
                text = {
                    Text(
                        opt.label,
                        color = if (selected) FinnencerColors.TextPrimary
                        else FinnencerColors.TextSecondary,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
            )
        }
    }
}

@Composable
private fun EmptySearchResult(modifier: Modifier = Modifier, query: String) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = FinnencerColors.TextTertiary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No tickers match \"$query\"",
            style = MaterialTheme.typography.titleMedium,
            color = FinnencerColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Try a different symbol or part of the company name.",
            style = MaterialTheme.typography.bodyMedium,
            color = FinnencerColors.TextSecondary,
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
