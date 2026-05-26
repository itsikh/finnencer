package io.itsikh.finnencer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.itsikh.finnencer.ui.theme.FinnencerColors

/**
 * Minimalist intraday sparkline drawn in pure Canvas — no chart library
 * dependency. Sized to fit between the company name column and the
 * price column on a watchlist row.
 *
 * Coloring follows the price direction: last close vs. first close.
 * Mint for up, Coral for down, secondary text color for flat or when
 * fewer than 2 points are available (we draw a dashed flat line as a
 * placeholder so the row's column geometry stays stable).
 *
 * The line is drawn with [StrokeCap.Round] so it stays legible at
 * 1.5dp width on small surfaces.
 */
@Composable
fun Sparkline(
    closes: List<Double>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (closes.size < 2) {
            // Placeholder — keep the row geometry stable when we don't
            // yet have intraday data (pre-open, or first poll hasn't
            // returned). Render a faint horizontal hairline.
            val y = size.height / 2f
            drawLine(
                color = FinnencerColors.TextTertiary.copy(alpha = 0.35f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
            return@Canvas
        }
        val first = closes.first()
        val last = closes.last()
        val color = when {
            last > first -> FinnencerColors.Mint
            last < first -> FinnencerColors.Coral
            else -> FinnencerColors.TextTertiary
        }
        val min = closes.min()
        val max = closes.max()
        val range = (max - min).takeIf { it > 0.0 } ?: 1.0
        val n = closes.size
        val stepX = if (n > 1) size.width / (n - 1) else 0f
        val pad = 1.dp.toPx()
        val drawableH = size.height - pad * 2

        val path = Path()
        closes.forEachIndexed { i, v ->
            val x = i * stepX
            val yNorm = ((v - min) / range).toFloat() // 0..1, low → high
            val y = pad + (1f - yNorm) * drawableH
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color.copy(alpha = 0.9f),
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
        )
        // Subtle baseline ghost at the prior close (first point of the
        // series, since we feed only today's regular candles): a
        // dotted hairline that helps the user read the move's amplitude.
        val firstYNorm = ((first - min) / range).toFloat()
        val firstY = pad + (1f - firstYNorm) * drawableH
        drawLine(
            color = FinnencerColors.TextTertiary.copy(alpha = 0.25f),
            start = Offset(0f, firstY),
            end = Offset(size.width, firstY),
            strokeWidth = 0.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** Convenience overload exposing the color as a parameter (used by other places that
 *  want to color the line based on something other than the price direction). */
@Composable
fun Sparkline(
    closes: List<Double>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (closes.size < 2) return@Canvas
        val min = closes.min()
        val max = closes.max()
        val range = (max - min).takeIf { it > 0.0 } ?: 1.0
        val n = closes.size
        val stepX = if (n > 1) size.width / (n - 1) else 0f
        val pad = 1.dp.toPx()
        val drawableH = size.height - pad * 2
        val path = Path()
        closes.forEachIndexed { i, v ->
            val x = i * stepX
            val yNorm = ((v - min) / range).toFloat()
            val y = pad + (1f - yNorm) * drawableH
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
