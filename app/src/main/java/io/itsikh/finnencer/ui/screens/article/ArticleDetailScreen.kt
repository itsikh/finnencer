package io.itsikh.finnencer.ui.screens.article

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.data.entity.QueueItemKind
import io.itsikh.finnencer.data.providers.stripHtmlToText
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.components.QueueToggleIconButton
import io.itsikh.finnencer.ui.theme.FinnencerColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleDetailScreen(
    onBack: () -> Unit,
    onOpenReader: () -> Unit,
) {
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
                    state.article?.let { a ->
                        QueueToggleIconButton(
                            kind = QueueItemKind.ARTICLE,
                            refId = a.id,
                            title = a.title,
                            subtitle = a.sourceName,
                            tickerSymbol = a.primaryTickerSymbol,
                        )
                    }
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

            // Source snippet (if any). Strip HTML at display time so legacy
            // rows ingested before the parse-time cleanup also render as
            // plain prose instead of raw `<p>`/`<a>` markup.
            val cleanedSnippet = remember(article.snippet) { stripHtmlToText(article.snippet) }
            val sourceUrl = article.url.takeIf { it.isNotBlank() }
            if (cleanedSnippet != null || sourceUrl != null) {
                GlassCard {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Source excerpt",
                            style = MaterialTheme.typography.labelMedium,
                            color = FinnencerColors.TextTertiary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (cleanedSnippet != null) {
                            Text(
                                cleanedSnippet,
                                style = MaterialTheme.typography.bodyMedium,
                                color = FinnencerColors.TextSecondary,
                            )
                        }
                        if (sourceUrl != null) {
                            FilledTonalButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl)))
                                    }
                                },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = FinnencerColors.SurfaceGlass,
                                    contentColor = FinnencerColors.Violet,
                                ),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(
                                    Icons.Default.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(8.dp))
                                Text("Read original article", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // AI Summary block
            SummaryBlock(
                state = state,
                onRequest = vm::requestSummary,
                onRegenerate = vm::openRegenerate,
                onOpenReader = onOpenReader,
            )

            val versions by vm.versions.collectAsState()
            if (versions.size > 1) {
                VersionsRow(versions = versions, onPick = vm::showVersion)
            }

            if (state.regenerateOpen) {
                RegenerateSheet(
                    state = state,
                    onClose = vm::closeRegenerate,
                    onSubmit = vm::regenerate,
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SummaryBlock(
    state: ArticleDetailState,
    onRequest: () -> Unit,
    onRegenerate: () -> Unit,
    onOpenReader: () -> Unit,
) {
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
                    // Action row first so Read mode + Regenerate stay
                    // reachable without having to scroll through the prose.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                io.itsikh.finnencer.ui.screens.reader.ReaderHolder.store(
                                    io.itsikh.finnencer.ui.screens.reader.ReaderHolder.Payload(
                                        title = state.article?.title ?: "Summary",
                                        body = s.text,
                                        attribution = io.itsikh.finnencer.data.ai.friendlyModelLabel(s.model)?.let { "via $it" },
                                    )
                                )
                                onOpenReader()
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = FinnencerColors.SurfaceGlass,
                                contentColor = FinnencerColors.Violet,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("Read mode", fontWeight = FontWeight.SemiBold) }
                        Spacer(Modifier.weight(1f))
                        FilledTonalButton(
                            onClick = onRegenerate,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = FinnencerColors.Violet,
                                contentColor = FinnencerColors.TextOnAccent,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("Regenerate", fontWeight = FontWeight.SemiBold) }
                    }
                    io.itsikh.finnencer.data.ai.friendlyModelLabel(s.model)?.let { label ->
                        Text(
                            "via $label",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.TextTertiary,
                        )
                    }
                    if (s.fromCache) {
                        Text(
                            "Cached locally — no extra cost on re-open.",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.TextTertiary,
                        )
                    }
                    Text(
                        s.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = FinnencerColors.TextPrimary,
                    )
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RegenerateSheet(
    state: ArticleDetailState,
    onClose: () -> Unit,
    onSubmit: (pagesTarget: Int?, customPrompt: String?) -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pages by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(2) }
    var note by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = FinnencerColors.BgTop,
        contentColor = FinnencerColors.TextPrimary,
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Regenerate summary",
                style = MaterialTheme.typography.titleLarge,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "New summary is saved as a new version — the previous ones stay available.",
                style = MaterialTheme.typography.bodySmall,
                color = FinnencerColors.TextSecondary,
            )

            Text(
                "TARGET LENGTH",
                style = MaterialTheme.typography.labelSmall,
                color = FinnencerColors.TextTertiary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val options: List<Pair<Int?, String>> = listOf(
                    null to "Short",
                    2 to "2 pp",
                    5 to "5 pp",
                    10 to "10 pp",
                )
                options.forEach { opt ->
                    val value: Int? = opt.first
                    val label: String = opt.second
                    LenChip(label = label, selected = pages == value) { pages = value }
                }
            }

            androidx.compose.material3.OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Additional instructions (optional)") },
                placeholder = {
                    Text(
                        "e.g. emphasize guidance, skip analyst opinion",
                        color = FinnencerColors.TextTertiary,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedTextColor = FinnencerColors.TextPrimary,
                    unfocusedTextColor = FinnencerColors.TextPrimary,
                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedLabelColor = FinnencerColors.Violet,
                    unfocusedLabelColor = FinnencerColors.TextTertiary,
                    focusedIndicatorColor = FinnencerColors.Violet,
                    unfocusedIndicatorColor = FinnencerColors.SurfaceBorder,
                    cursorColor = FinnencerColors.Violet,
                ),
            )

            state.regenerateError?.let { err ->
                Text(err, color = FinnencerColors.Coral, style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextButton(onClick = onClose) {
                    Text("Cancel", color = FinnencerColors.TextSecondary)
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = { onSubmit(pages, note.ifBlank { null }) },
                    enabled = !state.regenerating,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FinnencerColors.Violet,
                        contentColor = FinnencerColors.TextOnAccent,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (state.regenerating) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = FinnencerColors.TextOnAccent,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        if (state.regenerating) "Generating…" else "Generate",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun LenChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = if (selected) FinnencerColors.Violet else FinnencerColors.TextTertiary
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) FinnencerColors.Violet.copy(alpha = 0.22f) else FinnencerColors.SurfaceGlass)
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun VersionsRow(
    versions: List<io.itsikh.finnencer.data.entity.SummaryVersion>,
    onPick: (io.itsikh.finnencer.data.entity.SummaryVersion) -> Unit,
) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    io.itsikh.finnencer.ui.components.GlassCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${versions.size - 1} older version${if (versions.size - 1 == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = FinnencerColors.TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "Hide" else "Show",
                    style = MaterialTheme.typography.labelSmall,
                    color = FinnencerColors.Violet,
                )
            }
            if (expanded) {
                versions.drop(1).forEach { v ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onPick(v) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                FMT.format(java.time.Instant.ofEpochMilli(v.generatedAtMillis)),
                                style = MaterialTheme.typography.bodySmall,
                                color = FinnencerColors.TextPrimary,
                            )
                            Text(
                                buildString {
                                    append(v.model)
                                    v.pagesTarget?.let { append(" · ${it}pp") }
                                    if (!v.customPrompt.isNullOrBlank()) append(" · custom prompt")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = FinnencerColors.TextTertiary,
                            )
                        }
                        Text(
                            "Show",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.Violet,
                        )
                    }
                }
            }
        }
    }
}
