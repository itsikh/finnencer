package io.itsikh.finnencer.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.ai.AiUsage
import io.itsikh.finnencer.data.ai.DefaultPrompts
import io.itsikh.finnencer.data.ai.PromptPreferences
import io.itsikh.finnencer.data.ai.defaultModel
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.theme.FinnencerColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PromptEditorViewModel @Inject constructor(
    private val prefs: PromptPreferences,
) : ViewModel() {

    /** Per-usage saved extras, observed reactively. */
    val saved: StateFlow<Map<AiUsage, String>> = combine(
        prefs.observe(AiUsage.SCORING),
        prefs.observe(AiUsage.SUMMARY),
        prefs.observe(AiUsage.REPORT_BRIEF),
        prefs.observe(AiUsage.REPORT_STANDARD),
        combine(
            prefs.observe(AiUsage.REPORT_DEEP),
            prefs.observe(AiUsage.PODCAST_SCRIPT),
        ) { d, p -> d to p },
    ) { sc, su, b, s, (d, p) ->
        mapOf(
            AiUsage.SCORING to sc,
            AiUsage.SUMMARY to su,
            AiUsage.REPORT_BRIEF to b,
            AiUsage.REPORT_STANDARD to s,
            AiUsage.REPORT_DEEP to d,
            AiUsage.PODCAST_SCRIPT to p,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _justSaved = MutableStateFlow<AiUsage?>(null)
    /** Briefly non-null after a save so the UI can flash a confirmation. */
    val justSaved: StateFlow<AiUsage?> = _justSaved

    fun save(usage: AiUsage, extra: String) {
        viewModelScope.launch {
            prefs.set(usage, extra)
            _justSaved.value = usage
            kotlinx.coroutines.delay(1500)
            if (_justSaved.value == usage) _justSaved.value = null
        }
    }

    fun clear(usage: AiUsage) {
        viewModelScope.launch { prefs.set(usage, "") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptEditorScreen(onBack: () -> Unit) {
    val vm: PromptEditorViewModel = hiltViewModel()
    val saved by vm.saved.collectAsState()
    val justSaved by vm.justSaved.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "AI prompts",
                        style = MaterialTheme.typography.headlineMedium,
                        color = FinnencerColors.TextPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = FinnencerColors.TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Whatever you type below is APPENDED to the built-in prompt for that workload on every call. " +
                    "Use it to set persistent preferences — \"keep briefs under 250 words\", \"podcasts should " +
                    "have 3 turns per minute\", \"always cite source ticker symbols\".",
                style = MaterialTheme.typography.bodySmall,
                color = FinnencerColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )

            AiUsage.entries.forEach { usage ->
                PromptCard(
                    usage = usage,
                    savedExtra = saved[usage].orEmpty(),
                    justSaved = justSaved == usage,
                    onSave = { vm.save(usage, it) },
                    onClear = { vm.clear(usage) },
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun PromptCard(
    usage: AiUsage,
    savedExtra: String,
    justSaved: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var draft by remember(usage, savedExtra) { mutableStateOf(savedExtra) }
    var defaultExpanded by remember { mutableStateOf(false) }

    GlassCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        usage.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = FinnencerColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        usage.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = FinnencerColors.TextSecondary,
                    )
                }
                Text(
                    "default: ${usage.defaultModel.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = FinnencerColors.TextTertiary,
                )
            }

            // Collapsible block showing the built-in prompt verbatim so
            // users know what they're augmenting.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(FinnencerColors.SurfaceGlass)
                    .border(1.dp, FinnencerColors.SurfaceBorder, RoundedCornerShape(10.dp))
                    .clickable { defaultExpanded = !defaultExpanded }
                    .padding(10.dp),
            ) {
                val full = DefaultPrompts.forUsage(usage).trim()
                Column {
                    Text(
                        if (defaultExpanded) "BUILT-IN PROMPT (tap to collapse)" else "BUILT-IN PROMPT (tap to expand)",
                        style = MaterialTheme.typography.labelSmall,
                        color = FinnencerColors.TextTertiary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (defaultExpanded) full else full.take(160) + if (full.length > 160) "…" else "",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = FinnencerColors.TextSecondary,
                    )
                }
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Your extra instructions") },
                placeholder = {
                    Text(
                        when (usage) {
                            AiUsage.REPORT_BRIEF -> "e.g. keep it to under 250 words; lead with a one-line bull/bear take."
                            AiUsage.PODCAST_SCRIPT -> "e.g. exactly 3 turns per minute; bias toward the Host asking pointed questions."
                            AiUsage.SUMMARY -> "e.g. always end with one explicit next-catalyst line in italics."
                            else -> "Plain English; appended verbatim to the built-in prompt above."
                        },
                        color = FinnencerColors.TextTertiary,
                    )
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                minLines = 3,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = { onSave(draft) },
                    enabled = draft.trim() != savedExtra.trim(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FinnencerColors.Violet,
                        contentColor = FinnencerColors.TextOnAccent,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Save") }
                if (savedExtra.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            draft = ""
                            onClear()
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Clear") }
                }
                Spacer(Modifier.weight(1f))
                if (justSaved) {
                    Text(
                        "Saved ✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = FinnencerColors.Mint,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else if (savedExtra.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(FinnencerColors.Mint.copy(alpha = 0.15f))
                            .border(1.dp, FinnencerColors.Mint.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "active",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.Mint,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
