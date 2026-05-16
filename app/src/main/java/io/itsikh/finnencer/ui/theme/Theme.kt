package io.itsikh.finnencer.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Glass Modern theme. Always dark — there is no light mode for this app.
 *
 * Wraps Material3 with our color/typography overrides and paints the
 * full-canvas radial gradient as the bottommost layer.
 */
@Composable
fun FinnencerTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = FinnencerColors.Violet,
        onPrimary = FinnencerColors.TextOnAccent,
        primaryContainer = FinnencerColors.VioletDim,
        onPrimaryContainer = FinnencerColors.TextPrimary,
        secondary = FinnencerColors.Mint,
        onSecondary = FinnencerColors.TextOnAccent,
        tertiary = FinnencerColors.Amber,
        onTertiary = FinnencerColors.TextOnAccent,
        background = FinnencerColors.BgTop,
        onBackground = FinnencerColors.TextPrimary,
        surface = FinnencerColors.Surface,
        onSurface = FinnencerColors.TextPrimary,
        surfaceVariant = FinnencerColors.SurfaceGlass,
        onSurfaceVariant = FinnencerColors.TextSecondary,
        outline = FinnencerColors.SurfaceBorder,
        outlineVariant = FinnencerColors.SurfaceBorderStrong,
        error = FinnencerColors.Coral,
        onError = FinnencerColors.TextOnAccent,
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
