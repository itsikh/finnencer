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
        val articleIds = newsDao.topArticleIdsAcrossSymbols(
            symbols = symbols,
            minScore = MIN_SCORE,
            sinceMillis = sinceMillis,
            limit = MAX_ARTICLES,
        )
        if (articleIds.size < MIN_ARTICLES) {
            Log.i(TAG, "only ${articleIds.size} qualifying article(s) in last ${LOOKBACK_HOURS}h — skipping brief")
            return
        }
        val customPrompt = buildString {
            append("This is a personalized **morning brief** for the user's watchlist: ")
            append(symbols.joinToString(", "))
            append(". Cover what moved overnight, key earnings due today, and the most important news headlines. ")
            append("Keep it punchy (around five minutes when read aloud) and skip generic market commentary the user can get anywhere else.")
        }
        val jobId = aiJobs.enqueueSummaryAndPodcast(
            tickerSymbol = null, // cross-watchlist, no single ticker tag
            articleIds = articleIds,
            pages = BundleSummarizer.Pages.TWO,
            minutes = BundleSummarizer.PodcastMinutes.FIVE,
            customPrompt = customPrompt,
        )
        Log.i(TAG, "queued morning brief job $jobId from ${articleIds.size} articles across ${symbols.size} tickers")
    }

    private companion object {
        const val TAG = "MorningBrief"
        const val LOOKBACK_HOURS = 16L
        const val MIN_SCORE = 6
        const val MAX_ARTICLES = 25
        const val MIN_ARTICLES = 3
    }
}
