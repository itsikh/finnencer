package io.itsikh.finnencer.data.ai

import io.itsikh.finnencer.logging.AppLogger as Log
import com.google.gson.Gson
import io.itsikh.finnencer.data.api.FinnhubRecommendation
import io.itsikh.finnencer.data.api.FinnhubService
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.dao.TickerAnalystSnapshotDao
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.entity.EarningsEvent
import io.itsikh.finnencer.data.entity.EarningsReport
import io.itsikh.finnencer.data.entity.ReportTier
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.data.entity.TickerAnalystSnapshot
import io.itsikh.finnencer.data.entity.fiscalLabelOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates a BRIEF / STANDARD / DEEP earnings report for a given
 * EarningsEvent. Each tier assembles its own source bundle AND uses its own
 * prompt template — per the design doc, "be brief" alone gives shallow
 * coverage of everything; we want deep coverage of what matters.
 *
 * The numeric backbone of every tier is [EarningsFactsSheet]: SEC XBRL
 * actuals with margins, YoY/QoQ deltas, FCF and consensus surprise all
 * computed in Kotlin, plus the 8-K Exhibit 99.1 press release (the only
 * place guidance and segment revenue actually live) and the post-print
 * price reaction. Tiers differ in how much news and analyst history they
 * add on top, and in how much of the analysis they ask for — not in
 * whether they get real numbers.
 */
