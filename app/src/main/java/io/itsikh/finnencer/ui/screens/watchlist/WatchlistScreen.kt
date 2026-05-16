package io.itsikh.finnencer.ui.screens.watchlist

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.theme.FinnencerColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    onOpenSettings: () -> Unit,
    onOpenEarnings: () -> Unit,
    onOpenPodcasts: () -> Unit,
    onOpenTickerFeed: (symbol: String) -> Unit,
) {
    val vm: WatchlistViewModel = hiltViewModel()
    val tickers by vm.tickers.collectAsState()
    val addSheet by vm.addSheet.collectAsState()
    val settingsSheet by vm.settingsSheet.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "finnencer",
                            style = MaterialTheme.typography.headlineMedium,
                            color = FinnencerColors.TextPrimary,
                        )
                        Text(
                            text = if (tickers.isEmpty()) "No tickers yet" else "${tickers.size} tracked",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnencerColors.TextTertiary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenEarnings) {
                        Icon(
                            Icons.Default.EventNote,
                            contentDescription = "Earnings",
                            tint = FinnencerColors.TextSecondary,
                        )
                    }
                    IconButton(onClick = onOpenPodcasts) {
                        Icon(
                            Icons.Default.Headphones,
                            contentDescription = "Podcasts",
                            tint = FinnencerColors.TextSecondary,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = FinnencerColors.TextSecondary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = vm::openAddSheet,
                containerColor = FinnencerColors.Violet,
                contentColor = FinnencerColors.TextOnAccent,
                shape = CircleShape,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add ticker")
            }
        },
    ) { padding ->
        if (tickers.isEmpty()) {
            EmptyWatchlist(modifier = Modifier.padding(padding), onAdd = vm::openAddSheet)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tickers, key = { it.symbol }) { ticker ->
                    TickerCard(
                        ticker = ticker,
                        onTap = { onOpenTickerFeed(ticker.symbol) },
                        onLongPress = { vm.openSettings(ticker) },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) } // FAB clearance
            }
        }
    }

    if (addSheet.open) {
        AddTickerSheet(
            state = addSheet,
            onClose = vm::closeAddSheet,
            onQueryChange = vm::onSearchQueryChanged,
            onAdd = vm::addTicker,
        )
    }
    settingsSheet.ticker?.let {
        TickerSettingsSheet(
            state = settingsSheet,
            onClose = vm::closeSettings,
            onThreshold = vm::setDraftThreshold,
            onCap = vm::setDraftCap,
            onMuted = vm::setDraftMuted,
            onSave = vm::saveSettings,
            onRemove = { vm.removeTicker(it.symbol) },
        )
    }
}

@Composable
private fun TickerCard(
    ticker: Ticker,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    GlassCard(onClick = onTap) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Ticker monogram while we don't have logo URL data yet.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(FinnencerColors.Violet.copy(alpha = 0.18f))
                    .border(1.dp, FinnencerColors.Violet.copy(alpha = 0.40f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ticker.symbol.take(2),
                    style = MaterialTheme.typography.labelLarge,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ticker.symbol,
                    style = MaterialTheme.typography.titleLarge,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = ticker.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = FinnencerColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ThresholdPill(ticker.notificationThreshold)
            Spacer(Modifier.size(8.dp))
            if (ticker.mutedUntilMillis != null) {
                Icon(
                    Icons.Default.NotificationsOff,
                    contentDescription = "Muted",
                    tint = FinnencerColors.TextTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onLongPress) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Ticker settings",
                    tint = FinnencerColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ThresholdPill(threshold: Int) {
    val color = when {
        threshold >= 8 -> FinnencerColors.Coral
        threshold >= 6 -> FinnencerColors.Amber
        else -> FinnencerColors.Violet
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = "≥$threshold",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyWatchlist(modifier: Modifier = Modifier, onAdd: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(FinnencerColors.Violet.copy(alpha = 0.10f))
                .border(1.dp, FinnencerColors.Violet.copy(alpha = 0.30f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = FinnencerColors.Violet,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Add your first ticker",
            style = MaterialTheme.typography.headlineSmall,
            color = FinnencerColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Search for any US-traded company. finnencer pulls news every 15 min and ranks importance with Claude.",
            style = MaterialTheme.typography.bodyMedium,
            color = FinnencerColors.TextSecondary,
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(
            onClick = onAdd,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = FinnencerColors.Violet,
                contentColor = FinnencerColors.TextOnAccent,
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text("Add ticker", fontWeight = FontWeight.SemiBold)
        }
    }
}
