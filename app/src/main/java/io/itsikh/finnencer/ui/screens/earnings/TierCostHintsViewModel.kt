package io.itsikh.finnencer.ui.screens.earnings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.ai.AiPreferences
import io.itsikh.finnencer.data.ai.AiUsage
import io.itsikh.finnencer.data.ai.ModelCost
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Resolves "what would this tap cost approximately?" for the report
 * tiers and podcast generation. Reads the user's currently-configured
 * primary model for each [AiUsage] from [AiPreferences] and runs it
 * through [ModelCost] so a user who has swapped, say, REPORT_DEEP from
 * Opus to Sonnet sees a lower hint, while the default-Opus user sees
 * the real ~$1 number before tapping.
 *
 * Returned values are pre-formatted "~$0.04" strings — the UI just
 * inlines them next to the tier label.
 */
@HiltViewModel
class TierCostHintsViewModel @Inject constructor(
    aiPrefs: AiPreferences,
) : ViewModel() {

    val briefHint: StateFlow<String> = hintFlow(aiPrefs, AiUsage.REPORT_BRIEF)
    val standardHint: StateFlow<String> = hintFlow(aiPrefs, AiUsage.REPORT_STANDARD)
    val deepHint: StateFlow<String> = hintFlow(aiPrefs, AiUsage.REPORT_DEEP)

    /** Combined "script + validation + TTS" estimate for a single
     *  podcast generation. TTS dollars are approximate — the
     *  multi-speaker preview tier charges by audio output tokens which
     *  the app can't measure ahead of time; a small flat allowance is
     *  added so the dialog doesn't under-quote the user. */
    val podcastHint: StateFlow<String> = combine(
        aiPrefs.observeRanked(AiUsage.PODCAST_SCRIPT),
        aiPrefs.observeRanked(AiUsage.PODCAST_VALIDATION),
    ) { script, validation ->
        val scriptModelId = script.firstOrNull()?.id
        val validationModelId = validation.firstOrNull()?.id
        val scriptProfile = ModelCost.typicalProfile(AiUsage.PODCAST_SCRIPT)
        val validationProfile = ModelCost.typicalProfile(AiUsage.PODCAST_VALIDATION)
        val scriptCost = scriptModelId
            ?.let { ModelCost.estimateUsd(it, scriptProfile.input, scriptProfile.output) }
            ?: 0.0
        val validationCost = validationModelId
            ?.let { ModelCost.estimateUsd(it, validationProfile.input, validationProfile.output) }
            ?: 0.0
        // Flat allowance for Gemini multi-speaker TTS audio output —
        // the audio-token volume varies with podcast length, but $0.25
        // covers a typical 10-min generation without under-quoting.
        val ttsAllowance = 0.25
        ModelCost.formatUsd(scriptCost + validationCost + ttsAllowance)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "—")

    private fun hintFlow(aiPrefs: AiPreferences, usage: AiUsage): StateFlow<String> {
        val profile = ModelCost.typicalProfile(usage)
        return aiPrefs.observeRanked(usage)
            .map { ranked ->
                val id = ranked.firstOrNull()?.id ?: return@map "—"
                ModelCost.formatUsd(ModelCost.estimateUsd(id, profile.input, profile.output))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "—")
    }
}
