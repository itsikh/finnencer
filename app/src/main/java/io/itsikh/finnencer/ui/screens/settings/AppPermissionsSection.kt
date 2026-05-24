package io.itsikh.finnencer.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.itsikh.finnencer.ui.theme.FinnencerColors

/**
 * One row per Android permission finnencer declares in its manifest, with
 * a human-readable purpose, current grant state, and (where applicable)
 * an inline Grant button or a deep link to the system app-info screen.
 *
 * Runtime permissions (POST_NOTIFICATIONS, CAMERA) get a Grant button
 * that triggers the system prompt. Already-granted runtime perms link
 * to the system app-info screen so the user can revoke them. Install-
 * time / special permissions (INTERNET, FOREGROUND_SERVICE, etc.) are
 * informational only.
 */
@Composable
fun AppPermissionsSection() {
    val context = LocalContext.current

    // Bump this counter after a permission request returns to force the
    // section to recompose and re-read the live grant state.
    var revision by remember { mutableIntStateOf(0) }

    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { revision++ }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { revision++ }

    fun openAppSettings() {
        val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun isGranted(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    // We re-read grant state inside the composable on each `revision` bump.
    @Suppress("UNUSED_EXPRESSION") revision

    SettingsSection(
        title = "Permissions",
        icon = Icons.Default.Lock,
        iconTint = FinnencerColors.Violet,
        summary = "Notifications · camera · install · biometrics",
    ) {
        // ── Runtime: POST_NOTIFICATIONS (Android 13+) ──
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(Manifest.permission.POST_NOTIFICATIONS)
        } else true
        PermissionRow(
            title = "Notifications",
            purpose = "Required to alert you when a watchlist article scores at or above your importance threshold.",
            icon = Icons.Default.Notifications,
            iconTint = FinnencerColors.Violet,
            granted = notifGranted,
            actionLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifGranted) "Grant" else "Manage",
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifGranted) {
                    notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else openAppSettings()
            },
        )

        // ── Runtime: CAMERA ──
        val cameraGranted = isGranted(Manifest.permission.CAMERA)
        PermissionRow(
            title = "Camera",
            purpose = "Used only when you scan a QR code to import API keys from another device.",
            icon = Icons.Default.CameraAlt,
            iconTint = FinnencerColors.Mint,
            granted = cameraGranted,
            actionLabel = if (!cameraGranted) "Grant" else "Manage",
            onAction = {
                if (!cameraGranted) cameraLauncher.launch(Manifest.permission.CAMERA)
                else openAppSettings()
            },
        )

        // ── Special: install packages (auto-update flow) ──
        val canInstall = context.packageManager.canRequestPackageInstalls()
        PermissionRow(
            title = "Install other apps",
            purpose = "Required so the in-app auto-update can install a downloaded APK from GitHub Releases. " +
                "Without this, you'll be prompted by the system installer every time.",
            icon = Icons.Default.GetApp,
            iconTint = FinnencerColors.Amber,
            granted = canInstall,
            actionLabel = "Manage",
            onAction = {
                val intent = Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
                    .onFailure { openAppSettings() }
            },
        )

        // ── Install-time / special, informational only ──
        InfoPermissionRow(
            title = "Internet",
            purpose = "All network access: Anthropic, Gemini, Finnhub, SEC EDGAR, GitHub.",
            icon = Icons.Default.Public,
        )
        InfoPermissionRow(
            title = "Biometric (USE_BIOMETRIC)",
            purpose = "Optional fingerprint / face unlock for accessing stored API keys.",
            icon = Icons.Default.Fingerprint,
        )
        InfoPermissionRow(
            title = "Foreground media playback",
            purpose = "Lets podcast playback keep running while the screen is off, with lock-screen controls.",
            icon = Icons.Default.PlayCircle,
        )
        InfoPermissionRow(
            title = "Wake lock",
            purpose = "Briefly held during background sync cycles so the radio doesn't drop mid-request.",
            icon = Icons.Default.Lock,
        )
        InfoPermissionRow(
            title = "Allow backup",
            purpose = "Lets you export and restore your watchlist + DB through the Backup & Restore section.",
            icon = Icons.Default.Backup,
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    purpose: String,
    icon: ImageVector,
    iconTint: Color,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (granted) "GRANTED" else "NOT GRANTED",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (granted) FinnencerColors.Mint else FinnencerColors.Coral,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                purpose,
                style = MaterialTheme.typography.labelSmall,
                color = FinnencerColors.TextTertiary,
            )
        }
        Spacer(Modifier.size(8.dp))
        FilledTonalButton(
            onClick = onAction,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = FinnencerColors.SurfaceGlass,
                contentColor = FinnencerColors.TextPrimary,
            ),
        ) {
            Text(actionLabel, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun InfoPermissionRow(title: String, purpose: String, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = FinnencerColors.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "GRANTED",
                    style = MaterialTheme.typography.labelSmall,
                    color = FinnencerColors.Mint,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                purpose,
                style = MaterialTheme.typography.labelSmall,
                color = FinnencerColors.TextTertiary,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "auto",
                style = MaterialTheme.typography.labelSmall,
                color = FinnencerColors.TextTertiary,
            )
        }
    }
}
