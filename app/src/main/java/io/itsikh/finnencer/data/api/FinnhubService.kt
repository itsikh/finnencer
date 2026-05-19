package io.itsikh.finnencer.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

interface FinnhubService {

    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("exchange") exchange: String = "US",
    ): FinnhubSearchResponse

    @GET("stock/profile2")
    suspend fun profile(@Query("symbol") symbol: String): FinnhubProfile?

    @GET("quote")
    suspend fun quote(@Query("symbol") symbol: String): FinnhubQuote

    @GET("company-news")
    suspend fun companyNews(
        @Query("symbol") symbol: String,
        @Query("from") fromIso: String,
        @Query("to") toIso: String,
    ): List<FinnhubNewsItem>

    @GET("calendar/earnings")
    suspend fun earningsCalendar(
        @Query("from") fromIso: String,
        @Query("to") toIso: String,
        @Query("symbol") symbol: String? = null,
    ): FinnhubEarningsCalendarResponse

    @GET("stock/recommendation")
    suspend fun recommendationTrends(@Query("symbol") symbol: String): List<FinnhubRecommendation>

    @GET("stock/price-target")
    suspend fun priceTarget(@Query("symbol") symbol: String): FinnhubPriceTarget

    @GET("stock/metric")
    suspend fun basicFinancials(
        @Query("symbol") symbol: String,
        @Query("metric") metric: String = "all",
    ): FinnhubMetricResponse
}

data class FinnhubSearchResponse(
    val count: Int = 0,
    val result: List<FinnhubSearchMatch> = emptyList(),
)

data class FinnhubSearchMatch(
    val symbol: String?,
    val description: String?,
    val displaySymbol: String?,
    val type: String?,
)

data class FinnhubProfile(
    val ticker: String?,
    val name: String?,
    val exchange: String?,
    val finnhubIndustry: String?,
    val logo: String?,
    val weburl: String?,
    val country: String?,
    val currency: String?,
    val marketCapitalization: Double?,
    val shareOutstanding: Double?,
    val cik: String? = null,
)

data class FinnhubQuote(
    val c: Double?, // current price
    val d: Double?, // change
    val dp: Double?, // percent change
    val h: Double?, // day high
    val l: Double?, // day low
    val o: Double?, // open
    val pc: Double?, // previous close
    val t: Long?, // timestamp (epoch seconds)
)

data class FinnhubNewsItem(
    val id: Long?,
    val category: String?,
    val datetime: Long?, // epoch seconds
    val headline: String?,
    val image: String?,
    val related: String?, // comma-separated tickers
    val source: String?,
    val summary: String?,
    val url: String?,
)

data class FinnhubEarningsCalendarResponse(
    @SerializedName("earningsCalendar")
    val earningsCalendar: List<FinnhubEarningsEvent> = emptyList(),
)

data class FinnhubEarningsEvent(
    val symbol: String?,
    val date: String?, // YYYY-MM-DD
    val epsEstimate: Double?,
    val revenueEstimate: Double?,
    val epsActual: Double?,
    val revenueActual: Double?,
    val hour: String?, // bmo / amc
    val quarter: Int?,
    val year: Int?,
)

data class FinnhubRecommendation(
    val symbol: String?,
    val period: String?, // YYYY-MM-DD
    val buy: Int?,
    val hold: Int?,
    val sell: Int?,
    val strongBuy: Int?,
    val strongSell: Int?,
)

data class FinnhubPriceTarget(
    val symbol: String?,
    val targetHigh: Double?,
    val targetLow: Double?,
    val targetMean: Double?,
    val targetMedian: Double?,
    val lastUpdated: String?,
)

data class FinnhubMetricResponse(
    val symbol: String?,
    val metric: FinnhubMetrics?,
)

/**
 * Subset of Finnhub's /stock/metric?metric=all payload — only the fields
 * actually rendered on the snapshot screen. Finnhub's field naming
 * intermixes camelCase with leading-digit identifiers (e.g. `52WeekHigh`)
 * which Kotlin can't represent directly; those get @SerializedName.
 */
data class FinnhubMetrics(
    @SerializedName("52WeekHigh") val fiftyTwoWeekHigh: Double?,
    @SerializedName("52WeekLow") val fiftyTwoWeekLow: Double?,
    @SerializedName("52WeekHighDate") val fiftyTwoWeekHighDate: String?,
    @SerializedName("52WeekLowDate") val fiftyTwoWeekLowDate: String?,
    val marketCapitalization: Double?,
    val peNormalizedAnnual: Double?,
    val peTTM: Double?,
    val epsNormalizedAnnual: Double?,
    val epsTTM: Double?,
    val beta: Double?,
    val currentDividendYieldTTM: Double?,
    val dividendPerShareTTM: Double?,
    @SerializedName("10DayAverageTradingVolume") val avgVol10d: Double?,
    @SerializedName("3MonthAverageTradingVolume") val avgVol3m: Double?,
    val revenueGrowthTTMYoy: Double?,
    val psTTM: Double?,
)
