package io.itsikh.finnencer.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A single color theme for the Glass Modern look. Every screen reads
 * tokens via [FinnencerColors] which forwards through a
 * `mutableStateOf<FinnencerPalette>` so changing the active palette
 * automatically recomposes every observer — no per-screen rewiring.
 *
 * Token shape preserves Glass Modern's existing API:
 *  - [bgTop] / [bgBottom] are the two endpoints of the radial gradient
 *    that paints the canvas via [appBackgroundBrush]
 *  - [surface] is the rare solid card fill (e.g. opaque bottom-sheet
 *    backdrops)
 *  - [surfaceGlass] / [surfaceGlassStrong] are the alpha-white overlays
 *    used for glass cards — kept as alpha-white across themes so the
 *    underlying bg tints them organically
 *  - [surfaceBorder] / [surfaceBorderStrong] are the alpha-white card
 *    outlines
 *  - [accent] / [accentDim] replace the Violet/VioletDim pair — the
 *    primary action color
 *  - [up] / [down] are the semantic mint/coral pair. Stay green/red
 *    across all themes so the watchlist signal language never changes
 *  - [amber] is the third accent, used for warnings / mid-tier scores
 *  - Text tiers ([textPrimary] / [textSecondary] / [textTertiary]) are
 *    tuned to the bg's color temperature
 */
data class FinnencerPalette(
    val id: ThemeId,
    val displayName: String,
    val description: String,

    // Gradient endpoints
    val bgTop: Color,
    val bgBottom: Color,
    val surface: Color,

    // Glass overlays (alpha-white in all themes — let the bg tint them)
    val surfaceGlass: Color,
    val surfaceGlassStrong: Color,
    val surfaceBorder: Color,
    val surfaceBorderStrong: Color,

    // Accents
    val accent: Color,
    val accentDim: Color,
    val up: Color,
    val down: Color,
    val amber: Color,

    // Text tiers
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textOnAccent: Color,
)

/** Display order = picker order in Settings. */
enum class ThemeId {
    MIDNIGHT_VIOLET,
    FOREST_CALM,
    OCEAN_DEEP,
    WARM_PAPER,
    SLATE_STEEL,
    TWILIGHT_ROSE,
}

/**
 * The six bundled Glass Modern palettes. Designed around four
 * eye-comfort rules:
 *  1. No pure `#000` — every bg has a slight color cast for OLED safety
 *  2. Body text capped at ~85% white luminance (off-white, never #FFF)
 *  3. Saturation is reserved for the up/down semantics; accent colors
 *     are moderately saturated
 *  4. Each palette commits to one temperature (warm or cool) so colors
 *     read as "from the same family"
 */
object Palettes {

    /** Default — the original Glass Modern look. Cool, late-night ops. */
    val MidnightViolet = FinnencerPalette(
        id = ThemeId.MIDNIGHT_VIOLET,
        displayName = "Midnight Violet",
        description = "Cool deep navy with lavender accents. The original look.",
        bgTop = Color(0xFF050818),
        bgBottom = Color(0xFF0C1535),
        surface = Color(0xFF0F1A36),
        surfaceGlass = Color(0x10FFFFFF),
        surfaceGlassStrong = Color(0x1AFFFFFF),
        surfaceBorder = Color(0x1AFFFFFF),
        surfaceBorderStrong = Color(0x2EFFFFFF),
        accent = Color(0xFFA78BFA),
        accentDim = Color(0xFF6D5BD0),
        up = Color(0xFF34D399),
        down = Color(0xFFFB7185),
        amber = Color(0xFFFBBF24),
        textPrimary = Color(0xFFF5F7FA),
        textSecondary = Color(0xCCBFC6DB),
        textTertiary = Color(0x996B7592),
        textOnAccent = Color(0xFF0A0F1F),
    )

    /** Forest-at-dusk. Warm pine bg, copper accent, softer up/down. */
    val ForestCalm = FinnencerPalette(
        id = ThemeId.FOREST_CALM,
        displayName = "Forest Calm",
        description = "Warm forest greens with copper accents. Easy on the eyes for long evening reads.",
        bgTop = Color(0xFF0D1F17),
        bgBottom = Color(0xFF163228),
        surface = Color(0xFF1B3429),
        surfaceGlass = Color(0x12FFFFFF),
        surfaceGlassStrong = Color(0x1FFFFFFF),
        surfaceBorder = Color(0x1FFFFFFF),
        surfaceBorderStrong = Color(0x33FFFFFF),
        accent = Color(0xFFD97757),
        accentDim = Color(0xFFB85F40),
        up = Color(0xFF6FCB7B),
        down = Color(0xFFE78A8A),
        amber = Color(0xFFD9B26E),
        textPrimary = Color(0xFFEFE9DA),
        textSecondary = Color(0xCCB5BBAC),
        textTertiary = Color(0x99868B7C),
        textOnAccent = Color(0xFF0D1F17),
    )

