package io.itsikh.finnencer.ui.screens.keys

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.itsikh.finnencer.ui.theme.FinnencerColors

/** Stub for routes registered ahead of Build A·5 implementation. */
@Composable
fun QrPlaceholderScreen(label: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$label\n\nComing in Build A·5",
            style = MaterialTheme.typography.titleMedium,
            color = FinnencerColors.TextSecondary,
        )
    }
}
