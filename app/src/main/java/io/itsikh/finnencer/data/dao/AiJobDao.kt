package io.itsikh.finnencer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.itsikh.finnencer.data.entity.AiJob
import kotlinx.coroutines.flow.Flow

@Dao
interface AiJobDao {

    @Query("SELECT * FROM ai_jobs ORDER BY createdAtMillis DESC LIMIT 200")
    fun observeAll(): Flow<List<AiJob>>

    @Query("SELECT COUNT(*) FROM ai_jobs WHERE status IN ('QUEUED','RUNNING')")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT * FROM ai_jobs WHERE id = :id")
    suspend fun get(id: String): AiJob?

    @Query("SELECT * FROM ai_jobs WHERE id = :id")
    fun observe(id: String): Flow<AiJob?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: AiJob)

    @Query(
        """
        UPDATE ai_jobs
        SET status = :status,
            startedAtMillis = COALESCE(startedAtMillis, :nowMs)
        WHERE id = :id
        """
    )
    suspend fun markRunning(id: String, status: String, nowMs: Long)

    @Query(
        """
        UPDATE ai_jobs
        SET status = :status,
            resultKind = :resultKind,
            resultRefId = :resultRefId,
            resultText = :resultText,
            resultModel = :resultModel,
            errorMessage = NULL,
            completedAtMillis = :nowMs
        WHERE id = :id
        """
    )
    suspend fun markCompleted(
        id: String,
        status: String,
        resultKind: String,
        resultRefId: String?,
        resultText: String?,
        resultModel: String?,
        nowMs: Long,
    )

    @Query(
        """
        UPDATE ai_jobs
        SET status = :status,
            errorMessage = :errorMessage,
            completedAtMillis = :nowMs
        WHERE id = :id
        """
    )
    suspend fun markFailed(id: String, status: String, errorMessage: String, nowMs: Long)

    @Query("DELETE FROM ai_jobs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM ai_jobs WHERE status IN ('COMPLETED','FAILED','CANCELED')")
    suspend fun clearFinished()
}
