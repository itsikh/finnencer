package io.itsikh.finnencer.ui.screens.settings

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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.BuildConfig
import io.itsikh.finnencer.ui.components.GlassCard
import io.itsikh.finnencer.ui.theme.FinnencerColors
import io.itsikh.finnencer.update.AppUpdateManager
import io.itsikh.finnencer.update.ReleaseNotesView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class ReleaseNotesUiState(
    val loading: Boolean = false,
    val notes: ReleaseNotesView? = null,
    val error: String? = null,
)

@HiltViewModel
class ReleaseNotesViewModel @Inject constructor(
    private val updater: AppUpdateManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ReleaseNotesUiState(loading = true))
    val state: StateFlow<ReleaseNotesUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val notes = updater.fetchLatestReleaseNotes()
            _state.value = if (notes == null) {
                ReleaseNotesUiState(
                    loading = false,
                    notes = null,
                    error = "Couldn't fetch release notes. Make sure a GitHub token is configured in Settings → API keys.",
                )
            } else {
                ReleaseNotesUiState(loading = false, notes = notes, error = null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseNotesScreen(onBack: () -> Unit) {
    val vm: ReleaseNotesViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "What's new",
                        style = MaterialTheme.typography.headlineMedium,
                        color = FinnencerColors.TextPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = FinnencerColors.TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = FinnencerColors.TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                state.loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 24.dp)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = FinnencerColors.Violet,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "Loading release notes…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FinnencerColors.TextSecondary,
                        )
                    }
                }

                state.error != null -> {
                    GlassCard {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                state.error.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = FinnencerColors.Coral,
                            )
                            FilledTonalButton(onClick = vm::refresh) { Text("Retry") }
                        }
                    }
                }

                state.notes != null -> {
                    val n = state.notes!!
                    ReleaseHeaderCard(notes = n)
                    GlassCard {
                        Text(
                            n.notesMarkdown,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FinnencerColors.TextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        )
                    }
                    n.htmlUrl?.let { url ->
                        FilledTonalButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        ) { Text("Open on GitHub") }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ReleaseHeaderCard(notes: ReleaseNotesView) {
    GlassCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Latest · v${notes.version}",
                    style = MaterialTheme.typography.titleMedium,
                    color = FinnencerColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            (if (notes.isNewerThanInstalled) FinnencerColors.Amber else FinnencerColors.Mint)
                                .copy(alpha = 0.18f),
                        )
                        .border(
                            1.dp,
                            (if (notes.isNewerThanInstalled) FinnencerColors.Amber else FinnencerColors.Mint)
                                .copy(alpha = 0.45f),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        if (notes.isNewerThanInstalled) "Update available" else "You're up to date",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (notes.isNewerThanInstalled) FinnencerColors.Amber else FinnencerColors.Mint,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                buildString {
                    append("Installed: v")
                    append(BuildConfig.VERSION_NAME)
                    notes.publishedAtMillis?.let { ms ->
                        append("  ·  Published ")
                        append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms)))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = FinnencerColors.TextTertiary,
            )
        }
    }
}
