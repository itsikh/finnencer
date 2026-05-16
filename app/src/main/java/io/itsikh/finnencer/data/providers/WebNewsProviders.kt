package io.itsikh.finnencer.data.providers

import io.itsikh.finnencer.data.api.RssService
import io.itsikh.finnencer.data.entity.NewsArticle
import io.itsikh.finnencer.data.entity.NewsProvider as ProviderEnum
import io.itsikh.finnencer.logging.AppLogger
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Searches Google News RSS for the ticker symbol + the company name.
 * Free, no auth, no rate limit in practice. Coverage is essentially the
 * entire indexable web routed through Google's relevance ranking.
 */
class GoogleNewsRssProvider @Inject constructor(
    private val rss: RssService,
) : NewsProvider {
    override val key = "rss_google_news"
    override val displayName = "Google News"

    override suspend fun fetchForTicker(
        tickerSymbol: String,
        companyName: String?,
        cik: String?,
        sinceMillis: Long,
    ): List<NewsArticle> {
        val nameOrTicker = companyName?.takeIf { it.isNotBlank() } ?: tickerSymbol
        // Build a tight query: NYSE/NASDAQ stock context, exact-quote ticker,
        // exact-quote company name. The "stock OR shares OR earnings" filter
        // strips off-topic hits like product reviews when the company name
        // overlaps a brand.
        val query = """
            ("$tickerSymbol" OR "$nameOrTicker") (stock OR shares OR earnings OR analyst OR guidance OR revenue)
        """.trimIndent()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://news.google.com/rss/search?q=$encoded&hl=en-US&gl=US&ceid=US:en"
        val xml = runCatching { rss.fetch(url) }
            .onFailure { AppLogger.w(TAG, "Google News fetch failed for $tickerSymbol: ${it.message}") }
            .getOrElse { return emptyList() }
        return FeedParser.parse(xml)
            .filter { it.publishedAtMillis >= sinceMillis - SLACK_MS }
            .map { item ->
                val source = extractSource(item.title) ?: "Google News"
                NewsArticle(
                    id = ArticleIds.stableId(key, item.guid),
                    provider = ProviderEnum.RSS_GOOGLE_NEWS.name,
                    sourceArticleId = item.guid,
                    // Google News titles are formatted "Real headline - Source"; trim trailing source.
                    title = item.title.replace(Regex(" - [^-]+$"), "").trim(),
                    snippet = item.summary,
                    url = item.link,
                    sourceName = source,
                    imageUrl = null,
                    publishedAtMillis = item.publishedAtMillis,
                    fetchedAtMillis = System.currentTimeMillis(),
                    clusterKey = ArticleIds.clusterKey(item.title),
                    primaryTickerSymbol = tickerSymbol,
                )
            }
    }

    private fun extractSource(title: String): String? =
        Regex(" - ([^-]+)$").find(title)?.groupValues?.get(1)?.trim()

    private companion object {
        const val TAG = "GoogleNewsRss"
        const val SLACK_MS = 60 * 60 * 1000L
    }
}

/**
 * Yahoo Finance per-ticker RSS — public, free, lightweight. Mix of press
 * releases + Yahoo-aggregated wire stories. Cleaner ticker tagging than
 * generic web search because Yahoo serves it from the ticker page.
 */
class YahooFinanceRssProvider @Inject constructor(
    private val rss: RssService,
) : NewsProvider {
    override val key = "rss_yahoo_finance"
    override val displayName = "Yahoo Finance"

    override suspend fun fetchForTicker(
        tickerSymbol: String,
        companyName: String?,
        cik: String?,
        sinceMillis: Long,
    ): List<NewsArticle> {
        val url = "https://feeds.finance.yahoo.com/rss/2.0/headline?s=$tickerSymbol&region=US&lang=en-US"
        val xml = runCatching { rss.fetch(url) }
            .onFailure { AppLogger.w(TAG, "Yahoo fetch failed for $tickerSymbol: ${it.message}") }
            .getOrElse { return emptyList() }
        return FeedParser.parse(xml)
            .filter { it.publishedAtMillis >= sinceMillis - SLACK_MS }
            .map {
                NewsArticle(
                    id = ArticleIds.stableId(key, it.guid),
                    provider = ProviderEnum.RSS_YAHOO_FINANCE.name,
                    sourceArticleId = it.guid,
                    title = it.title,
                    snippet = it.summary,
                    url = it.link,
                    sourceName = it.source ?: "Yahoo Finance",
                    imageUrl = null,
                    publishedAtMillis = it.publishedAtMillis,
                    fetchedAtMillis = System.currentTimeMillis(),
                    clusterKey = ArticleIds.clusterKey(it.title),
                    primaryTickerSymbol = tickerSymbol,
                )
            }
    }

    private companion object {
        const val TAG = "YahooFinanceRss"
        const val SLACK_MS = 60 * 60 * 1000L
    }
}
