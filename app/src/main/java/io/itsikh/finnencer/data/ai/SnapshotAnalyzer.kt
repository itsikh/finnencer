package io.itsikh.finnencer.data.ai

import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.dao.TickerMetricsDao
import io.itsikh.finnencer.data.entity.TickerMetrics
import io.itsikh.finnencer.data.entity.TickerMetricsAnalysis
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the configured METRICS_ANALYZE model (Sonnet by default) to read a
 * ticker's current fundamentals snapshot in plain English. Cached one row
 * per (ticker, ET-calendar-date) so re-opens during the same trading day
 * are free.
 */
@Singleton
class SnapshotAnalyzer @Inject constructor(
    private val router: AiRouter,
    private val dao: TickerMetricsDao,
    private val tickerDao: TickerDao,
    private val promptPrefs: PromptPreferences,
) {

    suspend fun cached(ticker: String): TickerMetricsAnalysis? =
        dao.getAnalysis(ticker.uppercase(), todayEt())

    suspend fun analyze(
        ticker: String,
        metrics: TickerMetrics,
        currentPrice: Double?,
        force: Boolean = false,
    ): TickerMetricsAnalysis {
        val symbol = ticker.uppercase()
        val date = todayEt()
        if (!force) {
            dao.getAnalysis(symbol, date)?.let { return it }
        }

        val tickerRow = tickerDao.get(symbol)
        val prompt = buildPrompt(symbol, tickerRow?.name, currentPrice, metrics)
        val system = promptPrefs.applyExtras(
            base = DefaultPrompts.forUsage(AiUsage.METRICS_ANALYZE),
            extra = promptPrefs.get(AiUsage.METRICS_ANALYZE),
        )
        val completion = router.complete(
            usage = AiUsage.METRICS_ANALYZE,
            system = system,
            userMessage = prompt,
            maxTokens = 400,
            temperature = 0.3,
        )
        val row = TickerMetricsAnalysis(
            ticker = symbol,
            asOfDate = date,
            analysis = completion.text.trim(),
            model = completion.modelUsed.id,
            generatedAtMillis = System.currentTimeMillis(),
        )
        dao.upsertAnalysis(row)
        return row
    }

    private fun buildPrompt(
        symbol: String,
        companyName: String?,
        currentPrice: Double?,
        m: TickerMetrics,
    ): String = buildString {
        append("Ticker: ").append(symbol)
        if (!companyName.isNullOrBlank()) append(" (").append(companyName).append(')')
        append('\n')
        currentPrice?.let { append("Current price: $").append(String.format("%.2f", it)).append('\n') }
        m.fiftyTwoWeekLow?.let { lo ->
            m.fiftyTwoWeekHigh?.let { hi ->
                append("52-week range: $").append(String.format("%.2f", lo))
                append(" – $").append(String.format("%.2f", hi)).append('\n')
            }
        }
        m.marketCap?.let { append("Market cap: ").append(formatMarketCap(it)).append('\n') }
        m.peTtm?.let { append("P/E (TTM): ").append(String.format("%.1f", it)).append('\n') }
        m.epsTtm?.let { append("EPS (TTM): ").append(String.format("%.2f", it)).append('\n') }
        m.beta?.let { append("Beta: ").append(String.format("%.2f", it)).append('\n') }
        m.divYield?.let { append("Dividend yield: ").append(String.format("%.2f%%", it)).append('\n') }
        m.revGrowthYoy?.let { append("Revenue growth YoY: ").append(String.format("%+.2f%%", it)).append('\n') }
        m.priceToSales?.let { append("Price-to-sales: ").append(String.format("%.2f", it)).append('\n') }
        m.avgVol10d?.let { append("Avg volume (10d): ").append(formatVolume(it)).append('\n') }
        m.avgVol3m?.let { append("Avg volume (3m): ").append(formatVolume(it)).append('\n') }
    }

    private fun formatMarketCap(v: Double): String = when {
        v >= 1_000_000.0 -> String.format("$%.2fT", v / 1_000_000.0)
        v >= 1_000.0 -> String.format("$%.2fB", v / 1_000.0)
        else -> String.format("$%.0fM", v)
    }

    private fun formatVolume(v: Double): String = when {
        v >= 1.0 -> String.format("%.2fM", v)
        else -> String.format("%.0fK", v * 1_000.0)
    }

    private fun todayEt(): String =
        LocalDate.now(ZoneId.of("America/New_York")).toString()
}
