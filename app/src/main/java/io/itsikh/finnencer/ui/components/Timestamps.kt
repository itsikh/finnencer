package io.itsikh.finnencer.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shared "when was this made" formatting for anything the AI produced —
 * earnings reports, podcasts, summaries, move explanations, snapshot
 * interpretations and job rows.
 *
 * There is one helper because there were four divergent local ones: some
 * screens showed a date with no time, one showed date + time, one showed
 * only a relative age, and several showed nothing at all. Comparing two
 * reports for the same quarter, or working out whether a podcast predates
 * this morning's filing, needs the same answer in the same shape
 * everywhere.
 *
 * Minute precision, no seconds: these are human "when did I generate
 * this" stamps, and seconds are noise at a glance. 24-hour clock matches
 * the formatters already in the app.
 */
object Timestamps {

    private val ZONE: ZoneId get() = ZoneId.systemDefault()

    /** "Jul 29 · 17:38" — same calendar year. */
    private val SAME_YEAR: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d · HH:mm")

    /** "Jul 29, 2025 · 17:38" — the year only appears when it isn't this
     *  one, so the common case stays short on a dense row. */
    private val OTHER_YEAR: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")

    /**
     * Absolute creation stamp for an AI-produced item.
     *
     * Deliberately absolute rather than relative ("3h ago"): a relative
     * age answers "is this fresh?" but not "is this the report I
     * generated before or after the earnings call?", which is the
     * question that actually comes up.
     */
    fun created(millis: Long): String {
        val zoned = Instant.ofEpochMilli(millis).atZone(ZONE)
        val formatter = if (zoned.year == Instant.now().atZone(ZONE).year) SAME_YEAR else OTHER_YEAR
        return formatter.format(zoned)
    }

    /** [created] with a leading label, e.g. "Generated Jul 29 · 17:38". */
    fun generated(millis: Long): String = "Generated ${created(millis)}"

    /**
     * Compact relative age — "just now", "14m ago", "3h ago", "2d ago".
     * Pair it with [created] where both matter; on its own it can't
     * distinguish two items made the same afternoon.
     */
    fun age(millis: Long): String {
        val mins = ((System.currentTimeMillis() - millis).coerceAtLeast(0L)) / 60_000L
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 1440 -> "${mins / 60}h ago"
            else -> "${mins / 1440}d ago"
        }
    }

    /** "Jul 29 · 17:38 · 3h ago" — for detail screens with room for both. */
    fun createdWithAge(millis: Long): String = "${created(millis)} · ${age(millis)}"
}
