package io.itsikh.finnencer.data.repo

import io.itsikh.finnencer.data.api.FinnhubMetrics
import io.itsikh.finnencer.data.api.FinnhubService
import io.itsikh.finnencer.data.dao.TickerMetricsDao
import io.itsikh.finnencer.data.entity.TickerMetrics
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fundamentals snapshot per ticker (52w range, market cap, P/E, EPS, beta,
 * dividend yield, etc.). Fetched from Finnhub's free `/stock/metric`
 * endpoint and cached 24h — the underlying numbers only move on company
 * filings or once a day from Finnhub's recompute, so a tighter cache
 * would just burn the user's free-tier quota.
 */
@Singleton
class TickerMetricsRepo @Inject constructor(
    private val service: FinnhubService,
    private val dao: TickerMetricsDao,
) {

    fun observe(ticker: String): Flow<TickerMetrics?> = dao.observe(ticker.uppercase())

    suspend fun load(ticker: String, force: Boolean = false): TickerMetrics {
        val symbol = ticker.uppercase()
        val cached = dao.get(symbol)
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAtMillis < CACHE_TTL_MS) {
            return cached
        }
        val response = service.basicFinancials(symbol)
        val row = (response.metric ?: FinnhubMetrics(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        )).toEntity(symbol)
        dao.upsert(row)
        return row
    }

    private fun FinnhubMetrics.toEntity(symbol: String): TickerMetrics = TickerMetrics(
        ticker = symbol,
        fetchedAtMillis = System.currentTimeMillis(),
        fiftyTwoWeekHigh = fiftyTwoWeekHigh,
        fiftyTwoWeekLow = fiftyTwoWeekLow,
        fiftyTwoWeekHighDate = fiftyTwoWeekHighDate,
        fiftyTwoWeekLowDate = fiftyTwoWeekLowDate,
        marketCap = marketCapitalization,
        peTtm = peTTM,
        peNormalized = peNormalizedAnnual,
        epsTtm = epsTTM,
        epsNormalized = epsNormalizedAnnual,
        beta = beta,
        divYield = currentDividendYieldTTM,
        divPerShare = dividendPerShareTTM,
        avgVol10d = avgVol10d,
        avgVol3m = avgVol3m,
        revGrowthYoy = revenueGrowthTTMYoy,
        priceToSales = psTTM,
    )

    private companion object {
        const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    }
}
