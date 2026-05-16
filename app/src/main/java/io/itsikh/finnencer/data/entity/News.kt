package io.itsikh.finnencer.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Enum-as-String columns. Storing the `name` (not ordinal) so reordering the
 * enum doesn't corrupt the DB.
 */
enum class NewsProvider {
    // Free, in active use
    RSS_GOOGLE_NEWS,
    RSS_YAHOO_FINANCE,
    RSS_NASDAQ,
    RSS_SEEKING_ALPHA,
    SEC_EDGAR,
    // Legacy / inactive (kept so old rows still parse)
    FINNHUB,
    MARKETAUX,
}

enum class ArticleCategory {
    EARNINGS, M_AND_A, REGULATORY, MANAGEMENT, MACRO, LEGAL,
    PRODUCT, ANALYST, INSIDER, OTHER
}

/**
 * A single news article. `id` is a stable hash of `provider + sourceArticleId`
 * so re-ingesting the same article from the same provider is idempotent.
 *
 * `clusterKey` groups articles about the same underlying story (same headline
 * across multiple wires within ~6h). Used by the notification engine for
 * dedup.
 */
@Entity(
    tableName = "news_articles",
    indices = [
        Index("primary_ticker_symbol"),
        Index("published_at_millis"),
        Index("cluster_key"),
        Index(value = ["provider", "source_article_id"], unique = true),
    ],
)
data class NewsArticle(
    @PrimaryKey val id: String,
    val provider: String,
    @ColumnInfo(name = "source_article_id") val sourceArticleId: String,
    val title: String,
    val snippet: String?,
    val url: String,
    @ColumnInfo(name = "source_name") val sourceName: String,
    @ColumnInfo(name = "image_url") val imageUrl: String?,
    @ColumnInfo(name = "published_at_millis") val publishedAtMillis: Long,
    @ColumnInfo(name = "fetched_at_millis") val fetchedAtMillis: Long,
    @ColumnInfo(name = "cluster_key") val clusterKey: String,
    @ColumnInfo(name = "primary_ticker_symbol") val primaryTickerSymbol: String?,
)

/**
 * Many-to-many join: an article may reference several tickers (e.g. M&A news
 * mentioning acquirer + target).
 */
@Entity(
    tableName = "article_ticker_xref",
    primaryKeys = ["article_id", "ticker_symbol"],
    foreignKeys = [
        ForeignKey(
            entity = NewsArticle::class,
            parentColumns = ["id"],
            childColumns = ["article_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Ticker::class,
            parentColumns = ["symbol"],
            childColumns = ["ticker_symbol"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("ticker_symbol")],
)
data class ArticleTickerXref(
    @ColumnInfo(name = "article_id") val articleId: String,
    @ColumnInfo(name = "ticker_symbol") val tickerSymbol: String,
)

/**
 * Importance score for an article. One row per (article, ticker) pair because
 * the same article can have different importance to different tickers it
 * mentions.
 */
@Entity(
    tableName = "article_scores",
    primaryKeys = ["article_id", "ticker_symbol"],
    foreignKeys = [
        ForeignKey(
            entity = NewsArticle::class,
            parentColumns = ["id"],
            childColumns = ["article_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("ticker_symbol"), Index("score")],
)
data class ArticleScore(
    @ColumnInfo(name = "article_id") val articleId: String,
    @ColumnInfo(name = "ticker_symbol") val tickerSymbol: String,
    val score: Int,
    val category: String, // ArticleCategory.name
    val reason: String,
    val model: String,
    @ColumnInfo(name = "scored_at_millis") val scoredAtMillis: Long,
)

/**
 * LLM-generated summary of a single article. Cached so we don't pay for the
 * same summary twice.
 */
@Entity(
    tableName = "article_summaries",
    foreignKeys = [
        ForeignKey(
            entity = NewsArticle::class,
            parentColumns = ["id"],
            childColumns = ["article_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ArticleSummary(
    @PrimaryKey @ColumnInfo(name = "article_id") val articleId: String,
    val summary: String,
    val model: String,
    @ColumnInfo(name = "generated_at_millis") val generatedAtMillis: Long,
)
