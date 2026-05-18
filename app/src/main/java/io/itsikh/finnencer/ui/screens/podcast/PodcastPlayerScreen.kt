package io.itsikh.finnencer.ui.screens.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.data.entity.PodcastGenerationStatus
import io.itsikh.finnencer.data.entity.QueueItemKind
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.components.QueueToggleIconButton
import io.itsikh.finnencer.ui.theme.FinnencerColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastPlayerScreen(
    onBack: () -> Unit,
    /** Called on auto-play-next with (nextPodcastId, launchSource).
     *  launchSource is passed through so a podcast opened from the
     *  queue keeps the "play through" behavior all the way down the
     *  chain. */
    onOpenPodcast: (Long, String) -> Unit = { _, _ -> },
    onOpenReader: () -> Unit = {},
) {
    val vm: PodcastPlayerViewModel = hiltViewModel()
    val podcast by vm.podcast.collectAsState()
    val ui by vm.ui.collectAsState()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.navigateToNext.collect { nextId -> onOpenPodcast(nextId, vm.launchSource) }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        podcast?.title ?: "Podcast",
                        style = MaterialTheme.typography.titleMedium,
                        color = FinnencerColors.TextPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = FinnencerColors.TextPrimary,
                        )
                    }
                },
                actions = {
                    podcast?.let { p ->
                        QueueToggleIconButton(
                            kind = QueueItemKind.PODCAST,
                            refId = p.id.toString(),
                            title = p.title,
                            subtitle = p.durationMs?.let { "${it / 1000 / 60} min" },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        val p = podcast
        if (p == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Loading…", color = FinnencerColors.TextSecondary)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Defense for small phones (Samsung S918B fits the controls
                // by a hair) — if the column ever exceeds the viewport the
                // user can still reach the speed chips.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))

            // Art panel. A soft violet→mint radial-ish gradient with a big
            // headphones glyph reads better than the bare "Charon · Aoede"
            // voice-pair label that used to occupy this card.
            GlassCard {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 240.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Subtle accent backdrop
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.radialGradient(
                                    colors = listOf(
                                        FinnencerColors.Violet.copy(alpha = 0.20f),
                                        Color.Transparent,
                                    ),
                                )
                            )
                    )
                    Icon(
                        imageVector = Icons.Default.Headphones,
                        contentDescription = null,
                        tint = FinnencerColors.Violet.copy(alpha = 0.70f),
                        modifier = Modifier.size(96.dp),
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    p.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${p.voiceHost} · ${p.voiceAnalyst ?: "—"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = FinnencerColors.TextTertiary,
                )
            }

            when (p.status) {
                PodcastGenerationStatus.PENDING.name, PodcastGenerationStatus.GENERATING.name -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = FinnencerColors.Violet,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "Generating two-voice podcast…",
                            color = FinnencerColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                PodcastGenerationStatus.FAILED.name -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        p.generationError ?: "Generation failed",
                        color = FinnencerColors.Coral,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val retryStatus by vm.retryStatus.collectAsState()
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                    ) {
                        androidx.compose.material3.FilledTonalButton(
                            onClick = vm::retry,
                            enabled = retryStatus != PodcastPlayerViewModel.RetryStatus.Retrying,
                            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                                containerColor = FinnencerColors.Violet,
                                contentColor = FinnencerColors.TextOnAccent,
                            ),
                        ) {
                            if (retryStatus == PodcastPlayerViewModel.RetryStatus.Retrying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = FinnencerColors.TextOnAccent,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.size(8.dp))
                                Text("Retrying…")
                            } else {
                                Text("Retry")
                            }
                        }
                        // If the script was already produced before the
                        // TTS step blew up, give the user a way to read
                        // the dialogue text now instead of waiting for
                        // audio to ever succeed (#42 fallback).
                        if (!p.scriptText.isNullOrBlank()) {
                            androidx.compose.material3.FilledTonalButton(
                                onClick = {
                                    io.itsikh.finnencer.ui.screens.reader.ReaderHolder.store(
                                        io.itsikh.finnencer.ui.screens.reader.ReaderHolder.Payload(
                                            title = p.title,
                                            body = p.scriptText,
                                            attribution = "Podcast script · ${p.voiceHost} · ${p.voiceAnalyst ?: ""}",
                                        )
                                    )
                                    onOpenReader()
                                },
                                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                                    containerColor = FinnencerColors.SurfaceGlass,
                                    contentColor = FinnencerColors.TextPrimary,
                                ),
                            ) {
                                Text("Read the script")
                            }
                        }
                    }
                    val rs = retryStatus
                    when (rs) {
                        is PodcastPlayerViewModel.RetryStatus.Error -> Text(
                            rs.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.Coral,
                        )
                        PodcastPlayerViewModel.RetryStatus.Success -> Text(
                            "Re-queued — check the Tasks screen for live progress.",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.Mint,
                        )
                        else -> p.scriptText?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                "${it.length} characters of dialogue are saved locally — no internet needed.",
                                style = MaterialTheme.typography.labelSmall,
                                color = FinnencerColors.TextTertiary,
                            )
                        }
                    }
                }
                PodcastGenerationStatus.WAITING_FOR_NETWORK.name -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = FinnencerColors.Violet,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "Waiting for usable internet…",
                            color = FinnencerColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    p.generationError?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.TextTertiary,
                        )
                    }
                    Text(
                        "Generation will start automatically when Anthropic and Gemini are reachable.",
                        style = MaterialTheme.typography.labelSmall,
                        color = FinnencerColors.TextTertiary,
                    )
                }
                PodcastGenerationStatus.READY.name -> {
                    PlayerControls(
                        positionMs = ui.positionMs,
                        durationMs = if (ui.durationMs > 0) ui.durationMs else (p.durationMs ?: 0L),
                        isPlaying = ui.isPlaying,
                        speed = ui.speed,
                        onPlayPause = vm::playPause,
                        onSkipBack = vm::skipBack,
                        onSkipForward = vm::skipForward,
                        onSeek = vm::seekTo,
                        onSpeed = vm::setSpeed,
                    )
                }
            }

            // Bottom safe-area spacer — Scaffold's contentPadding already
            // includes navigationBars, but Samsung's gesture strip sits
            // partially inside the inset and chips of size ~36dp benefit
            // from a small extra cushion.
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PlayerControls(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    speed: Float,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Slider(
            value = if (durationMs > 0) positionMs / durationMs.toFloat() else 0f,
            onValueChange = { v -> onSeek((v * durationMs).toLong()) },
            colors = SliderDefaults.colors(
                thumbColor = FinnencerColors.Violet,
                activeTrackColor = FinnencerColors.Violet,
                inactiveTrackColor = FinnencerColors.SurfaceGlassStrong,
            ),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt(positionMs), style = MaterialTheme.typography.labelMedium, color = FinnencerColors.TextSecondary)
            Text(fmt(durationMs), style = MaterialTheme.typography.labelMedium, color = FinnencerColors.TextSecondary)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onSkipBack) {
                Icon(Icons.Default.Replay30, contentDescription = "-30s", tint = FinnencerColors.TextPrimary, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.size(24.dp))
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(FinnencerColors.Violet)
                    .border(2.dp, FinnencerColors.Violet.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onPlayPause, modifier = Modifier.size(80.dp)) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = FinnencerColors.TextOnAccent,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Spacer(Modifier.size(24.dp))
            IconButton(onClick = onSkipForward) {
                Icon(Icons.Default.Forward30, contentDescription = "+30s", tint = FinnencerColors.TextPrimary, modifier = Modifier.size(36.dp))
            }
        }
        // Speed chips. Equal-weight so all five fit in one row on any
        // phone (Samsung S918B previously wrapped "2.0x" to a second
        // line). Labels drop the .0 suffix to save horizontal space.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { s ->
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = speed == s,
                    onClick = { onSpeed(s) },
                    label = {
                        Text(
                            formatSpeedLabel(s),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = FinnencerColors.SurfaceGlass,
                        labelColor = FinnencerColors.TextSecondary,
                        selectedContainerColor = FinnencerColors.Violet.copy(alpha = 0.25f),
                        selectedLabelColor = FinnencerColors.TextPrimary,
                    ),
                )
            }
        }
    }
}

private fun formatSpeedLabel(s: Float): String {
    // "1x" / "1.25x" / "1.5x" / "2x" — drop trailing zero so chips fit
    // comfortably across a 5-column equal-weight row.
    val rounded = if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0').trimEnd('.')
    return "${rounded}x"
}

private fun fmt(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
