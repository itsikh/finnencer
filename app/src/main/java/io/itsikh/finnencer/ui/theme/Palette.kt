package io.itsikh.finnencer.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A single color theme — every screen consumes its tokens via
 * [FinnencerColors] which forwards reads to the currently-selected
 * palette. Adding a new theme is just defining one more
 * [FinnencerPalette] constant.
 */
data class FinnencerPalette(
    val id: ThemeId,
    val displayName: String,
    val isLight: Boolean,

    val canvas: Color,
    val surface: Color,
    val hairline: Color,
    val hairlineStrong: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textOnAccent: Color,

    val violet: Color,
    val violetDim: Color,
    val mint: Color,
    val coral: Color,
    val amber: Color,
)

/**
 * The set of palettes the user can choose from. Order in this enum
 * is the display order in the picker.
 */
enum class ThemeId {
    TERMINAL_PRO,
    SOLARIZED_NIGHT,
    CARBON_SLATE,
    TOKYO_NIGHT,
    PAPER_LIGHT,
}

/** Bundled palettes. Each tuned to be eye-comfortable on phones. */
object Palettes {

    /** Default. Near-black canvas, mint/coral semantics, violet accent. */
    val TerminalPro = FinnencerPalette(
        id = ThemeId.TERMINAL_PRO,
        displayName = "Terminal Pro",
        isLight = false,
        canvas = Color(0xFF07080B),
        surface = Color(0xFF0E1016),
        hairline = Color(0x1CFFFFFF),
        hairlineStrong = Color(0x38FFFFFF),
        textPrimary = Color(0xFFE6E8EE),
        textSecondary = Color(0xFFB8BCCD),
        textTertiary = Color(0xFF7A7F94),
        textOnAccent = Color(0xFF07080B),
        violet = Color(0xFFA78BFA),
        violetDim = Color(0xFF6D5BD0),
        mint = Color(0xFF4ADE80),
        coral = Color(0xFFF87171),
        amber = Color(0xFFFBBF24),
    )

    /** Warm dark navy with cyan accents. Low-fatigue for long reads. */
    val SolarizedNight = FinnencerPalette(
        id = ThemeId.SOLARIZED_NIGHT,
        displayName = "Solarized Night",
        isLight = false,
        canvas = Color(0xFF002B36),
        surface = Color(0xFF073642),
        hairline = Color(0x33EEE8D5),
        hairlineStrong = Color(0x66EEE8D5),
        textPrimary = Color(0xFFFDF6E3),
        textSecondary = Color(0xFF93A1A1),
        textTertiary = Color(0xFF657B83),
        textOnAccent = Color(0xFF002B36),
        violet = Color(0xFF2AA198),       // cyan (replaces violet semantic)
        violetDim = Color(0xFF268BD2),    // blue
        mint = Color(0xFF859900),         // olive green
        coral = Color(0xFFDC322F),        // red
        amber = Color(0xFFB58900),        // warm gold
    )

    /** True-dark Bloomberg-y palette with a single amber accent. */
    val CarbonSlate = FinnencerPalette(
        id = ThemeId.CARBON_SLATE,
        displayName = "Carbon Slate",
        isLight = false,
        canvas = Color(0xFF0B0E14),
        surface = Color(0xFF161B22),
        hairline = Color(0x26FFFFFF),
        hairlineStrong = Color(0x4DFFFFFF),
        textPrimary = Color(0xFFF2F4F8),
        textSecondary = Color(0xFFC9CFDB),
        textTertiary = Color(0xFF7C8497),
        textOnAccent = Color(0xFF0B0E14),
        violet = Color(0xFFF59E0B),       // amber (no violet)
        violetDim = Color(0xFFB45309),
        mint = Color(0xFF22C55E),
        coral = Color(0xFFEF4444),
        amber = Color(0xFFF59E0B),
    )

    /** Indigo-tinted dark, lilac/rose accents. Editor-theme aesthetic. */
    val TokyoNight = FinnencerPalette(
        id = ThemeId.TOKYO_NIGHT,
        displayName = "Tokyo Night",
        isLight = false,
        canvas = Color(0xFF1A1B26),
        surface = Color(0xFF24283B),
        hairline = Color(0x33C0CAF5),
        hairlineStrong = Color(0x66C0CAF5),
        textPrimary = Color(0xFFC0CAF5),
        textSecondary = Color(0xFF9AA5CE),
        textTertiary = Color(0xFF565F89),
        textOnAccent = Color(0xFF1A1B26),
        violet = Color(0xFFBB9AF7),       // lilac
        violetDim = Color(0xFF7AA2F7),    // blue
        mint = Color(0xFF9ECE6A),         // apple green
        coral = Color(0xFFF7768E),        // rose
        amber = Color(0xFFE0AF68),        // honey
    )

    /** Off-white canvas, dark ink — the only light option. */
    val PaperLight = FinnencerPalette(
        id = ThemeId.PAPER_LIGHT,
        displayName = "Paper Light",
        isLight = true,
        canvas = Color(0xFFF7F7F5),
        surface = Color(0xFFFFFFFF),
        hairline = Color(0x1F000000),
        hairlineStrong = Color(0x33000000),
        textPrimary = Color(0xFF1A1C24),
        textSecondary = Color(0xFF4B5563),
        textTertiary = Color(0xFF6B7280),
        textOnAccent = Color(0xFFFFFFFF),
        violet = Color(0xFF7C3AED),
        violetDim = Color(0xFF5B21B6),
        mint = Color(0xFF16A34A),
        coral = Color(0xFFDC2626),
        amber = Color(0xFFD97706),
    )

    val all: List<FinnencerPalette> = listOf(TerminalPro, SolarizedNight, CarbonSlate, TokyoNight, PaperLight)

    fun byId(id: ThemeId): FinnencerPalette = when (id) {
        ThemeId.TERMINAL_PRO -> TerminalPro
        ThemeId.SOLARIZED_NIGHT -> SolarizedNight
        ThemeId.CARBON_SLATE -> CarbonSlate
        ThemeId.TOKYO_NIGHT -> TokyoNight
        ThemeId.PAPER_LIGHT -> PaperLight
    }
}
