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
        description = "Converts a report or article-bundle into a Host/Analyst dialogue script before TTS renders it.",
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
) {
    CLAUDE_HAIKU_4_5("claude-haiku-4-5-20251001", "Claude Haiku 4.5", AiProvider.ANTHROPIC, 200_000, false, AiTier.FAST_CHEAP),
    CLAUDE_SONNET_4_6("claude-sonnet-4-6", "Claude Sonnet 4.6", AiProvider.ANTHROPIC, 200_000, true, AiTier.BALANCED),
    CLAUDE_OPUS_4_7("claude-opus-4-7", "Claude Opus 4.7 (1M ctx)", AiProvider.ANTHROPIC, 1_000_000, true, AiTier.LARGE, supportsTemperature = false),
    GEMINI_2_5_FLASH("gemini-2.5-flash", "Gemini 2.5 Flash", AiProvider.GEMINI, 1_000_000, true, AiTier.FAST_CHEAP),
    GEMINI_2_5_PRO("gemini-2.5-pro", "Gemini 2.5 Pro", AiProvider.GEMINI, 2_000_000, true, AiTier.LARGE);

    companion object {
        fun byId(id: String?): AiModel? = entries.firstOrNull { it.id == id }
    }
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

/** Initial default model per usage. */
val AiUsage.defaultModel: AiModel
    get() = when (this) {
        AiUsage.SCORING -> AiModel.CLAUDE_HAIKU_4_5
        AiUsage.SUMMARY -> AiModel.CLAUDE_SONNET_4_6
        AiUsage.REPORT_BRIEF -> AiModel.CLAUDE_SONNET_4_6
        AiUsage.REPORT_STANDARD -> AiModel.CLAUDE_SONNET_4_6
        AiUsage.REPORT_DEEP -> AiModel.CLAUDE_OPUS_4_7
        // Podcast script doesn't need Opus 4.7's 1M context — Sonnet 4.6
        // responds 2-3x faster and costs ~5x less per token, which cuts
        // total worker runtime well clear of WorkManager's 10-min cap
        // even with continuation passes (#42 — "rewire & optimize").
        // Users who already picked Opus 4.7 in Settings → AI keep their
        // choice; this only affects fresh installs / unconfigured slots.
        AiUsage.PODCAST_SCRIPT -> AiModel.CLAUDE_SONNET_4_6
        AiUsage.MOVE_EXPLAIN -> AiModel.CLAUDE_HAIKU_4_5
        AiUsage.METRICS_ANALYZE -> AiModel.CLAUDE_SONNET_4_6
        // Validator runs against the script-writer's output — using a
        // stronger model gives a meaningful second opinion. If the
        // validator is the same model as the writer it risks endorsing
        // its own mistakes.
        AiUsage.PODCAST_VALIDATION -> AiModel.CLAUDE_OPUS_4_7
    }
