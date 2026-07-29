package io.itsikh.finnencer.data.providers

import io.itsikh.finnencer.data.api.YahooQuoteService
import io.itsikh.finnencer.logging.AppLogger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How the market actually reacted to an earnings print.
 *
 * We report BOTH the session-of move and the next-session move, and
 * deliberately don't pick one: a company that reports after the close
 * moves the *next* session, one that reports before the open moves the
 * *same* session, and the earnings calendar doesn't reliably tell us
 * which it was. Handing the LLM both — labelled — beats guessing and
 * getting it backwards.
 *
 * All fields nullable so a partial series still yields a usable object;
 * [hasAnyMove] tells the caller whether it's worth rendering at all.
 */
data class PostEarningsReaction(
    val referenceDate: LocalDate,
    /** Close of the last session at or before the report date. */
    val priorClose: Double?,
    /** Close of the session containing the report date. */
    val sessionOfClose: Double?,
    /** Close of the first session after the report date. */
    val nextSessionClose: Double?,
    /** % move of the session containing the report date. */
    val sessionOfPct: Double?,
    /** % move of the first session after the report date. */
    val nextSessionPct: Double?,
    /** Volume of whichever session moved most, for a "volume spike" read. */
    val reactionVolume: Long?,
    /** Most recent close in the fetched window — "where it trades now". */
    val latestClose: Double?,
    /** % from the reaction session's close to [latestClose] — did the
     *  move hold, fade, or extend? */
    val driftSincePct: Double?,
) {
    val hasAnyMove: Boolean get() = sessionOfPct != null || nextSessionPct != null

    /** The larger-magnitude of the two candidate reaction moves — the one
     *  most likely to BE the earnings reaction. */
    val headlineMovePct: Double?
        get() = listOfNotNull(sessionOfPct, nextSessionPct)
            .maxByOrNull { kotlin.math.abs(it) }
}

/**
 * Derives [PostEarningsReaction] from the same free, unauthenticated
 * Yahoo chart endpoint the watchlist poller uses — just at daily
 * granularity over a window that straddles the report date.
 *
 * Every failure path returns null. A missing price reaction must never
 * fail an earnings report.
 */
@Singleton
class PostEarningsReactionProvider @Inject constructor(
    private val yahoo: YahooQuoteService,
) {

    /**
     * @param symbol ticker as Yahoo knows it
     * @param reportDate the date the results were released
     */
    suspend fun reactionFor(symbol: String, reportDate: LocalDate): PostEarningsReaction? {
        // 6 months of daily candles gives us the print plus enough
        // trailing sessions to measure drift, in one ~15KB response.
        val result = runCatching {
            yahoo.chart(
                symbol = symbol,
                interval = "1d",
                range = "6mo",
                includePrePost = false,
            ).chart.result?.firstOrNull()
        }.onFailure {
            AppLogger.w(TAG, "daily chart fetch failed for $symbol: ${it.message}")
        }.getOrNull() ?: return null

        val timestamps = result.timestamp
        val series = result.indicators?.quote?.firstOrNull()
        val closes = series?.close
        if (timestamps.isNullOrEmpty() || closes.isNullOrEmpty()) {
            AppLogger.w(TAG, "daily chart for $symbol had no usable series")
            return null
        }

        val zone = ZoneId.systemDefault()
        // Pair each candle with its session date, dropping buckets Yahoo
        // nulled out (halts, or the not-yet-closed current session).
        val sessions = timestamps.indices.mapNotNull { i ->
            val close = closes.getOrNull(i) ?: return@mapNotNull null
            val date = Instant.ofEpochSecond(timestamps[i]).atZone(zone).toLocalDate()
            Session(date, close, series.volume?.getOrNull(i))
        }
        if (sessions.size < 2) return null

        // Index of the session containing (or first after) the report
        // date. Reports land on non-trading days often enough — a Friday
        // evening release reads as Monday — that we search forward.
        val ofIdx = sessions.indexOfFirst { !it.date.isBefore(reportDate) }
        if (ofIdx < 0) {
            // Report date is after every candle we have: nothing to
            // measure yet.
            return null
        }

        val priorIdx = ofIdx - 1
        val nextIdx = ofIdx + 1
        val prior = sessions.getOrNull(priorIdx)
        val of = sessions.getOrNull(ofIdx)
        val next = sessions.getOrNull(nextIdx)

        val sessionOfPct = pctChange(prior?.close, of?.close)
        val nextSessionPct = pctChange(of?.close, next?.close)

        // Attribute volume to whichever session actually moved.
        val reactionSession = when {
            sessionOfPct == null && nextSessionPct == null -> null
            nextSessionPct == null -> of
            sessionOfPct == null -> next
            kotlin.math.abs(sessionOfPct) >= kotlin.math.abs(nextSessionPct) -> of
            else -> next
        }
        val latest = sessions.last()

        return PostEarningsReaction(
            referenceDate = of?.date ?: reportDate,
            priorClose = prior?.close,
            sessionOfClose = of?.close,
            nextSessionClose = next?.close,
            sessionOfPct = sessionOfPct,
            nextSessionPct = nextSessionPct,
            reactionVolume = reactionSession?.volume,
            latestClose = latest.close,
            driftSincePct = pctChange(reactionSession?.close, latest.close),
        ).also {
            AppLogger.i(
                TAG,
                "$symbol reaction around $reportDate: sessionOf=${fmtPct(it.sessionOfPct)} " +
                    "next=${fmtPct(it.nextSessionPct)} driftSince=${fmtPct(it.driftSincePct)}",
            )
        }
    }

    private data class Session(val date: LocalDate, val close: Double, val volume: Long?)

    /** Null-safe % change, guarding a zero/negative base. */
    private fun pctChange(from: Double?, to: Double?): Double? {
        if (from == null || to == null || from <= 0.0) return null
        return (to - from) / from * 100.0
    }

    private fun fmtPct(d: Double?): String = if (d == null) "—" else "%+.2f%%".format(d)

    private companion object {
        const val TAG = "PostEarningsReaction"
    }
}
