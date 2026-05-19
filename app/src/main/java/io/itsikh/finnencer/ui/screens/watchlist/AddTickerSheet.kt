package io.itsikh.finnencer.ui.screens.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import io.itsikh.finnencer.data.repo.TickerSearchResult
import io.itsikh.finnencer.ui.theme.FinnencerColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTickerSheet(
    state: AddSheetState,
    onClose: () -> Unit,
    onQueryChange: (String) -> Unit,
    onAdd: (TickerSearchResult) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = FinnencerColors.BgTop,
        contentColor = FinnencerColors.TextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Add ticker",
                style = MaterialTheme.typography.headlineSmall,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search e.g. NVDA, Microsoft, ASML", color = FinnencerColors.TextTertiary) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = FinnencerColors.TextSecondary,
                    )
                },
                trailingIcon = {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = FinnencerColors.Violet,
                            strokeWidth = 2.dp,
                        )
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = FinnencerColors.TextPrimary,
                    unfocusedTextColor = FinnencerColors.TextPrimary,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = FinnencerColors.Violet,
                    unfocusedIndicatorColor = FinnencerColors.SurfaceBorder,
                    cursorColor = FinnencerColors.Violet,
                ),
            )

            state.error?.let { err ->
                Text(err, color = FinnencerColors.Coral, style = MaterialTheme.typography.bodySmall)
            }

            if (state.query.isBlank()) {
                Text(
                    "Symbol or company name. Common stock on US exchanges only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FinnencerColors.TextTertiary,
                )
            } else if (!state.loading && state.results.isEmpty() && state.error == null) {
                Text(
                    "No matches.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FinnencerColors.TextTertiary,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.results, key = { it.symbol }) { result ->
                    SearchResultRow(result = result, onClick = { onAdd(result) })
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: TickerSearchResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(FinnencerColors.SurfaceGlass)
            .border(
                1.dp,
                FinnencerColors.SurfaceBorder,
                androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(FinnencerColors.Violet.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = result.symbol.take(2),
                style = MaterialTheme.typography.labelMedium,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.displaySymbol,
                style = MaterialTheme.typography.titleMedium,
                color = FinnencerColors.TextPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = result.description,
                style = MaterialTheme.typography.bodySmall,
                color = FinnencerColors.TextSecondary,
                maxLines = 1,
            )
        }
        androidx.compose.material3.IconButton(
            onClick = onClick,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add ${result.symbol}",
                tint = FinnencerColors.Violet,
            )
        }
    }
}
