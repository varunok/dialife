package com.lumenarcanus.dialife.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

@Composable
fun DiaLifeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val dia = if (dark) DarkDiaColors else LightDiaColors
    // Material components read the scheme; our screens read DiaTheme.colors directly.
    val scheme = if (dark) {
        darkColorScheme(
            primary = dia.inRange, background = dia.background, surface = dia.surface,
            onBackground = dia.text, onSurface = dia.text, outline = dia.line, error = dia.low,
        )
    } else {
        lightColorScheme(
            primary = dia.inRange, background = dia.background, surface = dia.surface,
            onBackground = dia.text, onSurface = dia.text, outline = dia.line, error = dia.low,
        )
    }
    CompositionLocalProvider(LocalDiaColors provides dia) {
        MaterialTheme(colorScheme = scheme, typography = DiaTypography, content = content)
    }
}

object DiaTheme {
    val colors: DiaColors
        @Composable @ReadOnlyComposable get() = LocalDiaColors.current
}
