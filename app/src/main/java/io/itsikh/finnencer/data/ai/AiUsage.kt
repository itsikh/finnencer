package io.itsikh.finnencer.data.ai

/**
 * Discrete LLM workloads that the user can route to different models from
 * Settings → AI. Each usage has a sensible default in [AiModel]; the user
 * can override per-usage from the AI preferences screen.
 */
enum class AiUsage(val displayName: String, val description: String) {
    SCORING(
        displayName = "Article scoring",
        description = "Rates every fetched article 1-10 for price impact / reaction value. High volume, runs on every sync — cheap & fast is preferred.",
    ),
    SUMMARY(
        displayName = "Article summary",
        description = "On-demand summary of a single article or a multi-article selection.",
    ),
    REPORT_BRIEF(
        displayName = "Earnings: 2-page report",
        description = "Executive brief generated from EDGAR 8-K + recent news.",
    ),
    REPORT_STANDARD(
        displayName = "Earnings: 5-page report",
        description = "Standard report with guidance commentary + segment detail.",
    ),
    REPORT_DEEP(
        displayName = "Earnings: 10-page report",
        description = "Deep dive with explicit bull/bear synthesis. Needs a large context window.",
    ),
    PODCAST_SCRIPT(
        displayName = "Podcast dialogue script",
        description = "Converts an article-bundle or summary into a Host/Analyst dialogue script before TTS renders it.",
    ),
    PODCAST_EARNINGS(
        displayName = "Earnings podcast script",
        description = "Writes the Host/Analyst script for a quarterly-results episode. Gets its own prompt and model slot because it works from a verified facts sheet (SEC actuals, margins, guidance, segments) and follows a fixed segment plan with a numeric-density floor — a general dialogue prompt pads instead of citing.",
    ),
    MOVE_EXPLAIN(
        displayName = "Why-is-it-moving?",
        description = "Per-ticker one-paragraph correlation of today's price move with recent headlines. Runs on user tap from the ticker feed.",
    ),
    METRICS_ANALYZE(
        displayName = "Snapshot interpretation",
        description = "Plain-English read of the current valuation, momentum, and risk numbers for one ticker. Runs on user tap from the snapshot screen.",
    ),
    PODCAST_VALIDATION(
        displayName = "Podcast script validation",
        description = "After the script writer produces a podcast script, a second model reads it against the requirements (length, alternating speakers, no mid-script re-intros, no fabricated numbers, analyst-reactions segment for 20-min+) and either passes it through, rewrites it, or flags it for your review.",
    ),
}

/**
 * Discrete model identifiers we can route a usage to. The string `id` is
 * what gets sent on the wire to the provider; the [provider] flag tells the
 * router which client to use.
 */
