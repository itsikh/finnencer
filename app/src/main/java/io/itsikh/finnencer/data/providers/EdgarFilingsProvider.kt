package io.itsikh.finnencer.data.providers

import io.itsikh.finnencer.data.api.SecEdgarService
import io.itsikh.finnencer.data.entity.NewsArticle
import io.itsikh.finnencer.data.entity.NewsProvider as ProviderEnum
import javax.inject.Inject

/**
 * Surfaces new SEC filings (any form) for a tracked company as NewsArticle
 * rows so they flow through the same dedup + scoring + notification pipeline
 * as press releases. The title typically includes the form type — e.g.
 * "8-K — Item 5.02 Departure of Directors or Certain Officers" — which is
 * what the scorer keys off.
 */
class EdgarFilingsProvider @Inject constructor(
    private val service: SecEdgarService,
    private val cikLookup: EdgarCikLookup,
) : NewsProvider {

    override val key = "sec_edgar"
    override val displayName = "SEC EDGAR"

    override suspend fun fetchForTicker(
        tickerSymbol: String,
        companyName: String?,
        cik: String?,
        sinceMillis: Long,
    ): List<NewsArticle> {
        val resolvedCik = cik ?: cikLookup.resolve(tickerSymbol) ?: return emptyList()
        // Atom feed of recent filings for this CIK. count=40 is plenty for a
        // 15-min poll cadence.
        val url = "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany" +
                "&CIK=$resolvedCik&type=&dateb=&owner=include&count=40&output=atom"
        val xml = service.companyAtom(url)
        return FeedParser.parse(xml)
            .filter { it.publishedAtMillis >= sinceMillis - SLACK_MS }
            .map { item ->
                val nowMillis = System.currentTimeMillis()
                NewsArticle(
                    id = ArticleIds.stableId(key, item.guid),
                    provider = ProviderEnum.SEC_EDGAR.name,
                    sourceArticleId = item.guid,
                    title = item.title,
                    snippet = item.summary,
                    url = item.link,
                    sourceName = "SEC EDGAR",
                    imageUrl = null,
                    publishedAtMillis = item.publishedAtMillis,
                    fetchedAtMillis = nowMillis,
                    clusterKey = ArticleIds.clusterKey(item.title),
                    primaryTickerSymbol = tickerSymbol,
                )
            }
    }

    private companion object {
        const val SLACK_MS = 6 * 60 * 60 * 1000L // filings can lag 1-2h
    }
}
