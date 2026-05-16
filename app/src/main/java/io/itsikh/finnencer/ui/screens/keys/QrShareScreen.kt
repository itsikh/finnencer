package io.itsikh.finnencer.ui.screens.keys

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.theme.FinnencerColors

@Composable
fun QrShareScreen(onBack: () -> Unit) {
    val vm: QrShareViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

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
                    "Share Keys",
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
            Text(
                text = "Generates a QR with all configured keys. Anyone who can see the QR can read them — use the passphrase option if you can't trust the room.",
                style = MaterialTheme.typography.bodySmall,
                color = FinnencerColors.TextSecondary,
            )

            GlassCard {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Passphrase-protect",
                                style = MaterialTheme.typography.titleMedium,
                                color = FinnencerColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Encrypt with AES-256-GCM using PBKDF2-HMAC-SHA256 (100k iter)",
                                style = MaterialTheme.typography.bodySmall,
                                color = FinnencerColors.TextSecondary,
                            )
                        }
                        Switch(
                            checked = state.encrypt,
                            onCheckedChange = vm::setEncrypt,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = FinnencerColors.TextOnAccent,
                                checkedTrackColor = FinnencerColors.Violet,
                                uncheckedThumbColor = FinnencerColors.TextTertiary,
                                uncheckedTrackColor = FinnencerColors.SurfaceGlass,
                            ),
                        )
                    }

                    if (state.encrypt) {
                        Spacer(Modifier.height(12.dp))
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
                    }
                }
            }

            FilledTonalButton(
                onClick = vm::generate,
                enabled = !state.encrypt || state.passphrase.length >= 6,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = FinnencerColors.Violet,
                    contentColor = FinnencerColors.TextOnAccent,
                ),
            ) {
                Text("Generate QR", fontWeight = FontWeight.SemiBold)
            }

            state.error?.let { err ->
                Text(
                    err,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FinnencerColors.Coral,
                )
            }

            state.bitmap?.let { bitmap ->
                Spacer(Modifier.height(8.dp))
                GlassCard {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White),
                        ) {
                            Image(
                                bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                                contentDescription = "QR with finnencer API keys",
                                modifier = Modifier.fillMaxSize().padding(12.dp),
                            )
                        }
                    }
                }
                Text(
                    text = if (state.encrypt)
                        "${state.keyCount} keys, encrypted. Scanner must enter the same passphrase."
                    else
                        "${state.keyCount} keys, PLAINTEXT. Anyone who sees this QR has your keys.",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.encrypt) FinnencerColors.Mint else FinnencerColors.Coral,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}
