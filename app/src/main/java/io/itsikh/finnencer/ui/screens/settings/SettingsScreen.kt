package io.itsikh.finnencer.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.AppConfig
import io.itsikh.finnencer.BuildConfig
import io.itsikh.finnencer.data.repo.ApiKey
import io.itsikh.finnencer.data.repo.ApiKeysRepository
import io.itsikh.finnencer.ui.screens.bugreport.ReportMode
import io.itsikh.finnencer.ui.theme.FinnencerColors

/**
 * Finnencer's single Settings hub.
 *
 * Sectioned Glass-Modern layout that wraps the template's
 * [SettingsViewModel] for Auto-Update + Backup logic, and surfaces
 * everything else the user might need to configure or diagnose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenBugReport: (ReportMode) -> Unit,
    onOpenKeys: () -> Unit = {},
    onOpenCost: () -> Unit = {},
    onOpenPodcasts: () -> Unit = {},
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val keysRepo: ApiKeysRepository = hiltViewModel<ApiKeysHolderViewModel>().repo

    val autoUpdate by viewModel.autoUpdateEnabled.collectAsState()
    val autoBackup by viewModel.autoBackupEnabled.collectAsState()
    val showBugButton by viewModel.showBugButton.collectAsState()
    val adminMode by viewModel.adminMode.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val restoreState by viewModel.restoreState.collectAsState()
    val configuredMap by keysRepo.configured.collectAsState()
    val keysConfigured = configuredMap.count { it.value }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? -> if (uri != null) viewModel.exportBackupToUri(uri) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? -> if (uri != null) viewModel.restoreFromBackup(uri) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium, color = FinnencerColors.TextPrimary)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = FinnencerColors.TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {

            // ───────── Credentials ─────────
            SettingsSection(title = "Credentials") {
                SettingsRow(
                    title = "API Keys",
                    subtitle = "$keysConfigured of ${ApiKey.entries.size} configured · Claude · Finnhub · Gemini · GitHub · EDGAR",
                    icon = Icons.Default.VpnKey,
                    onClick = onOpenKeys,
                )
            }

            // ───────── App ─────────
            SettingsSection(title = "App") {
                AutoUpdateRow(
                    enabled = autoUpdate,
                    updateState = updateState,
                    onToggle = viewModel::setAutoUpdateEnabled,
                    onCheckNow = viewModel::checkForUpdate,
                    onInstall = viewModel::downloadAndInstall,
                    onReset = viewModel::resetUpdateState,
                )
                SettingsRow(
                    title = "Cost meter",
                    subtitle = "Per-provider API spend (Anthropic / Gemini)",
                    icon = Icons.Default.AttachMoney,
                    iconTint = FinnencerColors.Mint,
                    onClick = onOpenCost,
                )
                SettingsRow(
                    title = "Podcasts library",
                    subtitle = "Generated podcasts and playback history",
                    icon = Icons.Default.Headphones,
                    iconTint = FinnencerColors.Amber,
                    onClick = onOpenPodcasts,
                )
            }

            // ───────── Backup & Restore ─────────
            SettingsSection(title = "Backup & Restore") {
                SettingsRow(
                    title = "Auto-backup",
                    subtitle = "Back up the local DB after every sync cycle",
                    icon = Icons.Default.Storage,
                    iconTint = FinnencerColors.Violet,
                    trailing = {
                        Switch(
                            checked = autoBackup,
                            onCheckedChange = viewModel::setAutoBackupEnabled,
                            colors = switchColors(),
                        )
                    },
                )
                SettingsRow(
                    title = "Export backup…",
                    subtitle = backupSubtitle(exportState, restoreState, isExport = true),
                    icon = Icons.Default.CloudUpload,
                    iconTint = FinnencerColors.Mint,
                    onClick = { exportLauncher.launch("finnencer-backup.zip") },
                )
                SettingsRow(
                    title = "Restore from backup…",
                    subtitle = backupSubtitle(exportState, restoreState, isExport = false),
                    icon = Icons.Default.RestoreFromTrash,
                    iconTint = FinnencerColors.Amber,
                    onClick = { restoreLauncher.launch("application/zip") },
                )
            }

            // ───────── Diagnostics ─────────
            SettingsSection(title = "Diagnostics") {
                SettingsRow(
                    title = "Report a bug",
                    subtitle = "Files a GitHub issue with device + log info",
                    icon = Icons.Default.BugReport,
                    iconTint = FinnencerColors.Coral,
                    onClick = { onOpenBugReport(ReportMode.BUG_REPORT) },
                )
                SettingsRow(
                    title = "Send feedback",
                    subtitle = "Suggestion or feature request",
                    icon = Icons.Default.Info,
                    onClick = { onOpenBugReport(ReportMode.USER_FEEDBACK) },
                )
                if (adminMode) {
                    SettingsRow(
                        title = "Floating bug button",
                        subtitle = "Drag-and-drop FAB visible across screens",
                        icon = Icons.Default.BugReport,
                        trailing = {
                            Switch(
                                checked = showBugButton,
                                onCheckedChange = viewModel::setShowBugButton,
                                colors = switchColors(),
                            )
                        },
                    )
                    SettingsRow(
                        title = "Clear all logs",
                        icon = Icons.Default.DeleteSweep,
                        iconTint = FinnencerColors.Coral,
                        onClick = viewModel::clearAllLogs,
                    )
                }
            }

            // ───────── About ─────────
            AboutSection(adminMode = adminMode, onTapVersion = { viewModel.setAdminMode(!adminMode) })

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun AutoUpdateRow(
    enabled: Boolean,
    updateState: SettingsViewModel.UpdateState,
    onToggle: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onInstall: (io.itsikh.finnencer.update.UpdateInfo) -> Unit,
    onReset: () -> Unit,
) {
    SettingsRow(
        title = "Auto-update",
        subtitle = updateSubtitle(updateState),
        icon = Icons.Default.SystemUpdate,
        iconTint = FinnencerColors.Mint,
        trailing = {
            Switch(checked = enabled, onCheckedChange = onToggle, colors = switchColors())
        },
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (updateState) {
            is SettingsViewModel.UpdateState.Idle, is SettingsViewModel.UpdateState.UpToDate -> {
                FilledTonalButton(
                    onClick = onCheckNow,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FinnencerColors.SurfaceGlass,
                        contentColor = FinnencerColors.TextPrimary,
                    ),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Check now")
                }
            }
            is SettingsViewModel.UpdateState.Checking -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = FinnencerColors.Violet,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Checking…", color = FinnencerColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            is SettingsViewModel.UpdateState.UpdateAvailable -> {
                FilledTonalButton(
                    onClick = { onInstall(updateState.info) },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FinnencerColors.Violet,
                        contentColor = FinnencerColors.TextOnAccent,
                    ),
                ) {
                    Text("Update to v${updateState.info.version}", fontWeight = FontWeight.SemiBold)
                }
            }
            is SettingsViewModel.UpdateState.ReadyToInstall -> {
                Text(
                    "APK downloaded — installer should open.",
                    color = FinnencerColors.Mint,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is SettingsViewModel.UpdateState.Downloading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = FinnencerColors.Violet,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Downloading…", color = FinnencerColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            is SettingsViewModel.UpdateState.Error -> {
                Column {
                    Text(
                        updateState.message,
                        color = FinnencerColors.Coral,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FilledTonalButton(onClick = onReset) { Text("Reset") }
                }
            }
        }
    }
}

@Composable
private fun AboutSection(adminMode: Boolean, onTapVersion: () -> Unit) {
    val context = LocalContext.current
    SettingsSection(title = "About") {
        SettingsRow(
            title = AppConfig.APP_NAME,
            subtitle = "Version ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
            icon = Icons.Default.Info,
            onClick = onTapVersion,
        )
        SettingsRow(
            title = "Source code",
            subtitle = "github.com/itsikh/finnencer",
            icon = Icons.Default.OpenInNew,
            onClick = {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/${AppConfig.GITHUB_RELEASES_REPO_OWNER}/${AppConfig.GITHUB_RELEASES_REPO_NAME}"),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
        )
        if (adminMode) {
            SettingsRow(
                title = "Admin mode ON",
                subtitle = "Tap version above to toggle",
                icon = Icons.Default.Info,
                iconTint = FinnencerColors.Amber,
            )
        }
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = FinnencerColors.TextOnAccent,
    checkedTrackColor = FinnencerColors.Violet,
    uncheckedThumbColor = FinnencerColors.TextTertiary,
    uncheckedTrackColor = FinnencerColors.SurfaceGlass,
)

private fun backupSubtitle(
    export: SettingsViewModel.ExportState,
    restore: SettingsViewModel.RestoreState,
    isExport: Boolean,
): String {
    if (isExport) return when (export) {
        is SettingsViewModel.ExportState.Idle -> "Save app data to a file you choose"
        is SettingsViewModel.ExportState.Exporting -> "Exporting…"
        is SettingsViewModel.ExportState.Done -> "Last export OK · ${export.itemCount} items"
        is SettingsViewModel.ExportState.Error -> "Last export failed: ${export.message}"
    }
    return when (restore) {
        is SettingsViewModel.RestoreState.Idle -> "Replace local data from a backup file"
        is SettingsViewModel.RestoreState.Restoring -> "Restoring…"
        is SettingsViewModel.RestoreState.Done -> "Last restore OK · ${restore.itemCount} items"
        is SettingsViewModel.RestoreState.Error -> "Last restore failed: ${restore.message}"
    }
}

private fun updateSubtitle(state: SettingsViewModel.UpdateState): String = when (state) {
    is SettingsViewModel.UpdateState.Idle -> "Check GitHub Releases on app launch"
    is SettingsViewModel.UpdateState.Checking -> "Checking GitHub…"
    is SettingsViewModel.UpdateState.UpToDate -> "You are on the latest version"
    is SettingsViewModel.UpdateState.UpdateAvailable -> "Update available: v${state.info.version}"
    is SettingsViewModel.UpdateState.Downloading -> "Downloading update…"
    is SettingsViewModel.UpdateState.ReadyToInstall -> "Ready to install"
    is SettingsViewModel.UpdateState.Error -> "Error: ${state.message}"
}
