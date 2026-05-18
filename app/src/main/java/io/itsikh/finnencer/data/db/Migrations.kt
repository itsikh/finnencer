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

/** All migrations in version order. Add new ones at the end. */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_4_5,
    MIGRATION_5_6,
)
