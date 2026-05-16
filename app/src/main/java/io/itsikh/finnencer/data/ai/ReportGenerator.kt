package io.itsikh.finnencer.data.ai

import io.itsikh.finnencer.logging.AppLogger as Log
import com.google.gson.Gson
import io.itsikh.finnencer.data.api.FinnhubService
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.entity.EarningsReport
import io.itsikh.finnencer.data.entity.ReportTier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates a BRIEF / STANDARD / DEEP earnings report for a given
 * EarningsEvent. Each tier assembles its own source bundle AND uses its own
 * prompt template — per the design doc, "be brief" alone gives shallow
 * coverage of everything; we want deep coverage of what matters.
 *
 * Source mix per tier (without Finnhub Pro transcripts in MVP):
 *  - BRIEF:    consensus + actual numbers + last 5 SEC filings + last 3
 *              high-score news items + price-target snapshot
 *  - STANDARD: + last 10 news items + recommendation-trends history
 *              + filing snippets
 *  - DEEP:     + full last-quarter PT history + full filings list (titles
 *              only — no body extraction yet) + bull/bear framing
 */
@Singleton
class ReportGenerator @Inject constructor(
    private val router: AiRouter,
    private val finnhub: FinnhubService,
    private val tickerDao: TickerDao,
    private val newsDao: NewsDao,
    private val earningsDao: EarningsDao,
    @Suppress("unused") private val gson: Gson,
) {

    suspend fun generate(eventId: Long, tier: ReportTier): Long {
        val event = earningsDao.getEvent(eventId)
            ?: error("EarningsEvent $eventId not found")
        val ticker = tickerDao.get(event.tickerSymbol)
            ?: error("Ticker ${event.tickerSymbol} no longer in watchlist")

        // ───────── Source bundle ─────────
        val bundle = StringBuilder()
        bundle.append("# ${ticker.symbol} — ${ticker.name}\n")
        bundle.append("Fiscal Q${event.fiscalQuarter} ${event.fiscalYear}\n")
        bundle.append("Scheduled: ${event.scheduledAtMillis}\n")
        if (event.actualReportedAtMillis != null) {
            bundle.append("Reported: ${event.actualReportedAtMillis}\n")
        }
        bundle.append("\n## Consensus vs actual\n")
        bundle.append(" - EPS consensus: ${fmt(event.consensusEps)}  actual: ${fmt(event.actualEps)}\n")
        bundle.append(" - Revenue consensus: ${fmt(event.consensusRevenue)}  actual: ${fmt(event.actualRevenue)}\n")

        val newsLimit = when (tier) {
            ReportTier.BRIEF -> 5; ReportTier.STANDARD -> 12; ReportTier.DEEP -> 25
        }
        val recent = newsDao.recentForTicker(ticker.symbol, newsLimit)
        bundle.append("\n## Recent news titles\n")
        if (recent.isEmpty()) {
            bundle.append("(no recent news in local cache)\n")
        } else {
            recent.forEach { a ->
                val snippet = a.snippet?.take(160)?.replace("\n", " ")
                bundle.append(" - [${a.sourceName}] ${a.title}")
                if (!snippet.isNullOrBlank()) bundle.append(" — ").append(snippet)
                bundle.append('\n')
            }
        }

        // Analyst layer
        val pt = runCatching { finnhub.priceTarget(ticker.symbol) }.getOrNull()
        val recs = runCatching { finnhub.recommendationTrends(ticker.symbol) }.getOrNull().orEmpty()
        bundle.append("\n## Analyst price target\n")
        bundle.append(" - mean ${fmt(pt?.targetMean)} high ${fmt(pt?.targetHigh)} low ${fmt(pt?.targetLow)} (as of ${pt?.lastUpdated ?: "?"})\n")
        if (recs.isNotEmpty()) {
            bundle.append("\n## Recommendation trends (latest first)\n")
            val take = when (tier) { ReportTier.BRIEF -> 1; ReportTier.STANDARD -> 3; ReportTier.DEEP -> 6 }
            recs.take(take).forEach { r ->
                bundle.append(" - ${r.period}: strongBuy=${r.strongBuy} buy=${r.buy} hold=${r.hold} sell=${r.sell} strongSell=${r.strongSell}\n")
            }
        }

        // ───────── Prompt template ─────────
        val (usage, system, maxTokens) = when (tier) {
            ReportTier.BRIEF -> Triple(AiUsage.REPORT_BRIEF, BRIEF_PROMPT, 1500)
            ReportTier.STANDARD -> Triple(AiUsage.REPORT_STANDARD, STANDARD_PROMPT, 3500)
            ReportTier.DEEP -> Triple(AiUsage.REPORT_DEEP, DEEP_PROMPT, 6500)
        }

        Log.i(TAG, "generating ${tier.name} report for ${ticker.symbol}")
        val completion = router.complete(
            usage = usage,
            system = system,
            userMessage = bundle.toString(),
            maxTokens = maxTokens,
            temperature = 0.4,
        )
        val text = completion.text

        val title = "${ticker.symbol} · Q${event.fiscalQuarter} ${event.fiscalYear} · ${tier.name.lowercase().replaceFirstChar { it.uppercase() }}"
        val id = earningsDao.insertReport(
            EarningsReport(
                tickerSymbol = ticker.symbol,
                earningsEventId = event.id,
                tier = tier.name,
                title = title,
                contentMarkdown = text,
                model = completion.modelUsed.id,
                inputTokens = 0, // tracked in ApiUsage by the client
                outputTokens = 0,
                sourcesUsedJson = "[]",
                generatedAtMillis = System.currentTimeMillis(),
            )
        )
        return id
    }

    private fun fmt(d: Double?): String = if (d == null) "—" else "%.4f".format(d).trimEnd('0').trimEnd('.')

    private companion object {
        const val TAG = "ReportGenerator"

        const val BRIEF_PROMPT = """
You are a senior equity analyst writing a TWO-PAGE executive brief for a holder of the named ticker.

Output a Markdown document with exactly these sections:
1. Headline (one-sentence summary of the print and its directional read)
2. Numbers (table: revenue, EPS, gross margin, guidance — with consensus deltas)
3. What matters (3 bullets, each one sentence)
4. Risks & next catalyst (one paragraph)

Length budget: 350-550 words total. Plain prose, no fluff, no preamble."""

        const val STANDARD_PROMPT = """
You are a senior equity analyst writing a FIVE-PAGE earnings report for a holder of the named ticker.

Output a Markdown document with these sections:
1. Executive summary (3-4 sentences)
2. Numbers vs consensus (markdown table)
3. Guidance commentary (one paragraph, explicit about beats/misses vs prior guide)
4. Segment / product detail (paragraphs as appropriate)
5. Analyst reaction (synthesize ratings + PT movement)
6. Risks (3-5 bullets)
7. Next catalyst (one paragraph)

Length budget: 1000-1500 words. Prose paragraphs, table for numbers."""

        const val DEEP_PROMPT = """
You are a senior equity analyst writing a TEN-PAGE deep-dive earnings report for a holder of
the named ticker. The reader is sophisticated; they already know the company.

Output a Markdown document with these sections:
1. Executive summary (4-5 sentences with explicit bull/bear framing)
2. Numbers vs consensus (markdown table)
3. Guidance and management tone (paragraphs)
4. Segment / product detail with quantitative depth where data permits
5. Analyst reaction (synthesize ratings + PT history — note magnitude and dispersion)
6. Bull case (3-5 bullets, each one full sentence, with the linchpin assumption named)
7. Bear case (same shape)
8. Risk factors (5-7 bullets with severity inline)
9. Comparables / read-throughs to peers (if applicable)
10. What to watch next quarter (3 specific data points)

Length budget: 2500-4000 words. Be specific. Cite source rows from the input by source name."""
    }
}