    /** Night dive. Cool ocean bg, cyan-teal accent, watchlist coral kept. */
    val OceanDeep = FinnencerPalette(
        id = ThemeId.OCEAN_DEEP,
        displayName = "Ocean Deep",
        description = "Cool deep ocean with cyan accents. Professional, focused.",
        bgTop = Color(0xFF061722),
        bgBottom = Color(0xFF0E2638),
        surface = Color(0xFF102E40),
        surfaceGlass = Color(0x10FFFFFF),
        surfaceGlassStrong = Color(0x1AFFFFFF),
        surfaceBorder = Color(0x1FFFFFFF),
        surfaceBorderStrong = Color(0x33FFFFFF),
        accent = Color(0xFF5EEAD4),
        accentDim = Color(0xFF3FB8A4),
        up = Color(0xFF4ADE80),
        down = Color(0xFFFB7185),
        amber = Color(0xFFF59E0B),
        textPrimary = Color(0xFFDCE6F0),
        textSecondary = Color(0xCC97AAC2),
        textTertiary = Color(0x995E7185),
        textOnAccent = Color(0xFF061722),
    )

    /** Sepia-tinted dark with terracotta. Warmest of the bunch. */
    val WarmPaper = FinnencerPalette(
        id = ThemeId.WARM_PAPER,
        displayName = "Warm Paper",
        description = "Sepia-tinted dark with terracotta accents. Reduces blue-light fatigue for evening sessions.",
        bgTop = Color(0xFF1A1410),
        bgBottom = Color(0xFF2A2017),
        surface = Color(0xFF2F2418),
        surfaceGlass = Color(0x14FFFFFF),
        surfaceGlassStrong = Color(0x22FFFFFF),
        surfaceBorder = Color(0x22FFFFFF),
        surfaceBorderStrong = Color(0x38FFFFFF),
        accent = Color(0xFFE8A87C),
        accentDim = Color(0xFFC7825A),
        up = Color(0xFF95C780),
        down = Color(0xFFD86F6F),
        amber = Color(0xFFE5B870),
        textPrimary = Color(0xFFF0E8D8),
        textSecondary = Color(0xCCC2B5A0),
        textTertiary = Color(0x998C7F6A),
        textOnAccent = Color(0xFF1A1410),
    )

    /** Cool industrial charcoal with steel-blue accents. Most businesslike. */
    val SlateSteel = FinnencerPalette(
        id = ThemeId.SLATE_STEEL,
        displayName = "Slate Steel",
        description = "Cool industrial charcoal with steel-blue accents. Minimal and businesslike.",
        bgTop = Color(0xFF0A0E14),
        bgBottom = Color(0xFF161D29),
        surface = Color(0xFF1A2330),
        surfaceGlass = Color(0x10FFFFFF),
        surfaceGlassStrong = Color(0x1AFFFFFF),
        surfaceBorder = Color(0x1AFFFFFF),
        surfaceBorderStrong = Color(0x2EFFFFFF),
        accent = Color(0xFF8B9BBA),
        accentDim = Color(0xFF5F6D8A),
        up = Color(0xFF4ADE80),
        down = Color(0xFFF87171),
        amber = Color(0xFFFBBF24),
        textPrimary = Color(0xFFE6E8EE),
        textSecondary = Color(0xCCA6B0C2),
        textTertiary = Color(0x996F7A8E),
        textOnAccent = Color(0xFF0A0E14),
    )

    /** Late-evening lounge. Plum bg, mauve accent, soft sage/rose semantics. */
    val TwilightRose = FinnencerPalette(
        id = ThemeId.TWILIGHT_ROSE,
        displayName = "Twilight Rose",
        description = "Warm plum canvas with mauve accents and softer sage/rose semantics.",
        bgTop = Color(0xFF1A0F1F),
        bgBottom = Color(0xFF2A1A30),
        surface = Color(0xFF2F1F35),
        surfaceGlass = Color(0x14FFFFFF),
        surfaceGlassStrong = Color(0x22FFFFFF),
        surfaceBorder = Color(0x22FFFFFF),
        surfaceBorderStrong = Color(0x38FFFFFF),
        accent = Color(0xFFC7A9F0),
        accentDim = Color(0xFF9F7DC6),
        up = Color(0xFFB5DBBC),
        down = Color(0xFFF0A0B0),
        amber = Color(0xFFE5C28A),
        textPrimary = Color(0xFFEFE0F0),
        textSecondary = Color(0xCCB8A5C2),
        textTertiary = Color(0x998B7B95),
        textOnAccent = Color(0xFF1A0F1F),
    )

    val all: List<FinnencerPalette> = listOf(
        MidnightViolet, ForestCalm, OceanDeep, WarmPaper, SlateSteel, TwilightRose,
    )

    fun byId(id: ThemeId): FinnencerPalette = when (id) {
        ThemeId.MIDNIGHT_VIOLET -> MidnightViolet
        ThemeId.FOREST_CALM -> ForestCalm
        ThemeId.OCEAN_DEEP -> OceanDeep
        ThemeId.WARM_PAPER -> WarmPaper
        ThemeId.SLATE_STEEL -> SlateSteel
        ThemeId.TWILIGHT_ROSE -> TwilightRose
    }
}
