package io.itsikh.finnencer.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ticker_metrics")
data class TickerMetrics(
    @PrimaryKey val ticker: String,
    @ColumnInfo(name = "fetched_at_millis") val fetchedAtMillis: Long,
    @ColumnInfo(name = "fifty_two_week_high") val fiftyTwoWeekHigh: Double?,
    @ColumnInfo(name = "fifty_two_week_low") val fiftyTwoWeekLow: Double?,
    @ColumnInfo(name = "fifty_two_week_high_date") val fiftyTwoWeekHighDate: String?,
    @ColumnInfo(name = "fifty_two_week_low_date") val fiftyTwoWeekLowDate: String?,
    @ColumnInfo(name = "market_cap") val marketCap: Double?,
    @ColumnInfo(name = "pe_ttm") val peTtm: Double?,
    @ColumnInfo(name = "pe_normalized") val peNormalized: Double?,
    @ColumnInfo(name = "eps_ttm") val epsTtm: Double?,
    @ColumnInfo(name = "eps_normalized") val epsNormalized: Double?,
    val beta: Double?,
    @ColumnInfo(name = "div_yield") val divYield: Double?,
    @ColumnInfo(name = "div_per_share") val divPerShare: Double?,
    @ColumnInfo(name = "avg_vol_10d") val avgVol10d: Double?,
    @ColumnInfo(name = "avg_vol_3m") val avgVol3m: Double?,
    @ColumnInfo(name = "rev_growth_yoy") val revGrowthYoy: Double?,
    @ColumnInfo(name = "price_to_sales") val priceToSales: Double?,
)
