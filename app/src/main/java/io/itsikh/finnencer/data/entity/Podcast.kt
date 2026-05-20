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
enum class PodcastGenerationStatus {
    PENDING,
    GENERATING,
    /** Waiting indefinitely until the AI endpoints (Anthropic, Gemini)
     *  become reachable. The worker is alive — see the foreground
     *  notification — but no LLM/TTS calls are being made yet (#43). */
    WAITING_FOR_NETWORK,
    /**
     * Validator flagged the script as broken (mid-script re-intro,
     * missing required section, fabricated facts, unparseable output).
     * The script is persisted; the user reviews it in the Tasks tab
     * and either taps "Proceed anyway" (skip validation, force TTS)
     * or "Cancel" (mark FAILED). Distinct from FAILED so a hard error
     * is still distinguishable from a soft "needs human eyes" stop.
     */
    PENDING_REVIEW,
    READY,
    FAILED,
}

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
    /**
     * The fully-assembled Host/Analyst dialogue script. Persisted as soon
     * as the script step completes so that a TTS-stage failure can be
     * retried without re-billing the script LLM, AND so the user can
     * still read the text even if audio rendering never succeeds (#42).
     */
    @ColumnInfo(name = "script_text") val scriptText: String? = null,
    /** Validator's free-text notes about what it checked / changed /
     *  flagged. Null until the validator stage has run. */
    @ColumnInfo(name = "validation_notes") val validationNotes: String? = null,
    /** Which model produced [validationNotes]. */
    @ColumnInfo(name = "validation_model") val validationModel: String? = null,
    /** Set true when the user taps "Proceed anyway" from a PENDING_REVIEW
     *  row. The next worker run skips the validator and goes straight to
     *  TTS using [scriptText]. */
    @ColumnInfo(name = "force_accept_script") val forceAcceptScript: Boolean = false,
)
