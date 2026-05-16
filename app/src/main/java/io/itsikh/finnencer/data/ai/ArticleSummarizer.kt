package io.itsikh.finnencer.data.ai

import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.entity.ArticleSummary
import io.itsikh.finnencer.data.entity.NewsArticle
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

    /**
     * Returns a summary string for [article]. Reads from the cache first; if
     * absent calls Claude Sonnet, persists, and returns the new summary.
     */
    suspend fun summarize(article: NewsArticle): String {
        newsDao.summaryFor(article.id)?.let { return it.summary }

        val prompt = buildString {
            append("Article headline: ").append(article.title).append('\n')
            article.snippet?.takeIf { it.isNotBlank() }?.let {
                append("Snippet from the source: ").append(it).append('\n')
            }
            append("Ticker context: ").append(article.primaryTickerSymbol ?: "unknown").append('\n')
            append("Source publication: ").append(article.sourceName).append('\n')
        }

        val completion = router.complete(
            usage = AiUsage.SUMMARY,
            system = SYSTEM_PROMPT,
            userMessage = prompt,
            maxTokens = 600,
            temperature = 0.2,
        )
        val text = completion.text.trim()

        newsDao.insertSummary(
            ArticleSummary(
                articleId = article.id,
                summary = text,
                model = completion.modelUsed.id,
                generatedAtMillis = System.currentTimeMillis(),
            )
        )
        return text
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
