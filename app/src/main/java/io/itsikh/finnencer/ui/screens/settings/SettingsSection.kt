package io.itsikh.finnencer.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.itsikh.finnencer.ui.theme.FinnencerColors
import io.itsikh.finnencer.ui.theme.MonoStyles

/**
 * Terminal Pro settings section. A single hairline + section title
 * above the rows, no card surface — the row hairlines provide the
 * visual structure.
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(FinnencerColors.Hairline),
        )
        Text(
            text = title.uppercase(),
            style = MonoStyles.SectionHead,
            color = FinnencerColors.TextTertiary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
        content()
    }
}

/**
 * Terminal Pro settings row. Mono uppercase title, dim mono sub. The
 * leading icon — when given — sits as a small tinted glyph next to
 * the title rather than as a round chip; this keeps row height tight.
 * Tappable rows show a typographic `→` chevron when no trailing
 * content is supplied. A 1dp hairline closes each row at the bottom.
 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    @Suppress("UNUSED_PARAMETER")
    icon: ImageVector? = null,
    iconTint: Color = FinnencerColors.Violet,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val rowMod = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp)
        Row(modifier = rowMod, verticalAlignment = Alignment.CenterVertically) {
            // Subtle leading accent bar tied to the previous iconTint so
            // categories (mint = money, amber = warning, violet = AI)
            // still scan as different.
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 36.dp)
                    .background(iconTint),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title.uppercase(),
                    style = MonoStyles.NavLabel,
                    color = FinnencerColors.TextPrimary,
                )
                subtitle?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        it.uppercase(),
                        style = MonoStyles.BrandSub,
                        color = FinnencerColors.TextTertiary,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            } else if (onClick != null) {
                Text(
                    "→",
                    style = MonoStyles.NavLabel,
                    color = FinnencerColors.TextTertiary,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(FinnencerColors.Hairline),
        )
    }
}
