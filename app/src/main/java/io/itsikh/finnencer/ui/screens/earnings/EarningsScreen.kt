package io.itsikh.finnencer.ui.screens.earnings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
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
import io.itsikh.finnencer.ui.theme.FinnencerColors
import io.itsikh.finnencer.ui.theme.MonoStyles
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EarningsScreen(
    onBack: () -> Unit,
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
                    title = { EarningsTitle(label = "${selected.size} SELECTED", sub = "TAP A ROW TO TOGGLE") },
                    navigationIcon = { EarningsChip(label = "✕", onClick = vm::clearReportSelection) },
                    actions = {
                        EarningsChip(
                            label = "DELETE",
                            accent = if (selected.isNotEmpty()) FinnencerColors.Coral else FinnencerColors.TextTertiary,
                            border = if (selected.isNotEmpty()) FinnencerColors.Coral else FinnencerColors.HairlineStrong,
                            onClick = { if (selected.isNotEmpty()) deleteSelectedConfirmOpen = true },
                        )
                        Box {
                            EarningsChip(label = "···", onClick = { overflowOpen = true })
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
                        Spacer(Modifier.size(8.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            } else {
                TopAppBar(
                    title = {
                        EarningsTitle(
                            label = "EARNINGS",
                            sub = "LAST 2 WKS  ·  NEXT 90 DAYS  ·  ${upcoming.size} UPCOMING",
                        )
                    },
                    navigationIcon = { EarningsChip(label = "← BACK", onClick = onBack) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 0.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (upcoming.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "NO EARNINGS",
                            style = MonoStyles.Brand,
                            color = FinnencerColors.TextPrimary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "BACKGROUND SYNC RUNS EVERY 15 MIN",
                            style = MonoStyles.BrandSub,
                            color = FinnencerColors.TextSecondary,
                        )
                    }
                }
            } else {
                item { SectionHead(label = "UPCOMING", count = upcoming.size, suffix = "EVENTS") }
                items(upcoming, key = { "ev-${it.id}" }) { event ->
                    EventRow(event = event, onTap = { vm.openTierPicker(event) })
                }
            }
            if (reports.isNotEmpty()) {
                item {
                    SectionHead(
                        label = "RECENT REPORTS",
                        count = reports.size,
                        suffix = if (reports.size == 1) "REPORT" else "REPORTS",
                        trailing = {
                            EarningsChip(
                                label = "DELETE ALL",
                                accent = FinnencerColors.Coral,
                                border = FinnencerColors.Coral,
                                onClick = { deleteAllConfirmOpen = true },
                            )
                        },
                    )
                }
                items(reports, key = { "rep-${it.id}" }) { r ->
                    ReportRow(
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
private fun EarningsTitle(label: String, sub: String) {
    Column {
        Text(label, style = MonoStyles.Brand, color = FinnencerColors.TextPrimary)
        Text(sub, style = MonoStyles.BrandSub, color = FinnencerColors.TextTertiary)
    }
}

@Composable
private fun EarningsChip(
    label: String,
    accent: Color = FinnencerColors.TextSecondary,
    border: Color = FinnencerColors.HairlineStrong,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MonoStyles.NavLabel, color = accent)
    }
}

@Composable
private fun SectionHead(
    label: String,
    count: Int,
    suffix: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(FinnencerColors.Hairline))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MonoStyles.SectionHead, color = FinnencerColors.TextSecondary)
            Box(modifier = Modifier.weight(1f))
            if (trailing != null) {
                trailing()
                Spacer(Modifier.width(8.dp))
            }
            Text("$count $suffix", style = MonoStyles.SectionHead, color = FinnencerColors.TextTertiary)
        }
    }
}

/**
 * Upcoming-earnings row. Tap opens the tier picker so the user can
 * generate a Brief / Standard / Deep report for the event. Status
 * tag (REPORTED / MISSED / SCHEDULED) lives on the right.
 */
@Composable
private fun EventRow(event: EarningsEvent, onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${event.tickerSymbol}  ·  Q${event.fiscalQuarter} ${event.fiscalYear}".uppercase(),
                    style = MonoStyles.NavLabel,
                    color = FinnencerColors.TextPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    FMT.format(Instant.ofEpochMilli(event.scheduledAtMillis)).uppercase(),
                    style = MonoStyles.BrandSub,
                    color = FinnencerColors.TextSecondary,
                )
            }
            StatusTag(event.status)
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(FinnencerColors.Hairline))
}

/** Mono uppercase chip — SCHEDULED / REPORTED / MISSED. */
@Composable
private fun StatusTag(status: String) {
    val color = when (status) {
        EarningsStatus.REPORTED.name -> FinnencerColors.Mint
        EarningsStatus.MISSED.name -> FinnencerColors.Coral
        else -> FinnencerColors.Violet
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(status.uppercase(), style = MonoStyles.Chip, color = color)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReportRow(
    report: EarningsReport,
    selected: Boolean,
    selectionMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val rowColor = if (selected) FinnencerColors.Violet.copy(alpha = 0.12f) else Color.Transparent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowColor)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (selected) FinnencerColors.Violet else Color.Transparent)
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
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            // Tier chip mirrors the watchlist's ALR pattern — color
            // gradient (mint=brief, amber=standard, coral=deep) maps
            // depth to perceived intensity.
            TierChip(tier = report.tier)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    report.title.uppercase(),
                    style = MonoStyles.NavLabel,
                    color = FinnencerColors.TextPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    FMT.format(Instant.ofEpochMilli(report.generatedAtMillis)).uppercase(),
                    style = MonoStyles.BrandSub,
                    color = FinnencerColors.TextTertiary,
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(FinnencerColors.Hairline))
    }
}

@Composable
private fun TierChip(tier: String) {
    val color = when (tier.uppercase()) {
        "BRIEF" -> FinnencerColors.Mint
        "STANDARD" -> FinnencerColors.Amber
        "DEEP" -> FinnencerColors.Coral
        else -> FinnencerColors.Violet
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(tier.uppercase(), style = MonoStyles.Chip, color = color)
    }
}

private val FMT = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())
