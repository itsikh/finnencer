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
 * The active palette is set by [FinnencerTheme] each composition from
 * the user's [ThemeId] preference; callers should NOT poke
 * [setPalette] directly.
 */
object FinnencerColors {

    private var paletteState by mutableStateOf(Palettes.TerminalPro)

    /** Internal — called by [FinnencerTheme] when the user's pref changes. */
    fun setPalette(p: FinnencerPalette) {
        paletteState = p
    }

    /** Currently-active palette (read-only). */
    val current: FinnencerPalette get() = paletteState

    // ─── Tokens — each is just a forwarding accessor ───
    val Canvas: Color get() = paletteState.canvas
    val Surface: Color get() = paletteState.surface
    val Hairline: Color get() = paletteState.hairline
    val HairlineStrong: Color get() = paletteState.hairlineStrong

    val TextPrimary: Color get() = paletteState.textPrimary
    val TextSecondary: Color get() = paletteState.textSecondary
    val TextTertiary: Color get() = paletteState.textTertiary
    val TextOnAccent: Color get() = paletteState.textOnAccent

    val Violet: Color get() = paletteState.violet
    val VioletDim: Color get() = paletteState.violetDim
    val Mint: Color get() = paletteState.mint
    val Coral: Color get() = paletteState.coral
    val Amber: Color get() = paletteState.amber

    // Semantic aliases.
    val Up: Color get() = paletteState.mint
    val Down: Color get() = paletteState.coral
    val Neutral: Color get() = paletteState.textSecondary

    // Article-importance scoring color bands.
    val Score9to10: Color get() = paletteState.coral
    val Score7to8: Color get() = paletteState.amber
    val Score4to6: Color get() = paletteState.violet
    val Score1to3: Color get() = paletteState.textSecondary

    // ─── Legacy Glass Modern aliases (kept for screens not yet migrated) ───
    val BgTop: Color get() = paletteState.canvas
    val BgBottom: Color get() = paletteState.canvas
    val SurfaceGlass: Color
        get() = paletteState.textPrimary.copy(alpha = 0.06f)
    val SurfaceGlassStrong: Color
        get() = paletteState.textPrimary.copy(alpha = 0.10f)
    val SurfaceBorder: Color get() = paletteState.hairline
    val SurfaceBorderStrong: Color get() = paletteState.hairlineStrong
}
