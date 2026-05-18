package io.itsikh.finnencer.ui.screens.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import io.itsikh.finnencer.data.repo.TickerSearchResult
import io.itsikh.finnencer.ui.theme.FinnencerColors
import io.itsikh.finnencer.ui.theme.MonoStyles

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
        containerColor = FinnencerColors.Canvas,
        contentColor = FinnencerColors.TextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                text = "ADD TICKER",
                style = MonoStyles.Brand,
                color = FinnencerColors.TextPrimary,
            )
            Text(
                text = "SYMBOL OR NAME  ·  US COMMON STOCK",
                style = MonoStyles.BrandSub,
                color = FinnencerColors.TextTertiary,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        "NVDA, MICROSOFT, ASML",
                        style = MonoStyles.NavLabel,
                        color = FinnencerColors.TextTertiary,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(6.dp),
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
                    unfocusedIndicatorColor = FinnencerColors.HairlineStrong,
                    cursorColor = FinnencerColors.Violet,
                ),
            )
            Spacer(Modifier.height(8.dp))

            state.error?.let { err ->
                Text(
                    err.uppercase(),
                    color = FinnencerColors.Coral,
                    style = MonoStyles.BrandSub,
                )
                Spacer(Modifier.height(4.dp))
            }

            when {
                state.query.isBlank() -> Text(
                    "TYPE A SYMBOL OR COMPANY NAME TO SEARCH.",
                    style = MonoStyles.BrandSub,
                    color = FinnencerColors.TextTertiary,
                )
                !state.loading && state.results.isEmpty() && state.error == null -> Text(
                    "NO MATCHES.",
                    style = MonoStyles.BrandSub,
                    color = FinnencerColors.TextTertiary,
                )
                state.results.isNotEmpty() -> Text(
                    "MATCHES  ·  ${state.results.size}",
                    style = MonoStyles.SectionHead,
                    color = FinnencerColors.TextSecondary,
                )
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items(state.results, key = { it.symbol }) { result ->
                    SearchResultRow(result = result, onClick = { onAdd(result) })
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }
}

/**
 * Terminal-style row for a search result. Tap the whole row to add.
 * Symbol on the left in mono, company name on the right in dim mono,
 * + ADD chip on the trailing edge for an explicit primary affordance.
 */
@Composable
private fun SearchResultRow(result: TickerSearchResult, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(1.dp).background(FinnencerColors.Hairline),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = result.displaySymbol,
                style = MonoStyles.NavLabel,
                color = FinnencerColors.TextPrimary,
                modifier = Modifier.padding(end = 14.dp),
            )
            Text(
                text = result.description.uppercase(),
                style = MonoStyles.BrandSub,
                color = FinnencerColors.TextSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, FinnencerColors.Violet, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("+ ADD", style = MonoStyles.NavLabel, color = FinnencerColors.Violet)
            }
        }
    }
}
