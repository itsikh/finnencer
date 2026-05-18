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

    /**
     * Reset a finished (failed / completed / canceled) job back to
     * QUEUED so a fresh worker run can pick it up. Clears the timing
     * + result-display fields so the Tasks UI doesn't show stale
     * "failed at" info while it's retrying.
     *
     * `resultRefId` is intentionally PRESERVED so the worker can find
     * any in-flight artifact (e.g. a FAILED podcast row from a prior
     * attempt) and reuse it instead of inserting a duplicate (#39).
     * The Tasks UI gates the "open" affordance on `resultKind` (which
     * we still clear), so a kept refId without a kind stays inert.
     */
    @Query(
        """
        UPDATE ai_jobs
        SET status = 'QUEUED',
            errorMessage = NULL,
            startedAtMillis = NULL,
            completedAtMillis = NULL,
            resultKind = NULL,
            resultText = NULL,
            resultModel = NULL
        WHERE id = :id
        """
    )
    suspend fun markQueued(id: String)

    /**
     * Persist the in-flight artifact id (e.g. a Podcast row id) as soon
     * as the worker creates or reuses one. Lets a retry after a network
     * failure find and overwrite the same podcast row instead of
     * leaving an orphan + creating a fresh one (#39).
     */
    @Query("UPDATE ai_jobs SET resultRefId = :refId WHERE id = :id")
    suspend fun setResultRefId(id: String, refId: String?)

    @Query("DELETE FROM ai_jobs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM ai_jobs WHERE status IN ('COMPLETED','FAILED','CANCELED')")
    suspend fun clearFinished()
}
