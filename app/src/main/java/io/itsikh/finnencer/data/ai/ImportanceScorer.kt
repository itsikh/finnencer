package io.itsikh.finnencer.data.ai

import io.itsikh.finnencer.logging.AppLogger as Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.entity.ArticleCategory
import io.itsikh.finnencer.data.entity.ArticleScore
import io.itsikh.finnencer.data.entity.NewsArticle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Batched importance scorer. Pulls unscored articles, asks Claude Haiku to
 * rate each on a 1-10 financial-impact scale plus a category enum and a
 * one-sentence reason. Persists [ArticleScore] rows.
 *
 * Output of each batched call is JSON the model is instructed to emit
 * verbatim. We parse it tolerantly because Haiku is *usually* clean but
 * occasionally wraps JSON in prose.
 */
@Singleton
class ImportanceScorer @Inject constructor(
    private val claude: ClaudeClient,
    private val newsDao: NewsDao,
    private val gson: Gson,
) {

    data class ScorerStats(
        val articlesScored: Int,
        val batches: Int,
        val parseErrors: Int,
        val newScores: List<ArticleScore>,
    )

    suspend fun scoreUnscored(maxArticles: Int = 50): ScorerStats {
        val collected = mutableListOf<ArticleScore>()
        var batches = 0
        var parseErrors = 0

        var remaining = maxArticles
        while (remaining > 0) {
            val batchSize = minOf(BATCH_SIZE, remaining)
            val articles = newsDao.unscoredJoined(batchSize)
            if (articles.isEmpty()) break

            batches++
            val results = runCatching { scoreBatch(articles) }
                .onFailure { Log.e(TAG, "batch failed", it) }
                .getOrElse { emptyList() }

            if (results.isEmpty()) {
                parseErrors++
                // Avoid an infinite loop on a persistently-bad batch: give up
                // this run, let the next sync cycle retry.
                break
            }

            newsDao.insertScores(results)
            collected += results
            remaining -= articles.size
        }

        return ScorerStats(
            articlesScored = collected.size,
            batches = batches,
            parseErrors = parseErrors,
            newScores = collected,
        )
    }

    private suspend fun scoreBatch(articles: List<NewsArticle>): List<ArticleScore> {
        val payload = buildString {
            articles.forEachIndexed { idx, a ->
                append("---\n")
                append("idx: $idx\n")
                append("ticker: ${a.primaryTickerSymbol ?: "?"}\n")
                append("source: ${a.sourceName}\n")
                append("headline: ${a.title}\n")
                if (!a.snippet.isNullOrBlank()) {
                    append("snippet: ${a.snippet.take(280)}\n")
                }
            }
        }

        val raw = claude.complete(
            model = ClaudeModels.HAIKU,
            system = SYSTEM_PROMPT,
            userMessage = USER_PREAMBLE + payload + USER_POSTAMBLE,
            maxTokens = 1200,
            temperature = 0.0,
        )
        val json = claude.extractJson(raw) ?: return emptyList()
        val parsed = runCatching { gson.fromJson(json, JsonObject::class.java) }
            .getOrNull() ?: return emptyList()
        val arr = parsed["scores"]?.asJsonArray ?: return emptyList()
        val now = System.currentTimeMillis()
        val results = ArrayList<ArticleScore>(arr.size())
        for (el in arr) {
            val obj = el.asJsonObject
            val idx = obj["idx"]?.asInt ?: continue
            if (idx !in articles.indices) continue
            val article = articles[idx]
            val ticker = article.primaryTickerSymbol ?: continue
            val score = obj["score"]?.asInt?.coerceIn(1, 10) ?: continue
            val rawCategory = obj["category"]?.asString?.uppercase() ?: ArticleCategory.OTHER.name
            val category = ArticleCategory.entries.firstOrNull { it.name == rawCategory }
                ?: ArticleCategory.OTHER
            val reason = obj["reason"]?.asString?.trim().orEmpty().take(280)
            results += ArticleScore(
                articleId = article.id,
                tickerSymbol = ticker,
                score = score,
                category = category.name,
                reason = reason,
                model = ClaudeModels.HAIKU,
                scoredAtMillis = now,
            )
        }
        return results
    }

    private companion object {
        const val BATCH_SIZE = 10
        const val TAG = "ImportanceScorer"

        const val SYSTEM_PROMPT = """
You are a senior financial-news triage analyst. For each article you receive, output a strict
JSON object with one field "scores" whose value is an array of objects. Each object has:
  idx: integer (same idx as in the input)
  ticker: the ticker that was passed in
  score: integer 1..10 representing financial importance to a holder of that ticker:
    1-3 = noise (generic price recap, blog opinion, irrelevant tag-along)
    4-6 = informational (analyst commentary, minor partnerships, secondary PR)
    7-8 = material (guidance change, management departure, supply chain shock,
                    M&A talk, regulatory action, product approval/rejection)
    9-10 = critical (confirmed earnings beat/miss, accepted M&A, lawsuit damages,
                     CEO exit, FDA approval/rejection, accounting restatement)
  category: one of EARNINGS, M_AND_A, REGULATORY, MANAGEMENT, MACRO, LEGAL,
            PRODUCT, ANALYST, INSIDER, OTHER
  reason: one short sentence justifying the score (max 30 words)

Return ONLY the JSON, no preamble, no markdown fences. If unsure, prefer lower scores."""

        const val USER_PREAMBLE = "Score the following articles:\n\n"
        const val USER_POSTAMBLE = "\n\nReturn JSON only. Schema: {\"scores\": [{idx, ticker, score, category, reason}, ...]}"
    }
}
