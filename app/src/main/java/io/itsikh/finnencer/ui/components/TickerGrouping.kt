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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.itsikh.finnencer.ui.theme.FinnencerColors

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
 * grouped-list mode. Shows ticker + count + chevron.
 */
@Composable
fun TickerGroupHeader(
    ticker: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(FinnencerColors.SurfaceGlass)
            .border(1.dp, FinnencerColors.SurfaceBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
            contentDescription = if (expanded) "Collapse $ticker" else "Expand $ticker",
            tint = FinnencerColors.TextSecondary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            ticker,
            style = MaterialTheme.typography.titleSmall,
            color = FinnencerColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(2.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(FinnencerColors.Violet.copy(alpha = 0.18f))
                .border(1.dp, FinnencerColors.Violet.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = FinnencerColors.Violet,
                fontWeight = FontWeight.SemiBold,
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
