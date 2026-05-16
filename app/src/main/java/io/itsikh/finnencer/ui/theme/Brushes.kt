package io.itsikh.finnencer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Radial gradient covering the full app canvas. Centered slightly above the
 * vertical midpoint so the brighter hotspot reads as a "spotlight" behind the
 * top of the content.
 */
@Composable
@ReadOnlyComposable
fun appBackgroundBrush(): Brush = Brush.radialGradient(
    colorStops = arrayOf(
        0.0f to FinnencerColors.BgBottom,
        0.55f to FinnencerColors.BgTop,
        1.0f to FinnencerColors.BgTop,
    ),
    center = Offset(0.5f * 1080f, 0.25f * 2400f),
    radius = 1800f,
)

@Composable
@ReadOnlyComposable
fun glassFillBrush(strong: Boolean = false): Brush = Brush.verticalGradient(
    colors = if (strong) {
        listOf(
            FinnencerColors.SurfaceGlassStrong,
            FinnencerColors.SurfaceGlass,
        )
    } else {
        listOf(
            FinnencerColors.SurfaceGlass,
            FinnencerColors.SurfaceGlass.copy(alpha = 0.03f),
        )
    },
)

@Composable
@ReadOnlyComposable
fun scoreBadgeBrush(score: Int): Brush {
    val color: Color = when {
        score >= 9 -> FinnencerColors.Coral
        score >= 7 -> FinnencerColors.Amber
        score >= 4 -> FinnencerColors.Violet
        else -> FinnencerColors.Neutral
    }
    return Brush.linearGradient(
        colors = listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0.55f)),
    )
}

@Composable
@ReadOnlyComposable
fun directionalLineBrush(positive: Boolean): Brush {
    val color = if (positive) FinnencerColors.Up else FinnencerColors.Down
    return Brush.verticalGradient(
        colors = listOf(color, color.copy(alpha = 0.2f)),
    )
}
