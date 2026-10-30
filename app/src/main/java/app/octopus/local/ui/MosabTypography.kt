package app.octopus.local.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.octopus.local.R

private val MosabArabic = FontFamily(
    Font(R.font.ibm_plex_arabic_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_arabic_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_arabic_semibold, FontWeight.Bold),
    Font(R.font.ibm_plex_arabic_semibold, FontWeight.ExtraBold),
)
fun mosabTypography(): Typography = Typography().let { base -> base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = MosabArabic),
    displayMedium = base.displayMedium.copy(fontFamily = MosabArabic),
    displaySmall = base.displaySmall.copy(fontFamily = MosabArabic),
    headlineLarge = base.headlineLarge.copy(fontFamily = MosabArabic),
    headlineMedium = base.headlineMedium.copy(fontFamily = MosabArabic),
    headlineSmall = base.headlineSmall.copy(fontFamily = MosabArabic),
    titleLarge = base.titleLarge.copy(fontFamily = MosabArabic),
    titleMedium = base.titleMedium.copy(fontFamily = MosabArabic),
    titleSmall = base.titleSmall.copy(fontFamily = MosabArabic),
    bodyLarge = base.bodyLarge.copy(fontFamily = MosabArabic),
    bodyMedium = base.bodyMedium.copy(fontFamily = MosabArabic),
    bodySmall = base.bodySmall.copy(fontFamily = MosabArabic),
    labelLarge = base.labelLarge.copy(fontFamily = MosabArabic),
    labelMedium = base.labelMedium.copy(fontFamily = MosabArabic),
    labelSmall = base.labelSmall.copy(fontFamily = MosabArabic),
) }
