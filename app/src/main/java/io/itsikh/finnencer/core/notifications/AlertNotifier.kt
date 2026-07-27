package io.itsikh.finnencer.core.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.MainActivity
import io.itsikh.finnencer.R
import io.itsikh.finnencer.data.dao.NotificationDao
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.entity.ArticleScore
import io.itsikh.finnencer.data.entity.NewsArticle
import io.itsikh.finnencer.data.entity.NotificationLog
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.logging.AppLogger
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides which scored articles deserve a push and posts the notification.
 *
 * Gates, in order (any failure suppresses):
 *  1. score >= ticker.threshold
 *  2. ticker not hard-muted
 *  3. current local time is OUTSIDE the ticker's quiet hours window
 *  4. notifications-already-sent-for-this-ticker-today < ticker.dailyCap
 *  5. another article in the same cluster_key was NOT already notified in
 *     the last 6h
 *  6. system notification permission + channel are both enabled
 *
 * Quiet hours defer rather than drop: an alert suppressed ONLY by the
 * quiet-hours gate is recorded in [DeferredAlertStore] and re-offered on
 * later cycles (up to ~12h) so it fires on the first fanout after the
 * window ends. Every other suppression (threshold, mute, cap, cluster
 * dedup) is terminal — deferred entries hitting one of those gates at
 * delivery time are dropped from the store, never retried.
 */
