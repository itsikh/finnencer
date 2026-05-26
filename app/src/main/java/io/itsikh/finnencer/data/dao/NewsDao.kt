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
import io.itsikh.finnencer.data.entity.SummaryVersion
import kotlinx.coroutines.flow.Flow

/** Projection for [NewsDao.observeHighScoreCounts]: ticker_symbol → count. */
data class TickerCountRow(
    val symbol: String,
    val count: Int,
)

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

    @Query(
        """
        UPDATE article_scores
        SET user_override = :override
        WHERE article_id = :articleId AND ticker_symbol = :ticker
        """
    )
    suspend fun setUserOverride(articleId: String, ticker: String, override: Int?)

    // ───────── summaries (legacy single-row table) ─────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSummary(summary: ArticleSummary)

    @Query("SELECT * FROM article_summaries WHERE article_id = :articleId")
    suspend fun summaryFor(articleId: String): ArticleSummary?

    // ───────── summaries (versioned) ─────────

    @Insert
    suspend fun insertSummaryVersion(version: SummaryVersion): Long

    @Query(
        """
        SELECT * FROM summary_versions
        WHERE article_id = :articleId
        ORDER BY generated_at_millis DESC
        """
    )
    fun observeSummaryVersions(articleId: String): Flow<List<SummaryVersion>>

    @Query(
        """
        SELECT * FROM summary_versions
        WHERE article_id = :articleId
        ORDER BY generated_at_millis DESC
        LIMIT 1
        """
    )
    suspend fun latestSummaryVersion(articleId: String): SummaryVersion?

    // ───────── feed queries ─────────
    // `score` in the projection prefers user_override when set, so all
    // existing UI (filter chips, ordering, min-score gate) honors the
    // override transparently.

    // The cluster-key collapse picks the newest article per cluster_key so
    // the user sees one row per story even when 3 providers carry it
    // (#37 — CRBS triple-arrival). We pick the row by id-tiebreak only
    // when published_at_millis ties exactly, which is rare across
    // independent providers.
    @Transaction
    @Query(
        """
        SELECT a.id, a.title, a.snippet, a.url, a.source_name, a.image_url,
               a.published_at_millis, a.primary_ticker_symbol, a.cluster_key,
               COALESCE(s.user_override, s.score) AS score, s.category, s.reason
        FROM news_articles a
        INNER JOIN article_ticker_xref x ON x.article_id = a.id
        LEFT JOIN article_scores s
            ON s.article_id = a.id AND s.ticker_symbol = x.ticker_symbol
        WHERE x.ticker_symbol = :symbol
          AND a.published_at_millis = (
              SELECT MAX(a2.published_at_millis)
              FROM news_articles a2
              INNER JOIN article_ticker_xref x2 ON x2.article_id = a2.id
              WHERE x2.ticker_symbol = :symbol
                AND a2.cluster_key = a.cluster_key
          )
        GROUP BY a.cluster_key
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
               MAX(COALESCE(s.user_override, s.score)) AS score, s.category, s.reason
        FROM news_articles a
        LEFT JOIN article_scores s ON s.article_id = a.id
        WHERE a.published_at_millis = (
            SELECT MAX(a2.published_at_millis)
            FROM news_articles a2
            WHERE a2.cluster_key = a.cluster_key
        )
        GROUP BY a.cluster_key
        ORDER BY a.published_at_millis DESC
        LIMIT :limit
        """
    )
    fun observeGlobalFeed(limit: Int = 500): Flow<List<ScoredArticleRow>>

    @Query("DELETE FROM news_articles WHERE fetched_at_millis < :beforeMillis")
    suspend fun pruneOlderThan(beforeMillis: Long): Int

    /**
     * Per-ticker count of high-importance article clusters published in
     * the last [sinceMillis]. Counts distinct cluster_keys (not raw
     * articles) so the watchlist's "🔥 N" pill doesn't double-count
     * when three wires carry the same story. Uses COALESCE so the
     * user's manual override score is honored.
     */
    @Query(
        """
        SELECT s.ticker_symbol AS symbol, COUNT(DISTINCT a.cluster_key) AS count
        FROM article_scores s
        INNER JOIN news_articles a ON a.id = s.article_id
        WHERE s.ticker_symbol IN (:symbols)
          AND COALESCE(s.user_override, s.score) >= :minScore
          AND a.published_at_millis > :sinceMillis
        GROUP BY s.ticker_symbol
        """
    )
    fun observeHighScoreCounts(
        symbols: List<String>,
        minScore: Int,
        sinceMillis: Long,
    ): Flow<List<TickerCountRow>>

    /**
     * Top-scored article IDs across a set of watchlist tickers in a
     * time window. Used by the morning-brief worker to assemble the
     * podcast corpus. Cluster collapse via GROUP BY cluster_key so the
     * same story carried by three wires only contributes one article.
     * Sort: highest score first (descending), then most-recent first.
     */
    @Query(
        """
        SELECT a.id FROM news_articles a
        INNER JOIN article_ticker_xref x ON x.article_id = a.id
        INNER JOIN article_scores s
            ON s.article_id = a.id AND s.ticker_symbol = x.ticker_symbol
        WHERE x.ticker_symbol IN (:symbols)
          AND COALESCE(s.user_override, s.score) >= :minScore
          AND a.published_at_millis > :sinceMillis
        GROUP BY a.cluster_key
        ORDER BY MAX(COALESCE(s.user_override, s.score)) DESC,
                 a.published_at_millis DESC
        LIMIT :limit
        """
    )
    suspend fun topArticleIdsAcrossSymbols(
        symbols: List<String>,
        minScore: Int,
        sinceMillis: Long,
        limit: Int,
    ): List<String>

    @Query(
        """
        SELECT a.* FROM news_articles a
        INNER JOIN article_ticker_xref x ON x.article_id = a.id
        WHERE x.ticker_symbol = :symbol
        ORDER BY a.published_at_millis DESC
        LIMIT :limit
        """
    )
    suspend fun recentForTicker(symbol: String, limit: Int): List<io.itsikh.finnencer.data.entity.NewsArticle>
}
