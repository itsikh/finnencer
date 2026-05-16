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
 */
@Singleton
class AlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tickerDao: TickerDao,
    private val newsDao: NewsDao,
    private val notificationDao: NotificationDao,
) {

    data class FanoutStats(
        val candidates: Int,
        val suppressedByThreshold: Int,
        val suppressedByQuietHours: Int,
        val suppressedByMute: Int,
        val suppressedByCap: Int,
        val suppressedByClusterDedup: Int,
        val posted: Int,
    )

    suspend fun fanout(newScores: List<ArticleScore>): FanoutStats {
        if (newScores.isEmpty()) return FanoutStats(0, 0, 0, 0, 0, 0, 0)
        if (!hasNotificationPermission()) {
            return FanoutStats(newScores.size, newScores.size, 0, 0, 0, 0, 0)
        }
        NotificationChannels.ensureCreated(context)
        if (!NotificationChannels.areAlertsEnabled(context)) {
            return FanoutStats(newScores.size, newScores.size, 0, 0, 0, 0, 0)
        }

        val now = System.currentTimeMillis()
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
        for (score in newScores.sortedByDescending { it.score }) {
            val ticker = tickerDao.get(score.tickerSymbol) ?: continue

            if (score.score < ticker.notificationThreshold) {
                thrSup++; continue
            }
            if (ticker.mutedUntilMillis != null && ticker.mutedUntilMillis > now) {
                muteSup++; continue
            }
            if (insideQuietHours(ticker, now)) {
                quietSup++; continue
            }
            val sentToday = notificationDao.countSinceForTicker(ticker.symbol, startOfTodayMillis)
            if (sentToday >= ticker.dailyNotificationCap) {
                capSup++; continue
            }
            val article = newsDao.getArticle(score.articleId) ?: continue
            val clusterAlreadyAlerted = notificationDao.clusterAlreadyNotified(
                article.clusterKey, sixHoursAgo,
            )
            if (clusterAlreadyAlerted) {
                clusterSup++; continue
            }

            postNotification(ticker, article, score)
            notificationDao.insert(
                NotificationLog(
                    articleId = article.id,
                    tickerSymbol = ticker.symbol,
                    score = score.score,
                    sentAtMillis = now,
                )
            )
            posted++
        }

        return FanoutStats(
            candidates = newScores.size,
            suppressedByThreshold = thrSup,
            suppressedByQuietHours = quietSup,
            suppressedByMute = muteSup,
            suppressedByCap = capSup,
            suppressedByClusterDedup = clusterSup,
            posted = posted,
        )
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
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            data = Uri.parse("finnencer://ticker/${ticker.symbol}?article=${article.id}")
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
            NotificationManagerCompat.from(context).notify(article.id.hashCode(), notif)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

