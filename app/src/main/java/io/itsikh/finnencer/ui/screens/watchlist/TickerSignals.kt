package io.itsikh.finnencer.ui.screens.watchlist

import io.itsikh.finnencer.data.entity.EarningsEvent
import io.itsikh.finnencer.data.repo.TickerQuote

/** Price within 2% of a 52-week extreme counts as "near" it. */
internal const val FIFTY_TWO_WEEK_NEAR_THRESHOLD = 0.02

/** Day volume at or above this multiple of average counts as a spike. */
internal const val VOLUME_SPIKE_THRESHOLD = 2.0

/** Earnings within this many days count as "soon". */
internal const val EARNINGS_SOON_DAYS = 14

/** Whole days until [event]'s scheduled time. Null if no event. */
internal fun daysUntilEarnings(event: EarningsEvent?): Int? {
    val ms = event?.scheduledAtMillis ?: return null
    val deltaMs = ms - System.currentTimeMillis()
    return (deltaMs / (24L * 60 * 60 * 1000)).toInt()
}

/**
 * Derived "what's notable" signals for one ticker. Shared by the
 * watchlist row's badge strip and the Why-Moving sheet's signal chips
 * so both always agree on what counts as near-52w / volume-spike /
 * earnings-soon.
 */
internal data class TickerSignals(
    val nearHigh: Boolean,
    val nearLow: Boolean,
    val volRatio: Double?,
    val volSpike: Boolean,
    val daysUntilEarnings: Int?,
    val earningsSoon: Boolean,
)

internal fun computeTickerSignals(
    quote: TickerQuote?,
    daysUntilEarnings: Int?,
): TickerSignals {
    val price = quote?.price
    val nearHigh = price != null && quote.fiftyTwoWeekHigh != null &&
        quote.fiftyTwoWeekHigh > 0.0 &&
        (quote.fiftyTwoWeekHigh - price) / quote.fiftyTwoWeekHigh <= FIFTY_TWO_WEEK_NEAR_THRESHOLD &&
        price <= quote.fiftyTwoWeekHigh * 1.005
    val nearLow = price != null && quote.fiftyTwoWeekLow != null &&
        quote.fiftyTwoWeekLow > 0.0 &&
        (price - quote.fiftyTwoWeekLow) / quote.fiftyTwoWeekLow <= FIFTY_TWO_WEEK_NEAR_THRESHOLD &&
        price >= quote.fiftyTwoWeekLow * 0.995
    val volRatio = quote?.volumeRatio
    val volSpike = volRatio != null && volRatio >= VOLUME_SPIKE_THRESHOLD
    val earningsSoon = daysUntilEarnings != null && daysUntilEarnings in 0..EARNINGS_SOON_DAYS
    return TickerSignals(
        nearHigh = nearHigh,
        nearLow = nearLow,
        volRatio = volRatio,
        volSpike = volSpike,
        daysUntilEarnings = daysUntilEarnings,
        earningsSoon = earningsSoon,
    )
}

