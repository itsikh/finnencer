package io.itsikh.finnencer.ui.screens.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.itsikh.finnencer.data.entity.MoveExplanation
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.theme.FinnencerColors
import kotlin.math.abs

@Composable
fun MoveExplanationCard(
    state: MoveUiState,
    onExplain: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HeaderRow(state = state)
            when (state) {
                MoveUiState.Idle -> IdleBody(onExplain = onExplain)
                MoveUiState.Loading -> LoadingBody()
                is MoveUiState.Loaded -> LoadedBody(row = state.row, onRegenerate = onRegenerate)
                is MoveUiState.NoNews -> NoNewsBody(pctChange = state.pctChange)
                is MoveUiState.Error -> ErrorBody(message = state.message, onRetry = onExplain)
            }
        }
    }
}

@Composable
private fun HeaderRow(state: MoveUiState) {
    val pct: Double? = when (state) {
        is MoveUiState.Loaded -> state.row.pctChange
        is MoveUiState.NoNews -> state.pctChange
        else -> null
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "WHY IS IT MOVING?",
            style = MaterialTheme.typography.labelSmall,
            color = FinnencerColors.TextTertiary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (pct != null) PctChip(pct = pct)
    }
}

@Composable
private fun PctChip(pct: Double) {
    val up = pct >= 0.0
    val color = if (up) FinnencerColors.Mint else FinnencerColors.Coral
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = String.format(if (up) "+%.2f%%" else "%.2f%%", pct),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun IdleBody(onExplain: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Ask Haiku to correlate today's move with the news below.",
            style = MaterialTheme.typography.bodyMedium,
            color = FinnencerColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        AccentButton(label = "Explain", onClick = onExplain)
    }
}

@Composable
private fun LoadingBody() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = FinnencerColors.Violet,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Asking Haiku…",
            style = MaterialTheme.typography.bodyMedium,
            color = FinnencerColors.TextSecondary,
        )
    }
}

@Composable
private fun LoadedBody(row: MoveExplanation, onRegenerate: () -> Unit) {
    Text(
        row.explanation,
        style = MaterialTheme.typography.bodyMedium,
        color = FinnencerColors.TextPrimary,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            relativeAge(row.generatedAtMillis) + " · " + row.model,
            style = MaterialTheme.typography.labelSmall,
            color = FinnencerColors.TextTertiary,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onRegenerate,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        ) {
            Text(
                "Regenerate",
                style = MaterialTheme.typography.labelSmall,
                color = FinnencerColors.Violet,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun NoNewsBody(pctChange: Double) {
    val text = if (abs(pctChange) < 0.5) {
        "No significant move today, and no fresh headlines in the last 36 hours."
    } else {
        "No recent headlines for this ticker — today's move likely reflects sector drift or broader market."
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = FinnencerColors.TextSecondary,
    )
}

@Composable
private fun ErrorBody(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = FinnencerColors.Coral,
        )
        AccentButton(label = "Retry", onClick = onRetry)
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
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = FinnencerColors.Violet,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun relativeAge(millis: Long): String {
    val deltaMs = (System.currentTimeMillis() - millis).coerceAtLeast(0L)
    val mins = deltaMs / 60_000L
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 1440 -> "${mins / 60}h ago"
        else -> "${mins / 1440}d ago"
    }
}
