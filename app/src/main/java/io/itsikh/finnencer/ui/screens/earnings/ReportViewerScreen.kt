package io.itsikh.finnencer.ui.screens.earnings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.ExperimentalMaterial3Api
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

import io.itsikh.finnencer.ui.theme.FinnencerColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportViewerScreen(
    onBack: () -> Unit,
    onListen: (reportId: Long) -> Unit,
) {
    val vm: ReportViewerViewModel = hiltViewModel()
    val report by vm.report.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        report?.title ?: "Report",
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
                    report?.let { r ->
                        IconButton(onClick = { onListen(r.id) }) {
                            Icon(
                                Icons.Default.Headphones,
                                contentDescription = "Listen as podcast",
                                tint = FinnencerColors.Violet,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        val r = report
        if (r == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Loading report…", color = FinnencerColors.TextSecondary)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = 22.dp, vertical = 8.dp)),
        ) {
            Text(
                r.title,
                style = MaterialTheme.typography.headlineSmall,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${r.tier} · ${r.model}",
                style = MaterialTheme.typography.labelMedium,
                color = FinnencerColors.TextTertiary,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            // For MVP we render the markdown body as plain text styled with
            // light line-height. A future polish pass can swap in a real
            // markdown renderer (Markwon / RichText).
            Text(
                text = r.contentMarkdown,
                style = MaterialTheme.typography.bodyLarge,
                color = FinnencerColors.TextPrimary,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}
