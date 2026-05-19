package io.itsikh.finnencer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
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
    Font(googleFont = Inter, fontProvider = FontProvider, weight = FontWeight.Normal),
    Font(googleFont = Inter, fontProvider = FontProvider, weight = FontWeight.Medium),
    Font(googleFont = Inter, fontProvider = FontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = Inter, fontProvider = FontProvider, weight = FontWeight.Bold),
    Font(googleFont = Inter, fontProvider = FontProvider, weight = FontWeight.ExtraBold),
)

val InterDisplayFamily = FontFamily(
    Font(googleFont = Inter, fontProvider = FontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = Inter, fontProvider = FontProvider, weight = FontWeight.Bold),
    Font(googleFont = Inter, fontProvider = FontProvider, weight = FontWeight.ExtraBold),
)

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
