package io.itsikh.finnencer.data.repo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.logging.AppLogger
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Belt-and-suspenders backup of the watchlist as a flat JSON file in
 * the app's internal storage. Survives even a catastrophic database
 * wipe (e.g. an accidental destructive Room migration), because the
 * file lives outside the SQLite DB.
 *
 * The file is rewritten after every mutation in [WatchlistRepository]
 * and also refreshed on app startup once the DB is verified healthy.
 * If the DB is observed empty but a snapshot exists, [WatchlistRestorer]
 * re-populates the table.
 *
 * History: v0.0.30 bumped the Room schema with
 * `fallbackToDestructiveMigration()` enabled and wiped every user's
 * watchlist. The migration policy has since been fixed (see
 * [io.itsikh.finnencer.data.db.Migrations]); this snapshot exists so
 * that even a future migration mistake can't lose the watchlist again.
 */
@Singleton
class WatchlistSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private val gson = Gson()
    private val listType = object : TypeToken<List<Ticker>>() {}.type

    fun hasSnapshot(): Boolean = file.exists() && file.length() > 0

    fun load(): List<Ticker> {
        if (!hasSnapshot()) return emptyList()
        return runCatching {
            gson.fromJson<List<Ticker>>(file.readText(), listType) ?: emptyList()
        }.getOrElse {
            AppLogger.e(TAG, "Failed to read watchlist snapshot — ignoring", it)
            emptyList()
        }
    }

    fun save(tickers: List<Ticker>) {
        // Don't overwrite a good snapshot with an empty list — would
        // erase the very safety net we're trying to provide if the DB
        // ever transiently returns 0 rows.
        if (tickers.isEmpty() && hasSnapshot()) return
        runCatching {
            file.writeText(gson.toJson(tickers))
        }.onFailure {
            AppLogger.e(TAG, "Failed to write watchlist snapshot", it)
        }
    }

    private companion object {
        const val FILE_NAME = "watchlist_snapshot.json"
        const val TAG = "WatchlistSnapshot"
    }
}

/**
 * Startup-time recovery. Called from [io.itsikh.finnencer.TemplateApplication]
 * once Hilt is ready. Restores tickers from the snapshot file when the
 * `tickers` table is empty but a snapshot exists; otherwise refreshes
 * the snapshot from the current DB contents.
 */
@Singleton
class WatchlistRestorer @Inject constructor(
    private val tickerDao: TickerDao,
    private val snapshotStore: WatchlistSnapshotStore,
) {
    suspend fun ensureRestored() {
        val current = tickerDao.getAll()
        if (current.isNotEmpty()) {
            snapshotStore.save(current)
            return
        }
        val saved = snapshotStore.load()
        if (saved.isEmpty()) return
        AppLogger.w(
            TAG,
            "Tickers table empty but snapshot has ${saved.size} entries — restoring",
        )
        saved.forEach { tickerDao.upsert(it) }
    }

    private companion object { const val TAG = "WatchlistRestorer" }
}
