package com.lumenarcanus.dialife.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Design tokens from the approved mockups ("calm instrument").
 * Glucose states differ in lightness as well as hue so they stay
 * distinguishable half-asleep and with colour-vision deficiency.
 */
@Immutable
data class DiaColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val line: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val inRange: Color,
    val inRangeContainer: Color,
    val onInRangeContainer: Color,
    val high: Color,
    val highContainer: Color,
    val low: Color,
    val lowContainer: Color,
    val urgent: Color,
    val urgentBackground: Color,
    val insulin: Color,
    val insulinContainer: Color,
    val carbs: Color,
    val carbsContainer: Color,
    val isDark: Boolean,
)

val DarkDiaColors = DiaColors(
    background = Color(0xFF0B0E13),
    surface = Color(0xFF121720),
    surfaceRaised = Color(0xFF1B212C),
    line = Color(0xFF1C2330),
    text = Color(0xFFECEFF4),
    textMuted = Color(0xFF8B95A7),
    textFaint = Color(0xFF6B7586),
    inRange = Color(0xFF4FD1C5),
    inRangeContainer = Color(0xFF12241F),
    onInRangeContainer = Color(0xFF6EE7D8),
    high = Color(0xFFF5A524),
    highContainer = Color(0xFF2A1E0A),
    low = Color(0xFFFF5C7A),
    lowContainer = Color(0xFF2A0D17),
    urgent = Color(0xFFFF3B5C),
    urgentBackground = Color(0xFF22050D),
    insulin = Color(0xFFC4B5FD),
    insulinContainer = Color(0xFF2B2147),
    carbs = Color(0xFFF5C46B),
    carbsContainer = Color(0xFF3A2A12),
    isDark = true,
)

val LightDiaColors = DiaColors(
    background = Color(0xFFF4F6F9),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFEEF1F5),
    line = Color(0xFFE1E6ED),
    text = Color(0xFF0F1622),
    textMuted = Color(0xFF5B6577),
    textFaint = Color(0xFF5B6577),
    inRange = Color(0xFF0B7D73),
    inRangeContainer = Color(0xFFDDF3F0),
    onInRangeContainer = Color(0xFF075E57),
    high = Color(0xFFB86E00),
    highContainer = Color(0xFFFDF3E2),
    low = Color(0xFFD12E52),
    lowContainer = Color(0xFFFCE4EA),
    urgent = Color(0xFFD12E52),
    urgentBackground = Color(0xFF22050D),
    insulin = Color(0xFF4A2FC0),
    insulinContainer = Color(0xFFF1EDFF),
    carbs = Color(0xFF7A4800),
    carbsContainer = Color(0xFFFDF3E2),
    isDark = false,
)

val LocalDiaColors = staticCompositionLocalOf { DarkDiaColors }
