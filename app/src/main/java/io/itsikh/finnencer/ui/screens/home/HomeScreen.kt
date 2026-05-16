package io.itsikh.finnencer.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.itsikh.finnencer.R
import io.itsikh.finnencer.ui.theme.FinnencerColors

/**
 * Placeholder home screen. Replaced by the Watchlist screen in Build A·6.
 *
 * Temporary entry points so the rest of A.x routes are reachable on a debug
 * install while the watchlist UI is being built.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit = {},
    onOpenKeys: () -> Unit = {},
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        color = FinnencerColors.TextPrimary,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = FinnencerColors.TextSecondary,
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
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Watchlist coming up in Build A·6.",
                style = MaterialTheme.typography.bodyLarge,
                color = FinnencerColors.TextSecondary,
            )
            Text(
                text = "Start by configuring your API keys.",
                style = MaterialTheme.typography.bodyMedium,
                color = FinnencerColors.TextTertiary,
            )
            FilledTonalButton(
                onClick = onOpenKeys,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = FinnencerColors.Violet.copy(alpha = 0.22f),
                    contentColor = FinnencerColors.TextPrimary,
                ),
            ) {
                Icon(Icons.Default.VpnKey, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("  Manage API Keys", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
