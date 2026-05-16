package io.itsikh.finnencer.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun dpValue(v: Float): Dp = v.dp

/**
 * Motion design tokens. Glass Modern direction uses springs everywhere except
 * for opacity fades (linear feels cleaner than springy fades).
 */
object Motion {

    /** Default spring for size / position / scale animations. Slight soft overshoot. */
    fun <T> defaultSpring(): SpringSpec<T> = spring(
        dampingRatio = 0.85f,
        stiffness = 380f,
    )

    /** Snappier spring for tap feedback / press scale. */
    fun <T> tapSpring(): SpringSpec<T> = spring(
        dampingRatio = 0.7f,
        stiffness = 600f,
    )

    /** Calm spring for screen transitions / large enter animations. */
    fun <T> screenSpring(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 220f,
    )

    /** Linear fade for opacity changes. */
    fun fade() = tween<Float>(durationMillis = 180)

    /** Slower fade for tooltips / score badges / status pills. */
    fun calmFade() = tween<Float>(durationMillis = 260)
}

object Radii {
    val Card: Dp = dpValue(20f)
    val Chip: Dp = dpValue(12f)
    val Button: Dp = dpValue(14f)
    val Sheet: Dp = dpValue(28f)
}
