package io.itsikh.finnencer.ui.screens.earnings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarningsScreen(
    onBack: () -> Unit,
    onOpenReport: (Long) -> Unit,
) {
    val vm: EarningsViewModel = hiltViewModel()
    val upcoming by vm.upcoming.collectAsState()
    val reports by vm.recentReports.collectAsState()
    val picker by vm.picker.collectAsState()

    // Navigate into the freshly-generated report when ready.
    LaunchedEffect(picker.producedReportId) {
        picker.producedReportId?.let {
            vm.closeTierPicker()
            onOpenReport(it)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Earnings",
                        style = MaterialTheme.typography.headlineMedium,
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
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
                items(upcoming, key = { it.id }) { event ->
                    EventCard(event = event, onTap = { vm.openTierPicker(event) })
                }
            }
            if (reports.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Recent reports",
                        style = MaterialTheme.typography.labelLarge,
                        color = FinnencerColors.TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(reports, key = { it.id }) { r ->
                    ReportListCard(r, onTap = { onOpenReport(r.id) })
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

@Composable
private fun ReportListCard(report: EarningsReport, onTap: () -> Unit) {
    GlassCard(onClick = onTap) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
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

private val FMT = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())
