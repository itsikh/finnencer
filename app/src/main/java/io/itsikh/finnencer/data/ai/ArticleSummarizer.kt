package io.itsikh.finnencer.data.ai

import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.entity.ArticleSummary
import io.itsikh.finnencer.data.entity.NewsArticle
import io.itsikh.finnencer.data.entity.SummaryVersion
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-demand article summarizer using Claude Sonnet. Cached in
 * [ArticleSummary] so we never pay twice for the same article.
 */
@Singleton
class ArticleSummarizer @Inject constructor(
    private val router: AiRouter,
    private val newsDao: NewsDao,
) {

    /** Text + the model that produced it (for the attribution line in the UI). */
    data class CachedSummary(val text: String, val model: String?)

    /**
     * Returns the latest cached summary for [article] without regenerating.
     * Prefers the newest [SummaryVersion] (versioned regenerate history);
     * falls back to the legacy single-row [ArticleSummary] cache.
     */
    suspend fun latestSummary(article: NewsArticle): CachedSummary? {
        newsDao.latestSummaryVersion(article.id)?.let {
            return CachedSummary(it.summary, it.model)
        }
        newsDao.summaryFor(article.id)?.let {
            return CachedSummary(it.summary, it.model)
        }
        return null
    }

    /**
     * Returns the latest cached summary or generates a new one with default
     * settings if no summary exists yet. Used by the "AI Summary" button on
     * the article detail screen when first opened — preserves the existing
     * one-tap UX without forcing the user to pick a tier.
     */
    suspend fun summarizeIfMissing(article: NewsArticle): CachedSummary {
        latestSummary(article)?.let { return it }
        return regenerate(article, pagesTarget = null, customPrompt = null)
    }

    /**
     * Always generates a new summary and saves it as a new [SummaryVersion]
     * row — the older rows stay in the DB so the user can compare or revert.
     *
     * @param pagesTarget approximate page count (2/5/10 etc.) or null for
     *        default short-form (~5-6 sentences).
     * @param customPrompt user-supplied extra instructions appended to the
     *        base prompt for this run only.
     */
    suspend fun regenerate(
        article: NewsArticle,
        pagesTarget: Int?,
        customPrompt: String?,
    ): CachedSummary {
        val prompt = buildString {
            append("Article headline: ").append(article.title).append('\n')
            article.snippet?.takeIf { it.isNotBlank() }?.let {
                append("Snippet from the source: ").append(it).append('\n')
            }
            append("Ticker context: ").append(article.primaryTickerSymbol ?: "unknown").append('\n')
            append("Source publication: ").append(article.sourceName).append('\n')
        }
        val system = buildString {
            append(SYSTEM_PROMPT)
            if (pagesTarget != null && pagesTarget > 0) {
                append("\n\nTarget length: about ").append(pagesTarget).append(" pages of dense prose ")
                append("(~").append(pagesTarget * 350).append(" words).")
            }
            if (!customPrompt.isNullOrBlank()) {
                append("\n\nAdditional user instructions (apply faithfully):\n")
                append(customPrompt.trim())
            }
        }
        val maxTokens = when {
            pagesTarget == null -> 600
            pagesTarget <= 2 -> 2_000
            pagesTarget <= 5 -> 4_500
            else -> 9_000
        }
        val completion = router.complete(
            usage = AiUsage.SUMMARY,
            system = system,
            userMessage = prompt,
            maxTokens = maxTokens,
            temperature = 0.2,
        )
        val text = completion.text.trim()

        newsDao.insertSummaryVersion(
            SummaryVersion(
                articleId = article.id,
                summary = text,
                model = completion.modelUsed.id,
                pagesTarget = pagesTarget,
                customPrompt = customPrompt?.ifBlank { null },
                generatedAtMillis = System.currentTimeMillis(),
            )
        )
        return CachedSummary(text = text, model = completion.modelUsed.id)
    }

    private companion object {
        const val SYSTEM_PROMPT = """
You are a financial-news summarizer for an investor watching a specific ticker.

Write a tight 4-6 sentence summary that answers, in this order:
1. What happened (one sentence, facts only).
2. Why it matters to a holder of the named ticker (one sentence; price/guidance/risk).
3. Key numbers if present (one sentence).
4. What's still unknown / next catalyst (one sentence).

Constraints:
- Do not speculate beyond what is given.
- If the source snippet is sparse, say so and stop after step 2.
- No bullet lists, no markdown headings — plain prose paragraphs only.
- Plain English, no jargon unless the ticker's sector requires it."""
    }
}
