package io.itsikh.finnencer.data.providers

import io.itsikh.finnencer.data.entity.NewsArticle

/**
 * A source that knows how to fetch fresh news for a single ticker. Each
 * provider is responsible for:
 *  - calling its remote endpoint
 *  - mapping the response to [NewsArticle] (with stable id + cluster key)
 *  - returning a list (possibly empty); errors are logged + swallowed by the
 *    caller so one flaky source doesn't break a sync cycle
 */
interface NewsProvider {
    val key: String        // short stable identifier (e.g. "finnhub")
    val displayName: String

    /**
     * @param tickerSymbol the user-facing symbol (e.g. "NVDA")
     * @param companyName best-effort company name (for keyword filtering in
     *        non-tagged sources). Pass null if unknown.
     * @param cik EDGAR Central Index Key (zero-padded 10-digit string) when
     *        known. Only used by [EdgarFilingsProvider].
     * @param sinceMillis ignore articles older than this. Best-effort hint
     *        only — providers may return older items if their API doesn't
     *        support filtering.
     */
    suspend fun fetchForTicker(
        tickerSymbol: String,
        companyName: String?,
        cik: String?,
        sinceMillis: Long,
    ): List<NewsArticle>
}
