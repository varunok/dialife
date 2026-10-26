package com.lumenarcanus.dialife.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R

// Onest is one variable font file; each weight is a point on its axis.
@OptIn(ExperimentalTextApi::class)
private fun onest(weight: Int) = Font(
    R.font.onest,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Onest = FontFamily(onest(300), onest(400), onest(500), onest(600), onest(700))

val PlexMono = FontFamily(
    Font(R.font.plexmono_regular, FontWeight.Normal),
    Font(R.font.plexmono_medium, FontWeight.Medium),
)

/** The hero glucose number: thin, tight, tabular so digits never jump. */
val GlucoseHero = TextStyle(
    fontFamily = Onest,
    fontWeight = FontWeight.Light,
    fontSize = 120.sp,
    lineHeight = 104.sp,
    letterSpacing = (-0.045).em,
    fontFeatureSettings = "tnum",
)

/** Small numbers, times and axis labels. */
val DataLabel = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 13.sp)

val DiaTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Onest),
        displayMedium = base.displayMedium.copy(fontFamily = Onest),
        displaySmall = base.displaySmall.copy(fontFamily = Onest),
        headlineLarge = base.headlineLarge.copy(fontFamily = Onest, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = Onest, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = Onest, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = Onest, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = Onest, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = Onest, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = Onest),
        bodyMedium = base.bodyMedium.copy(fontFamily = Onest),
        bodySmall = base.bodySmall.copy(fontFamily = Onest),
        labelLarge = base.labelLarge.copy(fontFamily = Onest, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontFamily = Onest, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontFamily = Onest, fontWeight = FontWeight.Medium),
    )
}
