package io.itsikh.finnencer.ui.screens.cost

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.itsikh.finnencer.data.dao.ProviderUsageRow
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.theme.FinnencerColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CostMeterScreen(onBack: () -> Unit) {
    val vm: CostMeterViewModel = hiltViewModel()
    val window by vm.window.collectAsState()
    val rows by vm.rollup.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "API costs",
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CostWindow.entries.forEach { w ->
                    FilterChip(
                        selected = window == w,
                        onClick = { vm.setWindow(w) },
                        label = { Text(w.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = FinnencerColors.SurfaceGlass,
                            labelColor = FinnencerColors.TextSecondary,
                            selectedContainerColor = FinnencerColors.Violet.copy(alpha = 0.25f),
                            selectedLabelColor = FinnencerColors.TextPrimary,
                        ),
                    )
                }
            }
            val totalCents = rows.sumOf { it.cost_millicents } / 1000.0
            Text(
                "Total · $${"%.4f".format(totalCents / 100)}",
                style = MaterialTheme.typography.displaySmall,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(
                "Estimates only. Real billing comes from the provider.",
                style = MaterialTheme.typography.labelSmall,
                color = FinnencerColors.TextTertiary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (rows.isEmpty()) {
                    item {
                        Text(
                            "Nothing recorded in this window yet.",
                            color = FinnencerColors.TextTertiary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(rows, key = { it.provider }) { row ->
                    ProviderRow(row = row)
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }
}

@Composable
private fun ProviderRow(row: ProviderUsageRow) {
    GlassCard {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.provider,
                    style = MaterialTheme.typography.titleMedium,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "$${"%.4f".format((row.cost_millicents / 1000.0) / 100.0)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = FinnencerColors.Mint,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "${row.calls} calls · ${row.tokens_in}+${row.tokens_out} tokens",
                style = MaterialTheme.typography.bodySmall,
                color = FinnencerColors.TextSecondary,
            )
        }
    }
}
