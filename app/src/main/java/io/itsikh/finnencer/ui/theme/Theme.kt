package io.itsikh.finnencer.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier

/**
 * Terminal Pro theme. Picks one of the bundled [FinnencerPalette]s
 * by [themeId] and pushes it into [FinnencerColors] so every token
 * reader in the app re-reads from the new palette automatically.
 *
 * The palette is applied via [SideEffect] so the mutation happens
 * after the composition is committed, avoiding mid-composition
 * cascading recompositions.
 */
@Composable
fun FinnencerTheme(
    themeId: ThemeId = ThemeId.TERMINAL_PRO,
    content: @Composable () -> Unit,
) {
    val palette = Palettes.byId(themeId)
    SideEffect { FinnencerColors.setPalette(palette) }

    val colorScheme = if (palette.isLight) {
        lightColorScheme(
            primary = palette.violet,
            onPrimary = palette.textOnAccent,
            primaryContainer = palette.violetDim,
            onPrimaryContainer = palette.textPrimary,
            secondary = palette.mint,
            onSecondary = palette.textOnAccent,
            tertiary = palette.amber,
            onTertiary = palette.textOnAccent,
            background = palette.canvas,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surface,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.hairline,
            outlineVariant = palette.hairlineStrong,
            error = palette.coral,
            onError = palette.textOnAccent,
        )
    } else {
        darkColorScheme(
            primary = palette.violet,
            onPrimary = palette.textOnAccent,
            primaryContainer = palette.violetDim,
            onPrimaryContainer = palette.textPrimary,
            secondary = palette.mint,
            onSecondary = palette.textOnAccent,
            tertiary = palette.amber,
            onTertiary = palette.textOnAccent,
            background = palette.canvas,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surface,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.hairline,
            outlineVariant = palette.hairlineStrong,
            error = palette.coral,
            onError = palette.textOnAccent,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FinnencerTypography,
        content = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.canvas),
            ) {
                content()
            }
        },
    )
}
