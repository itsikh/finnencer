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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

    val current: StateFlow<Map<AiUsage, AiModelOption>> = combine(
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

    val customModels: StateFlow<List<AiModelOption.Custom>> = discovered.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _discover = MutableStateFlow(DiscoverState())
    val discover: StateFlow<DiscoverState> = _discover.asStateFlow()

    fun set(usage: AiUsage, option: AiModelOption) {
        viewModelScope.launch { prefs.set(usage, option) }
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
                "Pick the model that runs each workload. Changes apply on the next request.",
                style = MaterialTheme.typography.bodySmall,
                color = FinnencerColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )

            DiscoverCard(
                customs = customs,
                onOpen = vm::openDiscover,
            )

            AiUsage.entries.forEach { usage ->
                val selected = current[usage] ?: AiModelOption.Builtin(usage.defaultModel)
                UsageCard(
                    usage = usage,
                    selected = selected,
                    customs = customs,
                    onSelect = { vm.set(usage, it) },
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
    selected: AiModelOption,
    customs: List<AiModelOption.Custom>,
    onSelect: (AiModelOption) -> Unit,
) {
    GlassCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AiModel.entries.forEach { model ->
                    val opt = AiModelOption.Builtin(model)
                    ModelRow(
                        option = opt,
                        isSelected = selected.id == model.id,
                        isDefault = model == usage.defaultModel,
                        onClick = { onSelect(opt) },
                    )
                }
                customs.forEach { custom ->
                    ModelRow(
                        option = custom,
                        isSelected = selected.id == custom.id,
                        isDefault = false,
                        onClick = { onSelect(custom) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    option: AiModelOption,
    isSelected: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit,
) {
    val accent = tierColor(option.tier)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) accent.copy(alpha = 0.18f) else FinnencerColors.SurfaceGlass)
            .border(
                1.dp,
                if (isSelected) accent.copy(alpha = 0.55f) else FinnencerColors.SurfaceBorder,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (isSelected) accent else accent.copy(alpha = 0.35f)),
        )
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    option.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (isDefault) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "default",
                        style = MaterialTheme.typography.labelSmall,
                        color = FinnencerColors.TextTertiary,
                    )
                }
                if (option is AiModelOption.Custom) {
                    Spacer(Modifier.size(6.dp))
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
