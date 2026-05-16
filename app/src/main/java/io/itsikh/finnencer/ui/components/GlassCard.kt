package io.itsikh.finnencer.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.itsikh.finnencer.ui.theme.FinnencerColors
import io.itsikh.finnencer.ui.theme.glassFillBrush

/**
 * Frosted-glass surface used as the universal card primitive in finnencer.
 *
 * - Translucent fill with a vertical highlight gradient
 * - 1dp border at 10% white opacity
 * - 20dp default radius
 * - Real backdrop blur on API 31+; falls back to a slightly stronger fill on
 *   older devices so the look stays cohesive without GPU cost.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    strong: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape: Shape = RoundedCornerShape(radius)
    val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val base = modifier
        .clip(shape)
        .then(
            if (supportsBlur) {
                Modifier.graphicsLayer {
                    renderEffect = BlurEffect(
                        radiusX = 0f,
                        radiusY = 0f,
                        edgeTreatment = TileMode.Clamp,
                    )
                }
            } else Modifier,
        )
        .background(glassFillBrush(strong = strong || !supportsBlur), shape)
        .border(
            BorderStroke(
                width = 1.dp,
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(
                        FinnencerColors.SurfaceBorderStrong,
                        FinnencerColors.SurfaceBorder,
                    ),
                ),
            ),
            shape = shape,
        )

    val withClick = if (onClick != null) base.clickable(onClick = onClick) else base

    Box(modifier = withClick) {
        content()
    }
}