@Singleton
class AlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tickerDao: TickerDao,
    private val newsDao: NewsDao,
    private val notificationDao: NotificationDao,
    private val deferredStore: DeferredAlertStore,
) {

    data class FanoutStats(
        val candidates: Int,
        val suppressedByThreshold: Int,
        val suppressedByQuietHours: Int,
        val suppressedByMute: Int,
        val suppressedByCap: Int,
        val suppressedByClusterDedup: Int,
        val posted: Int,
        /** Dropped because POST_NOTIFICATIONS is denied or the alerts
         *  channel is off — a system-level block, not a threshold verdict. */
        val suppressedBySystemDisabled: Int = 0,
    )

    suspend fun fanout(newScores: List<ArticleScore>): FanoutStats {
        val now = System.currentTimeMillis()
        // Alerts deferred by quiet hours in earlier cycles join this
        // cycle's candidates; they pass through the exact same gates below
        // (threshold, mute, cap, cluster dedup), so nothing double-fires.
        val deferred = deferredCandidates(newScores, now)
        val deferredKeys = deferred.mapTo(HashSet()) { it.articleId to it.tickerSymbol }
        val candidates = newScores + deferred
        if (candidates.isEmpty()) return FanoutStats(0, 0, 0, 0, 0, 0, 0)
        if (!hasNotificationPermission()) {
            AppLogger.w(TAG, "fanout: notifications permission not granted; suppressing ${candidates.size}")
            return FanoutStats(candidates.size, 0, 0, 0, 0, 0, 0, suppressedBySystemDisabled = candidates.size)
        }
        NotificationChannels.ensureCreated(context)
        if (!NotificationChannels.areAlertsEnabled(context)) {
            AppLogger.w(TAG, "fanout: alerts channel disabled by user; suppressing ${candidates.size}")
            return FanoutStats(candidates.size, 0, 0, 0, 0, 0, 0, suppressedBySystemDisabled = candidates.size)
        }

        val startOfTodayMillis = ZonedDateTime.now(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val sixHoursAgo = now - 6 * 60 * 60 * 1000L

        var thrSup = 0
        var quietSup = 0
        var muteSup = 0
        var capSup = 0
        var clusterSup = 0
        var posted = 0

        // Sort highest-score-first so we burn the day's cap on the most
        // material items.
        for (score in candidates.sortedByDescending { it.score }) {
            val key = score.articleId to score.tickerSymbol
            val isDeferred = key in deferredKeys
            // Any terminal verdict for a deferred entry drops it from the
            // store — only "still inside quiet hours" keeps it alive.
            suspend fun dropDeferred() {
                if (isDeferred) deferredStore.remove(score.articleId, score.tickerSymbol)
            }

            val ticker = tickerDao.get(score.tickerSymbol)
            if (ticker == null) {
                dropDeferred(); continue
            }

            if (score.score < ticker.notificationThreshold) {
                thrSup++; dropDeferred(); continue
            }
            if (ticker.mutedUntilMillis != null && ticker.mutedUntilMillis > now) {
                muteSup++; dropDeferred(); continue
            }
            if (insideQuietHours(ticker, now)) {
                quietSup++
                // The ONLY non-terminal suppression: record for a later
                // cycle (no-op refresh if already recorded).
                if (!isDeferred) deferredStore.add(score.articleId, score.tickerSymbol, now)
                continue
            }
            val sentToday = notificationDao.countSinceForTicker(ticker.symbol, startOfTodayMillis)
            if (sentToday >= ticker.dailyNotificationCap) {
                capSup++; dropDeferred(); continue
            }
            val article = newsDao.getArticle(score.articleId)
            if (article == null) {
                dropDeferred(); continue
            }
            val clusterAlreadyAlerted = notificationDao.clusterAlreadyNotified(
                article.clusterKey, sixHoursAgo,
            )
            if (clusterAlreadyAlerted) {
                clusterSup++; dropDeferred(); continue
            }

            postNotification(ticker, article, score)
            dropDeferred()
            // Log-insert failure must not abort the whole fanout: the
            // notification is already on screen, and rethrowing here would
            // retry the cycle and re-post it (no log row = no dedup).
            runCatching {
                notificationDao.insert(
                    NotificationLog(
                        articleId = article.id,
                        tickerSymbol = ticker.symbol,
                        score = score.score,
                        sentAtMillis = now,
                    )
                )
            }.onFailure { AppLogger.e(TAG, "notification log insert failed for ${article.id}", it) }
            AppLogger.i(TAG, "posted $${ticker.symbol} score=${score.score} cat=${score.category}")
            posted++
        }

        AppLogger.i(TAG, "fanout: candidates=${candidates.size} posted=$posted thr=$thrSup mute=$muteSup quiet=$quietSup cap=$capSup cluster=$clusterSup")
        return FanoutStats(
            candidates = candidates.size,
            suppressedByThreshold = thrSup,
            suppressedByQuietHours = quietSup,
            suppressedByMute = muteSup,
            suppressedByCap = capSup,
            suppressedByClusterDedup = clusterSup,
            posted = posted,
        )
    }

    /**
     * Alerts previously recorded in [DeferredAlertStore] (quiet-hours
     * suppressions only), resolved back to their [ArticleScore] rows.
     * Entries whose score or article row has since been pruned are
     * dropped from the store. This cycle's own scores are excluded so a
     * just-recorded deferral isn't offered twice in the same fanout.
     */
    private suspend fun deferredCandidates(
        newScores: List<ArticleScore>,
        now: Long,
    ): List<ArticleScore> {
        val newKeys = newScores.mapTo(HashSet()) { it.articleId to it.tickerSymbol }
        return deferredStore.entries(DEFERRED_LOOKBACK_MS, now)
            .filter { (it.articleId to it.tickerSymbol) !in newKeys }
            .mapNotNull { entry ->
                val score = newsDao.scoresFor(entry.articleId)
                    .firstOrNull { it.tickerSymbol == entry.tickerSymbol }
                if (score == null) {
                    deferredStore.remove(entry.articleId, entry.tickerSymbol)
                }
                score
            }
    }

    private fun insideQuietHours(ticker: Ticker, instantMillis: Long): Boolean {
        val nowMin = ZonedDateTime.ofInstant(Instant.ofEpochMilli(instantMillis), ZoneId.systemDefault())
            .let { it.hour * 60 + it.minute }
        val start = ticker.quietHoursStartMinute
        val end = ticker.quietHoursEndMinute
        return if (start == end) {
            false
        } else if (start < end) {
            nowMin in start until end
        } else {
            // wraps midnight (e.g. 23:00 -> 06:00)
            nowMin >= start || nowMin < end
        }
    }

    private fun postNotification(ticker: Ticker, article: NewsArticle, score: ArticleScore) {
        // Deep-link straight to the article detail screen so the tap is a
        // one-step path to read / bookmark / generate a summary. The
        // matching navDeepLink is registered in AppNavHost on the
        // `article/{articleId}` route; MainActivity has launchMode
        // singleTop + an intent-filter for this scheme in the manifest.
        val tapIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("finnencer://article/${article.id}"),
            context,
            MainActivity::class.java,
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            article.id.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "[$" + "${ticker.symbol}] " + article.title.take(80)
        val text = buildString {
            append(score.reason.ifBlank { article.snippet ?: "" })
            append(" · score ${score.score} · ${score.category.lowercase().replace('_', ' ')}")
        }
        val notif = NotificationCompat.Builder(context, NotificationChannels.ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(if (score.score >= 9) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .build()

        runCatching {
            // Tag by article id with a fixed numeric id: id.hashCode() can
            // collide across articles and replace an unrelated live
            // notification. The tap Intent is already unique per article via
            // its data URI, which PendingIntent's filterEquals includes.
            NotificationManagerCompat.from(context).notify(article.id, ALERT_NOTIFICATION_ID, notif)
        }.onFailure { AppLogger.e(TAG, "notify() threw for ${article.id}", it) }
    }

    private companion object {
        const val TAG = "AlertNotifier"
        const val ALERT_NOTIFICATION_ID = 1
        // How long a quiet-hours-deferred alert stays deliverable.
        const val DEFERRED_LOOKBACK_MS = 12 * 60 * 60 * 1000L
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

