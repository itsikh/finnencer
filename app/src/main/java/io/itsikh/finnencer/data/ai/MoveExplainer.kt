package io.itsikh.finnencer.data.ai

import io.itsikh.finnencer.data.dao.MoveExplanationDao
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.dao.ScoredArticleRow
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.entity.MoveExplanation
import io.itsikh.finnencer.data.repo.QuotePoller
import io.itsikh.finnencer.data.repo.TickerQuote
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks Claude Haiku to summarize, in one paragraph, why a watched ticker
 * is moving today. Cached one row per (ticker, ET-calendar-date) so a
 * curious user can re-open the feed all day without paying twice.
 */
@Singleton
class MoveExplainer @Inject constructor(
    private val router: AiRouter,
    private val dao: MoveExplanationDao,
    private val newsDao: NewsDao,
    private val tickerDao: TickerDao,
    private val quotePoller: QuotePoller,
) {

    sealed class Outcome {
        data class Ready(val row: MoveExplanation) : Outcome()
        data class NoNews(val pctChange: Double, val asOfDate: String) : Outcome()
    }

    suspend fun cached(ticker: String): MoveExplanation? =
        dao.get(ticker.uppercase(), todayEt())

    suspend fun explain(ticker: String, force: Boolean = false): Outcome {
        val symbol = ticker.uppercase()
        val date = todayEt()
        if (!force) {
            dao.get(symbol, date)?.let { return Outcome.Ready(it) }
        }

        val quote = quotePoller.snapshot(symbol)
            ?: quotePoller.latest.value[symbol]
            ?: error("Couldn't fetch a quote for $symbol — Yahoo unreachable.")

        val articles = newsDao.observeTickerFeed(symbol, limit = 50).first()
            .filter { it.published_at_millis >= System.currentTimeMillis() - WINDOW_MS }
            .take(MAX_ARTICLES)

        if (articles.isEmpty()) {
            return Outcome.NoNews(pctChange = quote.changePercent, asOfDate = date)
        }

        val tickerRow = tickerDao.get(symbol)
        val prompt = buildPrompt(symbol, tickerRow?.name, quote, articles)
        val completion = router.completeWith(
            model = AiModel.CLAUDE_HAIKU_4_5,
            system = SYSTEM_PROMPT,
            userMessage = prompt,
            maxTokens = 220,
            temperature = 0.3,
        )
        val text = completion.text.trim()

        val row = MoveExplanation(
            ticker = symbol,
            asOfDate = date,
            pctChange = quote.changePercent,
            explanation = text,
            model = completion.modelUsed.id,
            articleIdsCsv = articles.joinToString(",") { it.id },
            generatedAtMillis = System.currentTimeMillis(),
        )
        dao.upsert(row)
        return Outcome.Ready(row)
    }

    private fun buildPrompt(
        symbol: String,
        companyName: String?,
        quote: TickerQuote,
        articles: List<ScoredArticleRow>,
    ): String = buildString {
        append("Ticker: ").append(symbol)
        if (!companyName.isNullOrBlank()) append(" (").append(companyName).append(')')
        append('\n')
        append("Today's move: ")
        append(String.format("%+.2f%%", quote.changePercent))
        append(" (price $").append(String.format("%.2f", quote.price)).append(')')
        append('\n')
        append("Recent news (most recent first):\n")
        val now = System.currentTimeMillis()
        articles.forEachIndexed { index, row ->
            val ageHours = ((now - row.published_at_millis) / 3_600_000L).coerceAtLeast(0L)
            append(index + 1).append(". [").append(row.source_name).append(", ").append(ageHours).append("h ago] ")
            append(row.title)
            row.snippet?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it.trim()) }
            append('\n')
        }
    }

    private fun todayEt(): String =
        LocalDate.now(ZoneId.of("America/New_York")).toString()

    private companion object {
        const val WINDOW_MS = 36L * 60L * 60L * 1000L
        const val MAX_ARTICLES = 8
        const val SYSTEM_PROMPT = """
You are a financial analyst writing for a single retail investor who already follows this stock.
Given today's price move and the most recent headlines, identify the most likely catalyst in
one short paragraph (60-100 words). Cite article titles briefly in-text. If no headline plausibly
explains the move, say "No clear catalyst — looks like sector drift or broader market." Do not
speculate beyond what the headlines say. Plain prose, no markdown, no bullet lists."""
    }
}
