package io.itsikh.finnencer.ui.screens.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.ui.theme.FinnencerColors
import io.itsikh.finnencer.ui.theme.MonoStyles

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
        containerColor = FinnencerColors.Canvas,
        contentColor = FinnencerColors.TextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(text = ticker.symbol, style = MonoStyles.Brand, color = FinnencerColors.TextPrimary)
            Text(
                text = ticker.name.uppercase(),
                style = MonoStyles.BrandSub,
                color = FinnencerColors.TextTertiary,
            )

            Spacer(Modifier.height(20.dp))
            LabeledSlider(
                title = "ALERT THRESHOLD",
                subtitle = "Notify when an article scores at least this high (1–10).",
                value = state.draftThreshold.toFloat(),
                onValue = { onThreshold(it.toInt()) },
                valueLabel = "≥ ${state.draftThreshold}",
                steps = 8,
                range = 1f..10f,
            )

            Spacer(Modifier.height(20.dp))
            LabeledSlider(
                title = "DAILY CAP",
                subtitle = "Max notifications per day for this ticker.",
                value = state.draftDailyCap.toFloat(),
                onValue = { onCap(it.toInt()) },
                valueLabel = "${state.draftDailyCap} / DAY",
                steps = 0,
                range = 1f..20f,
            )

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "MUTE NOTIFICATIONS",
                        style = MonoStyles.SectionHead,
                        color = FinnencerColors.TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Silence pushes regardless of score.",
                        style = MonoStyles.BrandSub,
                        color = FinnencerColors.TextTertiary,
                    )
                }
                Switch(
                    checked = state.draftMuted,
                    onCheckedChange = onMuted,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = FinnencerColors.TextOnAccent,
                        checkedTrackColor = FinnencerColors.Violet,
                        uncheckedThumbColor = FinnencerColors.TextTertiary,
                        uncheckedTrackColor = FinnencerColors.HairlineStrong,
                    ),
                )
            }

            Spacer(Modifier.height(28.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(FinnencerColors.Hairline))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetChip(
                    label = "REMOVE TICKER",
                    accent = FinnencerColors.Coral,
                    border = FinnencerColors.Coral,
                    onClick = { onRemove(ticker) },
                )
                Spacer(Modifier.weight(1f))
                SheetChip(label = "CANCEL", onClick = onClose)
                Spacer(Modifier.width(8.dp))
                SheetChip(
                    label = "SAVE",
                    accent = FinnencerColors.Violet,
                    border = FinnencerColors.Violet,
                    onClick = onSave,
                )
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
                Text(title, style = MonoStyles.SectionHead, color = FinnencerColors.TextSecondary)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MonoStyles.BrandSub,
                    color = FinnencerColors.TextTertiary,
                )
            }
            Text(valueLabel, style = MonoStyles.NavLabel, color = FinnencerColors.Violet)
        }
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = FinnencerColors.Violet,
                activeTrackColor = FinnencerColors.Violet,
                inactiveTrackColor = FinnencerColors.HairlineStrong,
                activeTickColor = FinnencerColors.Violet.copy(alpha = 0.5f),
                inactiveTickColor = FinnencerColors.Hairline,
            ),
        )
    }
}

/**
 * Bordered tap-target chip — same pattern as the rest of the
 * Terminal Pro screens. Used here for SAVE / CANCEL / REMOVE.
 */
@Composable
private fun SheetChip(
    label: String,
    accent: Color = FinnencerColors.TextSecondary,
    border: Color = FinnencerColors.HairlineStrong,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MonoStyles.NavLabel, color = accent)
    }
}

