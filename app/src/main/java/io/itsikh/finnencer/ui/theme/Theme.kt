package io.itsikh.finnencer.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier

/**
 * Glass Modern theme. Always dark — there is no light mode for this app.
 *
 * Picks one of the bundled [FinnencerPalette]s by [themeId] and pushes
 * it into [FinnencerColors] so every token reader in the app re-reads
 * from the new palette automatically. The palette is applied via
 * [SideEffect] so the mutation happens after the composition is
 * committed, avoiding mid-composition cascading recompositions.
 *
 * Wraps Material3 with our color/typography overrides and paints the
 * full-canvas radial gradient as the bottommost layer.
 */
@Composable
fun FinnencerTheme(
    themeId: ThemeId = ThemeId.MIDNIGHT_VIOLET,
    content: @Composable () -> Unit,
) {
    val palette = Palettes.byId(themeId)
    SideEffect { FinnencerColors.setPalette(palette) }

    val colorScheme = darkColorScheme(
        primary = palette.accent,
        onPrimary = palette.textOnAccent,
        primaryContainer = palette.accentDim,
        onPrimaryContainer = palette.textPrimary,
        secondary = palette.up,
        onSecondary = palette.textOnAccent,
        tertiary = palette.amber,
        onTertiary = palette.textOnAccent,
        background = palette.bgTop,
        onBackground = palette.textPrimary,
        surface = palette.surface,
        onSurface = palette.textPrimary,
        surfaceVariant = palette.surfaceGlass,
        onSurfaceVariant = palette.textSecondary,
        outline = palette.surfaceBorder,
        outlineVariant = palette.surfaceBorderStrong,
        error = palette.down,
        onError = palette.textOnAccent,
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FinnencerTypography,
        content = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(appBackgroundBrush()),
            ) {
                content()
            }
        },
    )
}
