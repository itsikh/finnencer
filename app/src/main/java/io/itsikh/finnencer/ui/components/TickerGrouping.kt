package io.itsikh.finnencer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.itsikh.finnencer.ui.theme.FinnencerColors
import io.itsikh.finnencer.ui.theme.MonoStyles

/**
 * Constant used by the "Group by ticker" views (Tasks, Queue, Podcasts)
 * to bucket items whose ticker is null / parses to nothing
 * recognisable.
 */
const val UNGROUPED_TICKER = "Other"

/**
 * Best-effort ticker extraction from a generated title. Handles the
 * common patterns the app's own code emits:
 *  - Podcasts:  `"{TICKER} · {N} min · {detail}"`
 *  - Earnings:  `"{TICKER} earnings · {event} · {N}-min podcast"`
 *  - Reports:   `"{TICKER} · Q3 2026 · Brief"`
 *
 * Strategy: take the first `·`-separated segment, then try the whole
 * segment first (no-spaces ticker case), then the first whitespace-
 * separated word (for "{TICKER} earnings" / "{TICKER} podcast" style).
 * Falls back to [UNGROUPED_TICKER] when nothing in the leading
 * segment matches a 1–6 uppercase letter ticker pattern.
 */
fun tickerFromPodcastTitle(title: String): String {
    val first = title.substringBefore(SEPARATOR).trim()
    if (first.isEmpty()) return UNGROUPED_TICKER
    if (TICKER_REGEX.matches(first)) return first
    // Try the first whitespace-separated token — covers titles like
    // "SNDK earnings · Q2 2026 · 15-min podcast" where the segment is
    // "SNDK earnings".
    val firstWord = first.split(WS_REGEX).firstOrNull()?.trim().orEmpty()
    if (TICKER_REGEX.matches(firstWord)) return firstWord
    return UNGROUPED_TICKER
}

/**
 * Sort a list of ticker bucket names alphabetically with
 * [UNGROUPED_TICKER] pinned to the bottom.
 */
fun sortedTickerGroups(tickers: Set<String>): List<String> {
    val (others, real) = tickers.partition { it == UNGROUPED_TICKER }
    return real.sorted() + others
}

/**
 * Collapsible section header rendered above each ticker's items in
 * grouped-list mode. Terminal Pro style: a tracked uppercase ticker
 * on the left, item count on the right, single hairline above. The
 * tap area covers the whole row and the chevron is a simple typographic
 * triangle to keep the row free of stock Material icons.
 */
@Composable
fun TickerGroupHeader(
    ticker: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(FinnencerColors.Hairline))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (expanded) "▾" else "▸",
                style = MonoStyles.SectionHead,
                color = FinnencerColors.TextTertiary,
            )
            Text(
                text = ticker.uppercase(),
                style = MonoStyles.SectionHead,
                color = FinnencerColors.TextSecondary,
            )
            Spacer(Modifier.size(2.dp))
            Box(modifier = Modifier.weight(1f))
            Text(
                text = "$count " + if (count == 1) "ITEM" else "ITEMS",
                style = MonoStyles.SectionHead,
                color = FinnencerColors.TextTertiary,
            )
        }
    }
}

/** Wrap [content] in a session-only animated visibility, expanded by
 *  default if [expanded] is true. Just a typedef-ish helper for the
 *  three grouped-view screens to use a consistent expand animation. */
@Composable
fun TickerGroupBody(expanded: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = expanded) {
        content()
    }
}

private const val SEPARATOR = "·"

/** 1-6 uppercase letters with optional dot (e.g. "BRK.B"). */
private val TICKER_REGEX = Regex("^[A-Z]{1,6}(\\.[A-Z])?$")

private val WS_REGEX = Regex("\\s+")