enum class AiModel(
    val id: String,
    val displayName: String,
    val provider: AiProvider,
    val maxContextTokens: Int,
    val supportsLongOutput: Boolean,
    val tier: AiTier,
    /**
     * Whether the model accepts the `temperature` request parameter.
     * Anthropic deprecated `temperature` on Opus 4.x (server returns
     * HTTP 400 `"\`temperature\` is deprecated for this model."` if
     * the field is present). The router strips temperature for any
     * model where this is false.
     */
    val supportsTemperature: Boolean = true,
    /**
     * Whether the model accepts `output_config.effort`. Anthropic's
     * pre-5 models (Haiku 4.5, Sonnet 4.5) return HTTP 400 when it's
     * present, so this is opt-IN: unknown ids default to false and
     * simply don't get an effort hint.
     */
    val supportsEffort: Boolean = false,
    /** How this model expects the `thinking` field to be sent. */
    val thinking: AiThinkingMode = AiThinkingMode.OMIT,
) {
    // Sonnet 5: adaptive thinking, non-default sampling params rejected.
    // New tokenizer: ~30% more tokens for the same text than 4.6.
    CLAUDE_SONNET_5(
        "claude-sonnet-5", "Claude Sonnet 5", AiProvider.ANTHROPIC, 1_000_000, true, AiTier.BALANCED,
        supportsTemperature = false, supportsEffort = true, thinking = AiThinkingMode.ADAPTIVE,
    ),
    // Opus 5: $5/$25, 1M ctx by default (no beta header). Thinking is on
    // by default; we send it explicitly so the intent is visible on the
    // wire. `disabled` would be valid only at effort <= high, which is
    // one more reason not to rely on it.
    CLAUDE_OPUS_5(
        "claude-opus-5", "Claude Opus 5 (1M ctx)", AiProvider.ANTHROPIC, 1_000_000, true, AiTier.LARGE,
        supportsTemperature = false, supportsEffort = true, thinking = AiThinkingMode.ADAPTIVE,
    ),
    // Fable 5: most capable, $10/$50, 1M ctx, 128K output. Thinking is
    // ALWAYS on and cannot be configured — sending any `thinking` value
    // (including "adaptive") risks a 400, so the field is omitted
    // entirely. Requires 30-day data retention: under a zero-retention
    // org setting EVERY request fails with 400, which is worth checking
    // before making it a default anywhere.
    CLAUDE_FABLE_5(
        "claude-fable-5", "Claude Fable 5", AiProvider.ANTHROPIC, 1_000_000, true, AiTier.LARGE,
        supportsTemperature = false, supportsEffort = true, thinking = AiThinkingMode.ALWAYS_ON,
    ),
    GEMINI_3_6_FLASH("gemini-3.6-flash", "Gemini 3.6 Flash", AiProvider.GEMINI, 1_000_000, true, AiTier.FAST_CHEAP),
    GEMINI_3_1_PRO("gemini-3.1-pro", "Gemini 3.1 Pro", AiProvider.GEMINI, 2_000_000, true, AiTier.LARGE),

    /**
     * SENTINEL — resolved to a concrete id by [GeminiModelCatalog] just
     * before each call, from Google's ListModels endpoint. This [id] is
     * never sent on the wire.
     *
     * Exists so a fallback slot tracks Google's catalog instead of aging
     * into an older model the way a pinned id does. Prefer this over
     * [GEMINI_3_1_PRO] wherever the point is "a good Gemini Pro" rather
     * than "this exact release".
     */
    GEMINI_PRO_LATEST(
        "gemini-pro-latest", "Gemini Pro (latest)", AiProvider.GEMINI, 2_000_000, true, AiTier.LARGE,
    );

    /** True for ids that must be resolved before use. */
    val isSymbolic: Boolean get() = this == GEMINI_PRO_LATEST

    companion object {
        fun byId(id: String?): AiModel? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How a model wants the Anthropic `thinking` request field handled.
 *
 * [OMIT] and [ALWAYS_ON] both send nothing, but for opposite reasons —
 * kept distinct so the catalog documents intent rather than a
 * coincidence of wire format.
 */
enum class AiThinkingMode {
    /** Unknown/legacy model: don't send the field. */
    OMIT,
    /** Send `{"type": "adaptive"}` — Opus 5, Sonnet 5. */
    ADAPTIVE,
    /** Thinking can't be configured; sending the field risks a 400. */
    ALWAYS_ON,
}

/**
 * Reasoning depth hint (`output_config.effort`). Higher settings think
 * longer and spend more output tokens; thinking tokens bill at the
 * output rate and share the `max_tokens` cap with the response text, so
 * raising effort means raising the token budget too.
 */
enum class AiEffort(val wire: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
}

/**
 * Reasoning depth per workload. Set against what the task actually
 * needs, not uniformly: analysis and adversarial review earn deep
 * reasoning, classification and one-paragraph explanations don't, and
 * effort is the main lever on both latency and cost now that thinking
 * is enabled everywhere.
 */
val AiUsage.effort: AiEffort
    get() = when (this) {
        // Structured classification against a fixed rubric — the work is
        // recall, not reasoning.
        AiUsage.SCORING -> AiEffort.LOW
        // Summaries span one article to a ten-page multi-article
        // synthesis. The long end is genuine cross-source analysis —
        // working out why a set of articles belongs together — which LOW
        // under-serves; the short end costs little either way.
        AiUsage.SUMMARY -> AiEffort.MEDIUM
        AiUsage.MOVE_EXPLAIN -> AiEffort.LOW
        AiUsage.METRICS_ANALYZE -> AiEffort.LOW
        // Reports scale with how much synthesis the tier asks for.
        AiUsage.REPORT_BRIEF -> AiEffort.MEDIUM
        AiUsage.REPORT_STANDARD -> AiEffort.HIGH
        AiUsage.REPORT_DEEP -> AiEffort.XHIGH
        // Podcast is the app's headline feature and the earnings script
        // has to hold numeric fidelity against the facts sheet across a
        // long generation — the documented failure mode is padding
        // instead of citing, which is exactly what reasoning fixes.
        AiUsage.PODCAST_SCRIPT -> AiEffort.HIGH
        AiUsage.PODCAST_EARNINGS -> AiEffort.HIGH
        // Adversarial read of someone else's script against ground truth.
        AiUsage.PODCAST_VALIDATION -> AiEffort.HIGH
    }

enum class AiProvider { ANTHROPIC, GEMINI }

enum class AiTier { FAST_CHEAP, BALANCED, LARGE }

/**
 * Routable model option. Either a hard-coded [AiModel] enum entry (shipped
 * with the app) or a [Custom] entry discovered at runtime from a provider's
 * ListModels endpoint (used to surface Gemini models that came out after we
 * cut a release, e.g. gemini-3.x-pro). Both flow through [AiRouter] the
 * same way — only [provider] decides which client handles the call.
 */
sealed class AiModelOption {
    abstract val id: String
    abstract val displayName: String
    abstract val provider: AiProvider
    abstract val tier: AiTier

    data class Builtin(val model: AiModel) : AiModelOption() {
        override val id: String get() = model.id
        override val displayName: String get() = model.displayName
        override val provider: AiProvider get() = model.provider
        override val tier: AiTier get() = model.tier
    }

    data class Custom(
        override val id: String,
        override val displayName: String,
        override val provider: AiProvider,
        override val tier: AiTier,
    ) : AiModelOption()
}

/**
 * Initial default model per usage.
 *
 * Haiku was retired from the catalog: scoring and move-explanation are
 * judgement calls dressed as classification, and at batch sizes of ~10
 * articles the difference is fractions of a cent per batch. One
 * consequence to be aware of — Sonnet 5 rejects non-default sampling
 * params, so the `temperature = 0.0` that scoring used for run-to-run
 * stability is now stripped by the router; stability rests on the
 * strict-JSON prompt contract and LOW effort instead.
 *
 * Saved preferences pointing at the removed Haiku id resolve to null in
 * [AiPreferences.resolve] and fall back to these defaults — no migration
 * needed.
 */
val AiUsage.defaultModel: AiModel get() = defaultRanked.first()

/**
 * Default RANKED model list per usage: position 0 is the primary, the
 * rest are tried in order on failure (same semantics as a user's saved
 * ranking in [AiPreferences]).
 *
 * Most usages have a single entry — the router's rescue chain already
 * covers generic failures. A usage declares a longer chain here only
 * when a specific fallback is meaningfully better than the generic one.
 */
val AiUsage.defaultRanked: List<AiModel>
    get() = when (this) {
        AiUsage.SCORING -> listOf(AiModel.CLAUDE_SONNET_5)
        AiUsage.SUMMARY -> listOf(AiModel.CLAUDE_SONNET_5)
        AiUsage.MOVE_EXPLAIN -> listOf(AiModel.CLAUDE_SONNET_5)
        AiUsage.METRICS_ANALYZE -> listOf(AiModel.CLAUDE_SONNET_5)
        AiUsage.REPORT_BRIEF -> listOf(AiModel.CLAUDE_SONNET_5)
        AiUsage.REPORT_STANDARD -> listOf(AiModel.CLAUDE_OPUS_5)
        AiUsage.REPORT_DEEP -> listOf(AiModel.CLAUDE_OPUS_5)
        // Podcast is the headline feature, so it runs on Opus at HIGH
        // effort. This reverses the Sonnet default from #42, which was a
        // latency decision made against WorkManager's 10-minute
        // single-run cap — a cap the worker no longer lives under, since
        // it promotes itself to foreground immediately. The job budget
        // (JobDeadline) is the ceiling now, not the OS.
        AiUsage.PODCAST_SCRIPT -> listOf(AiModel.CLAUDE_OPUS_5)
        AiUsage.PODCAST_EARNINGS -> listOf(AiModel.CLAUDE_OPUS_5)
        // Validation only means something if the validator is genuinely
        // independent of the writer — a model reviewing its own output
        // tends to endorse its own mistakes. With the writer on Opus 5,
        // the validator moves UP to Fable 5 rather than sideways.
        //
        // Gemini Pro is the declared fallback rather than another Claude
        // model for two reasons. First, Fable has failure modes no Claude
        // fallback avoids: it can decline a request outright, and it
        // returns 400 on EVERY call if the Anthropic org is set to zero
        // data retention. Second, a different PROVIDER is the strongest
        // form of the independence the validator exists to provide — and
        // it keeps working when the network blocks Anthropic entirely
        // (#86). Podcast users already hold a Gemini key for TTS, so this
        // fallback is configured in practice.
        AiUsage.PODCAST_VALIDATION -> listOf(AiModel.CLAUDE_FABLE_5, AiModel.GEMINI_PRO_LATEST)
    }
