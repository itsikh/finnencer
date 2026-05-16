package io.itsikh.finnencer.ui.screens.earnings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import io.itsikh.finnencer.data.entity.ReportTier
import io.itsikh.finnencer.ui.theme.FinnencerColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TierPickerSheet(
    state: TierPickerState,
    onClose: () -> Unit,
    onPick: (ReportTier) -> Unit,
) {
    val event = state.event ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = FinnencerColors.BgTop,
        contentColor = FinnencerColors.TextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "${event.tickerSymbol}  ·  Q${event.fiscalQuarter} ${event.fiscalYear}",
                style = MaterialTheme.typography.headlineSmall,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Pick a depth. Each tier uses both a different source bundle AND a different Claude model.",
                style = MaterialTheme.typography.bodySmall,
                color = FinnencerColors.TextSecondary,
            )

            if (state.generating) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = FinnencerColors.Violet,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "Generating report — this may take 20-60 seconds for DEEP.",
                        color = FinnencerColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                TierOption(
                    title = "BRIEF",
                    subtitle = "~2 pages · Sonnet · executive read",
                    detail = "Headline, numbers vs consensus, what matters, next catalyst.",
                    accent = FinnencerColors.Mint,
                    enabled = !state.generating,
                    onClick = { onPick(ReportTier.BRIEF) },
                )
                TierOption(
                    title = "STANDARD",
                    subtitle = "~5 pages · Sonnet · standard read",
                    detail = "BRIEF + guidance commentary + segment detail + analyst reaction + risks.",
                    accent = FinnencerColors.Violet,
                    enabled = !state.generating,
                    onClick = { onPick(ReportTier.STANDARD) },
                )
                TierOption(
                    title = "DEEP",
                    subtitle = "~10 pages · Opus 4.7 (1M ctx) · deep dive",
                    detail = "STANDARD + explicit bull/bear, risk factors, comparables, next-quarter watchlist.",
                    accent = FinnencerColors.Amber,
                    enabled = !state.generating,
                    onClick = { onPick(ReportTier.DEEP) },
                )
            }

            state.error?.let { err ->
                Text(err, color = FinnencerColors.Coral, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun TierOption(
    title: String,
    subtitle: String,
    detail: String,
    accent: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(FinnencerColors.SurfaceGlass)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { onClick() })
            }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.size(10.dp))
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = FinnencerColors.TextSecondary)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = FinnencerColors.TextSecondary)
    }
}
