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

    /** Fetch one symbol's chart meta. We don't consume the candle
     *  series, only the `meta` block, but `range=1d&interval=1d`
     *  keeps the payload tiny. `includePrePost=true` ensures
     *  `regularMarketPrice` reflects the latest extended-hours trade
     *  when one is available. The percent-change calc uses
     *  `meta.previousClose` (canonical "prior day's close"); range=1d
     *  also makes `chartPreviousClose` equal to yesterday's close so
     *  the fallback baseline is correct. */
    @GET("v8/finance/chart/{symbol}")
    suspend fun chart(
        @Path("symbol") symbol: String,
        @Query("interval") interval: String = "1d",
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
)

/**
 * Subset of the Yahoo chart meta payload that we actually consume.
 * Yahoo returns several dozen fields; we deserialize only the price +
 * previous-close pair the watchlist needs.
 */
data class YahooChartMeta(
    @SerializedName("symbol") val symbol: String,
    @SerializedName("regularMarketPrice") val regularMarketPrice: Double? = null,
    @SerializedName("chartPreviousClose") val chartPreviousClose: Double? = null,
    @SerializedName("previousClose") val previousClose: Double? = null,
    @SerializedName("currency") val currency: String? = null,
)
