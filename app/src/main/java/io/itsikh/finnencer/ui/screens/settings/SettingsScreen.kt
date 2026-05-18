package io.itsikh.finnencer.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.AppConfig
import io.itsikh.finnencer.BuildConfig
import io.itsikh.finnencer.data.repo.ApiKey
import io.itsikh.finnencer.data.repo.ApiKeysRepository
import io.itsikh.finnencer.data.repo.EndOfPodcastAction
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
    onOpenAiPrefs: () -> Unit = {},
    onOpenAiPrompts: () -> Unit = {},
    onOpenReleaseNotes: () -> Unit = {},
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val keysRepo: ApiKeysRepository = hiltViewModel<ApiKeysHolderViewModel>().repo

    val autoUpdate by viewModel.autoUpdateEnabled.collectAsState()
    val autoBackup by viewModel.autoBackupEnabled.collectAsState()
    val showBugButton by viewModel.showBugButton.collectAsState()
    val adminMode by viewModel.adminMode.collectAsState()
    val showDiagnoseButtons by viewModel.showDiagnoseButtons.collectAsState()
    val endOfPodcastAction by viewModel.endOfPodcastAction.collectAsState()
    val podcastCharsPerMin by viewModel.podcastCharsPerMinute.collectAsState()
    val themeId by viewModel.themeId.collectAsState()
    val podcastConcurrency by viewModel.podcastConcurrency.collectAsState()
    val summaryConcurrency by viewModel.summaryConcurrency.collectAsState()
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
                    androidx.compose.foundation.layout.Column {
                        Text(
                            "SETTINGS",
                            style = io.itsikh.finnencer.ui.theme.MonoStyles.Brand,
                            color = FinnencerColors.TextPrimary,
                        )
                        Text(
                            "v${io.itsikh.finnencer.BuildConfig.VERSION_NAME}  ·  ${io.itsikh.finnencer.BuildConfig.VERSION_CODE}",
                            style = io.itsikh.finnencer.ui.theme.MonoStyles.BrandSub,
                            color = FinnencerColors.TextTertiary,
                        )
                    }
                },
                navigationIcon = {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .padding(start = 8.dp, end = 2.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, FinnencerColors.HairlineStrong, RoundedCornerShape(6.dp))
                            .clickable(onClick = onBack)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "← BACK",
                            style = io.itsikh.finnencer.ui.theme.MonoStyles.NavLabel,
                            color = FinnencerColors.TextSecondary,
                        )
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp),
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

            // ───────── AI ─────────
            SettingsSection(title = "AI") {
                SettingsRow(
                    title = "Model preferences",
                    subtitle = "Pick which model runs each workload (scoring, summary, reports, podcast script)",
                    icon = Icons.Default.AutoAwesome,
                    iconTint = FinnencerColors.Violet,
                    onClick = onOpenAiPrefs,
                )
                SettingsRow(
                    title = "Prompts",
                    subtitle = "Add persistent instructions to each AI workload — page counts, podcast length, tone, etc.",
                    icon = Icons.Default.AutoAwesome,
                    iconTint = FinnencerColors.Violet,
                    onClick = onOpenAiPrompts,
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
                    title = "What's new",
                    subtitle = "Release notes for v${io.itsikh.finnencer.BuildConfig.VERSION_NAME} (and any newer version available)",
                    icon = Icons.Default.NewReleases,
                    iconTint = FinnencerColors.Violet,
                    onClick = onOpenReleaseNotes,
                )
                SettingsRow(
                    title = "Cost meter",
                    subtitle = "Per-provider API spend (Anthropic / Gemini)",
                    icon = Icons.Default.AttachMoney,
                    iconTint = FinnencerColors.Mint,
                    onClick = onOpenCost,
                )
                SettingsRow(
                    title = "Show diagnose buttons",
                    subtitle = "Surface XBRL diagnostic buttons in per-ticker earnings — useful when troubleshooting EDGAR, off by default.",
                    icon = Icons.Default.Engineering,
                    iconTint = FinnencerColors.Amber,
                    trailing = {
                        Switch(
                            checked = showDiagnoseButtons,
                            onCheckedChange = viewModel::setShowDiagnoseButtons,
                            colors = switchColors(),
                        )
                    },
                )
                EndOfPodcastRow(
                    current = endOfPodcastAction,
                    onPick = viewModel::setEndOfPodcastAction,
                )
                PodcastCharsPerMinRow(
                    current = podcastCharsPerMin,
                    onChange = viewModel::setPodcastCharsPerMinute,
                )
            }

            // ───────── Appearance ─────────
            SettingsSection(title = "Appearance") {
                ThemePickerRow(
                    current = themeId,
                    onPick = viewModel::setThemeId,
                )
            }

            // ───────── Background jobs ─────────
            SettingsSection(title = "Background jobs") {
                ConcurrencyStepperRow(
                    title = "Podcasts at a time",
                    subtitle = "How many podcast generation jobs run in parallel. 1 = strict queue, 10 = max parallelism. Higher values risk Anthropic rate limits and memory pressure.",
                    value = podcastConcurrency,
                    onChange = viewModel::setPodcastConcurrency,
                )
                ConcurrencyStepperRow(
                    title = "Article summaries at a time",
                    subtitle = "How many summary / report jobs run in parallel. 1 = strict queue.",
                    value = summaryConcurrency,
                    onChange = viewModel::setSummaryConcurrency,
                )
            }

            // ───────── App permissions ─────────
            AppPermissionsSection()

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

