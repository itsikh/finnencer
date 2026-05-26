package io.itsikh.finnencer.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Yahoo Finance public chart endpoint — no auth, no key. Used by the
 * watchlist to show a live price + percent change on each ticker row.
 *
 * Endpoint: `https://query1.finance.yahoo.com/v8/finance/chart/NVDA?interval=1d&range=1d`
 *
 * We use the v8 *chart* endpoint instead of the older v7 *quote*
 * endpoint because Yahoo locked v7 down to authenticated sessions
 * (requires a crumb + cookies). The chart endpoint remains
 * unauthenticated. Trade-off: chart is single-symbol, so the poller
 * fans out one request per ticker in parallel. Response carries
 * `meta.regularMarketPrice` and `meta.chartPreviousClose`, from which
 * we derive the percent change.
 *
 * Yahoo blocks requests with an empty User-Agent header, so the OkHttp
 * client provides a browser-y one. If `query1.*` 401s for any reason
 * the poller retries against `query2.finance.yahoo.com` via [chartAt].
 */
interface YahooQuoteService {

    /** Fetch one symbol's chart for today.
     *
     *  We use `interval=15m&range=1d&includePrePost=true` so the response
     *  carries the intraday candle series including pre-market and
     *  post-market buckets. `meta.regularMarketPrice` freezes at 4pm ET,
     *  so to surface after-hours moves on the watchlist we walk the
     *  candle array for trades that fall outside the regular trading
     *  window (see `QuotePoller.extractExtendedHours`). 15-minute
     *  granularity keeps the payload ~10-15KB per symbol — small enough
     *  to fan out across a typical watchlist on a 60s poll cadence. */
    @GET("v8/finance/chart/{symbol}")
    suspend fun chart(
        @Path("symbol") symbol: String,
        @Query("interval") interval: String = "15m",
        @Query("range") range: String = "1d",
        @Query("includePrePost") includePrePost: Boolean = true,
    ): YahooChartResponse

    /** Same as [chart] but lets the poller direct the request at the
     *  `query2` host as a fallback. */
    @GET
    suspend fun chartAt(@Url url: String): YahooChartResponse
}

data class YahooChartResponse(
    @SerializedName("chart") val chart: YahooChartEnvelope,
)

data class YahooChartEnvelope(
    @SerializedName("result") val result: List<YahooChartResult>? = null,
    @SerializedName("error") val error: Any? = null,
)

data class YahooChartResult(
    @SerializedName("meta") val meta: YahooChartMeta,
    /** Epoch-second timestamp for each candle. Same length as
     *  [YahooQuoteSeries.close] when both are present. */
    @SerializedName("timestamp") val timestamp: List<Long>? = null,
    @SerializedName("indicators") val indicators: YahooChartIndicators? = null,
)

/**
 * Subset of the Yahoo chart meta payload that we actually consume.
 *
 * Includes the canonical price + previous-close fields plus optional
 * extended-hours fields. The pre/post-market fields are only sometimes
 * populated on the v8 chart endpoint — when they're missing the poller
 * falls back to walking [YahooChartResult.timestamp] +
 * [YahooQuoteSeries.close] to find the latest extended-session trade.
 */
data class YahooChartMeta(
    @SerializedName("symbol") val symbol: String,
    @SerializedName("regularMarketPrice") val regularMarketPrice: Double? = null,
    @SerializedName("chartPreviousClose") val chartPreviousClose: Double? = null,
    @SerializedName("previousClose") val previousClose: Double? = null,
    @SerializedName("currency") val currency: String? = null,
    @SerializedName("regularMarketTime") val regularMarketTime: Long? = null,
    @SerializedName("preMarketPrice") val preMarketPrice: Double? = null,
    @SerializedName("preMarketTime") val preMarketTime: Long? = null,
    @SerializedName("preMarketChangePercent") val preMarketChangePercent: Double? = null,
    @SerializedName("postMarketPrice") val postMarketPrice: Double? = null,
    @SerializedName("postMarketTime") val postMarketTime: Long? = null,
    @SerializedName("postMarketChangePercent") val postMarketChangePercent: Double? = null,
    @SerializedName("currentTradingPeriod") val currentTradingPeriod: YahooTradingPeriods? = null,
)

/** Today's pre / regular / post session boundaries, as Yahoo reports them. */
data class YahooTradingPeriods(
    @SerializedName("pre") val pre: YahooTradingPeriod? = null,
    @SerializedName("regular") val regular: YahooTradingPeriod? = null,
    @SerializedName("post") val post: YahooTradingPeriod? = null,
)

data class YahooTradingPeriod(
    @SerializedName("start") val start: Long,
    @SerializedName("end") val end: Long,
    @SerializedName("timezone") val timezone: String? = null,
    @SerializedName("gmtoffset") val gmtOffset: Long? = null,
)

data class YahooChartIndicators(
    @SerializedName("quote") val quote: List<YahooQuoteSeries>? = null,
)

/** One per indicators.quote entry. We only need the close array. */
data class YahooQuoteSeries(
    @SerializedName("close") val close: List<Double?>? = null,
)
