package io.itsikh.finnencer.ui.screens.article

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.theme.FinnencerColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleDetailScreen(onBack: () -> Unit) {
    val vm: ArticleDetailViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.article?.primaryTickerSymbol ?: "Article",
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
                    state.article?.url?.let { url ->
                        IconButton(onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }) {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = "Open in browser",
                                tint = FinnencerColors.TextSecondary,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        val article = state.article
        if (article == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Article not found.",
                    color = FinnencerColors.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = 20.dp, vertical = 8.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Headline + meta
            Text(
                text = article.title,
                style = MaterialTheme.typography.headlineSmall,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    article.sourceName,
                    style = MaterialTheme.typography.labelMedium,
                    color = FinnencerColors.TextSecondary,
                )
                Text("  ·  ", color = FinnencerColors.TextTertiary)
                Text(
                    FMT.format(Instant.ofEpochMilli(article.publishedAtMillis)),
                    style = MaterialTheme.typography.labelMedium,
                    color = FinnencerColors.TextSecondary,
                )
            }

            // Per-ticker scores
            if (state.scores.isNotEmpty()) {
                GlassCard {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Importance",
                            style = MaterialTheme.typography.labelMedium,
                            color = FinnencerColors.TextTertiary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        state.scores.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ScoreNumber(s.score)
                                Spacer(Modifier.size(10.dp))
                                Column {
                                    Text(
                                        "${s.tickerSymbol}  ·  ${s.category.lowercase().replace('_', ' ')}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = FinnencerColors.TextPrimary,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    if (s.reason.isNotBlank()) {
                                        Text(
                                            s.reason,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = FinnencerColors.TextSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Source snippet (if any)
            article.snippet?.takeIf { it.isNotBlank() }?.let { snippet ->
                GlassCard {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Source excerpt",
                            style = MaterialTheme.typography.labelMedium,
                            color = FinnencerColors.TextTertiary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            snippet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FinnencerColors.TextSecondary,
                        )
                    }
                }
            }

            // AI Summary block
            SummaryBlock(state = state, onRequest = vm::requestSummary)

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SummaryBlock(state: ArticleDetailState, onRequest: () -> Unit) {
    GlassCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = FinnencerColors.Violet,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "AI Summary",
                    style = MaterialTheme.typography.titleMedium,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            when (val s = state.summary) {
                SummaryState.Idle -> {
                    Text(
                        "Tap to get a 4-6 sentence summary tailored to a holder of $${state.article?.primaryTickerSymbol}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FinnencerColors.TextSecondary,
                    )
                    FilledTonalButton(
                        onClick = onRequest,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = FinnencerColors.Violet,
                            contentColor = FinnencerColors.TextOnAccent,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Generate", fontWeight = FontWeight.SemiBold) }
                }
                SummaryState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = FinnencerColors.Violet,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "Asking Claude Sonnet…",
                        color = FinnencerColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is SummaryState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        s.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = FinnencerColors.TextPrimary,
                    )
                    if (s.fromCache) {
                        Text(
                            "Cached locally — no extra cost on re-open.",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.TextTertiary,
                        )
                    }
                }
                is SummaryState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        s.message,
                        color = FinnencerColors.Coral,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(
                        onClick = onRequest,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = FinnencerColors.Violet,
                            contentColor = FinnencerColors.TextOnAccent,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun ScoreNumber(score: Int) {
    val color = when {
        score >= 9 -> FinnencerColors.Coral
        score >= 7 -> FinnencerColors.Amber
        score >= 4 -> FinnencerColors.Violet
        else -> FinnencerColors.Neutral
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(color.copy(alpha = 0.20f))
            .border(1.dp, color.copy(alpha = 0.40f), androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            score.toString(),
            color = color,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

private val FMT = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm").withZone(ZoneId.systemDefault())
