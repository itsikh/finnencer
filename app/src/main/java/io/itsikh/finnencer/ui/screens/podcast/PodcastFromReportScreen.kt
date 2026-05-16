package io.itsikh.finnencer.ui.screens.podcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.ui.theme.FinnencerColors

@Composable
fun PodcastFromReportScreen(
    onReady: (podcastId: Long) -> Unit,
    onFailed: () -> Unit,
) {
    val vm: PodcastFromReportViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    LaunchedEffect(state) {
        when (val s = state) {
            is PodcastGenState.Ready -> onReady(s.podcastId)
            is PodcastGenState.Failed -> Unit // shown below; user backs out manually
            PodcastGenState.Starting -> Unit
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = FinnencerColors.Violet,
        )
        Spacer(Modifier.size(24.dp))
        Text(
            when (val s = state) {
                PodcastGenState.Starting -> "Writing two-voice script with Claude Opus, then handing to Gemini TTS…"
                is PodcastGenState.Ready -> "Ready"
                is PodcastGenState.Failed -> "Generation failed: ${s.message}"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = FinnencerColors.TextSecondary,
        )
    }
}
