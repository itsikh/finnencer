package io.itsikh.finnencer.data.ai

import io.itsikh.finnencer.data.entity.ReportTier
import io.itsikh.finnencer.logging.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the user asked for when choosing an episode length.
 *
 * [Auto] is the default: rather than the listener guessing how much there
 * is to say about a print, [PodcastAutoSizer] measures the available
 * substance and picks both the report depth and the runway. A quarter with
 * guidance, segment detail and a big surprise earns thirty minutes; a
 * sparse pre-announcement gets five.
 */
sealed interface PodcastDuration {
    data object Auto : PodcastDuration
    data class Fixed(val minutes: BundleSummarizer.PodcastMinutes) : PodcastDuration

    companion object {
        /**
         * Job payloads carry minutes as a nullable Int — see
         * [io.itsikh.finnencer.core.work.AiJobWorker.PodcastInput] for why
         * these are primitives and not enums. `null` (or an absent field,
         * which Gson also yields as null) means Auto; any previously
         * persisted row carries a real number and keeps its fixed length.
         */
        fun fromMinutesValue(value: Int?): PodcastDuration {
            if (value == null) return Auto
            val match = BundleSummarizer.PodcastMinutes.entries.firstOrNull { it.minutes == value }
                ?: return Auto
            return Fixed(match)
        }
    }
}

/** Inverse of [PodcastDuration.fromMinutesValue] for writing job payloads. */
fun PodcastDuration.toMinutesValue(): Int? = when (this) {
    PodcastDuration.Auto -> null
    is PodcastDuration.Fixed -> minutes.minutes
}

/**
 * Label for job titles and pickers. Auto is deliberately named "Auto"
 * rather than resolved to a number here — at enqueue time the length
 * genuinely isn't known yet, and showing a guess that the worker then
 * contradicts is worse than showing none.
 */
fun PodcastDuration.titleLabel(): String = when (this) {
    PodcastDuration.Auto -> "Auto-length"
    is PodcastDuration.Fixed -> "${minutes.minutes} min"
}

/** Short label for a duration chip. */
fun PodcastDuration.chipLabel(): String = when (this) {
    PodcastDuration.Auto -> "Auto"
    is PodcastDuration.Fixed -> "${minutes.minutes} min"
}

/**
 * The duration choices every picker offers, in display order. Auto leads
 * and is the default: for most prints the listener has no way to know
 * whether there are five minutes or thirty minutes of substance in a
 * quarter until the sources have been read.
 */
val podcastDurationOptions: List<PodcastDuration> =
    listOf(PodcastDuration.Auto) +
        BundleSummarizer.PodcastMinutes.entries.map { PodcastDuration.Fixed(it) }

/**
 * Decides report depth and episode length from how much substance a print
 * actually has.
 *
 * The scoring is deliberately deterministic and inspectable rather than
 * model-driven: sizing decisions that silently change cost and runtime
 * need to be explainable after the fact, and [Decision.explanation] is
 * surfaced to the user for exactly that reason.
 *
 * Chosen minutes always snap to an existing [BundleSummarizer.PodcastMinutes]
 * preset. Everything downstream keys off that enum — the 20-minute
 * analyst-reactions gate, the character budget, the stored
 * `characterCount`, TTS chunking, the cost hints — so producing a
 * free-form 17 minutes would mean touching all of it for no listener
 * benefit.
 */
@Singleton
class PodcastAutoSizer @Inject constructor() {

    data class Decision(
        val tier: ReportTier,
        val minutes: BundleSummarizer.PodcastMinutes,
        val contentPoints: Int,
        val sizeSignal: Int,
        /** Human-readable reason, shown in the task subtitle and the title. */
        val explanation: String,
    )

