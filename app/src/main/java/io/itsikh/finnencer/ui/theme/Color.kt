package io.itsikh.finnencer.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Project-wide color tokens. Every screen reads via `FinnencerColors.X`
 * — those accessors forward through a [mutableStateOf]-backed
 * [FinnencerPalette] so any composable that reads them is
 * automatically observed by Compose and recomposes when the user
 * picks a new theme. No call-site migration needed.
 *
 * The active palette is set by [FinnencerTheme] from the user's
 * [ThemeId] preference; callers should NOT poke [setPalette]
 * directly.
 */
object FinnencerColors {

    private var paletteState by mutableStateOf(Palettes.MidnightViolet)

    /** Internal — called by [FinnencerTheme] when the user's pref changes. */
    fun setPalette(p: FinnencerPalette) {
        paletteState = p
    }

    /** Read-only handle on the active palette. */
    val current: FinnencerPalette get() = paletteState

    // ─── Glass Modern tokens — each just a forwarding accessor ───
    val BgTop: Color get() = paletteState.bgTop
    val BgBottom: Color get() = paletteState.bgBottom
    val Surface: Color get() = paletteState.surface
    val SurfaceGlass: Color get() = paletteState.surfaceGlass
    val SurfaceGlassStrong: Color get() = paletteState.surfaceGlassStrong
    val SurfaceBorder: Color get() = paletteState.surfaceBorder
    val SurfaceBorderStrong: Color get() = paletteState.surfaceBorderStrong

    val Violet: Color get() = paletteState.accent
    val VioletDim: Color get() = paletteState.accentDim
    val Mint: Color get() = paletteState.up
    val Coral: Color get() = paletteState.down
    val Amber: Color get() = paletteState.amber

    val TextPrimary: Color get() = paletteState.textPrimary
    val TextSecondary: Color get() = paletteState.textSecondary
    val TextTertiary: Color get() = paletteState.textTertiary
    val TextOnAccent: Color get() = paletteState.textOnAccent

    val Up: Color get() = paletteState.up
    val Down: Color get() = paletteState.down
    val Neutral: Color get() = paletteState.textSecondary

    val Score9to10: Color get() = paletteState.down
    val Score7to8: Color get() = paletteState.amber
    val Score4to6: Color get() = paletteState.accent
    val Score1to3: Color get() = paletteState.textSecondary
}
