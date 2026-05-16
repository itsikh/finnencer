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
) {
    CLAUDE_HAIKU_4_5("claude-haiku-4-5-20251001", "Claude Haiku 4.5", AiProvider.ANTHROPIC, 200_000, false, AiTier.FAST_CHEAP),
    CLAUDE_SONNET_4_6("claude-sonnet-4-6", "Claude Sonnet 4.6", AiProvider.ANTHROPIC, 200_000, true, AiTier.BALANCED),
    CLAUDE_OPUS_4_7("claude-opus-4-7", "Claude Opus 4.7 (1M ctx)", AiProvider.ANTHROPIC, 1_000_000, true, AiTier.LARGE),
    GEMINI_2_5_FLASH("gemini-2.5-flash", "Gemini 2.5 Flash", AiProvider.GEMINI, 1_000_000, true, AiTier.FAST_CHEAP),
    GEMINI_2_5_PRO("gemini-2.5-pro", "Gemini 2.5 Pro", AiProvider.GEMINI, 2_000_000, true, AiTier.LARGE);

    companion object {
        fun byId(id: String?): AiModel? = entries.firstOrNull { it.id == id }
    }
}

enum class AiProvider { ANTHROPIC, GEMINI }

enum class AiTier { FAST_CHEAP, BALANCED, LARGE }

/** Initial default model per usage. */
val AiUsage.defaultModel: AiModel
    get() = when (this) {
        AiUsage.SCORING -> AiModel.CLAUDE_HAIKU_4_5
        AiUsage.SUMMARY -> AiModel.CLAUDE_SONNET_4_6
        AiUsage.REPORT_BRIEF -> AiModel.CLAUDE_SONNET_4_6
        AiUsage.REPORT_STANDARD -> AiModel.CLAUDE_SONNET_4_6
        AiUsage.REPORT_DEEP -> AiModel.CLAUDE_OPUS_4_7
        AiUsage.PODCAST_SCRIPT -> AiModel.CLAUDE_OPUS_4_7
    }
