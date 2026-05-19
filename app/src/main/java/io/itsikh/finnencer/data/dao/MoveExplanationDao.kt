package io.itsikh.finnencer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.itsikh.finnencer.data.entity.MoveExplanation
import kotlinx.coroutines.flow.Flow

@Dao
interface MoveExplanationDao {

    @Query("SELECT * FROM move_explanation WHERE ticker = :ticker AND as_of_date = :date")
    suspend fun get(ticker: String, date: String): MoveExplanation?

    @Query("SELECT * FROM move_explanation WHERE ticker = :ticker AND as_of_date = :date")
    fun observe(ticker: String, date: String): Flow<MoveExplanation?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MoveExplanation)
}
