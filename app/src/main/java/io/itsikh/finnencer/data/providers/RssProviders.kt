package io.itsikh.finnencer.data.providers

import io.itsikh.finnencer.data.api.RssService
import io.itsikh.finnencer.data.entity.NewsArticle
import io.itsikh.finnencer.data.entity.NewsProvider as ProviderEnum
import javax.inject.Inject

class NasdaqRssProvider @Inject constructor(
    private val rss: RssService,
) : NewsProvider {
    override val key = "rss_nasdaq"
    override val displayName = "Nasdaq"

    override suspend fun fetchForTicker(
        tickerSymbol: String,
        companyName: String?,
        cik: String?,
        sinceMillis: Long,
    ): List<NewsArticle> {
        val url = "https://www.nasdaq.com/feed/rssoutbound?symbol=$tickerSymbol"
        val xml = rss.fetch(url)
        return FeedParser.parse(xml)
            .filter { it.publishedAtMillis >= sinceMillis - SLACK_MS }
            .map { it.toNewsArticle(tickerSymbol, ProviderEnum.RSS_NASDAQ.name, key, "Nasdaq") }
    }

    private companion object {
        const val SLACK_MS = 60 * 60 * 1000L
    }
}

class SeekingAlphaRssProvider @Inject constructor(
    private val rss: RssService,
) : NewsProvider {
    override val key = "rss_seekingalpha"
    override val displayName = "Seeking Alpha"

    override suspend fun fetchForTicker(
        tickerSymbol: String,
        companyName: String?,
        cik: String?,
        sinceMillis: Long,
    ): List<NewsArticle> {
        val url = "https://seekingalpha.com/api/sa/combined/$tickerSymbol.xml"
        val xml = rss.fetch(url)
        return FeedParser.parse(xml)
            .filter { it.publishedAtMillis >= sinceMillis - SLACK_MS }
            .map { it.toNewsArticle(tickerSymbol, ProviderEnum.RSS_SEEKING_ALPHA.name, key, "Seeking Alpha") }
    }

    private companion object {
        const val SLACK_MS = 60 * 60 * 1000L
    }
}

private fun FeedParser.FeedItem.toNewsArticle(
    tickerSymbol: String,
    providerEnum: String,
    providerKey: String,
    sourceLabel: String,
): NewsArticle {
    val nowMillis = System.currentTimeMillis()
    return NewsArticle(
        id = ArticleIds.stableId(providerKey, guid),
        provider = providerEnum,
        sourceArticleId = guid,
        title = title,
        snippet = summary,
        url = link,
        sourceName = source ?: sourceLabel,
        imageUrl = null,
        publishedAtMillis = publishedAtMillis,
        fetchedAtMillis = nowMillis,
        clusterKey = ArticleIds.clusterKey(title),
        primaryTickerSymbol = tickerSymbol,
    )
}
