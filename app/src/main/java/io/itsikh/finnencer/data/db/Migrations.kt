package io.itsikh.finnencer.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations for [FinnencerDatabase].
 *
 * Every schema bump MUST add a [Migration] here and register it in
 * [io.itsikh.finnencer.data.di.DatabaseModule]. The previous policy of
 * `fallbackToDestructiveMigration()` wiped user data (notably the
 * watchlist) on every schema change — we explicitly don't do that
 * anymore.
 *
 * DDL is copied verbatim from what Room's KSP-generated
 * `FinnencerDatabase_Impl.createAllTables` emits, so the resulting
 * schema hashes identically to what Room expects post-migration.
 */

/**
 * v4 → v5: introduces the `queue_items` table for the reading /
 * listening queue (#27, shipped in v0.0.30).
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `queue_items` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`ref_id` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`subtitle` TEXT, " +
                "`ticker_symbol` TEXT, " +
                "`sort_order` INTEGER NOT NULL, " +
                "`added_at_millis` INTEGER NOT NULL, " +
                "`completed_at_millis` INTEGER)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_queue_items_kind` ON `queue_items` (`kind`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_queue_items_completed_at_millis` ON `queue_items` (`completed_at_millis`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_queue_items_sort_order` ON `queue_items` (`sort_order`)")
    }
}

/**
 * v5 → v6: adds `script_text` to `podcasts` so the dialogue script is
 * persisted as soon as the LLM returns it. Lets a TTS-stage failure
 * retry without re-billing the script step, and gives the user a
 * "read the script" fallback when audio rendering keeps failing (#42).
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE podcasts ADD COLUMN script_text TEXT")
    }
}

/**
 * v6 → v7: adds per-job progress tracking to `ai_jobs` so the Tasks
 * page + Task Detail screen can surface "what step is this on, what
 * percent done, what's it doing right now" in real time (#43).
 *
 * `stage_progress` defaults to 0; the other two are nullable.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ai_jobs ADD COLUMN currentStage TEXT")
        db.execSQL("ALTER TABLE ai_jobs ADD COLUMN stageProgress INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE ai_jobs ADD COLUMN stageDetail TEXT")
    }
}

/**
 * v7 → v8: introduces the `move_explanation` table for the per-ticker
 * "Why is it moving?" AI explanations, cached one row per (ticker,
 * trading-day) so re-opens the same day are free.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `move_explanation` (" +
                "`ticker` TEXT NOT NULL, " +
                "`as_of_date` TEXT NOT NULL, " +
                "`pct_change` REAL NOT NULL, " +
                "`explanation` TEXT NOT NULL, " +
                "`model` TEXT NOT NULL, " +
                "`article_ids_csv` TEXT NOT NULL, " +
                "`generated_at_millis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`ticker`, `as_of_date`))"
        )
    }
}

/** All migrations in version order. Add new ones at the end. */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
)
