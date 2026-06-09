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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import androidx.compose.material3.TextButton
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
    onBack: (() -> Unit)? = null,
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
    val showBugButton by viewModel.showBugButton.collectAsState()
    val adminMode by viewModel.adminMode.collectAsState()
    val showDiagnoseButtons by viewModel.showDiagnoseButtons.collectAsState()
    val endOfPodcastAction by viewModel.endOfPodcastAction.collectAsState()
    val morningBriefEnabled by viewModel.morningBriefEnabled.collectAsState()
    val preEarningsEnabled by viewModel.preEarningsEnabled.collectAsState()
    val insiderAlertEnabled by viewModel.insiderAlertEnabled.collectAsState()
    val secFilingAlertEnabled by viewModel.secFilingAlertEnabled.collectAsState()
    val podcastCharsPerMin by viewModel.podcastCharsPerMinute.collectAsState()
    val podcastValidationEnabled by viewModel.podcastScriptValidationEnabled.collectAsState()
    val podcastTtsChunkChars by viewModel.podcastTtsChunkChars.collectAsState()
    val podcastTtsModel by viewModel.podcastTtsModel.collectAsState()
    val podcastTtsProvider by viewModel.podcastTtsProvider.collectAsState()
    val vertexProbe by viewModel.vertexProbe.collectAsState()
    val podcastSkipTtsPreflight by viewModel.podcastSkipTtsPreflight.collectAsState()
    val themeId by viewModel.themeId.collectAsState()
    val podcastConcurrency by viewModel.podcastConcurrency.collectAsState()
    val summaryConcurrency by viewModel.summaryConcurrency.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val restoreState by viewModel.restoreState.collectAsState()
    val configuredMap by keysRepo.configured.collectAsState()
    val keysConfigured = configuredMap.count { it.value }
    val newsRetentionDays by viewModel.newsRetentionDays.collectAsState()
    val apiUsageRetentionDays by viewModel.apiUsageRetentionDays.collectAsState()

    // Backup password flow. The user types a password first, then we
    // launch the file picker; the password is kept in this state across
    // the SAF round-trip so the actual export call has it ready. For
    // restore we go the other way — pick the file first, then prompt
    // for the password to decrypt it.
    var exportPasswordOpen by remember { mutableStateOf(false) }
    var pendingExportPassword by remember { mutableStateOf("") }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val password = pendingExportPassword
        pendingExportPassword = ""
        if (uri != null && password.isNotEmpty()) {
            viewModel.exportBackupToUri(uri, password)
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) pendingRestoreUri = uri
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium, color = FinnencerColors.TextPrimary)
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = FinnencerColors.TextPrimary)
                        }
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {

            // ───── Account & keys ─────
            SettingsSection(
                title = "Account & keys",
                icon = Icons.Default.VpnKey,
                iconTint = FinnencerColors.Violet,
                summary = "$keysConfigured of ${ApiKey.entries.size} configured",
            ) {
                SettingsRow(
                    title = "API keys",
                    subtitle = "Claude · Finnhub · Gemini · GitHub · EDGAR · Vertex",
                    icon = Icons.Default.VpnKey,
                    onClick = onOpenKeys,
                )
            }

            // ───── AI ─────
            SettingsSection(
                title = "AI",
                icon = Icons.Default.AutoAwesome,
                iconTint = FinnencerColors.Violet,
                summary = "Model picks · prompts",
            ) {
                SettingsRow(
                    title = "Model preferences",
                    subtitle = "Pick which model runs each workload (scoring, summary, reports, podcast script)",
                    icon = Icons.Default.AutoAwesome,
                    onClick = onOpenAiPrefs,
                )
                SettingsRow(
                    title = "Prompts",
                    subtitle = "Persistent instructions per workload — page counts, podcast length, tone, etc.",
                    icon = Icons.Default.AutoAwesome,
                    onClick = onOpenAiPrompts,
                )
            }

            // ───── Smart signals ─────
            // Three background watchers that push notifications when
            // something on the watchlist crosses a meaningful threshold.
            // Each one is opt-in (off by default) and uses the standard
            // alerts channel so users can silence everything in one tap.
            SettingsSection(
                title = "Smart signals",
                icon = Icons.Default.AutoAwesome,
                iconTint = FinnencerColors.Violet,
                summary = "Background watchers for insider buys, 8-K filings, pre-earnings briefings",
            ) {
                SettingsRow(
                    title = "Pre-earnings briefing",
                    subtitle = "Auto-generate a BRIEF earnings report ~24h before any watchlist ticker reports. Lands in Tasks for review; no manual tier picking required.",
                    icon = Icons.Default.AutoAwesome,
                    trailing = {
                        Switch(
                            checked = preEarningsEnabled,
                            onCheckedChange = viewModel::setPreEarningsEnabled,
                            colors = switchColors(),
                        )
                    },
                )
                SettingsRow(
                    title = "Insider buy alerts (Form 4)",
                    subtitle = "Notify when an insider buys ≥\$50k of stock on the open market (Code P). Open-market buys are the strongest insider signal — sales are noisy because they can be for taxes / diversification.",
                    icon = Icons.Default.AutoAwesome,
                    trailing = {
                        Switch(
                            checked = insiderAlertEnabled,
                            onCheckedChange = viewModel::setInsiderAlertEnabled,
                            colors = switchColors(),
                        )
                    },
                )
                SettingsRow(
                    title = "8-K filing alerts",
                    subtitle = "Notify when a watchlist company files an 8-K (material events: officer departures, debt issuance, M&A, ratings actions). Skips routine earnings 8-Ks since you already get those on the Earnings tab.",
                    icon = Icons.Default.AutoAwesome,
                    trailing = {
                        Switch(
                            checked = secFilingAlertEnabled,
                            onCheckedChange = viewModel::setSecFilingAlertEnabled,
                            colors = switchColors(),
                        )
                    },
                )
            }

            // ───── Podcasts ─────
            SettingsSection(
                title = "Podcasts",
                icon = Icons.Default.Headphones,
                iconTint = FinnencerColors.Coral,
                summary = "TTS · script length · auto-play",
            ) {
                SettingsRow(
                    title = "Morning brief",
                    subtitle = "Generate a personalized podcast at 5:00am on weekdays — only the day's big news for your watchlist. Scales to ~5–15 min, or skips quiet days. Plays in the Library tab.",
                    icon = Icons.Default.AutoAwesome,
                    trailing = {
                        Switch(
                            checked = morningBriefEnabled,
                            onCheckedChange = viewModel::setMorningBriefEnabled,
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
                PodcastTtsModelRow(
                    current = podcastTtsModel,
                    onPick = viewModel::setPodcastTtsModel,
                )
                PodcastTtsProviderRow(
                    current = podcastTtsProvider,
                    onPick = viewModel::setPodcastTtsProvider,
                )
                VertexProbeRow(
                    state = vertexProbe,
                    onRun = viewModel::runVertexProbe,
                    onAck = viewModel::ackVertexProbe,
                )
                PodcastTtsChunkRow(
                    current = podcastTtsChunkChars,
                    onChange = viewModel::setPodcastTtsChunkChars,
                )
                SettingsRow(
                    title = "Script validation",
                    subtitle = "Run a second AI on the script to catch mid-script re-intros, malformed lines and fabricated facts before paying for audio. Off by default.",
                    icon = Icons.Default.AutoAwesome,
                    trailing = {
                        Switch(
                            checked = podcastValidationEnabled,
                            onCheckedChange = viewModel::setPodcastScriptValidationEnabled,
                            colors = switchColors(),
                        )
                    },
                )
                SettingsRow(
                    title = "TTS preflight smoke test",
                    subtitle = "Verify Gemini TTS is responsive before writing the script. Default OFF — preview-TTS models have tight per-minute rate limits.",
                    icon = Icons.Default.AutoAwesome,
                    trailing = {
                        Switch(
                            checked = !podcastSkipTtsPreflight,
                            onCheckedChange = { enabled -> viewModel.setPodcastSkipTtsPreflight(!enabled) },
                            colors = switchColors(),
                        )
                    },
                )
            }

            // ───── Updates ─────
            SettingsSection(
                title = "Updates",
                icon = Icons.Default.SystemUpdate,
                iconTint = FinnencerColors.Mint,
                summary = updateSummary(updateState, autoUpdate),
            ) {
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
                    subtitle = "Release notes for v${BuildConfig.VERSION_NAME} (and any newer version available)",
                    icon = Icons.Default.NewReleases,
                    onClick = onOpenReleaseNotes,
                )
            }

            // ───── Costs ─────
            SettingsSection(
                title = "Costs",
                icon = Icons.Default.AttachMoney,
                iconTint = FinnencerColors.Mint,
                summary = "Per-provider API spend",
            ) {
                SettingsRow(
                    title = "Cost meter",
                    subtitle = "Anthropic · Gemini · per-day breakdown",
                    icon = Icons.Default.AttachMoney,
                    iconTint = FinnencerColors.Mint,
                    onClick = onOpenCost,
                )
            }

            // ───── Appearance ─────
            SettingsSection(
                title = "Appearance",
                icon = Icons.Default.Palette,
                iconTint = FinnencerColors.Violet,
                summary = themeSummary(themeId),
            ) {
                ThemePickerRow(
                    current = themeId,
                    onPick = viewModel::setThemeId,
                )
            }

            // ───── Background jobs ─────
            SettingsSection(
                title = "Background jobs",
                icon = Icons.Default.Speed,
                iconTint = FinnencerColors.Violet,
                summary = "Podcasts $podcastConcurrency · summaries $summaryConcurrency",
            ) {
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

            // ───── Storage ─────
            SettingsSection(
                title = "Storage",
                icon = Icons.Default.Storage,
                iconTint = FinnencerColors.Violet,
                summary = "News $newsRetentionDays d · usage $apiUsageRetentionDays d",
            ) {
                RetentionPickerRow(
                    title = "Cached news retention",
                    subtitle = "How long ingested news articles stay in the local DB. Older rows are pruned at the end of each sync cycle. Articles already in your reading list, summaries, reports and podcasts are NOT affected.",
                    current = newsRetentionDays,
                    presets = io.itsikh.finnencer.data.repo.RetentionPreferences.NEWS_PRESETS,
                    onPick = viewModel::setNewsRetentionDays,
                )
                RetentionPickerRow(
                    title = "API usage history retention",
                    subtitle = "How long the cost-meter keeps per-call token rows. Older calls drop off so the meter shows recent spend, not all-time.",
                    current = apiUsageRetentionDays,
                    presets = io.itsikh.finnencer.data.repo.RetentionPreferences.USAGE_PRESETS,
                    onPick = viewModel::setApiUsageRetentionDays,
                )
            }

            // ───── Permissions ─────
            AppPermissionsSection()

            // ───── Backup & Restore ─────
            SettingsSection(
                title = "Backup & Restore",
                icon = Icons.Default.Storage,
                iconTint = FinnencerColors.Amber,
                summary = "Encrypted · keys + watchlist",
            ) {
                SettingsRow(
                    title = "What's backed up",
                    subtitle = "Your API keys (encrypted) and your watchlist. News articles, summaries and podcasts are NOT backed up — they're rebuilt from a fresh sync.",
                    icon = Icons.Default.Security,
                    iconTint = FinnencerColors.Violet,
                )
                SettingsRow(
                    title = "Export encrypted backup…",
                    subtitle = backupSubtitle(exportState, restoreState, isExport = true),
                    icon = Icons.Default.CloudUpload,
                    iconTint = FinnencerColors.Mint,
                    onClick = { exportPasswordOpen = true },
                )
                SettingsRow(
                    title = "Restore from backup…",
                    subtitle = backupSubtitle(exportState, restoreState, isExport = false),
                    icon = Icons.Default.RestoreFromTrash,
                    iconTint = FinnencerColors.Amber,
                    onClick = { restoreLauncher.launch("*/*") },
                )
            }

            // ───── Diagnostics ─────
            SettingsSection(
                title = "Diagnostics",
                icon = Icons.Default.BugReport,
                iconTint = FinnencerColors.Coral,
                summary = if (adminMode) "Admin mode ON" else "Bug reports · feedback",
            ) {
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
                SettingsRow(
                    title = "Show diagnose buttons",
                    subtitle = "Surface XBRL diagnostic buttons in per-ticker earnings — useful when troubleshooting EDGAR.",
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

            // ───── About ─────
            AboutSection(adminMode = adminMode, onTapVersion = { viewModel.setAdminMode(!adminMode) })

            Spacer(Modifier.height(40.dp))
        }
    }

    // Password dialogs sit outside the Scaffold's content so they
    // overlay the whole screen.
    if (exportPasswordOpen) {
        BackupPasswordDialog(
            title = "Encrypt this backup",
            description = "Pick a password (8+ characters). You'll need it again to restore — store it somewhere safe.",
            confirmLabel = "Choose file location",
            requireConfirm = true,
            onCancel = { exportPasswordOpen = false },
            onConfirm = { pw ->
                pendingExportPassword = pw
                exportPasswordOpen = false
                exportLauncher.launch("finnencer-backup.${AppConfig.APP_NAME}_settings")
            },
        )
    }

    pendingRestoreUri?.let { uri ->
        BackupPasswordDialog(
            title = "Decrypt this backup",
            description = "Enter the password you used when this backup was created.",
            confirmLabel = "Restore",
            requireConfirm = false,
            onCancel = { pendingRestoreUri = null },
            onConfirm = { pw ->
                viewModel.restoreFromBackup(uri, pw)
                pendingRestoreUri = null
            },
        )
    }
}

/**
 * Simple password prompt used by both Export and Restore. The "require
 * confirm" mode shows a second field that must match — useful for
 * Export where a typo means the user permanently locks themselves out
 * of the file. Restore skips it (typo just means a retry).
 */
@Composable
private fun BackupPasswordDialog(
    title: String,
    description: String,
    confirmLabel: String,
    requireConfirm: Boolean,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    val mismatch = requireConfirm && pw2.isNotEmpty() && pw != pw2
    val tooShort = pw.length < 8
    val canSubmit = !tooShort && (!requireConfirm || pw == pw2)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FinnencerColors.TextSecondary,
                )
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (visible) "Hide" else "Show",
                                tint = FinnencerColors.TextTertiary,
                            )
                        }
                    },
                    isError = tooShort && pw.isNotEmpty(),
                    supportingText = {
                        if (tooShort && pw.isNotEmpty()) {
                            Text("At least 8 characters", color = FinnencerColors.Coral)
                        }
                    },
                    colors = passwordFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (requireConfirm) {
                    OutlinedTextField(
                        value = pw2,
                        onValueChange = { pw2 = it },
                        label = { Text("Confirm password") },
                        singleLine = true,
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = mismatch,
                        supportingText = {
                            if (mismatch) {
                                Text("Doesn't match", color = FinnencerColors.Coral)
                            }
                        },
                        colors = passwordFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { if (canSubmit) onConfirm(pw) },
                enabled = canSubmit,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = FinnencerColors.Violet,
                    contentColor = FinnencerColors.TextOnAccent,
                ),
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

@Composable
private fun passwordFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = FinnencerColors.TextPrimary,
    unfocusedTextColor = FinnencerColors.TextPrimary,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedLabelColor = FinnencerColors.Violet,
    unfocusedLabelColor = FinnencerColors.TextTertiary,
    focusedIndicatorColor = FinnencerColors.Violet,
    unfocusedIndicatorColor = FinnencerColors.SurfaceBorder,
    cursorColor = FinnencerColors.Violet,
)

