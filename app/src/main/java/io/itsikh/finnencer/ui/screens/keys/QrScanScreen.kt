package io.itsikh.finnencer.ui.screens.keys

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.data.qr.QrAnalyzer
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.theme.FinnencerColors
import java.util.concurrent.Executors

@Composable
fun QrScanScreen(onBack: () -> Unit, onImported: () -> Unit) {
    val vm: QrScanViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> vm.setCameraGranted(granted) }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        vm.setCameraGranted(granted)
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(state.stage) {
        if (state.stage is ScanStage.Imported) {
            onImported()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
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
                    "Scan Keys",
                    style = MaterialTheme.typography.headlineMedium,
                    color = FinnencerColors.TextPrimary,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val stage = state.stage) {
                is ScanStage.Scanning -> {
                    if (!state.cameraGranted) {
                        CameraPermissionPrompt {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    } else {
                        CameraPreview(onScanned = vm::onScannedRaw)
                        Text(
                            "Point camera at a finnencer QR.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FinnencerColors.TextSecondary,
                        )
                    }
                }
                is ScanStage.NeedsPassphrase -> PassphrasePrompt(state, vm)
                is ScanStage.Preview -> ImportPreview(stage.parsed, state, vm)
                is ScanStage.Error -> ScanErrorBlock(stage.message, vm::reset)
                ScanStage.Imported -> Text(
                    "Imported. Returning to keys screen…",
                    color = FinnencerColors.Mint,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun CameraPreview(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { analyzerExecutor.shutdown() }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(
                1.dp,
                FinnencerColors.SurfaceBorder,
                RoundedCornerShape(20.dp),
            )
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                    ),
                                )
                                .build()
                        )
                        .build()
                        .also {
                            it.setAnalyzer(
                                analyzerExecutor,
                                QrAnalyzer(executor = analyzerExecutor, onResult = onScanned),
                            )
                        }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )
    }
}

@Composable
private fun CameraPermissionPrompt(onRequest: () -> Unit) {
    GlassCard {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Camera permission required",
                style = MaterialTheme.typography.titleLarge,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Used only to decode a finnencer QR. Nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = FinnencerColors.TextSecondary,
            )
            FilledTonalButton(
                onClick = onRequest,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = FinnencerColors.Violet,
                    contentColor = FinnencerColors.TextOnAccent,
                ),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Grant camera access", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun PassphrasePrompt(state: QrScanState, vm: QrScanViewModel) {
    GlassCard {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Passphrase required",
                style = MaterialTheme.typography.titleLarge,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "This QR is encrypted. Enter the same passphrase used when it was generated.",
                style = MaterialTheme.typography.bodySmall,
                color = FinnencerColors.TextSecondary,
            )
            OutlinedTextField(
                value = state.passphrase,
                onValueChange = vm::setPassphrase,
                label = { Text("Passphrase") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = vm::reset) { Text("Cancel", color = FinnencerColors.TextSecondary) }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = vm::tryDecryptWithPassphrase,
                    enabled = state.passphrase.isNotBlank(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FinnencerColors.Violet,
                        contentColor = FinnencerColors.TextOnAccent,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Decrypt", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun ImportPreview(parsed: Map<String, String>, state: QrScanState, vm: QrScanViewModel) {
    GlassCard {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Ready to import",
                style = MaterialTheme.typography.titleLarge,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${parsed.size} key(s) will be saved on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = FinnencerColors.TextSecondary,
            )
            parsed.keys.sorted().forEach { slug ->
                Text(
                    "•  $slug",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FinnencerColors.TextPrimary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Overwrite existing values",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FinnencerColors.TextPrimary,
                    )
                    Text(
                        "Off = keep current keys, only fill blanks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FinnencerColors.TextSecondary,
                    )
                }
                Switch(
                    checked = state.overwriteExisting,
                    onCheckedChange = vm::setOverwrite,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = FinnencerColors.TextOnAccent,
                        checkedTrackColor = FinnencerColors.Violet,
                        uncheckedThumbColor = FinnencerColors.TextTertiary,
                        uncheckedTrackColor = FinnencerColors.SurfaceGlass,
                    ),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = vm::reset) {
                    Text("Cancel", color = FinnencerColors.TextSecondary)
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = vm::confirmImport,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FinnencerColors.Mint,
                        contentColor = FinnencerColors.TextOnAccent,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Import", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun ScanErrorBlock(message: String, onReset: () -> Unit) {
    GlassCard {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Couldn't import that QR",
                style = MaterialTheme.typography.titleLarge,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(message, color = FinnencerColors.Coral, style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(
                onClick = onReset,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = FinnencerColors.Violet,
                    contentColor = FinnencerColors.TextOnAccent,
                ),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Try again") }
        }
    }
}
