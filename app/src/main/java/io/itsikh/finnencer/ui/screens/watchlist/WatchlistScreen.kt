package io.itsikh.finnencer.ui.screens.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowOverflow
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
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.ui.components.GlassCard
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
            // Leaving via navigation disposes the composable WITHOUT an
            // ON_PAUSE (the activity stays resumed) — stop here too or
            // the singleton poller keeps hitting Yahoo every 60s from
            // any other tab/screen until the app is backgrounded.
            vm.stopQuotePolling()
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
        val whyTicker = tickers.firstOrNull { it.symbol == whySymbol }
        WhyMovingSheet(
            state = whyMovingState,
            quote = quotes[whySymbol.uppercase()],
            analystSnapshot = analystSnapshots[whySymbol],
            daysUntilEarnings = daysUntilEarnings(nextEarnings[whySymbol]),
            ticker = whyTicker,
            highScoreNewsCount = highScoreNewsCounts[whySymbol] ?: 0,
            onOpenSettings = {
                vm.closeWhyMoving()
                whyTicker?.let(vm::openSettings)
            },
            onDismiss = vm::closeWhyMoving,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TickerCard(
    ticker: Ticker,
    quote: io.itsikh.finnencer.data.repo.TickerQuote?,
    nextEarnings: io.itsikh.finnencer.data.entity.EarningsEvent?,
    analystSnapshot: io.itsikh.finnencer.data.entity.TickerAnalystSnapshot?,
    highScoreNewsCount: Int,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    // Dense two-line row: the two-tier card (#69) grew to ~150dp,
    // so only ~5 tickers fit per screen and the watchlist was mostly
    // scrolling. Everything now lives on two fixed lines:
    //   • line 1: symbol (left) ⇄ price + day % (right)
    //   • line 2: name + micro signal badges (left) ⇄ ext-hours/PT (right)
    // Sector, alert threshold and mute state moved to the long-press
    // Why-Moving sheet — they're set-once settings, not signals you scan
    // daily. Line 2 never wraps: the right cluster keeps its intrinsic
    // width, the badge FlowRow clips whole trailing badges (volume
    // first), and the name ellipsizes — so every row keeps the same
    // ~70dp height (~10 rows per screen on a Galaxy S23 Ultra).
    val signals = computeTickerSignals(quote, daysUntilEarnings(nextEarnings))
    val extendedAndPt = extendedAndPtText(quote, analystSnapshot)
    val description = cardContentDescription(ticker, quote, signals, highScoreNewsCount)
    GlassCard(
        modifier = Modifier.semantics { contentDescription = description },
        onClick = onTap,
        onLongClick = onLongPress,
        onClickLabel = "Open news feed",
        onLongClickLabel = "Why is it moving",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Ticker monogram while we don't have logo URL data yet.
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(FinnencerColors.Violet.copy(alpha = 0.18f))
                    .border(1.dp, FinnencerColors.Violet.copy(alpha = 0.40f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ticker.symbol.take(2),
                    style = MaterialTheme.typography.labelMedium,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.size(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Line 1: symbol ⇄ price + day %. The symbol owns the
                // slack, so price/percent never truncate; a rare long
                // symbol ellipsizes instead.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = ticker.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = FinnencerColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    if (quote == null) {
                        Text(
                            text = "—",
                            style = MaterialTheme.typography.titleSmall,
                            color = FinnencerColors.TextTertiary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = "—",
                            style = MaterialTheme.typography.labelMedium,
                            color = FinnencerColors.TextTertiary,
                        )
                    } else {
                        val pct = quote.changePercent
                        val pctColor = when {
                            pct > 0.0 -> FinnencerColors.Mint
                            pct < 0.0 -> FinnencerColors.Coral
                            else -> FinnencerColors.TextTertiary
                        }
                        val sign = if (pct > 0.0) "+" else if (pct < 0.0) "−" else ""
                        Text(
                            text = String.format(java.util.Locale.US, "$%,.2f", quote.price),
                            style = MaterialTheme.typography.titleSmall,
                            color = FinnencerColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = String.format(
                                java.util.Locale.US,
                                "%s%.2f%%",
                                sign,
                                kotlin.math.abs(pct),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = pctColor,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
                // Line 2: name + badges (left, shares the slack) ⇄
                // ext-hours/PT text (right, intrinsic width). Inside the
                // left cluster the badge FlowRow measures first, so the
                // name yields before any badge is dropped.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = ticker.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = FinnencerColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        val hasBadges = signals.earningsSoon || highScoreNewsCount > 0 ||
                            signals.nearHigh || signals.nearLow || signals.volSpike
                        if (hasBadges) {
                            Spacer(Modifier.size(6.dp))
                            FlowRow(
                                maxLines = 1,
                                overflow = FlowRowOverflow.Clip,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                // Most-time-critical first — with maxLines = 1
                                // trailing badges are sacrificed first on
                                // narrow rows. Anything clipped stays one
                                // long-press away in the Why-Moving sheet.
                                if (signals.earningsSoon) {
                                    EarningsPill(daysUntil = signals.daysUntilEarnings!!)
                                }
                                if (highScoreNewsCount > 0) {
                                    FaintChip(
                                        label = "🔥 $highScoreNewsCount",
                                        color = FinnencerColors.Coral,
                                    )
                                }
                                if (signals.nearHigh) {
                                    FaintChip(label = "↑52w", color = FinnencerColors.Mint)
                                } else if (signals.nearLow) {
                                    FaintChip(label = "↓52w", color = FinnencerColors.Coral)
                                }
                                if (signals.volSpike) {
                                    FaintChip(
                                        label = String.format(
                                            java.util.Locale.US,
                                            "×%.1f",
                                            signals.volRatio,
                                        ),
                                        color = FinnencerColors.Amber,
                                    )
                                }
                            }
                        }
                    }
                    if (extendedAndPt != null) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = extendedAndPt,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * TalkBack summary for the whole ticker row — the visual layout packs
 * signals into badges and colored text, so the merged description
 * spells them out instead.
 */
private fun cardContentDescription(
    ticker: Ticker,
    quote: io.itsikh.finnencer.data.repo.TickerQuote?,
    signals: TickerSignals,
    highScoreNewsCount: Int,
): String = buildString {
    append(ticker.symbol)
    append(", ")
    append(ticker.name)
    if (quote == null) {
        append(", quote pending")
    } else {
        append(String.format(java.util.Locale.US, ", $%,.2f", quote.price))
        val pct = quote.changePercent
        when {
            pct > 0.0 -> append(String.format(java.util.Locale.US, ", up %.2f percent", pct))
            pct < 0.0 -> append(String.format(java.util.Locale.US, ", down %.2f percent", -pct))
            else -> append(", unchanged")
        }
    }
    if (signals.earningsSoon) {
        when (signals.daysUntilEarnings) {
            0 -> append(", earnings today")
            1 -> append(", earnings tomorrow")
            else -> append(", earnings in ${signals.daysUntilEarnings} days")
        }
    }
    if (highScoreNewsCount > 0) append(", $highScoreNewsCount high-importance news")
    if (signals.nearHigh) append(", near 52-week high")
    if (signals.nearLow) append(", near 52-week low")
    if (signals.volSpike) {
        append(String.format(java.util.Locale.US, ", volume %.1f times average", signals.volRatio))
    }
}

/**
 * Single-line right cluster of the row's second line: the
 * extended-hours move and/or the analyst price-target delta, joined
 * with a tertiary " · ". Null when neither applies so the composable
 * is skipped entirely and the name/badges get the full width.
 *
 * Extended hours is not folded into the day % because conflating
 * regular and extended moves would confuse the sort + threshold logic.
 * The PT delta is the watchlist's "do the pros agree the stock has
 * upside?" signal at a glance.
 */
private fun extendedAndPtText(
    quote: io.itsikh.finnencer.data.repo.TickerQuote?,
    analystSnapshot: io.itsikh.finnencer.data.entity.TickerAnalystSnapshot?,
): AnnotatedString? {
    if (quote == null) return null
    val segments = mutableListOf<Pair<String, Color>>()
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
        segments += String.format(
            java.util.Locale.US,
            "%s %s%.2f%%",
            sessionLabel,
            extSign,
            kotlin.math.abs(extPct),
        ) to extColor
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
        segments += String.format(
            java.util.Locale.US,
            "PT %s%.0f%%",
            arrow,
            kotlin.math.abs(targetDelta),
        ) to targetColor
    }
    if (segments.isEmpty()) return null
    return buildAnnotatedString {
        segments.forEachIndexed { index, (text, color) ->
            if (index > 0) {
                withStyle(SpanStyle(color = FinnencerColors.TextTertiary)) { append(" · ") }
            }
            withStyle(SpanStyle(color = color)) { append(text) }
        }
    }
}

/** Tiny rounded chip used by the row's badge strip. Same visual
 *  language as [EarningsPill] but without an icon — labels are short
 *  enough on their own. */
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

/**
 * Compact "earnings is near" pill on the watchlist row.
 *
 * Rendered as `<calendar-icon> Nd` (e.g. "📅 3d"). The calendar icon
 * carries the "earnings" context so the label can drop the "Earnings
 * in" prefix entirely — saves ~70dp horizontally compared to the
 * old wording, which was wrapping to multiple lines on narrow rows
 * (#66 — Galaxy S23 with ORCL).
 *
 * Special-cased so 0/1-day labels still read well:
 *   - 0d → "Today"
 *   - 1d → "Tmrw"
 *   - else → "${N}d"
 *
 * `maxLines = 1` belt-and-braces — even if the column gets squeezed
 * again in a future row redesign, the pill ellipses instead of
 * wrapping.
 */
@Composable
private fun EarningsPill(daysUntil: Int) {
    val label = when (daysUntil) {
        0 -> "Today"
        1 -> "Tmrw"
        else -> "${daysUntil}d"
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
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.EventNote,
            contentDescription = "Earnings in $daysUntil days",
            tint = color,
            modifier = Modifier.size(11.dp),
        )
        Spacer(Modifier.size(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
