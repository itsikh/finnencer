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
        //
        // Output figures INCLUDE reasoning tokens, which bill at the
        // output rate now that adaptive thinking is on for every model
        // that supports it. They scale with the workload's configured
        // effort (see AiUsage.effort), so a LOW-effort call carries a
        // small reserve and an XHIGH-effort report a large one. Without
        // this the pre-tap estimate would materially under-quote.
        AiUsage.SCORING -> TokenProfile(input = 800, output = 1000)
        AiUsage.SUMMARY -> TokenProfile(input = 2000, output = 1700)
        // Report inputs grew substantially: the bundle now carries a
        // computed facts sheet, a multi-quarter trend table and the 8-K
        // Exhibit 99.1 press-release body (up to 60k chars ≈ 17k tokens).
        AiUsage.REPORT_BRIEF -> TokenProfile(input = 20000, output = 4000)
        AiUsage.REPORT_STANDARD -> TokenProfile(input = 27000, output = 9000)
        AiUsage.REPORT_DEEP -> TokenProfile(input = 34000, output = 18000)
        AiUsage.PODCAST_SCRIPT -> TokenProfile(input = 10500, output = 9000)
        // Earnings scripts carry the facts sheet on the initial pass AND
        // on each continuation, so input is billed several times over.
        AiUsage.PODCAST_EARNINGS -> TokenProfile(input = 40000, output = 13000)
        // The validator reads the whole source bundle plus the script. On
        // the earnings path that bundle now includes the facts sheet and
        // the press-release body, so input dwarfs the old estimate.
        AiUsage.PODCAST_VALIDATION -> TokenProfile(input = 20000, output = 12000)
        AiUsage.MOVE_EXPLAIN -> TokenProfile(input = 1500, output = 700)
        AiUsage.METRICS_ANALYZE -> TokenProfile(input = 2000, output = 1100)
    }

    data class TokenProfile(val input: Int, val output: Int)

    /**
     * Per-million-tokens (input, output) USD rates per provider/model.
     *
     * THE single price table for the app. [ClaudeClient] bills the cost
     * meter from this same function — there used to be a second copy
     * there, and the two had already drifted apart once.
     *
     * Matching is by substring so a dated snapshot id
     * (`claude-sonnet-5-20260401`) prices the same as the alias.
     */
    fun pricePerMillion(modelId: String): Pair<Double, Double> {
        val id = modelId.lowercase()
        return when {
            // Most capable tier — above Opus pricing. Checked before
            // "opus" so an id containing both can't be mispriced.
            id.contains("fable") || id.contains("mythos") -> 10.0 to 50.0
            id.contains("haiku") -> 1.0 to 5.0
            // Opus 4.6 through Opus 5 are $5/$25 (the old 15/75 was
            // Opus-4.1-era pricing and overstated DEEP reports ~3x).
            id.contains("opus") -> 5.0 to 25.0
            // Sonnet 5 list price. Note it is on introductory pricing of
            // $2/$10 through 2026-08-31; quoting the list rate slightly
            // over-estimates until then, which is the safe direction.
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

    /**
     * Cost in millicents (USD) for one Anthropic call, split across the
     * prompt-cache buckets.
     *
     * Ephemeral (5-minute) cache pricing:
     *  - writes cost ~1.25x the base input rate
     *  - reads cost ~0.10x the base input rate
     * Fresh input bills at the standard rate.
     */
    fun anthropicCallMillicents(
        model: String,
        freshInputTokens: Int,
        cacheCreationInputTokens: Int,
        cacheReadInputTokens: Int,
        outputTokens: Int,
    ): Long {
        val (inPerM, outPerM) = pricePerMillion(model)
        val freshCost = (freshInputTokens / 1_000_000.0) * inPerM
        val createCost = (cacheCreationInputTokens / 1_000_000.0) * inPerM * 1.25
        val readCost = (cacheReadInputTokens / 1_000_000.0) * inPerM * 0.10
        val outputCost = (outputTokens / 1_000_000.0) * outPerM
        val cents = (freshCost + createCost + readCost + outputCost) * 100
        return (cents * 1000).toLong()
    }
}
