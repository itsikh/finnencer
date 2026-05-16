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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.ai.AiModel
import io.itsikh.finnencer.data.ai.AiModelOption
import io.itsikh.finnencer.data.ai.AiPreferences
import io.itsikh.finnencer.data.ai.AiProvider
import io.itsikh.finnencer.data.ai.AiTier
import io.itsikh.finnencer.data.ai.AiUsage
import io.itsikh.finnencer.data.ai.DiscoveredModels
import io.itsikh.finnencer.data.ai.defaultModel
import io.itsikh.finnencer.data.api.GeminiService
import io.itsikh.finnencer.logging.AppLogger
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.theme.FinnencerColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State of the runtime "Discover Gemini models" probe. */
data class DiscoverState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val results: List<DiscoveredRow> = emptyList(),
)

data class DiscoveredRow(
    val id: String,
    val displayName: String,
    val description: String?,
    val tier: AiTier,
    val enabled: Boolean,
)

@HiltViewModel
class AiPrefsViewModel @Inject constructor(
    private val prefs: AiPreferences,
    private val discovered: DiscoveredModels,
    private val gemini: GeminiService,
) : ViewModel() {

    val current: StateFlow<Map<AiUsage, List<AiModelOption>>> = combine(
        prefs.observeRanked(AiUsage.SCORING),
        prefs.observeRanked(AiUsage.SUMMARY),
        prefs.observeRanked(AiUsage.REPORT_BRIEF),
        prefs.observeRanked(AiUsage.REPORT_STANDARD),
        combine(
            prefs.observeRanked(AiUsage.REPORT_DEEP),
            prefs.observeRanked(AiUsage.PODCAST_SCRIPT),
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

    val customModels: StateFlow<List<AiModelOption.Custom>> = discovered.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _discover = MutableStateFlow(DiscoverState())
    val discover: StateFlow<DiscoverState> = _discover.asStateFlow()

    /**
     * Set the model at [rank] (0-indexed) for [usage]. Other ranks stay
     * put, except: if [option] is already in the list at another rank, it
     * gets swapped to keep the list deduped (no duplicate IDs).
     * Pass `option = null` to clear that rank — entries after it slide up.
     */
    fun setSlot(usage: AiUsage, rank: Int, option: AiModelOption?) {
        viewModelScope.launch {
            val curr = prefs.getRanked(usage).toMutableList()
            // pad to rank with nulls so we can address the slot
            while (curr.size <= rank) curr.add(curr.lastOrNull() ?: AiModelOption.Builtin(usage.defaultModel))
            if (option == null) {
                // Clear → drop this rank and everything after it (a "None"
                // at slot 2 disables slot 3 as well; without a slot-2 model
                // the chain can't reach slot 3 anyway).
                val trimmed = curr.subList(0, rank).toList()
                prefs.setRanked(usage, trimmed.ifEmpty { listOf(AiModelOption.Builtin(usage.defaultModel)) })
            } else {
                // If the same model was elsewhere in the list, remove that
                // older entry before placing it at the new rank.
                val withoutDup = curr.filter { it.id != option.id }.toMutableList()
                while (withoutDup.size < rank) withoutDup.add(AiModelOption.Builtin(usage.defaultModel))
                if (withoutDup.size == rank) withoutDup.add(option) else withoutDup[rank] = option
                prefs.setRanked(usage, withoutDup)
            }
        }
    }

    fun resetUsage(usage: AiUsage) {
        viewModelScope.launch { prefs.reset(usage) }
    }

    fun openDiscover() {
        _discover.value = DiscoverState(open = true)
        runDiscover()
    }

    fun closeDiscover() {
        _discover.value = DiscoverState()
    }

    private fun runDiscover() {
        _discover.value = _discover.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { gemini.listModels() }
                .onSuccess { resp ->
                    val enabled = discovered.snapshot().map { it.id }.toSet()
                    val rows = (resp.models)
                        .asSequence()
                        // Only show models that can do text generation
                        // (filter out TTS / embeddings / vision-only entries).
                        .filter { (it.supportedGenerationMethods ?: emptyList()).contains("generateContent") }
                        // Skip TTS preview models — those are handled by GeminiTts.
                        .filter { (it.name ?: "").contains("-tts", ignoreCase = true).not() }
                        // Skip image-only / embedding models even if they advertise generateContent.
                        .filter { (it.name ?: "").contains("embedding", ignoreCase = true).not() }
                        .map { info ->
                            val rawName = info.name ?: ""
                            val id = rawName.removePrefix("models/")
                            DiscoveredRow(
                                id = id,
                                displayName = info.displayName ?: id,
                                description = info.description,
                                tier = inferTier(id, info.inputTokenLimit),
                                enabled = enabled.contains(id),
                            )
                        }
                        .toList()
                        .sortedBy { it.id }
                    _discover.value = _discover.value.copy(
                        loading = false,
                        results = rows,
                    )
                }
                .onFailure { t ->
                    AppLogger.e(TAG, "Gemini ListModels failed", t)
                    _discover.value = _discover.value.copy(
                        loading = false,
                        error = (t.message ?: t.javaClass.simpleName) + " — check key + Android restrictions",
                    )
                }
        }
    }

    fun toggleEnabled(row: DiscoveredRow) {
        viewModelScope.launch {
            if (row.enabled) {
                discovered.remove(row.id)
            } else {
                discovered.add(
                    AiModelOption.Custom(
                        id = row.id,
                        displayName = row.displayName,
                        provider = AiProvider.GEMINI,
                        tier = row.tier,
                    )
                )
            }
            // refresh dialog state
            _discover.value = _discover.value.copy(
                results = _discover.value.results.map {
                    if (it.id == row.id) it.copy(enabled = !it.enabled) else it
                }
            )
        }
    }

    /**
     * Best-effort tier inference for unknown Gemini IDs:
     *  - "flash" / "nano" → FAST_CHEAP
     *  - "pro" / "ultra" → LARGE
     *  - everything else → BALANCED
     *  - bump to LARGE if input token limit ≥ 500k
     */
    private fun inferTier(id: String, inputLimit: Int?): AiTier {
        val lower = id.lowercase()
        val tier = when {
            "flash" in lower || "nano" in lower -> AiTier.FAST_CHEAP
            "pro" in lower || "ultra" in lower -> AiTier.LARGE
            else -> AiTier.BALANCED
        }
        return if ((inputLimit ?: 0) >= 500_000 && tier == AiTier.BALANCED) AiTier.LARGE else tier
    }

    private companion object { const val TAG = "AiPrefsVM" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPrefsScreen(onBack: () -> Unit) {
    val vm: AiPrefsViewModel = hiltViewModel()
    val current by vm.current.collectAsState()
    val customs by vm.customModels.collectAsState()
    val discover by vm.discover.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("AI preferences", style = MaterialTheme.typography.headlineMedium, color = FinnencerColors.TextPrimary) },
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
                "Pick a primary model for each workload and up to two fallbacks. If a call to the primary fails (quota, auth, outage) the router automatically retries with the next slot.",
                style = MaterialTheme.typography.bodySmall,
                color = FinnencerColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )

            DiscoverCard(
                customs = customs,
                onOpen = vm::openDiscover,
            )

            AiUsage.entries.forEach { usage ->
                val ranked = current[usage] ?: listOf(AiModelOption.Builtin(usage.defaultModel))
                UsageCard(
                    usage = usage,
                    ranked = ranked,
                    customs = customs,
                    onSetSlot = { rank, opt -> vm.setSlot(usage, rank, opt) },
                    onReset = { vm.resetUsage(usage) },
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (discover.open) {
        DiscoverDialog(
            state = discover,
            onClose = vm::closeDiscover,
            onToggle = vm::toggleEnabled,
        )
    }
}

@Composable
private fun DiscoverCard(
    customs: List<AiModelOption.Custom>,
    onOpen: () -> Unit,
) {
    GlassCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Discover Gemini models",
                style = MaterialTheme.typography.titleMedium,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Probe your key's /v1beta/models endpoint to see every text-generation model available to you (incl. Gemini Pro 3.x as Google rolls them out). Enabled models appear in the picker below.",
                style = MaterialTheme.typography.bodySmall,
                color = FinnencerColors.TextSecondary,
            )
            if (customs.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Enabled: " + customs.joinToString(", ") { it.displayName },
                    style = MaterialTheme.typography.labelSmall,
                    color = FinnencerColors.Mint,
                )
            }
            FilledTonalButton(
                onClick = onOpen,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = FinnencerColors.Violet,
                    contentColor = FinnencerColors.TextOnAccent,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Discover")
            }
        }
    }
}

