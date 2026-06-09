package io.itsikh.finnencer.core.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.itsikh.finnencer.data.ai.BundleSummarizer
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.repo.AiJobsRepository
import io.itsikh.finnencer.data.repo.WatchlistRepository
import io.itsikh.finnencer.logging.AppLogger as Log
import kotlinx.coroutines.flow.first

/**
 * Generates the user's personalized morning brief podcast.
 *
 * Flow:
 *  1. Load the watched tickers (if empty, skip — nothing to brief on).
 *  2. Pull the top-scored news cluster heads across that set, published
 *     within [LOOKBACK_HOURS] hours (covers overnight + early
 *     pre-market). Score floor [MIN_SCORE] avoids briefing on noise.
 *  3. Cap at [MAX_ARTICLES] articles to keep the AI call cheap and the
 *     podcast tight.
 *  4. Hand the article IDs to [AiJobsRepository.enqueueSummaryAndPodcast]
 *     with a custom-prompt tag identifying this as a morning brief — the
 *     existing podcast pipeline (summarize → script → TTS → library row
 *     → notification) takes it from there.
 *  5. Reschedule the next run (chained OneTimeWorkRequest pattern).
 *
 * Cases that quietly skip (without erroring):
 *  - Empty watchlist
 *  - Fewer than [MIN_ARTICLES] qualifying articles (no signal to brief on)
 *  - Disabled at the prefs level (the scheduler should have cancelled but
 *    we double-check defensively)
 */
@HiltWorker
class MorningBriefWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val watchlist: WatchlistRepository,
    private val newsDao: NewsDao,
    private val aiJobs: AiJobsRepository,
    private val scheduler: MorningBriefScheduler,
    private val prefs: io.itsikh.finnencer.data.repo.MorningBriefPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        try {
            generateOnce()
        } catch (t: Throwable) {
            Log.e(TAG, "morning-brief generation failed", t)
            // Don't retry on the same trigger — we'll get another shot
            // tomorrow. Always reschedule so the next firing happens.
        } finally {
            runCatching { scheduler.rescheduleNext() }
        }
        return Result.success()
    }

    private suspend fun generateOnce() {
        if (prefs.enabled.first() != true) {
            Log.i(TAG, "morning brief disabled — skipping")
            return
        }
        val tickers = watchlist.observeAll().first()
        if (tickers.isEmpty()) {
            Log.i(TAG, "no tickers on watchlist — skipping morning brief")
            return
        }
        val symbols = tickers.map { it.symbol }
        val sinceMillis = System.currentTimeMillis() - LOOKBACK_HOURS * 60L * 60L * 1000L
        // Only "big news" qualifies for the brief — the score floor is the
        // gate the user asked for ("only big news, only if there's anything
        // to tell"). Everything below MIN_SCORE is day-to-day noise.
        val articleIds = newsDao.topArticleIdsAcrossSymbols(
            symbols = symbols,
            minScore = MIN_SCORE,
            sinceMillis = sinceMillis,
            limit = MAX_ARTICLES,
        )
        // Dynamic length: scale the episode to how much actually happened.
        //   ≤1 big story  → skip entirely (a 30-second "nothing happened"
        //                    podcast is worse than no podcast)
        //   2–4 stories   → a tight ~5-minute brief
        //   5+ stories    → the full ~15-minute brief
        val count = articleIds.size
        val plan = when {
            count <= SKIP_AT_OR_BELOW -> null
            count < LONG_BRIEF_THRESHOLD -> BriefPlan(
                BundleSummarizer.Pages.TWO,
                BundleSummarizer.PodcastMinutes.FIVE,
            )
            else -> BriefPlan(
                BundleSummarizer.Pages.TEN,
                BundleSummarizer.PodcastMinutes.FIFTEEN,
            )
        }
        if (plan == null) {
            Log.i(TAG, "only $count big story(ies) in last ${LOOKBACK_HOURS}h — skipping brief")
            return
        }
        val customPrompt = buildString {
            append("This is a personalized **daily brief** for the user's watchlist: ")
            append(symbols.joinToString(", "))
            append(". Cover only what genuinely matters: big moves, M&A, guidance/earnings, regulatory or management news. ")
            append("Lead with the most important story. Skip generic market commentary the user can get anywhere else, ")
            append("and don't pad — if a story isn't material, leave it out. ")
            append("Target about ${plan.minutes.minutes} minutes when read aloud.")
        }
        val jobId = aiJobs.enqueueSummaryAndPodcast(
            tickerSymbol = null, // cross-watchlist, no single ticker tag
            articleIds = articleIds,
            pages = plan.pages,
            minutes = plan.minutes,
            customPrompt = customPrompt,
        )
        Log.i(TAG, "queued daily brief job $jobId (~${plan.minutes.minutes}min) from $count big stories across ${symbols.size} tickers")
    }

    private data class BriefPlan(
        val pages: BundleSummarizer.Pages,
        val minutes: BundleSummarizer.PodcastMinutes,
    )

    private companion object {
        const val TAG = "MorningBrief"
        // Cover the full prior trading day + overnight + early pre-market.
        const val LOOKBACK_HOURS = 24L
        // "Big news only" — 8+ on the 1–10 importance scale.
        const val MIN_SCORE = 8
        const val MAX_ARTICLES = 25
        // ≤1 qualifying story → no episode. 5+ → the long (~15min) brief.
        const val SKIP_AT_OR_BELOW = 1
        const val LONG_BRIEF_THRESHOLD = 5
    }
}
