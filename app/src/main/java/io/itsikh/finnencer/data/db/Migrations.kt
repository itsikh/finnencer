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

/**
 * v8 → v9: introduces `ticker_metrics` (current fundamentals snapshot
 * per ticker — 52w range, P/E, EPS, market cap, beta, dividend yield,
 * etc.) and `ticker_metrics_analysis` (per-day AI interpretation of
 * those numbers). Fundamentals are fetched on-demand from Finnhub and
 * refreshed daily; the analysis is keyed by ET calendar date.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ticker_metrics` (" +
                "`ticker` TEXT NOT NULL, " +
                "`fetched_at_millis` INTEGER NOT NULL, " +
                "`fifty_two_week_high` REAL, " +
                "`fifty_two_week_low` REAL, " +
                "`fifty_two_week_high_date` TEXT, " +
                "`fifty_two_week_low_date` TEXT, " +
                "`market_cap` REAL, " +
                "`pe_ttm` REAL, " +
                "`pe_normalized` REAL, " +
                "`eps_ttm` REAL, " +
                "`eps_normalized` REAL, " +
                "`beta` REAL, " +
                "`div_yield` REAL, " +
                "`div_per_share` REAL, " +
                "`avg_vol_10d` REAL, " +
                "`avg_vol_3m` REAL, " +
                "`rev_growth_yoy` REAL, " +
                "`price_to_sales` REAL, " +
                "PRIMARY KEY(`ticker`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ticker_metrics_analysis` (" +
                "`ticker` TEXT NOT NULL, " +
                "`as_of_date` TEXT NOT NULL, " +
                "`analysis` TEXT NOT NULL, " +
                "`model` TEXT NOT NULL, " +
                "`generated_at_millis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`ticker`, `as_of_date`))"
        )
    }
}

/**
 * v9 → v10: adds the podcast script validation surface — a validator
 * runs between script gen and TTS, producing notes (and either an
 * unchanged or rewritten script). If the validator FAILs the script,
 * the row enters PENDING_REVIEW; the user can flip `force_accept_script`
 * via the Tasks UI to bypass validation on the next worker run.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE podcasts ADD COLUMN validation_notes TEXT")
        db.execSQL("ALTER TABLE podcasts ADD COLUMN validation_model TEXT")
        db.execSQL("ALTER TABLE podcasts ADD COLUMN force_accept_script INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v10 → v11: raises the default notification threshold floor from 7 to
 * 8 for every existing ticker. The default in [Ticker] also moves to 8
 * so newly added tickers start at the new floor. Users who explicitly
 * picked 9 or 10 keep their setting; users on the old default of 7 (or
 * any value below 8) are pulled up to 8.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE tickers SET notification_threshold = 8 WHERE notification_threshold < 8")
    }
}

/**
 * v11 → v12: introduces `ticker_analyst_snapshot` — a daily-refreshed
 * cache of Finnhub price-target + recommendation-trends per ticker. Lets
 * ReportGenerator skip a re-fetch on regenerate and unlocks the
 * watchlist "analyst PT vs current quote" pill without extra calls.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ticker_analyst_snapshot` (" +
                "`ticker` TEXT NOT NULL, " +
                "`fetched_at_millis` INTEGER NOT NULL, " +
                "`target_high` REAL, " +
                "`target_low` REAL, " +
                "`target_mean` REAL, " +
                "`target_median` REAL, " +
                "`last_updated` TEXT, " +
                "`recommendation_trends_json` TEXT NOT NULL, " +
                "PRIMARY KEY(`ticker`))"
        )
    }
}

/**
 * v12 → v13: adds `fiscal_confirmed` to earnings_events. EDGAR seeds rows
 * with a calendar-quarter guess that's wrong for offset fiscal years; this
 * flag marks rows whose fiscal label was confirmed by the fiscal-aware
 * Finnhub sync, so the UI only ever displays labels we trust (#70).
 * Existing rows default to 0 (unconfirmed) and get re-confirmed on the
 * next Finnhub earnings sync.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE earnings_events ADD COLUMN fiscal_confirmed INTEGER NOT NULL DEFAULT 0")
    }
}

/** All migrations in version order. Add new ones at the end. */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
    MIGRATION_8_9,
    MIGRATION_9_10,
    MIGRATION_10_11,
    MIGRATION_11_12,
    MIGRATION_12_13,
)
