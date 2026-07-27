package io.itsikh.finnencer.data.providers

import io.itsikh.finnencer.data.api.RssService
import io.itsikh.finnencer.data.entity.NewsArticle
import io.itsikh.finnencer.data.entity.NewsProvider as ProviderEnum
import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Base for "firehose" news sources: publisher-wide RSS feeds that are NOT
 * keyed by ticker (CNBC, MarketWatch, …). Unlike the per-ticker feeds
 * (Nasdaq, Yahoo, Seeking Alpha) these return the publisher's entire market
 * stream, so we:
 *
 *  1. fetch each feed at most once per ~10 min ([FirehoseCache]) instead of
 *     re-pulling the same firehose for every watched ticker, and
 *  2. keyword-match each item against the ticker / company name
 *     ([CompanyMatcher]) before tagging it to that ticker.
 *
 * Loose matches are expected — the downstream Claude importance scorer and
 * the daily brief's score floor (≥8) discard anything that isn't genuinely
 * about the company, so a few false positives here are harmless.
 *
 * Feeds must be HTTPS — the app has no cleartext-traffic exception.
 */
abstract class FirehoseRssProvider(
    private val rss: RssService,
) : NewsProvider {

    /** One or more publisher-wide HTTPS RSS feed URLs. */
    protected abstract val feedUrls: List<String>

    /** Enum stored on the row so old rows still parse after reorders. */
    protected abstract val providerEnum: ProviderEnum

    override suspend fun fetchForTicker(
        tickerSymbol: String,
        companyName: String?,
        cik: String?,
        sinceMillis: Long,
    ): List<NewsArticle> {
        val matcher = CompanyMatcher.from(tickerSymbol, companyName)
        // Symbol-only matching on a market-wide firehose is too noisy to be
        // useful (1–2 letter tickers match everything), so skip the source
        // for tickers we can't pin to a company name.
        if (!matcher.usable) return emptyList()
        val cutoff = sinceMillis - SLACK_MS
        val now = System.currentTimeMillis()
        return feedUrls
            .flatMap { url -> FirehoseCache.get(url) { fetchFeed(it) } }
            .asSequence()
            .filter { it.publishedAtMillis >= cutoff }
            .filter { matcher.matches(it.title) || matcher.matches(it.summary) }
            .map { item ->
                NewsArticle(
                    id = ArticleIds.stableId(key, item.guid),
                    provider = providerEnum.name,
                    sourceArticleId = item.guid,
                    title = item.title,
                    snippet = item.summary,
                    url = item.link,
                    sourceName = item.source ?: displayName,
                    imageUrl = null,
                    publishedAtMillis = item.publishedAtMillis,
                    fetchedAtMillis = now,
                    clusterKey = ArticleIds.clusterKey(item.title),
                    primaryTickerSymbol = tickerSymbol,
                )
            }
            .distinctBy { it.id }
            .toList()
    }

    // Null (not emptyList) on failure so the cache can tell "feed is down"
    // apart from "feed is legitimately empty" and skip caching the former.
    private suspend fun fetchFeed(url: String): List<FeedParser.FeedItem>? =
        runCatching { FeedParser.parse(rss.fetch(url)) }
            .onFailure { AppLogger.w(key, "$displayName fetch failed for $url: ${it.message}") }
            .getOrNull()

    protected companion object {
        const val SLACK_MS = 60 * 60 * 1000L
    }
}

/**
 * Per-feed-URL cache shared across all firehose providers. A market-wide
 * feed is identical no matter which ticker we're matching against, so we
 * parse it once per [TTL_MS] window and reuse it across the whole sync
 * cycle (which iterates every watched ticker).
 */
internal object FirehoseCache {
    /** [items] == null marks a FAILED fetch (negative entry). */
    private data class Entry(val items: List<FeedParser.FeedItem>?, val atMillis: Long)

    private val cache = HashMap<String, Entry>()
    private val mutex = Mutex()
    private const val TTL_MS = 10 * 60 * 1000L

    /** Failures are cached for a SHORT window instead of not at all: with
     *  no negative cache, a dead feed (e.g. its CDN unreachable) cost a
     *  full connect-timeout per WATCHED TICKER per cycle, stretching the
     *  sync worker past WorkManager's cap and getting it cancelled (#85).
     *  2 minutes bounds that to one timeout per window while still
     *  recovering quickly once the feed is reachable again. */
    private const val FAILURE_TTL_MS = 2 * 60 * 1000L

    /** [loader] returns null on failure. */
    suspend fun get(
        url: String,
        loader: suspend (String) -> List<FeedParser.FeedItem>?,
    ): List<FeedParser.FeedItem> {
        val now = System.currentTimeMillis()
        mutex.withLock {
            cache[url]?.let { entry ->
                val ttl = if (entry.items == null) FAILURE_TTL_MS else TTL_MS
                if (now - entry.atMillis < ttl) return entry.items ?: emptyList()
            }
        }
        val fresh = loader(url)
        mutex.withLock { cache[url] = Entry(fresh, now) }
        return fresh ?: emptyList()
    }
}