    /**
     * Score the substance available for an earnings print.
     *
     * Each term is capped so no single signal can dominate: a company with
     * 40 cached news items shouldn't earn a thirty-minute episode on news
     * volume alone.
     */
    fun contentPoints(signals: EarningsContentSignals): Int {
        val metrics = signals.metricCount.coerceIn(0, MAX_METRIC_POINTS)
        val comparatives = (signals.comparativeQuarters * 2).coerceIn(0, MAX_COMPARATIVE_POINTS)
        val segments = if (signals.hasSegmentDetail) SEGMENT_POINTS else 0
        val guidance = signals.guidanceSentenceCount.coerceIn(0, MAX_GUIDANCE_POINTS)
        val surprise = bucket(signals.surprisePctAbs, lowThreshold = 2.0, highThreshold = 10.0)
        val reaction = bucket(signals.reactionPctAbs, lowThreshold = 3.0, highThreshold = 8.0)
        val news = signals.newsCount.coerceIn(0, MAX_NEWS_POINTS)
        val dispersion = when {
            signals.analystDispersionPct == null -> 0
            signals.analystDispersionPct >= 60.0 -> 3
            signals.analystDispersionPct >= 25.0 -> 2
            else -> 0
        }
        return metrics + comparatives + segments + guidance + surprise + reaction + news + dispersion
    }

    /** Report depth for a given score. */
    fun tierFor(contentPoints: Int): ReportTier = when {
        contentPoints < BRIEF_CEILING -> ReportTier.BRIEF
        contentPoints <= STANDARD_CEILING -> ReportTier.STANDARD
        else -> ReportTier.DEEP
    }

    /**
     * Episode length. Combines the content score with how much the report
     * writer actually produced — a DEEP report that came back short has
     * less to talk about than its tier implies, and vice versa.
     */
    fun minutesFor(contentPoints: Int, reportChars: Int): BundleSummarizer.PodcastMinutes {
        val signal = sizeSignal(contentPoints, reportChars)
        return when {
            signal < 12 -> BundleSummarizer.PodcastMinutes.FIVE
            signal < 22 -> BundleSummarizer.PodcastMinutes.TEN
            signal < 34 -> BundleSummarizer.PodcastMinutes.FIFTEEN
            signal < 48 -> BundleSummarizer.PodcastMinutes.TWENTY
            else -> BundleSummarizer.PodcastMinutes.THIRTY
        }
    }

    fun sizeSignal(contentPoints: Int, reportChars: Int): Int =
        contentPoints + reportChars / CHARS_PER_SIGNAL_POINT

    /**
     * Hard ceiling on runway from the amount of discussable substance that
     * actually exists, independent of the score.
     *
     * The score can be inflated by signals that don't give the speakers
     * anything to SAY — a 25% earnings surprise on a company with seven
     * tagged metrics and no press release scores like a big story and then
     * has to fill fifteen minutes from seven numbers. That is the exact
     * padding failure this whole change set exists to remove, so substance
     * gets a veto over enthusiasm.
     *
     * Roughly one minute per verified figure, plus credit for a press
     * release: segment tables and guidance paragraphs are real discussion
     * material that isn't a countable numeric fact.
     */
    fun substanceMinutesCap(signals: EarningsContentSignals): Int =
        signals.metricCount +
            (if (signals.hasSegmentDetail) SEGMENT_MINUTES_CREDIT else 0) +
            signals.guidanceSentenceCount

    /**
     * Episode length for an earnings print: the score-based choice, capped
     * by [substanceMinutesCap] and never below the shortest preset.
     */
    fun minutesForEarnings(
        signals: EarningsContentSignals,
        reportChars: Int,
    ): BundleSummarizer.PodcastMinutes {
        val bySignal = minutesFor(contentPoints(signals), reportChars)
        val cap = substanceMinutesCap(signals)
        val bySubstance = BundleSummarizer.PodcastMinutes.entries
            .filter { it.minutes <= cap }
            .maxByOrNull { it.minutes }
            ?: BundleSummarizer.PodcastMinutes.FIVE
        return if (bySubstance.minutes < bySignal.minutes) bySubstance else bySignal
    }

