package io.itsikh.finnencer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.itsikh.finnencer.data.entity.Podcast
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(podcast: Podcast): Long

    @Update
    suspend fun update(podcast: Podcast)

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun get(id: Long): Podcast?

    @Query("SELECT * FROM podcasts WHERE id = :id")
    fun observe(id: Long): Flow<Podcast?>

    @Query("SELECT * FROM podcasts ORDER BY created_at_millis DESC")
    fun observeAll(): Flow<List<Podcast>>

    @Query("UPDATE podcasts SET play_position_ms = :positionMs, last_played_at_millis = :now WHERE id = :id")
    suspend fun updatePosition(id: Long, positionMs: Long, now: Long)

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun delete(id: Long)
}