/**
 * Decides whether a free-text headline/summary is about a given company.
 *
 * Matches on an explicit ticker mention (`(NVDA)`, `$NVDA`, `NASDAQ: NVDA`),
 * the normalized company name (legal suffixes and share classes stripped,
 * e.g. "COREWEAVE INC-CL A" → "coreweave"), or a short alias — the leading
 * tokens left once trade-name suffixes like "platforms" / "motor" / "com"
 * are dropped, so "Meta Platforms" matches a headline saying just "Meta".
 * Name and alias are matched on word boundaries: raw substring matching
 * would tag Intel on "artificial intelligence" and Apple on "pineapple".
 * Bare symbol substring matching is deliberately avoided — short tickers
 * would match unrelated words.
 */
internal class CompanyMatcher private constructor(
    private val explicit: List<String>,
    private val namePattern: Regex?,
    private val aliasPattern: Regex?,
) {
    /** False when we have nothing reliable to match on (no usable name). */
    val usable: Boolean get() = namePattern != null

    fun matches(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.lowercase()
        if (explicit.any { t.contains(it) }) return true
        if (namePattern == null) return false
        // Normalize punctuation to spaces so "Amazon.com" / "Meta,
        // Platforms" still line up with the space-separated name tokens.
        val normalized = t.replace(NON_ALNUM, " ")
        return namePattern.containsMatchIn(normalized) ||
            aliasPattern?.containsMatchIn(normalized) == true
    }

    companion object {
        private val NON_ALNUM = Regex("[^a-z0-9]+")

        private val SUFFIXES = Regex(
            "\\b(incorporated|inc|corporation|corp|company|co|ltd|limited|plc|" +
                "holdings|holding|group|sa|nv|ag|class [a-c]|cl [a-c]|the)\\b\\.?",
        )

        // Trade-name tokens headlines drop ("Meta Platforms" → "Meta").
        // Applied after SUFFIXES, so pure legal-form tokens are already gone.
        private val ALIAS_SUFFIX_TOKENS = setOf(
            "inc", "corp", "corporation", "co", "com", "ltd", "plc",
            "platforms", "motor", "motors", "holdings", "group", "technologies",
        )

        // Aliases too common as plain English to match alone ("General"
        // would tag every "general market" headline).
        private val ALIAS_STOPLIST = setOf(
            "general", "national", "united", "american", "first",
        )

        fun from(symbol: String, companyName: String?): CompanyMatcher {
            val s = symbol.lowercase()
            val explicit = listOf("($s)", "\$$s", "nasdaq: $s", "nyse: $s", "nasdaq:$s", "nyse:$s")
            val name = companyName
                ?.lowercase()
                ?.replace("-", " ")
                ?.replace(SUFFIXES, " ")
                ?.replace(Regex("[^a-z0-9 ]"), " ")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.length >= 4 }
            val alias = name
                ?.split(' ')
                ?.takeWhile { it !in ALIAS_SUFFIX_TOKENS }
                ?.joinToString(" ")
                ?.takeIf { it.isNotBlank() && it != name && it !in ALIAS_STOPLIST }
            return CompanyMatcher(
                explicit = explicit,
                namePattern = name?.let(::wordBounded),
                aliasPattern = alias?.let(::wordBounded),
            )
        }

        private fun wordBounded(phrase: String) = Regex("\\b${Regex.escape(phrase)}\\b")
    }
}

/** CNBC top-news + markets firehose. */
class CnbcFirehoseProvider @Inject constructor(
    rss: RssService,
) : FirehoseRssProvider(rss) {
    override val key = "rss_cnbc"
    override val displayName = "CNBC"
    override val providerEnum = ProviderEnum.RSS_CNBC
    override val feedUrls = listOf(
        "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=100003114", // Top News
        "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=20910258", // Markets
    )
}

/** MarketWatch top-stories + market-pulse firehose (Dow Jones-hosted). */
class MarketWatchFirehoseProvider @Inject constructor(
    rss: RssService,
) : FirehoseRssProvider(rss) {
    override val key = "rss_marketwatch"
    override val displayName = "MarketWatch"
    override val providerEnum = ProviderEnum.RSS_MARKETWATCH
    override val feedUrls = listOf(
        "https://feeds.content.dowjones.io/public/rss/mw_topstories",
        "https://feeds.content.dowjones.io/public/rss/mw_marketpulse",
    )
}

/** Investing.com stock-market news firehose. */
class InvestingComFirehoseProvider @Inject constructor(
    rss: RssService,
) : FirehoseRssProvider(rss) {
    override val key = "rss_investing_com"
    override val displayName = "Investing.com"
    override val providerEnum = ProviderEnum.RSS_INVESTING_COM
    override val feedUrls = listOf(
        "https://www.investing.com/rss/news_25.rss", // Stock Market News
    )
}

/** PR Newswire financial press-release wire. Primary-source company news. */
class PrNewswireFirehoseProvider @Inject constructor(
    rss: RssService,
) : FirehoseRssProvider(rss) {
    override val key = "rss_pr_newswire"
    override val displayName = "PR Newswire"
    override val providerEnum = ProviderEnum.RSS_PR_NEWSWIRE
    override val feedUrls = listOf(
        "https://www.prnewswire.com/rss/financial-services-latest-news/financial-services-latest-news-list.rss",
        "https://www.prnewswire.com/rss/news-releases-list.rss",
    )
}
