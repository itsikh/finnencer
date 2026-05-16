package io.itsikh.finnencer.data.providers

import io.itsikh.finnencer.data.api.FinnhubService
import io.itsikh.finnencer.data.entity.NewsArticle
import io.itsikh.finnencer.data.entity.NewsProvider as ProviderEnum
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

class FinnhubNewsProvider @Inject constructor(
    private val service: FinnhubService,
) : NewsProvider {

    override val key = "finnhub"
    override val displayName = "Finnhub"

    override suspend fun fetchForTicker(
        tickerSymbol: String,
        companyName: String?,
        cik: String?,
        sinceMillis: Long,
    ): List<NewsArticle> {
        val toDate = LocalDate.now(ZoneOffset.UTC)
        // Finnhub /company-news takes inclusive YYYY-MM-DD range. Always
        // request at least 2 days back to absorb published-vs-fetched skew
        // and weekend dead zones.
        val sinceDate = LocalDate.ofEpochDay(sinceMillis / 86_400_000L)
            .minusDays(1)
            .coerceAtLeast(toDate.minusDays(14))
        val resp = service.companyNews(
            symbol = tickerSymbol,
            fromIso = sinceDate.toString(),
            toIso = toDate.toString(),
        )
        val nowMillis = System.currentTimeMillis()
        return resp.mapNotNull { item ->
            val sourceId = item.id?.toString() ?: item.url ?: return@mapNotNull null
            val title = item.headline?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val publishedMillis = (item.datetime ?: 0L) * 1000L
            if (publishedMillis < sinceMillis - SLACK_MS) return@mapNotNull null
            NewsArticle(
                id = ArticleIds.stableId(key, sourceId),
                provider = ProviderEnum.FINNHUB.name,
                sourceArticleId = sourceId,
                title = title,
                snippet = item.summary?.trim()?.takeIf { it.isNotBlank() && it != title },
                url = item.url ?: return@mapNotNull null,
                sourceName = item.source ?: "Finnhub",
                imageUrl = item.image?.takeIf { it.isNotBlank() },
                publishedAtMillis = if (publishedMillis > 0) publishedMillis else nowMillis,
                fetchedAtMillis = nowMillis,
                clusterKey = ArticleIds.clusterKey(title),
                primaryTickerSymbol = tickerSymbol,
            )
        }
    }

    private companion object {
        const val SLACK_MS = 60 * 60 * 1000L
    }
}
