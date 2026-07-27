package io.itsikh.finnencer.data.ai

/**
 * Best-effort cost estimator for a single LLM call.
 *
 * Used by the UI to label "this tap is going to spend ~$X" before
 * the user commits to a long-running report or podcast — closes the
 * gap where the cost meter only reports actuals after the fact.
 *
 * Prices are best-effort approximations of the published rates at the
 * time of writing. If a provider shifts, the on-screen estimate will
 * drift but the cost meter still reflects what the user was actually
 * billed (it reads the response's usage block, not these constants).
 */
object ModelCost {

    /**
     * Cost in USD for a single completion using [modelId] with the
     * given [inputTokens] and [outputTokens]. No prompt-cache discount
     * is applied — call sites use this to predict an *upper* bound for
     * a fresh call, so any cache hits are pure upside.
     */
    fun estimateUsd(modelId: String, inputTokens: Int, outputTokens: Int): Double {
        val (inPerM, outPerM) = pricePerMillion(modelId)
        return (inputTokens / 1_000_000.0) * inPerM + (outputTokens / 1_000_000.0) * outPerM
    }

    /**
     * Format a USD cost as a short human-readable string the UI can
     * inline next to an action button. Examples:
     *   0.0008 -> "<$0.01"
     *   0.04   -> "~$0.04"
     *   0.72   -> "~$0.72"
     */
    fun formatUsd(usd: Double): String = when {
        usd <= 0 -> "—"
        usd < 0.01 -> "<$0.01"
        usd < 1.0 -> "~$" + "%.2f".format(usd)
        else -> "~$" + "%.2f".format(usd)
    }

    /**
     * Typical-call profile for an [AiUsage]. Input/output token figures
     * are conservative midpoints — actual calls vary with source data
     * size, but the order of magnitude is right and the meter shows
     * actuals after the fact.
     */
    fun typicalProfile(usage: AiUsage): TokenProfile = when (usage) {
        // Sized for the Sonnet 5 / Opus 5 tokenizer (~30% more tokens
        // for the same text than the 4.6-era numbers these grew from).
        // SCORING/MOVE_EXPLAIN run on Haiku 4.5 (old tokenizer) — left as-is.
        AiUsage.SCORING -> TokenProfile(input = 800, output = 600)
        AiUsage.SUMMARY -> TokenProfile(input = 2000, output = 800)
        AiUsage.REPORT_BRIEF -> TokenProfile(input = 6500, output = 2000)
        AiUsage.REPORT_STANDARD -> TokenProfile(input = 13000, output = 4600)
        AiUsage.REPORT_DEEP -> TokenProfile(input = 19500, output = 8500)
        AiUsage.PODCAST_SCRIPT -> TokenProfile(input = 10500, output = 6000)
        AiUsage.PODCAST_VALIDATION -> TokenProfile(input = 8000, output = 6000)
        AiUsage.MOVE_EXPLAIN -> TokenProfile(input = 1500, output = 400)
        AiUsage.METRICS_ANALYZE -> TokenProfile(input = 2000, output = 800)
    }

    data class TokenProfile(val input: Int, val output: Int)

    /** Per-million-tokens (input, output) USD rates per provider/model. */
    private fun pricePerMillion(modelId: String): Pair<Double, Double> {
        val id = modelId.lowercase()
        return when {
            id.contains("haiku") -> 1.0 to 5.0
            // Opus 4.6 through Opus 5 are $5/$25 (the old 15/75 was
            // Opus-4.1-era pricing and overstated DEEP reports ~3x).
            id.contains("opus") -> 5.0 to 25.0
            id.contains("sonnet") -> 3.0 to 15.0
            // Gemini Pro tiers (3.1 Pro $2/$12; older 2.5 Pro similar
            // ballpark — better to slightly over-quote than under).
            id.contains("gemini") && id.contains("pro") -> 2.0 to 12.0
            // Gemini Flash tiers, text and TTS (rough).
            id.contains("gemini") -> 0.30 to 2.50
            // Unknown model — assume Sonnet-class so we don't under-quote.
            else -> 3.0 to 15.0
        }
    }
}
