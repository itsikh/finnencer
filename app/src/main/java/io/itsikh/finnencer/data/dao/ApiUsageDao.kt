package io.itsikh.finnencer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.itsikh.finnencer.data.entity.ApiUsage
import kotlinx.coroutines.flow.Flow

data class ProviderUsageRow(
    val provider: String,
    val calls: Int,
    val tokens_in: Int,
    val tokens_out: Int,
    val cost_millicents: Long,
)

@Dao
interface ApiUsageDao {

    @Insert
    suspend fun insert(usage: ApiUsage): Long

    @Query("SELECT * FROM api_usage ORDER BY requested_at_millis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<ApiUsage>>

    @Query(
        """
        SELECT provider,
               COUNT(*) AS calls,
               COALESCE(SUM(input_tokens), 0) AS tokens_in,
               COALESCE(SUM(output_tokens), 0) AS tokens_out,
               COALESCE(SUM(cost_millicents), 0) AS cost_millicents
        FROM api_usage
        WHERE requested_at_millis >= :sinceMillis
        GROUP BY provider
        ORDER BY cost_millicents DESC
        """
    )
    fun observeRollupSince(sinceMillis: Long): Flow<List<ProviderUsageRow>>

    @Query("DELETE FROM api_usage WHERE requested_at_millis < :beforeMillis")
    suspend fun pruneOlderThan(beforeMillis: Long): Int
}
