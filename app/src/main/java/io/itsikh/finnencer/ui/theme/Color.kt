package io.itsikh.finnencer.ui.theme

import androidx.compose.ui.graphics.Color

object FinnencerColors {
    // ─── Terminal Pro (current design language) ───
    /** Pure near-black canvas — no gradient. The data on top IS the design. */
    val Canvas = Color(0xFF07080B)
    /** Rare card / sheet fill when we genuinely need a surface (e.g. bottom sheets). */
    val Surface = Color(0xFF0E1016)
    /** 1px row separator — ~11% white. */
    val Hairline = Color(0x1CFFFFFF)
    /** 1px chip / outline accent — ~22% white. */
    val HairlineStrong = Color(0x38FFFFFF)

    /** Top tier text — off-white, never #FFF (too harsh on OLED). */
    val TextPrimary = Color(0xFFE6E8EE)
    /** Body / row sub — lifted from #8B8FA3 to #B8BCCD for readability
     *  on the dense terminal layout. The old shade was technically
     *  AA-compliant but consistently read as "too dim to scan". */
    val TextSecondary = Color(0xFFB8BCCD)
    /** Captions, dim meta, dividers in text — dimmer cool gray. */
    val TextTertiary = Color(0xFF7A7F94)
    /** Text on a violet/accent fill — pure black for max contrast. */
    val TextOnAccent = Color(0xFF07080B)

    // Accents — saturated enough to read at small sizes against near-black.
    val Violet = Color(0xFFA78BFA)
    val VioletDim = Color(0xFF6D5BD0)
    val Mint = Color(0xFF4ADE80)
    val Coral = Color(0xFFF87171)
    val Amber = Color(0xFFFBBF24)

    // Up/down semantic aliases — used by quote rendering.
    val Up = Mint
    val Down = Coral
    val Neutral = TextSecondary

    // Article-importance scoring color bands.
    val Score9to10 = Coral
    val Score7to8 = Amber
    val Score4to6 = Violet
    val Score1to3 = Neutral

    // ─── Legacy Glass Modern (kept for screens not yet migrated) ───
    val BgTop = Canvas
    val BgBottom = Canvas
    val SurfaceGlass = Color(0x10FFFFFF)
    val SurfaceGlassStrong = Color(0x1AFFFFFF)
    val SurfaceBorder = Hairline
    val SurfaceBorderStrong = HairlineStrong
}
