package io.itsikh.finnencer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.itsikh.finnencer.ui.theme.FinnencerColors

/** Generic placeholder for routes that have not been built yet. */
@Composable
fun Placeholder(label: String, hint: String = "Coming in a later Build A phase") {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$label\n\n$hint",
            style = MaterialTheme.typography.titleMedium,
            color = FinnencerColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
