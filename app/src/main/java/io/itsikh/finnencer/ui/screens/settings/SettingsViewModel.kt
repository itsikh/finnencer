package io.itsikh.finnencer.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.itsikh.finnencer.bugreport.GitHubIssuesClient
import io.itsikh.finnencer.data.repo.EndOfPodcastAction
import io.itsikh.finnencer.data.repo.PodcastPreferences
import io.itsikh.finnencer.data.repo.ThemePreferences
import io.itsikh.finnencer.ui.theme.ThemeId
import io.itsikh.finnencer.logging.AppLogger
import io.itsikh.finnencer.logging.DebugSettings
import io.itsikh.finnencer.logging.LogLevel
import io.itsikh.finnencer.security.SecureKeyManager
import io.itsikh.finnencer.update.AppUpdateManager
import io.itsikh.finnencer.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the generic settings screen ([SettingsScreen]).
 *
 * Manages state for:
 * - **Admin mode** — toggled by the 7-tap easter egg in [ui.components.SettingsScaffold]
 * - **Log level** — DEBUG vs WARN, persisted via [DebugSettings]
 * - **Bug button visibility** — floating bug-report FAB shown/hidden
 * - **Auto-update** — whether [AppUpdateManager] checks on launch
 * - **Auto-backup** — whether the app backs up automatically after events
 * - **GitHub token** — PAT for bug reports and update checks
 * - **App update state machine** — Idle → Checking → Available/UpToDate → Downloading → Install
 * - **Backup export state machine** — Idle → Exporting → Done/Error
 * - **Backup restore state machine** — Idle → Restoring → Done/Error
 *
 * ## Backup integration
 * The backup export ([exportBackupToUri]) and restore ([restoreFromBackup]) methods contain
 * placeholder implementations that log a warning. To wire in your app's actual data:
 *
 * 1. Inject your concrete [backup.BaseBackupManager] subclass into this ViewModel.
 * 2. In [exportBackupToUri], call `backupManager.exportToUri(uri)`.
 * 3. In [restoreFromBackup], call `backupManager.importFromUri(uri)`.
 *
 * The SAF URI passed to these methods already handles both local storage and Google Drive —
 * no special Google Drive SDK is needed. Android routes the I/O through the correct provider.
 *
 * ## App update installation
 * [downloadAndInstall] checks `canRequestPackageInstalls()` before downloading. If the
 * permission has not been granted, it opens the system settings page for the user to enable it.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val debugSettings: DebugSettings,
    private val secureKeyManager: SecureKeyManager,
    private val updateManager: AppUpdateManager,
    private val podcastPrefs: PodcastPreferences,
    private val themePrefs: ThemePreferences,
    private val jobConcurrencyPrefs: io.itsikh.finnencer.data.repo.JobConcurrencyPreferences,
    private val geminiTts: io.itsikh.finnencer.data.ai.GeminiTts,
    private val backupManager: io.itsikh.finnencer.backup.FinnencerBackupManager,
    private val retentionPrefs: io.itsikh.finnencer.data.repo.RetentionPreferences,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /**
     * Result of the "Test Vertex setup" probe in Settings → Podcasts.
     * Surfaced as a colored inline card so the user knows exactly
     * which piece of the chain (auth / IAM / model / network) is
     * broken before they kick off a multi-minute podcast generation
     * that would have failed the same way.
     */
    sealed interface VertexProbeResult {
        object Idle : VertexProbeResult
        object Running : VertexProbeResult
        data class Ok(val model: String, val elapsedMs: Long) : VertexProbeResult
        data class Failed(val message: String) : VertexProbeResult
    }

    private val _vertexProbe = MutableStateFlow<VertexProbeResult>(VertexProbeResult.Idle)
    val vertexProbe: StateFlow<VertexProbeResult> = _vertexProbe.asStateFlow()

    // ── Debug settings state ──────────────────────────────────────────────────

    val adminMode: StateFlow<Boolean> = debugSettings.adminMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val logLevel: StateFlow<LogLevel> = debugSettings.logLevel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LogLevel.INFO)

    val showBugButton: StateFlow<Boolean> = debugSettings.showBugButton
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val autoUpdateEnabled: StateFlow<Boolean> = debugSettings.autoUpdateEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val autoBackupEnabled: StateFlow<Boolean> = debugSettings.autoBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val showDiagnoseButtons: StateFlow<Boolean> = debugSettings.showDiagnoseButtons
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val endOfPodcastAction: StateFlow<EndOfPodcastAction> = podcastPrefs.endOfPodcastAction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EndOfPodcastAction.STOP)

    /** Per-minute character budget used when generating podcast scripts.
     *  Wired to [PodcastPreferences.charsPerMinute]; the settings screen
     *  exposes a stepper bounded by the prefs' min/max. */
    val podcastCharsPerMinute: StateFlow<Int> = podcastPrefs.charsPerMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PodcastPreferences.CHARS_PER_MIN_DEFAULT)

    /** Whether the validator runs between podcast script gen and TTS. */
    val podcastScriptValidationEnabled: StateFlow<Boolean> = podcastPrefs.scriptValidationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** Max characters per Gemini TTS call. Smaller = more chunks but each
     *  is faster and less likely to time out (#49 follow-up). */
    val podcastTtsChunkChars: StateFlow<Int> = podcastPrefs.ttsChunkChars
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PodcastPreferences.TTS_CHUNK_DEFAULT)

    /** Which Gemini TTS preview model to route podcast synthesis through.
     *  Selectable in Settings → Podcasts (#53 follow-up). */
    val podcastTtsModel: StateFlow<io.itsikh.finnencer.data.repo.TtsModel> = podcastPrefs.ttsModel
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            io.itsikh.finnencer.data.repo.TtsModel.GEMINI_3_1_FLASH,
        )

    /** Which Google API surface to send TTS calls to. Generative
     *  Language uses an API key (easy to set up, tight quotas);
     *  Vertex AI uses an OAuth bearer minted from a service-account
     *  JSON (more setup, but inherits the project's Vertex quota
     *  uplift). */
    val podcastTtsProvider: StateFlow<io.itsikh.finnencer.data.repo.TtsProvider> =
        podcastPrefs.ttsProvider
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                io.itsikh.finnencer.data.repo.TtsProvider.GENERATIVE_LANGUAGE,
            )

    /** Escape hatch for users hitting flaky TTS preflight on slow keys
     *  (#55). When ON the worker skips the smoke probe entirely; the
     *  in-pipeline retry loop is still in effect. */
    val podcastSkipTtsPreflight: StateFlow<Boolean> = podcastPrefs.skipTtsPreflight
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Active color theme. The settings screen exposes a swatch picker
     *  to change it; the swap happens at app root via
     *  [io.itsikh.finnencer.ui.theme.FinnencerTheme]. */
    val themeId: StateFlow<ThemeId> = themePrefs.themeId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemePreferences.DEFAULT)

    val podcastConcurrency: StateFlow<Int> = jobConcurrencyPrefs.podcastConcurrency
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

    val summaryConcurrency: StateFlow<Int> = jobConcurrencyPrefs.summaryConcurrency
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

    /** Days of cached news to keep before [core.work.SyncWorker] prunes. */
    val newsRetentionDays: StateFlow<Int> = retentionPrefs.newsRetentionDays
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            io.itsikh.finnencer.data.repo.RetentionPreferences.DEFAULT_NEWS_DAYS,
        )

    /** Days of API-usage rows to keep before [core.work.SyncWorker] prunes. */
    val apiUsageRetentionDays: StateFlow<Int> = retentionPrefs.apiUsageRetentionDays
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            io.itsikh.finnencer.data.repo.RetentionPreferences.DEFAULT_USAGE_DAYS,
        )

    // ── GitHub token ──────────────────────────────────────────────────────────

    /** `true` if a GitHub PAT is currently stored in [SecureKeyManager]. */
    val hasGitHubToken: Boolean
        get() = secureKeyManager.hasKey(GitHubIssuesClient.KEY_GITHUB_TOKEN)

    /** Saves the GitHub PAT to [SecureKeyManager]. Trims whitespace before saving. */
    fun saveGitHubToken(token: String) {
        if (token.isNotBlank()) {
            secureKeyManager.saveKey(GitHubIssuesClient.KEY_GITHUB_TOKEN, token.trim())
            AppLogger.i(TAG, "GitHub token saved")
        }
    }

    /** Removes the GitHub PAT from [SecureKeyManager]. */
    fun clearGitHubToken() {
        secureKeyManager.deleteKey(GitHubIssuesClient.KEY_GITHUB_TOKEN)
        AppLogger.i(TAG, "GitHub token cleared")
    }

    // ── Settings toggles ──────────────────────────────────────────────────────

    fun setAdminMode(enabled: Boolean) {
        viewModelScope.launch { debugSettings.setAdminMode(enabled) }
    }

    fun setDetailedLogging(enabled: Boolean) {
        viewModelScope.launch {
            debugSettings.setLogLevel(if (enabled) LogLevel.DEBUG else LogLevel.WARN)
        }
    }

    fun setShowBugButton(show: Boolean) {
        viewModelScope.launch { debugSettings.setShowBugButton(show) }
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        viewModelScope.launch { debugSettings.setAutoUpdateEnabled(enabled) }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch { debugSettings.setAutoBackupEnabled(enabled) }
    }

    fun setShowDiagnoseButtons(show: Boolean) {
        viewModelScope.launch { debugSettings.setShowDiagnoseButtons(show) }
    }

    fun setNewsRetentionDays(days: Int) {
        viewModelScope.launch { retentionPrefs.setNewsRetentionDays(days) }
    }

    fun setApiUsageRetentionDays(days: Int) {
        viewModelScope.launch { retentionPrefs.setApiUsageRetentionDays(days) }
    }

    fun setEndOfPodcastAction(value: EndOfPodcastAction) {
        viewModelScope.launch { podcastPrefs.setEndOfPodcastAction(value) }
    }

    fun setPodcastCharsPerMinute(value: Int) {
        viewModelScope.launch { podcastPrefs.setCharsPerMinute(value) }
    }

    fun setPodcastScriptValidationEnabled(value: Boolean) {
        viewModelScope.launch { podcastPrefs.setScriptValidationEnabled(value) }
    }

    fun setPodcastTtsChunkChars(value: Int) {
        viewModelScope.launch { podcastPrefs.setTtsChunkChars(value) }
    }

    fun setPodcastTtsModel(value: io.itsikh.finnencer.data.repo.TtsModel) {
        viewModelScope.launch { podcastPrefs.setTtsModel(value) }
    }

    fun setPodcastTtsProvider(value: io.itsikh.finnencer.data.repo.TtsProvider) {
        viewModelScope.launch { podcastPrefs.setTtsProvider(value) }
    }

    /**
     * Fire one tiny TTS probe through whichever provider the user has
     * picked (Generative Language or Vertex). Verifies the entire
     * chain end-to-end: credential resolution, OAuth/JWT token mint,
     * the actual `generateContent` call, and the audio coming back.
     *
     * Specifically actionable failures we map:
     *  - "Vertex auth failed" / VertexConfigError → bad SA JSON, missing
     *    Web Client ID, revoked OAuth grant, expired refresh token.
     *  - HTTP 401/403 → IAM problem (account missing aiplatform.user
     *    role on the project).
     *  - HTTP 404 → model not available on Vertex in the chosen region.
     *  - "returned no audio" → model accepted the request but the
     *    response had no inlineData (preview-TTS region restrictions).
     *
     * Bounded to 60s so a hung call doesn't leave the UI spinning
     * forever — the same envelope the podcast pipeline uses for its
     * own pre-flight check.
     */
    fun runVertexProbe() {
        if (_vertexProbe.value is VertexProbeResult.Running) return
        _vertexProbe.value = VertexProbeResult.Running
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val model = podcastPrefs.ttsModel.first().modelId
            val result = runCatching {
                geminiTts.smokeTest(model = model, timeoutMs = PROBE_TIMEOUT_MS)
            }.getOrElse { it }
            val elapsed = System.currentTimeMillis() - startedAt
            _vertexProbe.value = if (result == null) {
                VertexProbeResult.Ok(model, elapsed)
            } else {
                VertexProbeResult.Failed(
                    io.itsikh.finnencer.data.ai.FriendlyError.describe(result, stage = "Test")
                )
            }
        }
    }

    fun ackVertexProbe() { _vertexProbe.value = VertexProbeResult.Idle }

    fun setPodcastSkipTtsPreflight(value: Boolean) {
        viewModelScope.launch { podcastPrefs.setSkipTtsPreflight(value) }
    }

    fun setThemeId(id: ThemeId) {
        viewModelScope.launch { themePrefs.setThemeId(id) }
    }

    fun setPodcastConcurrency(n: Int) {
        viewModelScope.launch { jobConcurrencyPrefs.setPodcastConcurrency(n) }
    }

    fun setSummaryConcurrency(n: Int) {
        viewModelScope.launch { jobConcurrencyPrefs.setSummaryConcurrency(n) }
    }

    /** Clears the in-memory [AppLogger] buffer. Does not affect crash log files on disk. */
    fun clearAllLogs() {
        AppLogger.clear()
        AppLogger.i(TAG, "Logs cleared by user")
    }

    // ── App update state ──────────────────────────────────────────────────────

    /**
     * State machine for the update check flow.
     * Displayed by the Auto-Update card in [SettingsScreen].
     */
    sealed class UpdateState {
        object Idle : UpdateState()
        object Checking : UpdateState()
        data class UpdateAvailable(val info: UpdateInfo) : UpdateState()
        object UpToDate : UpdateState()
        object Downloading : UpdateState()
        data class ReadyToInstall(val apkPath: String) : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState

    /** Checks GitHub Releases for a newer version. Updates [updateState]. */
    fun checkForUpdate() {
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            try {
                val update = updateManager.checkForUpdate()
                _updateState.value = if (update != null) UpdateState.UpdateAvailable(update)
                                     else UpdateState.UpToDate
            } catch (e: Exception) {
                AppLogger.e(TAG, "Update check failed", e)
                _updateState.value = UpdateState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Downloads the APK for [info] and launches the system package installer.
     * If `REQUEST_INSTALL_PACKAGES` has not been granted, opens the system settings page first.
     */
    fun downloadAndInstall(info: UpdateInfo) {
        viewModelScope.launch {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                _updateState.value = UpdateState.Error(
                    "Allow installing from this source in Settings, then try again"
                )
                return@launch
            }
            _updateState.value = UpdateState.Downloading
            val apkFile = updateManager.downloadApk(info.downloadUrl)
            if (apkFile != null) {
                _updateState.value = UpdateState.ReadyToInstall(apkFile.absolutePath)
                context.startActivity(updateManager.createInstallIntent(apkFile))
            } else {
                _updateState.value = UpdateState.Error("Download failed — opening browser instead")
                context.startActivity(updateManager.createBrowserDownloadIntent())
            }
        }
    }

    fun resetUpdateState() {
        _updateState.value = UpdateState.Idle
    }

    // ── Backup export state ───────────────────────────────────────────────────

    /**
     * State machine for the manual backup export flow.
     * [Done.itemCount] is whatever integer [exportBackupToUri] returns (e.g. number of records).
     */
    sealed class ExportState {
        object Idle : ExportState()
        object Exporting : ExportState()
        data class Done(val itemCount: Int) : ExportState()
        data class Error(val message: String) : ExportState()
    }

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState

    /**
     * Exports the user's API keys + watchlist to the SAF [uri] chosen
     * via [CreateDocument], encrypted with [password]. Articles,
     * podcasts, news and other rebuildable content are intentionally
     * excluded. See [io.itsikh.finnencer.backup.FinnencerBackupManager].
     */
    fun exportBackupToUri(uri: Uri, password: String) {
        viewModelScope.launch {
            _exportState.value = ExportState.Exporting
            try {
                backupManager.exportSettingsToUri(uri, password)
                _exportState.value = ExportState.Done(itemCount = backupManager.lastCounts.total)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Export failed", e)
                _exportState.value = ExportState.Error(e.message ?: "Export failed")
            }
        }
    }

    fun resetExportState() {
        _exportState.value = ExportState.Idle
    }

    // ── Backup restore state ──────────────────────────────────────────────────

    /**
     * State machine for the backup restore flow.
     * [Done.itemCount] is whatever integer [restoreFromBackup] returns (e.g. number of records restored).
     */
    sealed class RestoreState {
        object Idle : RestoreState()
        object Restoring : RestoreState()
        data class Done(val itemCount: Int) : RestoreState()
        data class Error(val message: String) : RestoreState()
    }

    private val _restoreState = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restoreState: StateFlow<RestoreState> = _restoreState

    /**
     * Restores API keys + watchlist from the encrypted backup at the
     * SAF [uri], decrypted with [password]. Wrong password surfaces as
     * a recognizable error in [RestoreState.Error] so the UI can prompt
     * the user to re-enter it.
     */
    fun restoreFromBackup(uri: Uri, password: String) {
        viewModelScope.launch {
            _restoreState.value = RestoreState.Restoring
            try {
                backupManager.importSettingsFromUri(uri, password)
                _restoreState.value = RestoreState.Done(itemCount = backupManager.lastCounts.total)
            } catch (e: javax.crypto.AEADBadTagException) {
                AppLogger.w(TAG, "Restore failed — wrong password or tampered file")
                _restoreState.value = RestoreState.Error("Wrong password, or the backup file is damaged.")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Restore failed", e)
                _restoreState.value = RestoreState.Error(e.message ?: "Restore failed")
            }
        }
    }

    fun resetRestoreState() {
        _restoreState.value = RestoreState.Idle
    }

    companion object {
        private const val TAG = "SettingsViewModel"
        /** Envelope for the in-Settings Vertex probe. Same shape as
         *  AiJobWorker's pre-flight smoke test — long enough for
         *  Pro-tts cold starts (#55), short enough to keep the UI from
         *  hanging if a credential dead-ends. */
        private const val PROBE_TIMEOUT_MS = 60_000L
    }
}