private fun updateSummary(state: SettingsViewModel.UpdateState, autoOn: Boolean): String {
    val auto = if (autoOn) "Auto-check ON" else "Auto-check OFF"
    val detail = when (state) {
        is SettingsViewModel.UpdateState.UpdateAvailable -> "v${state.info.version} available"
        is SettingsViewModel.UpdateState.UpToDate -> "Up to date"
        is SettingsViewModel.UpdateState.Downloading -> "Downloading…"
        is SettingsViewModel.UpdateState.ReadyToInstall -> "Ready to install"
        is SettingsViewModel.UpdateState.Error -> "Last check failed"
        else -> "v${BuildConfig.VERSION_NAME}"
    }
    return "$auto · $detail"
}

private fun themeSummary(id: io.itsikh.finnencer.ui.theme.ThemeId): String =
    io.itsikh.finnencer.ui.theme.Palettes.all.firstOrNull { it.id == id }?.displayName ?: "Default"

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
private fun PodcastTtsModelRow(
    current: io.itsikh.finnencer.data.repo.TtsModel,
    onPick: (io.itsikh.finnencer.data.repo.TtsModel) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = FinnencerColors.Violet,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Podcast TTS model",
                    style = MaterialTheme.typography.titleSmall,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    current.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = FinnencerColors.TextTertiary,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            io.itsikh.finnencer.data.repo.TtsModel.entries.forEach { model ->
                EndOfPodcastChip(
                    label = model.displayName.removePrefix("Gemini "),
                    selected = current == model,
                    onClick = { onPick(model) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PodcastTtsProviderRow(
    current: io.itsikh.finnencer.data.repo.TtsProvider,
    onPick: (io.itsikh.finnencer.data.repo.TtsProvider) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = FinnencerColors.Violet,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Podcast TTS provider",
                    style = MaterialTheme.typography.titleSmall,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    current.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = FinnencerColors.TextTertiary,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            io.itsikh.finnencer.data.repo.TtsProvider.entries.forEach { provider ->
                EndOfPodcastChip(
                    label = when (provider) {
                        io.itsikh.finnencer.data.repo.TtsProvider.GENERATIVE_LANGUAGE -> "Gen Lang API"
                        io.itsikh.finnencer.data.repo.TtsProvider.VERTEX_AI -> "Vertex AI"
                    },
                    selected = current == provider,
                    onClick = { onPick(provider) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun VertexProbeRow(
    state: SettingsViewModel.VertexProbeResult,
    onRun: () -> Unit,
    onAck: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Test TTS provider setup",
                    style = MaterialTheme.typography.titleSmall,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Fires one tiny TTS call through the provider you picked above. Verifies credentials, IAM, and that the model returns audio — before you spend Claude tokens writing a podcast script that would have failed at the TTS stage.",
                    style = MaterialTheme.typography.labelSmall,
                    color = FinnencerColors.TextTertiary,
                )
            }
            FilledTonalButton(
                onClick = onRun,
                enabled = state !is SettingsViewModel.VertexProbeResult.Running,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = FinnencerColors.Violet,
                    contentColor = FinnencerColors.TextOnAccent,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                if (state is SettingsViewModel.VertexProbeResult.Running) {
                    CircularProgressIndicator(
                        color = FinnencerColors.TextOnAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Testing…")
                } else {
                    Text("Run test", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        when (state) {
            is SettingsViewModel.VertexProbeResult.Ok -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(FinnencerColors.Mint.copy(alpha = 0.10f))
                            .border(1.dp, FinnencerColors.Mint.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "TTS setup OK — ${state.model} responded in ${state.elapsedMs / 1000}s.",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.Mint,
                        )
                    }
                    TextButton(onClick = onAck) { Text("Dismiss") }
                }
            }
            is SettingsViewModel.VertexProbeResult.Failed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(FinnencerColors.Coral.copy(alpha = 0.10f))
                            .border(1.dp, FinnencerColors.Coral.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.Coral,
                        )
                    }
                    TextButton(onClick = onAck) { Text("Dismiss") }
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun PodcastTtsChunkRow(
    current: Int,
    onChange: (Int) -> Unit,
) {
    val step = 250
    val min = io.itsikh.finnencer.data.repo.PodcastPreferences.TTS_CHUNK_MIN
    val max = io.itsikh.finnencer.data.repo.PodcastPreferences.TTS_CHUNK_MAX
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Podcast TTS chunk size",
                    style = MaterialTheme.typography.titleSmall,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "$current characters per Gemini call. Smaller = more API calls but each is faster and less likely to time out; failures only lose one chunk thanks to the resume cache.",
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
                    "$current chars / chunk",
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

/**
 * Picker row for a days-based retention setting. Renders one row of
 * preset chips (e.g. 14 / 30 / 60 / 90 / 180 / 365) so the user picks
 * a window without a free-form text field — same UX as the
 * end-of-podcast / TTS provider rows. The custom value the user might
 * have saved earlier is shown in the readout above the chips.
 */
@Composable
private fun RetentionPickerRow(
    title: String,
    subtitle: String,
    current: Int,
    presets: List<Int>,
    onPick: (Int) -> Unit,
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
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FinnencerColors.Violet.copy(alpha = 0.20f))
                    .border(1.dp, FinnencerColors.Violet.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$current d",
                    style = MaterialTheme.typography.titleSmall,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            presets.forEach { days ->
                EndOfPodcastChip(
                    label = "$days d",
                    selected = days == current,
                    onClick = { onPick(days) },
                    modifier = Modifier.weight(1f),
                )
            }
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
 * palette's display name and description plus five swatches (canvas
 * top + bottom + accent + up + down) so the user can preview the
 * color feel before committing. Tap applies immediately; the
 * selected row is highlighted by the active accent's border.
 */
@Composable
private fun ThemePickerRow(
    current: io.itsikh.finnencer.ui.theme.ThemeId,
    onPick: (io.itsikh.finnencer.ui.theme.ThemeId) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            "Color theme",
            style = MaterialTheme.typography.titleSmall,
            color = FinnencerColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Applies instantly. Green = up, red = down across every theme so signals stay consistent.",
            style = MaterialTheme.typography.labelSmall,
            color = FinnencerColors.TextTertiary,
        )
        Spacer(Modifier.size(12.dp))
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
    val border = if (selected) FinnencerColors.Violet else FinnencerColors.SurfaceBorder
    val borderWidth = if (selected) 2.dp else 1.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(FinnencerColors.SurfaceGlass)
            .border(borderWidth, border, RoundedCornerShape(14.dp))
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
            Spacer(Modifier.size(2.dp))
            Text(
                palette.description,
                style = MaterialTheme.typography.labelSmall,
                color = FinnencerColors.TextTertiary,
            )
        }
        Spacer(Modifier.size(12.dp))
        // Vertical swatch column — 5 stacked colors give a quick read of
        // the palette without crowding the row.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ThemeSwatch(palette.bgTop)
            ThemeSwatch(palette.accent)
            ThemeSwatch(palette.up)
            ThemeSwatch(palette.down)
            ThemeSwatch(palette.amber)
        }
    }
}

@Composable
private fun ThemeSwatch(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(width = 14.dp, height = 28.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color)
            .border(1.dp, FinnencerColors.SurfaceBorder, RoundedCornerShape(3.dp)),
    )
}