@Composable
private fun DiscoverDialog(
    state: DiscoverState,
    onClose: () -> Unit,
    onToggle: (DiscoveredRow) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Gemini models on your key") },
        text = {
            Column(modifier = Modifier.heightIn(min = 80.dp, max = 480.dp)) {
                when {
                    state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text("Calling /v1beta/models …", color = FinnencerColors.TextSecondary)
                    }
                    state.error != null -> Text(state.error, color = FinnencerColors.Coral)
                    state.results.isEmpty() -> Text("No text-generation models returned.", color = FinnencerColors.TextSecondary)
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.results, key = { it.id }) { row ->
                            DiscoverRow(row = row, onToggle = { onToggle(row) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

@Composable
private fun DiscoverRow(row: DiscoveredRow, onToggle: () -> Unit) {
    val accent = tierColor(row.tier)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (row.enabled) accent.copy(alpha = 0.18f) else FinnencerColors.SurfaceGlass)
            .border(1.dp, if (row.enabled) accent else FinnencerColors.SurfaceBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.displayName, color = FinnencerColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(row.id, color = FinnencerColors.TextTertiary, style = MaterialTheme.typography.labelSmall)
            row.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = FinnencerColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 2)
            }
        }
        Spacer(Modifier.size(8.dp))
        OutlinedButton(
            onClick = onToggle,
            shape = RoundedCornerShape(8.dp),
        ) { Text(if (row.enabled) "Disable" else "Enable") }
    }
}

@Composable
private fun UsageCard(
    usage: AiUsage,
    ranked: List<AiModelOption>,
    customs: List<AiModelOption.Custom>,
    onSetSlot: (rank: Int, option: AiModelOption?) -> Unit,
    onReset: () -> Unit,
) {
    var picker by remember { mutableStateOf<Int?>(null) } // which rank's picker is open

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
                TextButton(onClick = onReset) {
                    Text("Reset", color = FinnencerColors.TextTertiary, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(2.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Slot 0 is always present (primary). Slots 1 & 2 are
                // optional; we show an empty/"Add fallback" row when the
                // user hasn't picked one yet, but only the row immediately
                // after the highest-picked slot — picking slot 2 requires
                // slot 1 first.
                val visibleSlotCount = (ranked.size + 1).coerceAtMost(AiPreferences.MAX_RANK)
                for (rank in 0 until visibleSlotCount) {
                    val option = ranked.getOrNull(rank)
                    SlotRow(
                        rank = rank,
                        option = option,
                        isUsageDefault = option is AiModelOption.Builtin && option.model == usage.defaultModel,
                        onTap = { picker = rank },
                    )
                }
            }
        }
    }

    val pickerRank = picker
    if (pickerRank != null) {
        // Hide whichever model is already at OTHER ranks — picking the same
        // model twice for one usage isn't meaningful (fallback never kicks
        // in to itself).
        val takenAtOtherRanks = ranked
            .mapIndexedNotNull { i, opt -> if (i == pickerRank) null else opt.id }
            .toSet()
        ModelPickerDialog(
            rank = pickerRank,
            takenIds = takenAtOtherRanks,
            customs = customs,
            usageDefault = usage.defaultModel,
            onPick = {
                onSetSlot(pickerRank, it)
                picker = null
            },
            onClear = if (pickerRank > 0) {
                {
                    onSetSlot(pickerRank, null)
                    picker = null
                }
            } else null,
            onDismiss = { picker = null },
        )
    }
}

@Composable
private fun SlotRow(
    rank: Int,
    option: AiModelOption?,
    isUsageDefault: Boolean,
    onTap: () -> Unit,
) {
    val label = when (rank) {
        0 -> "1st choice"
        1 -> "Fallback"
        else -> "Last resort"
    }
    val borderColor = option?.let { tierColor(it.tier).copy(alpha = 0.55f) } ?: FinnencerColors.SurfaceBorder
    val bg = option?.let { tierColor(it.tier).copy(alpha = 0.10f) } ?: FinnencerColors.SurfaceGlass
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(
                    if (option != null) tierColor(option.tier).copy(alpha = 0.85f)
                    else FinnencerColors.SurfaceBorder
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${rank + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = if (option != null) FinnencerColors.TextOnAccent else FinnencerColors.TextTertiary,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = FinnencerColors.TextTertiary,
            )
            if (option != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        option.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FinnencerColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (isUsageDefault) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "default",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.TextTertiary,
                        )
                    }
                    if (option is AiModelOption.Custom) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "discovered",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.Mint,
                        )
                    }
                }
                Text(
                    tierLabel(option.tier),
                    style = MaterialTheme.typography.labelSmall,
                    color = FinnencerColors.TextTertiary,
                )
            } else {
                Text(
                    "Add fallback model",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FinnencerColors.TextSecondary,
                )
            }
        }
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = "Change model",
            tint = FinnencerColors.TextTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ModelPickerDialog(
    rank: Int,
    takenIds: Set<String>,
    customs: List<AiModelOption.Custom>,
    usageDefault: AiModel,
    onPick: (AiModelOption) -> Unit,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val title = when (rank) {
        0 -> "Pick primary model"
        1 -> "Pick fallback model"
        else -> "Pick last-resort model"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.heightIn(min = 80.dp, max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AiModel.entries.forEach { model ->
                    val opt = AiModelOption.Builtin(model)
                    PickerRow(
                        option = opt,
                        isDefault = model == usageDefault,
                        disabled = model.id in takenIds,
                        onClick = { if (model.id !in takenIds) onPick(opt) },
                    )
                }
                customs.forEach { custom ->
                    PickerRow(
                        option = custom,
                        isDefault = false,
                        disabled = custom.id in takenIds,
                        onClick = { if (custom.id !in takenIds) onPick(custom) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        dismissButton = onClear?.let { clear ->
            { TextButton(onClick = clear) { Text("Remove fallback", color = FinnencerColors.Coral) } }
        },
    )
}

@Composable
private fun PickerRow(
    option: AiModelOption,
    isDefault: Boolean,
    disabled: Boolean,
    onClick: () -> Unit,
) {
    val accent = tierColor(option.tier)
    val alpha = if (disabled) 0.35f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (disabled) FinnencerColors.SurfaceGlass else accent.copy(alpha = 0.10f))
            .border(1.dp, if (disabled) FinnencerColors.SurfaceBorder else accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(accent.copy(alpha = alpha)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    option.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FinnencerColors.TextPrimary.copy(alpha = alpha),
                    fontWeight = FontWeight.SemiBold,
                )
                if (isDefault) {
                    Spacer(Modifier.width(6.dp))
                    Text("default", style = MaterialTheme.typography.labelSmall, color = FinnencerColors.TextTertiary)
                }
                if (option is AiModelOption.Custom) {
                    Spacer(Modifier.width(6.dp))
                    Text("discovered", style = MaterialTheme.typography.labelSmall, color = FinnencerColors.Mint)
                }
                if (disabled) {
                    Spacer(Modifier.width(6.dp))
                    Text("already in chain", style = MaterialTheme.typography.labelSmall, color = FinnencerColors.TextTertiary)
                }
            }
            Text(
                tierLabel(option.tier),
                style = MaterialTheme.typography.labelSmall,
                color = FinnencerColors.TextTertiary,
            )
        }
    }
}

private fun tierLabel(t: AiTier): String = when (t) {
    AiTier.FAST_CHEAP -> "fast · low cost"
    AiTier.BALANCED -> "balanced"
    AiTier.LARGE -> "large context · slower · higher cost"
}

@Composable
private fun tierColor(t: AiTier) = when (t) {
    AiTier.FAST_CHEAP -> FinnencerColors.Mint
    AiTier.BALANCED -> FinnencerColors.Violet
    AiTier.LARGE -> FinnencerColors.Amber
}
