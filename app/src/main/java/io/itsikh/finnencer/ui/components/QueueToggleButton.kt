package io.itsikh.finnencer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.itsikh.finnencer.data.entity.QueueItemKind
import io.itsikh.finnencer.data.repo.QueueRepository
import io.itsikh.finnencer.ui.theme.FinnencerColors
import kotlinx.coroutines.launch

/**
 * Hilt entry point used by the Queue-toggle composables. They live in
 * the UI layer and don't have a ViewModel of their own — pulling the
 * repository directly via [EntryPointAccessors] keeps the call sites
 * trivial ("drop a QueueTogglePill anywhere").
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface QueueToggleEntryPoint {
    fun queueRepository(): QueueRepository
}

@Composable
private fun rememberQueueRepository(): QueueRepository {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            QueueToggleEntryPoint::class.java,
        ).queueRepository()
    }
}

/**
 * Pill-style "+ Queue" / "✓ Queued" toggle. Drop into a header / row
 * trailing area to let the user add or remove the current piece of
 * content from their reading queue.
 */
@Composable
fun QueueTogglePill(
    kind: QueueItemKind,
    refId: String,
    title: String,
    subtitle: String? = null,
    tickerSymbol: String? = null,
    modifier: Modifier = Modifier,
) {
    val repo = rememberQueueRepository()
    val scope = rememberCoroutineScope()
    var queued by remember(kind, refId) { mutableStateOf(false) }

    LaunchedEffect(kind, refId) {
        repo.observeByRef(kind, refId).collect { queued = it != null }
    }

    val color = if (queued) FinnencerColors.Mint else FinnencerColors.Violet

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.40f), RoundedCornerShape(20.dp))
            .clickable {
                scope.launch { repo.toggle(kind, refId, title, subtitle, tickerSymbol) }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (queued) Icons.Default.BookmarkAdded else Icons.Default.Bookmark,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (queued) "Queued" else "Queue",
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Icon-only Queue toggle for tight spots (top-bar actions, list-row
 * trailing icons).
 */
@Composable
fun QueueToggleIconButton(
    kind: QueueItemKind,
    refId: String,
    title: String,
    subtitle: String? = null,
    tickerSymbol: String? = null,
    modifier: Modifier = Modifier,
) {
    val repo = rememberQueueRepository()
    val scope = rememberCoroutineScope()
    var queued by remember(kind, refId) { mutableStateOf(false) }

    LaunchedEffect(kind, refId) {
        repo.observeByRef(kind, refId).collect { queued = it != null }
    }

    IconButton(
        modifier = modifier,
        onClick = {
            scope.launch { repo.toggle(kind, refId, title, subtitle, tickerSymbol) }
        },
    ) {
        Icon(
            if (queued) Icons.Default.BookmarkAdded else Icons.Default.Bookmark,
            contentDescription = if (queued) "Remove from queue" else "Add to queue",
            tint = if (queued) FinnencerColors.Mint else FinnencerColors.TextSecondary,
        )
    }
}
