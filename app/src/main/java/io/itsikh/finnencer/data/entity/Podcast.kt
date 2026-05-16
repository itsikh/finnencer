package io.itsikh.finnencer.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Generated podcast episode. Schema is defined now even though the
 * generation pipeline (Build B) is not yet implemented, so we don't migrate
 * the DB later when Build B lands.
 *
 * `sourceType` / `sourceId` identifies what was converted (an
 * EarningsReport, NewsArticle, or arbitrary user text).
 */
enum class PodcastSourceType { REPORT, ARTICLE, CUSTOM_TEXT }
enum class PodcastGenerationStatus { PENDING, GENERATING, READY, FAILED }

@Entity(tableName = "podcasts")
data class Podcast(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_type") val sourceType: String, // PodcastSourceType.name
    @ColumnInfo(name = "source_id") val sourceId: String,
    val title: String,
    @ColumnInfo(name = "voice_host") val voiceHost: String,
    @ColumnInfo(name = "voice_analyst") val voiceAnalyst: String?, // null for monologue
    @ColumnInfo(name = "file_path") val filePath: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "character_count") val characterCount: Int,
    val status: String, // PodcastGenerationStatus.name
    @ColumnInfo(name = "generation_error") val generationError: String? = null,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
    @ColumnInfo(name = "play_position_ms") val playPositionMs: Long = 0,
    @ColumnInfo(name = "last_played_at_millis") val lastPlayedAtMillis: Long? = null,
)
