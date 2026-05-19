package io.itsikh.finnencer.ui.screens.podcast

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.data.dao.PodcastDao
import io.itsikh.finnencer.data.entity.Podcast
import io.itsikh.finnencer.data.entity.PodcastGenerationStatus
import io.itsikh.finnencer.data.entity.QueueItemKind
import io.itsikh.finnencer.logging.AppLogger
import io.itsikh.finnencer.share.ShareHelpers
import io.itsikh.finnencer.share.WavToM4a
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.components.QueueToggleIconButton
import io.itsikh.finnencer.ui.theme.FinnencerColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PodcastLibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val podcastDao: PodcastDao,
    private val viewModePrefs: io.itsikh.finnencer.data.repo.ViewModePreferences,
) : ViewModel() {

    val podcasts: StateFlow<List<Podcast>> = podcastDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groupedByTicker: StateFlow<Boolean> = viewModePrefs.podcastsGrouped
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setGroupedByTicker(value: Boolean) {
        viewModelScope.launch { viewModePrefs.setPodcastsGrouped(value) }
    }

    private val _sharingId = MutableStateFlow<Long?>(null)
    /** Non-null while an m4a transcode is in flight; renders a spinner on the row. */
    val sharingId: StateFlow<Long?> = _sharingId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun clearError() { _error.value = null }

    fun delete(podcast: Podcast) {
        viewModelScope.launch {
            deleteFilesFor(podcast)
            podcastDao.delete(podcast.id)
        }
    }

    /**
     * Bulk-delete every FAILED podcast row (and its file if any) — the
     * escape hatch for #39 "spam bug" when prior retries left a pile of
     * failed rows in the library.
     */
    fun clearAllFailed() {
        viewModelScope.launch {
            val failed = podcasts.value.filter {
                it.status == PodcastGenerationStatus.FAILED.name
            }
            failed.forEach { p ->
                deleteFilesFor(p)
                podcastDao.delete(p.id)
            }
        }
    }

    /**
     * Delete every on-disk artifact tied to [podcast]: the final WAV (if
     * any), plus the per-podcast TTS resume cache dir (#42 — would
     * otherwise grow forever as failed-then-deleted podcasts leave
     * orphaned chunk PCMs behind).
     */
    private fun deleteFilesFor(podcast: Podcast) {
        podcast.filePath?.let { path ->
            runCatching { File(path).takeIf { it.exists() }?.delete() }
        }
        val cacheDir = File(context.filesDir, "podcasts/cache/podcast_${podcast.id}")
        runCatching {
            if (cacheDir.exists()) {
                cacheDir.listFiles()?.forEach { it.delete() }
                cacheDir.delete()
            }
        }
    }

    /**
     * Transcode the WAV to a compressed .m4a (~1 MB / min) then fire the
     * share sheet. Sharing the raw WAV would mean ~25 MB per 10-min
     * podcast — most messengers reject files that size.
     */
    fun share(podcast: Podcast) {
        val srcPath = podcast.filePath ?: run {
            _error.value = "Podcast file not ready yet."
            return
        }
        val src = File(srcPath)
        if (!src.exists()) {
            _error.value = "Podcast file is missing — re-generate it."
            return
        }
        if (_sharingId.value != null) return // one transcode at a time
        _sharingId.value = podcast.id
        viewModelScope.launch {
            try {
                val out = File(context.cacheDir, "share").apply { mkdirs() }
                val safeName = podcast.title.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)
                val dst = File(out, safeName.ifBlank { "podcast" } + ".m4a")
                runCatching { dst.delete() }
                WavToM4a.transcode(src, dst)
                ShareHelpers.shareFile(
                    context = context,
                    file = dst,
                    mime = "audio/mp4",
                    subject = podcast.title,
                )
            } catch (t: Throwable) {
                AppLogger.e(TAG, "podcast share failed for id=${podcast.id}", t)
                _error.value = t.message ?: "Share failed"
            } finally {
                _sharingId.value = null
            }
        }
    }

    private companion object { const val TAG = "PodcastLibraryVM" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastLibraryScreen(
    onBack: () -> Unit,
    onOpenPodcast: (Long) -> Unit,
) {
    val vm: PodcastLibraryViewModel = hiltViewModel()
    val items by vm.podcasts.collectAsState()
    val sharingId by vm.sharingId.collectAsState()
    val error by vm.error.collectAsState()

    var pendingDelete by remember { mutableStateOf<Podcast?>(null) }
    var clearFailedConfirm by remember { mutableStateOf(false) }
    val failedCount = items.count { it.status == PodcastGenerationStatus.FAILED.name }
    val grouped by vm.groupedByTicker.collectAsState()
    val expandedGroups = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Podcasts",
                        style = MaterialTheme.typography.headlineMedium,
                        color = FinnencerColors.TextPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = FinnencerColors.TextPrimary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.setGroupedByTicker(!grouped) }) {
                        Icon(
                            if (grouped) androidx.compose.material.icons.Icons.Default.Summarize
                            else androidx.compose.material.icons.Icons.Default.Bookmark,
                            contentDescription = if (grouped) "Switch to flat list" else "Group by ticker",
                            tint = if (grouped) FinnencerColors.Violet else FinnencerColors.TextSecondary,
                        )
                    }
                    if (failedCount > 0) {
                        TextButton(onClick = { clearFailedConfirm = true }) {
                            Text(
                                "Clear failed · $failedCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = FinnencerColors.Coral,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (items.isEmpty()) {
                item {
                    Text(
                        "No podcasts yet. Generate one from an earnings report.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FinnencerColors.TextTertiary,
                    )
                }
            }
            if (grouped && items.isNotEmpty()) {
                val buckets = items.groupBy { p ->
                    io.itsikh.finnencer.ui.components.tickerFromPodcastTitle(p.title)
                }
                val tickers = io.itsikh.finnencer.ui.components.sortedTickerGroups(buckets.keys)
                for (ticker in tickers) {
                    val bucket = buckets[ticker].orEmpty()
                    item(key = "header-$ticker") {
                        io.itsikh.finnencer.ui.components.TickerGroupHeader(
                            ticker = ticker,
                            count = bucket.size,
                            expanded = expandedGroups[ticker] ?: true,
                            onToggle = {
                                val curr = expandedGroups[ticker] ?: true
                                expandedGroups[ticker] = !curr
                            },
                        )
                    }
                    val expanded = expandedGroups[ticker] ?: true
                    if (expanded) {
                        items(bucket, key = { it.id }) { p ->
                            PodcastRow(
                                podcast = p,
                                isSharing = sharingId == p.id,
                                onOpen = onOpenPodcast,
                                onShare = { vm.share(p) },
                                onDelete = { pendingDelete = p },
                            )
                        }
                    }
                }
            } else if (!grouped) {
                items(items, key = { it.id }) { p ->
                    PodcastRow(
                        podcast = p,
                        isSharing = sharingId == p.id,
                        onOpen = onOpenPodcast,
                        onShare = { vm.share(p) },
                        onDelete = { pendingDelete = p },
                    )
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    if (clearFailedConfirm) {
        AlertDialog(
            onDismissRequest = { clearFailedConfirm = false },
            title = { Text("Clear $failedCount failed podcast${if (failedCount == 1) "" else "s"}?") },
            text = {
                Text(
                    "Removes every podcast in the failed state. Their audio files (if any) are deleted from this device. This can't be undone.",
                    color = FinnencerColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAllFailed()
                    clearFailedConfirm = false
                }) { Text("Clear", color = FinnencerColors.Coral) }
            },
            dismissButton = {
                TextButton(onClick = { clearFailedConfirm = false }) { Text("Cancel") }
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete podcast?") },
            text = {
                Text(
                    "“${target.title}” will be removed from your library and the audio file deleted from this device. This can't be undone.",
                    color = FinnencerColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(target)
                    pendingDelete = null
                }) { Text("Delete", color = FinnencerColors.Coral) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text("Couldn't share") },
            text = { Text(msg, color = FinnencerColors.TextSecondary) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } },
        )
    }
}

@Composable
private fun PodcastRow(
    podcast: Podcast,
    isSharing: Boolean,
    onOpen: (Long) -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val isReady = podcast.status == PodcastGenerationStatus.READY.name
    GlassCard(onClick = { onOpen(podcast.id) }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    podcast.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Row {
                    Text(
                        "${podcast.voiceHost} · ${podcast.voiceAnalyst ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = FinnencerColors.TextTertiary,
                    )
                    podcast.durationMs?.let { ms ->
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(
                            "  ·  ${ms / 1000 / 60}:${"%02d".format((ms / 1000) % 60)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.TextTertiary,
                        )
                    }
                }
                if (!isReady) {
                    Text(
                        podcast.status.lowercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (podcast.status == PodcastGenerationStatus.FAILED.name) FinnencerColors.Coral else FinnencerColors.Violet,
                    )
                }
            }
            if (isReady) {
                QueueToggleIconButton(
                    kind = QueueItemKind.PODCAST,
                    refId = podcast.id.toString(),
                    title = podcast.title,
                    subtitle = podcast.durationMs?.let { "${it / 1000 / 60} min" },
                    modifier = Modifier.size(36.dp),
                )
                IconButton(onClick = onShare, enabled = !isSharing, modifier = Modifier.size(36.dp)) {
                    if (isSharing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = FinnencerColors.Violet,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share podcast",
                            tint = FinnencerColors.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete podcast",
                    tint = FinnencerColors.TextTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
