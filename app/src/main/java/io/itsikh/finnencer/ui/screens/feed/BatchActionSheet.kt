package io.itsikh.finnencer.ui.screens.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import io.itsikh.finnencer.data.ai.BundleSummarizer
import io.itsikh.finnencer.ui.theme.FinnencerColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchActionSheet(
    state: BatchActionState,
    selectionSize: Int,
    onClose: () -> Unit,
    onGenerate: (BundleSummarizer.Pages, BundleSummarizer.PodcastMinutes?, String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var customPrompt by remember { mutableStateOf("") }
    var selectedPages by remember { mutableStateOf(BundleSummarizer.Pages.FIVE) }
    var podcastEnabled by remember { mutableStateOf(false) }
    var selectedMinutes by remember { mutableStateOf(BundleSummarizer.PodcastMinutes.TEN) }

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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Synthesize $selectionSize articles",
                style = MaterialTheme.typography.headlineSmall,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "AI reads everything you selected, finds the common thread, and produces one coherent summary — optionally narrated as a multi-voice podcast.",
                style = MaterialTheme.typography.bodySmall,
                color = FinnencerColors.TextSecondary,
            )

            OutlinedTextField(
                value = customPrompt,
                onValueChange = { customPrompt = it },
                label = { Text("Additional instructions (optional)") },
                placeholder = { Text("e.g. focus on management commentary, ignore analyst chatter", color = FinnencerColors.TextTertiary) },
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

            if (state.working) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = FinnencerColors.Violet,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "Queueing job…",
                        color = FinnencerColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                Text(
                    "SUMMARY LENGTH",
                    style = MaterialTheme.typography.labelSmall,
                    color = FinnencerColors.TextTertiary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BundleSummarizer.Pages.entries.forEach { p ->
                        SelectableChip(
                            label = "${p.target} pages",
                            accent = FinnencerColors.Mint,
                            selected = p == selectedPages,
                            onClick = { selectedPages = p },
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Also create a podcast",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FinnencerColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Multi-voice (Host + Analyst) narration generated from the summary above.",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.TextTertiary,
                        )
                    }
                    Switch(
                        checked = podcastEnabled,
                        onCheckedChange = { podcastEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = FinnencerColors.TextOnAccent,
                            checkedTrackColor = FinnencerColors.Amber,
                            uncheckedThumbColor = FinnencerColors.TextSecondary,
                            uncheckedTrackColor = FinnencerColors.SurfaceGlass,
                            uncheckedBorderColor = FinnencerColors.SurfaceBorder,
                        ),
                    )
                }

                if (podcastEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BundleSummarizer.PodcastMinutes.entries.forEach { m ->
                            SelectableChip(
                                label = "${m.minutes} min",
                                accent = FinnencerColors.Amber,
                                selected = m == selectedMinutes,
                                onClick = { selectedMinutes = m },
                            )
                        }
                    }
                }

                FilledTonalButton(
                    onClick = {
                        onGenerate(
                            selectedPages,
                            if (podcastEnabled) selectedMinutes else null,
                            customPrompt.ifBlank { null },
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FinnencerColors.Violet,
                        contentColor = FinnencerColors.TextOnAccent,
                    ),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        if (podcastEnabled) "Generate summary + podcast" else "Generate summary",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            state.error?.let { err ->
                Text(err, color = FinnencerColors.Coral, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SelectableChip(
    label: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.28f) else FinnencerColors.SurfaceGlass)
            .border(
                1.dp,
                if (selected) accent else FinnencerColors.SurfaceBorder,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) accent else FinnencerColors.TextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
