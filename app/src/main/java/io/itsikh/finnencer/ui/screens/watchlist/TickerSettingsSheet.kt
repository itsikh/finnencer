package io.itsikh.finnencer.ui.screens.watchlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.ui.theme.FinnencerColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TickerSettingsSheet(
    state: TickerSettingsSheetState,
    onClose: () -> Unit,
    onThreshold: (Int) -> Unit,
    onCap: (Int) -> Unit,
    onMuted: (Boolean) -> Unit,
    onSave: () -> Unit,
    onRemove: (Ticker) -> Unit,
) {
    val ticker = state.ticker ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = FinnencerColors.BgTop,
        contentColor = FinnencerColors.TextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = ticker.symbol,
                style = MaterialTheme.typography.headlineSmall,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = ticker.name,
                style = MaterialTheme.typography.bodyMedium,
                color = FinnencerColors.TextSecondary,
            )

            // Threshold slider
            LabeledSlider(
                title = "Alert threshold",
                subtitle = "Notify when an article scores at least this high (1–10).",
                value = state.draftThreshold.toFloat(),
                onValue = { onThreshold(it.toInt()) },
                valueLabel = "≥ ${state.draftThreshold}",
                steps = 8,
                range = 1f..10f,
            )

            // Daily cap slider
            LabeledSlider(
                title = "Daily cap",
                subtitle = "Maximum notifications per day for this ticker.",
                value = state.draftDailyCap.toFloat(),
                onValue = { onCap(it.toInt()) },
                valueLabel = "${state.draftDailyCap} / day",
                steps = 0, // continuous
                range = 1f..20f,
            )

            // Mute toggle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Mute notifications",
                        style = MaterialTheme.typography.titleSmall,
                        color = FinnencerColors.TextPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "No pushes regardless of score until you unmute.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FinnencerColors.TextSecondary,
                    )
                }
                Switch(
                    checked = state.draftMuted,
                    onCheckedChange = onMuted,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = FinnencerColors.TextOnAccent,
                        checkedTrackColor = FinnencerColors.Violet,
                        uncheckedThumbColor = FinnencerColors.TextTertiary,
                        uncheckedTrackColor = FinnencerColors.SurfaceGlass,
                    ),
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onRemove(ticker) },
                    colors = ButtonDefaults.textButtonColors(contentColor = FinnencerColors.Coral),
                ) { Text("Remove ticker") }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onClose,
                    colors = ButtonDefaults.textButtonColors(contentColor = FinnencerColors.TextSecondary),
                ) { Text("Cancel") }
                FilledTonalButton(
                    onClick = onSave,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FinnencerColors.Violet,
                        contentColor = FinnencerColors.TextOnAccent,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Save", fontWeight = FontWeight.SemiBold) }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LabeledSlider(
    title: String,
    subtitle: String,
    value: Float,
    onValue: (Float) -> Unit,
    valueLabel: String,
    steps: Int,
    range: ClosedFloatingPointRange<Float>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = FinnencerColors.TextSecondary,
                )
            }
            Text(
                valueLabel,
                style = MaterialTheme.typography.titleMedium,
                color = FinnencerColors.Violet,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = FinnencerColors.Violet,
                activeTrackColor = FinnencerColors.Violet,
                inactiveTrackColor = FinnencerColors.SurfaceGlassStrong,
                activeTickColor = FinnencerColors.Violet.copy(alpha = 0.5f),
                inactiveTickColor = FinnencerColors.SurfaceBorder,
            ),
        )
    }
}
