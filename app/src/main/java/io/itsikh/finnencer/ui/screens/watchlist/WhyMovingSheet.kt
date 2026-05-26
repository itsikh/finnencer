package io.itsikh.finnencer.ui.screens.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.itsikh.finnencer.ui.theme.FinnencerColors

/**
 * Bottom sheet that answers "why is this stock moving?" for a row.
 *
 * Top section: ticker symbol + today's move (with extended-hours move
 * when applicable) + analyst PT delta + earnings proximity pill.
 *
 * Below: an AI-generated 2-3 sentence synthesis from [whyMovingState].
 * Loading state shows a spinner; cached results render instantly.
 *
 * The sheet is presentational — it reads everything it shows from
 * stateful inputs the screen layer collects, so it never owns its own
 * data fetches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhyMovingSheet(
    state: WhyMovingState,
    quote: io.itsikh.finnencer.data.repo.TickerQuote?,
    analystSnapshot: io.itsikh.finnencer.data.entity.TickerAnalystSnapshot?,
    daysUntilEarnings: Int?,
    onDismiss: () -> Unit,
) {
    if (state is WhyMovingState.Idle) return
    val symbol = when (state) {
        is WhyMovingState.Loading -> state.symbol
        is WhyMovingState.Ready -> state.symbol
        is WhyMovingState.NoNews -> state.symbol
        is WhyMovingState.Error -> state.symbol
        else -> return
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = FinnencerColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header: symbol + day move
            HeaderRow(symbol = symbol, quote = quote)

            // Compact signal strip — analyst PT, extended hours, earnings
            SignalsStrip(
                quote = quote,
                analystSnapshot = analystSnapshot,
                daysUntilEarnings = daysUntilEarnings,
            )

            // AI synthesis section
            SynthesisBlock(state = state)
        }
    }
}

@Composable
private fun HeaderRow(
    symbol: String,
    quote: io.itsikh.finnencer.data.repo.TickerQuote?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.headlineMedium,
            color = FinnencerColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (quote != null) {
            val pct = quote.changePercent
            val color = when {
                pct > 0 -> FinnencerColors.Mint
                pct < 0 -> FinnencerColors.Coral
                else -> FinnencerColors.TextTertiary
            }
            val sign = if (pct > 0) "+" else if (pct < 0) "−" else ""
            Text(
                text = String.format(java.util.Locale.US, "%s%.2f%%", sign, kotlin.math.abs(pct)),
                style = MaterialTheme.typography.titleLarge,
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SignalsStrip(
    quote: io.itsikh.finnencer.data.repo.TickerQuote?,
    analystSnapshot: io.itsikh.finnencer.data.entity.TickerAnalystSnapshot?,
    daysUntilEarnings: Int?,
) {
    val items = mutableListOf<Pair<String, Color>>()
    quote?.let { q ->
        // Extended hours
        val extPct = q.extendedChangePercent
        val extSession = q.extendedSession
        if (extPct != null && extSession != null) {
            val label = when (extSession) {
                io.itsikh.finnencer.data.repo.ExtendedSession.PRE -> "Pre"
                io.itsikh.finnencer.data.repo.ExtendedSession.POST -> "After"
            }
            val sign = if (extPct > 0) "+" else if (extPct < 0) "−" else ""
            val color = when {
                extPct > 0 -> FinnencerColors.Mint
                extPct < 0 -> FinnencerColors.Coral
                else -> FinnencerColors.TextTertiary
            }
            items += String.format(java.util.Locale.US, "%s %s%.2f%%", label, sign, kotlin.math.abs(extPct)) to color
        }
    }
    val target = analystSnapshot?.targetMean
    if (target != null && target > 0.0 && quote != null && quote.price > 0.0) {
        val delta = (target - quote.price) / quote.price * 100.0
        val arrow = if (delta > 0) "▲" else if (delta < 0) "▼" else "·"
        val color = when {
            delta > 0 -> FinnencerColors.Mint
            delta < 0 -> FinnencerColors.Coral
            else -> FinnencerColors.TextTertiary
        }
        items += String.format(java.util.Locale.US, "Analyst PT %s%.0f%%", arrow, kotlin.math.abs(delta)) to color
    }
    if (daysUntilEarnings != null && daysUntilEarnings in 0..14) {
        val label = when (daysUntilEarnings) {
            0 -> "Earnings today"
            1 -> "Earnings tomorrow"
            else -> "Earnings in ${daysUntilEarnings}d"
        }
        val color = when {
            daysUntilEarnings <= 1 -> FinnencerColors.Coral
            daysUntilEarnings <= 7 -> FinnencerColors.Amber
            else -> FinnencerColors.Violet
        }
        items += label to color
    }
    if (items.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (label, color) ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = 0.14f))
                    .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SynthesisBlock(state: WhyMovingState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(FinnencerColors.SurfaceGlass)
            .border(1.dp, FinnencerColors.SurfaceBorder, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        when (state) {
            is WhyMovingState.Loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = FinnencerColors.Violet,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "Thinking…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FinnencerColors.TextSecondary,
                    )
                }
            }
            is WhyMovingState.Ready -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = FinnencerColors.Violet,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "AI synthesis",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.TextTertiary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = state.explanation.explanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FinnencerColors.TextPrimary,
                    )
                }
            }
            is WhyMovingState.NoNews -> {
                Text(
                    "No recent news to anchor a story. The move is currently unexplained by anything in the feed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FinnencerColors.TextSecondary,
                )
            }
            is WhyMovingState.Error -> {
                Text(
                    text = "Couldn't generate an explanation: ${state.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FinnencerColors.Coral,
                )
            }
            WhyMovingState.Idle -> Unit
        }
    }
}
