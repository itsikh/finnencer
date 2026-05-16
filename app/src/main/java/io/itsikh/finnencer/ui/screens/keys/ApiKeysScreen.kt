package io.itsikh.finnencer.ui.screens.keys

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.data.repo.ApiKey
import io.itsikh.finnencer.data.repo.KeyTestResult
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.theme.FinnencerColors

@Composable
fun ApiKeysScreen(
    onBack: () -> Unit,
    onOpenScan: () -> Unit,
    onOpenShare: () -> Unit,
) {
    val vm: ApiKeysViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { KeysTopBar(onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HeaderText()
            QrButtonsRow(onOpenScan = onOpenScan, onOpenShare = onOpenShare)
            BuildIdentityCard(
                packageName = vm.signingInfo.packageName,
                sha1Pretty = vm.signingInfo.signingCertSha1Pretty,
            )
            Spacer(Modifier.height(4.dp))
            ApiKey.entries.forEach { key ->
                val card = state[key] ?: return@forEach
                KeyCard(
                    state = card,
                    onToggleExpand = { vm.toggleExpand(key) },
                    onStartEdit = { vm.startEdit(key) },
                    onDraftChange = { vm.updateDraft(key, it) },
                    onToggleReveal = { vm.toggleReveal(key) },
                    onSave = { vm.saveAndValidate(key) },
                    onClear = { vm.clear(key) },
                    onTest = { vm.test(key) },
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun KeysTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = FinnencerColors.TextPrimary,
            )
        }
        Text(
            text = "API Keys",
            style = MaterialTheme.typography.headlineMedium,
            color = FinnencerColors.TextPrimary,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun BuildIdentityCard(packageName: String, sha1Pretty: String?) {
    val clipboard = LocalClipboardManager.current
    GlassCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Build identity (for GCP key restrictions)",
                style = MaterialTheme.typography.labelMedium,
                color = FinnencerColors.TextTertiary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Package",
                        style = MaterialTheme.typography.labelSmall,
                        color = FinnencerColors.TextTertiary,
                    )
                    Text(
                        packageName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FinnencerColors.TextPrimary,
                    )
                }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(packageName))
                }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy package",
                        tint = FinnencerColors.TextSecondary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "SHA-1 fingerprint",
                        style = MaterialTheme.typography.labelSmall,
                        color = FinnencerColors.TextTertiary,
                    )
                    Text(
                        sha1Pretty ?: "(unavailable)",
                        style = MaterialTheme.typography.bodySmall,
                        color = FinnencerColors.TextPrimary,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
                IconButton(onClick = {
                    sha1Pretty?.let { clipboard.setText(AnnotatedString(it)) }
                }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy SHA-1",
                        tint = FinnencerColors.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderText() {
    Text(
        text = "Five keys power finnencer. They are encrypted on this device and never leave it.",
        style = MaterialTheme.typography.bodyMedium,
        color = FinnencerColors.TextSecondary,
    )
}

@Composable
private fun QrButtonsRow(onOpenScan: () -> Unit, onOpenShare: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(
            onClick = onOpenScan,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = FinnencerColors.Violet.copy(alpha = 0.18f),
                contentColor = FinnencerColors.TextPrimary,
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Scan QR", style = MaterialTheme.typography.labelLarge)
        }
        FilledTonalButton(
            onClick = onOpenShare,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = FinnencerColors.Violet.copy(alpha = 0.18f),
                contentColor = FinnencerColors.TextPrimary,
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Share QR", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun KeyCard(
    state: KeyCardState,
    onToggleExpand: () -> Unit,
    onStartEdit: () -> Unit,
    onDraftChange: (String) -> Unit,
    onToggleReveal: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit,
) {
    GlassCard(onClick = onToggleExpand) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.key.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = FinnencerColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = state.key.purpose,
                        style = MaterialTheme.typography.bodySmall,
                        color = FinnencerColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(configured = state.configured)
            }

            AnimatedVisibility(
                visible = state.expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                ExpandedKeyEditor(
                    state = state,
                    onStartEdit = onStartEdit,
                    onDraftChange = onDraftChange,
                    onToggleReveal = onToggleReveal,
                    onSave = onSave,
                    onClear = onClear,
                    onTest = onTest,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(configured: Boolean) {
    val (label, color) = if (configured) {
        "Configured" to FinnencerColors.Mint
    } else {
        "Not set" to FinnencerColors.TextTertiary
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun ExpandedKeyEditor(
    state: KeyCardState,
    onStartEdit: () -> Unit,
    onDraftChange: (String) -> Unit,
    onToggleReveal: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = Modifier.padding(top = 16.dp)) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = onDraftChange,
            label = { Text("Key value") },
            visualTransformation = if (state.reveal) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Row {
                    IconButton(onClick = onToggleReveal) {
                        Icon(
                            if (state.reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (state.reveal) "Hide" else "Reveal",
                            tint = FinnencerColors.TextSecondary,
                        )
                    }
                    IconButton(
                        onClick = {
                            clipboard.getText()?.text?.let(onDraftChange)
                                ?: clipboard.setText(AnnotatedString("")) // no-op fallback
                        },
                    ) {
                        Icon(
                            Icons.Default.ContentPaste,
                            contentDescription = "Paste",
                            tint = FinnencerColors.TextSecondary,
                        )
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedTextColor = FinnencerColors.TextPrimary,
                unfocusedTextColor = FinnencerColors.TextPrimary,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedLabelColor = FinnencerColors.Violet,
                unfocusedLabelColor = FinnencerColors.TextTertiary,
                focusedIndicatorColor = FinnencerColors.Violet,
                unfocusedIndicatorColor = FinnencerColors.SurfaceBorder,
                cursorColor = FinnencerColors.Violet,
            ),
        )

        state.testResult?.let { TestResultRow(it) }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onTest,
                enabled = state.draft.isNotBlank() && !state.testing,
                colors = ButtonDefaults.textButtonColors(contentColor = FinnencerColors.Mint),
            ) { Text(if (state.testing) "Validating with provider…" else "Test") }

            Spacer(Modifier.weight(1f))

            TextButton(
                onClick = onClear,
                enabled = state.configured,
                colors = ButtonDefaults.textButtonColors(contentColor = FinnencerColors.Coral),
            ) { Text("Clear") }

            FilledTonalButton(
                onClick = onSave,
                enabled = state.draft.isNotBlank(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = FinnencerColors.Violet,
                    contentColor = FinnencerColors.TextOnAccent,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Get one at: " + state.key.signupUrl,
            style = MaterialTheme.typography.labelSmall,
            color = FinnencerColors.TextTertiary,
        )
    }
}

@Composable
private fun TestResultRow(result: KeyTestResult) {
    val (text, color) = when (result) {
        KeyTestResult.NotConfigured -> "No key saved yet" to FinnencerColors.TextTertiary
        KeyTestResult.ChecksSyntax -> "Checking format…" to FinnencerColors.TextSecondary
        KeyTestResult.Ok -> "Provider accepted the key" to FinnencerColors.Mint
        is KeyTestResult.BadFormat -> result.message to FinnencerColors.Amber
        is KeyTestResult.Failed -> result.message to FinnencerColors.Coral
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
