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
) {
    // Haiku 4.5 is still the newest Haiku as of 2026-07.
    CLAUDE_HAIKU_4_5("claude-haiku-4-5-20251001", "Claude Haiku 4.5", AiProvider.ANTHROPIC, 200_000, false, AiTier.FAST_CHEAP),
    // Sonnet 5: adaptive thinking on by default (ClaudeClient sends
    // thinking=disabled to keep behavior/cost parity) and non-default
    // sampling params are rejected — hence supportsTemperature=false.
    // New tokenizer: ~30% more tokens for the same text than 4.6.
    CLAUDE_SONNET_5("claude-sonnet-5", "Claude Sonnet 5", AiProvider.ANTHROPIC, 1_000_000, true, AiTier.BALANCED, supportsTemperature = false),
    // Opus 5: drop-in for Opus 4.7 at the same $5/$25 pricing; 1M ctx
    // is the default (no beta header needed). Same thinking caveat as
    // Sonnet 5.
    CLAUDE_OPUS_5("claude-opus-5", "Claude Opus 5 (1M ctx)", AiProvider.ANTHROPIC, 1_000_000, true, AiTier.LARGE, supportsTemperature = false),
    GEMINI_3_6_FLASH("gemini-3.6-flash", "Gemini 3.6 Flash", AiProvider.GEMINI, 1_000_000, true, AiTier.FAST_CHEAP),
    GEMINI_3_1_PRO("gemini-3.1-pro", "Gemini 3.1 Pro", AiProvider.GEMINI, 2_000_000, true, AiTier.LARGE);

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
        AiUsage.SUMMARY -> AiModel.CLAUDE_SONNET_5
        AiUsage.REPORT_BRIEF -> AiModel.CLAUDE_SONNET_5
        AiUsage.REPORT_STANDARD -> AiModel.CLAUDE_SONNET_5
        AiUsage.REPORT_DEEP -> AiModel.CLAUDE_OPUS_5
        // Podcast script doesn't need Opus-tier 1M context — Sonnet
        // responds 2-3x faster and costs ~40% less per token, which cuts
        // total worker runtime well clear of WorkManager's 10-min cap
        // even with continuation passes (#42 — "rewire & optimize").
        // Users who already picked Opus in Settings → AI keep their
        // choice; this only affects fresh installs / unconfigured slots.
        AiUsage.PODCAST_SCRIPT -> AiModel.CLAUDE_SONNET_5
        // Same reasoning as PODCAST_SCRIPT: the earnings script runs with
        // continuation passes inside WorkManager's 10-minute window, so
        // Sonnet's latency matters more here than Opus's extra depth.
        AiUsage.PODCAST_EARNINGS -> AiModel.CLAUDE_SONNET_5
        AiUsage.MOVE_EXPLAIN -> AiModel.CLAUDE_HAIKU_4_5
        AiUsage.METRICS_ANALYZE -> AiModel.CLAUDE_SONNET_5
        // Validator runs against the script-writer's output — using a
        // stronger model gives a meaningful second opinion. If the
        // validator is the same model as the writer it risks endorsing
        // its own mistakes.
        AiUsage.PODCAST_VALIDATION -> AiModel.CLAUDE_OPUS_5
    }
