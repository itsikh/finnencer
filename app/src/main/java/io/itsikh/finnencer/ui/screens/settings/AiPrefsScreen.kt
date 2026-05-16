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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import io.itsikh.finnencer.data.ai.AiPreferences
import io.itsikh.finnencer.data.ai.AiTier
import io.itsikh.finnencer.data.ai.AiUsage
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
class AiPrefsViewModel @Inject constructor(
    private val prefs: AiPreferences,
) : ViewModel() {

    private val refresh = MutableStateFlow(0)

    val current: StateFlow<Map<AiUsage, AiModel>> = combine(
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

    fun set(usage: AiUsage, model: AiModel) {
        viewModelScope.launch { prefs.set(usage, model) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPrefsScreen(onBack: () -> Unit) {
    val vm: AiPrefsViewModel = hiltViewModel()
    val current by vm.current.collectAsState()

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
            AiUsage.entries.forEach { usage ->
                val selected = current[usage] ?: usage.defaultModel
                UsageCard(
                    usage = usage,
                    selected = selected,
                    onSelect = { vm.set(usage, it) },
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun UsageCard(
    usage: AiUsage,
    selected: AiModel,
    onSelect: (AiModel) -> Unit,
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
                    ModelRow(
                        model = model,
                        isSelected = model == selected,
                        isDefault = model == usage.defaultModel,
                        onClick = { onSelect(model) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: AiModel,
    isSelected: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit,
) {
    val accent = when (model.tier) {
        AiTier.FAST_CHEAP -> FinnencerColors.Mint
        AiTier.BALANCED -> FinnencerColors.Violet
        AiTier.LARGE -> FinnencerColors.Amber
    }
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
                    model.displayName,
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
            }
            Text(
                tierLabel(model.tier),
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
