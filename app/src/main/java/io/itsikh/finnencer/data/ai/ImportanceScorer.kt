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
    private val router: AiRouter,
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

    /**
     * Single-article re-score with an optional user-supplied note appended
     * to the system prompt. Replaces (or inserts) the [ArticleScore] row
     * for every ticker the article references. Returns the new rows.
     */
    suspend fun rescoreSingle(article: NewsArticle, note: String?): List<ArticleScore> {
        val noteBlock = note?.takeIf { it.isNotBlank() }?.let {
            "\n\nAdditional reviewer note for this article (apply faithfully):\n${it.trim()}"
        }.orEmpty()
        val payload = buildString {
            append("---\n")
            append("idx: 0\n")
            append("ticker: ").append(article.primaryTickerSymbol ?: "?").append('\n')
            append("source: ").append(article.sourceName).append('\n')
            append("headline: ").append(article.title).append('\n')
            article.snippet?.takeIf { it.isNotBlank() }?.let {
                append("snippet: ").append(it.take(280)).append('\n')
            }
        }
        val completion = router.complete(
            usage = AiUsage.SCORING,
            system = SYSTEM_PROMPT + noteBlock,
            userMessage = USER_PREAMBLE + payload + USER_POSTAMBLE,
            maxTokens = 600,
            temperature = 0.0,
        )
        val json = router.extractJson(completion.text) ?: return emptyList()
        val parsed = runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull()
            ?: return emptyList()
        val arr = parsed["scores"]?.asJsonArray ?: return emptyList()
        val now = System.currentTimeMillis()
        val out = mutableListOf<ArticleScore>()
        for (el in arr) {
            val obj = el.asJsonObject
            val ticker = article.primaryTickerSymbol ?: continue
            val score = obj["score"]?.asInt?.coerceIn(1, 10) ?: continue
            val rawCategory = obj["category"]?.asString?.uppercase() ?: ArticleCategory.OTHER.name
            val category = ArticleCategory.entries.firstOrNull { it.name == rawCategory }
                ?: ArticleCategory.OTHER
            val reason = obj["reason"]?.asString?.trim().orEmpty().take(280)
            out += ArticleScore(
                articleId = article.id,
                tickerSymbol = ticker,
                score = score,
                category = category.name,
                reason = reason,
                model = completion.modelUsed.id,
                scoredAtMillis = now,
            )
        }
        if (out.isNotEmpty()) newsDao.insertScores(out)
        return out
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

        val completion = router.complete(
            usage = AiUsage.SCORING,
            system = SYSTEM_PROMPT,
            userMessage = USER_PREAMBLE + payload + USER_POSTAMBLE,
            maxTokens = 1200,
            temperature = 0.0,
        )
        val raw = completion.text
        val json = router.extractJson(raw) ?: return emptyList()
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
                model = completion.modelUsed.id,
                scoredAtMillis = now,
            )
        }
        return results
    }

    private companion object {
        const val BATCH_SIZE = 10
        const val TAG = "ImportanceScorer"

        const val SYSTEM_PROMPT = """
You are scoring financial news articles for an active investor in the named ticker.

The reader uses these scores to triage which stories to read NOW so they can
react to them (add, trim, hedge, hold through, ignore). Two filters apply:

(1) Reaction value — does the reader gain a decision-relevant signal from
    reading this? Generic explainers, history, evergreen pieces score low
    even when the company is named.
(2) Price-impact magnitude — how strongly could this article move the stock
    in the days/weeks after publication?

Score on the joint intent: high score = "tell me about this so I can react";
low score = "this is fine to skip, no action implied."

Output a strict JSON object with one field "scores" whose value is an array of:
  idx: integer (same as input)
  ticker: the ticker that was passed in
  score: integer 1..10 — direct price-impact magnitude:
    1-3 = no plausible price effect (generic recap, restated facts, off-topic
          tag-along, advice / opinion blog with no new fact)
    4-6 = secondary signal that informs view but rarely moves the tape alone
          (Street commentary, analyst chatter without rating change, minor
          partnerships, lesser PR, market-color tag-along)
    7-8 = MATERIAL — likely to move the stock measurably:
          - earnings or guidance commentary
          - analyst rating change or price-target revision >5%
          - management appointment / departure
          - supply-chain shock, large customer win/loss
          - M&A talk, regulatory action, product approval/rejection
          - notable insider transactions
    9-10 = CRITICAL — high-conviction price-mover:
          - confirmed earnings beat/miss vs consensus
          - accepted or rejected M&A
          - lawsuit damages, accounting restatement
          - CEO unexpected exit
          - FDA approval/rejection or major regulatory decision
          - guidance cut or raise >10%
  category: one of EARNINGS, M_AND_A, REGULATORY, MANAGEMENT, MACRO, LEGAL,
            PRODUCT, ANALYST, INSIDER, OTHER
  reason: one short sentence naming the specific price-impact path (max 30 words)

Bias notes:
 - When ambiguous between two adjacent tiers, prefer the HIGHER one if you can
   articulate any plausible price-impact mechanism.
 - Headlines that quote a specific number (revenue, EPS, % change, dollar
   damages, $ target) almost always score 7+.
 - Purely descriptive or evergreen content (explainers, history pieces,
   educational) scores 1-3 even if the company is named.

Return ONLY the JSON, no preamble, no markdown fences."""

        const val USER_PREAMBLE = "Score the following articles:\n\n"
        const val USER_POSTAMBLE = "\n\nReturn JSON only. Schema: {\"scores\": [{idx, ticker, score, category, reason}, ...]}"
    }
}
