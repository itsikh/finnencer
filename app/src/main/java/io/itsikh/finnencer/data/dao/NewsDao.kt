package io.itsikh.finnencer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.itsikh.finnencer.data.entity.ArticleScore
import io.itsikh.finnencer.data.entity.ArticleSummary
import io.itsikh.finnencer.data.entity.ArticleTickerXref
import io.itsikh.finnencer.data.entity.NewsArticle
import kotlinx.coroutines.flow.Flow

/**
 * Aggregate row used by feed UIs: an article joined with its score (if any)
 * for a specific ticker.
 */
data class ScoredArticleRow(
    val id: String,
    val title: String,
    val snippet: String?,
    val url: String,
    val source_name: String,
    val image_url: String?,
    val published_at_millis: Long,
    val primary_ticker_symbol: String?,
    val cluster_key: String,
    val score: Int?,
    val category: String?,
    val reason: String?,
)

@Dao
interface NewsDao {

    // ───────── articles ─────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArticles(articles: List<NewsArticle>): List<Long>

    @Query("SELECT * FROM news_articles WHERE id = :id")
    suspend fun getArticle(id: String): NewsArticle?

    @Query("SELECT id FROM news_articles WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    @Query(
        """
        SELECT id FROM news_articles
        WHERE cluster_key = :clusterKey AND fetched_at_millis >= :sinceMillis
        LIMIT 1
        """
    )
    suspend fun clusterMember(clusterKey: String, sinceMillis: Long): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkTickers(xrefs: List<ArticleTickerXref>)

    // ───────── scores ─────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScores(scores: List<ArticleScore>)

    /** Articles still missing a score for a given ticker. Used by the scoring batcher. */
    @Query(
        """
        SELECT a.* FROM news_articles a
        INNER JOIN article_ticker_xref x ON x.article_id = a.id
        LEFT JOIN article_scores s
            ON s.article_id = a.id AND s.ticker_symbol = x.ticker_symbol
        WHERE s.score IS NULL
        ORDER BY a.published_at_millis DESC
        LIMIT :limit
        """
    )
    suspend fun unscoredJoined(limit: Int): List<NewsArticle>

    @Query("SELECT * FROM article_scores WHERE article_id = :articleId")
    suspend fun scoresFor(articleId: String): List<ArticleScore>

    // ───────── summaries ─────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSummary(summary: ArticleSummary)

    @Query("SELECT * FROM article_summaries WHERE article_id = :articleId")
    suspend fun summaryFor(articleId: String): ArticleSummary?

    // ───────── feed queries ─────────

    @Transaction
    @Query(
        """
        SELECT a.id, a.title, a.snippet, a.url, a.source_name, a.image_url,
               a.published_at_millis, a.primary_ticker_symbol, a.cluster_key,
               s.score, s.category, s.reason
        FROM news_articles a
        INNER JOIN article_ticker_xref x ON x.article_id = a.id
        LEFT JOIN article_scores s
            ON s.article_id = a.id AND s.ticker_symbol = x.ticker_symbol
        WHERE x.ticker_symbol = :symbol
        ORDER BY a.published_at_millis DESC
        LIMIT :limit
        """
    )
    fun observeTickerFeed(symbol: String, limit: Int = 200): Flow<List<ScoredArticleRow>>

    @Transaction
    @Query(
        """
        SELECT a.id, a.title, a.snippet, a.url, a.source_name, a.image_url,
               a.published_at_millis, a.primary_ticker_symbol, a.cluster_key,
               MAX(s.score) AS score, s.category, s.reason
        FROM news_articles a
        LEFT JOIN article_scores s ON s.article_id = a.id
        GROUP BY a.id
        ORDER BY a.published_at_millis DESC
        LIMIT :limit
        """
    )
    fun observeGlobalFeed(limit: Int = 500): Flow<List<ScoredArticleRow>>

    @Query("DELETE FROM news_articles WHERE fetched_at_millis < :beforeMillis")
    suspend fun pruneOlderThan(beforeMillis: Long): Int
}
