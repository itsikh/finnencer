package io.itsikh.finnencer.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import io.itsikh.finnencer.data.dao.AiJobDao
import io.itsikh.finnencer.data.dao.ApiUsageDao
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.dao.MoveExplanationDao
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.dao.NotificationDao
import io.itsikh.finnencer.data.dao.PodcastDao
import io.itsikh.finnencer.data.dao.QueueItemDao
import io.itsikh.finnencer.data.dao.TickerAnalystSnapshotDao
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.dao.TickerMetricsDao
import io.itsikh.finnencer.data.entity.AiJob
import io.itsikh.finnencer.data.entity.ApiUsage
import io.itsikh.finnencer.data.entity.ArticleScore
import io.itsikh.finnencer.data.entity.ArticleSummary
import io.itsikh.finnencer.data.entity.ArticleTickerXref
import io.itsikh.finnencer.data.entity.EarningsEvent
import io.itsikh.finnencer.data.entity.EarningsReport
import io.itsikh.finnencer.data.entity.MoveExplanation
import io.itsikh.finnencer.data.entity.NewsArticle
import io.itsikh.finnencer.data.entity.NotificationLog
import io.itsikh.finnencer.data.entity.Podcast
import io.itsikh.finnencer.data.entity.QueueItem
import io.itsikh.finnencer.data.entity.SummaryVersion
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.data.entity.TickerAnalystSnapshot
import io.itsikh.finnencer.data.entity.TickerMetrics
import io.itsikh.finnencer.data.entity.TickerMetricsAnalysis

@Database(
    entities = [
        Ticker::class,
        NewsArticle::class,
        ArticleTickerXref::class,
        ArticleScore::class,
        ArticleSummary::class,
        SummaryVersion::class,
        NotificationLog::class,
        EarningsEvent::class,
        EarningsReport::class,
        Podcast::class,
        ApiUsage::class,
        AiJob::class,
        QueueItem::class,
        MoveExplanation::class,
        TickerMetrics::class,
        TickerMetricsAnalysis::class,
        TickerAnalystSnapshot::class,
    ],
    version = 12,
    exportSchema = false,
)
abstract class FinnencerDatabase : RoomDatabase() {
    abstract fun tickerDao(): TickerDao
    abstract fun newsDao(): NewsDao
    abstract fun notificationDao(): NotificationDao
    abstract fun earningsDao(): EarningsDao
    abstract fun podcastDao(): PodcastDao
    abstract fun apiUsageDao(): ApiUsageDao
    abstract fun aiJobDao(): AiJobDao
    abstract fun queueItemDao(): QueueItemDao
    abstract fun moveExplanationDao(): MoveExplanationDao
    abstract fun tickerMetricsDao(): TickerMetricsDao
    abstract fun tickerAnalystSnapshotDao(): TickerAnalystSnapshotDao

    companion object {
        const val NAME = "finnencer.db"
    }
}
