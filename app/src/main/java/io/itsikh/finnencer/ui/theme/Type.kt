package io.itsikh.finnencer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.itsikh.finnencer.R

private val FontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val Inter = GoogleFont("Inter")

val InterFamily = FontFamily(
    androidx.compose.ui.text.googlefonts.Font(googleFont = Inter, fontProvider = FontProvider, weight = FontWeight.Normal),
    androidx.compose.ui.text.googlefonts.Font(googleFont = Inter, fontProvider = FontProvider, weight = FontWeight.Medium),
    androidx.compose.ui.text.googlefonts.Font(googleFont = Inter, fontProvider = FontProvider, weight = FontWeight.SemiBold),
    androidx.compose.ui.text.googlefonts.Font(googleFont = Inter, fontProvider = FontProvider, weight = FontWeight.Bold),
    androidx.compose.ui.text.googlefonts.Font(googleFont = Inter, fontProvider = FontProvider, weight = FontWeight.ExtraBold),
)

val InterDisplayFamily = FontFamily(
    androidx.compose.ui.text.googlefonts.Font(googleFont = Inter, fontProvider = FontProvider, weight = FontWeight.SemiBold),
    androidx.compose.ui.text.googlefonts.Font(googleFont = Inter, fontProvider = FontProvider, weight = FontWeight.Bold),
    androidx.compose.ui.text.googlefonts.Font(googleFont = Inter, fontProvider = FontProvider, weight = FontWeight.ExtraBold),
)

/**
 * JetBrains Mono — bundled in res/font, no runtime fetch. Used for any
 * number, ticker symbol, tag, or terminal-style label. Tabular figures
 * are enabled via the `tnum` OpenType feature so digits never jitter
 * column width as prices tick.
 */
val MonoFamily = FontFamily(
    Font(R.font.jetbrains_mono, weight = FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, weight = FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, weight = FontWeight.Bold),
)

/**
 * Project-level mono text style families — not Material types, but used
 * across the watchlist row, top bar, chips, and any digit cluster. They
 * are referenced explicitly (e.g. `MonoStyles.Price`) rather than
 * through `MaterialTheme.typography`.
 */
object MonoStyles {
    /** Big price digit — 22sp tabular, medium weight. Watchlist price column. */
    val Price = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 1.05.em,
        fontFeatureSettings = "tnum",
    )

    /** Bold sign-prefixed percent change — Mint or Coral. */
    val Pct = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 1.1.em,
        letterSpacing = 0.01.em,
        fontFeatureSettings = "tnum",
    )

    /** Ticker symbol — bold, slightly tracked. */
    val Ticker = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 1.1.em,
        letterSpacing = 0.04.em,
    )

    /** Caps tracked nav label / brand mark / section header. */
    val NavLabel = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 1.2.em,
        letterSpacing = 0.10.em,
    )

    /** Brand mark — biggest tracked caps on top bar. Sized to fit
     *  next to 5 nav chips on a phone-narrow top bar without
     *  wrapping. */
    val Brand = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 1.1.em,
        letterSpacing = 0.10.em,
    )

    /** Tiny tracked caption — "5 TRACKED · 21:35:04 EDT". */
    val BrandSub = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 1.2.em,
        letterSpacing = 0.18.em,
    )

    /** Section header above grouped rows — uppercase tracked. */
    val SectionHead = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 1.2.em,
        letterSpacing = 0.20.em,
    )

    /** Bordered chip text (sector, ALR n, ON/OFF). */
    val Chip = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 1.2.em,
        letterSpacing = 0.10.em,
    )
}

val FinnencerTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = InterDisplayFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 36.sp,
        lineHeight = 1.1.em,
        letterSpacing = (-0.02).em,
    ),
    displayMedium = TextStyle(
        fontFamily = InterDisplayFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 1.15.em,
        letterSpacing = (-0.015).em,
    ),
    displaySmall = TextStyle(
        fontFamily = InterDisplayFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 1.2.em,
        letterSpacing = (-0.01).em,
    ),
    headlineLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 1.25.em,
    ),
    headlineMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 1.3.em,
    ),
    headlineSmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 1.35.em,
    ),
    titleLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 1.4.em,
    ),
    titleMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 1.4.em,
    ),
    titleSmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 1.4.em,
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 1.55.em,
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 1.5.em,
    ),
    bodySmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 1.45.em,
    ),
    labelLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 1.3.em,
        letterSpacing = 0.01.em,
    ),
    labelMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 1.3.em,
        letterSpacing = 0.02.em,
    ),
    labelSmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 1.3.em,
        letterSpacing = 0.06.em,
        fontStyle = FontStyle.Normal,
    ),
)