    /**
     * Full decision for an earnings print. Call after the report exists so
     * [reportChars] is real; pass 0 to size the report tier alone.
     */
    fun decideForEarnings(signals: EarningsContentSignals, reportChars: Int): Decision {
        val points = contentPoints(signals)
        val tier = tierFor(points)
        val minutes = minutesForEarnings(signals, reportChars)
        val decision = Decision(
            tier = tier,
            minutes = minutes,
            contentPoints = points,
            sizeSignal = sizeSignal(points, reportChars),
            explanation = explainEarnings(signals, tier, minutes, reportChars),
        )
        AppLogger.i(
            TAG,
            "auto sizing: points=$points signal=${decision.sizeSignal} " +
                "substanceCap=${substanceMinutesCap(signals)}min " +
                "(scoreWanted=${minutesFor(points, reportChars).minutes}min) " +
                "→ ${tier.name} report, ${minutes.minutes} min · ${decision.explanation}",
        )
        return decision
    }

    /**
     * Auto sizing for a news-bundle podcast, which has no facts sheet.
     * Article count and how much body text the articles carry are the only
     * substance signals available.
     */
    fun decideForBundle(articleCount: Int, sourceChars: Int): Decision {
        // Each article is worth a couple of minutes of discussion at most;
        // the text volume decides whether there's real content behind the
        // headline count.
        val points = (articleCount * 2).coerceAtMost(20)
        val minutes = minutesFor(points, sourceChars)
        val explanation = "Auto · ${minutes.minutes} min ($articleCount article" +
            (if (articleCount == 1) "" else "s") + ", ${sourceChars / 1000}k chars of source)"
        val decision = Decision(
            tier = ReportTier.BRIEF, // unused on this path
            minutes = minutes,
            contentPoints = points,
            sizeSignal = sizeSignal(points, sourceChars),
            explanation = explanation,
        )
        AppLogger.i(TAG, "auto sizing (bundle): $explanation")
        return decision
    }

    /**
     * Why Auto chose what it chose, in the compressed form the Tasks card
     * and podcast title can carry. Auto that can't be questioned is
     * indistinguishable from Auto that's broken.
     */
    private fun explainEarnings(
        signals: EarningsContentSignals,
        tier: ReportTier,
        minutes: BundleSummarizer.PodcastMinutes,
        reportChars: Int,
    ): String {
        val parts = buildList {
            add("${signals.metricCount} metrics")
            if (signals.comparativeQuarters > 0) add("${signals.comparativeQuarters} comparatives")
            if (signals.guidanceSentenceCount > 0) add("guidance ✓") else add("no guidance")
            if (signals.hasSegmentDetail) add("segments ✓")
            signals.surprisePctAbs?.let { add("${"%.1f".format(it)}% surprise") }
            signals.reactionPctAbs?.let { add("${"%.1f".format(it)}% reaction") }
            if (signals.newsCount > 0) add("${signals.newsCount} news")
            // Say so when substance, not score, set the length — otherwise a
            // big surprise looks like it should have earned a long episode
            // and the short one reads as a bug.
            if (minutes.minutes < minutesFor(contentPoints(signals), reportChars).minutes) {
                add("length capped by available detail")
            }
        }
        val tierLabel = tier.name.lowercase().replaceFirstChar { it.uppercase() }
        return "Auto · $tierLabel report · ${minutes.minutes} min (${parts.joinToString(", ")})"
    }

    /** 0 / 2 / 4 points for a magnitude signal, 0 when we have no reading. */
    private fun bucket(value: Double?, lowThreshold: Double, highThreshold: Double): Int = when {
        value == null -> 0
        value >= highThreshold -> 4
        value >= lowThreshold -> 2
        else -> 0
    }

    private companion object {
        const val TAG = "PodcastAutoSizer"

        // Per-signal caps. Tuned as a starting calibration — the log line
        // in decideForEarnings prints every term so these can be adjusted
        // against real episodes rather than guessed at twice.
        const val MAX_METRIC_POINTS = 14
        const val MAX_COMPARATIVE_POINTS = 8
        const val SEGMENT_POINTS = 8
        const val MAX_GUIDANCE_POINTS = 6
        const val MAX_NEWS_POINTS = 10

        const val BRIEF_CEILING = 14
        const val STANDARD_CEILING = 32

        /** Minutes of runway a retrievable press release is worth on top of
         *  the countable figures — segment tables and guidance paragraphs. */
        const val SEGMENT_MINUTES_CREDIT = 8

        /** 1000 characters of report ≈ one point of episode runway. */
        const val CHARS_PER_SIGNAL_POINT = 1000
    }
}
