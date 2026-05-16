package io.itsikh.finnencer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.itsikh.finnencer.data.entity.NotificationLog
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: NotificationLog): Long

    @Query("SELECT * FROM notifications ORDER BY sent_at_millis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<NotificationLog>>

    @Query(
        """
        SELECT COUNT(*) FROM notifications
        WHERE ticker_symbol = :symbol AND sent_at_millis >= :sinceMillis
        """
    )
    suspend fun countSinceForTicker(symbol: String, sinceMillis: Long): Int

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM notifications n
            INNER JOIN news_articles a ON a.id = n.article_id
            WHERE a.cluster_key = :clusterKey AND n.sent_at_millis >= :sinceMillis
        )
        """
    )
    suspend fun clusterAlreadyNotified(clusterKey: String, sinceMillis: Long): Boolean

    @Query("UPDATE notifications SET tapped_at_millis = :now WHERE id = :id")
    suspend fun markTapped(id: Long, now: Long)

    @Query("UPDATE notifications SET dismissed_at_millis = :now WHERE id = :id")
    suspend fun markDismissed(id: Long, now: Long)
}
