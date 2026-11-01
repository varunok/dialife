package com.lumenarcanus.dialife.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.core.ui.theme.GlucoseHero
import com.lumenarcanus.dialife.data.DoseCalc
import java.util.Locale

@Composable
fun Stepper(value: Double, step: Double, unit: String, onChange: (Double) -> Unit) {
    val c = DiaTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    Row(
        Modifier.fillMaxWidth().background(c.insulinContainer, RoundedCornerShape(18.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val minus = stringResource(R.string.tr_less)
        val plus = stringResource(R.string.tr_more)
        FilledTonalIconButton(
            onClick = {
                onChange((Math.round((value - step) * 10) / 10.0).coerceAtLeast(0.0))
            },
            modifier = Modifier.size(52.dp).semantics {
                contentDescription = minus
            }
        ) {
            Text("−", fontSize = 26.sp, color = c.insulin)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            Text(formatAmount(value, locale), style = GlucoseHero.copy(fontSize = 48.sp, lineHeight = 52.sp, color = c.text))
            Text(" $unit", color = c.textMuted, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        FilledTonalIconButton(
            onClick = {
                onChange((Math.round((value + step) * 10) / 10.0).coerceAtMost(200.0))
            },
            modifier = Modifier.size(52.dp).semantics {
                contentDescription = plus
            }
        ) {
            Text("+", fontSize = 26.sp, color = c.insulin)
        }
    }
}

/** The dose limits' card: red over the limit, red "a mistake?" over twice it. */
@Composable
fun LimitAlert(level: DoseCalc.Level, dose: Double, max: Double, units: Units, locale: Locale) {
    when (level) {
        DoseCalc.Level.Absurd -> AlertCard(DoseCalc.Alert.DoseAbsurd(dose, max), units, locale)
        DoseCalc.Level.High -> AlertCard(DoseCalc.Alert.DoseHigh(dose, max), units, locale)
        else -> {}
    }
}

/** The extra tap before a dose over the limit. */
@Composable
fun Confirm(on: Boolean, text: String, onChange: (Boolean) -> Unit) {
    val c = DiaTheme.colors
    Row(
        Modifier.fillMaxWidth().border(1.5.dp, c.low, RoundedCornerShape(14.dp)).clickable { onChange(!on) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = on, onCheckedChange = onChange)
        Text(text, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
