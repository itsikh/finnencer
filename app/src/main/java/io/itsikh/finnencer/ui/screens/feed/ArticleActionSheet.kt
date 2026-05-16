package io.itsikh.finnencer.ui.screens.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.itsikh.finnencer.ui.theme.FinnencerColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleActionSheet(
    state: ArticleActionState,
    onClose: () -> Unit,
    onApplyOverride: (Int) -> Unit,
    onClearOverride: () -> Unit,
    onRescoreWithNote: (String) -> Unit,
) {
    val article = state.article ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sliderValue by remember(article.id) {
        mutableStateOf((state.currentOverride ?: state.aiScore ?: 5).toFloat())
    }
    var noteValue by remember(article.id) { mutableStateOf("") }

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                article.title,
                style = MaterialTheme.typography.titleMedium,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
            )
            Row {
                Text(
                    "AI score: ${state.aiScore?.toString() ?: "?"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = FinnencerColors.TextSecondary,
                )
                if (state.currentOverride != null) {
                    Text(
                        "   ·   👤 your override: ${state.currentOverride}",
                        style = MaterialTheme.typography.labelMedium,
                        color = FinnencerColors.Mint,
                    )
                }
            }

            // ── Set my score ──
            Text(
                "SET MY SCORE",
                style = MaterialTheme.typography.labelSmall,
                color = FinnencerColors.TextTertiary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Override the AI's score for just this article. Used by the feed's min-score filter and ranking.",
                style = MaterialTheme.typography.bodySmall,
                color = FinnencerColors.TextSecondary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 0f..10f,
                    steps = 9,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = FinnencerColors.Mint,
                        activeTrackColor = FinnencerColors.Mint,
                        inactiveTrackColor = FinnencerColors.SurfaceGlassStrong,
                    ),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    sliderValue.toInt().toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = FinnencerColors.Mint,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.currentOverride != null) {
                    TextButton(
                        onClick = onClearOverride,
                        colors = ButtonDefaults.textButtonColors(contentColor = FinnencerColors.Coral),
                    ) { Text("Clear override") }
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = { onApplyOverride(sliderValue.toInt()) },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FinnencerColors.Mint,
                        contentColor = FinnencerColors.TextOnAccent,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Apply override", fontWeight = FontWeight.SemiBold) }
            }

            // ── Re-score with note ──
            Text(
                "RE-SCORE WITH A NOTE",
                style = MaterialTheme.typography.labelSmall,
                color = FinnencerColors.TextTertiary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                "Send the AI extra context for this article only. Replaces the current AI score.",
                style = MaterialTheme.typography.bodySmall,
                color = FinnencerColors.TextSecondary,
            )
            OutlinedTextField(
                value = noteValue,
                onValueChange = { noteValue = it },
                label = { Text("Note for the scorer") },
                placeholder = {
                    Text(
                        "e.g. only the guidance section matters here",
                        color = FinnencerColors.TextTertiary,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                colors = TextFieldDefaults.colors(
                    focusedTextColor = FinnencerColors.TextPrimary,
                    unfocusedTextColor = FinnencerColors.TextPrimary,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedLabelColor = FinnencerColors.Violet,
                    unfocusedLabelColor = FinnencerColors.TextTertiary,
                    focusedIndicatorColor = FinnencerColors.Violet,
                    unfocusedIndicatorColor = FinnencerColors.SurfaceBorder,
                    cursorColor = FinnencerColors.Violet,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = { onRescoreWithNote(noteValue) },
                    enabled = !state.rescoring,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FinnencerColors.Violet,
                        contentColor = FinnencerColors.TextOnAccent,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (state.rescoring) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = FinnencerColors.TextOnAccent,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (state.rescoring) "Re-scoring…" else "Re-score", fontWeight = FontWeight.SemiBold)
                }
            }

            state.error?.let { err ->
                Text(err, color = FinnencerColors.Coral, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}
