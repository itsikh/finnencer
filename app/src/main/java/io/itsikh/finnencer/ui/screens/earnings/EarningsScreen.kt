package io.itsikh.finnencer.ui.screens.earnings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.data.entity.EarningsEvent
import io.itsikh.finnencer.data.entity.EarningsReport
import io.itsikh.finnencer.data.entity.EarningsStatus
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.theme.FinnencerColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EarningsScreen(
    onBack: (() -> Unit)? = null,
    onOpenReport: (Long) -> Unit,
) {
    val vm: EarningsViewModel = hiltViewModel()
    val upcoming by vm.upcoming.collectAsState()
    val reports by vm.recentReports.collectAsState()
    val picker by vm.picker.collectAsState()
    val selected by vm.selectedReportIds.collectAsState()
    val isSelecting by vm.isSelecting.collectAsState()

    var deleteSelectedConfirmOpen by remember { mutableStateOf(false) }
    var deleteAllConfirmOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }

    LaunchedEffect(picker.producedReportId) {
        picker.producedReportId?.let {
            vm.closeTierPicker()
            onOpenReport(it)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            // Top app bar morphs into a selection bar when items are selected
            // — same pattern as Gmail/Photos: contextual actions take over
            // while in multi-select mode.
            if (isSelecting) {
                TopAppBar(
                    title = {
                        Text(
                            "${selected.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            color = FinnencerColors.TextPrimary,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = vm::clearReportSelection) {
                            Icon(Icons.Default.Close, "Cancel selection", tint = FinnencerColors.TextPrimary)
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { deleteSelectedConfirmOpen = true },
                            enabled = selected.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.Delete, "Delete selected", tint = FinnencerColors.Coral)
                        }
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Default.MoreVert, "More", tint = FinnencerColors.TextPrimary)
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Select all") },
                                    onClick = {
                                        vm.selectAllReports()
                                        overflowOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Invert selection") },
                                    onClick = {
                                        vm.invertReportSelection()
                                        overflowOpen = false
                                    },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            "Earnings",
                            style = MaterialTheme.typography.headlineMedium,
                            color = FinnencerColors.TextPrimary,
                        )
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = FinnencerColors.TextPrimary,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Upcoming · last 2 weeks + next 90 days",
                    style = MaterialTheme.typography.labelLarge,
                    color = FinnencerColors.TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (upcoming.isEmpty()) {
                item {
                    Text(
                        "No earnings on the calendar yet. Background sync populates this every 15 min.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FinnencerColors.TextTertiary,
                    )
                }
            } else {
                items(upcoming, key = { "ev-${it.id}" }) { event ->
                    EventCard(event = event, onTap = { vm.openTierPicker(event) })
                }
            }
            if (reports.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Recent reports",
                            style = MaterialTheme.typography.labelLarge,
                            color = FinnencerColors.TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { deleteAllConfirmOpen = true },
                        ) {
                            Text(
                                "Delete all",
                                style = MaterialTheme.typography.labelSmall,
                                color = FinnencerColors.Coral,
                            )
                        }
                    }
                    Text(
                        "Long-press a report to select multiple, then delete.",
                        style = MaterialTheme.typography.labelSmall,
                        color = FinnencerColors.TextTertiary,
                    )
                }
                items(reports, key = { "rep-${it.id}" }) { r ->
                    ReportListCard(
                        report = r,
                        selected = r.id in selected,
                        selectionMode = isSelecting,
                        onTap = {
                            if (isSelecting) vm.toggleReportSelection(r.id)
                            else onOpenReport(r.id)
                        },
                        onLongPress = { vm.toggleReportSelection(r.id) },
                    )
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    picker.event?.let {
        TierPickerSheet(
            state = picker,
            onClose = vm::closeTierPicker,
            onPick = vm::generate,
        )
    }

    if (deleteSelectedConfirmOpen) {
        AlertDialog(
            onDismissRequest = { deleteSelectedConfirmOpen = false },
            title = { Text("Delete ${selected.size} reports?") },
            text = { Text("This can't be undone. Underlying earnings calendar entries stay intact.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSelectedReports()
                    deleteSelectedConfirmOpen = false
                }) { Text("Delete", color = FinnencerColors.Coral) }
            },
            dismissButton = {
                TextButton(onClick = { deleteSelectedConfirmOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (deleteAllConfirmOpen) {
        AlertDialog(
            onDismissRequest = { deleteAllConfirmOpen = false },
            title = { Text("Delete all ${reports.size} reports?") },
            text = { Text("Wipes every cached AI report across all tickers. Calendar entries are untouched; you can regenerate any time.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAllReports()
                    deleteAllConfirmOpen = false
                }) { Text("Delete all", color = FinnencerColors.Coral) }
            },
            dismissButton = {
                TextButton(onClick = { deleteAllConfirmOpen = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EventCard(event: EarningsEvent, onTap: () -> Unit) {
    GlassCard(onClick = onTap) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${event.tickerSymbol}  ·  Q${event.fiscalQuarter} ${event.fiscalYear}",
                    style = MaterialTheme.typography.titleMedium,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    FMT.format(Instant.ofEpochMilli(event.scheduledAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = FinnencerColors.TextSecondary,
                )
            }
            StatusPill(event.status)
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val color = when (status) {
        EarningsStatus.REPORTED.name -> FinnencerColors.Mint
        EarningsStatus.MISSED.name -> FinnencerColors.Coral
        else -> FinnencerColors.Violet
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.20f))
            .border(1.dp, color.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            status.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReportListCard(
    report: EarningsReport,
    selected: Boolean,
    selectionMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val rowColor = if (selected) FinnencerColors.Violet.copy(alpha = 0.22f) else Color.Transparent
    GlassCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                .background(rowColor)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) FinnencerColors.Violet else Color.Transparent,
                        )
                        .border(
                            1.dp,
                            if (selected) FinnencerColors.Violet else FinnencerColors.TextTertiary,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = FinnencerColors.TextOnAccent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    report.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        report.tier.lowercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = FinnencerColors.Violet,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        FMT.format(Instant.ofEpochMilli(report.generatedAtMillis)),
                        style = MaterialTheme.typography.labelSmall,
                        color = FinnencerColors.TextTertiary,
                    )
                }
            }
        }
    }
}

private val FMT = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())