@Composable
private fun EndOfPodcastRow(
    current: EndOfPodcastAction,
    onPick: (EndOfPodcastAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Headphones,
                contentDescription = null,
                tint = FinnencerColors.Violet,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "When a podcast ends",
                    style = MaterialTheme.typography.titleSmall,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when (current) {
                        EndOfPodcastAction.STOP -> "Stay on this podcast, leave the player paused at the end."
                        EndOfPodcastAction.CONTINUE -> "Auto-play the next podcast in your To do queue."
                        EndOfPodcastAction.SHUFFLE -> "Auto-play a random remaining podcast from your To do queue."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = FinnencerColors.TextTertiary,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EndOfPodcastAction.entries.forEach { action ->
                EndOfPodcastChip(
                    label = action.label(),
                    selected = current == action,
                    onClick = { onPick(action) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EndOfPodcastChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) FinnencerColors.Violet.copy(alpha = 0.25f) else FinnencerColors.SurfaceGlass
    val border = if (selected) FinnencerColors.Violet else FinnencerColors.TextTertiary.copy(alpha = 0.35f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) FinnencerColors.TextPrimary else FinnencerColors.TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private fun EndOfPodcastAction.label(): String = when (this) {
    EndOfPodcastAction.STOP -> "Stop"
    EndOfPodcastAction.CONTINUE -> "Continue"
    EndOfPodcastAction.SHUFFLE -> "Mix"
}

/**
 * Stepper row for an integer setting clamped to 1..10. The user can tap
 * −/+ buttons or any of the 1-10 chips to pick a value. Used for the
 * podcast and summary concurrency limits in the Background jobs section.
 */
@Composable
private fun ConcurrencyStepperRow(
    title: String,
    subtitle: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = FinnencerColors.TextTertiary,
                )
            }
            Spacer(Modifier.size(10.dp))
            // Big readout of the current value.
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FinnencerColors.Violet.copy(alpha = 0.20f))
                    .border(1.dp, FinnencerColors.Violet.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    value.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            (1..10).forEach { n ->
                val selected = n == value
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) FinnencerColors.Violet.copy(alpha = 0.28f)
                            else FinnencerColors.SurfaceGlass
                        )
                        .border(
                            1.dp,
                            if (selected) FinnencerColors.Violet
                            else FinnencerColors.TextTertiary.copy(alpha = 0.35f),
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onChange(n) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        n.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) FinnencerColors.TextPrimary else FinnencerColors.TextSecondary,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * Stepper for the podcast script's chars-per-minute budget. The
 * dialogue prompt's hard length requirement is `minutes × this value`,
 * so users who consistently get podcasts that overshoot or undershoot
 * their requested duration can dial this knob to compensate. Steps in
 * 100-char increments between [PodcastPreferences.CHARS_PER_MIN_MIN]
 * and [PodcastPreferences.CHARS_PER_MIN_MAX]. The estimated runtime
 * for a 15-min podcast is shown in the subtitle so the effect of a
 * change is concrete.
 */
@Composable
private fun PodcastCharsPerMinRow(
    current: Int,
    onChange: (Int) -> Unit,
) {
    val step = 100
    val min = io.itsikh.finnencer.data.repo.PodcastPreferences.CHARS_PER_MIN_MIN
    val max = io.itsikh.finnencer.data.repo.PodcastPreferences.CHARS_PER_MIN_MAX
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Podcast script length",
                    style = MaterialTheme.typography.titleSmall,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "$current characters per minute of audio. Lower = tighter podcasts that may run short; higher = roomier scripts that may overshoot.",
                    style = MaterialTheme.typography.labelSmall,
                    color = FinnencerColors.TextTertiary,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperButton(
                label = "−",
                enabled = current > min,
                onClick = { onChange((current - step).coerceAtLeast(min)) },
            )
            Spacer(Modifier.size(12.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FinnencerColors.Violet.copy(alpha = 0.20f))
                    .border(1.dp, FinnencerColors.Violet.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$current chars / min",
                    style = MaterialTheme.typography.titleSmall,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.size(12.dp))
            StepperButton(
                label = "+",
                enabled = current < max,
                onClick = { onChange((current + step).coerceAtMost(max)) },
            )
        }
    }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val border = if (enabled) FinnencerColors.Violet else FinnencerColors.TextTertiary.copy(alpha = 0.35f)
    val color = if (enabled) FinnencerColors.TextPrimary else FinnencerColors.TextTertiary
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleLarge,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

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

/**
 * Theme picker — one row per bundled palette. Each row shows the
 * palette's display name plus four swatches (canvas / ink / up / down)
 * so the user can preview the color feel before committing. Tapping
 * a row applies the theme immediately.
 */
@Composable
private fun ThemePickerRow(
    current: io.itsikh.finnencer.ui.theme.ThemeId,
    onPick: (io.itsikh.finnencer.ui.theme.ThemeId) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "Color theme",
            style = MaterialTheme.typography.titleSmall,
            color = FinnencerColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Applies immediately. Up = green, down = red across every theme so signals stay consistent.",
            style = MaterialTheme.typography.labelSmall,
            color = FinnencerColors.TextTertiary,
        )
        Spacer(Modifier.size(10.dp))
        io.itsikh.finnencer.ui.theme.Palettes.all.forEach { palette ->
            ThemeOption(
                palette = palette,
                selected = palette.id == current,
                onPick = { onPick(palette.id) },
            )
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun ThemeOption(
    palette: io.itsikh.finnencer.ui.theme.FinnencerPalette,
    selected: Boolean,
    onPick: () -> Unit,
) {
    val border = if (selected) FinnencerColors.Violet else FinnencerColors.HairlineStrong
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(10.dp))
            .clickable(onClick = onPick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                palette.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (palette.isLight) "LIGHT" else "DARK",
                style = MaterialTheme.typography.labelSmall,
                color = FinnencerColors.TextTertiary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeSwatch(palette.canvas, border = palette.hairlineStrong)
            ThemeSwatch(palette.textPrimary, border = palette.hairlineStrong)
            ThemeSwatch(palette.mint, border = palette.hairlineStrong)
            ThemeSwatch(palette.coral, border = palette.hairlineStrong)
            ThemeSwatch(palette.violet, border = palette.hairlineStrong)
        }
    }
}

@Composable
private fun ThemeSwatch(color: androidx.compose.ui.graphics.Color, border: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .border(1.dp, border, RoundedCornerShape(4.dp)),
    )
}