@Singleton
class ReportGenerator @Inject constructor(
    private val router: AiRouter,
    private val finnhub: FinnhubService,
    private val tickerDao: TickerDao,
    private val newsDao: NewsDao,
    private val earningsDao: EarningsDao,
    private val analystSnapshotDao: TickerAnalystSnapshotDao,
    private val promptPrefs: PromptPreferences,
    private val factsBuilder: EarningsFactsBuilder,
    private val gson: Gson,
) {

    /**
     * Everything gathered for an event before a tier is chosen. Exists so
     * Auto mode can inspect the facts sheet to PICK the tier, then generate
     * at that tier without re-fetching EDGAR, Yahoo and Finnhub.
     */
    data class ReportContext(
        val event: EarningsEvent,
        val ticker: Ticker,
        val facts: EarningsFactsSheet,
        val analystSnapshot: TickerAnalystSnapshot?,
        val recommendations: List<FinnhubRecommendation>,
    )

    /**
     * Gather sources for [eventId] without generating anything. The
     * expensive network work (companyfacts, submissions + exhibit,
     * daily chart, basic financials) happens here and is internally
     * cached, so a subsequent [generate] with the returned context is
     * LLM-cost only.
     */
    suspend fun prepare(eventId: Long): ReportContext {
        val event = earningsDao.getEvent(eventId)
            ?: error("EarningsEvent $eventId not found")
        val ticker = tickerDao.get(event.tickerSymbol)
            ?: error("Ticker ${event.tickerSymbol} no longer in watchlist")
        val snap = analystSnapshot(ticker.symbol)
        val recs = parseRecommendationTrends(snap?.recommendationTrendsJson)
        // News count feeds Auto sizing; DEEP's larger news slice is read
        // again at bundle-assembly time against the chosen tier.
        val newsCount = newsDao.recentForTicker(ticker.symbol, NEWS_LIMIT_DEEP).size
        val facts = factsBuilder.build(
            event = event,
            ticker = ticker,
            newsCount = newsCount,
            analystSnapshot = snap,
            recommendations = recs,
        )
        return ReportContext(
            event = event,
            ticker = ticker,
            facts = facts,
            analystSnapshot = snap,
            recommendations = recs,
        )
    }

    /** Convenience entry point for callers that don't need Auto sizing. */
    suspend fun generate(eventId: Long, tier: ReportTier): Long =
        generate(prepare(eventId), tier)

    suspend fun generate(context: ReportContext, tier: ReportTier): Long {
        val (event, ticker, facts) = context

        // ───────── Source bundle ─────────
        // The facts sheet leads: it's the verified numeric layer plus the
        // press-release body. News and analyst history follow as
        // interpretive context.
        val bundle = StringBuilder()
        bundle.append(facts.markdown)

        val newsLimit = when (tier) {
            ReportTier.BRIEF -> NEWS_LIMIT_BRIEF
            ReportTier.STANDARD -> NEWS_LIMIT_STANDARD
            ReportTier.DEEP -> NEWS_LIMIT_DEEP
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

        if (context.recommendations.isNotEmpty()) {
            bundle.append("\n## Recommendation trends (latest first)\n")
            val take = when (tier) { ReportTier.BRIEF -> 1; ReportTier.STANDARD -> 3; ReportTier.DEEP -> 6 }
            context.recommendations.take(take).forEach { r ->
                bundle.append(" - ${r.period}: strongBuy=${r.strongBuy} buy=${r.buy} hold=${r.hold} sell=${r.sell} strongSell=${r.strongSell}\n")
            }
        }

        // ───────── Prompt template ─────────
        // Caps sized for Sonnet 5 / Opus 5 (~30% more tokens per unit of
        // text than 4.6) AND for the richer bundle: with segment data,
        // guidance and a margin trend now in play there is materially
        // more to write about, and the old caps truncated mid-table.
        val (usage, baseSystem, maxTokens) = when (tier) {
            ReportTier.BRIEF -> Triple(AiUsage.REPORT_BRIEF, DefaultPrompts.forUsage(AiUsage.REPORT_BRIEF), 5000)
            ReportTier.STANDARD -> Triple(AiUsage.REPORT_STANDARD, DefaultPrompts.forUsage(AiUsage.REPORT_STANDARD), 12000)
            ReportTier.DEEP -> Triple(AiUsage.REPORT_DEEP, DefaultPrompts.forUsage(AiUsage.REPORT_DEEP), 24000)
        }
        val system = promptPrefs.applyExtras(
            base = baseSystem,
            extra = promptPrefs.get(usage),
        )

        Log.i(
            TAG,
            "generating ${tier.name} report for ${ticker.symbol} " +
                "(facts=${facts.signals.metricCount} metrics, bundle=${bundle.length} chars)",
        )
        val completion = router.complete(
            usage = usage,
            system = system,
            userMessage = bundle.toString(),
            maxTokens = maxTokens,
            temperature = 0.4,
            // PERSONA + per-tier prompt + user "extras" are large and
            // stable across every report at this tier. Cache the system
            // block so a same-day batch (e.g. earnings week) pays cache-
            // read rates on the shared prefix.
            cacheSystem = true,
        )
        // Surface truncation instead of persisting a silently cut-off
        // report (a DEEP report ending mid-table looks like a render bug).
        val text = if (completion.stopReason == "max_tokens") {
            Log.w(TAG, "${tier.name} report for ${ticker.symbol} hit the $maxTokens-token output cap; storing with truncation marker")
            completion.text + "\n\n> ⚠️ Report hit the output-token limit and may be truncated."
        } else completion.text

        val tierLabel = tier.name.lowercase().replaceFirstChar { it.uppercase() }
        val title = event.fiscalLabelOrNull()
            ?.let { "${ticker.symbol} · $it · $tierLabel" }
            ?: "${ticker.symbol} · $tierLabel"
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
                sourcesUsedJson = gson.toJson(sourceRefs(facts)),
                generatedAtMillis = System.currentTimeMillis(),
            )
        )
        return id
    }

    /** Traceable source identifiers for the report row — the SEC filings
     *  the numbers came from, so a reader can verify them. */
    private fun sourceRefs(facts: EarningsFactsSheet): List<String> = buildList {
        facts.pressRelease?.sourceUrl?.let { add(it) }
        facts.accession?.let { add("sec-accession:$it") }
    }

    /**
     * Return a (cache_age <= 24h) analyst snapshot, refreshing from
     * Finnhub if missing or stale. Falls back to the stale cached row
     * if the refresh fails (a stale price target is still useful
     * grounding for an LLM report). Returns null only when there's
     * no cached row AND the API call fails — same shape as the
     * previous inline `runCatching` flow.
     */
    private suspend fun analystSnapshot(symbol: String): TickerAnalystSnapshot? {
        val now = System.currentTimeMillis()
        val cached = analystSnapshotDao.get(symbol)
        if (cached != null && (now - cached.fetchedAtMillis) < ANALYST_TTL_MS) {
            return cached
        }
        val pt = runCatching { finnhub.priceTarget(symbol) }
            .onFailure { Log.w(TAG, "priceTarget refresh failed for $symbol: ${it.message}") }
            .getOrNull()
        val recs = runCatching { finnhub.recommendationTrends(symbol) }
            .onFailure { Log.w(TAG, "recommendationTrends refresh failed for $symbol: ${it.message}") }
            .getOrNull().orEmpty()
        if (pt == null && recs.isEmpty()) {
            // Refresh failed end-to-end. Return whatever we had cached
            // (even stale) so the report still has something to ground
            // on; the next regenerate will try again.
            return cached
        }
        val snap = TickerAnalystSnapshot(
            ticker = symbol,
            fetchedAtMillis = now,
            targetHigh = pt?.targetHigh,
            targetLow = pt?.targetLow,
            targetMean = pt?.targetMean,
            targetMedian = pt?.targetMedian,
            lastUpdated = pt?.lastUpdated,
            recommendationTrendsJson = gson.toJson(recs),
        )
        analystSnapshotDao.upsert(snap)
        return snap
    }

    private fun parseRecommendationTrends(json: String?): List<FinnhubRecommendation> {
        if (json.isNullOrBlank() || json == "null") return emptyList()
        return runCatching {
            gson.fromJson(
                json,
                Array<FinnhubRecommendation>::class.java,
            )?.toList().orEmpty()
        }.getOrElse {
            Log.w(TAG, "recommendation_trends_json parse failed: ${it.message}")
            emptyList()
        }
    }

    private companion object {
        const val TAG = "ReportGenerator"
        /** How long the analyst snapshot is considered fresh. Daily
         *  refresh is plenty — Finnhub's price-target field updates at
         *  most a few times per week per ticker. */
        const val ANALYST_TTL_MS = 24L * 60 * 60 * 1000

        const val NEWS_LIMIT_BRIEF = 5
        const val NEWS_LIMIT_STANDARD = 12
        const val NEWS_LIMIT_DEEP = 25
    }
}
