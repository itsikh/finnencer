package io.itsikh.finnencer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.itsikh.finnencer.data.repo.ThemePreferences
import io.itsikh.finnencer.logging.AppLogger
import io.itsikh.finnencer.logging.DebugSettings
import io.itsikh.finnencer.ui.theme.ThemeId
import io.itsikh.finnencer.update.AppUpdateManager
import io.itsikh.finnencer.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel scoped to [MainActivity] that handles the startup auto-update check.
 *
 * On init, reads [DebugSettings.autoUpdateEnabled] and — if enabled — calls
 * [AppUpdateManager.checkForUpdate] silently in the background. If a newer version is
 * found, [updatePrompt] emits [UpdatePromptState.Available] which triggers an AlertDialog
 * in [MainActivity]. The user can then approve to download-and-install, or dismiss.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val updateManager: AppUpdateManager,
    private val debugSettings: DebugSettings,
    themePreferences: ThemePreferences,
    @ApplicationContext private val context: Context
) : ViewModel() {

    sealed class UpdatePromptState {
        object None : UpdatePromptState()
        data class Available(val info: UpdateInfo) : UpdatePromptState()
        object Downloading : UpdatePromptState()
    }

    private val _updatePrompt = MutableStateFlow<UpdatePromptState>(UpdatePromptState.None)
    val updatePrompt: StateFlow<UpdatePromptState> = _updatePrompt

    /** Current color theme. Wraps [ThemePreferences.themeId] so
     *  MainActivity can collect it and hand a fresh value to
     *  [io.itsikh.finnencer.ui.theme.FinnencerTheme] each composition. */
    val themeId: StateFlow<ThemeId> = themePreferences.themeId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemePreferences.DEFAULT)

    init {
        viewModelScope.launch {
            val enabled = debugSettings.autoUpdateEnabled.first()
            if (enabled) {
                runStartupUpdateCheck()
            }
        }
    }

    private suspend fun runStartupUpdateCheck() {
        try {
            val update = updateManager.checkForUpdate()
            if (update != null) {
                _updatePrompt.value = UpdatePromptState.Available(update)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Startup update check failed: ${e.message}")
        }
    }

    fun downloadAndInstall(info: UpdateInfo) {
        viewModelScope.launch {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                _updatePrompt.value = UpdatePromptState.None
                return@launch
            }
            _updatePrompt.value = UpdatePromptState.Downloading
            val apkFile = updateManager.downloadApk(info.downloadUrl)
            if (apkFile != null) {
                context.startActivity(updateManager.createInstallIntent(apkFile))
            } else {
                context.startActivity(updateManager.createBrowserDownloadIntent())
            }
            _updatePrompt.value = UpdatePromptState.None
        }
    }

    fun dismissUpdatePrompt() {
        _updatePrompt.value = UpdatePromptState.None
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
